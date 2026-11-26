# PCA native Object Storage S3 gateway — offline handoff

**Start here. This is a deployment candidate; PCA qualification is pending.**
The installed PCA release has not been established. Public OCI acceptance passed
35 checks; that evidence does not certify PCA. No appliance deployment is
performed by preparing or unpacking this package.

Target: a dedicated **Oracle Linux 8/9 x86-64 VM**, private network, systemd,
8443/TCP HTTPS. Confirm the actual OS/architecture onsite. The package includes
the tested dependency JAR, a checksum-verified Linux Temurin 17 JRE, Python
validation wheels, source, notices and configuration/runbook templates. Maven,
Internet access, Docker and a public OCI account are not needed to run it.

The site supplies Linux system libraries, Bash, coreutils/tar/gzip, OpenSSL,
systemd, Python **3.11+ with venv/pip** for validation, certificates, DNS/NTP,
PCA native users/keys/IAM and an approved administrative native API client or
Compute Web UI for native checks. Python and OS RPMs are not included; stage
these from the site's matching OL8/OL9 offline repositories before visiting.

```text
S3 clients -- HTTPS / gateway S3 key --> S3Proxy on VM:8443
         -- native HTTPS / mapped user's RSA signing key --> PCA Object Storage
```

Read and execute in order:

1. [Site worksheet and installation](docs/01-install.md).
2. [Per-user credentials and IAM](docs/02-users-iam.md).
3. [Frontend TLS and backend trust](docs/03-tls.md).
4. [Onsite acceptance and evidence](docs/04-validation.md).
5. [Cutover, operations and rollback](docs/05-operations-rollback.md).

`manifest.json` identifies source/product commits and artifact digests.
`SHA256SUMS` covers extracted regular files; the adjacent archive `.sha256`
verifies transfer integrity. Compare its value with the trusted sender's value
through an approved channel. Checksums are not a release signature or vendor
support statement. Keep the original package immutable; put site secrets only
under `/etc/pca-s3proxy` or the site's approved secret store.

The public OCI report/evidence are under `docs/public-oci-evidence/`. Their
`acceptance.py` provisions public OCI lab resources and **must not be used as
the PCA deployment validator**. Use this package's `bin/validate.py` instead;
it uses existing qualification buckets/users and never creates IAM resources.

License inventory: `source/LICENSE-s3proxy`,
`source/dependency-jars/` (original dependency JARs and their notices),
`source/dependency-inventory.json`, `source/jar-license-notices.zip`, `runtime/jre/legal/`, and license files inside
the original `wheels/*.whl` archives. Full pinned source is in
`source/s3proxy-source.tar`. Retain these files when redistributing. The source
archive plus bundled Java classes is not an offline Maven development cache.

The release preparation record describes checks run on the preparing host.
Linux loader/systemd/OS and real PCA TLS/API/IAM acceptance remain onsite gates.
