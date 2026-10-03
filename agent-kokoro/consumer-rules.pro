# sherpa-onnx's JNI layer looks up Kotlin classes, fields and methods by name
# (GenerationConfig.extra, OfflineTtsKokoroModelConfig.*, callbacks…), so
# they must survive R8 renaming in host apps.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# The generate() callback is looked up by JNI as invoke([F)Ljava/lang/Integer;.
-keep class com.makemore.agentfrontend.voice.kokoro.SherpaKokoroSynthesizer$SampleCallback { *; }
