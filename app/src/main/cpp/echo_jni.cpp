#include <jni.h>
#include <algorithm>
#include <cmath>
#include <stdexcept>
#include "echo_processor.h"

static void Error(JNIEnv* env) {
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "本地回声处理失败");
}
extern "C" JNIEXPORT jlong JNICALL Java_com_crabot_voice_NativeEchoProcessor_create(JNIEnv* env, jobject) {
  try { return reinterpret_cast<jlong>(new EchoProcessor()); }
  catch (...) { Error(env); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_crabot_voice_NativeEchoProcessor_destroy(JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<EchoProcessor*>(handle);
}
extern "C" JNIEXPORT void JNICALL Java_com_crabot_voice_NativeEchoProcessor_render(JNIEnv* env, jobject, jlong handle, jshortArray pcm) {
  try {
    if (env->GetArrayLength(pcm) != 240) throw std::runtime_error("Invalid render block");
    std::array<jshort, 240> input;
    std::array<float, 240> samples;
    env->GetShortArrayRegion(pcm, 0, 240, input.data());
    for (int i = 0; i < 240; ++i) samples[i] = input[i] / 32768.f;
    reinterpret_cast<EchoProcessor*>(handle)->Render(samples.data());
    input.fill(0); samples.fill(0);
  } catch (...) { Error(env); }
}
extern "C" JNIEXPORT jshortArray JNICALL Java_com_crabot_voice_NativeEchoProcessor_capture(JNIEnv* env, jobject, jlong handle, jshortArray pcm, jint delay) {
  try {
    if (env->GetArrayLength(pcm) != 160) throw std::runtime_error("Invalid capture block");
    std::array<jshort, 160> input, result;
    std::array<float, 160> samples, output;
    env->GetShortArrayRegion(pcm, 0, 160, input.data());
    for (int i = 0; i < 160; ++i) samples[i] = input[i] / 32768.f;
    reinterpret_cast<EchoProcessor*>(handle)->Capture(samples.data(), delay, output.data());
    for (int i = 0; i < 160; ++i) {
      if (!std::isfinite(output[i])) throw std::runtime_error("Non-finite APM output");
      result[i] = static_cast<jshort>(std::clamp(std::lround(output[i] * 32768.f), -32768L, 32767L));
    }
    auto value = env->NewShortArray(160);
    if (value) env->SetShortArrayRegion(value, 0, 160, result.data());
    input.fill(0); samples.fill(0); output.fill(0); result.fill(0);
    return value;
  } catch (...) { Error(env); return nullptr; }
}
