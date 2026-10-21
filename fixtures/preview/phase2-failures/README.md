# Historical Preview phase-two failures

`identities.json` identifies the 242 distinct transactions marked invalid in the public Preview chain
through slot 125092775, block 4748301, header hash
`c182c2d1b783983c614d5331241bd640ebd76ba58f7f51a95dda5921865f733e`.
There are 137 Babbage and 105 Conway transactions in 230 blocks across 68 epochs. These are public
blockchain identities and hashes, covered by the captured-chain-data attribution in [NOTICE](../../../NOTICE).
No signing material or private infrastructure path is included.

The historical October 11, 2026 Preview run at build `d66b216` reported 242 failed transaction-level
phase-two evaluations. Its original run directory is no longer retained locally. The identities were
recovered from the retained October 14–15 canonical Preview immutable archive, bounded by the exact
October 11 final tip above. Recovery checks index CRCs and the original header hashes, reads the
block's invalid-transaction index set, and hashes the original transaction body and witness spans.
The first two identities also occur in the earlier capped [Plutus fixtures](../README.md).

These identities establish the historical set of invalid transactions, not 242 individual failed
scripts. The historical evaluator stopped at the first failing redeemer and did not retain per-script
requests, logs or consumed budgets in its ledger log. Later cumulative log counters also attribute two
transactions differently across the epoch 762/763 boundary; canonical transaction slots determine
this fixture's epochs. Exact historical per-script failure diagnostics cannot be inferred from those
counters or the older fixtures' rendered `outcome` text.

To reconstruct replay inputs from a retained native immutable archive, native V5 snapshot corpus and
matching original UTxO checkpoints:

```sh
python3 scripts/preview_failure_corpus.py \
  --immutable /archive/db/immutable \
  --snapshots /native-v5/snapshots \
  --checkpoints /archive/db/utxo/checkpoints \
  --output /tmp/scarab/preview-failure-windows
```

The output must be a new directory. The exporter rereads original block bytes and requires exact
agreement with every checked-in identity and body/witness hash. It selects a native V5 snapshot
strictly before each failing epoch, keeps its checkpoint separate, and includes all subsequent
contiguous blocks through that epoch's end. Each window contains `records.jsonl`, original invalid
transaction spans in `invalid.jsonl`, the target epoch subset in `targets.jsonl`, and a read-only-use symlink to the supplied immutable archive.
Preceding-epoch overlap is intentional: `invalid.jsonl` lists every invalid transaction in the
window, while the union of target identities is exactly 242. Use `--epochs 13` for a bounded first
window. `windows.jsonl` is the single-JVM replay batch input; `manifest.json` binds snapshots, selected
indexes and records with SHA256. Archives and output data are not committed.

Fresh replay/reference results must report failed transactions, failed scripts and evaluated scripts
separately. The transaction validity flag supplies the authoritative aggregate admission expectation;
per-request verdicts, logs and budgets require a fresh unchanged-reference evaluation. Neither the
later preprod serial/parallel corpus nor a new unit-test count replaces this Preview coverage.

For a production `ChainStorage.open` replay, prepare a separate writable database instead of opening
the archive directly:

```sh
python3 scripts/preview_replay_db.py --immutable /archive/db/immutable \
  --output /tmp/scarab/preview-offline/db --container-immutable /input/immutable --max-blocks 10000
```

Mount `/archive/db/immutable` at `/input/immutable` **read-only** in that replay container. The stager
copies the format file and every selected index to private inodes, symlinks older chunk bodies into
that mount, and privately copies/trims the newest chunk and index at the selected prefix endpoint.
This isolates migration and crash recovery writes. The JSON manifest records source identities,
index hashes, both final-chunk hashes, count and authenticated first/last points. Omitting
`--max-blocks` selects the complete archive; an explicit count selects exactly that many records,
starting at block zero, and refuses a shorter source. The historical K1 Haskell endpoint requires
4,758,521 records through block 4,758,520 at slot 125366428, rather than the later Scala archive tip.

In the Docker JDK image, with that staged database mounted at `/scratch/db`, the authenticated
Haskell projection directory at `/reference`, and a fresh writable `/scratch/out`:

```sh
bash scripts/sbtw 'set node / Test / fork := true' \
  'set node / Test / javaOptions := Seq("-Xmx10g", "-XX:ActiveProcessorCount=4", "-Duser.dir=/work")' \
  'node/Test/runMain xyz.kd5ujc.scarab.node.preview.K1OfflineMain /scratch/db /work/fixtures/preview/genesis /reference/index.jsonl /scratch/out 4758521 125366428 956541c0937e9cdc279514c10fec9706865bb6004ddcfb487bf53c7bbdc97ad1'
```

The last three arguments are the independently staged block count, last slot and header hash.
The caller runs production startup replay, checks links and observer counters, writes current K1
projections, and exits before any follower or network connection. Afterwards require
`scripts/oracle_compare.py diff --reference /reference --dump /scratch/out/dump
--results /scratch/comparison.json --expected-epochs 1451`. A successful replay alone does not
establish reference equality. Compare its ordered `failedTransactionIds` with this fixture as well;
the early 10,000-block smoke prefix contains no phase-two transactions.

## Recorded replay reference contract

`PreviewReplaySuite` requires `SCARAB_PREVIEW_REFERENCE` to name a nonempty authentic
`index.jsonl` and its original reduced native `<slot:012d>-<hash>.json` bodies, as produced by
[`scripts/oracle/reference.py`](../../../scripts/oracle/reference.py). All indexed points must
lie in the selected replay window; missing bodies and differing contents fail. Its successful
summary requires `referenceBodyComparisons == referenceTargetsRequired > 0`. Each reference
field is compared recursively and numerically exactly, including every map digest bucket;
Scala-only fields are outside this relation. This is independent of the Scala serial/parallel
execution comparison and does not assert full native ledger equivalence. Synthetic comparator
controls in `ReplayReferenceSuite` test this contract, not additional observed native cases.

The separate `MithrilBootstrapAcceptanceSuite` checks pinned-artifact restoration, anchor and
UTxO count, then constructs an optional projection export. That test does not compare a native
projection body; a successful export alone is not reference equality.
