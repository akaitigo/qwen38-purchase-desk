import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('serverless_docs', Path(__file__).parents[1]/'scripts/serverless_docs.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class Jobs(unittest.TestCase):
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

    def test_evidence_must_exist_in_selected_source(self):
        entries=[{'path':'src/main/A.kt','text':'fun approve() {}'}]
        doc={'markdown':'## 概要\n説明\n## APIと権限制御\n説明\n## 未確認\n未確認','sources':[{'path':'src/main/A.kt','symbol':'invented'}]}
        result={'output':[{'choices':[{'finish_reason':'stop','message':{'content':json.dumps(doc)}}]}]}
        with self.assertRaises(ValueError):module.extract_document(result,entries)
        doc['sources'][0]['symbol']='approve'
        result['output'][0]['choices'][0]['message']['content']=json.dumps(doc)
        self.assertEqual(module.extract_document(result,entries)[0],doc)

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
