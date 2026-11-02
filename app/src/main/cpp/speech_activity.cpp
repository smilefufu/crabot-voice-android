#include <jni.h>
#include <array>
#include <algorithm>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include "common_audio/vad/include/webrtc_vad.h"
#include "sherpa-onnx/c-api/cxx-api.h"

namespace api = sherpa_onnx::cxx;
static api::VoiceActivityDetector Confirmed(const std::string& root) {
  api::VadModelConfig config;
  config.silero_vad.model = root + "/silero.onnx";
  config.silero_vad.min_speech_duration = 0.05;
  config.silero_vad.min_silence_duration = 0.05;
  config.silero_vad.max_speech_duration = 16;
  return api::VoiceActivityDetector::Create(config, 1);
}
struct SpeechDetector {
  std::unique_ptr<VadInst, decltype(&WebRtcVad_Free)> candidate{WebRtcVad_Create(), WebRtcVad_Free};
  api::VoiceActivityDetector confirmed;
  std::array<int16_t, 160> partial{};
  size_t count = 0;
  bool active = false;
  explicit SpeechDetector(const std::string& root) : confirmed(Confirmed(root)) {
    if (!candidate || WebRtcVad_Init(candidate.get()) != 0 || !confirmed.Get()) throw std::runtime_error("Cannot create speech detectors");
  }
  void Reset() {
    if (WebRtcVad_Init(candidate.get()) != 0) throw std::runtime_error("Cannot reset VAD");
    confirmed.Reset(); partial.fill(0); count = 0; active = false;
  }
};
static void Error(JNIEnv* env) {
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "本地人声检测失败");
}
extern "C" JNIEXPORT jlong JNICALL Java_com_crabot_voice_NativeSpeechDetector_create(JNIEnv* env, jobject, jstring directory) {
  try {
    const char* text = env->GetStringUTFChars(directory, nullptr);
    std::string root(text); env->ReleaseStringUTFChars(directory, text);
    return reinterpret_cast<jlong>(new SpeechDetector(root));
  } catch (...) { Error(env); return 0; }
}
extern "C" JNIEXPORT jint JNICALL Java_com_crabot_voice_NativeSpeechDetector_feed(JNIEnv* env, jobject, jlong handle, jshortArray pcm) {
  try {
    const int size = env->GetArrayLength(pcm);
    if (size < 1 || size > 320) throw std::runtime_error("Invalid speech frame");
    std::array<int16_t, 320> input{};
    std::array<float, 320> samples{};
    env->GetShortArrayRegion(pcm, 0, size, input.data());
    auto* detector = reinterpret_cast<SpeechDetector*>(handle);
    for (int i = 0; i < size; ++i) {
      samples[i] = input[i] / 32768.f;
      detector->partial[detector->count++] = input[i];
      if (detector->count == detector->partial.size()) {
        const int result = WebRtcVad_Process(detector->candidate.get(), 16000, detector->partial.data(), detector->partial.size());
        if (result < 0) throw std::runtime_error("WebRTC VAD failed");
        detector->active = result != 0; detector->partial.fill(0); detector->count = 0;
      }
    }
    detector->confirmed.AcceptWaveform(samples.data(), size);
    while (!detector->confirmed.IsEmpty()) detector->confirmed.Pop();
    input.fill(0); samples.fill(0);
    return (detector->active ? 1 : 0) | (detector->confirmed.IsDetected() ? 2 : 0);
  } catch (...) { Error(env); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_crabot_voice_NativeSpeechDetector_reset(JNIEnv* env, jobject, jlong handle) {
  try { reinterpret_cast<SpeechDetector*>(handle)->Reset(); } catch (...) { Error(env); }
}
extern "C" JNIEXPORT void JNICALL Java_com_crabot_voice_NativeSpeechDetector_destroy(JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<SpeechDetector*>(handle);
}
