# PCA native S3 gateway for OKE

This chart references an existing gateway image and three externally managed
Secrets; it never embeds credential data in Helm values/history. Set
`image.repository` and preferably the destination registry's `image.digest`.
Default Service is ClusterIP; `service.type=LoadBalancer` requests an internal
classic OKE TCP load balancer. The pod runs as UID/GID10001 with a read-only
root filesystem and whole-directory Secret mounts.

Use the offline handoff's top-level README for image import, file layout,
Secret provisioning, TLS, IAM acceptance, projection-aware rotation and
revocation-aware rollback. Source instructions:
https://github.com/orbenda-oracle/s3proxy/blob/codex/pca-native-object-storage/deploy/oke/README.md

TCP probes do not certify native IAM/TLS. No public endpoint, cluster RBAC,
credential provisioning, OCI/PCA changes or native storage provisioning is
performed by this chart. Linux/amd64 node pool and actual onsite acceptance are
required. No chart dependencies are fetched during installation.
