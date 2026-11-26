# 4. Onsite validation and evidence

Run against **qualification buckets with synthetic data only**, from the
approved client host. Keep application traffic blocked until signoff. This is
actual site acceptance; the included public OCI report is reference evidence.

Record exact package/source digests, PCA build, OS/JRE, endpoint/base path,
namespace/compartment, user/key aliases, applicable IAM policies and certificate
fingerprints in the protected site worksheet. Do not export site credentials,
populated profiles, native IDs, raw service logs or customer data. Keep detailed
evidence onsite; sanitize any separately approved external summary.

## Native and TLS preconditions

- Receipt checks and Java loader succeed, service is HTTPS-only, both frontend
  and native TLS chains/hostnames validate, clock/DNS/firewall are correct.
- Reader/writer are distinct native users with different API signing keys. Each
  properties user ID exactly matches its native profile. Startup rejects a
  deliberately mismatched copy in a separate stopped/canary configuration.
- Native IAM administrator confirms the effective policy matrix, including
  inherited policies. Direct native tests prove reader GET allowed / write
  denied, writer write/read allowed and protected-object denials.
- Administrator successfully reads the known protected object and records its
  exact bytes/hash/request ID before the users' denied reads. A denied 404
  without this control is inconclusive. Buckets are `NoPublicAccess`.
- Administrative native API tooling is supplied by the site. The bundled
  validator deliberately cannot administer IAM, create buckets or revoke keys.

## Automated S3 phases

In a protected workspace, combine `reader.aws` and `writer.aws` under their
separate profile names into a new credentials file, mode 0600. Set only its
path in the environment; never place secret values in command arguments.
Run the following in Bash, substituting onsite non-secret values:

```sh
export AWS_SHARED_CREDENTIALS_FILE=/PROTECTED/qualification.aws
validator=/opt/pca-s3proxy/current/bin/validate.py
python=/var/tmp/pca-s3-validation/bin/python
run_id=onsite-YYYYMMDD-UNIQUE
common=(--endpoint https://GATEWAY_FQDN:8443 --ca /PROTECTED/gateway-ca-chain.pem \
  --shared-bucket s3-qualification-shared --run-id "$run_id")
"$python" "$validator" baseline "${common[@]}" \
  --protected-bucket s3-qualification-protected --protected-key KNOWN_EXISTING_KEY \
  --report "$run_id-baseline.json"
```

A new `onsite-...` ID is required per baseline: conditional create refuses to
overwrite an existing seed. Reports refuse overwrite, so every attempt gets a
new filename. The runner exits nonzero on unexpected allow, unexpected denial,
transport/TLS failure or payload mismatch. Inspect its JSON `verdict` and all
cases; do not treat an empty/incomplete report as PASS. A successful phase is
only one part of final acceptance.

Baseline exercises reader read/head/list and denied write/delete/multipart,
both protected-object denials, writer conditional put/conflict/copy/delete,
Unicode/plus keys, ranges, native multipart parts/list/complete/abort and exact
round-trip bytes. Seed/multipart test objects remain for lifecycle checks.

### Gateway disable and secret rotation

1. Save the reader's original gateway credentials under profile
   `pca-reader-old` in the protected client credentials file. Keep
   `pca-reader` / `pca-writer` for current credentials.
2. On the gateway as root, disable the mapped reader key:

   ```sh
   python3.11 /opt/pca-s3proxy/current/bin/gateway-key.py disable \
     --properties /etc/pca-s3proxy/users/reader.properties
   ```

3. From the client, run:

   ```sh
   "$python" "$validator" gateway-disabled "${common[@]}" --report "$run_id-disabled.json"
   ```

   Reader must be denied and writer must still read the seed. No restart.
