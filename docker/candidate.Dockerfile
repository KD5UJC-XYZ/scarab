# SPDX-License-Identifier: Apache-2.0
# Linux/amd64 Temurin 21.0.11+10 JRE (the same Java release as the verified builder).
FROM eclipse-temurin@sha256:9af01c30e85b5a9b15fe249a84fb6dcb19e06ee84fbf23ea2d116b0e195c2ce9
ARG SCARAB_VERSION
ARG SCARAB_COMMIT
LABEL org.opencontainers.image.title="Scarab" \
      org.opencontainers.image.source="https://github.com/KD5UJC-XYZ/scarab" \
      org.opencontainers.image.version=$SCARAB_VERSION \
      org.opencontainers.image.revision=$SCARAB_COMMIT \
      org.opencontainers.image.licenses="Apache-2.0"
ENV MALLOC_ARENA_MAX=2
WORKDIR /opt/scarab
COPY --chmod=0444 app.jar /opt/scarab/scarab.jar
USER 10001:10001
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/opt/scarab/scarab.jar"]
