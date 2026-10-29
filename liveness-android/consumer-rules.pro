# Apps that shrink their code (R8) must keep what is reached by reflection or from native code.
# ONNX Runtime and OpenCV call back into their Java classes from JNI.
-keep class ai.onnxruntime.** { *; }
-keep class org.opencv.** { *; }
# Message bundles are looked up by name.
-keep class io.mosip.registration.liveness.i18n.** { *; }
