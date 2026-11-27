import unittest
import subprocess
import tempfile
from pathlib import Path
import sys
from unittest.mock import patch
import zipfile

from check_architecture import (APP, BASE, STORE, VIDEO, PKG, ANDROID, source_file,
                                wiring, tracked_paths, dependency_file,
                                manifest_errors, provider_errors, native_errors,
                                provider_resource_files, provider_reference, check_apk,
                                check_source, LOCAL_OPTIONS)


class SourceArchitectureTest(unittest.TestCase):
    def test_negative_source_rules(self):
        ui = BASE + "ui/Example.kt"
        for rule, path, code in (
            ("ARC-01", ui, "import java.net.URL"),
            ("ARC-01", ui, "NetworkFetcher . Factory (client)"),
            ("ARC-02", ui, "import android.database.sqlite.SQLiteDatabase"),
            ("ARC-02", ui, "RoomDatabase()"),
            ("ARC-03", ui, "val x = LadiesCore(path)"),
            ("ARC-03", ui, "import de.justify87.ladiesexplorer.core.LadiesCore as Engine"),
            ("ARC-04", ui, "class LadiesCore {}"),
            ("ARC-05", BASE + "core/Other.kt", "package de.justify87.ladiesexplorer.core\nimport de.justify87.ladiesexplorer.ui.MainViewModel"),
            ("ARC-06", ui, "external fun nativeCall()"),
            ("ARC-06", ui, "import com.sun.jna.Native"),
            ("ARC-07", ui, "import dev.jdtech.mpv.MPVLib"),
        ):
            with self.subTest(rule=rule, code=code):
                self.assertIn(rule, {e[0] for e in source_file(path, code)})

    def test_legitimate_infrastructure(self):
        for path, code in (
            (STORE, "private val engine: LadiesCore = LadiesCore(path)"),
            (APP, "val factory = NetworkFetcher.Factory(client)"),
            (VIDEO, "import dev.jdtech.mpv.MPVLib"),
            (BASE + "ui/Example.kt", "import android.net.Uri\nimport org.maplibre.android.maps.MapView\nval control = RequestControl()"),
            (BASE + "ui/Example.kt", "import de.justify87.ladiesexplorer.core.*\nval data: ProfileMediaItem"),
            (BASE + "core/Other.kt", "package de.justify87.ladiesexplorer.core\nimport java.io.File"),
        ):
            with self.subTest(path=path, code=code):
                self.assertEqual([], source_file(path, code))

    def test_wiring_and_local_video(self):
        self.assertEqual([], wiring(GOOD_APP, GOOD_STORE, GOOD_VIDEO))
        for rule, args in (
            ("ARC-01", ("NetworkFetcher.Factory(other)", GOOD_STORE, GOOD_VIDEO)),
            ("ARC-01", ("// " + GOOD_APP, GOOD_STORE, GOOD_VIDEO)),
            ("ARC-01", (GOOD_APP + "\nNetworkFetcher.Factory(other)", GOOD_STORE, GOOD_VIDEO)),
            ("ARC-03", (GOOD_APP, GOOD_STORE.replace("private ", ""), GOOD_VIDEO)),
            ("ARC-03", (GOOD_APP, "/* " + GOOD_STORE + " */", GOOD_VIDEO)),
            ("ARC-07", (GOOD_APP, GOOD_STORE, GOOD_VIDEO.replace("protocol_whitelist=file", "protocol_whitelist=http"))),
            ("ARC-07", (GOOD_APP, GOOD_STORE, GOOD_VIDEO.replace("source.file.absolutePath", "url"))),
            ("ARC-07", (GOOD_APP, GOOD_STORE, GOOD_VIDEO + '\nplayer.command(arrayOf("loadfile", url))')),
            ("ARC-07", (GOOD_APP, GOOD_STORE, GOOD_VIDEO.replace('player.setOptionString(name, value)', '// player.setOptionString(name, value)'))),
        ):
            self.assertIn(rule, {e[0] for e in wiring(*args)})
        for name, value in LOCAL_OPTIONS.items():
            pair = f'"{name}" to "{value}"'
            for replacement in ('// ' + pair, pair.replace(f'"{value}"', '"unsafe"'), pair + '\n' + pair):
                with self.subTest(name=name, replacement=replacement):
                    self.assertIn("ARC-07", {e[0] for e in wiring(GOOD_APP, GOOD_STORE, GOOD_VIDEO.replace(pair, replacement))})
        self.assertIn("ARC-07", {e[0] for e in wiring(GOOD_APP, GOOD_STORE, GOOD_VIDEO + '\nplayer.setOptionString("config", "yes")')})

    def test_comments_and_multiline_scans(self):
        ui = BASE + "ui/Example.kt"
        self.assertEqual([], source_file(ui, '// java.net.URL\n/* nested /* LadiesCore */ WebView */\nval s = "https://example.invalid"'))
        self.assertIn("ARC-01", {e[0] for e in source_file(ui, "NetworkFetcher\n . Factory\n (client)")})
        self.assertIn("ARC-01", {e[0] for e in source_file(ui, 'val s = "https://example.invalid" // url\nimport java.net.URL')})
        self.assertEqual([], wiring(GOOD_APP.replace("coreStore", "store"), GOOD_STORE.replace("engine", "nativeEngine"), GOOD_VIDEO.replace("source.file", "clip.file")))

    def test_dependencies_and_tracked_paths(self):
        for rule, dep in (("ARC-01", "com.squareup.okhttp3:okhttp"), ("ARC-02", "androidx.room:room-runtime"), ("ARC-06", "org.springframework:boot")):
            self.assertIn(rule, {e[0] for e in dependency_file("build.gradle.kts", dep)})
        self.assertEqual([], dependency_file("gradle/libs.versions.toml", "net.java.dev.jna:jna\norg.maplibre.gl:android-sdk\nio.coil-kt.coil3:coil-network-core"))
        for rule, path in (("ARC-04", "app/src/main/jniLibs/arm64/libladies_core.so"), ("ARC-06", "app/src/main/assets/server.py"), ("ARC-08", "exports/ladies-http-123.har.json"), ("ARC-08", "ladies.sqlite"), ("ARC-08", "ladies_data/media/images/a.png")):
            self.assertIn(rule, {e[0] for e in tracked_paths([path])})
        self.assertEqual([], tracked_paths(["tools/m05-reference.py", "fixtures/sample.json", "app/src/test/resources/synthetic.png", "gradle/wrapper/gradle-wrapper.jar"]))


