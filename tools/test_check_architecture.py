import unittest
import xml.etree.ElementTree as ET

from check_architecture import (APP, BASE, STORE, VIDEO, PKG, ANDROID, source_file,
                                wiring, tracked_paths, dependency_file,
                                manifest_errors, provider_errors, native_errors)


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
        app = "NetworkFetcher.Factory(RustImageNetworkClient(coreStore::loadDisplayImage))"
        store = "private val engine: LadiesCore"
        video = ('app.coreStore.loadDisplayVideo(url)\n'
                 '"demuxer-lavf-o" to "protocol_whitelist=file"\n'
                 'player.command(arrayOf("loadfile", source.file.absolutePath, "replace"))')
        self.assertEqual([], wiring(app, store, video))
        for rule, args in (
            ("ARC-01", ("NetworkFetcher.Factory(other)", store, video)),
            ("ARC-01", (app + "\nNetworkFetcher.Factory(other)", store, video)),
            ("ARC-03", (app, store.replace("private ", ""), video)),
            ("ARC-07", (app, store, video.replace("protocol_whitelist=file", "protocol_whitelist=http"))),
            ("ARC-07", (app, store, video.replace("source.file.absolutePath", "url"))),
            ("ARC-07", (app, store, video + '\nplayer.command(arrayOf("loadfile", url))')),
        ):
            self.assertIn(rule, {e[0] for e in wiring(*args)})

    def test_dependencies_and_tracked_paths(self):
        for rule, dep in (("ARC-01", "com.squareup.okhttp3:okhttp"), ("ARC-02", "androidx.room:room-runtime"), ("ARC-06", "org.springframework:boot")):
            self.assertIn(rule, {e[0] for e in dependency_file("build.gradle.kts", dep)})
        self.assertEqual([], dependency_file("gradle/libs.versions.toml", "net.java.dev.jna:jna\norg.maplibre.gl:android-sdk\nio.coil-kt.coil3:coil-network-core"))
        for rule, path in (("ARC-04", "app/src/main/jniLibs/arm64/libladies_core.so"), ("ARC-06", "app/src/main/assets/server.py"), ("ARC-08", "exports/ladies-http-123.har.json"), ("ARC-08", "ladies.sqlite"), ("ARC-08", "ladies_data/media/images/a.png")):
            self.assertIn(rule, {e[0] for e in tracked_paths([path])})
        self.assertEqual([], tracked_paths(["tools/m05-reference.py", "fixtures/sample.json", "app/src/test/resources/synthetic.png", "gradle/wrapper/gradle-wrapper.jar"]))


MANIFEST = f"""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{PKG}.debug">
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
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
            ('android:protectionLevel="signature"', 'android:protectionLevel="normal"'),
            ('android:permission="android.permission.DUMP"', 'android:permission="android.permission.INTERNET"'),
            ('android:debuggable="true"', 'android:debuggable="false"'),
            ('android:exported="false"', 'android:exported="true"'),
            ('android:grantUriPermissions="true"', 'android:grantUriPermissions="false"'),
            ('@xml/diagnostic_file_paths', '@xml/other_paths'),
            ('</application>', '<service android:name=".Hidden" android:exported="true"/></application>'),
        ):
            with self.subTest(after=after):
                self.assertTrue(manifest_errors(MANIFEST.replace(before, after)))

    def test_provider_and_native_mutations(self):
        for xml in (PATHS.replace("diagnostic-exports/", "."), PATHS.replace("cache-path", "root-path"), PATHS.replace("</paths>", '<files-path name="all" path="."/></paths>')):
            self.assertTrue(provider_errors(xml))
        for entries in ([], ["lib/x86_64/libladies_core.so"], ["lib/arm64-v8a/libladies_core.so"]):
            self.assertTrue(native_errors(entries))


if __name__ == "__main__":
    unittest.main()
