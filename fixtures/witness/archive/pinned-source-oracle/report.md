> Public export note: historical run logs and raw diagnostic output are retained only in the private research archive. Their mentions below document prior evidence; they are not bundled public artifacts or newly executed public checks.

# Pinned Cardano sodium public-verification oracle

Research date: 2026-10-08 UTC. Isolated research output; no main-repository modifications.

## Result

The official Cardano sodium fork at `dbb48cce5429cb6585c9034f002568964f1ce567` was built locally with **ED25519_COMPAT undefined**. Its detached Ed25519 verification agrees with both the recorded system libsodium 1.0.18 results and the strict Weavechain JVM prototype on **all 2,207 fixed-size public cases**. All eight ledger witnesses verify. Twelve malformed-length cases are excluded before any native call and are never padded.

- Corpus: 2,219 rows, 2,207 native verification calls per complete run, 12 malformed-length exclusions.
- Fixed-size results: 1,121 accepted, 1,086 rejected.
- System-sodium divergences: zero.
- Strict-JVM-prototype divergences: zero.
- BC 1.85.2 divergences: exactly `speccheck-2`, `speccheck-4`, `speccheck-5` (BC accepts, pinned oracle rejects).
- Mixed-order `speccheck-3` remains accepted. Prime-subgroup-only validation would still over-reject.

This closes the **unexecuted pinned-source native oracle** portion of the earlier research gap. It does **not** establish that the official shipped Cardano node binary was compiled with these flags, reproduce that binary, prove acceptance equivalence for every input, or complete ledger witness validation. Keep the runtime described as an experimental strict JVM verifier with differential evidence. Native artifacts remain research/test-only and introduce no runtime dependency into the JVM implementation.

## Source and linkage

Official source archive: https://codeload.github.com/IntersectMBO/libsodium/tar.gz/dbb48cce5429cb6585c9034f002568964f1ce567

Source commit: `dbb48cce5429cb6585c9034f002568964f1ce567`.

Archive SHA-256: `e4f29ae3c16037e484bb69e3fa22a5565c42adf497f8f88e61ff8d9486ab863e`.

The inspected Cardano node 11.1.3 source pin is `938cba990357ae7c4b7f95c8f75dd9d31174bbeb`; its `flake.lock` names this sodium revision under input-output-hk/libsodium. `nix/haskell.nix` lines 194–196 selects `pkgs.libsodium-vrf` for both cardano-crypto-praos and cardano-crypto-class. This is source dependency evidence, not proof of the binaries shipped to users. Exact release DSIGN source linkage is documented in `next-witness-slice.md`.

All 611 extracted source files were checked byte-for-byte against the downloaded archive after the build. No upstream source file was modified. `source-sha256.json` records every file; the ISC license is retained in the extracted source's LICENSE. The archive's `open.c` and `ed25519_ref10.c` also match the corresponding previously inspected witness research files byte-for-byte.

## Build and non-COMPAT evidence

Working directory: `cardano-research/cardano-sodium-oracle/`.

Tools already available:

- GCC `gcc (Debian 14.2.0-19) 14.2.0`, target `x86_64-linux-gnu`.
- GNU Make 4.4.1.
- Autoconf, Automake and libtoolize are absent. The official pinned archive includes generated configure, Makefile.in and build helpers, so no installation or bootstrap was needed.

Build was out of tree under `build/`:

```sh
mkdir -p build
cd build
env -u CPPFLAGS -u LDFLAGS CFLAGS='-O2 -g0 -UED25519_COMPAT' CC=gcc \
  ../libsodium-dbb48cce5429cb6585c9034f002568964f1ce567/configure \
  --prefix="$PWD/../install" --enable-shared --disable-static --disable-dependency-tracking
make -j2
```

The prefix was configured but **make install was never run**. No global install, permission change, security setting change, node process, network listener, or transaction submission was performed. Configure's local compiler feature probes ran normally. `make check` was deliberately not run because the upstream general test suite includes signing/key-generation operations outside this public-verification research scope. The full upstream library contains those APIs, but the test harness never invokes them.

Effective CFLAGS from the generated Makefile:

```text
-O2 -g0 -UED25519_COMPAT -pthread -fvisibility=hidden -fPIC -fPIE -fno-strict-aliasing -fno-strict-overflow -fstack-protector -ftls-model=local-dynamic
```

CPPFLAGS: `-D_FORTIFY_SOURCE=2`. Full DEFS, architecture feature choices, compiler target, configuration environment and arguments are in `provenance.json` and `build/config.log`. `verify-compile-command.txt` contains the dry-run expansion of the open.c compile command using the generated Makefile. Re-running that exact command in preprocessing mode established that:

