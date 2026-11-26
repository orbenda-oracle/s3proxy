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

The OCI profile authenticates S3Proxy to native Object Storage. The separate
`s3proxy.identity`/`s3proxy.credential` authenticate S3 clients to the gateway.
You do not need `jclouds.identity` or `jclouds.credential`. Frontend S3 keys are
not Oracle Customer Secret Keys, and cannot be sent directly to native APIs.
Keep the populated properties, OCI profile and private key outside Git, mount
secrets read-only and restrict their filesystem permissions.

Use a dedicated backend principal restricted by native IAM to the intended
compartment/buckets. The principal needs bucket-read access: before publishing
objects, copies or completed multipart uploads, the backend reads the native
bucket and requires `NoPublicAccess`. Public native buckets are unsupported;
this check adds a native metadata round trip and does not prevent a native
administrator from subsequently changing the bucket policy. `s3proxy.oci.compartment` controls bucket listing and
creation; IAM controls access to buckets addressed by name. Every frontend
identity wired to this backend acts as that backend principal. This is not
per-user native IAM delegation. Use separate principals/backends and S3Proxy's
bucket/prefix routing if isolation is needed. Anonymous reads through this
backend and ACL changes are refused; native bucket exposure must also be
controlled through native IAM and bucket settings.

Build and start (Java 17+):

```sh
mvn verify
./target/s3proxy --properties /run/secrets/s3proxy.properties
```

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
    config=Config(s3={"addressing_style": "path"}),
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

Recorded local validation on 2026-11-26: `mvn verify` succeeded with 1,499 tests,
zero failures/errors and 32 skips; Checkstyle reported zero violations and
Modernizer passed. The packaged dependency JAR also started on Java 17.0.11
with a generated test-only OCI profile and returned 403 for an unsigned S3
request, without contacting native Object Storage.
