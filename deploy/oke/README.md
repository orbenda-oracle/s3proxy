# Docker image and Helm chart for OKE

This packages the unchanged per-user native gateway for **Linux/amd64** nodes.
The image serves HTTPS on 8443, runs as UID/GID 10001, has a read-only root
filesystem and uses a writable `/tmp` volume. Native buckets and multipart state
remain in Object Storage; no PVC, node credential or OCI instance principal is
used. Each S3 key is still bound to its own native user's signing profile.

The offline release contains `s3proxy-image.tar.gz` (Docker archive),
`pca-s3proxy-0.1.0.tgz` (dependency-free Helm chart), `values-oke.example.yaml`,
`image-metadata.json`, checksums, pinned source, notices and client validation
tools/wheels. No site credentials or production TLS certificates are included.
Import the image into your approved private registry (Harbor/OCIR as appropriate).
The package is a deployment candidate; actual OKE/PCA acceptance is still required.

## 1. Verify and import the offline image

On the connected transfer/build host, compare the handoff archive SHA-256 with
the trusted sender's value. Extract and verify the inner `SHA256SUMS` before use.
On an administrator host with Docker and access to the target internal registry:

```sh
set -euo pipefail
sha256sum -c SHA256SUMS
gunzip -c s3proxy-image.tar.gz | docker load
docker image inspect pca-s3proxy:20261126-oke-r1 --format '{{.Id}}'
# Compare the image ID with image-metadata.json before tagging.
docker tag pca-s3proxy:20261126-oke-r1 REGISTRY/PROJECT/pca-s3proxy:20261126-oke-r1
docker login REGISTRY
docker push REGISTRY/PROJECT/pca-s3proxy:20261126-oke-r1
# Record the immutable digest returned by the target registry.
```

No registry push is performed while preparing this package. Use the approved
registry's authentication/CA process; never disable TLS verification. A Docker
archive checksum, Docker image ID/config digest and registry manifest digest
are different identifiers. Pin **the destination registry manifest digest** in
Helm values after import. `image.digest` overrides `image.tag`. The offline tag
is a local import name; it is not an already-published Docker Hub repository.
OKE worker nodes must trust/reach the internal registry. Pre-stage Helm/kubectl
for the actual cluster version; they are not installed by the chart.

## 2. Native IAM, per-user files and TLS

Use the [credential/IAM guide](../../examples/oci-native/offline/docs/02-users-iam.md)
and [TLS guide](../../examples/oci-native/offline/docs/03-tls.md), supplied as
`docs/02-users-iam.md` and `docs/03-tls.md` in the offline bundle. Create separate
native reader/writer users, RSA API keys and least-privilege policies. Review
inherited permissions. Qualify private `NoPublicAccess` buckets and a known
protected-object fixture. Public OCI's 35 passing checks are reference evidence,
not certification of the site's installed PCA release.

Prepare files in a protected administrator workspace (mode 0700), not in a
Git checkout. Generate gateway keys there with `tools/gateway-key.py` from
completed templates. For container use, **remove systemd/filesystem install
steps**: mount the same absolute paths through the Secrets below. Keep HTTPS
listener `https://0.0.0.0:8443`, matching allowed gateway FQDN, native user/profile
identity and frontend keystore password in every per-user properties file.
`native/config` must reference keys at `/etc/pca-s3proxy/native/USER.pem`.

Required Secret file layout:

| Existing Secret | Data keys / mounted paths |
| --- | --- |
| `pca-s3proxy-users` | `reader.properties`, `writer.properties`, one per gateway access key -> `/etc/pca-s3proxy/users/` |
| `pca-s3proxy-native` | `config`, `reader.pem`, `writer.pem` -> `/etc/pca-s3proxy/native/` |
| `pca-s3proxy-tls` | `frontend.p12`, `backend-trust.p12` -> `/etc/pca-s3proxy/tls/` |

Frontend PKCS#12 contains gateway private key/full chain. Backend PKCS#12 holds
only approved PCA CA certificates, with public truststore password `changeit`.
Clients trust the gateway CA separately. Native TLS/hostname checks stay enabled.
Use a site-approved external secret controller if required; it must produce the
same files and have its own access controls. Never put plaintext or base64
credential data in `values.yaml`, `--set`, Helm templates or ConfigMaps.

With the intended kube context **explicitly selected**, create only a dedicated
application namespace and Secrets (substitute `OKE_CONTEXT` and protected paths):