4. On the gateway, rotate while disabled, writing a **new protected** client
   credential file, then deliver that file securely and update only the current
   `pca-reader` profile on the client (retain the old profile for the test):

   ```sh
   python3.11 /opt/pca-s3proxy/current/bin/gateway-key.py rotate \
     --properties /etc/pca-s3proxy/users/reader.properties \
     --credentials /PROTECTED/reader-new.aws --profile pca-reader
   python3.11 /opt/pca-s3proxy/current/bin/gateway-key.py enable \
     --properties /etc/pca-s3proxy/users/reader.properties
   ```

5. From the client, run:

   ```sh
   "$python" "$validator" gateway-rotated "${common[@]}" --report "$run_id-rotated.json"
   ```

   Old secret must fail, replacement must read exact bytes, without restart.
   Also test a new random unknown access key and wrong secret via the selected
   application: both must be denied. Record request IDs/statuses, not secrets.

### Native signing-key revocation and rotation

Only the PCA IAM administrator changes native keys. Use the qualification
writer and synthetic data; native revocation is stateful and cannot be undone
by restoring a configuration backup.

1. Generate/register writer key B, retain key A temporarily, and independently
   verify key B can perform an approved native request on the shared fixture.
   Account for the appliance's key-count limit and propagation. A registration
   response alone is insufficient. Leave the running gateway on key A.
2. Delete key A in native IAM; record acknowledgement/request ID and time. With
   the gateway still running on A, execute the following with a new report name
   per attempt until native denial propagates or the agreed window expires:

   ```sh
   "$python" "$validator" native-revoked "${common[@]}" --report "$run_id-native-revoked-ATTEMPT.json"
   ```

   Writer must be denied while reader remains allowed. Record first observed
   denial time and all failed attempts. Also verify key A is denied through the
   site's direct native client. A timeout is failed acceptance, not permission
   to deploy. Public OCI observed 561.929 seconds for the running gateway; this
   is not a PCA propagation SLA. For urgent cutoff, disable the gateway key
   immediately; this does not revoke other direct-native clients.
3. Install key B and its fingerprint in the protected writer native profile;
   keep the same native user ID and gateway S3 key. Restart the service. Never
   remap a gateway key to another native user to avoid a denial.
4. Run:

   ```sh
   "$python" "$validator" native-recovered "${common[@]}" --report "$run_id-native-recovered.json"
   ```

   Replacement-key PUT/GET and exact integrity must pass. Original native key
   must remain absent/denied. Repeat a reader write denial after restart.

### Native policy withdrawal

Withdraw only the qualification writer's shared-object grant with the IAM
administrator; leave its valid key and reader grants unchanged. Repeat the
`native-revoked` phase with distinct report names, labelled as **policy
withdrawal** in the site record. Writer denial plus reader success proves the
running gateway uses native permissions. Restore the reviewed scoped grant,
wait for readiness and run `native-recovered` again. Record observed delays.
Do not broaden a production policy to recover a failed acceptance test.

## Restart, selected application and cleanup

Qualify service restart during a staged multipart upload: identify its upload
ID, restart, list/complete or abort it as the authorized writer, and verify
reader abort remains denied. Test application-specific retry/large-object and
checksum modes; this package's boto3 profile uses `when_required` optional
checksums. Do not assume every S3 feature/client mode is supported.

After all controls are enabled and key B is active:

```sh
"$python" "$validator" cleanup "${common[@]}" --report "$run_id-cleanup.json"
```

Cleanup aborts multipart uploads only under `acceptance/<run-id>/` and deletes
only the four known synthetic keys. It never deletes buckets or IAM resources.
The IAM administrator removes the protected fixture/test buckets, qualification
policies, users/groups/keys after data cleanup, using the site change process.
Record actual deletion state; asynchronous deletion is not completion.

Final acceptance requires receipt/runtime/TLS checks, distinct native users,
positive fixture controls, every automated phase, native policy withdrawal,
direct native revoked-key denial, multipart restart/retry, selected application
checks, cleanup and a rehearsed rollback. Record PASS/FAIL and owner for each.
No missing, skipped or failed mandatory check may be signed off as deployment
ready. Production keys/policies are separate and receive their own smoke tests.
