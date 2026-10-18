# MapLibre, OkHttp and usb-serial-for-android ship their own consumer rules, so nothing is needed for them.

# Keep line numbers in crash reports readable without keeping names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# OkHttp optionally references these; they are not present on Android.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
