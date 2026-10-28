"""Verify causal sampling and no invented end-of-turn, without loading a model."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('probe_turnsense', Path(__file__).with_name('probe-turnsense.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class CausalReplayTests(unittest.TestCase):
    def test_mid_pause_remains_hold_and_grid_never_reads_future_audio(self):
        points = probe.pause_points([{'start': 1, 'end': 1.65}, {'start': 3, 'end': 3.42}], 3.42)
        self.assertEqual([x['at'] for x in points], [1.2, 1.4, 1.6, 3.2, 3.4])
        self.assertEqual([x['eot'] for x in points], [False, False, False, True, True])
        self.assertTrue(all(x['at'] <= 3.42 for x in points))

    def test_no_grid_point_for_short_pause_does_not_extend_it(self):
        self.assertEqual(probe.pause_points([{'start': 1, 'end': 1.19}], 1.19), [])

    def test_invalid_original_labels_fail_before_prediction(self):
        with self.assertRaises(ValueError):
            probe.pause_points([{'start': 1, 'end': 2}], 1.5)

    def test_missing_and_invalid_predictions_never_force_a_commit(self):
        points = probe.pause_points([{'start': 1, 'end': 2}], 2)
        predictions = [{'complete': False, 'label': 'invalid', 'pipeline_ms': 20} for _ in points]
        self.assertIsNone(probe.first_decision(points, predictions, 0))

    def test_report_includes_observation_wait_and_processing(self):
        points = probe.pause_points([{'start': 1, 'end': 2}], 2)
        predictions = [{'complete': False, 'pipeline_ms': 10}, {'complete': True, 'pipeline_ms': 37}]
        self.assertEqual(probe.first_decision(points, predictions, 0), 437)


if __name__ == '__main__':
    unittest.main()
