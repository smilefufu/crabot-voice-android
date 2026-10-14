#include "sherpa-onnx/c-api/cxx-api.h"
#include <algorithm>
#include <fstream>
#include <iostream>
#include <map>
#include <vector>
namespace api = sherpa_onnx::cxx;
int main(int argc, char **argv) {
  if (argc != 2) return 2;
  std::string root = argv[1];
  api::SpeakerEmbeddingExtractorConfig config;
  config.model = root + "/campplus.onnx"; config.num_threads = 2;
  auto extractor = api::SpeakerEmbeddingExtractor::Create(config);
  if (!extractor.Get()) return 3;
  auto embed = [&](const std::string &name) {
    auto wave = api::ReadWave(root + "/" + name);
    auto stream = extractor.CreateStream();
    stream.AcceptWaveform(wave.sample_rate, wave.samples.data(), wave.samples.size()); stream.InputFinished();
    return extractor.IsReady(&stream) ? extractor.ComputeEmbedding(&stream) : std::vector<float>{};
  };
  std::map<std::string, std::vector<std::vector<float>>> enrolled;
  std::string name, file;
  std::ifstream enrollment(root + "/enroll.txt");
  while (enrollment >> name >> file) { auto v = embed(file); if (v.empty()) return 4; enrolled[name].push_back(v); }
  auto manager = api::SpeakerEmbeddingManager::Create(extractor.Dim());
  for (auto &[person, vectors] : enrolled) {
    std::vector<float> mean(extractor.Dim());
    for (auto &v : vectors) for (size_t i = 0; i < v.size(); ++i) mean[i] += v[i] / vectors.size();
    if (!manager.Add(person, mean.data())) return 5;
    std::fill(mean.begin(), mean.end(), 0);
    for (auto &v : vectors) std::fill(v.begin(), v.end(), 0);
  }
  std::ifstream tests(root + "/tests.txt");
  while (tests >> name >> file) {
    auto v = embed(file);
    auto hits = v.empty() ? std::vector<api::SpeakerMatch>{} : manager.GetBestMatches(v.data(), -1., 2);
    std::cout << name << '\t' << file << '\t' << v.size();
    for (auto &hit : hits) std::cout << '\t' << hit.name << '\t' << hit.score;
    std::cout << '\n'; std::fill(v.begin(), v.end(), 0);
  }
}
