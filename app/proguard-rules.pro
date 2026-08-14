# Add project specific ProGuard rules here.

# MediaPipe tasks-genai keeps its own rules; keep the native JNI entry points.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.framework.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.** { *** Companion; }