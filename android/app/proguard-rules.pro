# R8 rules for the release build.
#
# The app module itself is trivially shrinkable; everything here exists for the
# libraries the data layer pulls in, whose reflection R8 cannot see.

# --- Line numbers -------------------------------------------------------------
# Keep enough to symbolicate a stack trace from a user report.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Annotations the runtime libraries read at run time -----------------------
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions,
                RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations,
                RuntimeVisibleTypeAnnotations, AnnotationDefault

# --- kotlinx.serialization ----------------------------------------------------
# The generated serializers are referenced only reflectively from the companion.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$serializer {
    *** INSTANCE;
}
-dontnotewarnings kotlinx.serialization.**

# --- Retrofit / OkHttp --------------------------------------------------------
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- WorkManager --------------------------------------------------------------
# Workers are instantiated by name by the framework.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- Glance / app widget receiver --------------------------------------------
# The receiver is named from the merged manifest, so it must survive by name.
-keep class * extends android.appwidget.AppWidgetProvider { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }

# --- Domain models ------------------------------------------------------------
# Cheap to keep, and it makes crash reports from the schedule engine readable.
-keep class com.lumenpearson.lessons.core.model.** { *; }
