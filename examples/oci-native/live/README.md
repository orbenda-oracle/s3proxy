# Real OCI Object Storage authorization acceptance

This workflow runs the packaged S3Proxy gateway locally against **real OCI
native Object Storage**, with two newly created OCI users and different IAM
policies. It does not use a mock server or the OCI S3 compatibility endpoint.
It qualifies the gateway against public OCI; PCA still needs its own onsite
release, endpoint, CA and IAM acceptance.

## What is created

All resource names use a unique `s3px-<UTC timestamp>-<suffix>` prefix:

- One temporary child compartment under the supplied lab parent.
- Two API-only users, their own signing keys, two groups and one scoped policy.
- Two private buckets: `shared` and `protected`, with synthetic fixtures.
- A loopback-only local S3Proxy process and two gateway keys mapped to the users.

| Principal | Bucket metadata | Shared bucket objects | Protected bucket objects |
| --- | --- | --- | --- |
| Reader | Read | Read/list | Denied |
| Writer | Read | Manage objects | Denied |

The test-only writer temporarily receives protected-bucket object access to
seed a known existing object. The grant is then withdrawn and the workflow
waits until native access is denied before running the S3 matrix. Cleanup
similarly grants only the test writer access to this run's buckets, removes the
data, then removes the policy, keys, memberships, users and groups. Existing
users, groups, policies, firewall rules and application deployments are not
modified. Identity domains requiring email metadata use reserved
`@example.invalid` addresses; no usable mailbox or console password is created.

## Prerequisites

Use a lab operator profile with permission to create a child compartment,
users/groups/memberships/API keys and a policy in that child, and to manage its
bucket metadata. The two new users perform the object-data operations; the
operator is not granted extra permissions. OCI IAM is operated in the
profile's home region; Object Storage uses `--region`.

Required tools: Python 3.11+, OCI Python SDK, boto3, cryptography, Java 17+ and
the built S3Proxy dependency JAR. The recorded run identifies the exact SDK
versions, product commit, runner digest and JAR digest in its evidence file.
Use your approved package versions. For example, install the dependencies in
an isolated Python environment:

```sh
python3 -m venv /tmp/s3proxy-live-venv
/tmp/s3proxy-live-venv/bin/pip install oci boto3 cryptography
mvn verify
```

The live client deliberately uses path-style S3, signing region `us-east-1`,
and boto3 `request_checksum_calculation="when_required"` /
`response_checksum_validation="when_required"`. This is the tested
compatibility profile; other optional checksum modes are not qualified by this
matrix. The local SDK client connects only to `127.0.0.1` over HTTP. The native
OCI connection uses HTTPS with normal certificate verification. Do not expose
the loopback test listener as a deployed service; PCA delivery uses the
private HTTPS design in the [main guide](../README.md).

## Run and recover

Run from the repository root. Supply the lab parent compartment OCID and
operator profile explicitly. Keep `--work-dir` beneath `.private_secrets`, which
is ignored by Git. The runner creates protected files with mode 0600 and a
work directory with mode 0700.

```sh
python examples/oci-native/live/acceptance.py all \
  --operator-profile LAB_OPERATOR \
  --region eu-frankfurt-1 \
  --parent-compartment '<lab-parent-compartment-ocid>' \
  --work-dir .private_secrets/oci-acceptance-UNIQUE-RUN \
  --evidence examples/oci-native/live/evidence-UNIQUE-RUN.json \
  --jar "$PWD/target/s3proxy-4.3.0-SNAPSHOT-jar-with-dependencies.jar"
```

`all` executes provisioning, verification and cleanup in a `finally` block.
The private journal is saved immediately after resource creation, and binds the
run to the operator tenancy, Object Storage region and parent compartment.
It is not a generic resource deletion tool.

