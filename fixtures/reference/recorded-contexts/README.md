<!-- SPDX-License-Identifier: Apache-2.0 -->
# Script contexts of recorded transactions, as the Haskell ledger collects them

**Status: Current.** Evidence for [#43](https://github.com/KD5UJC-XYZ/scarab/issues/43): the recorded
Preview contexts and a context with several voters, built by the pinned Haskell collection instead of
Scalus's translation, which went with the Scalus backend.

`contexts.json` holds 52 cases, each with the answer of unchanged released
`collectPlutusScriptsWithContext` (cardano-node 11.1.3, cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`, Plutus 1.70.0.0) at the case's own era type and protocol
version, measured by the unchanged [context-matrix observer](../../../scripts/oracle/plutus/context-matrix/README.md)
(the executable of [the context matrix](../context-matrix/README.md)) from inputs written by
[`scripts/oracle/plutus/recorded-contexts`](../../../scripts/oracle/plutus/recorded-contexts/README.md).
A collected case keeps every request in the reference's order, each as `ToCBOR PlutusWithContext`
writes it: the protocol version, the language and script, the arguments with the complete script
context, the budget and the cost model; it also keeps the observer's evaluation verdict and budget.
A refused case keeps the collection's error kinds and their CBOR.

| Cases | Era / protocol | Source | Reference answer |
|---|---|---|---|
| 45 `preview/…` | Babbage 7 (17), Babbage 8 (8), Conway 10 (3), Conway 11 (17) | every recorded transaction of [`fixtures/preview/plutus`](../../preview/plutus/README.md) | 45 collections, 90 requests: PlutusV1 5, V2 37, V3 48 |
| 3 `preprod/…` | Conway 10 | three of the four of `fixtures/preprod/plutus` | 3 collections, 5 PlutusV2 requests; one transaction runs no script |
| 4 `voters/…` | Conway 11 | derived from `preview/slot117938233-a1b750a9` | 2 collections of 3 PlutusV3 requests, 2 refusals |

A recorded case is bound by the hash of its recorded file and of the observer input built from it;
its transaction, outputs and cost models are not copied here. The fourth preprod transaction,
`tx-c70243bb` (158 spending scripts of a 159-input transaction), is left out: its answer is 19.6 MB,
ten times all the others together. The observer reads the network's clock line: Preview from
1666656000 s, preprod's Shelley line from 1655683200 s (slot 86400 at 1655769600 s after four
Byron epochs of 20 s slots), 1 s slots; the preparation requires every slot sample of a recorded
case to lie on it, and the suite gives the node the recorded samples.

The `voters/` cases are API-only: the recorded vote of a script committee member, beside a key
committee member, a script DRep voting on two actions, a key DRep and a pool, written in the reverse
of `Ord Voter`, with the voting redeemers at the indices of `Ord Voter` (collected), of the
transaction's own order (one missing redeemer), of canonical CBOR key order (two missing redeemers),
and at every index (collected; the redeemers of key voters are not collection's concern). Their
signatures are not revalidated and they make no ledger-admission claim; neither does the observer
for the recorded cases, whose admission is the chain's.

`RecordedScriptContextsSuite` requires the node's requests to be the reference's, in order, with
every argument (datum, redeemer and context, as the language takes them) byte for byte as
`serialiseData` writes it, and the node's refusals to name the reference's purposes in its order.
It does not compare the retained evaluation verdicts and budgets, which are Plutus 1.70.0.0's.

`provenance.json` binds the pins, the observer image and executable, and the preparation and
observer sources; `manifest.json` binds the files. Replay with
`python3 scripts/oracle/plutus/recorded-contexts/run.py` where the observer image exists
(`--verify-only` needs no Docker). The transactions are public chain data; the derived
transactions use placeholder hashes and an Apache-2.0 test script; no keys or secrets.
