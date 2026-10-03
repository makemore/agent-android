# ONNX Runtime's JNI layer creates and reads its Java classes (OnnxTensor,
# OrtSession results, exceptions…) by name, so they must survive R8 in host apps.
-keep class ai.onnxruntime.** { *; }
