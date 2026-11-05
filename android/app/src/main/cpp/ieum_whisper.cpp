#include <jni.h>
#include "whisper.h"
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

// v3.3 (DESIGN_V4 3.2-1): the numbers of whisper_print_timings, read back from its log lines. The fallback counts
// (n_fail_p/h) are not in the public API, so this is the only way to see them. Numbers only.
struct Timings {
    float encode_ms = 0, decode_ms = 0, batchd_ms = 0, prompt_ms = 0, sample_ms = 0;
    int fail_p = 0, fail_h = 0;
};
static std::mutex timings_mutex;
static Timings * timings_capture = nullptr; // set only while whisper_print_timings runs, under timings_mutex
static void read_ms(const char * text, const char * label, float & out) {
    const char * p = std::strstr(text, label);
    if (p) { float v = 0; if (std::sscanf(p + std::strlen(label), "%f", &v) == 1) out = v; }
}
// No model text, path, prompt or audio is sent to Android logcat. Only the timing lines are read, as numbers.
static void quiet_log(enum ggml_log_level, const char * text, void *) {
    Timings * t = timings_capture;
    if (!t || !text || !std::strstr(text, "whisper_print_timings")) return;
    const char * f = std::strstr(text, "fallbacks =");
    if (f) { int p = 0, h = 0; if (std::sscanf(f + std::strlen("fallbacks ="), "%d p / %d h", &p, &h) == 2) { t->fail_p = p; t->fail_h = h; } }
    read_ms(text, "encode time =", t->encode_ms);
    read_ms(text, "decode time =", t->decode_ms);
    read_ms(text, "batchd time =", t->batchd_ms);
    read_ms(text, "prompt time =", t->prompt_ms);
    read_ms(text, "sample time =", t->sample_ms);
}
static Timings timings_of(whisper_context * ctx) {
    Timings t;
    std::lock_guard<std::mutex> guard(timings_mutex);
    timings_capture = &t; whisper_print_timings(ctx); timings_capture = nullptr;
    return t;
}
static std::string fixed(double v, const char * format) { char b[48]; std::snprintf(b, sizeof b, format, v); return b; }
// One handle = one loaded model for one recording. It owns the cancel flag and the per-chunk deadline.
struct Handle {
    whisper_context * ctx = nullptr;
    int threads = 1;
    std::atomic<bool> cancelled{false};
    std::atomic<long long> deadline_ms{0}; // steady_clock milliseconds; 0 = no chunk running
    explicit Handle(whisper_context * c, int t) : ctx(c), threads(t) {}
    ~Handle() { if (ctx) whisper_free(ctx); }
    Handle(const Handle &) = delete;
    Handle & operator=(const Handle &) = delete;
};
static long long now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
static std::mutex handles_mutex;
static std::unordered_map<jlong, std::shared_ptr<Handle>> handles;
static std::atomic<jlong> next_id{1};
static std::shared_ptr<Handle> find_handle(jlong id) {
    std::lock_guard<std::mutex> guard(handles_mutex);
    auto it = handles.find(id); return it == handles.end() ? nullptr : it->second;
}
static bool stopped(void * ptr) {
    auto * h = static_cast<Handle *>(ptr);
    if (h->cancelled.load()) return true;
    long long d = h->deadline_ms.load();
    return d != 0 && now_ms() >= d;
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
extern "C" JNIEXPORT void JNICALL Java_kr_callbrief_AndroidWhisperNative_cancel(JNIEnv *, jobject, jlong handle) {
    auto h = find_handle(handle); if (h) h->cancelled.store(true);
}
// Frees the model. Safe to call twice; a chunk still running keeps its own reference until it returns.
extern "C" JNIEXPORT void JNICALL Java_kr_callbrief_AndroidWhisperNative_close(JNIEnv *, jobject, jlong handle) {
    std::shared_ptr<Handle> h;
    { std::lock_guard<std::mutex> guard(handles_mutex);
      auto it = handles.find(handle); if (it == handles.end()) return; h = it->second; handles.erase(it); }
    h->cancelled.store(true);
}
// Loads the model once; same quiet, CPU-only engine as before.
extern "C" JNIEXPORT jlong JNICALL Java_kr_callbrief_AndroidWhisperNative_open(JNIEnv * env, jobject, jstring model, jint threads) {
    if (threads < 1) threads = 1; if (threads > 8) threads = 8;
    const char * path = env->GetStringUTFChars(model,nullptr);
    if (!path) { fail(env,"ANDROID_STT_MODEL_LOAD_FAILED"); return 0; }
    auto cp = whisper_context_default_params(); cp.use_gpu = false; cp.flash_attn = false; // measured on Galaxy S26: flash attention is ~1.7x slower on CPU
    whisper_log_set(quiet_log,nullptr);
    auto * ctx = whisper_init_from_file_with_params(path,cp);
    env->ReleaseStringUTFChars(model,path);
    if (!ctx) { fail(env,"ANDROID_STT_MODEL_LOAD_FAILED"); return 0; }
    auto h = std::make_shared<Handle>(ctx,static_cast<int>(threads));
    auto id = next_id.fetch_add(1);
    std::lock_guard<std::mutex> guard(handles_mutex);
    handles.emplace(id,h); return id;
}
// A silent chunk is valid here (0 segments); the pipeline drops it and keeps exact offsets.
// [seconds] is the deadline of this chunk alone; cancel() ends it at once.
// [prompt] (F4): Whisper's initial prompt (vocabulary hint); empty = none, as before. Off by default on the Kotlin side.
// [options] (v3.3, DESIGN_V4 3.2-5..8; only the golden variants change them, the app passes the defaults):
// {temperature_inc (<0: library default 0.2), best_of (<1: default 5), beam_size (<2: greedy as before), suppress_nst (>0.5: on)}.
// Output (v3.3, 3.2-1): each segment also carries "tokens":[[text,p,t0ms,t1ms],...] (special tokens left out, token-level
// timestamps on) and the piece carries "timings" (ms and fallback counts of this piece). Old readers ignore both keys.
extern "C" JNIEXPORT jbyteArray JNICALL Java_kr_callbrief_AndroidWhisperNative_transcribeChunk(
        JNIEnv * env, jobject, jlong handle, jfloatArray pcm, jstring language, jint seconds, jstring prompt, jfloatArray options) {
    const int min_segments = 0, max_segments = 2048; const size_t max_output = 1048576;
    if (seconds < 30) seconds = 30; if (seconds > 3600) seconds = 3600;
    auto h = find_handle(handle);
    if (!h) { fail(env,"ANDROID_STT_STOPPED"); return nullptr; }
    h->deadline_ms.store(now_ms() + static_cast<long long>(seconds) * 1000);
    if (stopped(h.get())) { fail(env,"ANDROID_STT_STOPPED"); return nullptr; }
    auto count = env->GetArrayLength(pcm);
    if (count < 1600 || count > 5242880) { fail(env,"ANDROID_STT_PCM_SIZE"); return nullptr; }
    if (prompt && env->GetStringUTFLength(prompt) > 1024) { fail(env,"ANDROID_STT_PROMPT"); return nullptr; }
    float opt[4] = {-1.0f, 0.0f, 0.0f, 0.0f};
    if (options) { jsize n = env->GetArrayLength(options); if (n > 4) n = 4; if (n > 0) env->GetFloatArrayRegion(options, 0, n, opt); }
    const char * lang = env->GetStringUTFChars(language,nullptr);
    if (!lang) { fail(env,"ANDROID_STT_NOT_COMPLETED"); return nullptr; }
    const char * hint = prompt ? env->GetStringUTFChars(prompt,nullptr) : nullptr;
    if (prompt && !hint) { env->ReleaseStringUTFChars(language,lang); fail(env,"ANDROID_STT_NOT_COMPLETED"); return nullptr; }
    const int beam = static_cast<int>(opt[2]);
    auto params = whisper_full_default_params(beam >= 2 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    if (beam >= 2) params.beam_search.beam_size = beam > 8 ? 8 : beam;
    if (opt[0] >= 0.0f) params.temperature_inc = opt[0] > 1.0f ? 1.0f : opt[0];
    if (opt[1] >= 1.0f) params.greedy.best_of = opt[1] > 8.0f ? 8 : static_cast<int>(opt[1]);
    params.suppress_nst = opt[3] > 0.5f;
    params.token_timestamps = true;
    params.n_threads = h->threads; params.language = lang; params.translate = false;
    params.print_realtime = false; params.print_progress = false; params.print_timestamps = false; params.print_special = false;
    params.no_context = true; params.single_segment = false; params.initial_prompt = (hint && *hint) ? hint : nullptr;
    params.abort_callback = stopped; params.abort_callback_user_data = h.get();
    auto * samples = env->GetFloatArrayElements(pcm,nullptr);
    if (!samples) { if (hint) env->ReleaseStringUTFChars(prompt,hint); env->ReleaseStringUTFChars(language,lang); fail(env,"ANDROID_STT_NOT_COMPLETED"); return nullptr; }
    const Timings before = timings_of(h->ctx); // the fallback counts only grow: this piece's are the difference
    whisper_reset_timings(h->ctx);
    int status = whisper_full(h->ctx,params,samples,count);
    env->ReleaseFloatArrayElements(pcm,samples,JNI_ABORT);
    if (hint) env->ReleaseStringUTFChars(prompt,hint);
    env->ReleaseStringUTFChars(language,lang);
    if (status != 0 || stopped(h.get())) { fail(env,"ANDROID_STT_NOT_COMPLETED"); return nullptr; }
    int segments = whisper_full_n_segments(h->ctx);
    if (segments < min_segments || segments > max_segments) { fail(env,"ANDROID_STT_SEGMENT_LIMIT"); return nullptr; }
    const Timings after = timings_of(h->ctx);
    const whisper_token eot = whisper_token_eot(h->ctx);
    std::string json = "{\"transcription\":[";
    for (int i=0; i<segments; ++i) {
        if (i) json += ',';
        json += "{\"offsets\":{\"from\":" + std::to_string(whisper_full_get_segment_t0(h->ctx,i)*10);
        json += ",\"to\":" + std::to_string(whisper_full_get_segment_t1(h->ctx,i)*10);
        json += "},\"text\":" + quote(whisper_full_get_segment_text(h->ctx,i));
        json += ",\"tokens\":[";
        const int tokens = whisper_full_n_tokens(h->ctx,i); bool first = true;
        for (int j=0; j<tokens; ++j) {
            const whisper_token_data d = whisper_full_get_token_data(h->ctx,i,j);
            if (d.id >= eot) continue; // timestamps and the other special tokens
            if (!first) { json += ','; }
            first = false;
            json += '[' + quote(whisper_full_get_token_text(h->ctx,i,j)) + ',' + fixed(d.p,"%.3f") + ',' + std::to_string(d.t0*10) + ',' + std::to_string(d.t1*10) + ']';
        }
        json += "]}";
    }
    json += "],\"timings\":{\"encodeMs\":" + fixed(after.encode_ms,"%.1f") + ",\"decodeMs\":" + fixed(after.decode_ms,"%.1f");
    json += ",\"batchdMs\":" + fixed(after.batchd_ms,"%.1f") + ",\"promptMs\":" + fixed(after.prompt_ms,"%.1f") + ",\"sampleMs\":" + fixed(after.sample_ms,"%.1f");
    json += ",\"fallbackP\":" + std::to_string(after.fail_p - before.fail_p) + ",\"fallbackH\":" + std::to_string(after.fail_h - before.fail_h) + '}';
    json += ",\"runtime\":\"whisper.cpp-v1.7.6-android-cpu\"}";
    h->deadline_ms.store(0);
    if (json.size() > max_output) { fail(env,"ANDROID_STT_OUTPUT_LIMIT"); return nullptr; }
    auto result = env->NewByteArray(static_cast<jsize>(json.size()));
    if (result) env->SetByteArrayRegion(result,0,static_cast<jsize>(json.size()),reinterpret_cast<const jbyte *>(json.data()));
    return result;
}
// v3.3 (DESIGN_V4 3.2-7): the number of tokens [text] makes (the initial prompt's limit is in tokens, not letters).
// -1 when the handle is gone or the text could not be read.
extern "C" JNIEXPORT jint JNICALL Java_kr_callbrief_AndroidWhisperNative_countTokens(JNIEnv * env, jobject, jlong handle, jstring text) {
    auto h = find_handle(handle);
    if (!h || !text) return -1;
    const char * t = env->GetStringUTFChars(text,nullptr);
    if (!t) return -1;
    std::vector<whisper_token> tokens(2048);
    int n = whisper_tokenize(h->ctx,t,tokens.data(),static_cast<int>(tokens.size()));
    env->ReleaseStringUTFChars(text,t);
    return n < 0 ? -n : n;
}
