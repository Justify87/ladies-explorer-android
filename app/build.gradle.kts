import javax.inject.Inject
import java.security.MessageDigest
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Adapted from Artefact's cargo-ndk and UniFFI variant build integration.
// The resulting directories are Gradle outputs, never checked-in source files.
val rustWorkspace: File = rootProject.file("rust")
val rustAbis: List<String> = providers.gradleProperty("ladies.abis")
    .getOrElse("arm64-v8a,x86_64")
    .split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()

require(rustAbis.isNotEmpty() && rustAbis.all { it in setOf("arm64-v8a", "x86_64") }) {
    "Select supported targets (arm64-v8a, x86_64) in ladies.abis"
}

val cargoExecutable: String = providers.environmentVariable("CARGO").orNull ?: run {
    val filename = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "cargo.exe" else "cargo"
    val rustup = File(System.getProperty("user.home"), ".cargo/bin/" + filename)
    if (rustup.isFile) rustup.absolutePath else filename
}


// CI02: only cache task outputs in the known hosted Linux environment. Outside
// it Cargo remains the authority (including developer toolchain overrides).
// No ambient RUSTFLAGS, CC, wrappers, target-dir, or SDK overrides enter cached builds.
val nativeCacheRequested = providers.gradleProperty("ladies.nativeCache").orNull == "true"
val userHome = System.getProperty("user.home")
val cargoHome = System.getenv("CARGO_HOME") ?: File(userHome, ".cargo").absolutePath
val rustupHome = System.getenv("RUSTUP_HOME") ?: File(userHome, ".rustup").absolutePath
val cargoConfigDirectories = generateSequence(rustWorkspace) { it.parentFile }
    .map { it.resolve(".cargo") }.toList() + File(cargoHome)
val externalCargoConfig = cargoConfigDirectories.any {
    it.resolve("config").exists() || it.resolve("config.toml").exists()
}
val runnerImage = System.getenv("ImageVersion").orEmpty()
val nativeCacheEnabled = nativeCacheRequested &&
    System.getenv("GITHUB_ACTIONS") == "true" &&
    System.getenv("RUNNER_OS") == "Linux" &&
    System.getenv("RUNNER_ARCH") == "X64" &&
    runnerImage.isNotBlank() && !externalCargoConfig
if (nativeCacheRequested && !nativeCacheEnabled) {
    logger.lifecycle("CI02: native task output cache disabled (unverified host or Cargo config); using Cargo directly")
}

// These values are both the actual subprocess environment and cache inputs.
// Host paths intentionally partition caches by tool installation, not checkout.
val nativeEnvironment = if (nativeCacheEnabled) mapOf(
    "PATH" to requireNotNull(System.getenv("PATH")),
    "HOME" to userHome,
    "CARGO_HOME" to cargoHome,
    "RUSTUP_HOME" to rustupHome,
    "CARGO_INCREMENTAL" to "0",
    "LANG" to "C.UTF-8",
    "LC_ALL" to "C.UTF-8",
    "TZ" to "UTC",
) else emptyMap()

fun nativeToolVersion(vararg command: String): Provider<String> =
    providers.exec {
        workingDir(rustWorkspace)
        commandLine(command.toList())
        setEnvironment(nativeEnvironment)
    }.standardOutput.asText.map { it.trim() }

val nativeIdentity = if (nativeCacheEnabled) {
    val cargoVersion = nativeToolVersion(cargoExecutable, "--version", "--verbose")
    val rustVersion = nativeToolVersion("rustc", "--version", "--verbose")
    val ndkVersion = nativeToolVersion(cargoExecutable, "ndk", "--version")
    val targets = nativeToolVersion("rustup", "target", "list", "--installed")
    providers.provider {
        listOf(runnerImage, System.getProperty("os.name"), System.getProperty("os.arch"),
            cargoVersion.get(), rustVersion.get(), ndkVersion.get(), targets.get()).joinToString("\n")
    }
} else providers.provider { "uncontrolled Cargo environment" }

