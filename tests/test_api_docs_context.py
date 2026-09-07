"""Offline checks for source-aware feedback reuse and writing requirements."""
from copy import deepcopy
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from api_docs import OPERATIONS, make_payload, sources
from api_docs_context import for_operation, load_memory, load_writing_context, new_memory, remember, with_context


class ContextTest(unittest.TestCase):
    def setUp(self):
        self.entries = sources(ROOT)
        self.context = load_writing_context(ROOT / 'config/api-docs-writing-context.json')
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def test_context_is_present_without_mutating_original_request(self):
        original = make_payload('login', self.entries)
        before = deepcopy(original)
        result = with_context(original, self.context, [])
        self.assertEqual(original, before)
        extra = json.loads(result['input']['openai_input']['messages'][-1]['content'])
        self.assertEqual(extra['writing_context']['language'], 'ja')
        self.assertIn('API', extra['writing_context']['audience'])
        self.assertEqual(extra['feedback_history'], [])

    def test_changed_source_keeps_feedback_as_a_revalidation_task(self):
        memory = new_memory(self.entries)
        remember(memory, 'login', [{'field': '/operation/description', 'reason': '確認する条件'}], 'provided_feedback', 0)
        path = Path(self.tmp.name) / 'memory.json'
        path.write_text(json.dumps(memory))
        changed = deepcopy(self.entries)
        changed[0]['sha256'] = '0' * 64
        restored = load_memory(path, changed, OPERATIONS)
        history = for_operation(restored, 'login')
        self.assertFalse(history[0]['source_matches_current'])
        self.assertEqual(history[0]['state'], 'requires_source_revalidation')
        self.assertNotIn('source_files', history[0])
        self.assertEqual(for_operation(restored, 'logout'), [])

    def test_tampered_or_duplicate_memory_is_rejected(self):
        memory = new_memory(self.entries)
        remember(memory, 'login', [{'field': '/operation/description', 'reason': 'original'}], 'provided_feedback', 0)
        path = Path(self.tmp.name) / 'memory.json'
        bad = deepcopy(memory)
        bad['entries'][0]['reason'] = 'changed without a new identity'
        path.write_text(json.dumps(bad))
        with self.assertRaisesRegex(ValueError, 'identity'):
            load_memory(path, self.entries, OPERATIONS)
        memory['entries'].append(deepcopy(memory['entries'][0]))
        path.write_text(json.dumps(memory))
        with self.assertRaisesRegex(ValueError, 'identity'):
            load_memory(path, self.entries, OPERATIONS)

    def test_repeated_finding_is_counted_and_memory_does_not_grow_without_bound(self):
        memory = new_memory(self.entries)
        issue = [{'field': '/operation/description', 'reason': 'same'}]
        remember(memory, 'login', issue, 'provided_feedback', 0)
        remember(memory, 'login', issue, 'model_review', 1)
        self.assertEqual(len(memory['entries']), 1)
        self.assertEqual(memory['entries'][0]['occurrences'], 2)
        for i in range(15):
            remember(memory, 'login', [{'field': '/operation/description', 'reason': str(i)}], 'model_review', 1)
        with self.assertRaisesRegex(ValueError, '16 findings'):
            remember(memory, 'login', [{'field': '/operation/description', 'reason': 'overflow'}], 'model_review', 2)


if __name__ == '__main__':
    unittest.main()
