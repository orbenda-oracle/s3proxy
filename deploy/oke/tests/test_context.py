from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
REPO=ROOT.parents[1]

class BuildContextTest(unittest.TestCase):
    def test_context_rejects_different_jar_before_writing_output(self):
        with tempfile.TemporaryDirectory() as d:
            d=Path(d);(d/'wrong.jar').write_bytes(b'wrong artifact')
            result=subprocess.run([sys.executable,str(ROOT/'prepare_context.py'),'--jar',str(d/'wrong.jar'),'--output',str(d/'context')],capture_output=True,text=True)
            self.assertIn('JAR differs from the live-tested artifact',result.stderr)
            self.assertFalse((d/'context').exists())

    def test_context_contains_only_explicit_public_build_inputs(self):
        with tempfile.TemporaryDirectory() as d:
            out=Path(d)/'context'
            result=subprocess.run([sys.executable,str(ROOT/'prepare_context.py'),'--output',str(out)],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertEqual({p.relative_to(out).as_posix() for p in out.rglob('*') if p.is_file()},{'Dockerfile','lib/s3proxy.jar','bin/launch.sh','build-inputs.json'})
            report=json.loads((out/'build-inputs.json').read_text())
            self.assertEqual(report['jar_sha256'],'d6e2affbf8e9c6b27e5334640911c94fe077bfc4e3a7db8767dc2a6daeb36232')

    def test_offline_context_rejects_an_unverified_runtime(self):
        with tempfile.TemporaryDirectory() as d:
            d=Path(d);(d/'wrong.tar.gz').write_bytes(b'not the verified runtime')
            result=subprocess.run([sys.executable,str(ROOT/'prepare_context.py'),'--offline-jre',str(d/'wrong.tar.gz'),'--output',str(d/'context')],capture_output=True,text=True)
            self.assertIn('JRE differs from the verified runtime',result.stderr)
            self.assertFalse((d/'context').exists())

if __name__=='__main__':unittest.main()
