# MeshChat R8 混淆规则

# ===== kotlinx.serialization（编译器插件生成的 serializer 必须保留，否则反序列化崩溃）=====
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.meshchat.app.**$$serializer { *; }
-keepclassmembers class com.meshchat.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.meshchat.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ===== kotlinx.serialization 内部 =====
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# ===== Room（AGP 已带 consumer rules，此处兜底）=====
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.**
