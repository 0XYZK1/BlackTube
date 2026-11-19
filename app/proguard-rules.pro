# NEXUS – Release-Regeln

-keep class com.nexus.videoclient.** { *; }
# LAME uses JNI – class and native method names must not be renamed
-keep class com.naman14.androidlame.** { *; }

# NewPipeExtractor arbeitet mit Reflection/JS (Rhino) für die Signatur-Auflösung
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class com.grack.nanojson.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.schabi.newpipe.extractor.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
