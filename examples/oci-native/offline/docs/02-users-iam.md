# 2. Per-user credentials and native IAM

There are two independent key layers:

| Layer | Credential | Issuer / purpose |
| --- | --- | --- |
| S3 client -> gateway | Gateway random access key + secret | Gateway operator; verifies SigV4 |
| Gateway -> native PCA | Each user's RSA API signing key/profile | PCA IAM; authorizes native operations |

Gateway keys are not Oracle Customer Secret Keys. The gateway cannot convert an
S3 secret, console password or SAML session into a native signing credential.
Do not use an administrator profile to represent multiple users.

## Native users and qualification policy

1. The PCA IAM administrator creates two distinct qualification users and groups
   (`PCA-S3-Readers`, `PCA-S3-Writers`) using the installed release's Compute Web
   UI or approved native CLI. Add each user only to its intended group. Check
   all inherited/group policies; OCI/PCA grants are additive, so a broad existing
   grant would invalidate the negative tests.
2. Generate a separate RSA signing key per user onsite, using the site's key
   mechanism. For an approved file-based key, OpenSSL commands below create
   protected unencrypted PEM material; filesystem/secret-store protection is
   required. Upload only the public key to the matching PCA user. Record its
   fingerprint from PCA; never put a private key in a policy or client profile.

   ```sh
   umask 077
   openssl genrsa -out reader.pem 3072
   openssl rsa -in reader.pem -pubout -out reader-public.pem
   openssl genrsa -out writer.pem 3072
   openssl rsa -in writer.pem -pubout -out writer-public.pem
   ```

3. Create two private qualification buckets with an administrator. Require
   `NoPublicAccess`. Use `config/iam-policy.template`, substituting actual local
   names and confirming syntax/conditions against the installed PCA release.
   The reader may read/list shared objects; the writer may manage shared
   objects; both may read bucket metadata; neither may access protected objects.
   These groups do not need bucket creation/deletion permission. For production,
   create separate workload-scoped policies rather than reusing test identities.
4. With the administrator's native API client, seed a known synthetic protected
   object and read it back. Record its key, exact bytes/SHA-256, request ID and
   native bucket in the protected site evidence. The two gateway users must not
   have that administrator grant. Confirm native reader GET allowed / PUT
   denied, writer PUT/GET allowed, both protected GET denied. A 404 may conceal
   authorization: only the prior successful native read establishes existence.
5. Fill `config/native-config.template` into a protected working file. Distinct
   `[READER]` / `[WRITER]` `user=` values must match the actual keys. Region,
   tenancy, endpoint, namespace and compartment must come from the appliance.
   Install `config`, `reader.pem`, `writer.pem` as `root:pca-s3proxy`, mode 0640,
   under `/etc/pca-s3proxy/native/`. Validate directory mode 0750. Do not copy
   administrator native credentials to the gateway.

The adapter requires bucket-read permission before object publication because
it checks `NoPublicAccess`; the configured compartment is not an authorization
boundary for a named bucket. Native IAM decides access to each actual resource.

## Generate each gateway user's S3 key

Complete TLS first, then copy `config/user.properties.template` to a protected
working `reader.template`. Replace every site value, including frontend FQDN,
keystore password, native endpoint/base path, namespace, compartment and profile.
Set `s3proxy.oci.user-id` equal to `[READER] user=`; leave only the two gateway
key placeholders. Repeat for the writer with `[WRITER]` and its own user OCID.
The listener, allowed-hosts and frontend keystore values must match in all files.

In the protected staging directory, run (using the package's absolute bin path):

```sh
umask 077
python3.11 /opt/pca-s3proxy/current/bin/gateway-key.py create \
  --template reader.template --properties reader.properties \
  --credentials reader.aws --profile pca-reader
python3.11 /opt/pca-s3proxy/current/bin/gateway-key.py create \
  --template writer.template --properties writer.properties \
  --credentials writer.aws --profile pca-writer
install -o root -g pca-s3proxy -m 0640 reader.properties \
  /etc/pca-s3proxy/users/reader.properties
install -o root -g pca-s3proxy -m 0640 writer.properties \
  /etc/pca-s3proxy/users/writer.properties
```

If `current` is not assigned yet, use the exact versioned release path. The
helper refuses to overwrite an existing client credentials file and never
prints secrets. Deliver each `.aws` file only to its intended client using the
approved secret channel; these files contain no native signing credentials.
For the qualification host, merge their distinct profile sections into a new
0600 AWS credentials file in the protected workspace. Set
`AWS_SHARED_CREDENTIALS_FILE` to that file; do not overwrite an operator's usual
AWS configuration. The service itself does not read client `.aws` files.

Delete unnecessary staging copies through the site's secret-handling process.
Adding an access key ID or changing a user/endpoint/profile binding requires a
restart. An edited binding fails closed until startup validation reloads it.
See the operations guide for hot gateway disable/secret rotation and native-key
rotation. Do not edit native identity mappings as a way to transfer ownership.

References: [PCA native credentials](https://docs.oracle.com/en/engineered-systems/private-cloud-appliance/3.0-latest/concept/concept-iam-user-creds.html),
[PCA policies](https://docs.oracle.com/en/engineered-systems/private-cloud-appliance/3.0-latest/concept/concept-iam-policies.html).
These references do not establish the installed release.
