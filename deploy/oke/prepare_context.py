#!/usr/bin/env python3
"""Stage only the public Docker build inputs, checking the accepted JAR digest."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

JRE_SHA256 = '5a092a0bc21de4c3d855480edd7945be9cfe157b38cdb84dac280511893bb009'

JAR_SHA256 = 'd6e2affbf8e9c6b27e5334640911c94fe077bfc4e3a7db8767dc2a6daeb36232'


def main():
    repo = Path(__file__).resolve().parents[2]
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar', type=Path, default=repo/'target/s3proxy-4.3.0-SNAPSHOT-jar-with-dependencies.jar')
    p.add_argument('--offline-jre', type=Path, help='verified Linux JRE archive for cached-base offline recipe')
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if hashlib.sha256(a.jar.read_bytes()).hexdigest() != JAR_SHA256:
        p.error('JAR differs from the live-tested artifact')
    if a.offline_jre and hashlib.sha256(a.offline_jre.read_bytes()).hexdigest() != JRE_SHA256:
        p.error('JRE differs from the verified runtime')
    if a.output.exists():
        p.error('Use a new output directory; existing files are not merged')
    a.output.mkdir(parents=True)
    (a.output/'lib').mkdir()
    (a.output/'bin').mkdir()
    shutil.copy2(a.jar, a.output/'lib/s3proxy.jar')
    shutil.copy2(repo/'examples/oci-native/offline/bin/launch.sh', a.output/'bin/launch.sh')
    recipe = 'Dockerfile.offline' if a.offline_jre else 'Dockerfile'
    shutil.copy2(Path(__file__).parent/'container'/recipe, a.output/'Dockerfile')
    (a.output/'lib/s3proxy.jar').chmod(0o444)
    (a.output/'bin/launch.sh').chmod(0o555)
    if a.offline_jre:
        # Input must match the pinned publisher digest above; no arbitrary archive.
        with tempfile.TemporaryDirectory() as tmp:
            with tarfile.open(a.offline_jre) as archive:
                archive.extractall(tmp)
            children = list(Path(tmp).iterdir())
            if len(children) != 1:
                raise ValueError('Expected one JRE archive root')
            (a.output/'runtime').mkdir()
            shutil.copytree(children[0], a.output/'runtime/jre', symlinks=True)
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
    dirty = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo, text=True).strip())
    (a.output/'build-inputs.json').write_text(json.dumps({'source_commit': commit, 'source_dirty': dirty,
        'jar_sha256': JAR_SHA256, 'platform': 'linux/amd64', 'recipe': recipe, 'jre_archive_sha256': JRE_SHA256 if a.offline_jre else None, 'site_credentials': False}, indent=2)+'\n')
    print('Staged explicit public inputs; no site configuration copied.')


if __name__ == '__main__':
    main()
