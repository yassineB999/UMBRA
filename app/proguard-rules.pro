# ProGuard rules for Umbra
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep class dev.yassine.umbra.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keep class com.google.firebase.** { *; }
