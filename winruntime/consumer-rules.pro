# The runtime is driven from native code (JNI callbacks) and by reflection-free string lookups; keep it intact.
-keep class com.chronos.pocketos.windows.** { *; }
-keep class com.github.luben.zstd.** { *; }
-dontwarn org.tukaani.xz.**
-dontwarn org.apache.commons.compress.**
