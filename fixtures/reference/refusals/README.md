# Reference refusals

Transactions the pinned reference node (`cardano-node` 11.1.3) refused, each with its **complete**
refusal: the hard-fork-combinator `ApplyTxErr` it sent over node-to-client LocalTxSubmission, holding
every predicate failure it accumulated, in its order (#401). `ReferenceRefusalSuite` (node module, part
of `check`) replays every case through Scarab and compares the bytes. Nothing in this directory is
written by hand; every file comes from a run of `scripts/devnet.py refusal-corpus`.
The data is from throwaway local devnets (their genesis and transactions signed with devnet keys);
no signing key is kept, and it carries the repository's licence.

## Layout

| path | content |
|---|---|
| `manifest.json` | `coverage`: the predicate-failure families each era must have a case for; `corpora`: each corpus directory with the sha256 of its `corpus.json` |
| `<corpus>/corpus.json` | era, protocol version, provenance (reference node version and revision, image id, the command, the Scarab commit of the tooling, the time), the sha256 of each genesis file and of each case file |
| `<corpus>/genesis/` | the cluster's genesis files and node configuration, as `devnet.py` copied them |
| `<corpus>/states/<sha256>.cbor` | the reference's `GetCBOR DebugNewEpochState` answer at the tip a case was judged at |
| `<corpus>/utxo/<sha256>.cbor` | the reference's `GetCBOR GetUTxOWhole` answer at that tip (UTxO-HD keeps the UTxO out of the state) |
| `<corpus>/cases/<label>.json` | one case (below) |

A case holds `item` (the exact LocalTxSubmission payload `[era index, #6.24(tx)]` that was sent), `tip`
(the ledger tip; `mempoolSlot` is the reference mempool's snapshot slot, the tip's successor), the state
and UTxO files it names, `reason` (the reference's reason item) with its sha256, `families` (the rule of
each failure, read from the reference's constructor tags), `expect` (the constructor names the case was
built to provoke) and `rendered` (what `cardano-cli transaction submit` printed for a second submission
of the same transaction; a diagnostic only, never compared).

The families are UTXO, UTXOW, UTXOS, LEDGER (the ledger rule's own failures), CERTS, DELEGS, DELEG, POOL,
GOVCERT, GOV and MEMPOOL. `scripts/tests/test_refusalcorpus.py` checks that each recorded family is the
one the reference's rendering names.

## What is compared

For a **Conway** case the suite decodes the state and UTxO with Scarab's Haskell-state codecs (a state
that does not read and write back byte for byte fails its own test), ticks it to the slot after the tip
(`MempoolLedger.tick`), and puts `item` through Scarab's own node-to-client path:
`LocalSubmission.unwrap`, `MempoolState.add` on an empty mempool, `LocalSubmission.reasonItem`. The
result must equal `reason` byte for byte: every failure, their number and their order.

For a **Babbage** case the same state is ticked and the transaction is judged by Scarab's Babbage LEDGER
rule (`ShelleyLedger.sts[Babbage]`) and written by `PreConwayFailureCbor`. Scarab's mempool and
LocalTxSubmission serve Conway only, so this compares the rule and its encoding, not a node-to-client
path.

There are no expected-failure lists, masks or tolerances. A case whose bytes differ fails. A family
that `manifest.json` requires for an era and no case of that era covers fails the era's coverage test:
missing coverage is an incomplete gate, never a pass. Batches were recorded only while the tip and the
mempool slot held still, with an empty mempool, so the validity-interval slot in a refusal is the one
the replay judges at; nothing is masked.

The upstream LEDGERS conformance adapter is not used here: these expectations come from the released
node's own submission path.

## Refresh

```sh
python3 scripts/devnet.py --name <yours> up
python3 scripts/devnet.py --name <yours> refusal-corpus                 # writes conway-devnet/
python3 scripts/devnet.py --name <yours> down
python3 scripts/devnet.py --name <yours> up-eras --final-era babbage   # walks Byron..Babbage and stays there
python3 scripts/devnet.py --name <yours> refusal-corpus --era babbage  # writes babbage-devnet/
python3 scripts/devnet.py --name <yours> down
python3 scripts/check_test_coverage.py --refresh-fixtures
```

A corpus directory is never overwritten: remove it to record it again. The command fails when a case is
refused for another reason than the one it was built for (a constructor of `expect` that the rendering
does not name), or when the reference accepts one.

## Adding cases

Cases are built in `scripts/devnet/refusalcases.py`. A branch may instead keep a builder module of its
own in `scripts/devnet` and record a corpus directory of its own with
`refusal-corpus --era <era> --corpus <name> --cases <module>:<function>` (the function takes the devnet
name and returns `refusalcases.Case`s). The command adds the directory to `manifest.json`; the suite and
the tests read every corpus the manifest lists, with no change to the runner.
