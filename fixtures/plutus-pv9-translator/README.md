# Synthetic PV9 translator differential packet

18 original offline synthetic transaction/pre-state packets and independently exported reference contexts. See [translator contract](../../translator/README.md) for admitted grammar, limits and interpretation. This proves tested-profile translation parity only, not full ledger validation, transaction acceptance or general context translation.

`vectors.json` contains original packet bytes, separate generator specification metadata, and actual verified reference output per case. Metadata expectations are not oracle observations. All18 reference translations completed;11 evaluations succeeded and7 returned unclassified CEK errors with null consumed budget. The unchanged script requires one ordinary input and one output: extra-input/output cases and redeemer8 intentionally fail evaluation despite correct translation.

Generator cases vary beneficiary/minimum/payment, own input identity/index/amount, fee, unsigned0x7f/0x80 ordering, equal hash/different index ordering and spending pointer, output order, redeemer, and unbounded/lower-only/upper-only/both/equal/domain-endpoint intervals. Reference transaction commitments are reconstructed before final serialization for every case. The baseline bytes exactly match the approved earlier packet. Context goldens are never patched.

## Reproduction and provenance

`source/VariantBuilder.hs` is pure original Apache-2.0 code; `VariantMain.hs` is the bounded IO adapter. Use the unchanged PacketBuilder.hs, Cabal project/freeze and package definition from `../plutus-pv9-reference/source/`, appending `source/executable-stanza.cabal.txt` to the isolated package definition. The compiled combined package/source hashes, binary hashes and fixture hash are in provenance.json. Upstream archive/ABI/native-reference provenance remains in the linked base provenance;379 external dependency units were unchanged. Historical source-only comments are preserved to retain the compiled source hashes and are superseded by recorded successful execution.

The generator accepts only the pinned script as raw stdin and emits bounded JSON. Evaluate each emitted packet using the unchanged approved real-context-exporter and verifier with fresh per-case hash manifests. Retain full reference context tree/CBOR and observed evaluation independently of specification expectations. No keys, signatures, historical anchor, production/provider corpus or network transaction is involved.

Runs used offline Docker at2CPUs/2GiB, no extra swap. Reference evaluation mounted helper/inputs read-only and output separately writable. Build/output evidence and executables remain untracked local evidence; this packet contains only original source, synthetic inputs/results and provenance. Haskell reference native libraries do not enter the Scala classpath. No new external dependency or provider was added.

From repository root, run `translator/test` using the documented cached isolated JVM runner. Tests require every ordered field and exact CBOR to match before evaluation; they also reverse pre-state entries, reject unsupported grammar, and require the exact fixed declared limits. Verify this directory with `sha256sum -c fixtures/plutus-pv9-translator/SHA256SUMS` from root. Independent parent review of generator/source/output remains required before public integration.
