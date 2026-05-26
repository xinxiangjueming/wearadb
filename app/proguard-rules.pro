-dontwarn javax.annotation.**
-keep class com.wearadb.adb.** { *; }

# conscrypt SSL compatibility (referenced by libadb-android)
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
