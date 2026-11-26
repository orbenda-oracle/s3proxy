import configparser
import importlib.util
import json
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class OfflineSafetyTest(unittest.TestCase):
    def test_key_lifecycle_preserves_native_binding_and_secret_file_permissions(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            template = d/'template'
            template.write_text('s3proxy.identity=REPLACE_ACCESS\ns3proxy.credential=REPLACE_SECRET\ns3proxy.oci.enabled=true\ns3proxy.oci.user-id=native-alice\n')
            props, credentials = d/'alice.properties', d/'client'
            def run(action, client=None):
                cmd=[sys.executable,str(ROOT/'bin/gateway-key.py'),action,'--properties',str(props)]
                if action=='create':cmd+=['--template',str(template)]
                if client:cmd+=['--credentials',str(client)]
                return subprocess.run(cmd,capture_output=True,text=True)
            first=run('create',credentials)
            self.assertEqual(first.returncode,0,first.stderr)
            original=props.read_text();c=configparser.ConfigParser();c.read(credentials)
            secret=c['pca-s3']['aws_secret_access_key']
            self.assertNotIn(secret,first.stdout+first.stderr)
            self.assertEqual(stat.S_IMODE(credentials.stat().st_mode),0o600)
            props.chmod(0o640)
            self.assertEqual(run('disable').returncode,0)
            self.assertIn('s3proxy.oci.enabled=false',props.read_text())
            self.assertEqual(run('rotate',d/'replacement').returncode,0)
            changed=props.read_text()
            self.assertNotIn(secret,changed)
            self.assertIn('s3proxy.oci.user-id=native-alice',changed)
            self.assertIn('s3proxy.oci.enabled=false',changed)
            self.assertEqual(stat.S_IMODE(props.stat().st_mode),0o640)
            self.assertNotEqual(run('create',credentials).returncode,0)
            self.assertEqual(props.read_text(),changed)

    def test_validation_cannot_accept_a_transport_failure_as_denial(self):
        path=ROOT/'bin/validate.py'
        self.assertTrue(path.exists(),'validation runner is not implemented')
        spec=importlib.util.spec_from_file_location('onsite',path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
        with tempfile.TemporaryDirectory() as d:
            r=m.Recorder(Path(d)/'report.json','onsite-test','baseline')
            def broken():raise ConnectionError('secret-marker')
            with self.assertRaises(AssertionError):r.check('denied','deny',broken)
            report=json.loads((Path(d)/'report.json').read_text())
            self.assertEqual(report['verdict'],'FAIL')
            self.assertNotIn('secret-marker',json.dumps(report))

    def test_validation_rejects_unexpected_grants(self):
        path=ROOT/'bin/validate.py'
        self.assertTrue(path.exists(),'validation runner is not implemented')
        spec=importlib.util.spec_from_file_location('onsite',path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
        with tempfile.TemporaryDirectory() as d:
            r=m.Recorder(Path(d)/'report.json','onsite-test','baseline')
            with self.assertRaises(AssertionError):r.check('denied','deny',lambda:{'ResponseMetadata':{'HTTPStatusCode':200}})
            self.assertEqual(json.loads((Path(d)/'report.json').read_text())['verdict'],'FAIL')

class AcceptanceEffectsTest(unittest.TestCase):
    def setUp(self):
        spec=importlib.util.spec_from_file_location('onsite',ROOT/'bin/validate.py');self.m=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.m)

    def test_empty_object_listing_cannot_pass(self):
        self.assertTrue(hasattr(self.m,'assert_listing'),'listing effect validation is missing')
        with self.assertRaises(AssertionError):self.m.assert_listing({'Contents':[]},'known-seed')
        self.m.assert_listing({'Contents':[{'Key':'known-seed'}]},'known-seed')

    def test_multipart_listing_must_match_uploaded_parts(self):
        self.assertTrue(hasattr(self.m,'assert_parts'),'part validation is missing')
        with self.assertRaises(AssertionError):self.m.assert_parts({'Parts':[]},[{'PartNumber':1,'ETag':'"part"'}],[3])
        self.m.assert_parts({'Parts':[{'PartNumber':1,'ETag':'"part"','Size':3}]},[{'PartNumber':1,'ETag':'"part"'}],[3])

    def test_delete_control_rejects_concealed_authorization(self):
        from botocore.exceptions import ClientError
        import inspect
        self.assertIn('code',inspect.signature(self.m.Recorder.check).parameters)
        with tempfile.TemporaryDirectory() as d:
            r=self.m.Recorder(Path(d)/'r.json','onsite-test','baseline')
            def denial():raise ClientError({'Error':{'Code':'AccessDenied'},'ResponseMetadata':{'HTTPStatusCode':404}},'GetObject')
            with self.assertRaises(AssertionError):r.check('deleted','deny',denial,status=404,code='NoSuchKey')

if __name__=='__main__':unittest.main()
