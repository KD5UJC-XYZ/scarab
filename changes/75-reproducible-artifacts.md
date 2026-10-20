Added deterministic node runtime ZIP packaging and an sbt check that compares two clean builds of
one frozen revision inside a fixed JDK image. Runtime classpath order and dependency/native resource
bytes are preserved. Signed releases, SBOM/provenance, published images and native-source rebuild
verification remain separate follow-ups under #75 and #229.
