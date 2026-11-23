# OpenTouchpad R8 / ProGuard keep rules

# Keep manifest-declared components
-keep class io.github.opentouchpad.MainActivity
-keep class io.github.opentouchpad.TouchpadService
-keep class io.github.opentouchpad.TouchpadActionReceiver

# Keep custom View subclasses and their constructors
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Preserve enum values() / valueOf() for PadAction, ThemeMode, ButtonLayoutMode
-keepclassmembers enum io.github.opentouchpad.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep clean stack traces for diagnostics
-keepattributes SourceFile,LineNumberTable
