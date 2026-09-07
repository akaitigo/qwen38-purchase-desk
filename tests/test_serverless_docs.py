import importlib.util
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('serverless_docs', Path(__file__).parents[1]/'scripts/serverless_docs.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class Jobs(unittest.TestCase):
    def test_thinking_is_explicit_and_keeps_output_budget(self):
        entries, _ = self.fixture()
        default = module.make_payload(entries)['input']['openai_input']
        thinking = module.make_payload(entries, thinking=True)['input']['openai_input']
        self.assertFalse(default['chat_template_kwargs']['enable_thinking'])
        self.assertTrue(thinking['chat_template_kwargs']['enable_thinking'])
        self.assertEqual(thinking['reasoning_effort'], 'low')
        self.assertNotIn('reasoning_effort', default)
        self.assertEqual(thinking['max_tokens'], default['max_tokens'])

    def test_repair_is_bound_to_sources_and_selected_claims(self):
        entries, doc = self.fixture()
        topic = module.TOPICS[0]
        review = {'sources':[{'path':e['path'], 'sha256':hashlib.sha256(e['text'].encode()).hexdigest()} for e in entries],
                  'claims':doc['claims'][:1], 'findings':{topic:'条件を再確認'}}
        p = module.make_payload(entries, [topic], review)
        self.assertIn('条件を再確認', p['input']['openai_input']['messages'][-1]['content'])
        changed = [dict(entries[0], text=entries[0]['text']+' changed')]
        with self.assertRaisesRegex(ValueError, 'snapshot'):
            module.make_payload(changed, [topic], review)
        review['findings'] = {module.TOPICS[1]:'別項目'}
        with self.assertRaisesRegex(ValueError, 'finding'):
            module.make_payload(entries, [topic], review)

    def test_cold_queue_then_completion_submits_once(self):
        calls, statuses, now = [], ['IN_QUEUE', 'IN_PROGRESS', 'COMPLETED'], [0]
        def api(method, route, body=None):
            calls.append(route)
            return {'id':'job-1'} if route == '/run' else {'status':statuses.pop(0), 'output':[]}
        record = {}
        module.run_job(api, {}, record, clock=lambda:now[0], sleep=lambda n:now.__setitem__(0,now[0]+n))
        self.assertEqual(calls.count('/run'),1)
        self.assertEqual(record['elapsed_seconds'],10)

    def test_timeout_cancels_only_own_job(self):
        calls, now = [], [0]
        def api(method,route,body=None):
            calls.append(route)
            return {'id':'owned-1'} if route=='/run' else {'status':'IN_QUEUE'}
        with self.assertRaises(TimeoutError):
            module.run_job(api,{}, {},clock=lambda:now[0],sleep=lambda n:now.__setitem__(0,now[0]+n),timeout=5)
        self.assertEqual(calls[-1],'/cancel/owned-1')

    def test_ambiguous_submission_never_retried(self):
        calls=[]
        def api(*args):
            calls.append(args)
            raise TimeoutError()
        record={}
        with self.assertRaises(TimeoutError):module.run_job(api,{},record)
        self.assertEqual(len(calls),1)
        self.assertIn('do_not_auto_retry',record['submission_outcome'])

    def test_failed_job_does_not_become_success(self):
        def api(method,route,body=None):
            return {'id':'one'} if route=='/run' else {'status':'FAILED'}
        with self.assertRaises(RuntimeError): module.run_job(api,{}, {})

    def test_completed_envelope_with_worker_error_rejected(self):
        with self.assertRaises(ValueError):module.extract_document({'output':[{'error':{'message':'bad'}}]},[])

    def test_truncated_document_rejected(self):
        with self.assertRaises(ValueError):module.extract_document({'output':[{'choices':[{'finish_reason':'length'}]}]},[])

    def fixture(self):
        entries = [{'path':'src/main/A.kt','text':'fun approve() { return approved }'}]
        claims = [{'topic':t,'statement':'未確認: 入力不足','conditions':'未確認: 入力不足','exceptions':'未確認: 入力不足','evidence':[]} for t in module.TOPICS]
        claims[0] = {'topic':module.TOPICS[0], 'statement':'合成テスト用の説明',
                     'conditions':'合成入力', 'exceptions':'未確認: 合成入力',
                     'evidence':[next(iter(module.evidence_catalog(entries)))]}
        return entries, {'claims':claims,'unknowns':['合成入力']}

    def wrapped(self, doc):
        return {'output':[{'choices':[{'finish_reason':'stop','message':{'content':json.dumps(doc)}}]}]}

    def test_claims_render_with_review_notice_and_quotes(self):
        entries, doc = self.fixture()
        result, _ = module.extract_document(self.wrapped(doc), entries)
        self.assertIn('fun approve()', result['markdown'])
        self.assertIn('内容の確認は未実施', result['markdown'])

    def test_invalid_evidence_rejected(self):
        for evidence in [['invented'], [{'quote':'invented'}]]:
            entries, doc = self.fixture()
            doc['claims'][0]['evidence'] = evidence
            with self.assertRaises(ValueError): module.extract_document(self.wrapped(doc), entries)

    def test_model_cannot_inject_its_own_quote(self):
        entries, doc = self.fixture()
        doc['claims'][0]['quote'] = 'made up source'
        with self.assertRaises(ValueError): module.extract_document(self.wrapped(doc), entries)

    def test_source_changes_invalidate_ids(self):
        entries, doc = self.fixture()
        entries[0]['text'] += '\n// changed'
        with self.assertRaisesRegex(ValueError, 'stale'):
            module.extract_document(self.wrapped(doc), entries)

    def test_conditions_and_exceptions_are_required(self):
        for field in ['conditions', 'exceptions']:
            entries, doc = self.fixture()
            del doc['claims'][0][field]
            with self.assertRaises(ValueError): module.extract_document(self.wrapped(doc), entries)

    def test_duplicate_or_excessive_evidence_rejected(self):
        for count in [2, 5]:
            entries, doc = self.fixture()
            doc['claims'][0]['evidence'] *= count
            with self.assertRaises(ValueError): module.extract_document(self.wrapped(doc), entries)

    def test_catalog_preserves_whitespace_quotes_and_line_ranges(self):
        text = 'fun x() {\r\n  print("日本語")\r\n}\r\n'
        first = next(iter(module.evidence_catalog([{'path':'src/main/A.kt','text':text}]).values()))
        self.assertEqual(first['quote'], text)
        self.assertEqual((first['start_line'],first['end_line']), (1,3))
        other = next(iter(module.evidence_catalog([{'path':'src/main/B.kt','text':text}])))
        self.assertNotEqual(first['id'], other)

    def test_missing_duplicate_and_unsupported_topics_rejected(self):
        for defect in ['missing', 'duplicate', 'unsupported']:
            with self.subTest(defect=defect):
                entries, doc = self.fixture()
                if defect == 'missing': doc['claims'].pop()
                elif defect == 'duplicate': doc['claims'].append(doc['claims'][0])
                else: doc['claims'][1]['statement'] = '根拠のない説明'
                with self.assertRaises(ValueError): module.extract_document(self.wrapped(doc), entries)

    def test_legacy_response_not_silently_accepted(self):
        with self.assertRaises(ValueError): module.extract_document(self.wrapped({'markdown':'旧形式','sources':[]}), [])

    def test_quote_existence_does_not_claim_semantic_approval(self):
        entries, doc = self.fixture()
        doc['claims'][0]['statement'] = 'この引用からは裏付けられない説明'
        result, _ = module.extract_document(self.wrapped(doc), entries)
        self.assertIn('内容の確認は未実施', result['markdown'])

    def test_offline_replay_never_constructs_api(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); entries, doc = self.fixture()
            response = root/'response.json'; response.write_text(json.dumps(self.wrapped(doc)))
            names = root/'files.txt'; names.write_text('src/main/A.kt')
            out = root/'out'
            args = ['docs','--repo',folder,'--files',str(names),'--out',str(out),'--response',str(response)]
            with patch('sys.argv',args), patch.object(module,'source_bundle',return_value=[dict(entries[0],sha256='test')]), patch.object(module.subprocess,'check_output',return_value=b'test-head'), patch.object(module,'API') as api:
                module.main(); api.assert_not_called()
            record=json.loads((out/'metadata.json').read_text())
            self.assertEqual(record['execution_mode'],'offline_replay')
            self.assertEqual(record['semantic_review'],'not_performed')
            self.assertTrue((out/'REVIEW.md').exists())

    def test_selected_topics_restrict_schema_and_validation(self):
        entries, doc = self.fixture()
        topic = module.TOPICS[0]
        payload = module.make_payload(entries, [topic])['input']['openai_input']
        self.assertEqual(payload['response_format']['type'], 'json_schema')
        schema = payload['response_format']['json_schema']['schema']
        self.assertEqual(schema['properties']['claims']['maxItems'], 1)
        props = schema['properties']['claims']['items']['properties']
        self.assertEqual(props['topic']['enum'], [topic])
        self.assertEqual(props['evidence']['items']['enum'], list(module.evidence_catalog(entries)))
        doc['claims'] = doc['claims'][:1]
        module.extract_document(self.wrapped(doc), entries, [topic])
        with self.assertRaises(ValueError): module.extract_document(self.wrapped(doc), entries)

    def test_topic_mismatch_and_duplicate_selection_rejected(self):
        entries, doc = self.fixture()
        for selection in [[], ['invented'], [module.TOPICS[0]] * 2]:
            with self.subTest(selection=selection), self.assertRaises(ValueError):
                module.make_payload(entries, selection)
        doc['claims'] = doc['claims'][:1]
        with self.assertRaises(ValueError):
            module.extract_document(self.wrapped(doc), entries, [module.TOPICS[1]])

    def test_source_selection_rejects_untracked_and_symlinks(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'src/main').mkdir(parents=True)
            source=root/'src/main/A.kt';source.write_text('fun x() {}')
            subprocess.run(['git','init','-q',folder],check=True)
            with self.assertRaises(ValueError):module.source_bundle(root,['src/main/A.kt'])
            subprocess.run(['git','-C',folder,'add','.'],check=True)
            self.assertEqual(len(module.source_bundle(root,['src/main/A.kt'])),1)
            source.unlink();source.symlink_to('/etc/hosts')
            with self.assertRaises(ValueError):module.source_bundle(root,['src/main/A.kt'])

    def test_source_limit_is_not_silent_truncation(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'src/main').mkdir(parents=True)
            (root/'src/main/A.kt').write_text('fun x() {}')
            subprocess.run(['git','init','-q',folder],check=True)
            subprocess.run(['git','-C',folder,'add','.'],check=True)
            with self.assertRaises(ValueError):module.source_bundle(root,['src/main/A.kt'],limit=1)


if __name__=='__main__':unittest.main()
