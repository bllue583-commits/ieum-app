// r19 자막 실험실: 앱의 JNI(android/app/src/main/cpp/ieum_whisper.cpp transcribeChunk)와 같은 whisper_full 설정으로
// 조각 파일(16 kHz 모노 float32 LE)을 하나씩 돌린다. 앱 기본값: 그리디, temperature_inc·best_of는 라이브러리 기본,
// suppress_nst 끔, token_timestamps 켬, no_context 켬, single_segment 끔, language "ko", 초기 프롬프트 없음, flash_attn 끔, GPU 끔.
// 사용: whisper_app_run <모델> <스레드> <목록 파일(줄마다 "id<TAB>경로")> <출력 jsonl> [초기 프롬프트]
// 이미 출력에 있는 id는 건너뛴다(중단 뒤 이어 하기).
#include "whisper.h"
#include <chrono>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <set>
#include <sstream>
#include <string>
#include <vector>

static std::string quote(const char * s) {
    std::string o = "\"";
    for (const unsigned char * p = (const unsigned char *) s; *p; ++p) {
        unsigned char c = *p;
        if (c == '"' || c == '\\') { o += '\\'; o += (char) c; }
        else if (c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
        else o += (char) c;
    }
    return o + "\"";
}
static void quiet(enum ggml_log_level, const char *, void *) {}

int main(int argc, char ** argv) {
    if (argc < 5) { fprintf(stderr, "usage: %s model threads list out.jsonl [prompt]\n", argv[0]); return 2; }
    const char * model = argv[1]; int threads = atoi(argv[2]);
    std::string prompt = argc > 5 ? argv[5] : "";
    std::set<std::string> done;
    { std::ifstream in(argv[4]); std::string l;
      while (std::getline(in, l)) { auto a = l.find("\"id\":\""); if (a == std::string::npos) continue; a += 6; done.insert(l.substr(a, l.find('"', a) - a)); } }
    std::vector<std::pair<std::string,std::string>> items;
    { std::ifstream in(argv[3]); std::string l;
      while (std::getline(in, l)) { auto t = l.find('\t'); if (t == std::string::npos) continue; auto id = l.substr(0, t); if (!done.count(id)) items.push_back({id, l.substr(t + 1)}); } }
    if (items.empty()) { fprintf(stderr, "nothing to do\n"); return 0; }
    whisper_log_set(quiet, nullptr);
    auto cp = whisper_context_default_params(); cp.use_gpu = false; cp.flash_attn = false;
    whisper_context * ctx = whisper_init_from_file_with_params(model, cp);
    if (!ctx) { fprintf(stderr, "model load failed\n"); return 1; }
    FILE * out = fopen(argv[4], "a");
    const whisper_token eot = whisper_token_eot(ctx);
    for (auto & it : items) {
        std::ifstream f(it.second, std::ios::binary | std::ios::ate);
        size_t bytes = f.tellg(); f.seekg(0);
        std::vector<float> pcm(bytes / 4);
        f.read((char *) pcm.data(), pcm.size() * 4);
        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.suppress_nst = false;
        params.token_timestamps = true;
        params.n_threads = threads; params.language = "ko"; params.translate = false;
        params.print_realtime = false; params.print_progress = false; params.print_timestamps = false; params.print_special = false;
        params.no_context = true; params.single_segment = false;
        params.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();
        auto t0 = std::chrono::steady_clock::now();
        int st = whisper_full(ctx, params, pcm.data(), (int) pcm.size());
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        std::string j = "{\"id\":" + quote(it.first.c_str()) + ",\"status\":" + std::to_string(st) + ",\"audioMs\":" + std::to_string(pcm.size() / 16) +
                        ",\"workMs\":" + std::to_string((long long) ms) + ",\"segments\":[";
        int n = st == 0 ? whisper_full_n_segments(ctx) : 0;
        for (int i = 0; i < n; ++i) {
            if (i) j += ',';
            j += "{\"t0\":" + std::to_string(whisper_full_get_segment_t0(ctx, i) * 10) + ",\"t1\":" + std::to_string(whisper_full_get_segment_t1(ctx, i) * 10);
            j += ",\"text\":" + quote(whisper_full_get_segment_text(ctx, i)) + ",\"tokens\":[";
            bool first = true;
            for (int k = 0; k < whisper_full_n_tokens(ctx, i); ++k) {
                auto d = whisper_full_get_token_data(ctx, i, k);
                if (d.id >= eot) continue;
                if (!first) j += ','; first = false;
                char p[16]; snprintf(p, sizeof p, "%.3f", d.p);
                j += "[" + quote(whisper_full_get_token_text(ctx, i, k)) + "," + p + "]";
            }
            j += "]}";
        }
        j += "]}\n";
        fputs(j.c_str(), out); fflush(out);
        fprintf(stderr, "%s %.1fs audio %.1fs work\n", it.first.c_str(), pcm.size() / 16000.0, ms / 1000);
    }
    fclose(out); whisper_free(ctx);
    return 0;
}
