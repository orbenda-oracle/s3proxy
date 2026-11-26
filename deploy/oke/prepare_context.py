#!/usr/bin/env python3
"""Stage only the public Docker build inputs, checking the accepted JAR digest."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

JAR_SHA256 = 'd6e2affbf8e9c6b27e5334640911c94fe077bfc4e3a7db8767dc2a6daeb36232'


def main():
    repo = Path(__file__).resolve().parents[2]
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar', type=Path, default=repo/'target/s3proxy-4.3.0-SNAPSHOT-jar-with-dependencies.jar')
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if hashlib.sha256(a.jar.read_bytes()).hexdigest() != JAR_SHA256:
        p.error('JAR differs from the live-tested artifact')
    if a.output.exists():
        p.error('Use a new output directory; existing files are not merged')
    a.output.mkdir(parents=True)
    (a.output/'lib').mkdir()
    (a.output/'bin').mkdir()
    shutil.copy2(a.jar, a.output/'lib/s3proxy.jar')
    shutil.copy2(repo/'examples/oci-native/offline/bin/launch.sh', a.output/'bin/launch.sh')
    shutil.copy2(Path(__file__).parent/'container/Dockerfile', a.output/'Dockerfile')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
    dirty = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo, text=True).strip())
    (a.output/'build-inputs.json').write_text(json.dumps({'source_commit': commit, 'source_dirty': dirty,
        'jar_sha256': JAR_SHA256, 'platform': 'linux/amd64', 'site_credentials': False}, indent=2)+'\n')
    print('Staged explicit public inputs; no site configuration copied.')


if __name__ == '__main__':
    main()
