# PCA Native Object Storage Implementation Plan

> **For agentic workers:** Implement inline with test-driven development. User explicitly authorized proceeding with the native backend on 2026-11-26.

**Goal:** Let S3 clients use PCA native Object Storage through S3Proxy.

**Architecture:** Register `oci-native` with a PCA alias, implement the BlobStore SPI with OCI's Java ObjectStorage SDK and keep S3 frontend identities independent of the backend API-key principal. Require an explicit native HTTPS endpoint, namespace and compartment; read the backend signing key from an external OCI config profile. Native upload IDs and list pagination must survive proxy restarts.

**Tech Stack:** Java 17+, Maven, OCI Java SDK 3.103.0, existing AWS SDK S3 frontend, JUnit.

**Spec:** This document records the authorized implementation scope; it is not evidence of appliance qualification.

## Global Constraints

- GitHub owner: orbenda-oracle; branch codex/pca-native-object-storage.
- Project docs do not establish the installed PCA release: PCA First Access Offline Toolkit and PCA Firewall Requirements - Roy L explicitly leave it open. A 3.0.2 documentation URL is not a detected version.
- No live appliance credentials or data are needed for local tests. Native endpoint path is operator supplied, including /oci when required by the appliance.
- No S3/PCA feature-parity claim. Versioning, public ACL changes, KMS/SSE-C, object lock and unsupported conditions must be refused, not silently dropped.
- All objects stay in native buckets; no filesystem cache or private in-memory multipart state.

## Review Focus

- Inclusive native continuation tokens versus exclusive S3 start-after; delimiter and zero-size pages.
- Raw object ETags, metadata, unusual Unicode/plus/slash keys and range streaming.
- Missing objects versus forbidden buckets, service failures and retry safety of input streams.
- Multipart continuation pages and ordered commit ETags, with no process-local upload state.
- Unsupported security/storage options, stream closure and custom endpoint path/signing behavior.

### Task 1: Configuration and core objects
- [x] Add failing tests for provider validation and native request/response translation.
- [x] Add OCI SDK dependency, register provider, implement scoped bucket and streaming object operations.
- [x] Verify request conditions, metadata, ranges, error mapping and stream lifecycle.

### Task 2: Multipart and compatibility
- [x] Add failing tests for native upload/part/commit/abort and paginated listings.
- [x] Implement native multipart, streamed copy and explicit capability refusals.
- [x] Add a real signed HTTP transport test with a synthetic local native endpoint.

### Task 3: Delivery
- [x] Document config, AWS CLI/boto3 use, credentials boundary, CA trust and onsite acceptance.
- [x] Run focused tests, full test suite, packaging and static checks; report exact limits.
- [x] Review final diff and publish a reviewable branch in orbenda-oracle/s3proxy.

## Verification — 2026-11-26

- `mvn verify`: BUILD SUCCESS; 1,499 tests, zero failures/errors, 32 skipped.
- Checkstyle: zero violations; Modernizer and Java 17 release compilation passed.
- Packaged dependency JAR started using Java 17.0.11 and a generated test-only OCI config/key with `pca-native`; unsigned frontend request returned 403. No native service requests were made by this startup smoke test.
- Real AWS S3 client -> S3Proxy -> OCI SDK -> local synthetic native API: conditional PUT/GET bytes and native RSA signatures passed; public-bucket write was rejected before mutation.
- Independent review findings addressed: frontend capability registration, native bucket privacy checks (including default writes), destination copy conditions, all unsupported encryption fields and copy-source 304 -> 412 mapping.
- Live appliance release, TLS/IAM/API behavior and selected application acceptance remain onsite qualification gates.

Ruling: public native buckets are unsupported. Publishing an object or completed multipart upload checks the actual native bucket privacy, requiring bucket-read permission; native administrators remain responsible for subsequent policy changes.
