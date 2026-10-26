# Classes named in AndroidManifest.xml must keep their names.
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.accessibilityservice.AccessibilityService

# Readable stack traces in crash reports from users.
-keepattributes SourceFile,LineNumberTable

-allowaccessmodification
-dontwarn **
