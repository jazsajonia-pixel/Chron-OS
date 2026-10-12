# kotlinx.serialization keeps generated serializers via its own consumer rules.
-keepattributes *Annotation*, InnerClasses

# Wine's Android display driver (wineandroid.so) finds this class by name, registers its native methods and calls
# createDesktopView/createWindow/windowPosChanged/... through JNI. Keep names and signatures unchanged.
-keep class com.chronos.pocketos.engine.WineHost { *; }
-keepclasseswithmembernames class * { native <methods>; }
