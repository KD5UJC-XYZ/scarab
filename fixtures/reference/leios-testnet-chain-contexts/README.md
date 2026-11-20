# Script contexts of the public Leios testnet's own transactions

Status: Current. Evidence for [#54](https://github.com/KD5UJC-XYZ/scarab/issues/54) (the follow-up
[issuecomment-6905542791](https://github.com/KD5UJC-XYZ/scarab/issues/54#issuecomment-6905542791)).

The reference is the build the public Leios testnet runs: cardano-node `b004fe0c`, with
cardano-ledger `9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c`, ouroboros-consensus `2416493b` and
plutus-ledger-api 1.71.0.0 (`261766ad`); [the oracle README](../../../scripts/oracle/leios-testnet/README.md)
says how each was established. Unlike [leios-testnet-contexts](../leios-testnet-contexts/README.md),
whose transactions are written by hand with placeholder scripts, every transaction here was
recorded on the testnet's chain and its scripts are those it ran there.

## Where the transactions come from

On 2026-11-16 a node of the testnet's build, the release's image
`ghcr.io/input-output-hk/ouroboros-leios/cardano-node-testnet:rc-2026w44` (`sha256:70439665…`,
cardano-node 11.2.0.164 at `b004fe0c`), followed the public Leios testnet from Origin with the
[committed configuration](../../leios/README.md) (network magic 164, bootstrap peer
`leios-node.play.dev.cardano.org:3001`; the live files of that day are those files byte for byte),
as a following node with no forging keys and no published port, which submitted nothing. It was
stopped at its selected tip, block 113,880 at slot 2,570,179 (hash `7d601b3d…`, epoch 118).
`leios_chain_scan.py scan` read its database: 113,881 ranking blocks, 40,336 of them carrying a Leios
certificate, which apply 10,110,184 transactions of their own and 51,840,765 of the certified
endorser blocks' closures, none with a false validity flag. 107,831 of them have a redeemer (9,793 of
ranking blocks, 98,038 of endorser blocks), between slots 292,664 and 2,566,460: given PlutusV3
scripts 107,385 of them, PlutusV2 258, PlutusV1 184 and PlutusV4 41 (214 distinct scripts), with
304,330 spending, 26,788 rewarding, 319 minting, 177 certifying, 7 guarding and 2 proposing
redeemers; 41 have sub-transactions.

The protocol version (12.0) and the cost models in force at the tip, as `cardano-cli query
protocol-parameters` gave them, are those of the genesis files: PlutusV1 to PlutusV3 those of the
Alonzo genesis's `extraConfig` (332, 332 and 350 values), PlutusV4 the Dijkstra genesis's (251).
They were in force over the whole chain: a parameter changes only when a governance action is
enacted (`ENACT`, `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Enact.hs:83-96`, at the epoch
boundary of Dijkstra's `EPOCH`, `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Epoch.hs:165-192`,
cardano-ledger `9df9f98c`), and the chain's only two proposals are parameter changes of the
endorser block limits (keys 45 and 46, then 47 and 48, `eras/dijkstra/impl/cddl/data/dijkstra.cddl:727-730`),
both enacted, with no change of the cost models (key 18) and no hard fork initiation.

`leios_chain_scan.py sample` took 200 of those transactions: one of each of the 62 strata (where the
transaction comes from, its validity flag, the languages of the Plutus scripts it is given, the
purposes of its redeemers, whether it has sub-transactions and a validity interval), the one with the
least cost per script new to the sample, its cost being its size times its number of redeemers; then,
round by round over the strata, the same among the transactions of at most two redeemers and at most
twice the median size of their stratum, which keeps the fixture small. The rule is deterministic, and
`cases` collected the outputs the sample spends in a second reading of the chain. On 2026-11-16 a later revision of
the scanner, the one committed with the first image check, read the same database again and wrote
the same scan, sample and `cases.json`, byte for byte.

## Files

`cases.json` is the input of the [chain context oracle](../../../scripts/oracle/leios-testnet/README.md#the-chains-own-transactions-54)
as `leios_chain_scan.py cases` wrote it: the chain's protocol version (12.0), the cost models of
PlutusV1 to PlutusV4 in force, the system start and slot length of its Shelley genesis, and for each
transaction its place on the chain (block, slot, a ranking block's own or an endorser block's, and
which), its recorded validity flag, its bytes as the block or endorser block holds them, and the
outputs its inputs, collateral and reference inputs spend, each encoded as the chain holds it.
`results.json` is the oracle's output: for each transaction every context of the top-level
transaction and of each sub-transaction, the `ToCBOR PlutusWithContext` encoding (the protocol
version, the script with its language, the arguments, the redeemer's execution units and the cost
model), in the order the reference collects them. `provenance.json` binds both with the chain's
tip and the hash of the extraction script as it was when it extracted them (not the current
script's), and binds the oracle (`oracle`): the immutable ID of the image
that answered, without network, and the digests of its harness, package, cabal project and
executable, measured in the image by [`chain/run.py`](../../../scripts/oracle/leios-testnet/README.md#the-chains-own-transactions-54)
before it answered, with the ledger and cardano-base checkouts at the commits the project pins and
unchanged. `python3 -I scripts/oracle/leios-testnet/chain/run.py --verify-only` checks the fixture
against that record without Docker; on 2026-11-16 the image, so checked, answered the cases again
with these `results.json` bytes. On 2026-11-20 the harness was changed to write a sub-transaction's
collection failure too (#85), which none of these transactions has; the rebuilt image answered the
cases with `cases.json` and `results.json` byte for byte these, and its build was accepted anew
(`--accept-build`), the reason and the previous build's digests recorded in the provenance
(`buildAccepted`).

The 200 transactions (89 of ranking blocks, 111 of endorser blocks; slots 292,839 to 2,562,177; 28
with sub-transactions, 61 with a validity interval) run 273 scripts: 68 PlutusV1, 54 PlutusV2, 117
PlutusV3 and 34 PlutusV4: 62 distinct scripts, 7, 10, 42 and 4 of the 7, 13, 190 and 4 that the
chain's transactions with redeemers are given, so every PlutusV1 and PlutusV4 one; for 108 minting,
103 spending, 40 rewarding, 13 certifying, 7 guarding and 2 proposing purposes, 18 of them in
sub-transactions. The reference collected every transaction's contexts without a failure.

## What the suite checks

`LeiosChainContextsSuite` reads each transaction and its outputs as the node does and collects its
scripts with `DijkstraScriptContexts.transaction`, the slots turned to POSIX time from the chain's
system start: the same scripts, of the same languages and hashes, in the same order, each for the
same purpose (the input it spends, the policy it mints, the account it withdraws from, the voter, or
the index of the certificate, proposal or guard, read from the reference's arguments for every
language), with the same redeemers and execution units as the reference, at the top level and in
every sub-transaction; and each PlutusV4 script's argument, as `DataSerialisation` writes it, equal
byte for byte to the reference's argument bytes in its `PlutusWithContext` encoding. Every
collection of the reference must have succeeded, with an explicit list of contexts. The node builds
no context for a PlutusV1 to PlutusV3 script in a Dijkstra transaction (it collects them as
`Untranslated`, [the compatibility page](../../../docs/compatibility/plutus-v4-dijkstra.md)), so only
those arguments are not compared here; the suite reports how many, requires every PlutusV4 argument
of the reference to be compared, and fails on a PlutusV4 script collected without a context. The arguments themselves are
evaluated, with the reference's verdicts, by
[leios-testnet-chain-scripts](../leios-testnet-chain-scripts/README.md).

`DijkstraPlutusContextSuite` builds every one of the 273 contexts on the era types, each transaction annotated by
`mkDijkstraStAnnTopTx` and `mkDijkstraStAnnSubTx`, and compares each level's collection with the reference's: each
context's serialized arguments byte for byte, and the rest of its `PlutusWithContext` record (the protocol version,
language, script, units and cost model) field by field, Plutus V1 to V3 included.

## Licence

Public chain data of a test network, read from a node that followed the testnet as a client that
submits nothing; [NOTICE](../../../NOTICE) records it with the other captured chain data. The raw
database and logs of that node are not published.
