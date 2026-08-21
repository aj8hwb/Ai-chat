# Add project specific ProGuard rules here.

# llama.cpp native engine (org.codeshipping:llama-kotlin-android). The native
# JNI entry points are registered from Kotlin by name, so R8 must not rename
# or strip the binding classes.
-keep class org.codeshipping.llamakotlin.** { *; }
-dontwarn org.codeshipping.llamakotlin.**

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.** { *** Companion; }
