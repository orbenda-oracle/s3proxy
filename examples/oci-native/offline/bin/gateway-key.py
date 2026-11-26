#!/usr/bin/env python3
"""Provision gateway secrets into protected files; never print credentials."""
import argparse
import os
from pathlib import Path
import re
import secrets
import stat
import tempfile


def setting(text, key, value):
    text, count = re.subn(r'(?m)^' + re.escape(key) + r'=.*$', key + '=' + value, text)
    if count != 1:
        raise ValueError('Expected exactly one setting: ' + key)
    return text


def create(path, text):
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, 'w') as f:
        f.write(text)


def update(path, text):
    before = path.stat()
    fd, tmp = tempfile.mkstemp(prefix='.key-', dir=path.parent)
    try:
        os.fchown(fd, before.st_uid, before.st_gid)
        os.fchmod(fd, stat.S_IMODE(before.st_mode))
        with os.fdopen(fd, 'w') as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def main():
    os.umask(0o077)
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=['create', 'rotate', 'disable', 'enable'])
    p.add_argument('--properties', required=True, type=Path)
    p.add_argument('--template', type=Path)
    p.add_argument('--credentials', type=Path, help='NEW client-only AWS credentials file')
    p.add_argument('--profile', default='pca-s3')
    a = p.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]+', a.profile) or a.properties.is_symlink():
        p.error('Use a simple profile alias and a regular properties file')
    if a.action in ('disable', 'enable'):
        update(a.properties, setting(a.properties.read_text(), 's3proxy.oci.enabled', str(a.action == 'enable').lower()))
    else:
        if not a.credentials or a.credentials.exists():
            p.error('Supply a NEW --credentials path')
        if a.action == 'create':
            if not a.template or a.properties.exists():
                p.error('Create needs a filled --template and a NEW properties path')
            text = a.template.read_text()
            access = secrets.token_hex(16)
            text = setting(text, 's3proxy.identity', access)
        else:
            text = a.properties.read_text()
            ids = re.findall(r'(?m)^s3proxy.identity=(.+)$', text)
            if len(ids) != 1:
                p.error('Expected exactly one gateway access key')
            access = ids[0]
        secret = secrets.token_urlsafe(32)
        text = setting(text, 's3proxy.credential', secret)
        if a.action == 'create' and 'REPLACE_' in text:
            p.error('Fill site placeholders first; gateway key placeholders are generated')
        create(a.credentials, '[' + a.profile + ']\naws_access_key_id=' + access + '\naws_secret_access_key=' + secret + '\n')
        try:
            if a.action == 'create':
                create(a.properties, text)
            else:
                update(a.properties, text)
        except Exception:
            a.credentials.unlink()
            raise
    print('Completed; no secret values printed.')


if __name__ == '__main__':
    main()
