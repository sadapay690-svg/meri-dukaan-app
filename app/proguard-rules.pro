# Meri Dukaan — keep the WebView JS bridge alive
-keepclassmembers class com.bithub.meridukaan.MainActivity$Bridge {
    public *;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn android.webkit.**
