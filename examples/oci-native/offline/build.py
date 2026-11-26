#!/usr/bin/env python3
"""Assemble an offline release from explicitly supplied, verified inputs."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

JAR_SHA256 = 'd6e2affbf8e9c6b27e5334640911c94fe077bfc4e3a7db8767dc2a6daeb36232'
JRE_SHA256 = '5a092a0bc21de4c3d855480edd7945be9cfe157b38cdb84dac280511893bb009'
PRODUCT_COMMIT = '875243f0119552eea04bd26abc5a4fd0f5bea2cc'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('jre', 'jre-metadata', 'wheels', 'dependency-jars', 'output', 'release'):
        p.add_argument('--' + name, required=True)
    a = p.parse_args()
    if not a.release.replace('-', '').replace('.', '').replace('_', '').isalnum():
        p.error('Release must be a simple filename')
    repo = Path(__file__).resolve().parents[3]
    jar = repo/'target/s3proxy-4.3.0-SNAPSHOT-jar-with-dependencies.jar'
    jre = Path(a.jre)
    if digest(jar) != JAR_SHA256 or digest(jre) != JRE_SHA256:
        p.error('Input differs from the pinned/tested JAR or publisher runtime digest')
    if subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=no'], cwd=repo, text=True).strip():
        p.error('Commit source changes before building')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
    out = Path(a.output).resolve()
    out.mkdir(parents=True, exist_ok=True)
    archive = out/(a.release + '.tar.gz')
    if archive.exists():
        p.error('Output exists; use a new immutable release name')
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)/a.release
        root.mkdir()
        for name in ('bin', 'config', 'docs', 'tests'):
            shutil.copytree(Path(__file__).parent/name, root/name, ignore=shutil.ignore_patterns('__pycache__'))
        for name in ('README.md', 'requirements.txt'):
            shutil.copy2(Path(__file__).parent/name, root/name)
        (root/'lib').mkdir()
        shutil.copy2(jar, root/'lib/s3proxy.jar')
        (root/'runtime').mkdir()
        # The archive must match the hard-pinned publisher digest above.
        with tarfile.open(jre) as source:
            base = Path(tmp)/'jre-extracted'
            base.mkdir()
            for member in source.getmembers():
                if not (base/member.name).resolve().is_relative_to(base.resolve()):
                    raise ValueError('Invalid runtime archive path')
            source.extractall(base)
        children = list(base.iterdir())
        if len(children) != 1:
            raise ValueError('Expected one runtime root')
        shutil.move(str(children[0]), root/'runtime/jre')
        shutil.copy2(a.jre_metadata, root/'runtime/publisher-metadata.json')
        shutil.copytree(a.wheels, root/'wheels')
        for file in (root/'wheels').iterdir():
            if not file.name.endswith('.whl'):
                raise ValueError('Wheel directory must contain only wheels')
        (root/'source').mkdir()
        with (root/'source/s3proxy-source.tar').open('wb') as stream:
            subprocess.run(['git', 'archive', '--format=tar', commit], cwd=repo, stdout=stream, check=True)
        shutil.copy2(repo/'LICENSE', root/'source/LICENSE-s3proxy')
        dependencies = Path(a.dependency_jars)
        if not list(dependencies.glob('*.jar')):
            raise ValueError('Original runtime dependency JARs are required for notices')
        shutil.copytree(dependencies, root/'source/dependency-jars')
        inventory = [{'file': p.name, 'sha256': digest(p)} for p in sorted(dependencies.glob('*.jar'))]
        (root/'source/dependency-inventory.json').write_text(json.dumps(inventory, indent=2)+'\n')
        shutil.copytree(repo/'examples/oci-native/live', root/'docs/public-oci-evidence', ignore=shutil.ignore_patterns('__pycache__'))
        # Keep reference-guide relative links usable inside the offline bundle.
        guide = (repo/'examples/oci-native/README.md').read_text()
        guide = guide.replace('(live/', '(public-oci-evidence/').replace('(offline/README.md)', '(../README.md)')
        (root/'docs/README.md').write_text(guide)
        shutil.copy2(repo/'examples/oci-native/s3proxy.properties', root/'docs/s3proxy.properties')
        # Preserve packaged dependency notices plus original wheel/JRE license files.
        import zipfile
        with zipfile.ZipFile(jar) as z:
            notices = [n for n in z.namelist() if not n.endswith('/') and any(v in n.upper() for v in ('LICENSE', 'NOTICE', 'COPYING'))]
            with zipfile.ZipFile(root/'source/jar-license-notices.zip', 'w') as target:
                for n in notices:
                    target.writestr(n, z.read(n))
        manifest = {'release': a.release, 'source_commit': commit, 'product_commit': PRODUCT_COMMIT,
                    'target': 'Linux x86_64 glibc; Oracle Linux 8/9 qualification required',
                    'java': 'Eclipse Temurin 17.0.21.1+2 Linux x64 JRE',
                    'jar_sha256': JAR_SHA256, 'jre_archive_sha256': JRE_SHA256,
                    'pca_qualified': False, 'contains_site_secrets': False,
                    'validation_python': '3.11+ with venv/pip; provided by site OS',
                    'licenses': 'source/dependency-jars original META-INF licenses/notices, source/LICENSE-s3proxy, source/jar-license-notices.zip, runtime/jre/legal, wheels/*.whl dist-info licenses'}
        (root/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
        sums = []
        for file in sorted(root.rglob('*')):
            if file.is_file():
                sums.append(digest(file)+'  '+file.relative_to(root).as_posix())
        (root/'SHA256SUMS').write_text('\n'.join(sums)+'\n')
        with tarfile.open(archive, 'w:gz') as target:
            target.add(root, arcname=a.release)
    (out/(archive.name+'.sha256')).write_text(digest(archive)+'  '+archive.name+'\n')
    print(json.dumps({'archive': str(archive), 'bytes': archive.stat().st_size, 'sha256': digest(archive)}))


if __name__ == '__main__':
    main()
