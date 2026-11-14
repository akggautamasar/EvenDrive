# Keep TDLib JNI classes & callbacks intact in release builds
-keep class org.drinkless.tdlib.** { *; }
-dontwarn org.drinkless.tdlib.**
