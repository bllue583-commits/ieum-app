#include <jni.h>
#include "whisper.h"
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

// No model text, path, prompt or audio is sent to Android logcat.
static void quiet_log(enum ggml_log_level, const char *, void *) {}
struct Run {
    std::atomic<bool> cancelled{false};
    std::chrono::steady_clock::time_point deadline = std::chrono::steady_clock::now() + std::chrono::seconds(90);
};
static std::mutex runs_mutex;
static std::unordered_map<jlong, std::shared_ptr<Run>> runs;
static std::atomic<jlong> next_id{1};
static std::shared_ptr<Run> find_run(jlong id) {
    std::lock_guard<std::mutex> guard(runs_mutex);
    auto it = runs.find(id); return it == runs.end() ? nullptr : it->second;
}
static bool stopped(void * ptr) {
    auto * run = static_cast<Run *>(ptr);
    return run->cancelled.load() || std::chrono::steady_clock::now() >= run->deadline;
}
static void fail(JNIEnv * env, const char * code) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, code);
}
static std::string quote(const char * text) {
    static const char hex[] = "0123456789abcdef";
    std::string out = "\"";
    for (const auto * p = reinterpret_cast<const unsigned char *>(text); *p; ++p) {
        if (*p == '"' || *p == '\\') { out += '\\'; out += static_cast<char>(*p); }
        else if (*p < 32) { out += "\\u00"; out += hex[*p >> 4]; out += hex[*p & 15]; }
        else out += static_cast<char>(*p);
    }
    return out + '"';
}
extern "C" JNIEXPORT jlong JNICALL Java_kr_callbrief_AndroidWhisperNative_begin(JNIEnv *, jobject) {
    auto id = next_id.fetch_add(1); std::lock_guard<std::mutex> guard(runs_mutex);
    runs.emplace(id, std::make_shared<Run>()); return id;
}
extern "C" JNIEXPORT void JNICALL Java_kr_callbrief_AndroidWhisperNative_cancel(JNIEnv *, jobject, jlong id) {
    auto run = find_run(id); if (run) run->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL Java_kr_callbrief_AndroidWhisperNative_release(JNIEnv *, jobject, jlong id) {
    std::lock_guard<std::mutex> guard(runs_mutex); runs.erase(id);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_kr_callbrief_AndroidWhisperNative_transcribe(
        JNIEnv * env, jobject, jlong id, jstring model, jfloatArray pcm, jstring language) {
    auto run = find_run(id);
    if (!run || stopped(run.get())) { fail(env,"ANDROID_STT_STOPPED"); return nullptr; }
    auto count = env->GetArrayLength(pcm);
    if (count < 1600 || count > 5242880) { fail(env,"ANDROID_STT_PCM_SIZE"); return nullptr; }
    const char * path = env->GetStringUTFChars(model,nullptr);
    auto cp = whisper_context_default_params(); cp.use_gpu = false; cp.flash_attn = false;
    whisper_log_set(quiet_log,nullptr);
    auto * ctx = whisper_init_from_file_with_params(path,cp);
    env->ReleaseStringUTFChars(model,path);
    if (!ctx) { fail(env,"ANDROID_STT_MODEL_LOAD_FAILED"); return nullptr; }
    if (stopped(run.get())) { whisper_free(ctx); fail(env,"ANDROID_STT_STOPPED"); return nullptr; }
    const char * lang = env->GetStringUTFChars(language,nullptr);
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = 2; params.language = lang; params.translate = false;
    params.print_realtime = false; params.print_progress = false; params.print_timestamps = false; params.print_special = false;
    params.no_context = true; params.single_segment = false; params.initial_prompt = nullptr;
    params.abort_callback = stopped; params.abort_callback_user_data = run.get();
    auto * samples = env->GetFloatArrayElements(pcm,nullptr);
    int status = whisper_full(ctx,params,samples,count);
    env->ReleaseFloatArrayElements(pcm,samples,JNI_ABORT);
    env->ReleaseStringUTFChars(language,lang);
    if (status != 0 || stopped(run.get())) { whisper_free(ctx); fail(env,"ANDROID_STT_NOT_COMPLETED"); return nullptr; }
    int segments = whisper_full_n_segments(ctx);
    if (segments < 1 || segments > 256) { whisper_free(ctx); fail(env,"ANDROID_STT_SEGMENT_LIMIT"); return nullptr; }
    std::string json = "{\"transcription\":[";
    for (int i=0; i<segments; ++i) {
        if (i) json += ',';
        json += "{\"offsets\":{\"from\":" + std::to_string(whisper_full_get_segment_t0(ctx,i)*10);
        json += ",\"to\":" + std::to_string(whisper_full_get_segment_t1(ctx,i)*10);
        json += "},\"text\":" + quote(whisper_full_get_segment_text(ctx,i)) + '}';
    }
    json += "],\"runtime\":\"whisper.cpp-v1.7.6-android-cpu\"}";
    whisper_free(ctx);
    if (json.size() > 262144) { fail(env,"ANDROID_STT_OUTPUT_LIMIT"); return nullptr; }
    auto result = env->NewByteArray(static_cast<jsize>(json.size()));
    if (result) env->SetByteArrayRegion(result,0,static_cast<jsize>(json.size()),reinterpret_cast<const jbyte *>(json.data()));
    return result;
}
