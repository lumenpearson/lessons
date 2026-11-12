# Lite message metadata addresses fields by name, and our codec discovers the
# default instance through this static entry point instead of kotlin-reflect.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    public static *** getDefaultInstance();
}

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep @interface com.lumenpearson.lessons.core.data.network.v2.Credential
