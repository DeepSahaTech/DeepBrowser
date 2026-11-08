# WebView JS bridges (added in later phases) must be kept:
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
