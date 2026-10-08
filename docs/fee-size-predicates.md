# Closed transfer fee and size predicates (0.8.0)

## API and scope

`FeeSize.Parameters.create` checks exact `Conway`, protocol major 9, unsigned
64-bit per-byte/fixed fee coefficients and unsigned 32-bit maximum size.
`FeeSize.decode` reuses the constructor-private `Coverage.Projection` and
retains both original body and witness-map bytes. `FeeSize.Context.decode`
reparses every supplied resolved output through `Coverage.decodeResolved`.
Call `conwayLedgerSize` independently, or `checkTransferFeeAndSize` with that
checked context. All code is pure; the demo's filesystem/printing stays in Cats
Effect. No additional runtime dependencies, native binaries, signing or keys.

The closed profile permits exactly body keys 0/1/2, nonempty unique spending
inputs, key-only address kinds 0/6, witness map empty or containing VKeys at key
0, `isValid=true`, and null auxiliary data. Legacy two-item outputs and Babbage
maps with exactly keys 0/1 are supported. Both shapes exclude datum/reference
script fields. Every supplied UTxO entry is checked, including unrelated ones.
This conservative behavior can reject a map containing an unspent script output.

Unknown spending inputs fail prerequisites. No caller-supplied no-script Boolean
is trusted. Reference-script fees depend on scripts on the union of spending and
reference inputs, so merely excluding the reference-input body field would be
insufficient. Checked spending outputs establish zero reference cost; excluding
redeemers establishes zero execution fee. Mint, scripts, collateral, reference
inputs, datums, certificates, withdrawals, governance and auxiliary data remain
unsupported. Missing required keys and signature correctness are independent of
fee/size; an empty witness map may pass these predicates.

Malformed and unsupported input are typed `FeeSizeError.Scope(CoverageError)`;
invalid era/protocol/parameters and size overflow have separate errors. Evaluated
fee and size results are independent, with actual supplied values followed by
thresholds. Both failures can be returned together. Neither result establishes
full transaction validity, parameter enactability or a ledger transition.

## Exact memo sizing

The pinned ledger builds a fresh three-item list containing the memoized body,
memoized witness map and auxiliary data, omitting `isValid`. In this null-aux scope:

```text
size = 1 + originalBody.length + originalWitnessMap.length + 1
minimumFee = size * feePerByte + feeFixed
fee passes iff suppliedFee >= minimumFee
size passes iff size <= maxTxSize
```

Use original bytes, never semantic CBOR re-encoding or wire length minus one.
An indefinite or wide outer list has the same ledger size. A wider body integer
or wider/indefinite memoized witness map increases it. All sums/products use
`BigInt`; minimum fee has no artificial uint64 ceiling. The size conversion
checks uint32. Default CBOR resource limits remain 1 MiB/depth 64/100,000 items;
this is not a promise to decode every theoretical uint32-sized transaction.
The package-private numeric helper tests overflow without large allocations.

## Evidence categories

1. **Genuine archived whole-ledger evidence:** two untouched events in the pinned
   `ValueNotConservedUTxO` Haskell sequence. Event1 succeeded; event 2 failed value
   conservation. No fresh ledger rule execution was performed here.
2. **Source-derived fee/size expectations:** original sizes 265/402 and minimum
   fees 167041/173069 with parameters 44/155381/16384. Both pass fee and size;
   event 2 still fails the independent balance predicate (delta −3 lovelace).
   Synthetic equality/adjacent boundaries and raw-encoding variants are project
   tests, not genuine archived fee/size negatives.
3. **Recorded live CLI estimator observations:** official CLI 11.2.3.0 accepts
   the original and six encoding variants. It returns 167041 for all event 1
   variants, even when the original memo-byte minimum is 167085 or 167217.
   Its `estimateMinFeeTx` path edits/re-encodes fees and witnesses, so it is not
   an arbitrary raw-size oracle or a fee/size validity check. Additional witness
   counts are zero. Wide-body mutations have stale signatures; no signature or
   full-ledger acceptance is claimed for these synthetic encodings.

The original parameter record is retained in `fixtures/ledger/raw/pparams.cbor`;
indexes 0/1/3 and protocol index 12 are verified, with no borrowed network defaults.
The importer checks pinned archive, sequence, parameter and decoder hashes;
extracts original components and resolved output byte ranges; applies accepted
event 1 only; and compares the complete final UTxO by exact output bytes.
Output projections are deterministic and independent of working directory.
Historical CLI argv paths are retained as archival observations; they are not
required reproduction paths or native runtime dependencies.

No authentic archived closed-transfer `FeeTooSmallUTxO` or `MaxTxSizeUTxO`
negative has been established. Initial-parameter corpus screening was not a
parameter-state replay. A bounded separately executed Haskell rule harness is
the next stronger evidence gate; current claims deliberately stop short of it.

## Reproduction

```sh
python3 scripts/project-fee-size-fixtures.py --check
python3 scripts/verify-fee-size-projector.py
(cd fixtures/fee-size && sha256sum -c SHA256SUMS)
./scripts/sbtw check 'app/run fee-size-demo' app/runtimeClasspathFile
python3 scripts/verify-fee-size-cli.py
```

The importer requires only Python's standard library and vendored pinned files.
No live network or native oracle is run by these commands. The command admits
only the SHA256-pinned two-row TSV, with a 1 MiB read bound, exit 0 on matched
expectations and exit 2 for missing/modified/oversized input. Its report visibly
separates fee, size, balance and archived whole-ledger outcomes.

## Source pins and attribution

- Target ledger `f649f9751074d2ab3de033fc3912f29c9862c1f5`:
  `Alonzo/Tx.hs`324–332,429–464; `MemoBytes/Internal.hs`164–167;
  `Conway/Tx.hs`86–118; `Conway/UTxO.hs`157–170;
  `Conway/PParams.hs`644–650,862–895. Full URLs/hashes in
  `fixtures/fee-size/upstream/source-provenance.json`.
- Inequality rules are also retained under `fixtures/ledger/upstream` in the
  target Shelley/Babbage UTxO source, as described in ledger-conservation.md.
- Fixture Blueprint fork `1b230262024a992cfb9e9bc47970fd3fafca8dac`, generator
  `dc8494b30741824fcd2ff90d8b4755bfdc10b03d`, Amaru byte import
  `8b1ded2333675ae34be34b7ab4da5f323c4bc3cb`. Archive SHA256
  `33de88ffd3fe1f82326704056264bfcbd62231079e1f88b67fc60eb74da10e40`.
  Existing `fixtures/ledger/provenance.json` retains this provenance chain.
- API fee source tree `7a4de3f5e2510af7d2a819183333f2655e3b002d`, blob
  `692113827b32878a7ef3871df76425af7a62de44`; source provenance is not an
  invented mapping to a CLI release.
- Official CLI 11.2.3.0 binary SHA256
  `0ac45e874599fac4ee6ca4fd9602c0ddb9854a62be5f365dfa425eff9f4bd0ed`.
  It is not bundled. Only historical stdout/stderr/argv are retained here.

Imported ledger/generator/Blueprint/API materials are Apache-2.0, with full
licenses and generator NOTICE in `fixtures/fee-size/licenses`. No Amaru
implementation source or research-only CBOR parser is added to the JVM runtime.