```sh
set -euo pipefail
kubectl --context OKE_CONTEXT create namespace pca-s3proxy
kubectl --context OKE_CONTEXT -n pca-s3proxy create secret generic pca-s3proxy-users \
  --from-file=/PROTECTED/users/reader.properties \
  --from-file=/PROTECTED/users/writer.properties
kubectl --context OKE_CONTEXT -n pca-s3proxy create secret generic pca-s3proxy-native \
  --from-file=config=/PROTECTED/native/config \
  --from-file=/PROTECTED/native/reader.pem --from-file=/PROTECTED/native/writer.pem
kubectl --context OKE_CONTEXT -n pca-s3proxy create secret generic pca-s3proxy-tls \
  --from-file=/PROTECTED/tls/frontend.p12 --from-file=/PROTECTED/tls/backend-trust.p12
```

If the namespace already exists, inspect it rather than blindly replaying the
create block. Secrets are namespace-scoped; limit Secret read and pod/exec/create
permissions and enable the site's required encryption at rest. The pod gets no
API service-account token and needs no application Kubernetes RBAC. Use an
existing imagePullSecret when the registry requires it; credentials belong to
that Secret, never Helm values.

## 3. Install the chart

Copy `values-oke.example.yaml` to a protected site values file. Set the imported
registry repository/digest and Secret names. Values contain references only.
The chart defaults to ClusterIP, one replica, amd64 scheduling, Service port
443 -> pod HTTPS 8443, 512 MiB request / 2 GiB memory limit (JVM heap max 1 GiB).
Size and concurrency still require workload testing.

```sh
set -euo pipefail
helm lint ./pca-s3proxy-0.1.0.tgz --strict -f site-values.yaml
helm template gateway ./pca-s3proxy-0.1.0.tgz -n pca-s3proxy \
  -f site-values.yaml > rendered.yaml
# Review objects against the intended cluster/network policy before installation.
helm upgrade --install gateway ./pca-s3proxy-0.1.0.tgz \
  --kube-context OKE_CONTEXT --namespace pca-s3proxy \
  -f site-values.yaml --atomic --wait --timeout 10m
kubectl --context OKE_CONTEXT -n pca-s3proxy rollout status deployment/gateway-pca-s3proxy
```

`--atomic` can roll back chart resources after installation failure; it does
not undo externally managed Secret or IAM changes. Default ClusterIP is private
to the cluster. To expose through an OKE **internal** classic load balancer,
set `service.type: LoadBalancer`. The chart enforces:

```yaml
service:
  type: LoadBalancer
  annotations:
    service.beta.kubernetes.io/oci-load-balancer-internal: "true"
    oci.oraclecloud.com/load-balancer-type: "lb"
```

Service traffic is TCP passthrough; TLS terminates in the gateway. Qualify the
installed OCI/PCA cloud-controller annotations, private subnet/NSGs/security
rules, approved source CIDRs and DNS/SAN before enabling it. No ingress
controller, TLS-terminating proxy, public LB, wildcard DNS or cert-manager
installation is assumed. NLB-specific annotations/controllers require a separate
review. The chart deliberately rejects an explicit public-LB override.

If the cluster requires NetworkPolicy, enable the optional policy **with site-
specific rules** permitting approved ingress to pod8443, DNS and native-PCA
HTTPS egress. Empty enabled rules deny all. Enforcement depends on the CNI;
NetworkPolicy does not open cloud firewalls or grant native IAM. Include actual
LB/health-check source ranges required by your cluster. No sample rule assumes
public Internet access or universal PCA IP ranges.

## 4. Validate before admitting clients

TCP startup/readiness/liveness probes prove a listener only; they cannot check
certificate trust or native IAM. Helm `--wait` success is not storage acceptance.
Run the [onsite validation sequence](../../examples/oci-native/offline/docs/04-validation.md)
with `tools/validate.py`, the packaged wheel closure and existing qualification
users/buckets. Use the private gateway FQDN and trusted gateway CA, path-style
S3, signing region `us-east-1`, optional checksum mode `when_required`.

Require valid TLS, unsigned403, reader allowed reads/denied mutations, writer
allowed operations, both protected-object denials with a positive native fixture
control, object integrity, multipart, gateway disable/rotation, native policy/key
revocation and replacement-key recovery. Test restart and selected applications.
Record image ID/registry digest, chart version, Kubernetes/OKE/PCA builds and
all mandatory site results. Keep populated profiles, native IDs, logs and
credentials onsite; the public preparation record contains no site values.

For local operator access, use `kubectl port-forward` only on loopback and a
certificate-valid hostname/SNI. Example: forward Service443 to local8443 and
use `curl --resolve GATEWAY_FQDN:8443:127.0.0.1 --cacert gateway-ca.pem
https://GATEWAY_FQDN:8443/`. Boto3 must also resolve that same FQDN appropriately;
never bypass verification with an IP mismatch or `verify=False`.

