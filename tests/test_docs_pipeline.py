import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parents[1] / 'scripts'))
import docs_pipeline as p


def fixture():
    entries = [{'path': 'src/main/Synthetic.kt', 'text': '// Synthetic offline fixture only\nfun sample() = 1\n'}]
    evidence = [next(iter(p.evidence_catalog(entries)))]
    topics = ['HTMLログアウト', '編集']
    claims = [{'topic': t, 'statement': '合成データ: ' + t, 'conditions': '合成条件',
        'exceptions': '合成例外', 'evidence': evidence} for t in topics]
    document = {'claims': claims, 'unknowns': []}
    def review(verdict='supported', index=0):
        return p.wrapped({'reviews': [{'topic': t, 'verdict': verdict if i == index else 'supported',
            'reason': '合成テスト用の指摘。実際のソースの正確性を評価したものではありません。',
            'evidence': evidence} for i, t in enumerate(topics)],
            'unknowns_verdict': 'supported', 'unknowns_reason': '合成テスト'})
    repair = {'claims': [dict(claims[0], statement='合成データ: 修正後')], 'unknowns': []}
    return entries, topics, document, review, repair


class ScriptedCalls:
    """Offline test double. Never connects to Runpod or instantiates API."""
    def __init__(self, responses):
        self.responses = list(responses)
        self.payloads = []

    def __call__(self, payload, record, remaining):
        self.payloads.append(payload)
        if not self.responses:
            raise AssertionError('Unexpected extra model call')
        response = self.responses.pop(0)
        record.update(status='COMPLETED', execution_mode='offline_fixture')
        if isinstance(response, BaseException):
            raise response
        return response


