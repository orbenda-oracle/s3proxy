# S3 clients to PCA native Object Storage

This fork adds `jclouds.provider=oci-native` (alias `pca-native`). It translates
S3 requests to the **native** OCI Object Storage API with the OCI Java SDK;
it does not call a backend S3 compatibility endpoint. Objects and multipart
uploads live in native buckets, so native clients see the same data.

The intended client experience resembles OCI's S3 Compatibility API: configure
an S3 endpoint, region, access key and secret key in an existing S3 application.
This gateway is a separately operated service, with its own credentials and
compatibility limits. It is not Oracle's managed S3 endpoint and is not a claim
of complete S3 or public OCI feature parity.

## Configuration

Use [s3proxy.properties](s3proxy.properties). The native endpoint, including any
path such as `/oci`, is explicit. No endpoint is inferred from a public region.
HTTPS and normal certificate/hostname verification are required for backend
connections created from properties. Configure the appliance's trusted CA in
the JVM truststore, for example with `JAVA_TOOL_OPTIONS` containing
`-Djavax.net.ssl.trustStore=/run/secrets/pca-truststore.p12` and the corresponding
truststore options. Do not disable TLS verification. Native endpoint paths,
API availability and SDK behavior need qualification on the installed release.

Mount an external OCI configuration file and signing key:

```ini
[PCA]
user=ocid1.user.REPLACE
tenancy=ocid1.tenancy.REPLACE
fingerprint=REPLACE_WITH_API_KEY_FINGERPRINT
key_file=/run/secrets/oci/api-key.pem
region=REPLACE_WITH_SITE_REGION
```

## Per-user authentication and native authorization

Create **one protected properties file per gateway S3 access key**. Its
`s3proxy.oci.user-id` must exactly match `user=` in its external OCI profile;
a mismatch fails startup. Each file has its own `s3proxy.identity` and
`s3proxy.credential`. These are gateway keys, not Oracle Customer Secret Keys.
Native signing credentials cannot be derived from an S3 secret or a username.
The gateway must be provisioned with the corresponding user's native API
signing credential. A shared administrator profile does not impersonate users.

For every supported storage operation:

1. The locator reads the key's current protected file and rejects unknown,
   disabled, missing, malformed or remapped entries. There is no fallback user.
2. S3Proxy verifies the SigV4 signature with that gateway key's current secret.
   An invalid signature never reaches native Object Storage.
3. The user's own backend signs the native request as that PCA user. PCA's
   native IAM service evaluates that user's current native permissions for the
   actual operation/resource. The gateway maintains no copy of IAM policies or
   positive permission decisions.

The gateway stores these per-user signing credentials server-side. Keep the
populated properties, OCI profile and private key outside Git, use the approved
secret storage mechanism, mount secrets read-only where supported and restrict
filesystem access to the gateway operator. Select principals and native IAM
permissions appropriate to the users/workloads. Do not use an administrator as
a fallback when a user's native request is denied. Native user/key/policy
revocation remains subject to native service behavior and propagation, to be
qualified on the installed PCA release.

Give writers bucket-read permission as well as their approved object mutation
permissions: before publishing objects, copies or completed multipart uploads,
the adapter checks that the native bucket has `NoPublicAccess`. This adds a
native metadata round trip. Public native buckets are unsupported; native
administrators remain responsible for subsequent policy changes.
`s3proxy.oci.compartment` controls that user's bucket listing and creation;
native IAM controls access to buckets addressed by name. A configured
compartment alone never grants permission.

Build and start (Java 17+), using files such as `alice.properties` and
`bob.properties` copied from the template and bound to their respective profiles:

```sh
mvn verify
./target/s3proxy \
  --properties /run/secrets/s3proxy/alice.properties \
  --properties /run/secrets/s3proxy/bob.properties
```

