# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in your project's build.gradle
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# ======== TDLib Keep Rules ========
# TDLib requires native methods and reflection, so we must prevent R8 from obfuscating or stripping them.
-keep class org.drinkless.tdlib.** { *; }

# ======== Coroutines Keep Rules ========
# Ensure internal coroutines mechanisms aren't stripped, which can cause runtime crashes
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
