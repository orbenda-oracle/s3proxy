# PCA S3 gateway offline deployment package

Deliver the already implemented and OCI-tested native gateway as an immutable,
checksummed Linux x86-64 handoff. Do not deploy, modify PCA IAM, or embed site
secrets. Current product commit is 875243f0; acceptance source is a29a2aee.

Implementation choices: bundle a checksum-verified Temurin 17 Linux JRE and the
existing tested dependency JAR; use built-in HTTPS on 8443 and a dedicated
systemd account; separate frontend keystore from backend CA truststore. Keep
site configuration outside versioned binaries for rollback. Include pure Python
validation tooling and an offline wheel closure requiring onsite Python 3.11+.
This packages existing configuration/test behavior, not a new storage backend.

- [x] Add runtime/credential templates and service launcher.
- [x] Document installation, native users/IAM, TLS, lifecycle, acceptance and rollback.
- [x] Add offline acceptance client and package builder with exact source/digests/licenses.
- [x] Build archive, verify checksum closure, exercise TLS and key lifecycle locally,
      test wheel installation with no index, and rehearse Linux if available.
- [x] Independent read-only review, resolve material findings, publish source and
      local delivery artifact with precise validation limits.

Rulings: no installed PCA build has been established. Target Linux x86-64 is a
packaging assumption to confirm onsite. No public OCI result certifies PCA.
Execution proceeds under the user's explicit request to prepare this package.

Progress: six new safety checks and nine existing checks pass. Built-in TLS
launcher check and offline wheel install/pip check pass. Review findings on
runbook preconditions and list/delete effects fixed. No Linux engine available;
actual Linux and PCA replay remain explicit onsite gates.

Completion: r1 archive built from b5d8060f; 473 extracted file checksums and
source identity verified, local links resolve. External preparation record
retains the Linux/PCA NOT_RUN/PENDING gates. Seven package tests plus nine
existing safety tests passed. No PCA deployment or IAM changes performed.