For example, bind `alice.properties` to the Alice user OCID and `[ALICE]`
profile, and `bob.properties` to the Bob user OCID and `[BOB]` profile. Each user
needs native API signing credentials supported by the appliance; console
passwords, usernames and SAML sessions are not native delegation credentials.

The listener/TLS settings must agree in every file. Native user mode requires
`aws-v4`; anonymous access, mixed storage providers, bucket locators and
middleware-wrapped backends are rejected. This prevents alternate routing from
bypassing the native user binding. Use a separate process for other providers.
All identity/backend settings must be present in the protected files; JVM
property overrides must not be used to change these bindings.

To generate gateway credentials during protected provisioning, use a secure
random generator, for example Python's `secrets.token_hex(16)` for the access key
and `secrets.token_urlsafe(32)` for the secret. Write them directly to the user's
protected file and deliver the secret through the approved secret mechanism.
This change does not implement a web enrollment or credential delivery service.

To revoke a gateway key, atomically set `s3proxy.oci.enabled=false` in its file.
Deleting/unreading the file also denies subsequent credential lookups. To
rotate its secret, atomically replace `s3proxy.credential` in the same file;
the old secret and sessions derived from it stop working on subsequent
lookups. Requests already admitted can finish. New access key IDs, native user
bindings, endpoint/profile changes and native signing-key replacement require a
restart; an edited binding is denied until reloaded through startup validation.
Keep disabled files available if they remain in the startup argument list.

The sample listens on loopback HTTP for a local TLS reverse proxy. Expose a
private HTTPS endpoint to S3 clients, preserve their signed Host, path and query,
and disable request buffering if the reverse proxy would spool large objects.
You can instead configure S3Proxy's native HTTPS listener and keystore.
Frontend and backend CA chains are separate trust configurations.

## AWS CLI and Python clients

Store gateway credentials using an AWS profile named `pca-s3` (do not place
secret values in shell history). For clients on a private CA, configure
`AWS_CA_BUNDLE` to the CA of the **gateway's** HTTPS endpoint.

```sh
aws configure set s3.addressing_style path --profile pca-s3
aws --profile pca-s3 --region us-east-1 \
  --endpoint-url https://s3.pca.example s3api list-buckets
aws --profile pca-s3 --region us-east-1 \
  --endpoint-url https://s3.pca.example s3 cp ./sample.bin s3://approved-bucket/sample.bin
aws --profile pca-s3 --region us-east-1 \
  --endpoint-url https://s3.pca.example s3 cp s3://approved-bucket/sample.bin ./roundtrip.bin
```

`us-east-1` here is a frontend signing-region convention; the native endpoint
and OCI profile select the backend appliance. Path-style addressing avoids
requiring wildcard DNS and certificates for each bucket.

```python
import boto3
from botocore.config import Config

s3 = boto3.Session(profile_name="pca-s3").client(
    "s3",
    endpoint_url="https://s3.pca.example",
    region_name="us-east-1",
    config=Config(
        s3={"addressing_style": "path"},
        request_checksum_calculation="when_required",
        response_checksum_validation="when_required",
    ),
    # For a private CA: verify="/path/to/gateway-ca.pem"
)
s3.upload_file("sample.bin", "approved-bucket", "sample.bin")
s3.download_file("approved-bucket", "sample.bin", "roundtrip.bin")
```

## Compatibility boundary

Implemented native operations: list/create/head/delete buckets; list objects
with prefix/delimiter, continuation tokens and start-after; streaming
put/get/head/delete objects; content headers/user metadata; ETag conditions
where supported by native calls; byte ranges; native multipart initiate,
upload, list parts/uploads, commit and abort. Multi-delete uses the existing
S3Proxy per-object implementation. CopyObject streams via the gateway rather
than using asynchronous native CopyObject. Multipart IDs come from the native
service and do not require process-local upload state.

A native `NotAuthorizedOrNotFound` response is not evidence of successful
deletion. Concealed authorization failures remain errors; only an explicit
native `ObjectNotFound` delete result is treated as idempotent success.

