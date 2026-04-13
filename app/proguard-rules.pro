# Keep Kotlin metadata for stacktrace readability.
-keep class kotlin.Metadata { *; }

# Keep Parcelable creators.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Strip log statements in release.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