The separate `provision`, `verify` and `cleanup` phases support an actively
supervised diagnostic run. If the process is forcibly stopped or a phase
fails, use the **same arguments and work directory** with `cleanup`. Bucket
ownership tags and the child compartment are checked before data deletion.
If bucket cleanup fails, test identities are retained for recovery rather than
leaving data that can no longer be removed. OCI compartment deletion is
asynchronous: the report records the observed state instead of asserting
completion prematurely.

Verification is stateful: native-key rotation intentionally revokes the
original key. Use a fresh work directory for a complete new run after cleanup.
Provisioning can resume its saved journal; it does not recreate identities
whose IDs are already recorded. Changes to policy/key state can require time
to propagate. The runner disables hidden SDK retries and uses bounded,
explicit readiness/denial polling, recording elapsed propagation times. The
default propagation deadline is ten minutes; use `--propagation-timeout` (seconds)
when a different approved window is needed. A prior five-minute readiness
window proved too short in the recorded run. The report binds to the run
journal and archives previous verification attempts separately; a failed or
incomplete current attempt cannot retain a stale PASS verdict. A timeout is a
failed acceptance result, not approval to deploy. In the recorded run, an
already-running gateway observed native key revocation after 561.929 seconds;
ten minutes is a bounded test window, not a guaranteed OCI propagation limit.
Choose the window explicitly for your environment and keep gateway disable
available for urgent cutoff.

## Operations and pass criteria

The matrix verifies:

1. Reader GET/HEAD/list succeed; PUT/delete and multipart mutations are denied.
2. A denied reader delete leaves the known object unchanged.
3. Both users are denied access to the known existing protected object after
   its temporary seed grant is withdrawn.
4. Writer put/get, range reads, conditional create/conflict, copy, delete and
   multipart initiate/parts/list/complete/abort work, with byte-for-byte
   round-trip verification.
5. Invalid signatures and unknown gateway keys are denied.
6. Disabling a gateway key denies it. After secret rotation, the old secret is
   denied and the replacement succeeds without restarting the gateway.
7. A replacement native signing key works, the original is revoked in real
   OCI IAM, and the running gateway using that original key loses access.
8. After gateway restart with the replacement native credential, access and
   data integrity recover for the same mapped native user.

Every case records expectation, result, status, request ID and timestamp when
available. S3 cases carry gateway request IDs; direct native cases and
administrative events carry OCI request IDs. These are not a full OCI Audit
export. Unexpected permission grants fail the run. Native concealed
permission errors may be 404 rather than 403; a 404 is not interpreted as
proof that an object is absent. Positive fixture controls establish that the
protected objects exist, and denied mutations are followed by integrity checks.

Only the evidence schema's allowlisted fields are exported. Secrets, private
keys, populated profiles and raw diagnostics stay in the ignored private work
directory. Successful cleanup removes generated credential files and removes
gateway secrets from the journal. The sanitized evidence can be committed;
review it before sharing. Raw logs and the private journal must not be committed.

## Offline harness checks

These checks exercise evidence redaction, fail-on-unexpected-grant behavior,
private file permissions, prior-PASS invalidation, safe retries of uploaded
keys, bounded cleanup on deletion failure, and protections against using a
journal in another tenancy or cleaning a non-test journal. They never contact OCI:

```sh
python -m unittest discover -s examples/oci-native/live -p 'test_*.py' -v
```

## Evidence and PCA handoff

The dated [OCI evidence](evidence-2026-11-26.json) and accompanying
[validation report](validation-2026-11-26.md) record the supervised run and any
remaining limitations. Do not convert public OCI results into PCA release
certification. The onsite team repeats the relevant matrix using the installed
PCA release, its native base path, private CA, native users/policies and the
selected applications, keeping PCA credentials and results onsite.

References: [OCI Object Storage policy examples](https://docs.oracle.com/en-us/iaas/Content/Identity/policiescommon/commonpolicies.htm),
[Object Storage permission reference](https://docs.oracle.com/en-us/iaas/Content/Identity/Reference/objectstoragepolicyreference.htm),
[API signing key deletion](https://docs.oracle.com/en-us/iaas/Content/Identity/access/to_delete_an_API_signing_key.htm).
