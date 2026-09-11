# =============================================================================
# AiChatHub ProGuard/R8 Rules
# =============================================================================

# --- llama.cpp native engine (org.codeshipping:llama-kotlin-android) ---
-keep class org.codeshipping.llamakotlin.** { *; }
-dontwarn org.codeshipping.llamakotlin.**

# --- Kotlinx serialization ---
-keepattributes *Annotation*, InnerClasses
-dontwarn kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.** { *** Companion; }
-keep,includedescriptorclasses class com.aichathub.app.**$$serializer { *; }
-keepclassmembers class com.aichathub.app.** { *** Companion; }
-keepclasseswithmembers class com.aichathub.app.** { kotlinx.serialization.KSerializer serializer(...); }

# --- Room ---
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class com.aichathub.app.data.local.InstalledModelEntity { *; }
-keep class com.aichathub.app.data.local.ConversationEntity { *; }
-keep class com.aichathub.app.data.local.MessageEntity { *; }
-keep class com.aichathub.app.data.local.AiDatabase { *; }
-keep class com.aichathub.app.data.local.InstalledModelDao { *; }
-keep class com.aichathub.app.data.local.ConversationDao { *; }
-keep class com.aichathub.app.data.local.MessageDao { *; }

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# --- Domain models used by serialization ---
-keep class com.aichathub.app.domain.model.CatalogModel { *; }
-keep class com.aichathub.app.domain.model.ChatTemplate { *; }
