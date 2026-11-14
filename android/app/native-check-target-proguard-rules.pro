# A separate APK cannot call declarations removed before its target mapping exists.
# These roots apply only to nativeCheck; ordinary release and codec shrinking stay unchanged.
-keep,allowoptimization,allowobfuscation class androidx.tracing.Trace {
    public static void beginSection(java.lang.String);
    public static void endSection();
    public static void forceEnableAppTracing();
}
-keep,allowoptimization,allowobfuscation class com.lumenpearson.lessons.core.data.di.Graph {
    public static final com.lumenpearson.lessons.core.data.di.Graph INSTANCE;
    public void ensureInitialized(android.content.Context);
    public com.lumenpearson.lessons.core.data.di.LessonsContainer getContainer();
}
-keep,allowoptimization,allowobfuscation interface com.lumenpearson.lessons.core.data.di.LessonsContainer {
    public com.lumenpearson.lessons.core.data.repository.SessionRepository getSessionRepository();
    public com.lumenpearson.lessons.core.data.repository.SettingsRepository getSettingsRepository();
    public com.lumenpearson.lessons.core.data.repository.DiaryRepository getDiaryRepository();
    public com.lumenpearson.lessons.core.data.repository.DiarySignIn getDiarySignIn();
}
-keep,allowoptimization,allowobfuscation interface com.lumenpearson.lessons.core.data.repository.DiaryRepository {
    public java.lang.Object current(kotlin.coroutines.Continuation);
    public kotlinx.coroutines.flow.Flow getTarget();
    public java.lang.Object students-IoAF18A(kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation interface com.lumenpearson.lessons.core.data.repository.DiarySignIn {
    public java.lang.Object preflight-gIAlu-s(com.lumenpearson.lessons.core.data.repository.DiaryTarget,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class com.lumenpearson.lessons.core.data.repository.DiaryTarget {
    public static final com.lumenpearson.lessons.core.data.repository.DiaryTarget$Companion Companion;
}
-keep,allowoptimization,allowobfuscation class com.lumenpearson.lessons.core.data.repository.DiaryTarget$Companion {
    public com.lumenpearson.lessons.core.data.repository.DiaryTarget petersburg(java.lang.String);
}
-keep,allowoptimization,allowobfuscation class com.lumenpearson.lessons.core.data.repository.DiaryFailure$SignInRequired {
}
-keep,allowoptimization,allowobfuscation interface com.lumenpearson.lessons.core.data.repository.SessionRepository {
    public java.lang.Object currentAll(kotlin.coroutines.Continuation);
    public java.lang.Object join-0E7RQCE(java.lang.String,java.lang.String,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation interface com.lumenpearson.lessons.core.data.repository.SettingsRepository {
    public kotlinx.coroutines.flow.Flow getSettings();
    public java.lang.Object update(kotlin.jvm.functions.Function1,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class com.lumenpearson.lessons.core.data.repository.AppSettings {
    public java.lang.String getBaseUrl();
    public static com.lumenpearson.lessons.core.data.repository.AppSettings copy$default(...);
}
-keep,allowoptimization,allowobfuscation class kotlin.Result {
    public static kotlin.Result box-impl(java.lang.Object);
    public java.lang.Object unbox-impl();
    public static boolean isSuccess-impl(java.lang.Object);
}
# The public facade owners inherit their callable declarations from JVM part classes.
-keep,allowoptimization,allowobfuscation class kotlin.LazyKt {
}
-keep,allowoptimization,allowobfuscation class kotlin.LazyKt__LazyJVMKt {
    public static kotlin.Lazy lazy(kotlin.jvm.functions.Function0);
}
-keep,allowoptimization,allowobfuscation class kotlin.ResultKt {
    public static void throwOnFailure(java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlin.coroutines.intrinsics.IntrinsicsKt {
}
-keep,allowoptimization,allowobfuscation class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsKt {
    public static java.lang.Object getCOROUTINE_SUSPENDED();
}
-keep,allowoptimization,allowobfuscation class kotlin.coroutines.jvm.internal.SpillingKt {
    public static java.lang.Object nullOutSpilledVariable(java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.BuildersKt {
    public static java.lang.Object runBlockingK$default(kotlin.coroutines.CoroutineContext,kotlin.jvm.functions.Function2,int,java.lang.Object);
    public static java.lang.Object withContext(kotlin.coroutines.CoroutineContext,kotlin.jvm.functions.Function2,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.TimeoutKt {
    public static java.lang.Object withTimeout(long,kotlin.jvm.functions.Function2,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.flow.FlowKt {
    public static java.lang.Object first(kotlinx.coroutines.flow.Flow,kotlin.coroutines.Continuation);
}
# Separate coroutine subclasses retain these override names and original parameter order.
-keepclassmembers,allowoptimization class kotlin.coroutines.jvm.internal.BaseContinuationImpl {
    protected abstract java.lang.Object invokeSuspend(java.lang.Object);
    public kotlin.coroutines.Continuation create(java.lang.Object,kotlin.coroutines.Continuation);
}
