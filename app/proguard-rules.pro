# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Remove unused code and resources
-dontshrink
-dontoptimize
-dontobfuscate

# Keep application entry point
-keep class com.intentrouter.stubio.** { *; }

# Remove logging for production builds
-assumenosideeffects class android.util.Log {
    public static *;
}

# Optimize Kotlin-related code
-keep class kotlin.** { *; }
-keepclassmembers class kotlin.Metadata { *; }

# Keep required support libraries
-keep class androidx.** { *; }
-keep class com.google.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.appcompat.** { *; }
-keep class org.jetbrains.kotlin.** { *; }

# Retain Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep serialization for JSON processing libraries
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.fasterxml.jackson.** { *; }
-keep class com.google.gson.** { *; }

## Remove debug information (optional)
#-dontnote
#-dontwarn
#-optimizationpasses 5

# Shrink native libraries
-keep class * extends android.app.Application { *; }