abstract class NativeCargoTask : DefaultTask() {
    @get:Inject abstract val executors: ExecOperations
    @get:Input abstract val cargo: Property<String>
    @get:Internal abstract val workspace: DirectoryProperty
    @get:Input abstract val controlled: Property<Boolean>
    @get:Input abstract val toolIdentity: Property<String>
    @get:Input abstract val toolEnvironment: MapProperty<String, String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) @get:IgnoreEmptyDirectories
    abstract val sources: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    init {
        // No stale Gradle UP-TO-DATE result when local tools/config can vary.
        // Cargo still performs its own normal dependency/fingerprint reuse.
        outputs.upToDateWhen { controlled.get() }
        outputs.doNotCacheIf("Native environment is not controlled") { !controlled.get() }
    }

    protected fun freshOutput(): File = outputDir.get().asFile.also {
        check(!it.exists() || it.deleteRecursively()) { "Cannot remove stale output: $it" }
        check(it.mkdirs() || it.isDirectory) { "Cannot create native output: $it" }
    }

    protected fun runCargo(arguments: List<String>, ndk: File? = null, extraIdentity: String = "") {
        val root = workspace.get().asFile
        executors.exec {
            workingDir(root)
            if (controlled.get()) {
                setEnvironment(toolEnvironment.get())
                // Force Cargo fingerprints to change when the hosted toolchain
                // changes, even if restored target artifacts are present.
                val identity = (toolIdentity.get() + "\n" +
                    toolEnvironment.get().toSortedMap().entries.joinToString("\n") + "\n" +
                    extraIdentity).toByteArray(Charsets.UTF_8)
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity).joinToString("") { "%02x".format(it) }
                environment("CARGO_ENCODED_RUSTFLAGS", listOf(
                    "--remap-path-prefix=" + root.absolutePath + "=/ladies/rust",
                    "--cfg=ladies_native_toolchain=\"" + digest + "\"",
                ).joinToString("\u001f"))
                environment("CARGO", cargo.get())
            }
            if (ndk != null) environment("ANDROID_NDK_HOME", ndk.absolutePath)
            commandLine(listOf(cargo.get()) + arguments)
        }
    }
}

@CacheableTask
abstract class BuildRustForAndroid : NativeCargoTask() {
    // Absolute NDK install location partitions toolchains, not source roots.
    @get:Input abstract val ndkDirectory: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val ndkMetadata: RegularFileProperty
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val minApi: Property<Int>

    @TaskAction
    fun compile() {
        val output = freshOutput()
        val ndk = File(ndkDirectory.get())
        runCargo(
            listOf("ndk") + abis.get().flatMap { listOf("-t", it) } +
                listOf("--platform", minApi.get().toString(), "-o", output.absolutePath,
                    "build", "--release", "--locked", "-p", "ladies-core", "--lib"),
            ndk, ndk.absolutePath + "\n" + ndkMetadata.get().asFile.readText(),
        )
        check(abis.get().all { output.resolve("$it/libladies_core.so").let { f -> f.isFile && f.length() > 0 } }) {
            "Missing compiled Rust library for one or more selected ABIs"
        }
    }
}

@CacheableTask
abstract class GenerateUniffiKotlin : NativeCargoTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val libraryFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val output = freshOutput()
        runCargo(listOf("run", "--quiet", "--locked", "-p", "ladies-core-bindgen", "--",
            "generate", libraryFile.get().asFile.absolutePath,
            "--language", "kotlin", "--out-dir", output.absolutePath, "--no-format"))
        check(output.walkTopDown().any { it.isFile && it.extension == "kt" && it.length() > 0 }) {
            "UniFFI did not generate Kotlin bindings"
        }
    }
}

// Include non-Rust resources (e.g. filter_catalog.json), Cargo.lock, all
// manifests/configs and generator inputs. Do not guess which files affect Cargo.
val nativeSources = fileTree(rustWorkspace) {
    exclude("target/**", ".git/**")
}

fun NativeCargoTask.configureNativeInputs() {
    cargo.set(cargoExecutable)
    workspace.set(rustWorkspace)
    controlled.set(nativeCacheEnabled)
    toolIdentity.set(nativeIdentity)
    toolEnvironment.set(nativeEnvironment)
    sources.from(nativeSources)
}

android {
    namespace = "de.justify87.ladiesexplorer"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "de.justify87.ladiesexplorer"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters.addAll(rustAbis) }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

androidComponents {
    onVariants { variant ->
        val capitalized = variant.name.replaceFirstChar { it.uppercase() }
        val rustTask = tasks.register<BuildRustForAndroid>("cargoBuild" + capitalized) {
            group = "rust"
            configureNativeInputs()
            ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
            ndkMetadata.set(androidComponents.sdkComponents.ndkDirectory.map { it.file("source.properties") })
            abis.set(rustAbis)
            minApi.set(29)
            outputDir.set(layout.buildDirectory.dir("generated/rust/" + variant.name + "/jniLibs"))
        }
        val bindings = tasks.register<GenerateUniffiKotlin>("uniffiBindgen" + capitalized) {
            group = "rust"
            configureNativeInputs()
            libraryFile.set(rustTask.flatMap { it.outputDir.file(rustAbis.first() + "/libladies_core.so") })
            outputDir.set(layout.buildDirectory.dir("generated/rust/" + variant.name + "/kotlin"))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(rustTask, BuildRustForAndroid::outputDir)
        variant.sources.java?.addGeneratedSourceDirectory(bindings, GenerateUniffiKotlin::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.libmpv)
    implementation(libs.coil.network.core)
    implementation(libs.kermit)
    implementation(libs.maplibre.sdk)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jna) { artifact { type = "aar" } }
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
