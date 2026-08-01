# Retrofit and Gson inspect generic signatures and runtime annotations.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# libdiscord.so and the recovered WebRTC bridge use exact JNI class, member, and field names.
-keep class com.hammerandchisel.libdiscord.** { *; }
-keep class co.discord.media_engine.** { *; }
-keep class org.webrtc.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Gson's snake_case policy derives wire names from these Kotlin/JVM field names.
-keepclassmembers,allowoptimization class com.discord.api.** { <fields>; }
-keepclassmembers,allowoptimization class com.discord.models.** { <fields>; }
-keepclassmembers,allowoptimization class com.discord.analytics.generated.** { <fields>; }
-keepclassmembers,allowoptimization class com.discord.gateway.io.** { <fields>; }
-keepclassmembers,allowoptimization class com.discord.gateway.GatewaySocket$** { <fields>; }
-keepclassmembers,allowoptimization class com.discord.restapi.RestAPIParams$** { <fields>; }
-keepclassmembers,allowoptimization class com.discord.rtcconnection.socket.io.** { <fields>; }
-keepclassmembers,allowoptimization class com.discord.utilities.captcha.CaptchaErrorBody { <fields>; }
-keepclassmembers,allowoptimization class com.discord.widgets.settings.billing.WidgetPaymentSourceEditDialog$StateEntry { <fields>; }
