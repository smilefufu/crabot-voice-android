#include <jni.h>
#include <algorithm>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include "sherpa-onnx/c-api/cxx-api.h"

namespace api = sherpa_onnx::cxx;
struct Models {
  api::SpeakerEmbeddingExtractor embedding;
  api::OfflineSpeakerDiarization diarizer;
  api::VoiceActivityDetector vad;
  api::KeywordSpotter kws;
  api::OnlineStream stream;
};
static void Error(JNIEnv *env, const char *message) {
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
static std::string Text(JNIEnv *env, jstring value) {
  const char *chars = env->GetStringUTFChars(value, nullptr);
  std::string result(chars); env->ReleaseStringUTFChars(value, chars); return result;
}
static std::vector<float> Samples(JNIEnv *env, jshortArray input) {
  const auto n = env->GetArrayLength(input);
  if (n < 1 || n > 240000) throw std::runtime_error("Invalid finite audio");
  std::vector<jshort> pcm(n); env->GetShortArrayRegion(input, 0, n, pcm.data());
  std::vector<float> result(n);
  for (int i = 0; i < n; ++i) result[i] = pcm[i] / 32768.f;
  std::fill(pcm.begin(), pcm.end(), 0); return result;
}
static jfloatArray Floats(JNIEnv *env, const std::vector<float> &values) {
  auto out = env->NewFloatArray(values.size());
  if (out) env->SetFloatArrayRegion(out, 0, values.size(), values.data());
  return out;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_crabot_voice_NativeModels_create(JNIEnv *env, jobject, jstring dir) {
  try {
    const auto root = Text(env, dir) + "/";
    api::SpeakerEmbeddingExtractorConfig ec; ec.model = root + "campplus.onnx"; ec.num_threads = 2;
    api::OfflineSpeakerDiarizationConfig dc; dc.embedding = ec;
    dc.segmentation.pyannote.model = root + "segmentation.onnx"; dc.segmentation.num_threads = 2;
    dc.clustering.num_clusters = -1; dc.clustering.threshold = 0.5;
    dc.min_duration_on = 0; dc.min_duration_off = 0; // Retain short/negative speech; identity gate decides later.
    api::VadModelConfig vc; vc.silero_vad.model = root + "silero.onnx";
    vc.silero_vad.min_speech_duration = 0.05; vc.silero_vad.min_silence_duration = 0.05;
    vc.silero_vad.max_speech_duration = 16;
    api::KeywordSpotterConfig kc;
    kc.model_config.transducer.encoder = root + "encoder.onnx";
    kc.model_config.transducer.decoder = root + "decoder.onnx";
    kc.model_config.transducer.joiner = root + "joiner.onnx";
    kc.model_config.tokens = root + "tokens.txt";
    kc.keywords_buf = "n ǐ h ǎo p áng x iè @你好螃蟹";
    kc.keywords_score = 1; kc.keywords_threshold = 0.25;
    auto m = std::make_unique<Models>(Models{api::SpeakerEmbeddingExtractor::Create(ec), api::OfflineSpeakerDiarization::Create(dc), api::VoiceActivityDetector::Create(vc, 1), api::KeywordSpotter::Create(kc), api::OnlineStream(nullptr)});
    if (!m->embedding.Get() || !m->diarizer.Get() || !m->vad.Get() || !m->kws.Get()) throw std::runtime_error("Cannot load voice models");
    m->stream = m->kws.CreateStream();
    return reinterpret_cast<jlong>(m.release());
  } catch (...) { Error(env, "端侧模型加载失败"); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_crabot_voice_NativeModels_destroy(JNIEnv *, jobject, jlong ptr) { delete reinterpret_cast<Models *>(ptr); }
extern "C" JNIEXPORT void JNICALL Java_com_crabot_voice_NativeModels_reset(JNIEnv *env, jobject, jlong ptr, jstring keyword) {
  try { auto m = reinterpret_cast<Models *>(ptr); m->vad.Reset(); m->stream = m->kws.CreateStream(Text(env, keyword)); }
  catch (...) { Error(env, "唤醒词初始化失败"); }
}
extern "C" JNIEXPORT jint JNICALL Java_com_crabot_voice_NativeModels_feed(JNIEnv *env, jobject, jlong ptr, jshortArray input, jboolean vad, jboolean kws) {
  try {
    auto m = reinterpret_cast<Models *>(ptr); auto samples = Samples(env, input);
    if (vad) { m->vad.AcceptWaveform(samples.data(), samples.size()); while (!m->vad.IsEmpty()) m->vad.Pop(); }
    bool hit = false;
    if (kws) {
      m->stream.AcceptWaveform(16000, samples.data(), samples.size());
      while (m->kws.IsReady(&m->stream)) m->kws.Decode(&m->stream);
      hit = !m->kws.GetResult(&m->stream).keyword.empty();
    }
    if (hit) m->kws.Reset(&m->stream);
    std::fill(samples.begin(), samples.end(), 0);
    return (vad && m->vad.IsDetected() ? 1 : 0) | (hit ? 2 : 0);
  } catch (...) { Error(env, "端侧音频检测失败"); return 0; }
}
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_crabot_voice_NativeModels_embedding(JNIEnv *env, jobject, jlong ptr, jshortArray input) {
  try {
    auto m = reinterpret_cast<Models *>(ptr); auto samples = Samples(env, input);
    auto stream = m->embedding.CreateStream(); stream.AcceptWaveform(16000, samples.data(), samples.size()); stream.InputFinished();
    auto v = m->embedding.IsReady(&stream) ? m->embedding.ComputeEmbedding(&stream) : std::vector<float>{};
    std::fill(samples.begin(), samples.end(), 0); auto out = Floats(env, v); std::fill(v.begin(), v.end(), 0); return out;
  } catch (...) { Error(env, "声纹提取失败"); return nullptr; }
}
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_crabot_voice_NativeModels_segments(JNIEnv *env, jobject, jlong ptr, jshortArray input) {
  try {
    auto samples = Samples(env, input); auto m = reinterpret_cast<Models *>(ptr);
    auto segments = m->diarizer.Process(samples.data(), samples.size()); std::fill(samples.begin(), samples.end(), 0);
    std::sort(segments.begin(), segments.end(), [](auto &a, auto &b) { return a.start < b.start; });
    std::vector<float> out; out.reserve(segments.size() * 3);
    for (auto &s : segments) { out.push_back(s.start); out.push_back(s.end); out.push_back(s.speaker); }
    return Floats(env, out);
  } catch (...) { Error(env, "说话人分段失败"); return nullptr; }
}
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_crabot_voice_NativeModels_matches(JNIEnv *env, jobject, jlong ptr, jfloatArray query, jobjectArray candidates) {
  try {
    auto m = reinterpret_cast<Models *>(ptr); const int dim = m->embedding.Dim();
    if (env->GetArrayLength(query) != dim) throw std::runtime_error("Invalid embedding");
    auto manager = api::SpeakerEmbeddingManager::Create(dim); std::vector<float> v(dim);
    for (int i = 0; i < env->GetArrayLength(candidates); ++i) {
      auto array = static_cast<jfloatArray>(env->GetObjectArrayElement(candidates, i));
      if (env->GetArrayLength(array) != dim) { env->DeleteLocalRef(array); throw std::runtime_error("Invalid enrolled embedding"); }
      env->GetFloatArrayRegion(array, 0, dim, v.data()); manager.Add(std::to_string(i), v.data()); env->DeleteLocalRef(array);
    }
    env->GetFloatArrayRegion(query, 0, dim, v.data()); const auto results = manager.GetBestMatches(v.data(), -1.0, 2);
    std::fill(v.begin(), v.end(), 0); std::vector<float> out;
    for (auto &r : results) { out.push_back(std::stoi(r.name)); out.push_back(r.score); }
    return Floats(env, out);
  } catch (...) { Error(env, "声纹比对失败"); return nullptr; }
}