GOOD_APP = "NetworkFetcher.Factory(RustImageNetworkClient(coreStore::loadDisplayImage))"
GOOD_STORE = "private val engine: LadiesCore = LadiesCore(path)"
GOOD_VIDEO = ("\n".join(f'"{name}" to "{value}"' for name, value in LOCAL_OPTIONS.items())
              + '\nplayer.setOptionString(name, value)\napp.coreStore.loadDisplayVideo(url)\n'
              + 'player.command(arrayOf("loadfile", source.file.absolutePath, "replace"))')


class RealRepositoryTest(unittest.TestCase):
    def test_complete_source_command(self):
        script = Path(__file__).resolve().with_name("check_architecture.py")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            subprocess.run(["git", "init", "-q", str(root)], check=True, timeout=30)

            def put(path, content):
                file = root / path
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(content, encoding="utf-8")
                subprocess.run(["git", "-C", tmp, "add", "--", path], check=True, timeout=30)

            for path, content in ((APP, GOOD_APP), (STORE, GOOD_STORE), (VIDEO, GOOD_VIDEO),
                                  ("app/src/test/java/Example.kt", "import java.net.URL"),
                                  ("app/src/androidTestDebug/java/Example.kt", "import android.database.sqlite.SQLiteDatabase")):
                put(path, content)

            def run():
                return subprocess.run([sys.executable, str(script), "--root", tmp],
                                      capture_output=True, text=True, timeout=30)

            result = run()
            self.assertEqual(0, result.returncode, result.stderr)
            for path, code, rule in (
                ("app/src/debug/java/Example.kt", "import java.net.URL", "ARC-01"),
                ("app/src/release/java/Example.kt", "val database = SQLiteDatabase()", "ARC-02"),
                ("app/src/testing/java/Example.kt", "val engine = LadiesCore(path)", "ARC-03"),
                (APP, "// " + GOOD_APP, "ARC-01"),
            ):
                put(path, code)
                result = run()
                self.assertEqual(1, result.returncode)
                self.assertIn(rule, result.stderr)
                if path == APP:
                    put(APP, GOOD_APP)
                else:
                    subprocess.run(["git", "-C", tmp, "rm", "-f", "--", path],
                                   check=True, timeout=30)
            self.assertEqual([], check_source(root))


MANIFEST = f"""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{PKG}.debug">
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="{PKG}.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"/>
<permission android:name="{PKG}.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" android:protectionLevel="signature"/>
<application android:allowBackup="false" android:debuggable="true">
  <activity android:name="{PKG}.MainActivity" android:exported="true"/>
  <activity android:name="androidx.activity.ComponentActivity" android:exported="true"/>
  <receiver android:name="androidx.profileinstaller.ProfileInstallReceiver" android:exported="true" android:permission="android.permission.DUMP"/>
  <provider android:name="{PKG}.DiagnosticFileProvider" android:exported="false" android:grantUriPermissions="true" android:authorities="{PKG}.debug.fileprovider">
    <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/diagnostic_file_paths"/>
  </provider>
</application></manifest>"""
PATHS = '<paths><cache-path name="diagnostic-exports" path="diagnostic-exports/"/></paths>'


