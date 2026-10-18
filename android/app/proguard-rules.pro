# ProGuard rules for the Hercules port.
# The native runtime is loaded via System.loadLibrary and its symbols are not mangled by Java,
# so there is little to preserve here — but keep reflection/annotation-safe defaults.

-keepattributes *Annotation*, EnclosingMethod, InnerClasses
-repackageclasses ''
-keepclassmembers class ** {
    @android.webkit.JavascriptInterface <methods>;
}

# JNI entry points are referenced by exact mangled name (Java_<pkg>_<cls>_<method>); do not rename them.
-keep classes com.example.hercules.** { *; }
