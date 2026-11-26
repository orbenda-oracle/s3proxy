#!/usr/bin/env python3
"""Onsite S3 acceptance. Uses existing site users/buckets; never provisions IAM."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import boto3
from botocore.config import Config
from botocore.exceptions import ClientError


class Recorder:
    def __init__(self, path, run_id, phase):
        self.path = path
        self.report = {'run_id': run_id, 'phase': phase, 'verdict': 'RUNNING', 'cases': []}
        self.save()

    def save(self):
        tmp = self.path.with_suffix(self.path.suffix + '.new')
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'w') as f:
            json.dump(self.report, f, indent=2)
            f.write('\n')
        os.replace(tmp, self.path)

    def check(self, name, expected, operation, validate=None, status=None, code=None):
        item = {'name': name, 'expected': expected, 'time_utc': dt.datetime.now(dt.timezone.utc).isoformat(), 'outcome': 'FAIL'}
        response = None
        try:
            response = operation()
            meta = response.get('ResponseMetadata', {})
            item.update(status=meta.get('HTTPStatusCode'), request_id=meta.get('RequestId'))
            if expected != 'allow':
                raise AssertionError('unexpected grant')
            if validate:
                validate(response)
            item['outcome'] = 'PASS'
        except ClientError as e:
            meta = e.response.get('ResponseMetadata', {})
            item.update(status=meta.get('HTTPStatusCode'), request_id=meta.get('RequestId'), code=e.response.get('Error', {}).get('Code'))
            if expected == 'deny' and item['status'] in ((status,) if status else (401, 403, 404)) and (code is None or item.get('code') == code):
                item['outcome'] = 'PASS'
        except Exception as e:
            item['exception'] = type(e).__name__
        self.report['cases'].append(item)
        if item['outcome'] == 'FAIL':
            self.report['verdict'] = 'FAIL'
        self.save()
        print(json.dumps(item), flush=True)
        if item['outcome'] != 'PASS':
            raise AssertionError('Acceptance failed: ' + name)
        return response


def body_equals(response, wanted):
    with response['Body'] as f:
        actual = f.read()
    if actual != wanted:
        raise AssertionError('Payload mismatch')


def assert_listing(response, key):
    if key not in {obj['Key'] for obj in response.get('Contents', [])}:
        raise AssertionError('Seed absent from object listing')


def assert_parts(response, uploaded, sizes):
    actual = [(p['PartNumber'], p['ETag'], p['Size']) for p in response.get('Parts', [])]
    expected = [(p['PartNumber'], p['ETag'], size) for p, size in zip(uploaded, sizes)]
    if actual != expected:
        raise AssertionError('Multipart listing differs from uploaded parts')


def client(profile, args):
    return boto3.Session(profile_name=profile).client('s3', endpoint_url=args.endpoint,
        region_name='us-east-1', verify=args.ca,
        config=Config(s3={'addressing_style': 'path'}, retries={'total_max_attempts': 1},
                      request_checksum_calculation='when_required', response_checksum_validation='when_required',
                      connect_timeout=10, read_timeout=90))


def main():
    os.umask(0o077)
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('phase', choices=['baseline', 'gateway-disabled', 'gateway-rotated', 'native-revoked', 'native-recovered', 'cleanup'])
    for name in ('endpoint', 'ca', 'shared-bucket', 'run-id', 'report'):
        p.add_argument('--' + name, required=True)
    p.add_argument('--reader-profile', default='pca-reader')
    p.add_argument('--writer-profile', default='pca-writer')
    p.add_argument('--old-reader-profile', default='pca-reader-old')
    p.add_argument('--protected-bucket')
    p.add_argument('--protected-key')
    a = p.parse_args()
    if not a.endpoint.startswith('https://') or not Path(a.ca).is_file():
        p.error('HTTPS endpoint and gateway CA file are required')
    if not re.fullmatch(r'onsite-[A-Za-z0-9-]+', a.run_id):
        p.error('Use a unique onsite-... run ID')
    if a.phase == 'baseline' and (not a.protected_bucket or not a.protected_key):
        p.error('Baseline requires a known existing protected fixture')
    if Path(a.report).exists():
        p.error('Use a new report file for each attempt; previous evidence is never overwritten')
    r = Recorder(Path(a.report), a.run_id, a.phase)
    try:
        reader, writer = client(a.reader_profile, a), client(a.writer_profile, a)
        prefix = 'acceptance/' + a.run_id + '/'
        key, copy, multipart, abort = [prefix + v for v in ('seed-שלום+file.bin', 'copy.bin', 'multipart.bin', 'abort.bin')]
        data = b'synthetic onsite validation\n' * 100
        def get(c, name='seed', target=key, payload=data):
            return r.check(name, 'allow', lambda: c.get_object(Bucket=a.shared_bucket, Key=target), lambda x: body_equals(x, payload))
        if a.phase == 'baseline':
            r.check('writer-create', 'allow', lambda: writer.put_object(Bucket=a.shared_bucket, Key=key, Body=data, IfNoneMatch='*'))
            get(reader, 'reader-integrity')
            r.check('reader-head', 'allow', lambda: reader.head_object(Bucket=a.shared_bucket, Key=key))
            r.check('reader-list', 'allow', lambda: reader.get_paginator('list_objects_v2').paginate(Bucket=a.shared_bucket, Prefix=prefix).build_full_result(), lambda x: assert_listing(x, key))
            r.check('reader-put-denied', 'deny', lambda: reader.put_object(Bucket=a.shared_bucket, Key=key, Body=b'forbidden'))
            r.check('reader-delete-denied', 'deny', lambda: reader.delete_object(Bucket=a.shared_bucket, Key=key))
            get(reader, 'fixture-survives-denied-mutations')
            for alias, c in [('reader', reader), ('writer', writer)]:
                r.check(alias + '-protected-denied', 'deny', lambda c=c: c.get_object(Bucket=a.protected_bucket, Key=a.protected_key))
            r.check('reader-range', 'allow', lambda: reader.get_object(Bucket=a.shared_bucket, Key=key, Range='bytes=3-17'), lambda x: body_equals(x, data[3:18]))
            r.check('conditional-conflict', 'deny', lambda: writer.put_object(Bucket=a.shared_bucket, Key=key, Body=b'no', IfNoneMatch='*'), status=412)
            r.check('writer-copy', 'allow', lambda: writer.copy_object(Bucket=a.shared_bucket, Key=copy, CopySource={'Bucket': a.shared_bucket, 'Key': key}))
            get(writer, 'copy-integrity', copy)
            r.check('reader-multipart-init-denied', 'deny', lambda: reader.create_multipart_upload(Bucket=a.shared_bucket, Key=abort))
            upload = r.check('writer-multipart-init', 'allow', lambda: writer.create_multipart_upload(Bucket=a.shared_bucket, Key=multipart))['UploadId']
            r.check('reader-multipart-abort-denied', 'deny', lambda: reader.abort_multipart_upload(Bucket=a.shared_bucket, Key=multipart, UploadId=upload))
            parts = []
            payloads = [b'A' * (6 * 1024 * 1024), b'B' * 65536]
            for number, payload in enumerate(payloads, 1):
                result = r.check('writer-part-' + str(number), 'allow', lambda n=number, b=payload: writer.upload_part(Bucket=a.shared_bucket, Key=multipart, UploadId=upload, PartNumber=n, Body=b))
                parts.append({'PartNumber': number, 'ETag': result['ETag']})
            r.check('writer-list-parts', 'allow', lambda: writer.get_paginator('list_parts').paginate(Bucket=a.shared_bucket, Key=multipart, UploadId=upload).build_full_result(), lambda x: assert_parts(x, parts, [len(b) for b in payloads]))
            r.check('writer-complete', 'allow', lambda: writer.complete_multipart_upload(Bucket=a.shared_bucket, Key=multipart, UploadId=upload, MultipartUpload={'Parts': parts}))
            get(writer, 'multipart-integrity', multipart, b''.join(payloads))
            uid = r.check('writer-abort-init', 'allow', lambda: writer.create_multipart_upload(Bucket=a.shared_bucket, Key=abort))['UploadId']
            r.check('writer-abort', 'allow', lambda: writer.abort_multipart_upload(Bucket=a.shared_bucket, Key=abort, UploadId=uid))
            r.check('writer-delete', 'allow', lambda: writer.delete_object(Bucket=a.shared_bucket, Key=copy))
            r.check('deleted-copy-absent', 'deny', lambda: writer.get_object(Bucket=a.shared_bucket, Key=copy), status=404, code='NoSuchKey')
            r.report['fixture_sha256'] = hashlib.sha256(data).hexdigest()
        elif a.phase == 'gateway-disabled':
            r.check('disabled-reader-denied', 'deny', lambda: reader.get_object(Bucket=a.shared_bucket, Key=key))
            get(writer, 'writer-still-allowed')
        elif a.phase == 'gateway-rotated':
            old = client(a.old_reader_profile, a)
            r.check('old-secret-denied', 'deny', lambda: old.get_object(Bucket=a.shared_bucket, Key=key))
            get(reader, 'new-secret-allowed')
        elif a.phase == 'native-revoked':
            r.check('revoked-writer-native-key-denied', 'deny', lambda: writer.get_object(Bucket=a.shared_bucket, Key=key))
            get(reader, 'reader-control-still-allowed')
        elif a.phase == 'native-recovered':
            r.check('replacement-native-key-put', 'allow', lambda: writer.put_object(Bucket=a.shared_bucket, Key=key, Body=data))
            get(writer, 'replacement-native-key-integrity')
        else:
            uploads = writer.get_paginator('list_multipart_uploads')
            for page in uploads.paginate(Bucket=a.shared_bucket, Prefix=prefix):
                for u in page.get('Uploads', []):
                    r.check('cleanup-abort', 'allow', lambda u=u: writer.abort_multipart_upload(Bucket=a.shared_bucket, Key=u['Key'], UploadId=u['UploadId']))
            for target in (key, copy, multipart, abort):
                r.check('cleanup-test-object', 'allow', lambda k=target: writer.delete_object(Bucket=a.shared_bucket, Key=k))
        r.report['verdict'] = 'PASS'
        r.save()
        return 0
    except Exception as e:
        r.report.update(verdict='FAIL', exception=type(e).__name__)
        r.save()
        return 1


if __name__ == '__main__':
    sys.exit(main())
