# AI 大模型相关
-keep class com.example.ailogapp.data.** { *; }
-keep class com.example.ailogapp.ai.** { *; }

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**

# Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keep class * implements java.io.Serializable
