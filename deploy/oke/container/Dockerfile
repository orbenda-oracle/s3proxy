# Official Temurin 17.0.21.1_2-jre-jammy, immutable multi-platform manifest.
# Build only linux/amd64: the delivered image/chart qualify that platform.
FROM docker.io/library/eclipse-temurin@sha256:0197d17ef5317724fda570ba2263b5e011fa32663339a880414fdb186884a611
ARG SOURCE_REVISION
LABEL org.opencontainers.image.title="PCA native Object Storage S3 gateway" \
      org.opencontainers.image.source="https://github.com/orbenda-oracle/s3proxy" \
      org.opencontainers.image.revision="${SOURCE_REVISION}" \
      org.opencontainers.image.version="20261126-oke-r1" \
      org.opencontainers.image.licenses="Apache-2.0"
WORKDIR /opt/pca-s3proxy
COPY --chmod=0444 lib/s3proxy.jar /opt/pca-s3proxy/lib/s3proxy.jar
COPY --chmod=0555 bin/launch.sh /opt/pca-s3proxy/bin/launch.sh
ENV PCA_S3PROXY_JAVA=/opt/java/openjdk/bin/java \
    PCA_S3PROXY_CONFIG_DIR=/etc/pca-s3proxy \
    HOME=/tmp
USER 10001:10001
EXPOSE 8443
STOPSIGNAL SIGTERM
ENTRYPOINT ["/opt/pca-s3proxy/bin/launch.sh"]
