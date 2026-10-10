# sherpa-onnx's JNI reads its config classes' fields by name and calls back
# into Kotlin (invoke([F)Ljava/lang/Integer;) - nothing there may be renamed.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.rusty.museecho.gadget.NeuralVoice$SampleSink { *; }

# Vosk talks to its native library through JNA, which looks everything up by name.
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

-dontwarn org.bouncycastle.**
-dontwarn org.apache.commons.compress.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Readable crash stack traces in the error log.
-keepattributes SourceFile,LineNumberTable