1. `ED25519_COMPAT` is not defined in `verify-preprocessor-macros.txt`.
2. `verify-preprocessed.c` includes the canonical-S/small-order rejection branch.
3. It does not include the weaker `if (sig[63] & 224)` COMPAT branch.

One optional `make -n -B` command-expansion attempt requested regeneration and failed because Automake is absent; it did not affect the already successful library build. A targeted `make -n -W <open.c> V=1 <open-object>` expansion succeeded without regeneration. Post-build archive comparison confirmed pristine source files.

Built library: `build/src/libsodium/.libs/libsodium.so.23.3.0`.

Library SHA-256: `76b41125c867878a1b0cfe64dde580872f61ca4cc6fae06223e902e3e803b24f`.

Reported version: 1.0.18. The version alone is not provenance; the pinned source, build records and binary hash identify this oracle. ELF NEEDED entries are libc.so.6 and ld-linux-x86-64.so.2; it does not load the system libsodium as a dependency.

## Harness, inputs and safeguards

`verify_public.py` uses Python ctypes to load the absolute path of the built library. Only `sodium_version_string` and `crypto_sign_ed25519_verify_detached` are called. The detached function has explicit ctypes argument and return types. There is no sodium_init, key generation, signing, seed handling or credential access. Inputs contain public keys, published signatures and public messages only.

Before loading the native library, the harness verifies fixed SHA-256 expectations for the archive, built library, source corpus, recorded system/BC result set and strict prototype result file. It checks exact public-key/signature lengths in Python before dispatch. Invalid sizes produce an excluded row, without truncation, padding or native invocation. The harness rejects Python optimization explicitly, so `python -O` cannot silently disable its provenance/ABI assertions. Both an ordinary full rerun and the optimized-mode rejection were checked.

Input hashes:

- `witness-slice-probe/vectors.json`: `c9cbe16bc930c541fa45df129d5ae99606a0095c045b9d2c7974aab9abffb23e`
- `witness-slice-probe/results.json`: `3bc0fab952d45108f11f569edb4dda3b3ebf0368505105236e21207470c6bf3f`
- `witness-slice-probe/strict-results.tsv`: `8a56009372dbf3efeb7ae0c042c81b58fe55e3a9506921214668063750af4430`

Comparison is against **recorded** system sodium and strict prototype results, not a new JVM run. All IDs and lengths are checked. The corpus composition and public-vector licensing/provenance are recorded in `next-witness-slice.md`: 1,024 sodium positives, 1,024 S+L negatives, 12 speccheck cases, 151 Wycheproof cases (12 malformed), and eight ledger fixture witnesses. No new upstream golden status is assigned to witnesses from ledger-rejected transactions; their signatures remain valid differential observations.

## Deliverables and reproduction

Under `cardano-sodium-oracle/`:

- `results.json`: row-by-row pinned-native result, native-call/exclusion status, lengths, and recorded comparator results.
- `summary.json`: counts and divergence IDs.
- `provenance.json`: source, compiler, flags, artifact hash and explicit release-binary limitation.
- `sha256.json`: hashes of harnesses, results, source manifest, logs and build evidence.
- `source-sha256.json`: all unmodified official source-file hashes.
- `verify_public.py`: hash-gated public-input corpus runner.
- `record_provenance.py`: source-archive integrity and preprocessing evidence recorder.
- `configure.log`, `build.log`, `build/config.log`, `build/config.status`: retained build evidence.
- `verify-compile-command.txt`, `verify-preprocessor-macros.txt`, `verify-preprocessed.c`: non-COMPAT compile evidence.
- `source.tar.gz` and pristine extracted official source, including LICENSE.

Run `python verify_public.py` from the oracle directory to repeat this pinned-artifact comparison. It intentionally rejects a changed/rebuilt artifact until its provenance is separately reviewed and its expected hash is explicitly updated. `python record_provenance.py` rechecks source integrity and preprocessing evidence. Local binary reproducibility across different compilers/platforms is not claimed.

## Remaining limits

Official node release compiler/build flags and byte-identical shipped-library provenance remain unestablished. This self-build is an exact-source, explicitly non-COMPAT oracle, not an execution of the official node release binary. Finite differential tests are not an audit or proof over all byte strings. JVM arithmetic-composition review, provider failure behavior, immutable buffer ownership, input-envelope handling, licensing and dependency/native-load checks remain separate implementation review obligations. No full Cardano node or consensus/network runtime was started.
