### Added (Verified candidate artifacts, #75)

- Build unpublished runnable fat-JAR and Linux/amd64 OCI candidates with two-clean-build JAR
  comparison, source-built portable JNI comparison, runtime smoke checks and retained input/output
  evidence. Generate subject-bound CycloneDX inventories from the actual resolved JVM graph,
  assembly resources and image/OS contents; fail missing component and hash coverage.
- Keep licence/advisory review, authenticated publication and signatures separate from verified
  inventory. Candidate preparation does not publish artifacts or approve production releases.