class PipelineTests(unittest.TestCase):
    def run_case(self, responses, **kwargs):
        entries, topics, _, _, _ = fixture()
        fake = ScriptedCalls(responses)
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        out = Path(folder.name) / 'artifact'
        summary = p.run_pipeline(entries, topics, out, fake, execution_mode='offline_fixture', **kwargs)
        return summary, out, fake

    def test_repairs_only_flagged_claim_and_rechecks_entire_document(self):
        _, _, doc, review, repair = fixture()
        summary, out, fake = self.run_case([p.wrapped(doc), review('revise'), p.wrapped(repair), review()])
        self.assertEqual(summary['status'], 'automated_review_clear_human_pending')
        self.assertEqual(summary['jobs_started'], 4)
        self.assertTrue(summary['human_review_required'])
        self.assertFalse(summary['publication_authorized'])
        final = json.loads((out / 'document.json').read_text())
        self.assertEqual(final['claims'][0], repair['claims'][0])
        self.assertEqual(final['claims'][1], doc['claims'][1])
        check = json.loads(fake.payloads[-1]['input']['openai_input']['messages'][1]['content'])
        self.assertEqual(check['draft'], final)
        self.assertTrue((out / 'initial.json').exists())
        self.assertTrue((out / '04-review/response.json').exists())

    def test_persistent_error_stops_at_repair_limit(self):
        _, _, doc, review, repair = fixture()
        summary, out, fake = self.run_case([p.wrapped(doc), review('revise'), p.wrapped(repair), review('revise')])
        self.assertEqual(summary['status'], 'needs_review')
        self.assertEqual(len(fake.payloads), 4)
        self.assertTrue((out / 'DOCUMENT.md').exists())

    def test_new_error_in_previously_supported_topic_is_not_ignored(self):
        _, _, doc, review, repair = fixture()
        summary, _, _ = self.run_case([p.wrapped(doc), review('revise'), p.wrapped(repair), review('revise', 1)])
        self.assertEqual(summary['status'], 'needs_review')

    def test_reserve_review_slot_before_repair(self):
        _, _, doc, review, _ = fixture()
        summary, _, fake = self.run_case([p.wrapped(doc), review('revise')], max_jobs=3)
        self.assertEqual(summary['status'], 'needs_review')
        self.assertEqual(len(fake.payloads), 2)

    def test_uncertain_does_not_become_approved_or_trigger_blind_retry(self):
        _, _, doc, review, _ = fixture()
        summary, _, fake = self.run_case([p.wrapped(doc), review('uncertain')])
        self.assertEqual(summary['status'], 'needs_review')
        self.assertEqual(len(fake.payloads), 2)

    def test_unknowns_are_not_erased_to_pass(self):
        _, _, doc, review, _ = fixture()
        doc['unknowns'] = ['合成未確認事項']
        summary, out, _ = self.run_case([p.wrapped(doc), review()])
        self.assertEqual(summary['status'], 'needs_review')
        self.assertEqual(json.loads((out / 'document.json').read_text())['unknowns'], doc['unknowns'])

    def test_unsupported_claim_is_not_cleared_by_overoptimistic_reviewer(self):
        _, _, doc, review, _ = fixture()
        doc['claims'][0].update(statement='未確認: 合成', evidence=[])
        summary, _, _ = self.run_case([p.wrapped(doc), review()])
        self.assertEqual(summary['status'], 'needs_review')

    def test_ambiguous_submission_stops_without_retry_and_saves_failure(self):
        summary, out, fake = self.run_case([TimeoutError('secret must not appear')])
        self.assertEqual(len(fake.payloads), 1)
        self.assertEqual(summary['status'], 'failed_needs_review')
        self.assertEqual(summary['failure_type'], 'TimeoutError')
        self.assertNotIn('secret', (out / 'summary.json').read_text())
        self.assertTrue((out / '01-generate/metadata.json').exists())

    def test_invalid_repair_cannot_replace_valid_draft(self):
        _, _, doc, review, repair = fixture()
        repair['claims'][0]['evidence'] = ['fake-id']
        summary, out, _ = self.run_case([p.wrapped(doc), review('revise'), p.wrapped(repair)])
        self.assertEqual(summary['status'], 'failed_needs_review')
        self.assertEqual(json.loads((out / 'document.json').read_text()), doc)

    def test_review_must_cover_topics_and_have_valid_evidence(self):
        entries, topics, _, review, _ = fixture()
        for defect in ['missing', 'duplicate', 'evidence', 'truncated', 'empty', 'long_reason', 'long_unknowns']:
            response = review()
            choice = response['output']['choices'][0]
            value = json.loads(choice['message']['content'])
            if defect == 'missing': value['reviews'].pop()
            elif defect == 'duplicate': value['reviews'][1] = value['reviews'][0]
            elif defect == 'evidence': value['reviews'][0]['evidence'] = ['invented']
            elif defect == 'truncated': choice['finish_reason'] = 'length'
            elif defect == 'long_reason': value['reviews'][0]['reason'] = 'あ' * 181
            elif defect == 'long_unknowns': value['unknowns_reason'] = 'あ' * 181
            else: value['reviews'][0]['evidence'] = []
            choice['message']['content'] = json.dumps(value)
            with self.subTest(defect=defect), self.assertRaises(ValueError):
                p.extract_review(response, entries, topics)

    def test_wall_clock_prevents_next_call(self):
        _, _, doc, _, _ = fixture()
        now = [0]
        fake = ScriptedCalls([p.wrapped(doc)])
        def invoke(*args):
            result = fake(*args)
            now[0] = 20
            return result
        entries, topics, _, _, _ = fixture()
        with tempfile.TemporaryDirectory() as folder:
            summary = p.run_pipeline(entries, topics, Path(folder)/'out', invoke,
                total_seconds=10, clock=lambda: now[0], execution_mode='offline_fixture')
        self.assertEqual(summary['status'], 'budget_exhausted_needs_review')
        self.assertEqual(len(fake.payloads), 1)


def smoke(out):
    entries, topics, doc, review, repair = fixture()
    out.mkdir(parents=True, exist_ok=False)
    cases = {
        'repair_then_clear': ([p.wrapped(doc), review('revise'), p.wrapped(repair), review()], 'automated_review_clear_human_pending'),
        'still_needs_review': ([p.wrapped(doc), review('revise'), p.wrapped(repair), review('revise')], 'needs_review'),
    }
    results = {}
    for name, (responses, expected) in cases.items():
        result = p.run_pipeline(entries, topics, out/name, ScriptedCalls(responses), execution_mode='offline_fixture')
        if result['status'] != expected:
            raise AssertionError(name + ' unexpected outcome')
        results[name] = result['status']
    p.save_json(out/'offline-results.json', {'execution_mode':'offline_fixture_no_gpu', 'cases':results})
    (out/'README.md').write_text('合成応答によるCI配線の検証です。GPUを使用せず、Qwenの生成品質を測定したものではありません。\n')


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--smoke-out':
        smoke(Path(sys.argv[2]))
    else:
        unittest.main()
