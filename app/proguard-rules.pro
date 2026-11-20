# UniFFI's generated Kotlin interfaces access JNA reflectively.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class de.justify87.ladiesexplorer.core.** { *; }
-dontwarn java.awt.**

# libmpv JNI callbacks are looked up by name.
-keep class dev.jdtech.mpv.** { *; }
