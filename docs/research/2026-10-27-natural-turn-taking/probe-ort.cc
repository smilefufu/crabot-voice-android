// Offline oracle for fixed public features; no microphone, speaker or device data.
#include <chrono>
#include <cmath>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>
#include "onnxruntime_c_api.h"

int main(int argc, char **argv) {
  const OrtApi *api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
  if (!api || argc < 3) return 2;
  auto check = [api](OrtStatus *status) {
    if (!status) return;
    const std::string message = api->GetErrorMessage(status);
    api->ReleaseStatus(status);
    throw std::runtime_error(message);
  };
  try {
    OrtEnv *envRaw = nullptr;
    check(api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "offline-turn-probe", &envRaw));
    std::unique_ptr<OrtEnv, decltype(api->ReleaseEnv)> env(envRaw, api->ReleaseEnv);
    OrtSessionOptions *optionsRaw = nullptr;
    check(api->CreateSessionOptions(&optionsRaw));
    std::unique_ptr<OrtSessionOptions, decltype(api->ReleaseSessionOptions)> options(optionsRaw, api->ReleaseSessionOptions);
    check(api->SetIntraOpNumThreads(options.get(), 1));
    check(api->SetInterOpNumThreads(options.get(), 1));
    check(api->SetSessionExecutionMode(options.get(), ORT_SEQUENTIAL));
    check(api->SetSessionGraphOptimizationLevel(options.get(), ORT_ENABLE_ALL));
    OrtSession *sessionRaw = nullptr;
    check(api->CreateSession(env.get(), argv[1], options.get(), &sessionRaw));
    std::unique_ptr<OrtSession, decltype(api->ReleaseSession)> session(sessionRaw, api->ReleaseSession);
    OrtMemoryInfo *memoryRaw = nullptr;
    check(api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memoryRaw));
    std::unique_ptr<OrtMemoryInfo, decltype(api->ReleaseMemoryInfo)> memory(memoryRaw, api->ReleaseMemoryInfo);
    OrtAllocator *allocator = nullptr;
    check(api->GetAllocatorWithDefaultOptions(&allocator));
    char *outputName = nullptr;
    check(api->SessionGetOutputName(session.get(), 0, allocator, &outputName));
    const std::string output(outputName);
    allocator->Free(allocator, outputName);
    const char *inputNames[] = {"input_features"}, *outputNames[] = {output.c_str()};
    for (int i = 2; i < argc; ++i) {
      std::vector<float> features(80 * 800);
      std::ifstream file(argv[i], std::ios::binary);
      file.read(reinterpret_cast<char *>(features.data()), features.size() * sizeof(float));
      if (!file || file.peek() != std::char_traits<char>::eof()) throw std::runtime_error("Expected exactly 80x800 float32 features");
      const int64_t shape[] = {1, 80, 800};
      OrtValue *inputRaw = nullptr, *outputRaw = nullptr;
      check(api->CreateTensorWithDataAsOrtValue(memory.get(), features.data(), features.size() * sizeof(float), shape, 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &inputRaw));
      std::unique_ptr<OrtValue, decltype(api->ReleaseValue)> input(inputRaw, api->ReleaseValue);
      const OrtValue *inputs[] = {input.get()};
      const auto began = std::chrono::steady_clock::now();
      check(api->Run(session.get(), nullptr, inputNames, inputs, 1, outputNames, 1, &outputRaw));
      const double milliseconds = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - began).count();
      std::unique_ptr<OrtValue, decltype(api->ReleaseValue)> result(outputRaw, api->ReleaseValue);
      OrtTensorTypeAndShapeInfo *shapeRaw = nullptr;
      check(api->GetTensorTypeAndShape(result.get(), &shapeRaw));
      std::unique_ptr<OrtTensorTypeAndShapeInfo, decltype(api->ReleaseTensorTypeAndShapeInfo)> resultShape(shapeRaw, api->ReleaseTensorTypeAndShapeInfo);
      ONNXTensorElementDataType type;
      size_t elements = 0;
      check(api->GetTensorElementType(resultShape.get(), &type));
      check(api->GetTensorShapeElementCount(resultShape.get(), &elements));
      if (type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || elements != 1) throw std::runtime_error("Expected one float32 probability");
      float *probability = nullptr;
      check(api->GetTensorMutableData(result.get(), reinterpret_cast<void **>(&probability)));
      if (!std::isfinite(probability[0]) || probability[0] < 0 || probability[0] > 1) throw std::runtime_error("Invalid probability");
      std::cout << i - 2 << " " << probability[0] << " " << milliseconds << std::endl;
    }
  } catch (const std::exception &error) {
    std::cerr << error.what() << std::endl;
    return 1;
  }
}
