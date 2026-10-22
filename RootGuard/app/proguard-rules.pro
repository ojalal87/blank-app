# AIDL interface used between the app and the libsu root service process.
-keep class com.rootguard.app.helper.IRootGuardHelper { *; }
-keep class com.rootguard.app.helper.IRootGuardHelper$* { *; }
-keep class com.rootguard.app.helper.RootGuardHelperService { *; }
# kotlinx-serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.rootguard.** { *** Companion; }
-keepclasseswithmembers class com.rootguard.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.rootguard.**$$serializer { *; }
