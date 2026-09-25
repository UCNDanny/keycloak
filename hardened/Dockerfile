# Same hardening as kreutzmann-img's Dockerfile.keycloak, fed the locally built distribution.
# Pins (base digests, BCFIPS jars) come from kreutzmann-img's dependencies.lock.json.
ARG JAVA_IMAGE
ARG UBI_IMAGE
ARG CA_IMAGE
FROM --platform=$BUILDPLATFORM ${CA_IMAGE} AS ca
RUN test -s /etc/ssl/certs/ca-certificates.crt

FROM ${UBI_IMAGE} AS build
ARG BC_FIPS_URL
ARG BC_FIPS_SHA256
ARG BCTLS_FIPS_URL
ARG BCTLS_FIPS_SHA256
ARG BCPKIX_FIPS_URL
ARG BCPKIX_FIPS_SHA256
ARG BCUTIL_FIPS_URL
ARG BCUTIL_FIPS_SHA256
RUN microdnf install -y --setopt=install_weak_deps=0 java-21-openjdk-devel tar gzip && microdnf clean all
COPY keycloak.tar.gz /tmp/keycloak.tar.gz
RUN mkdir -p /opt/keycloak && tar -xzf /tmp/keycloak.tar.gz --strip-components=1 -C /opt/keycloak
ADD --checksum=sha256:${BC_FIPS_SHA256} ${BC_FIPS_URL} /opt/keycloak/providers/bc-fips.jar
ADD --checksum=sha256:${BCTLS_FIPS_SHA256} ${BCTLS_FIPS_URL} /opt/keycloak/providers/bctls-fips.jar
ADD --checksum=sha256:${BCPKIX_FIPS_SHA256} ${BCPKIX_FIPS_URL} /opt/keycloak/providers/bcpkix-fips.jar
ADD --checksum=sha256:${BCUTIL_FIPS_SHA256} ${BCUTIL_FIPS_URL} /opt/keycloak/providers/bcutil-fips.jar
# Stable provider mtimes prevent optimized-start rebuild detection after COPY.
RUN chmod 0644 /opt/keycloak/providers/*.jar \
    && touch -m --date=@1700000000 /opt/keycloak/providers/*.jar
ENV KC_DB=postgres KC_HEALTH_ENABLED=true KC_METRICS_ENABLED=true KC_FEATURES=fips KC_FIPS_MODE=strict
RUN /opt/keycloak/bin/kc.sh build

COPY KeycloakLauncher.java /tmp/KeycloakLauncher.java
RUN javac -d /opt/keycloak/lib /tmp/KeycloakLauncher.java \
    && mkdir -p /opt/keycloak/data \
    && chown -R 1000:1000 /opt/keycloak/data \
    && chmod -R go-w /opt/keycloak \
    && rm -rf /opt/keycloak/bin

FROM ${JAVA_IMAGE}
COPY --from=build /opt/keycloak /opt/keycloak
COPY keycloak-java.options /opt/keycloak/conf/java.options
COPY --from=ca /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/public-ca-bundle.pem
ENV KC_DB=postgres KC_HEALTH_ENABLED=true KC_METRICS_ENABLED=true \
    KC_FEATURES=fips KC_FIPS_MODE=strict KC_RUN_IN_CONTAINER=true
USER 1000:1000
WORKDIR /opt/keycloak
EXPOSE 8080 9000
LABEL org.opencontainers.image.title="keycloak-preview-hardened" \
      org.opencontainers.image.source="https://github.com/UCNDanny/keycloak" \
      org.opencontainers.image.licenses="Apache-2.0"
ENTRYPOINT ["/usr/bin/java", "@/opt/keycloak/conf/java.options", "-cp", "/opt/keycloak/lib:/opt/keycloak/lib/quarkus-run.jar", "KeycloakLauncher"]
CMD ["start", "--optimized"]
