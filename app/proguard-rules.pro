# Retrofit and Gson inspect generic signatures and runtime annotations.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# libdiscord.so and WebRTC use exact JNI class, callback, model, member, and field names.
-keep class com.hammerandchisel.libdiscord.** { *; }
-keep class co.discord.media_engine.** { *; }
-keep class com.discord.native.engine.NativeEngine { *; }
-keep class com.discord.native.engine.NativeEngine$* { *; }
-keep class com.discord.native.engine.NativeConnection { *; }
-keep class com.discord.native.engine.NativeConnection$* { *; }
-keep class com.discord.native.engine.AudioInputDeviceDescription { *; }
-keep class com.discord.native.engine.AudioInputInitializationInfo { *; }
-keep class com.discord.native.engine.AudioOutputDeviceDescription { *; }
-keep class com.discord.native.engine.ConnectionInfo { *; }
-keep class com.discord.native.engine.VideoInputDeviceDescription { *; }
-keep class com.discord.native.engine.VideoInputDeviceFacing { *; }
-keep class com.discord.DiscordDecoderStreamManager { *; }
-keep class com.discord.DiscordDecoderStreamManagerDelegate { *; }
-keep class com.discord.MainApplication { *; }
-keep class com.discord.crash_reporting.WebrtcCrashReporting { *; }
-keep class com.discord.play_delivery.PlayAssetDeliveryNativeWrapper { *; }
-keep class com.discord.socialsdk.DiscordSocialSdkInit { *; }
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