class ApkArchitectureTest(unittest.TestCase):
    def test_reviewed_manifest(self):
        self.assertEqual([], manifest_errors(MANIFEST))
        self.assertEqual([], provider_errors(PATHS))
        self.assertEqual([], native_errors(["lib/arm64-v8a/libladies_core.so", "lib/arm64-v8a/libjnidispatch.so"]))

    def test_manifest_mutations(self):
        for before, after in (
            ('allowBackup="false"', 'allowBackup="true"'),
            ('android.permission.ACCESS_NETWORK_STATE', 'android.permission.READ_CONTACTS'),
            ('android.permission.ACCESS_WIFI_STATE', 'android.permission.ACCESS_FINE_LOCATION'),
            ('android.permission.ACCESS_WIFI_STATE', 'android.permission.ACCESS_COARSE_LOCATION'),
            ('android:protectionLevel="signature"', 'android:protectionLevel="normal"'),
            ('android:permission="android.permission.DUMP"', 'android:permission="android.permission.INTERNET"'),
            ('android:debuggable="true"', 'android:debuggable="false"'),
            ('android:exported="false"', 'android:exported="true"'),
            ('android:grantUriPermissions="true"', 'android:grantUriPermissions="false"'),
            ('</application>', '<service android:name=".Hidden" android:exported="true"/></application>'),
        ):
            with self.subTest(after=after):
                self.assertTrue(manifest_errors(MANIFEST.replace(before, after)))

    def test_additional_uri_grant_provider_is_rejected(self):
        for provider in (
            '<provider android:name=".Other" android:exported="false" android:grantUriPermissions="true"/>',
            '<provider android:name=".Other" android:exported="false"><grant-uri-permission android:pathPrefix="/"/></provider>',
            '<provider android:name=".Other" android:exported="false"><meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/other"/></provider>',
        ):
            self.assertTrue(manifest_errors(MANIFEST.replace("</application>", provider + "</application>")))
        self.assertEqual([], manifest_errors(MANIFEST.replace("</application>", '<provider android:name="androidx.startup.InitializationProvider" android:exported="false"/></application>')))

    def test_provider_resource_resolution(self):
        table = (
            f"Package name={PKG}.debug id=7f\n"
            "  type xml id=13 entryCount=2\n"
            "    resource 0x7f130000 xml/diagnostic_file_paths\n"
            "      (default) (file) res/xml/diagnostic_file_paths.xml type=XML\n"
            "      (v31) (file) res/xml-v31/diagnostic_file_paths.xml type=XML\n"
            "    resource 0x7f130001 xml/other\n"
            "      (default) (file) res/xml/other.xml type=XML\n"
        )
        expected = ["res/xml-v31/diagnostic_file_paths.xml", "res/xml/diagnostic_file_paths.xml"]
        for ref in ("@xml/diagnostic_file_paths", "@ref/0x7f130000", "@0x7f130000"):
            self.assertEqual(expected, provider_resource_files(ref, table))
        for ref in ("@xml/other", "@ref/0x7f130001", "@ref/0x12345678", None):
            with self.assertRaises(ValueError):
                provider_resource_files(ref, table)
        with self.assertRaises(ValueError):
            provider_resource_files("@ref/0x7f130000", table.replace("(file) res/xml-v31/diagnostic_file_paths.xml type=XML", "@xml/other"))
        self.assertEqual("@xml/diagnostic_file_paths", provider_reference(MANIFEST))

    def test_apk_checks_every_referenced_variant(self):
        table = (
            f"Package name={PKG}.debug id=7f\n"
            "  type xml id=13 entryCount=1\n"
            "    resource 0x7f130000 xml/diagnostic_file_paths\n"
            "      (default) (file) res/xml/diagnostic_file_paths.xml\n"
            "      (v31) (file) res/xml-v31/diagnostic_file_paths.xml\n"
        )
        manifest = MANIFEST.replace("@xml/diagnostic_file_paths", "@ref/0x7f130000")
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "sample.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for path in ("lib/arm64-v8a/libladies_core.so", "lib/arm64-v8a/libjnidispatch.so",
                             "res/xml/diagnostic_file_paths.xml", "res/xml-v31/diagnostic_file_paths.xml"):
                    archive.writestr(path, b"fixture")

            invalid_variant = False

            def response(args, **kwargs):
                if args[0] == "aapt2":
                    return table
                if args[1] == "manifest":
                    return manifest.encode()
                if invalid_variant and "xml-v31" in args[4]:
                    return PATHS.replace("diagnostic-exports/", ".").encode()
                return PATHS.encode()

            with patch("check_architecture.subprocess.check_output", side_effect=response):
                self.assertEqual([], check_apk(apk, "apkanalyzer", "aapt2"))
                invalid_variant = True
                self.assertEqual(provider_errors(PATHS.replace("diagnostic-exports/", ".")),
                                 check_apk(apk, "apkanalyzer", "aapt2"))

    def test_provider_and_native_mutations(self):
        for xml in (PATHS.replace("diagnostic-exports/", "."), PATHS.replace("cache-path", "root-path"), PATHS.replace("</paths>", '<files-path name="all" path="."/></paths>')):
            self.assertTrue(provider_errors(xml))
        for entries in ([], ["lib/x86_64/libladies_core.so"], ["lib/arm64-v8a/libladies_core.so"]):
            self.assertTrue(native_errors(entries))


if __name__ == "__main__":
    unittest.main()
