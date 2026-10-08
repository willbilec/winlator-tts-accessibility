# Optional Kotlin debug metadata annotation in AndroidX Test, absent from this app's older Kotlin runtime.
-dontwarn kotlin.jvm.internal.SourceDebugExtension
# These compile-time annotations are not required by the device runner.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
