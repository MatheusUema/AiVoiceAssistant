# O R8 não vê as chamadas vindas do JNI: preserve nomes e assinaturas da ponte.
-keepclasseswithmembernames,includedescriptorclasses class com.voiceassistant.llama.LlamaBridge {
    native <methods>;
}
-keep class com.voiceassistant.llama.LlamaBridge { *; }
