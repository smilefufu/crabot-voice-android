"""Regression checks for audit interpretation, not model accuracy assertions."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('audit', Path(__file__).with_name('audit-turn-pipeline.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class AuditInterpretationTests(unittest.TestCase):
    def test_repeated_silence_checks_are_not_independent_turns(self):
        points = [{'id': 'a', 'span': 0, 'eot': False, 'pause_s': at,
                   'model': {'complete': at != .2}} for at in [.2, .4, .6]]
        self.assertEqual(audit.first_check_comparison(points, 'model'), {
            'scorable_hold_spans': 1, 'first_check_positive': 0, 'any_check_positive': 1})

    def test_endpoint_and_other_turns_do_not_pollute_hold_count(self):
        points = [{'id': name, 'span': 0, 'eot': end, 'pause_s': .2,
                   'model': {'complete': True}} for name, end in [('a', False), ('b', False), ('a', True)]]
        self.assertEqual(audit.first_check_comparison(points, 'model')['any_check_positive'], 2)

    def test_absent_future_audio_is_not_a_success_or_premature_action(self):
        self.assertEqual(audit.classify(None, [{'start': 4, 'end': 5}]), 'no_complete_before_eof')

    def test_later_completion_cannot_erase_first_premature_commit(self):
        commits = [2.5, 4.5]
        spans = [{'start': 2, 'end': 3}, {'start': 4, 'end': 5}]
        self.assertEqual(audit.classify(commits[0], spans), 'premature')
        self.assertEqual(audit.classify(commits[1], spans), 'endpoint')

    def test_annotation_gap_does_not_invent_a_premature_label(self):
        spans = [{'start': 2, 'end': 3}, {'start': 4, 'end': 5}]
        self.assertEqual(audit.classify(3.95, spans), 'outside_annotated_silence')


if __name__ == '__main__':
    unittest.main()
