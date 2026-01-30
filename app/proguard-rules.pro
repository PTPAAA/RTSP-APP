# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep RTSP server classes
-keep class com.rtsp.camera.rtsp.** { *; }

# Keep model classes
-keep class com.rtsp.camera.model.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# CameraX
-keep class androidx.camera.** { *; }
