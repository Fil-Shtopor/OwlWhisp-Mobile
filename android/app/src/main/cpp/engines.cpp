#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <stdexcept>
#include <cstdlib>
extern "C" {
#include "phonon2.h"
#include "threadpool.h"
#include "voxrt.h"
#ifdef OWL_WHISTLE
#include "needle.h"
#endif
}
static void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
extern "C" JNIEXPORT jstring JNICALL Java_ai_owlwhisp_mobile_NativeEngines_transcribe(
        JNIEnv* env, jobject, jstring kind, jstring directory, jfloatArray audio) {
    const char* k = env->GetStringUTFChars(kind, nullptr);
    const char* d = env->GetStringUTFChars(directory, nullptr);
    std::string engine(k), path(d);
    env->ReleaseStringUTFChars(kind, k); env->ReleaseStringUTFChars(directory, d);
    std::vector<float> pcm(env->GetArrayLength(audio));
    env->GetFloatArrayRegion(audio, 0, pcm.size(), pcm.data());
    std::string result;
    try {
        if (engine == "redux") {
            setenv("RAYON_NUM_THREADS", "2", 1);
            auto* model = voxrt_new(path.c_str());
            if (!model) throw std::runtime_error(voxrt_last_error());
            auto* text = voxrt_transcribe(model, pcm.data(), pcm.size(), 16000);
            if (text) { result = text->text; voxrt_transcript_free(text); }
            else { std::string reason(voxrt_last_error()); voxrt_free(model); throw std::runtime_error(reason); }
            voxrt_free(model);
        } else if (engine == "phonon") {
            auto* pool = p2_pool_create(2); p2_set_pool(pool);
            auto* model = p2_engine_open(path.c_str());
            if (!model) { p2_set_pool(nullptr); p2_pool_destroy(pool); throw std::runtime_error("Cannot load Phonon-2 weights"); }
            P2Result text{};
            int status = p2_transcribe(model, pcm.data(), pcm.size(), 16000, &text);
            if (status == 0 && text.text) result = text.text;
            p2_result_free(&text); p2_engine_close(model);
            p2_set_pool(nullptr); p2_pool_destroy(pool);
            if (status != 0) throw std::runtime_error("Phonon-2 transcription failed");
        } else if (engine == "whistle") {
#ifdef OWL_WHISTLE
            setenv("NEEDLE_TELEMETRY", "0", 1); setenv("DO_NOT_TRACK", "1", 1);
            std::ifstream file(path + "/whistle.cact", std::ios::binary | std::ios::ate);
            if (!file) throw std::runtime_error("Whistle model missing");
            std::vector<unsigned char> weights(static_cast<size_t>(file.tellg()));
            file.seekg(0); file.read(reinterpret_cast<char*>(weights.data()), weights.size());
            if (needle_load(weights.data(), weights.size()) < 0) throw std::runtime_error(needle_last_error());
            std::vector<char> text(65536);
            if (needle_transcribe(pcm.data(), pcm.size(), nullptr, nullptr, 0, text.data(), text.size()) < 0)
                throw std::runtime_error(needle_last_error());
            result = text.data();
#else
            throw std::runtime_error("Whistle requires an arm64 Android device");
#endif
        } else throw std::runtime_error("Unknown native engine");
        jbyteArray bytes = env->NewByteArray(result.size());
        env->SetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<const jbyte*>(result.data()));
        jclass strings = env->FindClass("java/lang/String");
        jmethodID ctor = env->GetMethodID(strings, "<init>", "([BLjava/lang/String;)V");
        return (jstring)env->NewObject(strings, ctor, bytes, env->NewStringUTF("UTF-8"));
    } catch (const std::exception& error) { fail(env, error.what()); return nullptr; }
}
