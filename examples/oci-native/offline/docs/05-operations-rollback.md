# 5. Cutover, operations and rollback

## Cutover

Start with an isolated qualification listener and test-only IAM identities.
Complete and sign the onsite worksheet before admitting application traffic.
Provision production principals/policies separately, validate user-to-key
bindings and run approved allowed/denied smoke tests with synthetic production
validation objects. Verify certificate expiry, disk/memory budget, DNS/NTP,
logging retention, restart ownership and recovery contacts.

After acceptance, use the site's load balancer/DNS/firewall change process to
admit only approved clients to the private HTTPS endpoint. Record previous
routing and TTLs. Preserve signed Host/path/query if routing through a proxy.
This package itself does not change DNS, firewalls or load balancers.

```sh
(
set -euo pipefail
systemctl enable pca-s3proxy
systemctl status pca-s3proxy --no-pager
journalctl -u pca-s3proxy --since '10 minutes ago' --no-pager
)
```

Use a signed, authorized synthetic HEAD/GET for native readiness. An unsigned
403 is a frontend auth check only. Monitor process restarts, errors, latency,
certificate expiry and VM memory/CPU. Start with the supplied 1 GiB maximum heap
and qualify concurrency/large-object behavior before choosing production sizing.
Restrict raw logs; request data may contain site resource identifiers. Do not
enable credential/header/body debug logging or export raw journals offsite.

## Routine gateway key lifecycle

`bin/gateway-key.py` preserves ownership/mode and atomically replaces properties.
Run it as the authorized file owner/root. It does not edit native identity,
endpoint or profile values. Client credential outputs are new 0600 files.

- **Urgent gateway cutoff:** `disable --properties /etc/pca-s3proxy/users/USER.properties`.
  It denies subsequent credential lookups without restart. Already-admitted
  requests may finish. Disable every gateway access key bound to the affected
  native user. For an incident, block new traffic/stop the service too when
  required by the incident owner; a stop can interrupt in-flight writes.
- **Gateway secret rotation:** disable, rotate to a new protected client file,
  deliver it, enable, verify old secret denied/new secret allowed. Rotation
  preserves the enabled flag; rotating a disabled key does not enable it.
- **New access key ID/user binding:** provision a new file, validate user/profile
  equality, restart, then run allowed/denied checks. Do not repoint an existing
  access key to a different user. Retain disabled files referenced by startup.
- **Native signing key:** register/positively validate replacement, delete old
  key through PCA IAM, observe denial, install replacement key/fingerprint in
  the same user's profile and restart. Native key/policy propagation can take
  minutes. Gateway disable is independent of direct-native credential revocation.

See the validation guide for exact lifecycle commands and evidence phases.

## Before every upgrade

1. Record `readlink -f /opt/pca-s3proxy/current`, unit checksum, package checksum,
   current effective native IAM policy, TLS state and the change owner.
2. Keep the previous verified binary release. Back up `/etc/pca-s3proxy` and
   any unit overrides **inside the approved encrypted/protected onsite secret
   backup system**. This backup contains private keys and passwords. Never add
   it to the delivery package, Git or an unprotected archive.
3. Record key lifecycle changes separately from configuration backups: disabled
   gateway IDs, current secrets/native key fingerprints, revoked/compromised
   material and policy withdrawals. A backup is not authority to restore access.
4. Confirm the prior release supports explicit per-user native authorization
   and the current configuration format. Upstream S3Proxy or a shared-admin
   configuration is not an acceptable security rollback target. If no qualified
   prior version exists (including first installation), the rollback is a
   stopped gateway and withdrawn route, not a fallback administrator gateway.
5. Quiesce clients through the site routing process, allow admitted requests to
   drain according to application limits, and record active multipart IDs.
   Multipart state is native; a process rollback does not erase it. Abort only
   test/approved abandoned uploads using the authorized native/S3 principal.

## Binary rollback

Triggers include failed TLS, an unexpected permission grant, corrupted data,
unsupported native APIs or unacceptable availability. Restrict/withdraw client
traffic first. For an authorization incident, disable affected gateway keys
before further investigation. Preserve restricted evidence onsite.

As root, substitute the recorded **qualified prior release**:

```sh
(
set -euo pipefail
systemctl stop pca-s3proxy
previous=/opt/pca-s3proxy/releases/RECORDED_QUALIFIED_RELEASE
test -x "$previous/runtime/jre/bin/java"
test -f "$previous/lib/s3proxy.jar"
(cd "$previous" && sha256sum -c SHA256SUMS)
ln -s "$previous" /opt/pca-s3proxy/current.rollback
mv -Tf /opt/pca-s3proxy/current.rollback /opt/pca-s3proxy/current
systemctl start pca-s3proxy
systemctl status pca-s3proxy --no-pager
)
```

Leave `/etc/pca-s3proxy` at its current secure key/policy/TLS state when formats
are compatible. **Never restore a revoked secret, compromised certificate,
disabled gateway key, deleted native key or withdrawn permission from backup.**
If configuration restoration is required, stage it separately, reconcile all
revocations/rotations with the change owner, validate explicit native user
bindings, then install atomically with correct permissions before startup.
Do not re-add a deleted native public key merely to make an old profile work.

Repeat TLS checks, unsigned 403, authorized integrity read, reader mutation
denial, protected-object denial and revoked-key denial. Restore client routing
only after the acceptance owner approves the rollback result. Keep the failed
release and sanitized incident summary for investigation.

## First-install rollback / uninstall

With no previously qualified gateway, withdraw the route and:

```sh
(
set -euo pipefail
systemctl disable --now pca-s3proxy
)
```

Leave native buckets/objects intact. The administrator revokes only credentials
and scoped policies created for this gateway according to the approved change
record; inventory other consumers before deleting shared IAM resources. Remove
native private-key/client-secret copies through the site's secret-destruction
process after recovery evidence is secured. Remove the service unit/versioned
binaries only when no rollback investigation needs them. Do not delete buckets
or production objects as part of gateway uninstall.

Binary/configuration rollback does not undo object PUT/copy/delete operations.
Use the native storage recovery/retention/backup process for data recovery.
Do not promise S3 version recovery: this backend does not implement
version-specific object access. Document any interrupted/ambiguous writes and
verify resulting native object bytes before retries.
