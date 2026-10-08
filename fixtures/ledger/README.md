# Conway PV9 value-conservation conformance evidence

Four cases are projected from two complete, unmodified Haskell-generated ledger
sequences, distributed by Cardano Blueprint and Amaru. Two are accepted setup
transactions and two reject with the Haskell `ValueNotConservedUTxO` failure family.
The nested event boolean is the independent expectation. Calculated consumed,
produced and delta quantities are this project's diagnostics, not Haskell golden
error payloads. Passing this one predicate does not establish transaction validity.

## Offline reproduction

From the repository root:

    python3 scripts/project-ledger-fixtures.py --check

Omit `--check` to regenerate `ledger-vectors.tsv` and `projection.json` byte for byte.
The script always runs its bounded CBOR reader's regression tests and checks
immutable hashes before decoding. No Haskell, Rust, node binary, Scalus, network,
clock or native-crypto dependency is used. Python's standard-library Blake2b hashes
original body slices only to resolve outputs of the accepted setup transaction.

`ledger-vectors.tsv` columns are name, complete original transaction CBOR hex,
resolved-spending-input map CBOR hex, and upstream boolean (`true`/`false`). The map
is `[txidBytes,outputIndex] -> value`, where value is an integer coin or
`[coin,{policyBytes:{assetNameBytes:quantity}}]`. Values are projected losslessly;
addresses and unrelated snapshot state are preserved in the raw sequences, not
silently interpreted as zero. No Scalus MemPack importer is used. Decimal quantities
in JSON are strings and all calculations use exact arbitrary-precision integers.

## Scope and schema checks

The source sequence is `[config,initialNewEpochState,finalNewEpochState,events,title]`.
The pinned snapshot's UTxO path is `[stateIndex][3][1][1][0]`, with exact array
sizes checked along that path (7, 4, 2, 6). Its governance record is size 7 and
contains the current parameter hash at index 3. Both initial and final states
refer to the vendored 31-field parameter record; field 12 is exactly `[9,0]`.
Titles explicitly identify Conway, irrespective of Amaru's shelley/mary directories.
The configuration, epoch 899, initial slot 3883680, tick `[1,1]`, transaction slots
3883681, event sizes and statuses are checked. The complete original archive is
retained and all three selected members are checked for byte equality offline.

Full transaction bodies must contain exactly `{0,1,2}` or `{0,1,2,9}`. This proves
certificates, withdrawals, proposals, donations, collateral, reference inputs,
validity extensions and all other unsupported fields absent; any extra body key
fails closed. `isValid` must be true. Outputs must be the observed two-item legacy
form, with unsigned coin/assets and validated policy/name/index lengths. Inputs
must resolve exactly and be unique; duplicate CBOR map keys also fail. Witnesses
remain intact but are not verified. Snapshot certificate/governance state is
retained but no contextual ledger transition is claimed.

The projector independently recomputes:

    consumed = sum(resolved inputs) + positive mint
    produced = sum(outputs) + fee + absolute value of negative mint

Accepted setup outputs are assigned their real Blake2b-256 transaction ID from the
original body bytes. Rejected transactions do not mutate the projected UTxO. The
final UTxO value projection is compared with the upstream final snapshot; this is
not a comparison of full ledger states. JSON includes source half-open byte ranges
for every transaction, body and resolved/output value, plus complete transaction
bytes and decoded per-input, per-output, fee and mint values.

## Provenance and limits

See `provenance.json` for immutable source links, hashes, member paths and source
assertion locations; `SHA256SUMS` covers every packaged file except itself.

- Amaru: `34a453005bcaaf837ee73bd996b99eab8ef92961`
- Blueprint distribution: `1b230262024a992cfb9e9bc47970fd3fafca8dac`
- Haskell generator fork: `dc8494b30741824fcd2ff90d8b4755bfdc10b03d`

The Haskell generator README says `cabal test cardano-ledger-conway`. These tests
were not rerun for this project. This is archived PV9 evidence, not a new
node-11.1.3 differential run. The bounded arithmetic was separately source-reviewed
against node-11.1.3's ledger commit `f649f9751074d2ab3de033fc3912f29c9862c1f5`.
No claim of PV10 or broader ledger conformance follows from these four cases.

Original fixtures, upstream source excerpts (actually complete files), archive and
license texts are unmodified. New TSV, JSON metadata and projector are project
packaging. Upstream Apache-2.0 texts and applicable Haskell NOTICE are retained in
`../licenses/`; attribution covers Haskell origin and both intermediate distributors.
