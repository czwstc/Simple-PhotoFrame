# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# Run as root through app_process
-keep class com.zweichen.photoframe.util.RootWifi { public static void main(java.lang.String[]); }

# ZXing
-dontwarn com.google.zxing.**
