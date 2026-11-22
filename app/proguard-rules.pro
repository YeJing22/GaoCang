# GaoCang Release ProGuard / R8 rules
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# AccessibilityService declared in AndroidManifest.xml
-keep class com.yejing.artvault.ScreenSenseService { *; }
