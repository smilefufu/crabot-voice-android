"""Probe contract checks; requires an already-built host probe in APM_PROBE."""
import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import numpy as np

spec = importlib.util.spec_from_file_location("probe_apm", Path(__file__).with_name("probe-apm.py"))
metrics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metrics)


class ProbeTest(unittest.TestCase):
    def invoke(self, render, capture, enabled="0", delay="80"):
        with tempfile.TemporaryDirectory(prefix="apm-probe-check-") as folder:
            root = Path(folder)
            np.asarray(render, dtype="<f4").tofile(root / "render")
            np.asarray(capture, dtype="<f4").tofile(root / "capture")
            completed = subprocess.run([os.environ["APM_PROBE"], str(root / "render"),
                str(root / "capture"), str(root / "out"), enabled, delay],
                capture_output=True, text=True, timeout=10)
            output = np.fromfile(root / "out", dtype="<f4") if (root / "out").exists() else None
            return completed, output

    def test_bypass_preserves_capture_samples_in_mixed_rate_api(self):
        audio = np.random.default_rng(23).uniform(-0.1, 0.1, 1600).astype("<f4")
        completed, output = self.invoke(np.zeros(2400), audio)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        np.testing.assert_array_equal(output, audio)
        self.assertEqual(json.loads(completed.stdout)["frames"], 10)

    def test_different_stream_durations_are_rejected(self):
        completed, output = self.invoke(np.zeros(240), np.zeros(320))
        self.assertNotEqual(completed.returncode, 0)
        self.assertIsNone(output)

    def test_incomplete_frame_is_rejected(self):
        completed, output = self.invoke(np.zeros(240), np.zeros(159))
        self.assertNotEqual(completed.returncode, 0)
        self.assertIsNone(output)

    def test_nonfinite_samples_are_rejected(self):
        audio = np.zeros(160); audio[8] = np.nan
        completed, output = self.invoke(np.zeros(240), audio)
        self.assertNotEqual(completed.returncode, 0)
        self.assertIsNone(output)

    def test_out_of_range_samples_are_rejected(self):
        audio = np.zeros(160); audio[8] = 1.1
        completed, output = self.invoke(np.zeros(240), audio)
        self.assertNotEqual(completed.returncode, 0)
        self.assertIsNone(output)

    def test_invalid_delay_is_rejected(self):
        completed, output = self.invoke(np.zeros(240), np.zeros(160), delay="-1")
        self.assertNotEqual(completed.returncode, 0)
        self.assertIsNone(output)

    def test_invalid_aec_switch_is_rejected(self):
        completed, output = self.invoke(np.zeros(240), np.zeros(160), enabled="2")
        self.assertNotEqual(completed.returncode, 0)
        self.assertIsNone(output)

    def test_missing_files_are_rejected(self):
        completed = subprocess.run([os.environ["APM_PROBE"], "/nonexistent/render", "/nonexistent/capture",
            "/nonexistent/out", "0", "80"], capture_output=True, text=True, timeout=10)
        self.assertNotEqual(completed.returncode, 0)

    def test_alignment_reports_delay_instead_of_hiding_it(self):
        audio = np.random.default_rng(23).uniform(-0.1, 0.1, 16000)
        shifted = np.r_[np.zeros(64), audio[:-64]]
        result = metrics.near_metrics(audio, shifted)
        self.assertEqual(result["alignment_samples_at_16k"], 64)
        self.assertEqual(result["alignment_ms"], 4)
        self.assertGreater(result["aligned_cosine"], 0.999)
        self.assertLess(result["unaligned_cosine"], 0.1)

    def test_linear_export_is_configured_at_both_apm_and_aec3(self):
        with tempfile.TemporaryDirectory(prefix="apm-linear-check-") as folder:
            root = Path(folder)
            np.zeros(2400, dtype="<f4").tofile(root / "render")
            np.zeros(1600, dtype="<f4").tofile(root / "capture")
            completed = subprocess.run([os.environ["APM_PROBE"], str(root / "render"),
                str(root / "capture"), str(root / "out"), "1", "80", str(root / "linear")],
                capture_output=True, text=True, timeout=10)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertEqual(json.loads(completed.stdout)["frames"], 10)
            self.assertEqual((root / "linear").stat().st_size, 1600 * 4)
            self.assertTrue(np.all(np.isfinite(np.fromfile(root / "linear", dtype="<f4"))))


if __name__ == "__main__":
    unittest.main()
