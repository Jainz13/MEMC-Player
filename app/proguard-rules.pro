# Release builds are shrunk and optimised by R8 (smaller APK, noticeably smoother Compose UI),
# but names are kept so crash reports and debug recordings stay readable.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# ---- ONNX Runtime (AI frames on the NPU) ----------------------------------------------
# Its native half looks these classes up by name. The TV build compiles against it without
# shipping it, so missing classes are expected there.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ---- SMB (smbj) ---------------------------------------------------------------------------
# smbj wires its message handlers through an annotation-driven event bus and loads crypto
# through BouncyCastle's provider, both by reflection.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
-dontwarn org.bouncycastle.**
-dontwarn javax.el.**
-dontwarn javax.naming.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn java.beans.**
-dontwarn sun.security.**

# ---- FFmpeg audio decoder ---------------------------------------------------------------------
# Media3 creates the renderer by name, and the native decoder reads fields from Java.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class org.jellyfin.media3.** { *; }

# ---- Misc ---------------------------------------------------------------------------------
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
