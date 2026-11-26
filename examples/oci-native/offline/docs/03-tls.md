# 3. Frontend TLS and backend trust

The listener certificate and native PCA trust chain are separate:

- Frontend: clients verify `https://<gateway-fqdn>:8443` using the gateway CA.
  The certificate SAN must include that DNS name; provide its full intermediate
  chain. Use the private key only on the gateway. No HTTP listener is configured.
- Backend: Java verifies the native PCA endpoint's certificate/hostname using
  `/etc/pca-s3proxy/tls/backend-trust.p12`. Import the approved PCA CA chain;
  do not disable hostname or certificate validation.

Obtain the site-approved server certificate, key and CA chains through the
site's PKI process. This package does not issue production certificates. Verify
fingerprints through a trusted channel before import. Ensure internal DNS and
NTP are working and certificate/key ownership is assigned.

From a protected root workspace, build the frontend PKCS#12 store:

```sh
umask 077
openssl verify -CAfile gateway-root-ca.pem -untrusted gateway-intermediates.pem gateway.crt
openssl x509 -in gateway.crt -noout -subject -issuer -dates -ext subjectAltName
openssl pkcs12 -export -name gateway \
  -inkey gateway.key -in gateway.crt -certfile gateway-intermediates.pem \
  -out frontend.p12
```

OpenSSL prompts for the export password; use a long site-generated hexadecimal
password so Java properties escaping is unambiguous. Put that same password in
each protected user's `s3proxy.keystore-password` setting. Do not put it in
command arguments, shell history, this package or the change record. If the
certificate is directly root-signed, omit the intermediate-specific options.
Confirm the store contains the private key and full certificate chain:

```sh
/opt/pca-s3proxy/current/runtime/jre/bin/keytool -list -v \
  -keystore frontend.p12 -storetype PKCS12
```

Create a new backend truststore and import each independently verified CA with
a distinct alias (repeat for any required CA intermediates):

```sh
/opt/pca-s3proxy/current/runtime/jre/bin/keytool -importcert -noprompt \
  -alias pca-root -file pca-root-ca.pem -storetype PKCS12 \
  -keystore backend-trust.p12 -storepass changeit
install -o root -g pca-s3proxy -m 0640 frontend.p12 backend-trust.p12 \
  /etc/pca-s3proxy/tls/
```

`changeit` protects a store containing public CA certificates only; it is not a
private-key password. Root ownership protects its integrity. The launcher sets
the backend truststore explicitly. Inspect all imported certificates; do not
import arbitrary download chains or use trust-all SDK settings. Use the exact
versioned runtime path until `current` exists.

## Start in restricted qualification mode

After users, profiles and keys are installed, activate the versioned `current`
link as documented in the installation guide, then:

```sh
systemctl start pca-s3proxy
systemctl status pca-s3proxy --no-pager
journalctl -u pca-s3proxy --since '5 minutes ago' --no-pager
ss -lntp
openssl s_client -connect GATEWAY_FQDN:8443 -servername GATEWAY_FQDN \
  -CAfile gateway-ca-chain.pem -verify_return_error \
  -verify_hostname GATEWAY_FQDN </dev/null
curl --cacert gateway-ca-chain.pem -sS -o /dev/null -w '%{http_code}\n' \
  https://GATEWAY_FQDN:8443/
```

TLS verification must succeed and the unsigned request must return **403**.
Confirm no gateway HTTP listener on 8080/80 and no unexpected interfaces/ports.
An unsigned 403 proves frontend refusal only; it is not native IAM readiness.
Check the native endpoint with `openssl s_client` using its own hostname, CA and
443/TCP; native data tests must also succeed through the gateway's Java trust
configuration. Never use `curl -k` or boto3 `verify=False` as acceptance evidence.

Clients use path-style S3 with signing region `us-east-1`; trust the **gateway**
CA via boto3 `verify=/path/to/gateway-ca-chain.pem` or `AWS_CA_BUNDLE` for AWS CLI.
The validator requires HTTPS and an explicit CA file. If an approved load
balancer is introduced later, qualify preservation of signed Host/path/query,
streaming behavior and TLS separately; it is outside this package's tested
built-in-listener configuration.

Certificate/truststore replacement requires a controlled service restart and
repeat TLS plus allowed/denied smoke tests. Record expiry/renewal owners. Keep
previous, still-valid TLS material in the approved protected rollback store;
never restore a revoked or compromised private key.
