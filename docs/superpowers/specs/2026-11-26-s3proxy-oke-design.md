# S3Proxy container and OKE Helm delivery

Package the unchanged validated native gateway JAR for Linux/amd64 OKE nodes.
Use a pinned official Temurin17 JRE base and a non-root, read-only container.
Use the existing HTTPS launcher and protected per-user configuration layout.

The Helm chart manages Deployment/Service and optionally NetworkPolicy. It
references three pre-existing Secrets (per-user properties, native profiles and
keys, TLS stores), mounts whole directories without subPath, and never templates
secret contents. ClusterIP is default; an internal OKE TCP load balancer is an
explicit option. The chart needs no cluster RBAC, service account token or PVC.

TCP probes indicate listener readiness only. Signed positive/negative native
operation checks remain the acceptance gate. Keep replicas=1 initially; no
HA or application compatibility certification is inferred from a Helm render.
Secret projection is eventually consistent, so urgent cutoff may require
withdrawn routing/scale-to-zero. Native signing key/profile changes require a
rollout. Helm rollback must not restore revoked Secrets.

Deliver Docker image archive, packaged chart, immutable image metadata,
checksums, source/build recipe and install/rotation/rollback runbook. Do not
publish to a registry or change an OKE cluster without separate instructions.
Registry hostname, cluster release/networking and actual PCA qualification are
site inputs; image amd64 scheduling is explicit. Prior user instructions to
proceed cover packaging choices and local build/test work.