Explicit limitations: version-specific access and S3 versioning configuration;
public ACLs/ACL changes; requested SSE/KMS/SSE-C; tags; non-standard storage
classes; object lock/retention; redirects/Expires on writes; date-based read
conditions and delete size/date conditions. Additional S3Proxy-wide limitations
in the root README still apply. Native encryption at rest remains the native
service's responsibility. The gateway does not assert that an unimplemented
S3 encryption option was honored. Native ETags are opaque and are not promised
to be object MD5 hashes. Streamed writes are not transparently retried by the
SDK; retry the operation at the S3 client, or retry an individual multipart part.

## Qualification and evidence

The PCA project notes reviewed on 2026-11-26 do **not** identify an accepted
installed software release. The First Access Offline Toolkit leaves the release
as a compatibility gate; the Firewall Requirements note says its 3.0.2 URLs need
installed-release confirmation. SDK 3.103.0 is an engineering dependency pin,
not certification against a particular appliance release.

Local tests cover native request mapping, streamed data, list continuation,
private-read boundaries, unsupported options, errors and multipart restart
behavior. A local transport test sends an AWS-signed S3 put/get through the real
S3Proxy frontend and OCI SDK to a synthetic native HTTP server, and verifies the
native RSA signature. The synthetic server is not an appliance emulator or
proof of PCA TLS/IAM/API compatibility.

Before site acceptance record the exact PCA release/build, native base URL,
namespace, compartment, CA and scoped principal. Test certificate failure,
unauthorized access, signed put/get/head/list/delete, prefix/delimiter and
pagination, conditional writes, ranges, metadata, Unicode keys, multipart retry,
abort and proxy restart, integrity hashes and each selected application.
Verify native and S3 reads see the same bytes. Retain evidence on the appliance;
never export real credentials or customer object contents into this repository.

## References

- [OCI S3 Compatibility API](https://docs.oracle.com/en-us/iaas/Content/Object/Tasks/s3compatibleapi.htm)
- [PCA Object Storage](https://docs.oracle.com/en/engineered-systems/private-cloud-appliance/3.0-latest/user/user-usr-objectstore.html)
- [OCI Java SDK](https://docs.oracle.com/en-us/iaas/Content/API/SDKDocs/javasdk.htm)

Recorded local validation on 2026-11-26 after the per-user changes:
`mvn verify` succeeded with 1,514 tests across the JUnit providers (4 + 1,510),
zero failures/errors and 32 skips; Checkstyle reported zero violations and
Modernizer passed. The packaged dependency JAR also started on Java 17.0.11
with a generated test-only OCI profile and returned 403 for an unsigned S3
request, without contacting native Object Storage.

Per-user authorization was added after the initial native adapter. Legacy
native configurations must add the explicit user binding and use one file per
gateway key. Qualification must additionally prove two users with different
native IAM policies, cross-bucket denial, invalid signatures, native user/API
key and policy revocation, gateway disable/rotation and cross-user multipart
permissions. Native authorization tests use a synthetic service which verifies
the actual per-user RSA signatures; they are not live PCA IAM certification.

## Real OCI acceptance before PCA deployment

Use the [live acceptance workflow](live/README.md) and its dated
[validation report](live/validation-2026-11-26.md) / [sanitized evidence](live/evidence-2026-11-26.json).
It provisions two real OCI users with different native IAM permissions and
tests allowed/denied data operations, gateway key lifecycle and native signing
key revocation/rotation. The report states the exact artifact and client profile,
propagation observations, cleanup outcome and remaining PCA qualification gates.

For urgent cutoff **at this gateway**, disable the mapped gateway keys first.
Native IAM/key changes can take time to propagate; disabling a gateway key does
not revoke a caller's separate direct native API credentials. Native signing-key
rotation requires the replacement to authenticate successfully before the old
key is revoked, then an updated profile and gateway restart. The live test
verifies this recovery sequence.
