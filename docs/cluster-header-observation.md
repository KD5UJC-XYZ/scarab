# Hash-bound genesis header observation

`lab.ClusterHeaderObservation` is an offline app-level adapter over `PraosHeaderConformance`. It connects the saved `transfer-range-block` records to the existing v2 transfer context loader, derives KES timing and fixture registration from hash-bound genesis JSON, and checks both signature predicate results. It adds no live network operation or new core profile.

## Evidence flow

1. `load(directory)` calls `ClusterTransferCommand.load`, preserving its exact manifest, parameter, tip-bracket and ledger-source digest checks. No parser or context format is copied.
2. The adapter bounded-reads `transfer-genesis.md` again and checks its original-byte SHA256 against the checked context's `genesisDigest`. A file changed between reads must still match that digest. `ReferenceJson` handles strict UTF-8/JSON parsing, duplicate fields and exact integer lexemes.
3. `slotsPerKESPeriod` and `maxKESEvolutions` come only from that JSON. Period length must be positive uint64, and the existing Sum6 lifetime is 1..64. Testnet network identity must match the loaded context.
4. Fixture registration comes from `extraConfig.stakePools.data` in the same hashed genesis. Object keys and `poolId` must be identical canonical hash28 values; each `vrf` is a canonical hash32. There is no fallback to a self-derived issuer, missing registration, an unhashed pool list, or caller-supplied timing values.
5. `captures(path)` reads at most 4 MiB of strict UTF-8 JSON-lines and selects one to eight `transfer-range-block` records. Other receipt lines do not confer success or authority. It extracts the envelope and original block bytes, not a reconstructed block.
6. `inspect` reparses each envelope through `ReferenceCaptureCommand.header`, reuses `ReferenceCaptureCommand.compare` for original header/block identity, body size/hash commitments and parent/slot continuity, and requires the last point to equal the context's post point. The first point extends the context's pre point.
7. The header cold key identifies its genesis pool entry. The core adapter checks that entry's VRF hash and applies the original-body encoding restriction, OpCert verification and KES verification using the derived timing.

All new implementation dependencies point from this app adapter to existing app/core components. No existing source is changed and no dependency cycle is introduced. Checked context/report constructors are private. Package-visible helpers exist for bounded synthetic tests.

The digest passed to the core adapter's generic `genesisHash` field is explicitly the context's **SHA256 of the exact genesis export bytes**, not an asserted Cardano genesis Blake2b hash. The app output names it `genesisSha256`.

## Meaning of results

A successful `Either` means a supported, structurally bound observation exists. It can contain rejected signatures. `cryptographicPredicatesSucceeded` requires **both** `OperationalCertificateSignatureVerified` and `SuppliedMessageSignatureVerified`, for every nonempty range entry. The CLI returns exit 2 on a rejected signature or unsupported input; positive predicate observations return exit 0.

`genesisTimingSourceBound` and `genesisRegistrationSourceBound` mean the values were extracted from the original source matching the context digest. They do not authenticate that manifest or establish trusted ledger registration. The context loader retains its non-atomic snapshot limitations. Genesis pool registration is a fixture assumption, not current pool membership, epoch stake or current VRF authorization.

OpCert counter state, VRF proof, stake, epoch nonce and leadership remain unchecked. No header or consensus validity follows from positive signatures; `fullHeaderValidated` and `consensusValidated` are always false. This adapter also does not rerun the transaction/ledger transition comparison. It checks the header/block range under the loaded context.

The core observations retain their conservative lower-layer unchecked list. The app's source-bound flags narrow the missing input-provenance claim only for this app path; they do not silently promote the reusable core API.

## Exact serializer boundary retained

The pinned reference source hashes memoized original header bytes but signs a version-selected reserialization of HeaderBody. The core adapter still requires original-body equality with the supported shortest-width definite encoding. Alternative body encodings remain unsupported, not proven reference-invalid. The header version is observed from the bytes, not used to admit a wider consensus profile.

Node package evidence for Conway 1.23.0.0, Babbage 1.14.0.0 and ledger-binary 1.9.1.0 does not by itself establish the exact cardano-protocol/consensus/crypto build identities or version-11 encoding/acceptance behavior. The source pins and unresolved malformed-signature/reference-serialization parity in `header-crypto-conformance.md` remain unchanged.

## Running and optional integration

Without editing `Main.scala`:

```text
app/runMain lab.ClusterHeaderObservation /path/to/v2/evidence
```

The evidence directory must contain the inputs required by the existing v2 context loader and `scala-transfer.md`. The original v1 retained transfer directory is deliberately unsupported; use the already-retained v2 projection, which preserves the reference source bytes.

The integrator may add this single route inside `Main.run` (no shared source is changed in this packet):

```diff
   def run(args: List[String]): IO[ExitCode] = args match
+    case "cluster-header-observation" :: rest => ClusterHeaderObservation.run(rest)
```

This keeps dependencies one-way. Do not change the existing live transfer receipt's `headerSignaturesChecked` flag merely because this standalone offline command exists.

## Tests

`app/testOnly lab.ClusterHeaderObservationSuite` runs eight public tests using one SHA256-pinned, already-cleared public Amaru header. They cover source/timing/registration mutation, duplicate/fractional JSON, signature result conjunction, real signature mutations, unsupported encoding, body/range mismatch and the distinction between supported observations and predicate success. Public synthetic genesis values establish mechanics only, not real-network registration.

Set `CLUSTER_HEADER_EVIDENCE` to the retained v2 evidence directory to add one explicit private offline pipeline test. It requires both signatures to succeed and derives 129600 slots/KES period and 60 evolutions from the original hashed genesis. No retained corpus or raw private log is committed. Docker tests use an isolated cache/output tree, 2 CPUs and 2 GiB memory; no live cluster is required.
