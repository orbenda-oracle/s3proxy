# 6. Preparation checks and rebuilding

Preparation host: macOS. The unchanged packaged product JAR was previously
verified on Java 17 and against real OCI native Object Storage with 35 passing
checks (see `public-oci-evidence/`). This handoff adds configuration, packaging
and validation tools; it does not change Java storage/authorization code.

Checks performed while preparing this package:

- Linux x64 Temurin JRE download matched the pinned publisher SHA-256.
- Offline-only installation of all seven pinned Python wheels into a new venv
  succeeded; `pip check` reported no broken requirements.
- Seven offline package safety tests passed, including fail-on-unexpected-grant,
  fail-on-transport-error, actual key-file rotation/disable/permissions, listing
  effects, multipart part integrity and concealed-authorization rejection, plus the Linux release filename.
- Nine existing live-harness safety tests passed.
- Actual launcher and packaged JAR started with built-in HTTPS, a generated
  test-only certificate and host Java 17. Trusted-CA HTTPS returned unsigned
  403; a signed unknown key returned 403; an untrusted CA failed verification.
  Native endpoint was intentionally unreachable: this was a frontend TLS/auth
  smoke test, not a new native integration or PCA test.
- Bash syntax and documentation links were checked; independent review findings
  on guarded runbooks and operation-effect assertions were resolved.

The bundled **Linux** JRE cannot execute on the macOS preparation host. No local
Linux container engine was running. Linux loader/systemd/SELinux behavior and
real PCA release/TLS/API/IAM/application behavior are mandatory onsite checks.
No claim of a clean OL8/OL9 replay or completed PCA qualification is made.
Archive extraction/checksum/source/wheel closure results are recorded in the
adjacent release preparation JSON created after packaging.

## Rebuild from pinned source on a connected build host

The package builder itself performs no downloads. From a clean checkout at the
manifest's source commit, obtain these inputs through approved channels:

- The exact JAR whose SHA-256 is in `build.py` and the public OCI evidence. A
  fresh Maven build may differ in archive timestamps; qualifying a new JAR is
  a separate acceptance run. Do not bypass the builder's digest check.
- Linux JRE `OpenJDK17U-jre_x64_linux_hotspot_17.0.21.1_2.tar.gz` from the official
  [Temurin 17 binary release](https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.21.1%2B2),
  plus the Adoptium API metadata. Expected archive SHA-256:
  `5a092a0bc21de4c3d855480edd7945be9cfe157b38cdb84dac280511893bb009`.
- Pure Python wheels resolved from `requirements.txt` using `pip download
  --only-binary=:all: --python-version 3.11 --implementation cp --abi cp311
  --platform manylinux2014_x86_64 -r requirements.txt --dest WHEELS`.
- Original runtime dependency JARs: from the same source/POM, run
  `mvn org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies
  -DincludeScope=runtime -DoutputDirectory=ABSOLUTE_DEPENDENCY_DIRECTORY`.
  Keep their original notices; the builder copies them and emits a hash inventory.

Then run from the repository root, supplying absolute paths to those inputs:

```sh
python3 examples/oci-native/offline/build.py \
  --jre /INPUTS/temurin-linux-jre.tar.gz \
  --jre-metadata /INPUTS/adoptium-metadata.json \
  --wheels /INPUTS/wheels --dependency-jars /INPUTS/dependency-jars \
  --output /OUTPUT --release pca-s3proxy-NEW_RELEASE-linux-x86_64
```

Use a new release name; existing archive paths are refused. The builder checks
the pinned JAR/JRE, requires committed tracked source, embeds its exact Git
archive, manifest, original dependency inventory/notices and SHA256SUMS, then
writes an external transfer checksum. Compare the external checksum through a
trusted channel. No private profiles/keys or work directory are copied.
This is an artifact assembler, not a signed release pipeline or an assertion
that dependencies are vulnerability-free; use the site's normal artifact and
license review before import.
