# Dependency and native boundary

Direct runtime dependencies: Scala 3.3.8, Bouncy Castle bcprov-jdk18on 1.85.2, Cats Effect 3.6.3, Scalus 1.3.0, Weavechain curve25519-elisabeth 0.1.3. Transitive runtime graph is recorded separately from the full resolved build/test checksum inventory. Bouncy Castle's Blake2b implementation is Java; no application JNI crypto/storage library is chosen. The experimental strict public-input Ed25519 composition is documented separately; Experimental public-only draft03 VRF and separate Praos alpha/certificate checks use the existing Java BC/JDK runtime; KES and BLS remain unimplemented.

Build tooling is a separate boundary: sbt uses ipcsocket/JNA and may extract/use native socket support. The wrapper disables the optional sbt application server and lets the build continue when its optional boot socket cannot be created. This tooling is not shipped as the prototype's runtime. “All-JVM” here describes application dependencies, not a claim that the JDK, OS or build launcher has no native code.

Scalus 1.3.0 is now an explicitly bounded runtime dependency. Both `foundation.icon:blst-java` and `org.scalus:scalus-secp256k1-jni` are excluded. A dedicated platform adapter throws typed capability errors for every platform operation; fixture-hash admission prevents unsupported parser paths, and only the frozen arithmetic/control-flow/Data packet and its explicitly gated AST capabilities are admitted. The complete library still contains native-dependent paths; this harness does not expose them. The pinned 1.63 Plutus corpus differs from the roadmap 1.70 target.

Reproduce the runtime archive audit with `./scripts/sbtw app/runtimeClasspathFile` then `python3 scripts/runtime-inventory.py`. The final graph has 26 runtime jars; the audit rejects excluded artifacts and bundled `.so`, `.dll`, `.dylib`, `.jnilib` files. Run `python3 scripts/verify-runtime-inventory.py` for isolated native-file, wrong-arithmetic-hash and missing-provider rejection tests; no synthetic jar is executed. This scoped archive audit is not a proof about all transitive APIs or a full native-code security audit.

Maven artifacts are version-pinned and the sbt launcher is SHA256-pinned. `scripts/dependency-inventory.py` records fetched jar hashes for review. The runtime audit additionally enforces the inspected Weavechain 0.1.3 binary SHA256. This is otherwise an evidence inventory, not a general dependency-lock enforcement mechanism, signed provenance, full license audit, or distributable SBOM. Cache population requires network access; no fully offline source-only build claim is made. The repository ZIP excludes all caches and build artifacts.

Original code is Apache-2.0. Scala uses Apache-2.0, Cats/Cats Effect MIT, MUnit Apache-2.0, Bouncy Castle its permissive Bouncy Castle license. Upstream fixture LICENSE and NOTICE are retained in fixtures/licenses. Before distributing binary/runtime artifacts, generate an actual-distribution SBOM, bundle required licenses and review transitive obligations. This milestone distributes source and fixtures only.

The 0.3.0 network module adds no new production dependencies. It depends on core
only; effect ownership stays in app using the existing Cats Effect pin. Actual
localhost TCP uses JDK asynchronous channels. No FS2, JNI, storage or competing
Future-based application runtime was imported for networking.

The 0.5.0 strict witness candidate uses only the published curve arithmetic artifact (no transitive dependencies in its Maven POM), not the git-head revision from the earlier roadmap review. Full source/binary pins and native-oracle limitations are in [witness scope](witness-verification.md) and [provenance](witness-provenance.md). Its complete MIT, CC0/eddsa-java and BSD-3-Clause curve25519-dalek notices are in fixtures/witness/licenses/elisabeth-LICENSE. No signing/timing guarantee or binary reproducible-build claim is made.

The private-cluster v2 reference JSON projection uses the bounded app-local `ReferenceJson` reader. It adds no dependency and does not call JSON APIs from accidental transitive artifacts.
