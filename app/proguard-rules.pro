# Native methods are bound by class and method name (JNI): keep them and their classes.
-keepclasseswithmembernames class * { native <methods>; }
-keep class app.feldkit.hash.NativeXxh { *; }
-keep class app.feldkit.photo.RawDeveloper { *; }

# libVLC: the Java side is called from native code
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# Archive libraries use reflection and optional dependencies
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-keep class com.github.junrar.** { *; }
-dontwarn com.github.junrar.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**

# Coil decoders and Media3 pick implementations by class name
-keep class coil.decode.** { *; }
-dontwarn coil.**
-dontwarn androidx.media3.**
-dontwarn org.apache.commons.**
-dontwarn aQute.bnd.annotation.**

# Readable crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
