# Leios testnet block decoding oracle

**Status: Current** — optional offline reference oracle for
[#711](https://github.com/KD5UJC-XYZ/scarab/issues/711).

The Leios reference is the build the public Leios testnet runs (decided 2026-11-03 on #711): the
node of release `rc-2026w44` of `input-output-hk/ouroboros-leios`, whose `MinNodeVersion` the
testnet's live `config.json` names (`11.2.0.164-rc-2026w44`). That release's `flake.lock` pins
`cardano-node` `b004fe0c50913dcdaa827a98e68a43d0ccce152a` (branch `leios-prototype`), and the
release's Docker image `ghcr.io/input-output-hk/ouroboros-leios/cardano-node-testnet:rc-2026w44`
reports that revision (`cardano-node --version`: `git rev b004fe0c…`). Its `cabal.project` pins
cardano-ledger `9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c`, cardano-base
`c1e1ed8d7ae39c111c73fc5f59ac37ce5933b83c`, ouroboros-consensus
`2416493bfdbe93b761b3a4bb1ccf2b4a913e5e4b` and ouroboros-network
`2f1c154f248659b89523d5679ca532f442e7ed2a`. The release notes say the wire formats are those of the
previous release, `rc-2026w43`, whose node pins the same ledger and cardano-base.

The harness builds the ledger and cardano-base packages from those revisions (`cabal.project`
copies the node's repository, index state and `source-repository-package` stanzas) and reads:

- a block as BlockFetch carries it, `[tag, block]`, with the envelope of `decodeDiskHfcBlock`
  (ouroboros-consensus 2416493b,
  `ouroboros-consensus-cardano/src/ouroboros-consensus-cardano/Ouroboros/Consensus/Cardano/Node.hs:185-198`):
  `enforceSize "CardanoBlock" 2`, `CBOR.decodeWord`, and for tag 8 the ledger's
  `Block (Leios.Header StandardCrypto) DijkstraEra` at Dijkstra's decoder version 12 (`Praos2`'s
  header, `Protocol/Praos2.hs:57`), for tag 7 `Block (Praos.Header StandardCrypto) ConwayEra` at
  Conway's 9, each with the annotated decoder run over the whole input, as `decodeShelleyBlock` does
  (`ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Block.hs:332-339`:
  `eraDecoder @era`, `(. Full) . runAnnotator <$> decCBOR`). `cardanoBlock` in `Main.hs` writes
  those lines out for tags 7 and 8 and refuses any other tag with the same `DecoderErrorUnknownTag`
  (the harness has no Byron to Babbage cases): for those two tags it is the consensus decoder line
  for line, which a review against the pinned source confirmed. Consensus itself is not compiled:
  the decoders those lines call are all the ledger's, and building the whole consensus tree needs
  native libraries (`liburing` for its `blockio` dependency) the base image does not have;
- a Leios header alone, `decodeFullAnnotator` at version 12, as `decodeShelleyHeader` reads the
  header inside ChainSync's era envelope.
- a transaction alone (#750), `decodeFullAnnotator` at version 12 of `decodeDijkstraTopTxInBlock`
  (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Tx.hs:175-182`), as the block body's `decodeSeq`
  reads each of its transactions: body, witness set, auxiliary data and sub-transactions.

- a transaction with the outputs it spends (`kind` "context", #54): the Plutus script contexts the
  ledger collects for it before evaluation (`mkDijkstraStAnnTopTx`,
  `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra.hs:113-169`), at protocol version 12.0 with a
  PlutusV4 cost model, each the `ToCBOR PlutusWithContext` encoding, or the collection's failure.
  The cases come from `leios_context_cases.py`; the
  [fixtures](../../../fixtures/reference/leios-testnet-contexts/README.md) retain them.

For an accepted block it records the header's hash, size, signed bytes (the header body's original
bytes, `getSignableRepresentation = originalBytes`, `Leios/BlockHeader.hs:164-165`) and fields, the
block body's hash and size as the ledger computes them, each transaction's id and validity flag, and
which certificates the body carries; for an accepted transaction its id, validity flag, the keys of
its witness set's script and datum maps, its redeemers in key order with original data and execution
units, and the corresponding fields of its sub-transactions; for a refused one the complete
failure. No rule, signature or
certificate is checked. The [fixtures](../../../fixtures/reference/leios-testnet-blocks/README.md)
retain the encodings, verdicts and pins.

The image starts from the [context-reference image](../plutus/context/README.md), for its GHC
(9.10.3; the release builds with 9.6), cabal and native libraries; build that first, then:

```sh
docker build -f scripts/oracle/leios-testnet/Dockerfile -t scarab-leios-testnet:local .
```

The cases come from `leios_cases.py`, run as the fixture README shows.

## The chain's own transactions (#54)

`leios_chain_scan.py` reads the database of a node of the testnet's build (the release's image
`ghcr.io/input-output-hk/ouroboros-leios/cardano-node-testnet:rc-2026w44`, run as a following node
with the [committed configuration](../../../fixtures/leios/testnet/config/)) and finds every
transaction the ledger applies with a redeemer. Those are a ranking block's own transactions and,
for a block carrying a Leios certificate, first the transactions of the endorser block its
predecessor announced. At ouroboros-consensus `2416493b` a certifying block's body on the wire
carries no transactions; applying it checks the certificate, then reads the announced endorser
block's closure from the LeiosDB and applies its transactions in the endorser block's order, then
the block (`Ouroboros/Consensus/Storage/LedgerDB/Forker.hs:623-687`, `resolveAndApplyLeiosClosure`
at `:1066-1104`). The Dijkstra instance decodes each closure transaction in the block-body encoding,
the validity flag included (`ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Leios.hs:167-177`;
`serializeTxForLeios`, `LeiosDemoTypes.hs:1234-1245`), and applies it with the ledger's `LEDGER`
rule at `ValidateNone` (`Leios.hs:210-252`): the trust comes from the certificate, whose voters
validated the closure in full, scripts included (`Forker.hs:897-913`; `validateEbClosure`,
`LeiosVoting.hs:446-473`).

`chain/` is a second harness of the same project (its image `scarab-leios-testnet-chain:local`
derives from `scarab-leios-testnet:local`): the `kind` "context" above with the chain's environment
in place of the fixed one, the protocol version and cost models in force and the chain's system
start and slot length, so that a transaction's contexts are those its scripts were given on the
chain. `mkDijkstraStAnnTopTx` and `mkDijkstraStAnnSubTx` read nothing else of the protocol parameters
(`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra.hs:111-219`). Build it after the parent:

```sh
docker build --label xyz.kd5ujc.scarab.kind=oracle \
  -f scripts/oracle/leios-testnet/chain/Dockerfile -t scarab-leios-testnet-chain:local .
```

The [chain contexts fixture](../../../fixtures/reference/leios-testnet-chain-contexts/README.md)
says how its cases were extracted, and the
[chain scripts fixture](../../../fixtures/reference/leios-testnet-chain-scripts/README.md) how the
plutus 1.71.0.0 oracle judged them.
