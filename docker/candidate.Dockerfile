# SPDX-License-Identifier: Apache-2.0
# Linux/amd64 Temurin 25.0.4.1+1 JRE on Ubuntu 26.04 (resolute): tag eclipse-temurin:25.0.4.1_1-jre-resolute, index
# digest checked on 2026-10-28 (the same Java release as the verified builder).
FROM eclipse-temurin@sha256:acd83cdf6c9b5bfcd7c4e9d3e1969bacaacdcfa034c074500ab28610fb840f81
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
# Scala 3's lazy vals (scala.runtime.LazyVals, still in 3.9) use sun.misc.Unsafe, on which Java 25 warns once
# per process (JEP 498); the flag allows it without the warning.
ENTRYPOINT ["java", "--sun-misc-unsafe-memory-access=allow", "-jar", "/opt/scarab/scarab.jar"]
