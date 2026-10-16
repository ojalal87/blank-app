# Shizuku instantiates the UserService reflectively in a separate (shell/root) process.
-keep class com.bright.onepluscare.shizuku.AdvancedMaintenanceService {
    public <init>();
    public <init>(android.content.Context);
}
# Generated AIDL stub/proxy used across the Binder boundary.
-keep class com.bright.onepluscare.IAdvancedMaintenanceService { *; }
-keep class com.bright.onepluscare.IAdvancedMaintenanceService$Stub { *; }
-keep class com.bright.onepluscare.IAdvancedMaintenanceService$Stub$Proxy { *; }

# kotlinx.serialization: keep generated serializers for @Serializable classes in :core.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.bright.onepluscare.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.bright.onepluscare.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Strip verbose/debug logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
