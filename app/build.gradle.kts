import javax.inject.Inject
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

require(rustAbis.isNotEmpty()) { "Select at least one target in ladies.abis" }

val cargoExecutable: String = providers.environmentVariable("CARGO").orNull ?: run {
    val filename = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "cargo.exe" else "cargo"
    val rustup = File(System.getProperty("user.home"), ".cargo/bin/" + filename)
    if (rustup.isFile) rustup.absolutePath else filename
}

abstract class BuildRustForAndroid : DefaultTask() {
    @get:Inject abstract val executors: ExecOperations
    @get:Input abstract val cargo: Property<String>
    @get:Input abstract val workspace: Property<String>
    @get:Input abstract val ndkDirectory: Property<String>
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val minApi: Property<Int>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun compile() {
        val output = outputDir.get().asFile
        output.mkdirs()
        executors.exec {
            workingDir(workspace.get())
            environment("ANDROID_NDK_HOME", ndkDirectory.get())
            commandLine(
                listOf(cargo.get(), "ndk") +
                    abis.get().flatMap { listOf("-t", it) } +
                    listOf("--platform", minApi.get().toString(), "-o", output.absolutePath,
                        "build", "--release", "-p", "ladies-core", "--lib")
            )
        }
    }
}

abstract class GenerateUniffiKotlin : DefaultTask() {
    @get:Inject abstract val executors: ExecOperations
    @get:Input abstract val cargo: Property<String>
    @get:Input abstract val workspace: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val libraryFile: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDir.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        executors.exec {
            workingDir(workspace.get())
            commandLine(
                cargo.get(), "run", "--quiet", "-p", "ladies-core-bindgen", "--",
                "generate", libraryFile.get().asFile.absolutePath,
                "--language", "kotlin", "--out-dir", output.absolutePath, "--no-format"
            )
        }
    }
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
            cargo.set(cargoExecutable)
            workspace.set(rustWorkspace.absolutePath)
            ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
            abis.set(rustAbis)
            minApi.set(29)
            sources.from(fileTree(rustWorkspace) {
                include("**/*.rs", "**/Cargo.toml", "**/uniffi.toml", "Cargo.lock", "rust-toolchain.toml")
                exclude("target/**")
            })
            outputDir.set(layout.buildDirectory.dir("generated/rust/" + variant.name + "/jniLibs"))
        }
        val bindings = tasks.register<GenerateUniffiKotlin>("uniffiBindgen" + capitalized) {
            group = "rust"
            cargo.set(cargoExecutable)
            workspace.set(rustWorkspace.absolutePath)
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jna) { artifact { type = "aar" } }
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
