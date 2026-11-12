# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.altscroll.app.data.model.** {
    *** Companion;
    <fields>;
}
-keepclasseswithmembers class com.altscroll.app.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
