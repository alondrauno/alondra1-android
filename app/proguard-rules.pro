# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.alondra1.app.**$$serializer { *; }
-keepclassmembers class com.alondra1.app.** { *** Companion; }
-keepclasseswithmembers class com.alondra1.app.** { kotlinx.serialization.KSerializer serializer(...); }
