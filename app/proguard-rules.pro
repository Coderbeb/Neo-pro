# Add project specific ProGuard rules here.
# No specific rules needed for debug builds.
# The accessibility service classes are automatically kept by the manifest references.
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy

# === Vision Assistance Module ===

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Google Generative AI (Gemini SDK)
-keep class com.google.ai.client.generativeai.** { *; }
-dontwarn com.google.ai.client.generativeai.**

# ML Kit Face Detection
-keep class com.google.mlkit.vision.face.** { *; }
-dontwarn com.google.mlkit.vision.face.**

# UVCCamera (serenegiant)
-keep class com.serenegiant.** { *; }
-dontwarn com.serenegiant.**
