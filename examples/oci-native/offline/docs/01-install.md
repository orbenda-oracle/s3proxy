# 1. Site worksheet and installation

Record these values in a protected onsite change record before starting:

| Item | Site value / evidence |
| --- | --- |
| Release name, archive SHA-256, source commit | From manifest / trusted sender |
| Installed PCA release and build | Actual appliance, not a documentation URL |
| VM OS, architecture, libraries, Java output | OL8/9 x86-64; qualify actual host |
| Native Object Storage endpoint/base path | Confirm `/oci` applicability; HTTPS |
| Namespace, tenancy, compartment, region | Actual native API values |
| Gateway FQDN/IP and port | Private HTTPS 8443; client SAN matches FQDN |
| Frontend certificate issuer/expiry/SAN | Approved site CA |
| Backend CA issuer/expiry/SAN | PCA native endpoint chain |
| Reader/writer native user and group identifiers | Distinct users and API keys |
| Shared/protected qualification buckets | Private `NoPublicAccess`; synthetic data |
| Change owner, IAM/TLS owners, acceptance owner | Named onsite operators |
| Previous release/config backup, rollback owner | Protected onsite location |

Stop if the installed release does not support the required native API, signing
credentials or policy conditions. Do not substitute an S3 compatibility
endpoint or a shared administrator credential to make tests pass.

## Verify receipt

Run each block in Bash; its subshell stops on any failed command.
On a Linux administrator host, in the directory containing both transferred
files (substitute the release filename):

```sh
(
set -euo pipefail
sha256sum -c pca-s3proxy-RELEASE-linux-x86_64.tar.gz.sha256
tar -xzf pca-s3proxy-RELEASE-linux-x86_64.tar.gz
cd pca-s3proxy-RELEASE-linux-x86_64
sha256sum -c SHA256SUMS
cat manifest.json
file runtime/jre/bin/java
ldd runtime/jre/bin/java
runtime/jre/bin/java -version
)
```

Every checksum must pass, `ldd` must show no missing libraries, and Java must
start on the actual host. The bundled runtime was downloaded from Adoptium and
checked against the pinned publisher SHA-256; its original metadata is under
`runtime/`. It targets Linux/glibc, not Alpine/musl, ARM or Windows.

## Install without starting

Run the following as root from the verified extracted release root. Set the
release path to the exact manifest name:

```sh
(
set -euo pipefail
release=$(basename "$PWD")
getent group pca-s3proxy >/dev/null || groupadd --system pca-s3proxy
id pca-s3proxy >/dev/null 2>&1 || useradd --system --gid pca-s3proxy \
  --home-dir /nonexistent --shell /sbin/nologin pca-s3proxy
install -d -m 0755 /opt/pca-s3proxy/releases
# Must be a NEW release directory. Do not merge or overwrite an old release.
test ! -e "/opt/pca-s3proxy/releases/$release"
cp -a "$PWD" "/opt/pca-s3proxy/releases/$release"
chown -R root:root "/opt/pca-s3proxy/releases/$release"
chmod -R go-w "/opt/pca-s3proxy/releases/$release"
install -d -o root -g pca-s3proxy -m 0750 /etc/pca-s3proxy \
  /etc/pca-s3proxy/users /etc/pca-s3proxy/native /etc/pca-s3proxy/tls
install -o root -g root -m 0644 config/pca-s3proxy.service \
  /etc/systemd/system/pca-s3proxy.service
systemctl daemon-reload
)
```

These are administrator-executed instructions; they do not run automatically.
Do not add this account to sudo or container groups. The service uses journald,
read-only system paths, no writable application state and an unprivileged port.
Use a separate instance and configuration if you need a canary. Only trusted
operators may edit `/etc/pca-s3proxy`; files are root-owned and group-readable
by the service where needed. Never make native keys or properties world-readable.

Complete users/IAM and TLS before pointing `current` to the release:

```sh
(
set -euo pipefail
# Set the exact manifest release again (the installation subshell is isolated).
release=EXACT_MANIFEST_RELEASE_NAME
test -f "/opt/pca-s3proxy/releases/$release/manifest.json"
# Record the old target first when upgrading.
readlink -f /opt/pca-s3proxy/current || true
ln -s "/opt/pca-s3proxy/releases/$release" /opt/pca-s3proxy/current.next
mv -Tf /opt/pca-s3proxy/current.next /opt/pca-s3proxy/current
)
```

Keep client traffic blocked except the qualification host. Allow only approved
client subnets to gateway 8443; gateway egress to native PCA HTTPS, internal DNS
and NTP as required. No public DNS or package repository is a runtime dependency.
Use the site's firewall process; this package does not change firewall rules.
Confirm clock synchronization before signature tests. Test SELinux under the
site policy; do not disable it to work around a denial.

## Prepare validation dependencies offline

On an administrator/validation host with Python 3.11+ and venv/pip already staged:

```sh
(
set -euo pipefail
python3.11 -m venv /var/tmp/pca-s3-validation
/var/tmp/pca-s3-validation/bin/python -m pip install --no-index \
  --find-links "$PWD/wheels" -r requirements.txt
/var/tmp/pca-s3-validation/bin/python -m pip check
)
```

This installs the complete pinned wheel closure without querying an index.
It does not need native OCI credentials. Keep its AWS gateway credential files
in a protected administrator workspace, separate from the gateway filesystem.
