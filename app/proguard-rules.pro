# Regras ProGuard para o Voice Assistant

# --- Hilt ---
-keepclasseswithmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}

# --- Room ---
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# --- Kotlin Serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class **$$serializer {
    static **$$serializer INSTANCE;
}

# --- MediaPipe LLM Inference ---
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.tasks.genai.** { *; }
-dontwarn com.google.mediapipe.**

# --- Firebase ---
-keep class com.google.firebase.** { *; }
