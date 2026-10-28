#include "echo_processor.h"
#include <stdexcept>

static void Check(int result) {
  if (result != webrtc::AudioProcessing::kNoError) throw std::runtime_error("APM processing failed");
}
EchoProcessor::EchoProcessor() : apm_(webrtc::AudioProcessingBuilder().Create()) {
  if (!apm_) throw std::runtime_error("APM allocation failed");
  webrtc::AudioProcessing::Config config;
  config.echo_canceller.enabled = true;
  config.echo_canceller.mobile_mode = false;
  apm_->ApplyConfig(config);
  webrtc::ProcessingConfig streams;
  streams.input_stream() = streams.output_stream() = capture_;
  streams.reverse_input_stream() = streams.reverse_output_stream() = render_;
  Check(apm_->Initialize(streams));
}
void EchoProcessor::Render(const float* samples) {
  std::array<float, 240> unchanged;
  const float* source[] = {samples};
  float* output[] = {unchanged.data()};
  Check(apm_->ProcessReverseStream(source, render_, render_, output));
}
void EchoProcessor::Capture(const float* samples, int delay_ms, float* output) {
  if (delay_ms < 0 || delay_ms > 500) throw std::runtime_error("APM delay out of range");
  const float* source[] = {samples};
  float* destination[] = {output};
  Check(apm_->set_stream_delay_ms(delay_ms));
  Check(apm_->ProcessStream(source, capture_, capture_, destination));
}
