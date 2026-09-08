# kotlinx.serialization — сохраняем сериализаторы.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.umbra.app.**$$serializer { *; }
-keepclassmembers class com.umbra.app.** { *** Companion; }
-keepclasseswithmembers class com.umbra.app.** { kotlinx.serialization.KSerializer serializer(...); }
