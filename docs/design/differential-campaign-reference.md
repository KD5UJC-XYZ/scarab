<!-- SPDX-License-Identifier: Apache-2.0 -->
# A differential campaign against the reference: what the reference does

**Status**: Proposal, 2026-11-16, the reference claims of the [differential campaign](differential-campaign.md)
([#922](https://github.com/KD5UJC-XYZ/scarab/issues/922)), which cites them by their IDs (R1 to R25, N1 to N16); its
sections keep the campaign's numbering (§3). All of it is derived from source, not from a capture; §3.5 lists the
claims with their pins.

Pins. The claims cite the `11.1` reference build, which `--reference-build` selected by default on 2026-11-16 (ADR
0001's [amendment of
2026-11-14](../adr/0001-haskell-node-as-reference.md#amendment-2026-11-14-the-newest-release-re-pinned-in-stages) says
when that moves): cardano-node 11.1.3; ouroboros-consensus 82ecba32; ouroboros-network c45735a5 (ouroboros-network
1.2.0.0); cardano-ledger f649f975, and for the files of cardano-ledger-shelley 1.19.0.1 cardano-ledger 4c81e909 (the
files cited here are identical at f649f975). They were read in checkouts of those revisions, 4c81e909 from its git
objects. Nothing was read at the `11.2` build's pins (ouroboros-consensus 808e4005, ouroboros-network 4646039f,
cardano-ledger 29a7d656): there every claim is not checked (the campaign's
[Q1](differential-campaign.md#10-risks-and-open-questions)).
[`docs/data/reference-pins.json`](../data/reference-pins.json) has every pin. Paths are abbreviated: consensus files
from `Ouroboros/Consensus/` in their package (`ouroboros-consensus`, its `-cardano` `shelley` sublibrary,
`-diffusion`), network files from `Ouroboros/Network/` (the `protocols` sublibrary and the main library), ledger files
from `Cardano/Ledger/` in their era's package, and cardano-node files from `cardano-node/src/Cardano/Node/`.

## 3.1 A transaction from a local client

The LocalTxSubmission server adds the transaction to the mempool and answers `SubmitSuccess`, or `SubmitFail` with the
mempool's `ApplyTxErr` (R1). For the Cardano block that error is the hard-fork wrapper: an error from one era, or
"wrong era"; in a Shelley-based era it is the ledger's `ApplyTxError`, in Conway a non-empty list of
`ConwayLedgerPredFailure` (R2). The mempool judges against the ledger state at its tip ticked to the slot after the
tip, not to the wall clock (R3), with every transaction at index `minBound` (R21). It first measures the transaction
against the per-transaction limits (its size, and from Alonzo on its units, by the measures' names; N15) and refuses
at once one that fails; only then does it wait until the transaction fits, and then validate it (R4, R21). The mempool
follows the chain through a background thread, so just after a block it can still judge against the previous tip
(R19); a LocalTxMonitor snapshot shows its slot but not its tip's hash (R22), and a local ChainSync follower does not
see every change of the selection (R23), while the chain-selection trace does see every fork switch (R24). In Conway
the MEMPOOL rule refuses, before LEDGER, a transaction whose inputs are all spent and, below a protocol version its
comment names as 11, votes by unelected committee members (R6): a mempool verdict can differ from the verdict on the
same transaction in a block.

Validation is timed. With the node's default limits, a Conway transaction whose validation finishes after more than
1 s is refused, whoever submitted it, with an error whose text holds the measured duration and the transaction id; a
validation cut off at 1.5 s is refused the same way for a local client and raises an exception for a remote peer.
Shelley to Babbage, and Dijkstra, have no such error: there a validation that finishes late is judged as any other,
and one cut off raises an exception for a local client too (R5). That the node hands these limits to the mempool is
not checked (N12). Such a verdict depends on the machine's speed. Scarab's mempool has no such timeout
([metrics](../running/metrics.md); [#340](https://github.com/KD5UJC-XYZ/scarab/issues/340)).

The transaction is decoded inside the message decoder (R7): a transaction the reference cannot decode gets no
`SubmitFail`. That the connection then ends is not checked (N2).

## 3.2 A transaction from a peer

The TxSubmission2 inbound side (logic V1) adds what it fetched to the mempool, traces and counts the refused ones, and
sends back no verdict (R8). A peer learns no verdict; the campaign reads transaction verdicts from LocalTxSubmission
only.

## 3.3 A header from a peer

The ChainSync client validates each header and, on failure, ends the connection with `HeaderError` (R9). Validation
includes the envelope: a protocol version against the node's maximum, the header's size against `maxBHSize` and the
declared body size against `maxBBSize` (R10). The version is the header's own in the TPraos eras (Shelley to Alonzo)
and the ledger view's in the Praos eras (Babbage on) (R10, R20), so in a Praos era the envelope does not read the
version a header declares; what else reads it is N14. A header whose slot starts more than the clock skew (2 s by
default) after its arrival ends the connection; one within the skew is held until its slot starts (R11).

## 3.4 A block from a peer

A fetched block goes to chain selection with a punishment for its sender: an invalid predecessor always ends the
connection; an invalid block itself does too, unless diffusion pipelining is on and its header meets the
tentative-header criterion, when it is forgiven (R12). Chain selection validates a block only as part of a candidate
that is preferable to the current chain; otherwise the block is stored and nothing is judged (R13). A block that
fails is traced `InvalidBlock`, recorded with its `ExtValidationError` (ledger or header), and its sender punished;
a block already recorded invalid is ignored and its sender punished (R14). A ChainSync client whose candidate holds a
known-invalid block ends the connection, skipping the candidate's tip under pipelining, though it can miss one when the
candidate changes meanwhile (R15). The node traces
`ChainDB.AddBlockEvent.AddBlockValidation.InvalidBlock` at Error, with the point and the error's `Show` text (R16).
The body checks of BBODY compare the body's size and hash with the header's, and add the units of the block (Alonzo)
and the size of its reference scripts (Conway) (R17).

So a block is judged only on a chain preferable to the node's own, once its header passes validation and the in-future
check, and an invalid tip can be forgiven without a disconnect. A block probe needs a peer that serves the node's only
chain, signs headers with a pool's keys in slots it leads that have started, and reads the verdict from the tip and
the `InvalidBlock` trace, not from a disconnect.

## 3.5 The claims

| ID | Claim | Pin, file and lines | Checked |
|---|---|---|---|
| R1 | LocalTxSubmission's server answers `SubmitSuccess`, or `SubmitFail` with the mempool's `ApplyTxErr`. | consensus 82ecba32 `MiniProtocol/LocalTxSubmission/Server.hs` 26-38 | yes |
| R2 | `HardForkApplyTxErr` is `HardForkApplyTxErrFromEra` or `HardForkApplyTxErrWrongEra`; a Shelley-based era's `ApplyTxErr` is the ledger's `ApplyTxError`; Conway's is `NonEmpty (ConwayLedgerPredFailure ConwayEra)`. | consensus 82ecba32 `HardFork/Combinator/Mempool.hs` 61-66, `shelley` `Shelley/Ledger/Mempool.hs` 154; ledger f649f975 `eras/conway/impl/src/Cardano/Ledger/Conway.hs` 51, 4c81e909 `eras/shelley/impl/src/Cardano/Ledger/Shelley/API/Mempool.hs` 181 | yes |
| R3 | The mempool ticks the tip's ledger state to the slot after the tip (`ForgeInUnknownSlot`); at Origin, to the first possible slot. | consensus 82ecba32 `Mempool/Impl/Common.hs` 295, 319-338 | yes |
| R4 | `addTx` first measures the transaction (`txMeasurePhase1`) and refuses at once one whose measure fails; then, if it does not fit, it waits for the mempool's size to change and tries again; only then does it validate. | consensus 82ecba32 `Mempool/API.hs` 142-145; `Mempool/Update.hs` 198-235 | yes |
| R5 | Defaults soft 1 s, hard 1.5 s, capacity 5 s. Where the era has the error, a validation that finishes over the soft limit is refused, whoever submitted it, as `ConwayMempoolFailure` with the text `"MempoolTxTooSlow (<duration>) <txid>"`, and one cut off at the hard limit is refused so for a local client; otherwise a cut-off raises `MkExnMempoolTimeout`. Only Conway has the error: Shelley to Babbage and Dijkstra have none. | node 11.1.3 `Configuration/POM.hs` 889-897; consensus 82ecba32 `Mempool/Update.hs` 127-130, 237-296, `shelley` `Shelley/Eras.hs` 169-220 | yes, except that the node passes the limits to the mempool (N12) |
| R6 | Conway's MEMPOOL rule refuses all-inputs-spent transactions and, unless `hardforkConwayDisallowUnelectedCommitteeFromVoting` holds, votes by unelected committee members, then runs LEDGER. | ledger f649f975 `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Mempool.hs` 103-139 | yes, except the predicate's definition: protocol version 11 is from the comment at 124-125 |
| R7 | The LocalTxSubmission codec decodes the transaction inside `MsgSubmitTx`'s decoder. | network c45735a5 `Protocol/LocalTxSubmission/Codec.hs` 148-169 | yes; what follows a failed decode is N2 |
| R8 | TxSubmission2 inbound V1 traces and counts refused transactions and sends back no verdict; the protocol goes on. | network c45735a5 `TxSubmission/Inbound/V1.hs` 313-329 | yes; that V1 is the default is N10 |
| R9 | The ChainSync client ends the connection with `HeaderError` when `validateHeader` fails; that exception brings down every mini-protocol with the peer. | consensus 82ecba32 `MiniProtocol/ChainSync/Client.hs` 1893-1925, 2262-2266 | yes |
| R10 | `validateHeader` runs `validateEnvelope`, which runs the Shelley-based envelope check: a protocol version against the node's maximum, the header size, the declared body size. Under Praos the version is the ledger view's (`plvProtocolVersion`) and `maxBHSize` is the ledger view's; under TPraos the version is the header's (`bprotver`). | consensus 82ecba32 `HeaderValidation.hs` 376, 508-514; `shelley` `Shelley/Ledger/Ledger.hs` 788-789, `Shelley/Protocol/Praos.hs` 59-72, `Shelley/Protocol/TPraos.hs` 66-77, `Shelley/Protocol/EnvelopeChecks.hs` 60-91 | yes |
| R11 | A header more than the clock skew early ends the connection (`InFutureHeaderExceedsClockSkew`); within the skew the client waits for the slot's onset; the default skew is 2 s. | consensus 82ecba32 `MiniProtocol/ChainSync/Client/InFutureCheck.hs` 154-175, 206-207; `Client.hs` 1755-1776; diffusion `Node.hs` 1108 | yes |
| R12 | A fetched block goes to `addBlockAsync`; an invalid prefix ends the connection; an invalid block itself does unless pipelining is on and the tentative-header state forgives it. | consensus 82ecba32 `MiniProtocol/BlockFetch/ClientInterface.hs` 176-227; `Storage/ChainDB/API/Types/InvalidBlockPunishment.hs` 49-92 | yes |
| R13 | Only candidates through the block that are preferable to the current chain are validated; otherwise `StoreButDontChange`. | consensus 82ecba32 `Storage/ChainDB/Impl/ChainSel.hs` 660-684, 699-778 | yes |
| R14 | A failed candidate block is traced `InvalidBlock`, added to the invalid set and its sender punished; a known-invalid block is ignored and its sender punished. `ExtValidationError` is a ledger or a header error. | consensus 82ecba32 `ChainSel.hs` 414-444, 1311-1347, 1359-1365; `Ledger/Extended.hs` 58-60; `HeaderValidation.hs` 435-439 | yes |
| R15 | The ChainSync client ends a connection whose candidate holds a known-invalid block, skipping the tip under pipelining. Its comments say it can miss a block when the candidate changes meanwhile. | consensus 82ecba32 `Client.hs` 1642-1663, 2085-2156 | yes |
| R16 | cardano-node traces `InvalidBlock` with `kind`, `block` and `error` (`showT`), at Error. | node 11.1.3 `Tracing/Tracers/ChainDB.hs` 1307-1310, 1329-1334 | yes; that the default options hide it is N11 |
| R17 | BBODY: `WrongBlockBodySizeBBODY`, `InvalidBodyHashBBODY` (Shelley); `TooManyExUnits` (Alonzo); `BodyRefScriptsSizeTooBig` (Conway). | ledger 4c81e909 `eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Bbody.hs` 96-101; f649f975 `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Rules/Bbody.hs` 63, `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Bbody.hs` 103 | yes |
| R18 | The mempool's refusal trace, `TraceMempoolRejectedTx`, carries the transaction and its error. | consensus 82ecba32 `Mempool/Update.hs` 215-220, 266-275 | yes; what the node's log prints of it is N13 |
| R19 | The mempool re-syncs with the ledger in a background watcher, apart from chain selection: its state can lag the selected chain. | consensus 82ecba32 `Mempool/Init.hs` 66-77; `Mempool/Update.hs` 558-575 | yes |
| R20 | Shelley, Allegra, Mary and Alonzo blocks are TPraos blocks; Babbage, Conway and Dijkstra blocks are Praos blocks. | consensus 82ecba32 `-cardano` `Cardano/Block.hs` 250-256 | yes |
| R21 | A Shelley-based transaction is applied with the ledger's mempool environment: the slot the mempool ticked to and the index `minBound`, on the state left by the transactions before it. Its phase-1 measure is `txInBlockSize` from Shelley to Mary and `txMeasureAlonzo` (an `AlonzoMeasure`) from Alonzo on. | consensus 82ecba32 `shelley` `Shelley/Ledger/Mempool.hs` 301-323, 522-542, 668-676, 752-770; ledger 4c81e909 `eras/shelley/impl/src/Cardano/Ledger/Shelley/API/Mempool.hs` 368-377 | yes, except the measures' bodies: what each measures and refuses is N15 |
| R22 | LocalTxMonitor's acquired snapshot gives its slot (`snapshotSlotNo`) and its transactions, not the hash of the tip it rests on; the mempool resyncs unless both the tip's hash and the slot are unchanged. | consensus 82ecba32 `MiniProtocol/LocalTxMonitor/Server.hs` 49-63; `Mempool/Update.hs` 587-596 | yes |
| R23 | A ChainDB follower (local ChainSync) reads successors from the current chain, and a fork switch moves it only when its position is on the suffix the switch removes: it does not see every change of the selection. | consensus 82ecba32 `Storage/ChainDB/Impl/Follower.hs` 428-439, 540-564 | yes |
| R24 | Every change of the selection's tip goes through `switchTo`, which writes the chain and then traces `AddedToCurrentChain` when nothing was rolled back and `SwitchedToAFork` otherwise; the only other write removes the oldest header. cardano-node traces both at Notice (or a ledger event's higher severity) with the new tip, under `ChainDB`, which its default options show from Info; it sends `AddedToCurrentChain` without ledger events through a built-in frequency limiter, and `SwitchedToAFork` through none. | consensus 82ecba32 `Storage/ChainDB/Impl/ChainSel.hs` 898-972, `Storage/ChainDB/Impl/Background.hs` 217-226; node 11.1.3 `Tracing/Tracers/ChainDB.hs` 74-89, 640-690, 813-821, `Tracing/Tracers.hs` 118-120, `Tracing/DefaultTraceConfig.hs` 27-28 | yes; that a devnet node's log shows every such line and the campaign reads it is N18 |
| R25 | The hard-fork combinator refuses a header of the wrong era in its envelope check (`HardForkEnvelopeErrWrongEra`) and a block of the wrong era when applying it (`HardForkLedgerErrorWrongEra`): a block of the old era after a boundary is never adopted. | consensus 82ecba32 `HardFork/Combinator/Ledger.hs` 243-255, 336-347 | yes; which of the two a node reports is N16 |

## 3.6 Not checked

Each is confirmed, by reading the pinned source or by a run, in the campaign's stage named
([§9](differential-campaign.md#91-stages)), before the campaign relies on it.

- **N1** (S1). What LocalTxSubmission answers for a transaction whose validity flag says its scripts fail.
- **N2** (S1). That a LocalTxSubmission message whose transaction fails to decode ends the connection on both nodes,
  and how each node reports it.
- **N3** (S2). What `cardano-cli` prints for a refusal, beyond what `ntc` already compares.
- **N4** (S4). That both nodes adopt and validate a chain whose slots lie far behind the wall clock, and how soon each
  reconnects to a local root it disconnected for an invalid block.
- **N5** (S4). Chain selection between forks of equal length.
- **N6** (S4). BlockFetch's check of a fetched body against its header, and a block that fails to decode.
- **N7** (S2). How the reference decodes a hash of the wrong length outside delegation certificates
  ([§5.3](differential-campaign.md#53-the-first-target-hashes-of-the-wrong-length-outside-delegation-certificates)).
- **N8** (before the campaign judges against the `11.2` build, Q1). Everything in §3 at that build's pins.
- **N9** (S4). When the hard-fork mempool answers `HardForkApplyTxErrWrongEra`; R2 shows only that it exists.
- **N10** (S1). That TxSubmission's V1 logic is the 11.1.3 default (R8), as [metrics](../running/metrics.md) says.
- **N11** (S4). That 11.1.3's default trace options hide `InvalidBlock` (R16), as `scripts/devnet/reference_tracing.py`
  3-16 says, and what its log prints of a refused block.
- **N12** (S1). That the node passes its mempool time limits to the mempool (R5).
- **N13** (S1). What the node's log prints of a refused transaction (R18).
- **N14** (S4). What, if anything, reads the protocol version a Praos header declares (R10).
- **N15** (S1). What `txInBlockSize` and `txMeasureAlonzo` measure, and which limits they refuse (R21).
- **N16** (S4). What the reference answers for a block of the old era served after an era boundary.
- **N17** (S1). That a reference node started with no peer on a recorded chain database opens it at its tip and
  judges a local submission at the slot after that tip, whatever the wall clock (R3).
- **N18** (S1). That a devnet node's log, under the options `scripts/devnet/` sets, shows every `SwitchedToAFork`
  line (R24), and that the campaign parses it.