## 5. Secret rotation and rollback

Secrets mount as complete directories, not `subPath`. Kubelet propagates Secret
updates **eventually**. Gateway secret/enable flags are reread after projection,
so changing the Kubernetes Secret is not an instantaneous revocation guarantee.
For urgent cutoff withdraw routing or scale the Deployment to zero, and revoke
native keys separately if direct-native access is also affected. Existing
requests can be interrupted by a forced stop. With multiple replicas, verify
every pod's projection/denials; a successful denial on one load-balanced request
is insufficient. Start with one replica; multi-replica behavior needs qualification.

Edit local protected source files with `tools/gateway-key.py`, then update the
**complete** users Secret with all currently configured per-user files. Do not
remove unrelated bindings or restore disabled/revoked credentials from old files.
No Kubernetes API edit happens by editing a local file. After updating the Secret,
wait for propagation and verify old/new-secret behavior on each pod. Native
profile/key/TLS changes, additions of key IDs or user-binding changes require a
controlled `kubectl rollout restart deployment/gateway-pca-s3proxy`; confirm
replacement native keys work before revoking old ones. Review effective IAM
and credential version consistency across all rolling pods before cutover.

Helm rollback changes chart/image configuration and leaves these external
Secrets alone:

```sh
set -euo pipefail
helm history gateway --kube-context OKE_CONTEXT -n pca-s3proxy
helm rollback gateway QUALIFIED_REVISION --kube-context OKE_CONTEXT \
  -n pca-s3proxy --wait --timeout 10m
```

Withdraw traffic first if authorization/TLS/data checks fail. Roll back only to
a qualified per-user native gateway image compatible with the current Secrets.
Never re-enable a revoked key or broaden IAM to make an old image work. Without
a qualified prior release, scale to zero/withdraw the route. Helm uninstall does
not delete external Secrets or native buckets; remove gateway-specific Secrets
only through the approved incident/uninstall process. Do not delete object data
or shared IAM policies as a deployment rollback. Repeat TLS and positive/negative
checks before restoring traffic. Binary rollback cannot undo object mutations.

## Build from source

The standard Dockerfile pins its official Temurin base digest and performs no package installs.
`prepare_context.py` verifies the exact previously OCI-tested JAR and copies
only that JAR, the launcher, Dockerfile and public input metadata into a NEW
build directory. Do not build from a populated site secret workspace.

```sh
set -euo pipefail
python3 deploy/oke/prepare_context.py --output /NEW/BUILD/CONTEXT
docker build --platform linux/amd64 \
  --build-arg SOURCE_REVISION="$(git rev-parse HEAD)" \
  --tag pca-s3proxy:20261126-oke-r1 /NEW/BUILD/CONTEXT
helm lint deploy/oke/chart --strict --set image.repository=registry.example/s3proxy
helm package deploy/oke/chart
```

No Arm image is included. On Arm OKE node pools the chart remains Pending until
an amd64 node is available; do not override scheduling to run the wrong platform.
Scan the image/dependencies using the site's normal vulnerability/license process
before registry import. Original dependency and base runtime notices are retained.

References: [OKE internal LB annotations](https://docs.oracle.com/en-us/iaas/Content/ContEng/Tasks/contengcreatingloadbalancer_topic-Summaryofannotations.htm),
[Kubernetes Secret projection](https://kubernetes.io/docs/concepts/configuration/secret/),
[official Temurin image](https://hub.docker.com/_/eclipse-temurin).


### Recorded offline-build fallback

The first candidate's connected Temurin-base build was interrupted because
registry access became unavailable. The delivered image uses
`container/Dockerfile.offline`: cached official Python Debian/glibc Linux/amd64
base `python@sha256:5e2bdb1b0d542d5039cffb8181c65930093949194a65c365dfb1b0fe13a4fa5b`
plus the same publisher-verified Temurin17.0.21.1 JRE as the offline VM package.
The Python runtime is retained in this base; gateway requests use Java only.
There is no borrowed application image or site environment in this base.
Both recipes expose the same filesystem paths, UID/GID and entrypoint.

For this exact recipe, use `prepare_context.py --offline-jre
/INPUTS/OpenJDK17U-jre_x64_linux_hotspot_17.0.21.1_2.tar.gz --output /NEW/CONTEXT`.
The helper checks the fixed publisher digest before extraction. The offline
base must already be loaded; its layers are included in the delivered image
archive. `image-metadata.json` records the actual recipe/base/JRE/source and
checks. Rebuilding the standard recipe produces a different image/digest and
requires its own runtime/acceptance checks.
