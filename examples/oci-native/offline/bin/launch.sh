#!/usr/bin/env bash
set -euo pipefail
release_dir=$(cd -- "$(dirname -- "$0")/.." && pwd -P)
site_dir=${PCA_S3PROXY_CONFIG_DIR:-/etc/pca-s3proxy}
java_bin=${PCA_S3PROXY_JAVA:-$release_dir/runtime/jre/bin/java}
shopt -s nullglob
files=("$site_dir"/users/*.properties)
if ((${#files[@]} == 0)); then
  echo 'No per-user properties files; refusing to start.' >&2
  exit 1
fi
args=()
for config in "${files[@]}"; do
  args+=(--properties "$config")
done
exec "$java_bin" -Xms128m -Xmx1g \
  -Djavax.net.ssl.trustStore="$site_dir/tls/backend-trust.p12" \
  -Djavax.net.ssl.trustStoreType=PKCS12 \
  -Djavax.net.ssl.trustStorePassword=changeit \
  -jar "$release_dir/lib/s3proxy.jar" "${args[@]}"
