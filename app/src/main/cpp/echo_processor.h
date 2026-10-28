#pragma once
#include <array>
#include "modules/audio_processing/include/audio_processing.h"

// One owner serializes render and capture; fixed 10 ms normalized float blocks.
class EchoProcessor {
 public:
  EchoProcessor();
  void Render(const float* samples);
  void Capture(const float* samples, int delay_ms, float* output);
 private:
  rtc::scoped_refptr<webrtc::AudioProcessing> apm_;
  webrtc::StreamConfig capture_{16000, 1};
  webrtc::StreamConfig render_{24000, 1};
};
