// Isolated public-file probe; never connected to device capture or playback.
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <vector>
#include "modules/audio_processing/aec3/echo_canceller3.h"
#include "modules/audio_processing/include/audio_processing.h"

// This source version requires export enabled on both APM and the AEC3 factory.
class LinearOutputFactory : public webrtc::EchoControlFactory {
  std::unique_ptr<webrtc::EchoControl> Create(int rate, int render, int capture) override {
    webrtc::EchoCanceller3Config config;
    config.filter.export_linear_aec_output = true;
    return std::make_unique<webrtc::EchoCanceller3>(config, rate, render, capture);
  }
};

std::vector<float> read(const char* path) {
  std::ifstream in(path, std::ios::binary | std::ios::ate);
  if (!in || in.tellg() <= 0 || in.tellg() % sizeof(float) != 0)
    throw std::runtime_error("invalid input file");
  std::vector<float> values(static_cast<size_t>(in.tellg()) / sizeof(float));
  in.seekg(0);
  in.read(reinterpret_cast<char*>(values.data()), values.size() * sizeof(float));
  if (!in || !std::all_of(values.begin(), values.end(), [](float x) {
        return std::isfinite(x) && std::abs(x) <= 1.f;
      })) throw std::runtime_error("invalid input samples");
  return values;
}

void check(int result) {
  if (result != webrtc::AudioProcessing::kNoError)
    throw std::runtime_error("APM return code " + std::to_string(result));
}

int main(int argc, char** argv) {
  try {
    if (argc != 6 && argc != 7) throw std::runtime_error("render.f32 capture.f32 out.f32 aec[0|1] delay_ms [linear.f32]");
    const auto render = read(argv[1]), capture = read(argv[2]);
    if (capture.size() % 160 || render.size() % 240 || capture.size() / 160 != render.size() / 240)
      throw std::runtime_error("input duration/frame mismatch");
    const int enabled = std::stoi(argv[4]), delay = std::stoi(argv[5]);
    if ((enabled != 0 && enabled != 1) || delay < 0 || delay > 500)
      throw std::runtime_error("invalid configuration");
    auto start = std::chrono::steady_clock::now();
    webrtc::AudioProcessingBuilder builder;
    if (argc == 7) builder.SetEchoControlFactory(std::make_unique<LinearOutputFactory>());
    rtc::scoped_refptr<webrtc::AudioProcessing> apm = builder.Create();
    if (!apm) throw std::runtime_error("APM allocation failed");
    webrtc::AudioProcessing::Config config;
    config.echo_canceller.enabled = enabled;
    config.echo_canceller.mobile_mode = false;
    config.echo_canceller.export_linear_aec_output = argc == 7;
    if (argc == 7 && !enabled) throw std::runtime_error("linear output requires AEC");
    apm->ApplyConfig(config);
    webrtc::ProcessingConfig streams;
    streams.input_stream() = streams.output_stream() = webrtc::StreamConfig(16000, 1);
    streams.reverse_input_stream() = streams.reverse_output_stream() = webrtc::StreamConfig(24000, 1);
    check(apm->Initialize(streams));
    const double init_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    std::ofstream output(argv[3], std::ios::binary | std::ios::trunc);
    if (!output) throw std::runtime_error("cannot open output");
    std::ofstream linear;
    if (argc == 7) {
      linear.open(argv[6], std::ios::binary | std::ios::trunc);
      if (!linear) throw std::runtime_error("cannot open linear output");
    }
    std::vector<double> times;
    float render_difference = 0;
    for (size_t frame = 0; frame < capture.size() / 160; ++frame) {
      float reverse_out[240], out[160];
      const float* reverse[] = {render.data() + frame * 240};
      const float* source[] = {capture.data() + frame * 160};
      float* reverse_dest[] = {reverse_out};
      float* dest[] = {out};
      start = std::chrono::steady_clock::now();
      check(apm->ProcessReverseStream(reverse, streams.reverse_input_stream(), streams.reverse_output_stream(), reverse_dest));
      check(apm->set_stream_delay_ms(delay));
      check(apm->ProcessStream(source, streams.input_stream(), streams.output_stream(), dest));
      times.push_back(std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - start).count());
      for (size_t i = 0; i < 240; ++i) render_difference = std::max(render_difference, std::abs(reverse_out[i] - reverse[0][i]));
      if (!std::all_of(out, out + 160, [](float x) { return std::isfinite(x); }))
        throw std::runtime_error("non-finite output");
      output.write(reinterpret_cast<const char*>(out), sizeof(out));
      if (argc == 7) {
        std::array<std::array<float, 160>, 1> samples;
        if (!apm->GetLinearAecOutput(rtc::ArrayView<std::array<float, 160>>(samples)))
          throw std::runtime_error("linear output unavailable");
        // AudioBuffer exports float S16; the normal float stream API is [-1, 1].
        for (float& sample : samples[0]) sample /= 32768.f;
        linear.write(reinterpret_cast<const char*>(samples[0].data()), 160 * sizeof(float));
      }
    }
    output.close();
    if (!output) throw std::runtime_error("output write failed");
    if (argc == 7) { linear.close(); if (!linear) throw std::runtime_error("linear output write failed"); }
    auto stats = apm->GetStatistics();
    std::cout << "{\"frames\":" << times.size() << ",\"init_ms\":" << init_ms
              << ",\"render_max_difference\":" << render_difference << ",\"last_reported_delay_ms\":";
    if (stats.delay_ms) std::cout << *stats.delay_ms; else std::cout << "null";
    std::cout << ",\"processing_us\":[";
    for (size_t i = 0; i < times.size(); ++i) { if (i) std::cout << ','; std::cout << times[i]; }
    std::cout << "]}\n";
  } catch (const std::exception& e) {
    std::cerr << e.what() << '\n';
    return 1;
  }
}
