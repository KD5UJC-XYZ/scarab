### Changed (build)

- Build, CI and the release image move from Java 21 to Java 25 (Temurin 25.0.4.1+1). The release JAR's manifest declares `Enable-Native-Access: ALL-UNNAMED`, so `java -jar` runs the native calls (the local socket's `unlink`, the Plutus crypto library) without a flag or warning. Scala 3.3's lazy vals (`scala.runtime.LazyVals`) use `sun.misc.Unsafe`, on which Java 25 warns once per process, so the node is started with `--sun-misc-unsafe-memory-access=allow` (the release image's entrypoint, the devnet launcher, `sbt run` and the documented command line).
