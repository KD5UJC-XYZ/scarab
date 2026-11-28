### Changed (JVM options, #1521)

- **Every JVM that runs Scarab's code now starts with `-XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges`.**
  On JDK 25 (Temurin 25.0.4.1, the JDK of the release image) the C2 compiler miscompiled code that the ledger rules
  reach from `TxChecks.collateral`, so a phase-2-invalid transaction could make the rules throw a
  `NullPointerException` and the node stop extending its chain; whether a JVM is affected depends on its compile
  order. Turning off C2's reduction of allocation merges stopped it in the recorded reproduction (0 failing runs of 8,
  against 2 of 8 with the defaults). The release image's launcher `scripts/scarab-jvm` passes the options in either
  diagnostics mode, so the image and the Nix network apps get them with no change by the operator; so do the devnet
  JVMs, sbt's own JVM (`scripts/sbtw`), the forked test JVMs and `node / run` (`build.sbt`), the conformance tools,
  the release checks of the built JAR and image, and the Antithesis trial's node. Left alone: the secret-timing
  measurement, whose JVM is part of its recorded method, the `java -version` probes, and the miscompilation's own
  regression test, which runs on the JIT's defaults. A node started with `java` by hand, from the fat JAR, or with `docker run
  --entrypoint java` replacing the launcher, needs the two options on its command line; the documented commands now
  carry them. `docs/running/launcher.md` ("The JIT workaround") says how to check a running node. A new guard,
  `scripts/check_jit_workaround.py`, fails when a launcher loses the options or a new place starts a JVM without
  being classified. The options go when a JDK without the fault is pinned and the source change of #1521 has landed.
