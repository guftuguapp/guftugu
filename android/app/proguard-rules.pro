# Guftugu — R8 rules (minification is currently disabled in build.gradle.kts).
# Keep kotlinx.serialization generated serializers when minification is enabled.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.guftugu.app.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.guftugu.app.**$$serializer { *; }
# WebRTC uses JNI
-keep class org.webrtc.** { *; }

# ---- Guftugu release rules ----
# kotlinx.serialization: keep @Serializable classes' generated serializers and companions.
-keepclassmembers @kotlinx.serialization.Serializable class com.guftugu.app.** {
    static **$* *;
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.guftugu.app.**$$serializer { *; }
# Room entities/DAOs are generated (KSP) — no reflection at runtime, nothing to keep.
# OkHttp / Okio ship their own rules. Coil 3 ships consumer rules.
# Keep enum names that are serialised by name.
-keepclassmembers enum com.guftugu.app.** { *; }
# Crash reports readable
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
