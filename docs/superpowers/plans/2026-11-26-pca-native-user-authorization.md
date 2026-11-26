# PCA Native Per-user Authorization Implementation Plan

User requirement: validate each S3 user and enforce their native permissions. User selected gateway-issued S3 keys mapped to individual PCA users on 2026-11-26.

Architecture: one protected properties file per S3 access key, explicitly bound to the native user ID of its external OCI signing profile. A native-only locator selects the corresponding user's backend; native Object Storage authenticates and authorizes real operations using that user's signing key. No administrator fallback and no local IAM policy replica. Re-read key admission/secret on each request for revocation and rotation; identity/backend changes require restart. Native policy or API-key revocation remains enforced by PCA on each native operation, subject to its normal propagation.

- [x] Test mandatory user binding, wrong profile rejection, unknown/disabled/malformed keys, per-key secrets, backend remapping refusal and mixed-provider/middleware rejection.
- [x] Implement expected native-user validation and the native-only per-user locator in the CLI.
- [x] Test real signed requests from a reader and writer against synthetic native authorization: correct native signer, read-only denial, bucket isolation, invalid signatures, immediate gateway revocation and native permission/key revocation. Verify bucket-location permission checks too.
- [x] Update multi-user setup, least-privilege prerequisites, key provisioning/revocation and native-auth limitations in the guide. Existing single-principal configs must migrate explicitly.
- [x] Independently review, run full Maven verification and Java 17 packaged startup smoke, then update the existing draft PR and project note.

Constraints: native CLI requires SigV4 and direct native backends. A process serving native users cannot mix unrelated providers or bucket-locator/middleware routing. Server-controlled native endpoint/profile/user mappings must not be influenced by client headers. Native user admission secrets and signing private keys stay external to Git. No actual PCA IAM state or credentials are queried or changed in local tests.

## Verification

- `mvn verify`: BUILD SUCCESS, 4 JUnit4 plus 1,510 platform tests, zero failures/errors, 32 skips; Checkstyle and Modernizer passed.
- Packaged JAR startup on Java 17.0.11 passed with explicit native user binding and external generated test-only profile/key; unsigned frontend HTTP returned 403 without native API requests.
- Independent read-only security review found no remaining P1/P2 identity mixups or fail-open paths.
- Native permission revocation, signing-key revocation, gateway disable/secret rotation, reader/writer separation, protected bucket denial, multipart mutation denial and invalid signatures passed against the synthetic native authorizer with actual per-user RSA verification.
- PCA release/build and actual native IAM/TLS/key lifecycle qualification remain onsite gates. No real account, credential or IAM policy was read or modified.

Ruling: only explicit native `ObjectNotFound` is an idempotent delete success. Concealed native authorization/not-found responses remain errors, preventing false success when a user lacks permission.
