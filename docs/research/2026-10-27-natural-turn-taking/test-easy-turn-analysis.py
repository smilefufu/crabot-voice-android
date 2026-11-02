import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('analysis', Path(__file__).with_name('analyze-easy-turn.py'))
analysis = importlib.util.module_from_spec(spec)
spec.loader.exec_module(analysis)


class ParserAudit(unittest.TestCase):
    def test_known_trailing_eos_is_not_a_classifier_failure(self):
        self.assertEqual('incomplete', analysis.tag('然后就是<INCOMPLETE><|endoftext|>'))

    def test_unknown_trailing_content_is_preserved_as_invalid(self):
        self.assertEqual('invalid', analysis.tag('好<complete>还有别的内容'))

    def test_multiple_tags_are_not_cherry_picked(self):
        self.assertEqual('invalid', analysis.tag('好<complete><incomplete><|endoftext|>'))

    def test_embedded_eos_is_not_silently_removed(self):
        self.assertEqual('invalid', analysis.tag('好<|endoftext|><complete>'))

    def test_no_tag_or_repeated_eos_remains_invalid(self):
        self.assertEqual('invalid', analysis.tag(''))
        self.assertEqual('invalid', analysis.tag('好<complete><|endoftext|><|endoftext|>'))

    def test_other_actions_remain_distinct(self):
        self.assertEqual('wait', analysis.tag('等一下<wait>'))
        self.assertEqual('backchannel', analysis.tag('嗯<backchannel>'))


if __name__ == '__main__':
    unittest.main()
