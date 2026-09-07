"""Scripted model responses test orchestration; these are not Qwen runs."""
from copy import deepcopy
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from api_docs import OPERATIONS, SMALL_SCOPE, evidence_catalog, sources
from api_docs_pipeline import apply_document_patch, load_resume, measurement_summary, parse_review, response_measurement, run_pipeline
from api_docs_fixture import documents
from check_api_docs import run_checks


def wrapped(value):
    return {'status': 'COMPLETED', 'output': {'model': 'synthetic-fixture', 'choices': [
        {'finish_reason': 'stop', 'message': {'content': json.dumps(value, ensure_ascii=False)}}]}}


class ScriptedModel:
    def __init__(self, docs, wrong_status=False, reviewer_revision=False):
        self.docs = {d['operationId']: d for d in docs}
        self.wrong_status = wrong_status
        self.reviewer_revision = reviewer_revision
        self.calls = []
        self.repaired = False

    def __call__(self, payload, record, seconds):
        stage, op_id = record['stage'], record['operationId']
        self.calls.append((stage, op_id))
        if stage == 'review':
            if self.reviewer_revision and op_id == 'login' and not self.repaired:
                return wrapped({'operationId': op_id, 'verdict': 'revise', 'issues': [{
                    'field': '/operation/description', 'reason': '合成レビューの修正指摘',
                    'evidence': self.docs[op_id]['evidence']}]})
            return wrapped({'operationId': op_id, 'verdict': 'supported', 'issues': []})
        doc = deepcopy(self.docs[op_id])
        if stage == 'generate' and op_id == 'createRequest' and self.wrong_status:
            doc['operation']['responses']['200'] = doc['operation']['responses'].pop('201')
        if stage == 'repair':
            self.repaired = True
        return wrapped(doc)


class ApiPipelineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.entries = sources(ROOT)

    def setUp(self):
        self.docs = documents(self.entries)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)

    def run_case(self, model, **kwargs):
        return run_pipeline(self.entries, SMALL_SCOPE, Path(self.temp.name) / 'pipeline', model,
                            lambda spec: run_checks(spec, ROOT), execution_mode='synthetic_fixture', **kwargs)

    def test_missing_usage_is_not_reported_as_a_complete_zero_cost_run(self):
        record = response_measurement({'output': [{'model': 'alias', 'usage': {
            'prompt_tokens': 120, 'completion_tokens': True, 'total_tokens': -1}}]})
        self.assertEqual(record['prompt_tokens'], 120)
        self.assertIsNone(record['completion_tokens'])
        self.assertIsNone(record['total_tokens'])
        self.assertFalse(record['weight_identity_verified'])
        summary = measurement_summary([
            dict(stage='generate', **record), {'stage': 'generate', 'failure_type': 'TimeoutError'},
            {'stage': 'repair', 'prompt_tokens': 130, 'completion_tokens': 25}])
        self.assertEqual(summary['by_stage']['generate']['prompt_tokens'],
                         {'reported_sum': 120, 'reported_jobs': 1, 'complete': False})
        self.assertEqual(summary['by_stage']['repair']['completion_tokens']['reported_sum'], 25)
        self.assertIsNone(summary['billed_usd'])

    def test_resume_skips_generation_and_rechecks_against_app(self):
        model = ScriptedModel(self.docs)
        previous = Path(self.temp.name) / 'previous'
        def recorded(payload, record, seconds):
            result = model(payload, record, seconds)
            result['id'] = record['job_id'] = 'recorded-test-' + str(len(model.calls))
            return result
        run_pipeline(self.entries, SMALL_SCOPE, previous, recorded,
                     lambda spec: run_checks(spec, ROOT), execution_mode='live')
        # These local mocks test receipt validation, not real GPU generation.
        initial, receipt = load_resume(previous, self.entries, SMALL_SCOPE)
        resumed = self.run_case(ScriptedModel(self.docs), initial_documents=initial, resume_receipt=receipt)
        self.assertEqual(resumed['status'], 'automated_checks_clear_human_pending')
        self.assertEqual(resumed['jobs_started'], 3)
        self.assertEqual(resumed['measurement']['by_stage']['generate']['jobs_attempted'], 0)
        chained_folder = Path(self.temp.name) / 'chained'
        run_pipeline(self.entries, SMALL_SCOPE, chained_folder, recorded,
                     lambda spec: run_checks(spec, ROOT), execution_mode='live',
                     initial_documents=initial, resume_receipt=receipt)
        again, _ = load_resume(chained_folder, self.entries, SMALL_SCOPE)
        self.assertEqual(again, initial)
        altered = json.loads((previous / 'documents.json').read_text())
        altered[0]['operation']['description'] = 'Altered after generation'
        (previous / 'documents.json').write_text(json.dumps(altered))
        with self.assertRaisesRegex(ValueError, 'differs'):
            load_resume(previous, self.entries, SMALL_SCOPE)

    def test_resume_rejects_synthetic_and_changed_sources(self):
        self.run_case(ScriptedModel(self.docs))
        folder = Path(self.temp.name) / 'pipeline'
        with self.assertRaisesRegex(ValueError, 'live results'):
            load_resume(folder, self.entries, SMALL_SCOPE)
        previous = json.loads((folder / 'summary.json').read_text())
        previous.update(execution_mode='live', sources={})
        (folder / 'summary.json').write_text(json.dumps(previous))
        with self.assertRaisesRegex(ValueError, 'current source'):
            load_resume(folder, self.entries, SMALL_SCOPE)

    def test_source_grounded_feedback_causes_targeted_repair(self):
        model = ScriptedModel(self.docs)
        result = self.run_case(model, initial_documents=[d for d in self.docs if d['operationId'] in SMALL_SCOPE],
            initial_findings={'login': [{'field': '/operation/description', 'reason': 'Synthetic review finding'}]})
        self.assertEqual(result['status'], 'automated_checks_clear_human_pending')
        self.assertFalse(any(stage == 'generate' for stage, op in model.calls))
        self.assertEqual(model.calls[0], ('repair', 'login'))

    def test_patch_repairs_authentication_and_runs_real_http_checks(self):
        initial = deepcopy([d for d in self.docs if d['operationId'] in SMALL_SCOPE])
        create = next(d for d in initial if d['operationId'] == 'createRequest')
        create['operation']['security'] = [{'sessionCookie': []}]
        model = ScriptedModel(self.docs)
        def invoke(payload, record, seconds):
            if record['stage'] == 'repair':
                self.assertEqual(record['response_format'], 'api_patch')
                return wrapped({'operationId': 'createRequest', 'edits': [{
                    'op': 'set', 'path': '/operation/security',
                    'value': [{'sessionCookie': [], 'csrfHeader': []}]}]})
            return model(payload, record, seconds)
        result = self.run_case(invoke, initial_documents=initial, repair_format='patch')
        self.assertEqual(result['status'], 'automated_checks_clear_human_pending')
        self.assertEqual(result['jobs_started'], 4)
        self.assertEqual(create['operation']['security'], [{'sessionCookie': []}])

    def test_patch_pointer_scope_and_removal(self):
        doc = deepcopy(self.docs[0])
        doc['operation']['responses']['200']['content']['application/json']['schema']['nullable'] = True
        patch = {'operationId': doc['operationId'], 'edits': [{
            'op': 'remove', 'path': '/operation/responses/200/content/application~1json/schema/nullable'}]}
        fixed = apply_document_patch(doc, patch)
        self.assertNotIn('nullable', fixed['operation']['responses']['200']['content']['application/json']['schema'])
        for path in ('/evidence', '/operationId', '/operation/description~x'):
            with self.assertRaises(ValueError):
                apply_document_patch(doc, {'operationId': doc['operationId'], 'edits': [
                    {'op': 'set', 'path': path, 'value': 'not allowed'}]})

    def test_actual_http_mismatch_repairs_and_rechecks(self):
        model = ScriptedModel(self.docs, wrong_status=True)
        result = self.run_case(model, max_jobs=8)
        self.assertEqual(result['status'], 'automated_checks_clear_human_pending')
        self.assertEqual(result['repairs_completed'], 1)
        self.assertIn(('repair', 'createRequest'), model.calls)
        self.assertFalse(result['publication_authorized'])
        self.assertEqual(result['source_scale']['files'], len(self.entries))
        measurement = result['measurement']['by_stage']
        self.assertEqual(measurement['generate']['jobs_attempted'], 3)
        self.assertEqual(measurement['repair']['jobs_attempted'], 1)
        self.assertEqual(measurement['review']['jobs_attempted'], 3)
        self.assertFalse(measurement['generate']['prompt_tokens']['complete'])
        self.assertTrue(measurement['generate']['client_elapsed_seconds']['complete'])
        out = Path(self.temp.name) / 'pipeline'
        self.assertEqual(json.loads((out / 'checks-0.json').read_text())['status'], 'failed')
        self.assertEqual(json.loads((out / 'checks-1.json').read_text())['status'], 'passed')
        before = {d['operationId']: d for d in json.loads((out / 'initial.json').read_text())}
        after = {d['operationId']: d for d in json.loads((out / 'documents.json').read_text())}
        self.assertEqual(before['login'], after['login'])

    def test_model_review_causes_repair_and_new_review(self):
        model = ScriptedModel(self.docs, reviewer_revision=True)
        result = self.run_case(model, max_jobs=8)
        self.assertEqual(result['status'], 'automated_checks_clear_human_pending')
        self.assertEqual(model.calls.count(('review', 'login')), 2)
        self.assertEqual(model.calls.count(('review', 'getSession')), 1)

    def test_budget_reserves_review_after_repair(self):
        model = ScriptedModel(self.docs, wrong_status=True)
        result = self.run_case(model, max_jobs=6)
        self.assertEqual(result['status'], 'repair_limit_needs_review')
        self.assertFalse(any(stage == 'repair' for stage, op_id in model.calls))

    def test_unknowns_are_not_cleared_by_a_supported_verdict(self):
        self.docs[0]['unknowns'] = ['合成データの未確認事項']
        result = self.run_case(ScriptedModel(self.docs))
        self.assertEqual(result['status'], 'needs_review')

    def test_truncated_response_is_retained_and_never_approved(self):
        def invoke(payload, record, seconds):
            result = wrapped(self.docs[0])
            result['output']['choices'][0]['finish_reason'] = 'length'
            return result
        result = self.run_case(invoke)
        self.assertEqual(result['status'], 'failed_needs_review')
        self.assertEqual(result['jobs_started'], 1)
        self.assertTrue((Path(self.temp.name) / 'pipeline/01-generate-getSession/response.json').exists())

    def test_failed_or_ambiguous_invocation_is_not_retried(self):
        calls = []
        def invoke(payload, record, seconds):
            calls.append(record['operationId'])
            raise TimeoutError('ambiguous')
        result = self.run_case(invoke)
        self.assertEqual(result['status'], 'failed_needs_review')
        self.assertEqual(len(calls), 1)

    def test_wall_clock_deadline_stops_new_jobs(self):
        times = iter([0, 3, 3])
        model = ScriptedModel(self.docs)
        result = self.run_case(model, total_seconds=1, clock=lambda: next(times))
        self.assertEqual(result['status'], 'budget_exhausted_needs_review')
        self.assertEqual(model.calls, [])

    def test_impossible_job_budget_is_rejected_before_generation(self):
        model = ScriptedModel(self.docs)
        with self.assertRaisesRegex(ValueError, 'Reserve'):
            self.run_case(model, max_jobs=5)
        self.assertEqual(model.calls, [])

    def test_per_job_ttl_is_bounded_by_remaining_deadline(self):
        model = ScriptedModel(self.docs)
        deadlines = []
        def invoke(payload, record, seconds):
            self.assertLessEqual(payload['policy']['ttl'], int(seconds * 1000))
            self.assertLessEqual(payload['policy']['executionTimeout'], payload['policy']['ttl'])
            deadlines.append(seconds)
            return model(payload, record, seconds)
        result = self.run_case(invoke, total_seconds=60)
        self.assertEqual(result['status'], 'automated_checks_clear_human_pending')
        self.assertGreater(deadlines[0], deadlines[-1])

    def test_stale_or_contradictory_model_review_is_rejected(self):
        for value in [
            {'operationId': 'login', 'verdict': 'revise', 'issues': []},
            {'operationId': 'login', 'verdict': 'revise', 'issues': [
                {'field': '/operation/description', 'reason': '合成指摘', 'evidence': ['E-stale']}]}]:
            with self.assertRaises(Exception):
                parse_review(wrapped(value), self.entries, 'login')


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--smoke-out':
        entries = sources(ROOT)
        result = run_pipeline(entries, tuple(OPERATIONS), sys.argv[2], ScriptedModel(documents(entries), wrong_status=True),
                              lambda spec: run_checks(spec, ROOT), max_jobs=24, execution_mode='synthetic_fixture')
        if result['status'] != 'automated_checks_clear_human_pending':
            raise SystemExit(1)
    else:
        unittest.main()
