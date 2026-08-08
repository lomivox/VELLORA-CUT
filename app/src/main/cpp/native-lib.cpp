#include <jni.h>
#include <string>

// Phase 0 milestone target: Kotlin -> JNI -> C++ -> FFmpeg -> input video -> output video
// This stub will be replaced once externalNativeBuild is enabled in build.gradle.kts.

extern "C" JNIEXPORT jstring JNICALL
Java_com_vellora_cut_engine_NativeEngine_ping(JNIEnv *env, jobject /* this */) {
    std::string result = "vellora native engine alive";
    return env->NewStringUTF(result.c_str());
}
