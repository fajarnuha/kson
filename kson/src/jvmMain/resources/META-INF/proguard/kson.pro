# ksonDecoderOf reads @Kson at runtime and loads com.example.UserJson.INSTANCE for com.example.User.
-keepattributes RuntimeVisibleAnnotations,Signature
-keep @interface com.fajarnuha.kson.Kson
-keep @com.fajarnuha.kson.Kson interface *
-keep class * implements com.fajarnuha.kson.KsonDecoder {
    public static ** INSTANCE;
}
