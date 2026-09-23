# 保留 kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.yusheng.quota.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Glance / Widget
-keep class androidx.glance.** { *; }
