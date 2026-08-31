# OpenCV rules
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# ML Kit rules
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Coroutines rules
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Project Models & Enums
-keep class com.aaa.macro.model.** { *; }
