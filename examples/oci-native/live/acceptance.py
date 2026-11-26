#!/usr/bin/env python3
"""Real OCI acceptance; generates test-only identities and always journals IDs.

Secrets/raw logs stay in --work-dir, which must be under .private_secrets.
Only an explicit allowlist of evidence fields is written to --evidence.
"""
import argparse
import base64
import configparser
import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time
import traceback

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
import oci


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def write_private(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temp = path.with_name(path.name + ".new")
    with open(temp, "w", encoding="utf-8") as stream:
        os.chmod(temp, 0o600)
        stream.write(data)
    os.replace(temp, path)


def save_json(path, value):
    write_private(path, json.dumps(value, indent=2, sort_keys=True) + "\n")


def error_fields(error):
    if isinstance(error, ClientError):
        metadata = error.response.get("ResponseMetadata", {})
        return {"status": metadata.get("HTTPStatusCode"),
                "code": error.response.get("Error", {}).get("Code"),
                "request_id": metadata.get("RequestId")}
    if isinstance(error, oci.exceptions.ServiceError):
        return {"status": error.status, "code": error.code,
                "request_id": getattr(error, "request_id", None) or error.headers.get("opc-request-id")}
    return {"exception": type(error).__name__}


class Acceptance:
    def __init__(self, args):
        self.args = args
        self.work = Path(args.work_dir).resolve()
        if ".private_secrets" not in self.work.parts:
            raise ValueError("--work-dir must be inside .private_secrets")
        self.work.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self.work, 0o700)
        self.state_path = self.work / "state.json"
        self.state = json.loads(self.state_path.read_text()) if self.state_path.exists() else {}
        self.evidence_path = Path(args.evidence).resolve()
        self.report = json.loads(self.evidence_path.read_text()) if self.evidence_path.exists() else {
            "started_utc": now(), "target": "real OCI Object Storage native API",
            "region": args.region, "cases": [], "events": [], "cleanup": {}}
        if self.report.get("run_id") and self.report["run_id"] != self.state.get("run_id"):
            raise ValueError("evidence belongs to a different run journal")
        self.config = oci.config.from_file(profile_name=args.operator_profile)
        context = {"tenancy": self.config["tenancy"], "region": args.region,
                   "parent_compartment": args.parent_compartment}
        if self.state.get("context") not in (None, context):
            raise ValueError("operator tenancy, region or parent does not match run journal")
        self.state["context"] = context
        self.identity = oci.identity.IdentityClient(self.config, timeout=(10, 30), retry_strategy=oci.retry.NoneRetryStrategy())
        self.storage = oci.object_storage.ObjectStorageClient(
            {**self.config, "region": args.region}, timeout=(10, 30), retry_strategy=oci.retry.NoneRetryStrategy())
        self.process = None
        self.s3_clients = []
        self.namespace = self.state.get("namespace")
        self.persist()

    def persist(self):
        save_json(self.state_path, self.state)
        save_json(self.evidence_path, self.report)

    def event(self, phase, **fields):
        item = {"time_utc": now(), "phase": phase, **fields}
        self.report["events"].append(item)
        self.persist()
        print(json.dumps(item), flush=True)

    def record(self, name, expected, call, validate=None):
        start = time.monotonic()
        try:
            response = call()
            if expected != "allow":
                raise AssertionError("operation unexpectedly allowed")
            if validate:
                validate(response)
            metadata = response.get("ResponseMetadata", {}) if isinstance(response, dict) else {
                "HTTPStatusCode": getattr(response, "status", None),
                "RequestId": getattr(response, "headers", {}).get("opc-request-id")}
            result = {"outcome": "PASS", "observed": "allowed",
                      "status": metadata.get("HTTPStatusCode"),
                      "request_id": metadata.get("RequestId")}
        except (ClientError, oci.exceptions.ServiceError) as error:
            fields = error_fields(error)
            allowed_denials = {401, 403, 404}
            if expected == "deny" and fields.get("status") in allowed_denials:
                result = {"outcome": "PASS", "observed": "denied", **fields}
            else:
                result = {"outcome": "FAIL", **fields}
        except Exception as error:
            result = {"outcome": "FAIL", **error_fields(error)}
            with (self.work / "failure.log").open("a") as log:
                traceback.print_exc(file=log)
        result.update(name=name, expected=expected, time_utc=now(),
                      duration_seconds=round(time.monotonic() - start, 3))
        self.report["cases"].append(result)
        self.persist()
        print(json.dumps(result), flush=True)
        if result["outcome"] != "PASS":
            raise AssertionError("acceptance case failed: " + name)
        return response if expected == "allow" else None

    def provision(self):
        if self.state.get("ready"):
            self.event("provision-reused")
            return
        if "run_id" not in self.state:
            self.state.update({"run_id": "s3px-" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d%H%M%S") + "-" + secrets.token_hex(2),
                          "users": {}, "groups": {}, "memberships": {}, "keys": {}})
        run = self.state["run_id"]
        tags = {"Purpose": "s3proxy-acceptance", "TestRun": run}
        tenancy = self.config["tenancy"]
        self.namespace = self.storage.get_namespace().data
        self.state["namespace"] = self.namespace
        self.report["run_id"] = run
        self.report["source_commit"] = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=self.args.repo, text=True).strip()
        parent = self.identity.get_compartment(self.args.parent_compartment).data
        if parent.lifecycle_state != "ACTIVE":
            raise ValueError("parent compartment is not active")
        if "compartment" not in self.state:
            result = self.identity.create_compartment(oci.identity.models.CreateCompartmentDetails(
                compartment_id=self.args.parent_compartment, name=run,
                description="Temporary real OCI S3Proxy acceptance; test-only resources", freeform_tags=tags))
            self.state["compartment"] = result.data.id
            self.persist()
            self.event("isolated-compartment-created")
        for role in ("reader", "writer"):
            if role not in self.state["users"]:
                response = self.identity.create_user(oci.identity.models.CreateUserDetails(
                    compartment_id=tenancy, name=run + "-" + role,
                    description="Temporary S3Proxy acceptance " + role,
                    email=run + "-" + role + "@example.invalid", freeform_tags=tags))
                self.state["users"][role] = response.data.id
                self.persist()
            if role not in self.state["groups"]:
                response = self.identity.create_group(oci.identity.models.CreateGroupDetails(
                    compartment_id=tenancy, name=run + "-" + role,
                    description="Temporary S3Proxy acceptance " + role, freeform_tags=tags))
                self.state["groups"][role] = response.data.id
                self.persist()
            if role not in self.state["memberships"]:
                response = self.identity.add_user_to_group(oci.identity.models.AddUserToGroupDetails(
                    user_id=self.state["users"][role], group_id=self.state["groups"][role]))
                self.state["memberships"][role] = response.data.id
                self.persist()
            if role + "-a" not in self.state["keys"]:
                self.new_native_key(role, "a")
            self.event("test-user-ready", user_alias=role)
        self.state.setdefault("buckets", {})
        for alias in ("shared", "protected"):
            if alias not in self.state["buckets"]:
                name = run + "-" + alias
                self.storage.create_bucket(self.namespace, oci.object_storage.models.CreateBucketDetails(
                    name=name, compartment_id=self.state["compartment"],
                    public_access_type="NoPublicAccess", versioning="Disabled", freeform_tags=tags))
                self.state["buckets"][alias] = name
                self.persist()
        shared = self.state["buckets"]["shared"]
        if "policy" not in self.state:
            compartment = self.state["compartment"]
            statements = []
            for role, verb in (("reader", "read"), ("writer", "manage")):
                group = self.state["groups"][role]
                statements.extend([
                    f"Allow group id {group} to read buckets in compartment id {compartment}",
                    f"Allow group id {group} to {verb} objects in compartment id {compartment} where target.bucket.name='{shared}'"])
            response = self.identity.create_policy(oci.identity.models.CreatePolicyDetails(
                compartment_id=compartment, name=run + "-objects", statements=statements,
                description="Only new test groups and this isolated test compartment", freeform_tags=tags))
            self.state["policy"] = response.data.id
            self.persist()
        baseline = []
        for role, verb in (("reader", "read"), ("writer", "manage")):
            group = self.state["groups"][role]
            compartment = self.state["compartment"]
            baseline.extend([
                f"Allow group id {group} to read buckets in compartment id {compartment}",
                f"Allow group id {group} to {verb} objects in compartment id {compartment} where target.bucket.name='{shared}'"])
        writer_group = self.state["groups"]["writer"]
        protected = self.state["buckets"]["protected"]
        fixture_statement = (f"Allow group id {writer_group} to manage objects in "
            f"compartment id {self.state['compartment']} where target.bucket.name='{protected}'")
        # Only the newly created writer group gets temporary seed access.
        self.identity.update_policy(self.state["policy"],
            oci.identity.models.UpdatePolicyDetails(statements=baseline + [fixture_statement]))
        actor = self.native_actor("writer")
        try:
            for alias in ("shared", "protected"):
                self.wait_access("writer-fixture-seed-" + alias,
                    lambda: self.ensure_seed(self.state["buckets"][alias]))
            self.record("fixture-protected-object-exists-before-withdrawal", "allow",
                lambda: actor.head_object(self.namespace, protected, "seed.txt"))
        finally:
            self.identity.update_policy(self.state["policy"],
                oci.identity.models.UpdatePolicyDetails(statements=baseline))
            self.event("temporary-protected-seed-grant-withdrawn")
        self.wait_access("protected-policy-withdrawal-propagated",
            lambda: actor.head_object(self.namespace, protected, "seed.txt"), expected="deny")
        self.write_runtime()
        self.state["ready"] = True
        self.persist()
        self.event("provisioned", users=2, groups=2, buckets=2,
                   policy_scope="isolated child compartment; shared bucket objects only")

    def ensure_seed(self, bucket):
        # A timed-out PUT can have committed. Confirm exact bytes before retry.
        actor = self.native_actor("writer")
        seed = b"synthetic s3proxy acceptance seed\n"
        try:
            response = actor.get_object(self.namespace, bucket, "seed.txt")
            if response.data.content == seed:
                return response
        except oci.exceptions.ServiceError as error:
            if error.status != 404:
                raise
        return actor.put_object(self.namespace, bucket, "seed.txt", seed)

    def new_native_key(self, role, version):
        existing = self.state["keys"].get(role + "-" + version)
        if existing:
            if existing.get("revoked"):
                raise ValueError("native key version was already revoked; use a new run")
            return existing
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        pem = key.private_bytes(serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode()
        file = self.work / (role + "-" + version + ".pem")
        write_private(file, pem)
        public = key.public_key().public_bytes(serialization.Encoding.PEM,
            serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        response = self.identity.upload_api_key(self.state["users"][role],
            oci.identity.models.CreateApiKeyDetails(key=public))
        entry = {"fingerprint": response.data.fingerprint, "file": str(file), "revoked": False}
        self.state["keys"][role + "-" + version] = entry
        self.persist()
        return entry

    def actor_config(self, role, version="a"):
        key = self.state["keys"][role + "-" + version]
        return {"user": self.state["users"][role], "tenancy": self.config["tenancy"],
                "region": self.args.region, "fingerprint": key["fingerprint"], "key_file": key["file"]}

    def native_actor(self, role, version="a"):
        return oci.object_storage.ObjectStorageClient(self.actor_config(role, version), timeout=(10, 30), retry_strategy=oci.retry.NoneRetryStrategy())

    def write_runtime(self, writer_version="a"):
        config = configparser.ConfigParser(interpolation=None)
        for role in ("reader", "writer"):
            config[role.upper()] = self.actor_config(role, writer_version if role == "writer" else "a")
        out = io.StringIO()
        config.write(out)
        write_private(self.work / "oci-config", out.getvalue())
        self.state.setdefault("gateway", {})
        for role in ("reader", "writer"):
            credentials = self.state["gateway"].setdefault(role,
                {"access": secrets.token_hex(16), "secret": secrets.token_urlsafe(32)})
            self.gateway_properties(role, credentials["secret"])
        self.persist()

    def gateway_properties(self, role, secret, enabled=True):
        credentials = self.state["gateway"][role]
        text = ("s3proxy.endpoint=http://127.0.0.1:" + str(self.state.get("port", 18080)) + "\n"
            "s3proxy.authorization=aws-v4\n"
            "s3proxy.identity=" + credentials["access"] + "\n"
            "s3proxy.credential=" + secret + "\n"
            "jclouds.provider=oci-native\n"
            "jclouds.endpoint=https://objectstorage." + self.args.region + ".oraclecloud.com\n"
            "s3proxy.oci.user-id=" + self.state["users"][role] + "\n"
            "s3proxy.oci.enabled=" + str(enabled).lower() + "\n"
            "s3proxy.oci.namespace=" + self.namespace + "\n"
            "s3proxy.oci.compartment=" + self.state["compartment"] + "\n"
            "s3proxy.oci.config-file=" + str(self.work / "oci-config") + "\n"
            "s3proxy.oci.profile=" + role.upper() + "\n")
        write_private(self.work / (role + ".properties"), text)

    def wait_access(self, name, operation, expected="allow", timeout=None):
        timeout = timeout or self.args.propagation_timeout
        start = time.monotonic()
        last = None
        attempts = 0
        while time.monotonic() - start < timeout:
            attempts += 1
            try:
                value = operation()
                if expected == "allow":
                    self.event(name, observed="allowed", attempts=attempts,
                               propagation_seconds=round(time.monotonic() - start, 3))
                    return value
            except (ClientError, oci.exceptions.ServiceError) as error:
                last = error_fields(error)
                if expected == "deny" and last.get("status") in (401, 403, 404):
                    self.event(name, observed="denied", attempts=attempts,
                               propagation_seconds=round(time.monotonic() - start, 3), **last)
                    return
            except oci.exceptions.RequestException as error:
                last = error_fields(error)
            time.sleep(5)
        self.event(name, observed="timeout", last_error=last)
        raise TimeoutError(name)

    def start_gateway(self):
        with socket.socket() as port:
            port.bind(("127.0.0.1", 0))
            self.state["port"] = port.getsockname()[1]
        for role in ("reader", "writer"):
            self.gateway_properties(role, self.state["gateway"][role]["secret"])
        self.persist()
        log = open(self.work / "gateway.log", "a")
        self.process = subprocess.Popen(["java", "-jar", self.args.jar,
            "--properties", str(self.work / "reader.properties"),
            "--properties", str(self.work / "writer.properties")], stdout=log, stderr=subprocess.STDOUT)
        log.close()
        for _ in range(100):
            if self.process.poll() is not None:
                raise RuntimeError("gateway exited; inspect protected gateway.log")
            try:
                with socket.create_connection(("127.0.0.1", self.state["port"]), timeout=0.2):
                    return
            except OSError:
                time.sleep(0.2)
        raise TimeoutError("gateway startup")

    def stop_gateway(self):
        for client in self.s3_clients:
            client.close()
        self.s3_clients = []
        if self.process:
            self.process.terminate()
            try:
                self.process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
            self.process = None

    def s3(self, role, secret=None, access=None):
        credentials = self.state["gateway"][role]
        client = boto3.client("s3", endpoint_url="http://127.0.0.1:" + str(self.state["port"]),
            region_name="us-east-1", aws_access_key_id=access or credentials["access"],
            aws_secret_access_key=secret or credentials["secret"],
            config=Config(s3={"addressing_style": "path"}, retries={"total_max_attempts": 1},
                request_checksum_calculation="when_required", response_checksum_validation="when_required",
                connect_timeout=5, read_timeout=45))
        self.s3_clients.append(client)
        return client

    @staticmethod
    def bytes_response(response, expected):
        with response["Body"] as stream:
            data = stream.read()
        if data != expected:
            raise AssertionError("round-trip bytes do not match")

    def begin_verification(self):
        old_cases = self.report.pop("cases", [])
        if self.report.get("verification_started_utc"):
            self.report.setdefault("previous_attempts", []).append({
                "started_utc": self.report["verification_started_utc"],
                "finished_utc": self.report.get("verification_finished_utc"),
                "verdict": self.report.get("verdict", "INCOMPLETE"), "cases": old_cases})
        elif old_cases:
            self.report["fixture_checks"] = old_cases
        self.report["cases"] = []
        self.report.pop("verification_finished_utc", None)
        self.report.pop("payload_integrity", None)
        self.report["verification_started_utc"] = now()
        self.report["verdict"] = "RUNNING"
        self.report["run_status"] = "RUNNING"
        self.persist()

    def verify(self):
        self.begin_verification()
        if not self.state.get("ready"):
            raise ValueError("provision first")
        self.namespace = self.state["namespace"]
        shared, protected = (self.state["buckets"][k] for k in ("shared", "protected"))
        reader_native = self.native_actor("reader")
        writer_native = self.native_actor("writer")
        self.wait_access("reader-native-iam-propagated", lambda: reader_native.head_object(self.namespace, shared, "seed.txt"))
        self.wait_access("writer-native-iam-propagated", lambda: writer_native.put_object(self.namespace, shared, "ready.txt", b"ready"))
        self.report["native_principals"] = {"distinct_users": self.state["users"]["reader"] != self.state["users"]["writer"],
            "distinct_api_keys": self.state["keys"]["reader-a"]["fingerprint"] != self.state["keys"]["writer-a"]["fingerprint"],
            "permissions": {"reader": "read buckets and read shared-bucket objects",
                "writer": "read buckets and manage shared-bucket objects"}}
        self.report["artifact"] = {
            "jar_sha256": hashlib.sha256(Path(self.args.jar).read_bytes()).hexdigest(),
            "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "python": sys.version.split()[0], "oci_sdk": oci.__version__,
            "boto3": boto3.__version__, "s3_signing_region": "us-east-1",
            "request_checksum_calculation": "when_required",
            "response_checksum_validation": "when_required",
            "gateway_listener": "loopback HTTP; local SDK client only",
            "backend_transport": "real OCI HTTPS with normal certificate validation"}
        self.persist()
        if not all(self.report["native_principals"][field] for field in ("distinct_users", "distinct_api_keys")):
            raise AssertionError("acceptance requires distinct users and signing keys")
        self.start_gateway()
        try:
            reader, writer = self.s3("reader"), self.s3("writer")
            seed = b"synthetic s3proxy acceptance seed\n"
            self.record("reader-get-allowed", "allow", lambda: reader.get_object(Bucket=shared, Key="seed.txt"), lambda r: self.bytes_response(r, seed))
            self.record("reader-head-allowed", "allow", lambda: reader.head_object(Bucket=shared, Key="seed.txt"))
            self.record("reader-list-allowed", "allow", lambda: reader.list_objects_v2(Bucket=shared))
            self.record("reader-put-denied", "deny", lambda: reader.put_object(Bucket=shared, Key="seed.txt", Body=b"forbidden"))
            self.record("reader-delete-denied", "deny", lambda: reader.delete_object(Bucket=shared, Key="seed.txt"))
            self.record("seed-intact-after-reader-delete-denial", "allow",
                lambda: reader.get_object(Bucket=shared, Key="seed.txt"),
                lambda r: self.bytes_response(r, seed))
            self.record("reader-protected-get-denied", "deny", lambda: reader.get_object(Bucket=protected, Key="seed.txt"))
            self.record("writer-protected-get-denied", "deny", lambda: writer.get_object(Bucket=protected, Key="seed.txt"))
            self.record("reader-multipart-initiate-denied", "deny", lambda: reader.create_multipart_upload(Bucket=shared, Key="forbidden-mpu"))
            payload = b"synthetic real OCI roundtrip" * 100
            key = "acceptance/folder/שלום+file.bin"
            self.record("writer-put-allowed", "allow", lambda: writer.put_object(Bucket=shared, Key=key, Body=payload, Metadata={"acceptance": "synthetic"}, ContentType="application/octet-stream"))
            self.record("writer-get-integrity", "allow", lambda: writer.get_object(Bucket=shared, Key=key), lambda r: self.bytes_response(r, payload))
            self.record("reader-range-allowed", "allow", lambda: reader.get_object(Bucket=shared, Key=key, Range="bytes=3-17"), lambda r: self.bytes_response(r, payload[3:18]))
            conditional_key = "conditional/" + secrets.token_hex(8)
            self.record("writer-conditional-put-allowed", "allow", lambda: writer.put_object(Bucket=shared, Key=conditional_key, Body=b"first", IfNoneMatch="*"))
            self.record_exact_denial("writer-conditional-conflict", lambda: writer.put_object(Bucket=shared, Key=conditional_key, Body=b"second", IfNoneMatch="*"), 412)
            self.record("writer-copy-allowed", "allow", lambda: writer.copy_object(Bucket=shared, Key="copy.bin", CopySource={"Bucket": shared, "Key": key}))
            self.record("writer-copy-integrity", "allow", lambda: writer.get_object(Bucket=shared, Key="copy.bin"), lambda r: self.bytes_response(r, payload))
            self.multipart(reader, writer, shared)
            self.record("writer-delete-allowed", "allow", lambda: writer.delete_object(Bucket=shared, Key="copy.bin"))
            invalid = self.s3("reader", secret=secrets.token_urlsafe(32))
            unknown = self.s3("reader", access=secrets.token_hex(16))
            self.record("invalid-gateway-signature-denied", "deny", lambda: invalid.get_object(Bucket=shared, Key="seed.txt"))
            self.record("unknown-gateway-key-denied", "deny", lambda: unknown.get_object(Bucket=shared, Key="seed.txt"))
            self.gateway_properties("reader", self.state["gateway"]["reader"]["secret"], enabled=False)
            self.record("gateway-key-revocation-denied", "deny", lambda: reader.get_object(Bucket=shared, Key="seed.txt"))
            rotated = secrets.token_urlsafe(32)
            self.state["gateway"]["reader"]["secret"] = rotated
            self.persist()
            self.gateway_properties("reader", rotated)
            self.record("old-gateway-secret-denied", "deny", lambda: reader.get_object(Bucket=shared, Key="seed.txt"))
            reader = self.s3("reader")
            self.record("rotated-gateway-secret-allowed", "allow", lambda: reader.get_object(Bucket=shared, Key="seed.txt"), lambda r: self.bytes_response(r, seed))
            self.native_rotation(writer, shared)
        finally:
            self.stop_gateway()
        self.report["verification_finished_utc"] = now()
        self.report["verdict"] = "PASS"
        self.report["run_status"] = "VERIFIED"
        self.report["payload_integrity"] = {"algorithm": "SHA-256", "sha256": hashlib.sha256(payload).hexdigest(), "bytes": len(payload)}
        self.persist()
        self.event("verification-complete", cases=len(self.report["cases"]))

    def record_exact_denial(self, name, call, status):
        try:
            call()
        except ClientError as error:
            fields = error_fields(error)
            result = {"name": name, "expected_status": status, "time_utc": now(),
                      "outcome": "PASS" if fields.get("status") == status else "FAIL", **fields}
            self.report["cases"].append(result)
            self.persist()
            print(json.dumps(result), flush=True)
            if result["outcome"] != "PASS":
                raise AssertionError(name)
            return
        raise AssertionError("expected denial: " + name)

    def multipart(self, reader, writer, bucket):
        key = "multipart.bin"
        upload = self.record("writer-multipart-initiate-allowed", "allow", lambda: writer.create_multipart_upload(Bucket=bucket, Key=key))
        upload_id = upload["UploadId"]
        first, last = b"A" * (6 * 1024 * 1024), b"B" * (64 * 1024)
        self.record("reader-multipart-abort-denied", "deny", lambda: reader.abort_multipart_upload(Bucket=bucket, Key=key, UploadId=upload_id))
        a = self.record("writer-multipart-part1-allowed", "allow", lambda: writer.upload_part(Bucket=bucket, Key=key, UploadId=upload_id, PartNumber=1, Body=first))
        b = self.record("writer-multipart-part2-allowed", "allow", lambda: writer.upload_part(Bucket=bucket, Key=key, UploadId=upload_id, PartNumber=2, Body=last))
        self.record("writer-multipart-list-parts-allowed", "allow", lambda: writer.list_parts(Bucket=bucket, Key=key, UploadId=upload_id))
        self.record("writer-multipart-complete-allowed", "allow", lambda: writer.complete_multipart_upload(Bucket=bucket, Key=key, UploadId=upload_id,
            MultipartUpload={"Parts": [{"PartNumber": 1, "ETag": a["ETag"]}, {"PartNumber": 2, "ETag": b["ETag"]}]}))
        self.record("multipart-roundtrip-integrity", "allow", lambda: writer.get_object(Bucket=bucket, Key=key), lambda r: self.bytes_response(r, first + last))
        abort = self.record("writer-abort-upload-initiate", "allow", lambda: writer.create_multipart_upload(Bucket=bucket, Key="abort-me"))
        self.record("writer-multipart-abort-allowed", "allow", lambda: writer.abort_multipart_upload(Bucket=bucket, Key="abort-me", UploadId=abort["UploadId"]))

    def native_rotation(self, writer, shared):
        self.new_native_key("writer", "b")
        new_native = self.native_actor("writer", "b")
        self.wait_access("replacement-native-key-propagated", lambda: new_native.head_object(self.namespace, shared, "seed.txt"))
        old = self.state["keys"]["writer-a"]
        response = self.identity.delete_api_key(self.state["users"]["writer"], old["fingerprint"])
        self.event("native-api-key-revocation-requested", user_alias="writer",
                   status=response.status, request_id=response.headers.get("opc-request-id"))
        old["revoked"] = True
        self.persist()
        self.wait_access("native-key-revocation-propagated", lambda: writer.head_object(Bucket=shared, Key="seed.txt"), expected="deny")
        self.record("revoked-native-key-denied-through-gateway", "deny", lambda: writer.get_object(Bucket=shared, Key="seed.txt"))
        old_native = self.native_actor("writer", "a")
        self.record("revoked-native-key-denied-direct", "deny", lambda: old_native.head_object(self.namespace, shared, "seed.txt"))
        self.stop_gateway()
        self.write_runtime(writer_version="b")
        self.start_gateway()
        replacement = self.s3("writer")
        self.record("rotated-native-key-allowed-after-restart", "allow", lambda: replacement.put_object(Bucket=shared, Key="native-rotation.txt", Body=b"rotated native key"))
        self.record("rotated-native-key-integrity", "allow", lambda: replacement.get_object(Bucket=shared, Key="native-rotation.txt"), lambda r: self.bytes_response(r, b"rotated native key"))

    def cleanup(self):
        self.stop_gateway()
        if not self.state.get("run_id"):
            return
        if not self.state["run_id"].startswith("s3px-"):
            raise ValueError("not an acceptance-run journal")
        errors = []
        def remove(name, operation):
            try:
                operation()
                self.report["cleanup"][name] = "deleted/request accepted"
            except oci.exceptions.ServiceError as error:
                if error.status == 404:
                    self.report["cleanup"][name] = "already absent"
                else:
                    errors.append({"resource_alias": name, **error_fields(error)})
            except Exception as error:
                errors.append({"resource_alias": name, **error_fields(error)})
            self.persist()
        self.namespace = self.state.get("namespace")
        existing_buckets = {}
        for alias, bucket in self.state.get("buckets", {}).items():
            try:
                metadata = self.storage.get_bucket(self.namespace, bucket).data
                if metadata.compartment_id != self.state["compartment"] or metadata.freeform_tags.get("TestRun") != self.state["run_id"]:
                    raise ValueError("bucket ownership does not match acceptance journal")
                existing_buckets[alias] = bucket
            except oci.exceptions.ServiceError as error:
                if error.status == 404:
                    self.report["cleanup"]["bucket-" + alias] = "already absent"
                else:
                    raise
        if existing_buckets:
            if "writer-cleanup" not in self.state["keys"]:
                self.new_native_key("writer", "cleanup")
            group = self.state["groups"]["writer"]
            compartment = self.state["compartment"]
            allowed_buckets = ", ".join("target.bucket.name='" + b + "'" for b in existing_buckets.values())
            cleanup_statements = [
                f"Allow group id {group} to read buckets in compartment id {compartment}",
                f"Allow group id {group} to manage objects in compartment id {compartment} where any {{{allowed_buckets}}}"]
            if self.state.get("policy"):
                self.identity.update_policy(self.state["policy"],
                    oci.identity.models.UpdatePolicyDetails(statements=cleanup_statements))
            else:
                response = self.identity.create_policy(oci.identity.models.CreatePolicyDetails(
                    compartment_id=compartment, name=self.state["run_id"] + "-cleanup",
                    statements=cleanup_statements, description="Recover only this test run's bucket data"))
                self.state["policy"] = response.data.id
                self.persist()
            actor = self.native_actor("writer", "cleanup")
            for alias, bucket in existing_buckets.items():
                self.wait_access("cleanup-access-" + alias,
                    lambda b=bucket: actor.list_objects(self.namespace, b))
                try:
                    uploads = oci.pagination.list_call_get_all_results(actor.list_multipart_uploads,
                        self.namespace, bucket).data
                    for upload in uploads:
                        remove("multipart-" + alias, lambda u=upload, b=bucket: actor.abort_multipart_upload(self.namespace, b, u.object, u.upload_id))
                    previous_page = None
                    for page in range(100):
                        objects = actor.list_objects(self.namespace, bucket).data
                        names = tuple(obj.name for obj in objects.objects)
                        if names and names == previous_page:
                            errors.append({"resource_alias": "objects-" + alias, "exception": "NoDeletionProgress"})
                            break
                        previous_page = names
                        failures_before = len(errors)
                        for obj in objects.objects:
                            remove("object-" + alias, lambda o=obj, b=bucket: actor.delete_object(self.namespace, b, o.name))
                        if len(errors) > failures_before or not objects.next_start_with:
                            break
                    else:
                        errors.append({"resource_alias": "objects-" + alias, "exception": "CleanupPageLimit"})
                    remove("bucket-" + alias, lambda b=bucket: self.storage.delete_bucket(self.namespace, b))
                except oci.exceptions.ServiceError as error:
                    errors.append({"resource_alias": "bucket-" + alias, **error_fields(error)})
        if errors:
            self.report["cleanup"]["errors"] = errors
            self.persist()
            raise RuntimeError("bucket cleanup incomplete; identities retained for recovery")
        if self.state.get("policy"):
            remove("policy", lambda: self.identity.delete_policy(self.state["policy"]))
        for role, user in self.state.get("users", {}).items():
            try:
                keys = self.identity.list_api_keys(user).data
                for key in keys:
                    remove("api-key-" + role, lambda k=key, u=user: self.identity.delete_api_key(u, k.fingerprint))
                memberships = self.identity.list_user_group_memberships(self.config["tenancy"], user_id=user).data
                for membership in memberships:
                    remove("membership-" + role, lambda m=membership: self.identity.remove_user_from_group(m.id))
                remove("user-" + role, lambda u=user: self.identity.delete_user(u))
            except oci.exceptions.ServiceError as error:
                if error.status != 404:
                    errors.append({"resource_alias": "user-" + role, **error_fields(error)})
        for role, group in self.state.get("groups", {}).items():
            remove("group-" + role, lambda g=group: self.identity.delete_group(g))
        if self.state.get("compartment") and not errors:
            remove("compartment", lambda: self.identity.delete_compartment(self.state["compartment"]))
            try:
                status = self.identity.get_compartment(self.state["compartment"]).data.lifecycle_state
                self.report["cleanup"]["compartment_state"] = status
            except oci.exceptions.ServiceError as error:
                if error.status == 404:
                    self.report["cleanup"]["compartment_state"] = "ABSENT"
                else:
                    errors.append({"resource_alias": "compartment-verification", **error_fields(error)})
        if not errors:
            for pattern in ("*.pem", "*.properties", "oci-config"):
                for file in self.work.glob(pattern):
                    file.unlink(missing_ok=True)
            self.state.pop("gateway", None)
            self.report["cleanup"]["local_generated_credentials"] = "removed"
        self.report["cleanup"]["errors"] = errors
        self.report["cleanup"]["finished_utc"] = now()
        self.report["run_status"] = "CLEANED" if not errors else "CLEANUP_FAILED"
        self.persist()
        self.event("cleanup-finished", errors=len(errors))
        if errors:
            raise RuntimeError("cleanup incomplete; see sanitized evidence")


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("provision", "verify", "cleanup", "all"))
    parser.add_argument("--operator-profile", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--parent-compartment", required=True)
    parser.add_argument("--work-dir", required=True)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parents[3]))
    parser.add_argument("--jar", required=True)
    parser.add_argument("--propagation-timeout", type=int, default=600)
    args = parser.parse_args()
    run = Acceptance(args)
    try:
        if args.phase == "all":
            try:
                run.provision()
                run.verify()
            finally:
                run.cleanup()
        else:
            getattr(run, args.phase)()
    except Exception as error:
        run.report["run_status"] = "FAILED"
        if run.report.get("verdict") == "RUNNING" or args.phase == "verify":
            run.report["verdict"] = "FAIL"
            run.report["verification_finished_utc"] = now()
        with (run.work / "failure.log").open("a") as log:
            traceback.print_exc(file=log)
        run.event("run-failed", **error_fields(error))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
