"""Offline safety/evidence checks; these tests never contact OCI."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

import oci
from botocore.exceptions import ClientError

spec = importlib.util.spec_from_file_location("live_acceptance", Path(__file__).with_name("acceptance.py"))
live = importlib.util.module_from_spec(spec)
spec.loader.exec_module(live)


class EvidenceSafetyTest(unittest.TestCase):
    def test_errors_never_export_messages_or_credentials(self):
        errors = [
            oci.exceptions.ServiceError(403, "NotAuthorized", {"opc-request-id": "safe-id"}, "DO-NOT-EXPORT"),
            ClientError({"Error": {"Code": "AccessDenied", "Message": "DO-NOT-EXPORT"},
                         "ResponseMetadata": {"HTTPStatusCode": 403, "RequestId": "safe-id"}}, "GetObject"),
        ]
        for error in errors:
            evidence = live.error_fields(error)
            self.assertNotIn("DO-NOT-EXPORT", json.dumps(evidence))
            self.assertEqual(403, evidence["status"])
            self.assertEqual("safe-id", evidence["request_id"])

    def test_unexpected_permission_grant_is_a_failed_case(self):
        with tempfile.TemporaryDirectory() as temp:
            run = live.Acceptance.__new__(live.Acceptance)
            run.work = Path(temp)
            run.report = {"cases": []}
            run.persist = lambda: None
            with contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(AssertionError):
                    run.record("reader-write", "deny", lambda: {"ResponseMetadata": {"HTTPStatusCode": 200}})
            self.assertEqual("FAIL", run.report["cases"][0]["outcome"])

    def test_secret_files_are_private(self):
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / "key"
            live.write_private(file, "test-only")
            self.assertEqual(0o600, file.stat().st_mode & 0o777)

    def test_wrong_tenancy_journal_fails_before_any_client_is_created(self):
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp) / ".private_secrets" / "test"
            work.mkdir(parents=True)
            context = {"tenancy": "tenancy-a", "region": "region", "parent_compartment": "parent"}
            (work / "state.json").write_text(json.dumps({"context": context}))
            args = types.SimpleNamespace(work_dir=str(work), evidence=str(Path(temp) / "evidence.json"),
                                         operator_profile="TEST", region="region", parent_compartment="parent")
            with patch.object(oci.config, "from_file", return_value={"tenancy": "tenancy-b"}), \
                    patch.object(oci.identity, "IdentityClient") as identity:
                with self.assertRaisesRegex(ValueError, "does not match"):
                    live.Acceptance(args)
                identity.assert_not_called()

    def test_cleanup_refuses_a_non_test_journal(self):
        run = live.Acceptance.__new__(live.Acceptance)
        run.stop_gateway = lambda: None
        run.state = {"run_id": "production"}
        with self.assertRaisesRegex(ValueError, "not an acceptance"):
            run.cleanup()

    def test_new_verification_cannot_keep_a_previous_pass(self):
        run = live.Acceptance.__new__(live.Acceptance)
        run.report = {"verdict": "PASS", "verification_started_utc": "old",
                      "verification_finished_utc": "old-finish",
                      "cases": [{"outcome": "PASS"}]}
        run.persist = lambda: None
        run.begin_verification()
        self.assertEqual("RUNNING", run.report["verdict"])
        self.assertNotIn("verification_finished_utc", run.report)
        self.assertEqual([], run.report["cases"])
        self.assertEqual("PASS", run.report["previous_attempts"][0]["verdict"])

    def test_report_from_another_run_cannot_be_reused(self):
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp) / ".private_secrets" / "test"
            work.mkdir(parents=True)
            (work / "state.json").write_text(json.dumps({"run_id": "s3px-a"}))
            evidence = Path(temp) / "evidence.json"
            evidence.write_text(json.dumps({"run_id": "s3px-b", "verdict": "PASS"}))
            args = types.SimpleNamespace(work_dir=str(work), evidence=str(evidence), region="region")
            with self.assertRaisesRegex(ValueError, "different run"):
                live.Acceptance(args)

    def test_failed_page_deletion_stops_and_preserves_cleanup_identity(self):
        from unittest.mock import Mock
        run = live.Acceptance.__new__(live.Acceptance)
        run.stop_gateway = lambda: None
        run.persist = lambda: None
        run.state = {"run_id": "s3px-test", "namespace": "namespace", "compartment": "child",
                     "buckets": {"shared": "test-bucket"}, "keys": {"writer-cleanup": {}},
                     "groups": {"writer": "test-group"}, "policy": "test-policy"}
        run.report = {"cleanup": {}}
        run.identity = Mock()
        run.storage = Mock()
        run.storage.get_bucket.return_value.data = types.SimpleNamespace(
            compartment_id="child", freeform_tags={"TestRun": "s3px-test"})
        actor = Mock()
        page = types.SimpleNamespace(objects=[types.SimpleNamespace(name="object")], next_start_with="more")
        actor.list_objects.side_effect = [types.SimpleNamespace(data=page), types.SimpleNamespace(data=page),
                                          AssertionError("cleanup did not stop after failed deletion")]
        actor.delete_object.side_effect = oci.exceptions.ServiceError(403, "Denied", {}, "test")
        run.native_actor = lambda *args: actor
        run.wait_access = lambda name, call: call()
        with patch.object(oci.pagination, "list_call_get_all_results", return_value=types.SimpleNamespace(data=[])):
            with self.assertRaisesRegex(RuntimeError, "identities retained"):
                run.cleanup()
        self.assertEqual(2, actor.list_objects.call_count)
        run.identity.delete_policy.assert_not_called()
        run.identity.delete_user.assert_not_called()

    def test_retry_never_overwrites_an_already_uploaded_native_key(self):
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as temp:
            run = live.Acceptance.__new__(live.Acceptance)
            run.work = Path(temp)
            file = run.work / "writer-b.pem"
            file.write_text("test-only-key-material")
            entry = {"file": str(file), "fingerprint": "test-only", "revoked": False}
            run.state = {"keys": {"writer-b": entry}}
            run.identity = Mock()
            self.assertIs(entry, run.new_native_key("writer", "b"))
            self.assertEqual("test-only-key-material", file.read_text())
            entry["revoked"] = True
            with self.assertRaisesRegex(ValueError, "already revoked"):
                run.new_native_key("writer", "b")
            run.identity.upload_api_key.assert_not_called()


if __name__ == "__main__":
    unittest.main()
