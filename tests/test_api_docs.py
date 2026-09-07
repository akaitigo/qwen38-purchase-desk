import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from jsonschema import ValidationError
from api_docs import assemble, extract, load_responses, make_payload, OPERATIONS, prepare, render, sources
from api_docs_fixture import documents
from check_api_docs import ContractMismatch, run_checks


class ApiDocsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.entries = sources(ROOT)

    def setUp(self):
        self.docs = documents(self.entries)

    def test_render_and_local_app(self):
        spec = assemble(self.docs, self.entries)
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / 'candidate'
            render(spec, self.docs, self.entries, out, 'synthetic_fixture')
            self.assertIn('Qwenの生成結果ではありません', (out / 'index.md').read_text())
            report = json.loads((out / 'review.json').read_text())
            self.assertFalse(report['publication_authorized'])
            self.assertEqual(json.loads((out / 'examples/createRequest.json').read_text())['quantity'], 2)
            with self.assertRaises(FileExistsError):
                render(spec, self.docs, self.entries, out, 'synthetic_fixture')
        result = run_checks(spec, ROOT)
        self.assertEqual(len(result['checks']), 20)

    def test_example_type_disagreement(self):
        self.docs[2]['operation']['requestBody']['content']['application/json']['example']['quantity'] = 'two'
        with self.assertRaises(ValidationError):
            assemble(self.docs, self.entries)

    def test_missing_operation_and_duplicates(self):
        for docs in (self.docs[:2], [self.docs[0], self.docs[0], self.docs[2]]):
            with self.assertRaises(ValueError):
                assemble(docs, self.entries)

    def test_stale_evidence(self):
        self.docs[0]['evidence'] = ['E-stale']
        with self.assertRaises(ValidationError):
            assemble(self.docs, self.entries)

    def test_external_reference_rejected_before_validation(self):
        for key in ('$ref', '$dynamicRef', '$id', '$schema'):
            with self.subTest(key=key):
                docs = copy.deepcopy(self.docs)
                docs[0]['operation']['responses']['200']['content']['application/json']['schema'][key] = 'https://invalid.example/schema'
                with self.assertRaisesRegex(ValueError, 'inline'):
                    assemble(docs, self.entries)

    def test_fictional_security(self):
        self.docs[1]['operation']['security'] = [{'bearerAuth': []}]
        with self.assertRaises(ValueError):
            assemble(self.docs, self.entries)

    def test_empty_response_schema(self):
        self.docs[0]['operation']['responses']['200']['content']['application/json']['schema'] = {}
        with self.assertRaises(ValueError):
            assemble(self.docs, self.entries)

    def test_wrong_success_status_fails_against_app(self):
        responses = self.docs[2]['operation']['responses']
        responses['200'] = responses.pop('201')
        spec = assemble(self.docs, self.entries)  # Valid OpenAPI, wrong application contract.
        with self.assertRaisesRegex(AssertionError, 'status is missing'):
            run_checks(spec, ROOT)

    def test_wrong_csrf_contract_fails_even_when_openapi_is_valid(self):
        self.docs[2]['operation']['security'] = [{'sessionCookie': []}]
        spec = assemble(self.docs, self.entries)
        with self.assertRaisesRegex(ContractMismatch, 'security_requirements_mismatch'):
            run_checks(spec, ROOT)

    def test_cookie_header_omission_is_detected(self):
        del self.docs[0]['operation']['responses']['200']['headers']
        spec = assemble(self.docs, self.entries)
        with self.assertRaisesRegex(ContractMismatch, 'set_cookie_not_documented'):
            run_checks(spec, ROOT)

    def test_wrong_nullable_response_fails_against_app(self):
        media = self.docs[0]['operation']['responses']['200']['content']['application/json']
        media['schema']['properties']['user']['type'] = 'object'
        media['example']['user'] = {'username': 'EXAMPLE', 'role': 'EMPLOYEE'}
        spec = assemble(self.docs, self.entries)
        with self.assertRaises(ContractMismatch) as raised:
            run_checks(spec, ROOT)
        self.assertEqual(raised.exception.feedback['field'], ['user'])

    def test_undocumented_response_field_is_not_hidden_by_permissive_schema(self):
        media = self.docs[0]['operation']['responses']['200']['content']['application/json']
        del media['schema']['properties']['csrfToken']
        media['schema']['required'].remove('csrfToken')
        media['schema']['additionalProperties'] = True
        spec = assemble(self.docs, self.entries)
        with self.assertRaises(ContractMismatch) as raised:
            run_checks(spec, ROOT)
        self.assertEqual(raised.exception.feedback['reason'], 'response_field_not_documented')

    def test_saved_response_source_binding_and_payload_integrity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            prepare(self.entries, root)
            for document in self.docs:
                result = {'status': 'COMPLETED', 'output': {'model': 'unverified-alias', 'choices': [
                    {'finish_reason': 'stop', 'message': {'content': json.dumps(document)}}]}}
                (root / (document['operationId'] + '.response.json')).write_text(json.dumps(result))
            docs, receipts = load_responses(self.entries, root, root)
            self.assertEqual(docs, self.docs)
            self.assertFalse(receipts[0]['weight_identity_verified'])
            changed = copy.deepcopy(self.entries)
            changed[0]['sha256'] = 'changed'
            with self.assertRaisesRegex(ValueError, 'Source changed'):
                load_responses(changed, root, root)
            (root / 'login.payload.json').write_text('{}')
            with self.assertRaisesRegex(ValueError, 'payload changed'):
                load_responses(self.entries, root, root)

    def test_truncated_or_failed_worker_response(self):
        result = {'status': 'COMPLETED', 'output': {'choices': [
            {'finish_reason': 'length', 'message': {'content': json.dumps(self.docs[0])}}]}}
        with self.assertRaises(ValueError):
            extract(result)
        result['output']['choices'][0]['finish_reason'] = 'stop'
        self.assertEqual(extract(result), self.docs[0])
        result['status'] = 'FAILED'
        with self.assertRaises(ValueError):
            extract(result)

    def test_prepare_does_not_include_evaluator_or_submit_jobs(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = prepare(self.entries, directory)
            self.assertEqual(manifest['jobs_submitted'], 0)
            self.assertEqual(manifest['planned_generation_jobs'], 3)
            for op_id in OPERATIONS:
                payload = make_payload(op_id, self.entries)
                text = json.dumps(payload, ensure_ascii=False)
                self.assertNotIn('deliberately-wrong', text)
                self.assertNotIn('api_docs_fixture', text)
                self.assertTrue(all(p.startswith('src/main/') for p in manifest['source_files']))


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--smoke-out':
        from api_docs import save
        entries = sources(ROOT)
        docs = documents(entries)
        spec = assemble(docs, entries)
        out = Path(sys.argv[2])
        render(spec, docs, entries, out, 'synthetic_fixture')
        save(out / 'local-checks.json', run_checks(spec, ROOT))
    else:
        unittest.main()
