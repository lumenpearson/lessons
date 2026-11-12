# Only the test APK: the runner discovers this entry point by name and annotation.
# Target-side external declarations live in native-check-target-proguard-rules.pro.
# Neither file retains a generated message, codec or repository implementation.
-keep class com.lumenpearson.lessons.TransportReleaseTest { public <init>(); public void *(); }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# AndroidX Test references these CLASS-retention static-analysis annotations.
# They are absent from its runtime; neither is consulted by the native harness.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
