# GaoCang Release ProGuard / R8 rules
# 保留行号与源文件名：应用里有崩溃日志（CrashLog），混淆掉文件名会让日志失去定位价值。
# 没有 -renamesourcefileattribute —— 那行会把文件名统一改成 "SourceFile"，省几十字节，
# 代价是运营版崩溃日志里的文件名全变成同一个词（2026-11-22 改）。
-keepattributes SourceFile,LineNumberTable

# AccessibilityService declared in AndroidManifest.xml
-keep class com.yejing.artvault.ScreenSenseService { *; }
