# Ouroboros Leios: design note (roadmap N1)

Status: design note plus the pure N2 crypto (section 9). **Leios is not implemented in this
repository beyond BLS keys, votes, certificates and two wire codecs**, and nothing here has been
checked against a Leios node beyond a handshake and one refused exchange (sections 8 and 10). The protocol is
still moving: every statement below carries the source and revision it was read from, and where
the sources disagree the disagreement is recorded rather than resolved. Unofficial research
software, not audited. Read together with [architecture.md](architecture.md),
[compatibility.md](compatibility.md) and [roadmap.md](roadmap.md) (groups L and N).

Dates are 2026-10-14 (the day of reading) unless stated. "Linear Leios" is the variant in
CIP-164; the research paper's three-block-type design (input blocks, endorser blocks, ranking
blocks) is not what is being built.

## 1. Sources and revisions

Local clones live under `.workspace/data/2026-10-14-leios/` (shallow, read-only use); upstream
Haskell clones under `.workspace/data/2026-10-14-upstream/`.

| Source | Revision read | Notes |
|---|---|---|
| [CIP-164 "Ouroboros Linear Leios"](https://github.com/cardano-foundation/CIPs/tree/master/CIP-0164) (`cardano-foundation/CIPs`) | status **Proposed**, created 2025-03-07; last change to the CIP `a2cac18039` (2026-09-15, PR #1250 "voting consistent with implementation + complete parameter list"); repository master `20776563ab` (2026-10-13) | Earlier revisions: #1078 (original), #1167 (2026-06-09, mini-protocols refined after Buidler Fest), #1196 (committee selection, stake-based truncation), #1250. 3,480 lines; CDDL in Appendix B |
| [Leios R&D repository](https://github.com/input-output-hk/ouroboros-leios) | `main` `614b10d2` (2026-10-13) | `docs/leios-design` (node-level design), `docs/ImpactAnalysis.md`, `docs/threat-model.md`, `docs/status-report-2026q2.md` (dated 2026-06-24, provisional), `crypto-benchmarks.rs` (Rust BLS reference and `Specification.md`), `sim-rs`, `docs/haskell-simulator`, `leios-trace-hs`/`leios-trace-verifier`, `testnet/` (relay setup and pinned config). The CDDL drafts that used to live in `docs/cddl` were removed; last at `52f416a2` |
| [Protocol-level Agda specification](https://github.com/input-output-hk/ouroboros-leios-formal-spec) | tags V1.0, V1.1, V1.2; `main` `55b54f9f` (2026-10-01); the CIP links `formal-spec/Leios/Linear.lagda.md` at V1.0 | The EB-must-not-be-empty voting rule is cited by the CIP at commit `c7f8d618` |
| [Ledger formalization](https://github.com/IntersectMBO/formal-ledger-specifications/issues/1295) (`IntersectMBO/formal-ledger-specifications` #1295, "[Leios] tracking issue") | issue closed 2026-10-01; work on the `leios-main` branch, fork `williamdemeo/formal-ledger-specifications`, design note #1297 (`docs/leios/design-note.md`) | "Leios Ledger Formalization (LLF)": new block fields, parameters, BLS key and proof of possession in pool registration, epoch committee, vote and certificate validity, application of a certified EB. Plan: six weeks in three stages; metatheory, `Computational` instances and extraction are deferred follow-ups |
| [Dijkstra era](https://github.com/IntersectMBO/cardano-ledger/tree/master/eras/dijkstra) (`cardano-ledger`) | local clone `a51efc77`; remote master `ba863cf5` (2026-10-14); `cardano-ledger-dijkstra` 0.5.0.0 changelog | Concrete CDDL: `eras/dijkstra/impl/cddl/data/dijkstra.cddl`. [PR #5872](https://github.com/IntersectMBO/cardano-ledger/pull/5872) "Add LeiosCert to DijkstraBlockBody" merged 2026-06-30 |
| [`cardano-base`](https://github.com/IntersectMBO/cardano-base) | master `9c964bec` (2026-10-13) | `cardano-crypto-leios` package (types `EbHash`, `LeiosCert`; scaffold PR #670); BLS12-381 DSIGN with proof of possession in `cardano-crypto-class` (`Cardano/Crypto/DSIGN/BLS12381/Internal.hs`), vectors in `cardano-crypto-class/bls12-381-test-vectors` |
| Consensus prototype: [`ouroboros-consensus` branch `leios-prototype`](https://github.com/IntersectMBO/ouroboros-consensus/tree/leios-prototype) | `cef41f6b` (2026-10-14) | Modules `LeiosDemoLogic`, `LeiosDemoTypes`, `LeiosTxCache`, `LeiosVoteState`, the SQLite `LeiosDB` (explanation in `docs/website/contents/explanations/leiosdb.md`), `Test/Consensus/Leios`, golden file `ouroboros-consensus-cardano/golden/cardano/leios/LeiosVote`. Many contributor branches (`ch1bo/leios-*`, `bladyjoker/leios-*`) |
| Network and node prototype branches | `ouroboros-network` `leios-prototype` `f0243fe6` (2026-10-11, mux demo only); `cardano-node` `leios-prototype` `715b8d2a` (2026-10-11) | The released reference node (11.1.3, 2026-09-29) has no Leios |
| [Cardano Blueprint](https://github.com/cardano-scaling/cardano-blueprint) | `main` `fe4cceb9` (2026-10-13) | `src/network/node-to-node/leios-notify` and `leios-fetch`: state machines and `messages.cddl`. **The most current wire description; it differs from the CIP text** (section 4) |
| Public Leios testnet "musashi" (closed alpha) | network magic 164; config served at <https://book.play.dev.cardano.org/environments-pre/leios/>; pinned copy at `ouroboros-leios/testnet/config` (`MinNodeVersion` 11.1.0.164-prototype-2026w36); the live `config.json` read on 2026-10-14 says `11.1.0.164-prototype-2026w38a` | Bootstrap relay `leios-node.play.dev.cardano.org:3001`; `systemStart` 2026-09-07T00:00:00Z; Dijkstra genesis hash `aa1238f5...41085`; also a faucet, dashboards, Docker images (`ghcr.io/input-output-hk/ouroboros-leios/cardano-node-testnet`) and relays that publish a `leios.db` other implementations can side-load (Porto workshop report, June 2026; owner's copy) |
| Independent implementations (existence only, not read in depth) | `txpipe/pallas` `pallas-network2/src/protocol/leiosnotify.rs`; `input-output-hk/leios-tools` (`net-rs` Rust node-side tooling with adversarial behaviours) | Useful cross-checks for codecs |
| Leios paper | <https://eprint.iacr.org/2025/1115.pdf> | Background only |

## 2. What Linear Leios changes

Leios is an overlay on Praos: the ranking-block (RB) chain, its fork choice, leader election, KES
and VRF are unchanged (the CIP says chain selection follows the densest chain rule as in Genesis,
and EBs never influence it). It adds more transaction capacity per unit of time:

1. A leader forges an RB as today and **may announce** one endorser block (EB) in the RB header.
   An EB is only a list of transaction references: a map from transaction hash to transaction size,
   at most 512 kB (CIP, Table 7), referencing up to 12 MB of transactions.
2. Peers fetch the EB and the transactions it references (most already in their mempools), validate
   them as a valid extension of the announcing RB, and a stake-selected **committee** votes.
3. Votes are small BLS signatures over the hash of the announcing RB header. A quorum is
   aggregated by the next RB producer into a **certificate**: a bitfield over the epoch's committee
   plus one aggregate signature.
4. A later RB, at least `ceil((3 L_hdr + L_vote + L_diff) / slotLength)` slots after the announcing
   RB (14 slots with the CIP's example 1 s + 4 s + 7 s, as `3 + 4 + 7`), may carry the certificate
   instead of transactions; the certified EB's transactions then enter the ledger. An RB carries
   either a certificate or its own transactions, never both. An EB whose certificate cannot be
   included in time is discarded. About half of the RBs will therefore still carry transactions
   directly (0.95^13 is about 51% at f = 0.05).

Timing rules (CIP, "Protocol Flow" and "Protocol Parameters"):

- equivocation detection `3 L_hdr` from the announcement; a node votes only if it saw the header
  within `L_hdr`, saw no equivocating header for the slot, finished validating before
  `3 L_hdr + L_vote`, the EB is announced by the latest RB on its current chain, the EB is a
  valid extension of the announcing RB, and the EB is non-empty;
- constraints that make Leios security reduce to Praos security: `Delta_reapply < Delta_applyTxs`
  and `Delta_EB^W < 3 L_hdr + L_vote + L_diff + (Delta_RB - Delta_applyTxs)`, with
  `L_diff >= Delta_EB^W + Delta_reapply - Delta_RB - 3 L_hdr - L_vote`;
- the three `L` values are wall-clock durations, not slots; only the RB-to-RB comparison rounds
  up to slots;
- at most two announcements per (slot, issuer) election are relayed; announcements whose
  operational-certificate issue number is below the settled ledger value or more than one above
  it are ignored (the CIP's section on OCINs; a hot-key leak is then tolerated for up to roughly
  36 h instead of 90 days).

Parameters (CIP Table 7 "feasible" values; the testnet uses smaller ones):

| Parameter | CIP Table 7 | Musashi `dijkstra-genesis.json` |
|---|---|---|
| committee size `N_c` | 900 | `leiosCommitteeSize` 900 |
| quorum `tau` | 0.75 (`0.5 < tau < sigma(N_c)`) | `leiosQuorumStakeThreshold` 0.75 |
| `L_hdr` | 1 s | `leiosAnnouncementPeriodLength` 1000 (ms) |
| `L_vote` | 4 s | `leiosVotePeriodLength` 4000 |
| `L_diff` | 7 s | `leiosDiffusionPeriodLength` 7000 |
| max EB size `S_EB` | 512 kB | `maxEndorserBlockReferencesSize` 100000 |
| max EB transaction bytes `S_EB-tx` | 12 MB | `maxEndorserBlockTxsSize` 1000000 |
| max EB reference-script bytes | 12 MB | `maxRefScriptSizePerEndorserBlock` 4000000 |
| max EB execution units | 2000G steps, 7000M memory | `maxEndorserBlockExecutionUnits` 100000000000 steps, 310000000 memory |

Dijkstra encodes these as protocol parameters 40 to 48 (48 is the per-EB reference-script size limit) in `protocol_param_update`, with times in milliseconds (`uint .size 4`)
and the committee size as `uint .size 2`.

### Committee and keys

- The committee for an epoch is the top `N_c` pools by active stake from the stake distribution
  at the epoch boundary, ties broken by pool id ascending; the pool's position is its seat index
  (`voter_id`). Every seat votes on every EB of the epoch with its pool's stake as weight. A
  pool that has no valid voting key holds a *keyless* seat and cannot sign; a certificate naming a
  keyless signer is invalid. Earlier designs (wFA^LS with local sortition) were dropped in PR #1196.
- Dijkstra's `LEDGER`-side implementation seats the committee in the `SNAP` rule on the fresh mark
  snapshot (changelog 0.5.0.0), and exposes it through a `DijkstraEraForecast`.
- Voting keys are BLS12-381 **MinSig**: verification key compressed G2 (96 bytes), signature and
  proof of possession compressed G1 (48 bytes). The proof of possession is mandatory (rogue-key
  defence). The key rides in the pool registration certificate as the optional
  `bls_key = [bytes .size 96, bytes .size 48]` field of `pool_params` (CDDL identical in the CIP
  and in `dijkstra.cddl`). Activation is aligned with VRF key activation (epoch boundary).
  Rotation (about every 90 days) is **not settled**: pool re-registration versus a block-carried,
  opcert-style key is open (status report, "Key registration and rotation").
- `cardano-base` uses the IETF "POP" ciphersuite with the canonical `BLS_SIG_` and `BLS_POP_`
  domain-separation tags fixed internally per curve variant. The older Rust specification in
  `ouroboros-leios` describes the proof of possession as a 96-byte pair; the CIP and Dijkstra CDDL
  use 48 bytes. Treat the encodings in `dijkstra.cddl` plus the `cardano-base` vectors as
  authoritative for N2 and confirm by cross-checking vectors, not by reading prose.

### Votes and certificates

```
leios_vote        = [announcing_rb_hash : hash32, voter_id : uint, vote_signature : bytes .size 48]
leios_certificate = [signers : bytes .size (0 .. 8192), signature : bytes .size 48]   ; dijkstra.cddl
```

The signed message is `announcing_rb_hash`. Certificate verification (CIP): format; voters are
committee members; signatures valid; summed stake of signers at least `tau` times total active
stake; verified against the announcing RB determined from the certifying RB's chain context (not
carried in the certificate). The signer bitfield is `ceil(N/8)` bytes (113 bytes for 900 seats).

## 3. Block and ledger format (Dijkstra)

From `dijkstra.cddl` at cardano-ledger `a51efc77` (the CIP's Appendix B diff is older and
differs in shape):

- `header_body` ends with `..., block_body_contains_leios_cert : bool,
  eb_references_announcement : [eb_hash : hash32, eb_size : uint .size 4] / nil`. The CIP draft
  instead has optional `announced_eb`, `announced_eb_size` and `certified_eb` fields. The header also
  carries `header_version_info = [highest_supported_major_version, self_reported_software_tag]`.
- `block_body_hash` carries the comment "merkle triple root", unchanged from earlier eras'
  CDDL; whether Dijkstra changes the computation (it adds a Leios certificate and a Peras
  certificate to the body) is for L1 to confirm against the ledger source.
- `block_body = [transactions, leios_certificate / nil, peras_certificate / nil]`.
- `pool_params` gains optional `bls_key`.
- Dijkstra changelog 0.5.0.0: new `LeiosBbodySignal` for `BBODY`, `LeiosEraBlockHeader` class,
  `kesMaxKeyAgeEpochs` (how long a registered voting key stays valid from the KES parameters),
  `seatInitialLeiosCommittee` taking a `TransitionConfig`, `encodeLeiosCert`/`decodeLeiosCert`,
  nine Leios parameters.
- Ledger application: a certified EB's transactions are validated against the state of the
  announcing RB (the certifying RB's predecessor); the "no validation" reapplication path is what
  makes adoption affordable (early ledger benchmarks: about 5x for value transactions, 50x for
  script transactions, status report). The ledger-side Agda rules are the LLF (`LEDGERS`,
  `BBODY`, CHAIN threading with pending announcement and timing window).

Activation (CIP "Versioning" and the design document): a new major protocol version and a new
ledger era (Dijkstra) because the block format changes (CIP-84 applies). The design document
lists two fall-backs if Leios and Dijkstra do not line up: Leios in the following era, or the
format in Dijkstra with validation failing until an intra-era hard fork enables it (feature
flags, as Peras does). Peras and Leios in one hard fork is possible but discouraged. No
mainnet date or protocol version is fixed; the CIP's own acceptance list (load tests, blueprint
conformance tests, formal spec, DeltaQ model, BLS key rotation specified, agreed parameters,
peer-reviewed implementation, months of open testnet, infrastructure readiness, executed hard
fork) is all unchecked.

## 4. Network

### 4.1 What the CIP says (revision `a2cac18039`)

Four new mini-protocols: `LeiosAnnounce`, `LeiosVotes`, `LeiosBlockNotify` (all
`StIdle --RequestNext(N)--> StBusy{tokens}` credit-based streams, intended usage: request 1000
then 100 more per 100 received, pipelined) and `LeiosFetch` (request/response: multi-block
request with all transactions for syncing, and a block-transactions request addressed by index
bitmaps, a roaring-bitmap style byte string of 9-octet slices). ChainSync headers carry the
announcement fields; BlockFetch bodies carry certificates; TxSubmission is unchanged. The
requirements are freshest-first delivery, Praos independence (Praos traffic must not be delayed
by Leios), existing resources and tolerable complexity. Nodes must also fetch EBs of competing
forks (those within the security parameter).

### 4.2 What the implementation and Blueprint say (newer)

`cardano-blueprint` `fe4cceb9` documents two protocols, which is what the prototype node speaks:

- **LeiosNotify, mini-protocol number 18.** States `StIdle` (client agency), `StBusy` (server),
  `StQuit` (server). Messages (CBOR arrays tagged by integer): `MsgDone [0]`, `MsgQuit [1]`,
  `MsgCanceled [2]`, `MsgLeiosNotificationRequestNext [3]`, `MsgLeiosBlockAnnouncement [4,
  announcement]` (the announcement is exactly the RB header bytes, with its KES signature),
  `MsgLeiosBlockOffer [5, point, eb_size]`, `MsgLeiosBlockTxsOffer [6, point]`, `MsgLeiosVotes
  [7, [1* vote]]`; `point = [slot, eb_hash]`. Votes and announcements are folded into one
  notification stream; a note says a separate votes protocol could take votes away later.
- **LeiosFetch, mini-protocol number 19.** `StIdle`, `StBlock`, `StBlockTxs`. `MsgDone [0]`,
  `MsgLeiosBlockRequest [1, point]`, `MsgLeiosBlock [2, endorser_block]`,
  `MsgLeiosBlockTxsRequest [3, point, bitmaps]`, `MsgLeiosBlockTxs [4, point, bitmaps,
  tx_list]`; `endorser_block = {* hash32 => uint32}`; `bitmaps = {* word16 => word64}` (64-wide
  windows). The catch-up range request of the design document "was never implemented and is
  no longer planned"; catch-up uses ChainSync and BlockFetch plus per-EB fetches.

So the CIP's four-protocol text is already out of date relative to the code; the Blueprint
itself marks `endorser_block`, `bitmaps` and `tx` as underspecified and the bitmap encoding as
under discussion. The CIP says it "should be amended as implementors refine".

### 4.3 N2N versions and the handshake

The testnet relay answers a version query with exactly versions 14, 15 and 16 (section 8): no
new handshake version number was observed for Leios. Whether the Leios mini-protocols are gated
by a version, by the Dijkstra era, or by a node-side feature flag could not be established
without opening them, and this note makes no claim. That is the first thing N3 must resolve.
It also bears on G4 (our client offers only version 14 today; the pure `Handshake` already models
14 to 16 including the Peras flag).

### 4.4 Other network-side changes

- Mempool: the CIP requires capacity of at least `2 x (maxBlockBodySize + S_EB-tx)` (24 MB with
  the 12 MB limit); the Porto workshop report puts the working figure at about 12 MB with
  per-transaction validation of 1 to 100 microseconds, and notes ledger, ChainDB, tx
  submission, the EB store and forging all depend on the mempool. The testnet sets
  `MempoolCapacityBytesOverride` 2,000,000.
- High-throughput TxSubmission (a "v2" in `ouroboros-network`) is a separate track, not part of
  CIP-164.
- Node-to-client: Leios-aware nodes serve certified blocks over LocalChainSync *inlined*: the
  certified EB's transactions are spliced into the ordinary segwit segments of the
  `ranking_block` CDDL, so existing N2C clients work unchanged. The header's `block_body_hash`
  covers the on-chain (empty) body, not the inlined one. New LocalStateQuery queries (for
  example the selected committee) are still to be added.
- Prioritisation: Praos traffic must not be delayed by Leios; the design relies on the
  multiplexer's fairness plus bounded kernel send buffers (`TCP_NOTSENT_LOWAT`), measurement
  still open.
- Chain selection in the prototype must treat a certified RB whose EB closure is missing like a
  missing body: filter, fetch the closure, re-select (ouroboros-consensus #2076 replaces the first
  "staging area" design).

## 5. Mapping onto this codebase

| Leios change | Where it lands | Notes and reuse |
|---|---|---|
| Protocol ids 18, 19; pure codecs and state machines | `protocol/` (pure codecs, like `ChainSync`, `TxSubmission2`); `network` (`Protocols`, new `LeiosNotifyClient/Server`, `LeiosFetchClient/Server`, registered in `NodeToNodeServer.Protocol` and `NodeToNode.over`) | `Mux`, `MuxChannel.recv` with incremental decoders and `Timed` are reusable as is. Credit and pipelining do not exist for these two, so keep them simple. Per-protocol agency and size limits as for the existing ones |
| Handshake versions and flags | `network/Handshake.scala`, `HandshakeClient` | Today the client proposes version 14 only (`NodeToNode.over`); see G4 |
| Dijkstra headers (announcement, cert flag, version info) | `node/Header.scala`, `Block.scala` | New era variant next to Conway; header hash and KES body-signature validation reuse `Praos`; body hash must cover the new body fields (L1 to confirm) |
| Certificate verification in block validation | `LedgerRules.scala` (`NodeRules`), `consensus/` | A new pure `LeiosCertificate` rule: committee lookup in the ledger view, signer-stake sum against `tau`, aggregate-signature check. The committee is a pure function of the mark snapshot, so it belongs in the ledger state with `ledger/Stake.scala` and `consensus/LedgerView.scala` |
| Committee and pool BLS keys | `ledger/Stake.scala`, certificate decoding in `ledger/Tx.scala`, `ledger/ProtocolParams.scala` (keys 40 to 48; Peras adds 49 and 50) | Pool-registration CDDL has `bls_key`; SNAP-time seating with the stable sort (stake descending, pool id ascending) |
| BLS keys, vote signing, aggregation, verification | new `crypto/.../bls/` next to `vrf/`, `kes/` | `foundation.icon:blst-java:0.3.2` (Apache-2.0) is already on the classpath through Scalus 1.2.0 and exposes `supranational.blst.{SecretKey,P1,P2,P1_Affine,P2_Affine,Pairing}`: `keygen`, `sign_with`, `hash_to` (with DST), `aggregate`/`add`, `in_group`, `compress`, `serialize`, `core_verify` taking a G2 key (MinSig works: G1 signatures, G2 keys), and `Pairing` aggregate/commit/finalverify. Covers key generation, signing, proof of possession, aggregation, subgroup checks and verification. Checked in N2 (section 9): the `P1_Affine(byte[])`/`P2_Affine(byte[])` constructors decode (and accept uncompressed forms and do not check the subgroup, so we check length, flag and `in_group` ourselves), the DST strings, and handle use as local accumulators; `aggregate` repeats the subgroup check on every call, so sums use `add`. Not checked: constant-time behaviour. blst-java is now a direct dependency of `core` |
| Forging: announce an EB, vote, build certificates | `forge/` (`Forger`, `BlockBuilder`, `Leadership`) | New pure `EbBuilder` from mempool snapshots, a vote-casting step with the node-local timers `L_hdr`/`L_vote`, a vote store with tally per announcing RB, certificate aggregation at forge time, key-file reading for the BLS key (`KeyFiles`) |
| Mempool | `mempool/Mempool.scala`, `MempoolState.scala` | Capacity and validation-latency requirements change (section 4.4); the "validated once, flagged" transaction set `S` of the CIP maps to a validation-verdict cache beside `VerificationCache`. The existing `--mempool-bytes` flag is the knob |
| EB store, transaction cache, closures | `storage/` | New `EbStore[F]` algebra: EBs and their transaction closures by hash for volatile forks, durable for certified EBs on the immutable chain (served to syncing peers, inlined for N2C). The prototype uses SQLite (`LeiosDB`); we would key it in RocksDB or files like `ImmutableStore`. Open question in the design: whether the committee is materialised in ledger state or recomputed |
| Chain selection with missing closures | `consensus/ChainSelection.scala`, `Follower.scala` | Candidate chains with an unresolved certificate are filtered until the closure arrives |
| N2C inlined blocks and new queries | `ntc/` (`NtcQuery`, `StateQuerySource`), `relay/ChainServer.scala` | Splice closure transactions into the segwit segments when serving; the Dijkstra era history entry |
| Observability | `JsonLog`, `Query.scala` | New events: `LeiosBlockForged`, `LeiosBlockCertified`, EB acquired, vote accepted; the prototype logs similar kinds |

Everything above stays inside the existing style: pure rules, `F` only at the edges, a closed
error ADT (new `NodeError` cases for certificate and EB failures), and a recorded evidence row.

## 6. Risks and open questions

Specification in flux (as of the revisions in section 1):

- CIP versus code: mini-protocol set and numbering, header fields, proof of possession size,
  vote `voter_id` (`uint` in the CIP, `word16` in the Blueprint). The CIP is "Proposed"; the Blueprint is the freshest wire description and
  marks parts underspecified (EB map, bitmaps, transaction list encoding).
- BLS key rotation mechanism (CIP Appendix A requirement 2 unmet) and the activation boundary.
- Where the committee lives (ledger state versus derived at the boundary).
- Per-transaction execution-unit limits inside EBs (CIP discussion #1213), which parameters are
  governance-updatable, and constitution guard rails for them. The prototype hard-codes them.
- The certificate-to-ledger interaction (BBODY check, application order, timing window): the LLF
  Agda work is partly merged and the plan lists metatheory, extraction and conformance as
  deferred; there is no executable reference for the ledger rules except the Haskell code.
- Whether the N2N handshake gains a version for Leios (section 4.3), and the interplay with
  Peras (shared certificate and voting machinery; Peras `ObjectDiffusion` is the stated fallback).
- Catch-up without a range request; design of syncing from historical EBs (`leios.db` side-load
  exists in the testnet, which suggests the on-disk format may matter for interoperability).
- Resource figures: Praos-over-Leios isolation, GC and disk pressure under a worst-case burst,
  and the voting protocol at mainnet-scale committees are judged by prototype only. Resource
  requirements "cannot be estimated yet" (Porto report).
- Security: votes and certificates add a new cryptographic surface (rogue-key defence, subgroup
  and infinity checks, deterministic encodings), the BLS aggregate layer is "peer reviewed, awaiting
  independent audit"; our own code would be new and unaudited. The CIP's liveness threat (a
  26% stake attacker can block certification at `tau = 0.75`) is a throughput, not a safety, issue.
- Scope risk for this project: Leios touches consensus, the ledger, storage, mempool and the
  network. The Haskell implementation itself is a prototype; verifying against it before the
  design settles risks rework. The staged plan therefore separates pure, vector-testable parts
  (N2) from following the live network (N3) from block-producing interoperation (N4).

Contingencies the ecosystem discusses (Porto report): disabling by stake, a node setting to not
fetch or not vote, gradual rollout. A node that implements only the RB side can follow the chain
if it can resolve certified EBs; a node that does not validate certificates should say so.

## 7. Staged plan (refines N2 to N4)

Dependencies: **L1** (Dijkstra era: header, body, pool parameters, protocol parameters 40 to
48, `LEDGER`/`BBODY` for the new body hash and transactions) is a prerequisite for N3 and N4.
**G4** (handshake versions 15 and 16, client side) is a prerequisite for N3 if the testnet
requires them; today the client offers 14 only. N2 depends on neither.

**N2: BLS keys, votes and certificates (pure, in `core`).** Done 2026-10-14, results and deviations in section 9. Acceptance:

1. `core` has `LeiosBls` (MinSig): secret-key generation from a seed, public key (G2, 96 bytes),
   `sign`, `verify`, proof of possession (`create`, `verify`), `aggregate` signatures and public
   keys, and strict decoding that rejects off-curve, wrong-subgroup, infinity (where the scheme
   forbids it) and non-canonical encodings. Dependency: `blst-java` declared directly, licence
   recorded.
2. Pure `Committee.select(stakeDistribution, N_c)` with the CIP's order (stake descending, pool id
   ascending, truncated) and `Certificate.verify(committee, signers, signature, announcingRbHash,
   tau)` including the keyless-signer and quorum rules; `Vote` encode/decode per CDDL.
3. Test vectors from `cardano-base` (`bls12-381-test-vectors`, the POP/AUG vectors) and the
   golden `LeiosVote` file in `ouroboros-consensus-cardano/golden/cardano/leios`, each with
   provenance in a fixtures README; vectors from the Rust reference in `crypto-benchmarks.rs`
   cross-checked, with the 96-byte versus 48-byte proof-of-possession discrepancy resolved
   against `dijkstra.cddl` and recorded.
4. Properties: an aggregate of valid votes verifies; removing, duplicating, reordering signers,
   flipping any signature bit, replaying with another announcing hash, or a rogue key without a
   valid proof of possession all fail; certificate size is `ceil(N/8) + 48 + overhead`.
5. `check` passes; a recorded micro-benchmark of verification on 900 seats (the node needs it on
   the RB critical path). No ledger, network or node change in this stage.

**N3: follow the public testnet (read-only).** Prerequisites: L1 for decoding, G4 as needed.
Acceptance:

1. A recorded handshake and version result (done once: section 8) plus the first mini-protocol
   exchange on 18/19 (only after the owner confirms the exchange is acceptable against that
   public relay), with the observed gating (version, era, flag) documented here.
2. Pure codecs for LeiosNotify and LeiosFetch with round-trip and malformed-input tests against
   the Blueprint `messages.cddl`, and raw captured messages as fixtures with provenance
   (relay, date, node version).
3. Dijkstra RB headers and bodies decoded from the testnet (including `eb_references_announcement`
   and `leios_certificate`); the follower reaches the tip, validates the RB-side rules it can
   (Praos header rules, certificate structure, certificate signature and quorum using N2 and the
   committee from the stake distribution), and reports precisely what is skipped (EB transaction
   validation, ledger application, Plutus with the V4 cost model) in the same style as the
   Preview bootstrap report.
4. EB announcement, EB body and closure fetches work against the relay; the fetched closure
   hashes to what the announcement says; no EB transaction is applied unless the ledger rules for
   the Dijkstra era exist (L1).
5. Evidence row in `docs/compatibility.md`: testnet name, node version string, number of RBs,
   certificates verified, certificates failing (expected zero), and the "not checked" list.

**N4: devnet prototype against the reference.** Prerequisite: a buildable Leios reference node
(the `leios-prototype` branches or a release) in the reference image mechanism of
[reference-image.md](reference-image.md); the Dijkstra and committee rules from N3. Acceptance:

1. A `devnet.py` scenario with Haskell Leios pools and the Scala node as relay: every EB
   announcement, EB, vote bundle and certificate seen by the Scala node equals the reference's
   (hashes and raw bytes), committees equal at each epoch boundary, and the ledger state (K1
   comparator extended to Dijkstra) equal at epoch boundaries.
2. The Scala node forges RBs that announce EBs, votes as a committee member with a registered key
   and builds certificates that the Haskell nodes accept; its own announced EBs are fetched and
   certified by the reference committee.
3. Throughput and mempool numbers recorded with the performance conventions of
   [performance.md](performance.md), including the mempool capacity and validation latency
   targets above.
4. Contingency settings implemented and tested: do not fetch EBs, do not vote.
5. Honest scoping: if the reference is not stable enough at this point, the stage ends with the
   scenario defined and the divergence list from N3, not a partial implementation.

## 8. Testnet connectivity spike (2026-10-14)

Scope: network handshake only; no ledger work, no data kept beyond the bytes below. Done with
our own pure `Handshake` codec and the runtime's `Bearer.tcp` and `Mux` (a throwaway program built
against the compiled classes of the checkout). An attempt to open mini-protocol 18 after the
handshake was first declined by the session's safety policy; after the owner approved it on
2026-10-14 it was run (section 10): the relay closed the connection, so section 4's protocol numbers
still rest on the Blueprint and the prototype source, not on a live exchange.

Target: `leios-node.play.dev.cardano.org:3001` (resolves to 34.251.133.12), network magic 164.

| Proposal | Reply bytes (hex) | Meaning |
|---|---|---|
| versions 14, 15, 16 with the query flag | `8203a30e8418a4f401f40f8418a4f401f4108518a4f401f4f4` | `MsgQueryReply`: the relay supports exactly 14, 15 and 16; data for 14 and 15 `[164, false, 1, false]`, for 16 `[164, false, 1, false, false]`, that is initiator-and-responder, peer sharing enabled, no query, (v16) Peras flag false |
| version 14 | `83010e8418a4f500f4` | accepted 14; `[164, true, 0, false]` initiator only, no peer sharing (the answer to our initiator-only data) |
| version 15 | `83010f8418a4f500f4` | accepted 15, same data |
| version 16 | `8301108518a4f500f4f4` | accepted 16, same data plus Peras flag false |

Conclusions for N3: the relay accepts our handshake at all of 14 to 16 with magic 164; there is
no Leios-specific version number; the Peras bit is off. Raw replies are in the table, small
enough to be fixtures if N3 wants them (handshake codec test, provenance: relay above, node
version string `11.1.0.164-prototype-2026w38a` per the live config, 2026-10-14). Network
characteristics for N3: Shelley genesis `epochLength` 21600, `slotLength` 1, `securityParam` 108,
`activeSlotsCoeff` 0.05, `systemStart` 2026-09-07T00:00:00Z, protocol version 11.0, hard forks
to Conway at epoch 0, `ExperimentalHardForksEnabled` and `ExperimentalProtocolsEnabled`, a
`LeiosDbConfig` SQLite file; peer snapshot point slot 280825 / hash
`85f25eb3...c91d69`.

## 9. N2 results: BLS keys, votes and certificates (2026-10-14)

Implemented in `crypto/src/main/scala/xyz/kd5ujc/scarab/crypto/bls/` (pure; the only effect is the native library call,
confined to `MinSig.guard`). Roadmap item N2 / issue 71. Not audited; research software.

### API

- `MinSig`: `sign`, `verify`, `provePossession`, `verifyPossession`, `aggregateSignatures`,
  `aggregateKeys`, `verifyAggregate`; the tags are `BLS_SIG_BLS12381G1_XMD:SHA-256_SSWU_RO_POP_`
  and `BLS_POP_BLS12381G1_XMD:SHA-256_SSWU_RO_POP_` (the `BLS12381MinSigDSIGN` choice in
  `cardano-base` `Cardano.Crypto.DSIGN.BLS12381.Internal`: no augmentation, tags fixed per curve).
  The signed message of a vote is the 32 raw bytes of the announcing RB hash; the message of a
  proof of possession is the 96 compressed bytes of the key.
- Typed values with private constructors and strict decoders: `Seed` (32 bytes), `SecretKey`
  (non-zero scalar below the group order; `generate(seed)` is `blst_keygen` as `genKeyDSIGN`),
  `VerificationKey` (96-byte compressed G2), `Signature` and `ProofOfPossession` (48-byte
  compressed G1). Decoding requires the exact length, the compression flag, a valid point, the
  prime-order subgroup check, and rejects the identity. Opaque types: `Seed`, `SeatId` (16 bits),
  `RbHash`, `Signers` (bitfield, at most 8192 bytes), `Weight` (non-negative stake).
- `Committee.fromSeats` (checks each proof of possession once; a failing proof makes a keyless seat,
  as `mkLeiosCommittee`), `Committee.select(candidates, N_c)` (stake descending, pool id ascending,
  truncated), `Weight.fractionCeil(totalActiveStake, num, den)` (exact `tau`), `Vote` (`sign`,
  `verify`, `encode`, `decode`), `Certificate` (`aggregate`, `verify`, `encode`, `decode`).
  `Certificate.verify` follows `verifyLeiosCert`: committee size, bitfield length, keyless signers,
  weight against `required`, then one pairing check against the sum of the signers' keys.
- Every failure is a `BlsError` case (closed ADT; no strings, no exceptions). The CBOR codecs are
  strict (shortest form, definite length, no trailing bytes) and fail with `CborFault`.

### Decisions where the sources are silent or differ (all recorded here, none silent)

- **Proof of possession is 48 bytes** (one G1 signature), not the 96-byte pair of the older Rust
  specification in `ouroboros-leios/crypto-benchmarks.rs`: the proofs in the proto-devnet genesis
  are 48 bytes and verify, and regenerate byte for byte from the secret keys.
- **Padding bits of the signer bitfield must be clear** (`SignersPaddingSet`). `cardano-base`
  ignores bits at or beyond the committee size. No honest producer sets them, so valid certificates
  are the same set; a node that wants the reference's exact acceptance can drop the check in
  `Signers.members`. Flag for N4.
- **A signature or proof that is the identity is rejected at decode**; `cardano-base` rejects it
  later (at verification and aggregation). Same accepted set.
- **Quorum is `tau` of total active stake** (`leios-committee.md` in the Blueprint), not of the
  stake seated on the committee: the caller passes `required`.
- Not modelled here (ledger-side, N3/L1): expiry of a registered key after
  `ceil(slotsPerKESPeriod * maxKESEvolutions / epochLength) + 2` epochs (Blueprint), activation at
  the epoch boundary, the choice of the announcing RB.

### Vectors

| Source | What it proves |
|---|---|
| Proto-devnet pools 1 to 3 (`ouroboros-leios/demo/proto-devnet`: `bls.skey`/`bls.vkey` and the `blsKey` proofs in `shelley-genesis.json`, made by `cardano-cli` on `cardano-base`) | secret key to verification key; each registered proof verifies under the `BLS_POP_` tag with the key bytes as message; **our regenerated proof equals the registered one byte for byte** (BLS signing is deterministic, so this fixes tag, message framing and the 48-byte size) |
| `cardano-base` `serde_test_vectors` | off-curve, not-in-subgroup and uncompressed G1/G2 encodings are rejected with the right reason |
| Golden `LeiosVote` of the consensus prototype (`leios-prototype` `cef41f6b`) | `[hash, 1000, signature]` decodes, the signature is a valid G1 point, and re-encoding gives the same bytes |
| Not used | `bls_sig_aug_test_vectors` (NUL/AUG ciphersuite for G2 signatures, a different scheme); no seed-to-key golden exists upstream, so key generation from a seed is covered by the same library call and by determinism only |

Properties (ScalaCheck, `LeiosCertificateSuite`): any valid vote verifies; an aggregate of valid
votes is a certificate that verifies with the signers' weight; flipping any single bit of the
signature, the signer bitfield, a key, a proof or the hash fails; another announcing hash fails; a
duplicated signature, an added or removed signer, a signature moved to another seat fail; a
signature made under another tag fails; certificate size is `ceil(N/8) + 48 + 5` (166 bytes for 900
seats); a rogue-key aggregate verifies when keys are aggregated without proofs and is stopped by the
committee (`SignerWithoutKey`); committee seating equals a model sort.

### Benchmark: 900 seats

`core/Test/runMain xyz.kd5ujc.scarab.crypto.bls.BlsBenchmark`, one thread, JDK in the build container, warm, host shared
with other jobs (indicative only):

| Operation | Time |
|---|---|
| verify a certificate, 700 signers (at least 3/4) | median 1.7 ms (min 1.2, max 2.9) |
| verify a certificate, 900 signers | median 1.6 ms (min 1.4, max 2.6) |
| verify one vote | 0.6 ms |
| aggregate 900 signatures | 0.5 ms |
| sign a vote | 0.19 ms |
| build the committee (900 decoded keys, 900 proof checks), once per epoch | 0.7 s |

The first version used blst-java's `aggregate`, which re-runs the subgroup check on every call, and
took 45 ms; keys are validated once when decoded, so the sum uses `add`. A certificate is cheap
enough for the RB critical path; the committee build belongs to the epoch boundary.

## 10. N3 probe of the public testnet relay (2026-10-14, owner-approved)

Read-only connection to `leios-node.play.dev.cardano.org:3001`, magic 164, with our runtime
(`Bearer.tcp`, `Mux`, `HandshakeClient`, `KeepAliveClient`) and the new pure codecs
`LeiosNotify`/`LeiosFetch` (`network/`, Blueprint `messages.cddl`; round-trip, state-admission and
malformed-input tests) with clients `LeiosNotifyClient`/`LeiosFetchClient` (`network/`).
The probe is `LeiosProbe` in the test sources of `network`
(`network/Test/runMain xyz.kd5ujc.scarab.network.LeiosProbe <host> <port> <magic> <seconds> <outfile>`).

| Attempt | Result |
|---|---|
| Versions 14, 15, 16, initiator only, KeepAlive only, 40 s | handshake accepts 16; the connection stays up (pings answered) |
| Same, then `MsgLeiosNotificationRequestNext` `[3]` on protocol 18 | the relay **closes the connection** within a second, before any reply |
| Version 16 only with the Peras flag set | accepted; closed the same way on protocol 18 |
| Duplex (initiator and responder) connection, listening on 18 and 19 for 60 s without sending | handshake accepted, no bytes on 18 or 19 (the relay does not start its Leios client toward a connection it has not promoted) |

So **no EB announcement, offer, vote or certificate was received, and nothing was verified against
real data**: the relay does not serve LeiosNotify to an initiator-only follower that has not run
the Praos protocols, or does not offer protocol 18 on versions 14 to 16 at all. The first
possibility fits an on-demand responder that exists only for a warm or hot peer; the second fits
the gating being something other than the version (section 4.3). N3 must settle which by driving
ChainSync, BlockFetch and TxSubmission first (needs Dijkstra header decoding, L1) or by asking the
Leios team; this note makes no claim beyond the table. The recorded handshake replies are now a
test (`fixtures/leios/testnet/handshake-replies.tsv`, `LeiosTestnetHandshakeSuite`). Even with real
votes, verification needs the committee: the Shelley genesis of the testnet has one pool with
`blsKey: null`, so the voting keys exist only in on-chain pool registrations (N3 with L1).
