# Real OCI authorization validation — 26 November 2026

The packaged native backend was exercised against real OCI Object Storage in
`eu-frankfurt-1`, using two newly created OCI users with separate API signing
keys. S3 requests passed through the local S3Proxy frontend; backend requests
used the native HTTPS API and native IAM. The OCI S3 compatibility API was not
used. **All 35 checks passed**, including allowed and denied operations, gateway
key disable/rotation, native-key revocation and replacement-key recovery.
Verification ran from 17:31:39 to 17:41:55 UTC (19:31–19:41 Israel time).
Cleanup finished without errors at 17:46:56 UTC. The empty compartment was
observed in asynchronous `DELETING` state; completion is not yet asserted.

## Scope and evidence

Product commit: `875243f0119552eea04bd26abc5a4fd0f5bea2cc`.
Packaged JAR SHA-256:
`d6e2affbf8e9c6b27e5334640911c94fe077bfc4e3a7db8767dc2a6daeb36232`.
Runner SHA-256:
`f8cc10f1b2b5f17dd2af60f0cab2f1f61680af74cccd20ed0182ac02001e02a9`.
The [machine-readable evidence](evidence-2026-11-26.json) records individual
statuses, timestamps, request IDs, artifact hashes and previous attempts.
The [operator guide](README.md) explains repeatable execution and recovery.

| Principal | Bucket metadata | Shared objects | Protected objects |
| --- | --- | --- | --- |
| Reader | Read | Read/list | Denied |
| Writer | Read | Manage | Denied |

Both native buckets were private. A positive control read the protected
fixture before the temporary seed grant was withdrawn. The matrix then checked
both users' denials against that known existing object. A denied reader delete
was followed by a successful integrity read, so an ambiguous 404 did not count
as a successful deletion.

The operation matrix covers GET/HEAD/list, denied reader PUT/delete/multipart,
writer PUT/GET/copy/delete, Unicode and plus-sign object names, ranges,
conditional creation and HTTP 412 conflicts, and native multipart parts,
listing, completion, abort and byte-for-byte recovery. Authentication checks
cover invalid signatures, unknown gateway keys, gateway disable and secret
rotation. Native-key checks distinguish key registration, deletion in IAM,
observed denial in Object Storage, and recovery after a profile update/restart.

## Operational observations

- Withdrawal of the temporary protected-object grant was observed by the reused
  native client after 245.217 seconds. This is one measured observation, not an
  OCI propagation guarantee.
- The first replacement-native-key readiness window expired after five minutes
  with HTTP 401. The old key had not been deleted. A later supervised full
  verification retry observed the replacement working before deleting the old
  key. The retry's 1.430-second readiness check is not total propagation time
  since key creation. The failed first attempt remains in the evidence.
- IAM accepted original native-key deletion at 17:32:28 UTC. The already-running
  gateway first observed denial after **561.929 seconds** (9 minutes 22 seconds),
  returning HTTP 403; a direct native request using the revoked key returned
  HTTP 401. A fresh native client had observed denial earlier. These are
  observations of this run, without an asserted cache mechanism or global SLA.
  Restarting with the already-validated replacement key restored PUT/GET and
  exact payload integrity. Native deletion is therefore not an immediate cutoff
  for every existing client.
- Gateway disable returned HTTP 403 on the next tested request (4 ms), and the
  old gateway secret returned HTTP 403 after rotation (5 ms). The new secret
  succeeded without restarting. These are sample request durations, not SLAs;
  requests already admitted can finish.
- For urgent cutoff through this gateway, disable the mapped gateway key first.
  Native IAM/key changes propagate separately. Disabling a gateway key does
  not revoke another client's direct native API credentials.
- Native signing-key rotation requires updating the protected profile and
  restarting the gateway. Verify the replacement against Object Storage before
  revoking the old native key; then verify old-key denial and new-key recovery.

## Cleanup

Cleanup removed synthetic objects and incomplete multipart uploads, both
buckets, test policy, signing keys, group memberships, both users and both
groups. Independent post-cleanup GET checks confirmed both buckets, the policy,
users and groups absent. Generated PEM keys, runtime profiles and properties
were removed, and gateway secrets were removed from the private journal.
Existing operator credentials were untouched. Cleanup reported zero errors.

The empty child compartment deletion request was accepted; the subsequent
read returned `DELETING`. OCI deletes compartments asynchronously. This report
records the observed state rather than claiming its final deletion completed.
The public evidence contains no credentials or populated native profiles.

## Reproduction and limits

The recorded client uses Python 3.11.7, OCI Python SDK 2.160.0 and boto3 1.40.76,
path-style S3, signing region `us-east-1`, and optional checksum settings
`request_checksum_calculation="when_required"` and
`response_checksum_validation="when_required"`. Other checksum modes and other
applications are outside this matrix. Java 17.0.11 ran the packaged JAR.
The local client listener was loopback HTTP; backend OCI HTTPS used normal
certificate validation. Production delivery requires the private HTTPS
configuration in the [backend guide](../README.md).

All payloads and identities were synthetic and isolated in a test compartment.
The operator's existing permissions were not broadened. The test writer seeded
and cleaned this run's objects using temporary, bucket-scoped policy changes.
Raw logs, credential files and journals are excluded from public evidence.
Gateway request IDs and OCI request IDs are labelled by operation layer; this
report does not claim an OCI Audit export.

Product verification before this live run passed `mvn verify`: 1,514 tests,
zero failures/errors, 32 skipped, with Checkstyle and Modernizer passing.
Nine offline harness safety tests also passed. Independent read-only review
found no remaining material evidence, authorization or cleanup issues.

**PCA deployment remains gated.** Public OCI validation does not certify an
installed PCA release. Confirm the actual PCA build, native endpoint/base path,
private CA, native IAM/key lifecycle, selected S3 clients and operational
restart/retry behavior onsite before deployment. No PCA deployment or customer
data access was performed by this test.
