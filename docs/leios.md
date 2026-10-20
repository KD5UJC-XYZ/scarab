# Ouroboros Leios: design note (roadmap N1)

Status: design note, the pure N2 crypto (section 9) and the N3 follow of the public testnet
(section 11). **Leios is not implemented in the node beyond BLS keys, votes, certificates, two wire
codecs and read-only decoders and header rules for the testnet's chain** (section 11; the node itself
cannot follow it, 11.4). Against a Leios node, headers and blocks were checked on the public chain
(154,698 headers, every Praos check but the stake-dependent leader threshold); protocols 18 and 19
were never answered (sections 8, 10 and 11.7). The protocol is
still moving: every statement below carries the source and revision it was read from, and where
the sources disagree the disagreement is recorded rather than resolved. Unofficial research
software, not audited. Read together with [architecture.md](architecture.md),
[compatibility.md](compatibility.md) and [roadmap.md](roadmap.md) (groups L and N).

Dates are 2026-10-14 (the day of reading) unless stated. "Linear Leios" is the variant in
CIP-164; the research paper's three-block-type design (input blocks, endorser blocks, ranking
blocks) is not what is being built.

## 1. Sources and revisions

The table pins the repositories and revisions used for the source review. Local clone locations
are private working details; reproduce the review from these upstream identities.

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
  CDDL. **It is stale** (section 11.5): in Dijkstra the hash is the Blake2b-256 digest of the whole
  encoded body, and `block_body_size` is that body's size; checked on real blocks.
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
It also bore on G4, done 2026-10-17: the node now offers 14 and 15, and 16 with `ExperimentalProtocolsEnabled`, which the
testnet configuration sets (X9 of section 11.6). Version 16 adds only the Peras flag to the version data (cardano-diffusion
`NodeToNode/Version.hs` 85 to 95 and 187 to 189, identical at `c5117705` that cardano-node 11.1.3 pins, at `4b3ab766` that the testnet release `8c44d145` pins and at `96ee6e0e`
that the tip of the prototype's `cardano-node` branch pins; `latestReleasedNodeVersion` is 15 in consensus 4.2.1.0 and in the
release's `27fa649f` alike); the Leios mini-protocols have no version of their own. Answered in section
11.5 item 3: the release the testnet runs does not gate the Leios protocols on the version at all; what the relay does with
them is 11.7.

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

A proposal, mostly not built: of the rows below only the BLS keys (N2) and the pure codecs and clients of protocols 18 and
19 exist. The file names are those of the codebase before the module split (#82; see
[architecture.md](architecture.md#modules)): `Handshake.scala` is now in `protocol/`, `Header.scala` and `Block.scala` in
`ledger/`. `blst-java` is declared directly by `crypto` (N2), no longer only through Scalus (now 1.3.0).

| Leios change | Where it lands | Notes and reuse |
|---|---|---|
| Protocol ids 18, 19; pure codecs and state machines | `protocol/` (pure codecs, like `ChainSync`, `TxSubmission2`); `network` (`Protocols`, new `LeiosNotifyClient/Server`, `LeiosFetchClient/Server`, registered in `NodeToNodeServer.Protocol` and `NodeToNode.over`) | `Mux`, `MuxChannel.recv` with incremental decoders and `Timed` are reusable as is. Credit and pipelining do not exist for these two, so keep them simple. Per-protocol agency and size limits as for the existing ones |
| Handshake versions and flags | `network/Handshake.scala`, `HandshakeClient` | Done in G4: `protocol/ProtocolVersion.scala` (`NtnVersion`, `NtcVersion`), offers up to `NodeToNode.Settings.maxVersion`, 16 with `ExperimentalProtocolsEnabled`; the Peras flag is never set |
| Dijkstra headers (announcement, cert flag, version info) | `node/Header.scala`, `Block.scala` | New era variant next to Conway; header hash and KES body-signature validation reuse `Praos`; body hash must cover the new body fields (L1 to confirm) |
| Certificate verification in block validation | `LedgerRules.scala` (`NodeRules`), `consensus/` | A new pure `LeiosCertificate` rule: committee lookup in the ledger view, signer-stake sum against `tau`, aggregate-signature check. The committee is a pure function of the mark snapshot, so it belongs in the ledger state with `ledger/Stake.scala` and `consensus/LedgerView.scala` |
| Committee and pool BLS keys | `ledger/Stake.scala`, certificate decoding in `ledger/Tx.scala`, `ledger/ProtocolParams.scala` (keys 40 to 48; Peras adds 49 and 50) | Pool-registration CDDL has `bls_key`; SNAP-time seating with the stable sort (stake descending, pool id ascending) |
| BLS keys, vote signing, aggregation, verification | new `crypto/.../bls/` next to `vrf/`, `kes/` | `foundation.icon:blst-java:0.3.2` (Apache-2.0) is already on the classpath through Scalus 1.2.0 and exposes `supranational.blst.{SecretKey,P1,P2,P1_Affine,P2_Affine,Pairing}`: `keygen`, `sign_with`, `hash_to` (with DST), `aggregate`/`add`, `in_group`, `compress`, `serialize`, `core_verify` taking a G2 key (MinSig works: G1 signatures, G2 keys), and `Pairing` aggregate/commit/finalverify. Covers key generation, signing, proof of possession, aggregation, subgroup checks and verification. Checked in N2 (section 9): the `P1_Affine(byte[])`/`P2_Affine(byte[])` constructors decode (and accept uncompressed forms and do not check the subgroup, so we check length, flag and `in_group` ourselves), the DST strings, and handle use as local accumulators; `aggregate` repeats the subgroup check on every call, so sums use `add`. Not checked: constant-time behaviour. blst-java is now a direct dependency of `core` |
| Forging: announce an EB, vote, build certificates | `forge/` (`Forger`, `BlockBuilder`, `Leadership`) | New pure `EbBuilder` from mempool snapshots, a vote-casting step with the node-local timers `L_hdr`/`L_vote`, a vote store with tally per announcing RB, certificate aggregation at forge time, key-file reading for the BLS key (`KeyFiles`) |
| Mempool | `mempool/Mempool.scala`, `MempoolState.scala` | Capacity and validation-latency requirements change (section 4.4); the "validated once, flagged" transaction set `S` of the CIP maps to a validation-verdict cache beside `VerificationCache`. The existing `--mempool-bytes` flag is the knob |
| EB store, transaction cache, closures | `storage/` | New `EbStore[F]` algebra: EBs and their transaction closures by hash for volatile forks, durable for certified EBs on the immutable chain (served to syncing peers, inlined for N2C). The prototype uses SQLite (`LeiosDB`); we would key it in RocksDB or files like `ImmutableStore`. Open question in the design: whether the committee is materialised in ledger state or recomputed |
| Chain selection with missing closures | `consensus/ChainSelection.scala`, `Follower.scala` | Candidate chains with an unresolved certificate are filtered until the closure arrives |
| N2C inlined blocks and new queries | `ntc/` (`NtcQuery`, `StateQuerySource`), `relay/ChainServer.scala` | Splice closure transactions into the segwit segments when serving; the Dijkstra era history entry |
| Observability | `log/NodeEvent.scala` (the log), `Query.scala` | New events: `LeiosBlockForged`, `LeiosBlockCertified`, EB acquired, vote accepted; the prototype logs similar kinds |

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
**G4** (handshake versions 15 and 16) was a prerequisite for N3 if the testnet required them; it is done (X9), and the
testnet accepted version 16 from us. N2 depends on neither.

**N2: BLS keys, votes and certificates (pure, in `crypto`).** Done 2026-10-14, results and deviations in section 9. Acceptance:

1. `crypto` has `LeiosBls` (MinSig): secret-key generation from a seed, public key (G2, 96 bytes),
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

`crypto/Test/runMain xyz.kd5ujc.scarab.crypto.bls.BlsBenchmark`, one thread, JDK in the build container, warm, host shared
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

Follow-up (section 11.7, 2026-10-16): driving ChainSync and BlockFetch first, on version 16, and a
LeiosFetch request in place of the LeiosNotify one, end the same way, so the first possibility above
(the Praos protocols not having been run) is ruled out; the cause is still open.

## 11. N3: following the public testnet (2026-10-16, owner-approved, read-only)

Status: current (what was run and what it showed). Dates are 2026-10-16. Everything below was read
from the public relay `leios-node.play.dev.cardano.org:3001` (network magic 164) as a client that
submits nothing: no transaction, no vote. In this work the relay saw ten connections, one at a time:
a ChainSync and BlockFetch probe (version 14), two probes with one LeiosNotify pull (version 16), one
with one LeiosFetch request (version 16), two short header captures (300 and 20 headers), the full
header capture, two block captures (2 and 7 blocks) and one run of the node's own `CaptureMain`
(the baseline of 11.4). The full header capture was 154,698 headers in 200 seconds at 64 headers
per pipelined round with 25 ms between rounds (a syncing node's pace on one peer, about 84 MB);
blocks were fetched one at a time, half a second apart. The captures are kept outside the
repository; the fixtures take what the tests need.

### 11.1 The published configuration

The testnet's own name is `musashi` (the dojo network of the Leios team). Its configuration is
published, with the other Cardano environments, at
**`https://book.play.dev.cardano.org/environments-pre/leios/`** (`config.json`, `topology.json`,
`peer-snapshot.json` and the byron, shelley, alonzo, conway and dijkstra genesis files), the
deployment of `input-output-hk/cardano-playground` `docs/environments-pre/leios/`. The Leios team's
relay recipe, `input-output-hk/ouroboros-leios` `testnet/` (`README.md`, `pin-config.sh`,
`run-node.sh`; main `614b10d2`), pins an older snapshot of the same files and names the bootstrap
relay `leios-node.play.dev.cardano.org:3001`. The files of 2026-10-16 are in
`fixtures/leios/testnet/config/`, byte for byte as downloaded. Seven of the eight equal the pinned
snapshot of the relay recipe; `config.json` differs (the recipe's is older: `MinNodeVersion`
`11.1.0.164-prototype-2026w36`, `MempoolCapacityBytesOverride` 2,000,000 and a `Filepath` for the
LeiosDb, none of which the live file has; the live one adds snapshot delays and different trace
forwarder queues). The live `config.json` is the authority.

| File | SHA-256 (of the file) | Blake2b-256 (the hash `config.json` lists) |
|---|---|---|
| `config.json` | `de4673d1e527baf0ddfc0a87be0a9ba906eafb78ae97d6f1a1eafdc2d5f0233c` | not listed |
| `topology.json` | `d260ed809bed60384c8837443d8457062a58d7dca00a4e38d75fd65b486ff2cb` | not listed |
| `peer-snapshot.json` | `8bb27980a308bf2e046021ff8d3763f4af4879c2145011a47d2cfcd280163085` | not listed |
| `byron-genesis.json` | `4620a2a14b26731467c954263eb2af9b86e5f5248aae247f1c183ca0368dde65` | `5809f031d8dc8ae8091f66a80ebe8f7d173e475436110c462a0533ed65954639` (over the canonical JSON, as the node verifies it) |
| `shelley-genesis.json` | `da8508afc546a0b1824d93629a7a5fc8c0898d85095c2cf1b25730eb99d5a151` | `1944510a4fd91415444285231058f6f6ff0f6f3ff3d0356c76c00c5a77f29567` |
| `alonzo-genesis.json` | `23bf365cae730137ad6868787f20ef4f58e446d22617d7b824f1c75695c760b5` | `387a7c4880477ce7b128566fa7f9f9ed99ee04476084e9f6332b6d42d907faab` |
| `conway-genesis.json` | `51e9d92f09ea79e61811983b4b1e299926a61b352c3313aaebc7c287169ae2f0` | `e2951aa7f08dcd89bb6ca7fcf9acae5c46bdefb5a9affbac769bbe1902e982eb` |
| `dijkstra-genesis.json` | `e3a0f8edd086e0c1fccc9fa04b58fa76b8f735ce1214f854072078f7c823e8be` | `aa1238f505479a9b104d2cc001b4bf951062cd527200bea9a1857bdd0dc41085` |

`LeiosTestnetConfigSuite` checks every listed hash with the node's own `NodeSetup.verifyHashes`.
Not published: signing, KES, VRF or operational-certificate keys of the pools (none are needed for a
read-only follower), a checkpoints file, and the committee: the testnet's BLS voting keys exist only
in the pool registrations on the chain (the Shelley genesis pool has `blsKey: null`).

### 11.2 The parameters

| What | Value | From |
|---|---|---|
| Name, magic | `musashi`, 164 | `shelley-genesis.json` `networkMagic`; `ouroboros-leios` `testnet/README.md` |
| Node build | `MinNodeVersion` `11.1.0.164-prototype-2026w38a`. The release of 2026-09-22 (`input-output-hk/ouroboros-leios` `f65db01c`; its CHANGELOG: "no state wipe and no respin: schema and wire formats are unchanged from w38") is `cardano-node` `8c44d145` on `leios-prototype`, which pins `ouroboros-consensus` `27fa649f`, `cardano-ledger` `d93c6546`, `ouroboros-network` `4b3ab766`, `cardano-base` `fbfb3f05` (`flake.lock` and `cabal.project` at those revisions). Whether the relay runs that exact build or a later one is not published | `config.json`; the above |
| Consensus | `Protocol: Cardano`, `ConsensusMode: PraosMode`, `ExperimentalHardForksEnabled`, `ExperimentalProtocolsEnabled`, `TxSubmissionLogicVersion: TxSubmissionLogicV2`, `LeiosDbConfig.Backend: SQLite` | `config.json` |
| Eras | `Test{Shelley,Allegra,Mary,Alonzo,Babbage,Conway}HardForkAtEpoch` all 0: no Byron block. There is no `TestDijkstraHardForkAtEpoch`: the chain enters Dijkstra by itself (11.3) | `config.json` |
| Time | `systemStart` 2026-09-07T00:00:00Z, `slotLength` 1 s, `epochLength` 21,600, `securityParam` 108, `activeSlotsCoeff` 0.05, `slotsPerKESPeriod` 129,600, `maxKESEvolutions` 62 | `shelley-genesis.json` |
| Protocol version | genesis 11.0 (Conway); every header reports `[12, 0]` or `[12, 67]` (11.3) | `shelley-genesis.json`; headers |
| Genesis stake | none at the top level; `extraConfig` seats one pool (`fd32267b…ea88`, VRF key hash `f8eb3533…bdc5`, cost 0, margin 0, `blsKey: null`) with 900,000,000 lovelace delegated to it, and 30,000,000,000,000,000 lovelace in an enterprise address; maximum supply 45,000,000,000,000,000 | `shelley-genesis.json` |
| Leios parameters | committee 900, quorum 0.75, announcement period 1,000, vote period 4,000, diffusion period 7,000 slots; EB transactions 1,000,000 bytes, EB references 100,000 bytes, EB execution units 310,000,000 memory and 100,000,000,000 steps; reference scripts 1,048,576 bytes per block, 4,000,000 per EB, 204,800 per transaction; minimum pool margin 0.015; reference script cost 1.2 per 25,600 bytes; a Plutus V4 cost model of 251 values | `dijkstra-genesis.json` |
| Block limits | `maxBlockBodySize` 90,112, `maxBlockHeaderSize` 1,100, `maxTxSize` 16,384 | `shelley-genesis.json` |
| Peers | one bootstrap relay; `useLedgerAfterSlot` 151,200; the peer snapshot lists the same host three times (the three big ledger pools at slot 280,825, hash `85f25eb3…1d69`) | `topology.json`, `peer-snapshot.json` |
| Handshake | versions 14, 15, 16 accepted, magic 164, no Leios-specific version, Peras off (section 8) | the relay |

### 11.3 What the chain looks like (blocks 0 to 154,697, captured 21:57 UTC)

| Observation | Value |
|---|---|
| Headers | 154,698, from block 0 (slot 12) to block 154,697 (slot 3,448,307, epoch 159); the relay's tip was block 154,701 |
| Era index of the envelope | 6 (Conway) for blocks 0 to 4,254 (epochs 0 to 3), **7 (Dijkstra) from block 4,255 (slot 86,463, the 64th slot of epoch 4)** to the tip: 4,255 and 150,443 headers |
| Header shape | **12 elements in both**: the Praos header body with the protocol version replaced by `header_version_info` and the two Leios fields appended (`dijkstra.cddl` 56 to 71). The Conway-indexed blocks have it too (11.5) |
| Header element 9 (`protocol_version` in the release, `header_version_info` in the pinned CDDL) | `[12, 0]` in 153,845 headers, `[12, 67]` in 853 (first at block 28,533, last at 154,222): major version 12, the first Dijkstra protocol version; the second number is a minor version in the release and a software tag in the CDDL (11.5 item 2) |
| Block layout | index 6, block tag 7: `[header, bodies, witnesses, auxiliary data, invalid]` (the Conway layout); index 7, tag 8: `[header, [transactions, leios_certificate / nil, peras_certificate / nil]]` |
| Block body hash | tag 7: the merkle digest of the component hashes; **tag 8: the Blake2b-256 digest of the whole encoded body**, and `block_body_size` is the body's encoded size (11.5) |
| Producers | epochs 0 and 1: the genesis pool only (1,062 and 1,050 headers); epochs 2 on: three pools; from epoch 15 on: more, 147 distinct issuers in all (most first seen between epochs 15 and 24) |
| Announcing headers | 65,469 (42%); first at block 17,397, slot 371,614 (epoch 17); `eb_size` 37 to 199,998 bytes (median 99,183) |
| Headers with `block_body_contains_leios_cert` | 25,531 (16.5%); first at block 17,403, slot 371,723; 24,251 of them also announce, 1,280 do not |
| Block bodies | largest 88,873 bytes, median 73 bytes: most blocks are empty or hold a certificate, full blocks come with the transaction generator |

### 11.4 How far this node followed

"Follow" has two meanings here, and the answers differ.

**The node itself stops at the first header.** `follow --config config.json` reads the
configuration (every hash verified; `ExperimentalHardForksEnabled` is recorded in `configUnsupported`
as "the Dijkstra era is not implemented"), derives the genesis ledger from `extraConfig` (two outputs,
one pool: `GenesisState` already reads that, 11.5 item 4), connects, agrees version 14, and
intersects at Origin. The first header's envelope has index 6, `NtNHeader.codec` accepts it, and
`Header.fromRaw(Conway)` refuses its 12 elements: the node's own capture path (`CaptureMain`, which
uses `Peer.nodeToNode`) ends with `protocol: decode header: expected 10-element array`, a typed
`PeerError.Protocol`, at block 0. Zero blocks, zero headers.

**The new read-only tools followed the whole chain**, header by header, from Origin to block 154,697
(`LeiosCaptureMain` captures, `LeiosCheckMain` checks; the rules are `LeiosHeaderChain` in
`consensus`, the decoders `HfcHeader` in `protocol` and `LeiosHeader` and `LeiosBlock` in `ledger`):

| Check, per header | Result over the 154,698 headers |
|---|---|
| Envelope `[era, tag24(header)]` decodes (`HfcHeader`: Conway-indexed, Dijkstra-indexed, any other index kept as a value) | 4,255 + 150,443, none unrecognised |
| `LeiosHeader` decodes the 12-element body | all |
| Chain: `prev_hash` is the previous header's hash (Blake2b-256 of the exact header bytes), block numbers rise by one, slots rise | all |
| Operational certificate: the cold key's signature over hot key, sequence number and KES period; the KES period within `maxKESEvolutions` 62 of the certificate's | all |
| KES signature (Sum6KES) over the exact header body bytes | all |
| Operational certificate counters never decrease per issuer | all |
| VRF proof under the epoch nonce (evolved from the hash of the Shelley genesis file through 160 epochs: evolving nonce per block, candidate nonce frozen 4k/f slots before the end of the epoch, last-block nonce of the previous epoch) | all. The VRF input contains the nonce, so one wrong nonce anywhere would fail every later header: the nonce rules of Praos hold unchanged for these headers over 160 epochs |
| Leader threshold against the genesis stake | 2,112 headers (epochs 0 and 1, led by the one genesis pool, relative stake 1) |
| Leader threshold, epochs 2 on | **152,586 headers `Unchecked`**: the stake comes from the chain's transactions (pool registrations, delegations), which this follower does not apply; an issuer's VRF key is taken on trust at first sight |
| Block bodies | the six blocks kept as fixtures (two of tag 7, four of tag 8: empty, with transactions, announcing, certifying) decode, and in every one the body hash and the body size equal the header's, the certificate flag equals the presence of the certificate, and a certificate does not come with transactions (`LeiosBlock.faults`; consensus `Shelley/Ledger/Block.hs` 224 to 231); the body hash of two 88 kB tip blocks was checked by hand as well |
| Leios certificate | decoded strictly (`Certificate.decode`: bitfield up to 8,192 bytes, a signature that is a valid G1 point); **not verified**: no committee |

Nothing was applied to a ledger: 0 blocks are ledger-validated, because the Dijkstra era has no
ledger rules in this node and the Conway-indexed blocks carry a header the Conway rules do not read.
The whole check takes about four minutes on one core (`LeiosCheckMain`: 255 s, 2026-10-16).

### 11.5 Findings that change what sections 3, 4 and 10 say

1. **The hash of a Dijkstra body is not a merkle root.** Section 3 repeated the CDDL comment
   "merkle triple root". The ledger computes `hashBlockBody (MkDijkstraBlockBody m) = extractHash $
   getMemoBytesHash m` (cardano-ledger `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/BlockBody/Internal.hs`
   line 120): the Blake2b-256 digest of the original encoding of the whole body
   `[transactions, leios_certificate, peras_certificate]`. Checked on four real blocks.
2. **The Leios header is in blocks tagged Conway.** In the release the testnet runs, the Praos header
   body is the one type of Babbage, Conway and Dijkstra (`ouroboros-consensus` `27fa649f`:
   `Cardano/CanHardFork.hs` line 130 and following has all three as `ShelleyBlock (Praos c)`), and it
   carries an optional Leios extension: `ouroboros-consensus-protocol` `Protocol/Praos/Header.hs`
   lines 118 to 121 (`hbProtVer`, `hbLeiosExt :: StrictMaybe HeaderLeiosExtension`), 141 to 150
   (the certificate flag and the EB announcement), 228 to 243 (the encoder writes 10 or 12 elements
   by whether the extension is there) and 262 to 265 (the decoder reads either). Every header of
   this chain has it, so Conway blocks (blocks 0 to 4,254) have the 12-element header and the old
   five-part body, and Dijkstra blocks the same header with the new two-part block. A decoder
   written to `conway.cddl` (10 elements) cannot read this network's first block. In that release
   the element before the Leios fields is `hbProtVer`, a protocol version (`[12, 0]`; `[12, 67]` in
   853 headers, a minor version of 67, issued by 10 of the 147 pools; the cause is not known); the pinned `dijkstra.cddl` and cardano-ledger
   name it `header_version_info = [highest_supported_major_version, self_reported_software_tag]`
   with the same wire format (cardano-ledger-core `Block.hs` 147 to 156, "neither field is
   validated"). The consensus main branch (`8f56bc89`, which this repository pins) instead has a
   separate `Praos2` protocol for Dijkstra only (`Shelley/Protocol/Praos.hs` 228 to 326): the
   testnet follows the prototype release, not main.
3. **The N2N handshake does not gate Leios.** `Ouroboros.Consensus.Network.NodeToNode` of the release
   (the comment before `initiator` and `initiatorAndResponder`: "network currently doesn't enable
   protocols conditional on the protocol version"; `withHot` with `StartOnDemand` for mini-protocols 18
   and 19) registers the Leios protocols as hot protocols of every version. Section 4.3's open
   question "version, era or flag" is answered: not the version. What the relay does on 18 and 19 is
   11.7.
4. **The genesis `extraConfig` is read already.** `GenesisState.initial` reads `initialFunds`,
   `stakePools` and `stakeCredentials` from `extraConfig` (cardano-ledger `Shelley/Genesis.hs` 213 to
   260, the embedded `data` form); the node derives the two genesis outputs, the one pool and the
   genesis stake of epochs 0 and 1 (`LeiosTestnetConfigSuite`). The file form of an injection
   (`InjectionFromFile`, 796 to 846) is not read. `PoolParams` has no BLS key, so a registered
   voting key would be dropped.
5. **LeiosNotify carries at most sixteen votes in the prototype**: `MsgLeiosVotes = [7, [1*16 vote]]` in
   `ouroboros-consensus-cardano/cddl/node-to-node/leiosnotify/leiosNotify.cddl` (consensus main
   `144cb4f1`, 2026-10-14: "TODO Bound the votes in the blueprint too"); the Blueprint does not bound
   them. The codec now refuses more (`LeiosNotify.MaxVotes`, `LeiosCodecSuite`). The same file shows
   `announcement = header.header`: the announcement is the ChainSync header of the announcing block,
   whichever era index it has.
6. **A compatibility hazard the node has, not a Leios finding.** `NodeToNode.over` and
   `NodeToNodeServer.Protocol` give the multiplexer the protocols 0, 2, 3, 4 and 8, and `Mux.scala`
   (line 109) ends the connection at the first SDU on any other protocol number ("data on
   unexpected mini-protocol"). A Haskell Leios peer that has this node on a duplex connection can
   start its LeiosNotify and LeiosFetch initiators on 18 and 19 (`initiatorAndResponder` of the
   release, `StartOnDemand`); that was not observed, because the relay never started them toward us
   (11.7), so this is read from the two sources, not shown on the wire.

### 11.6 What is implemented and what is not: the gap table

IDs are stable names for the integrator to turn into issues; none was filed ("Not yet" points at
these). "Cited" is the source read, at the revisions of sections 1 and 11.2 and, where it says
"pinned", at the revisions in `models/src/main/cddl/upstream/SOURCES`.

| ID | What is missing | Where it matters | Cited |
|---|---|---|---|
| **X1** | The node cannot follow this chain: `NtNHeader` refuses era index 7 and `Header.fromRaw(Conway)` the 12-element Conway header. `HfcHeader`, `LeiosHeader` and `LeiosBlock` exist, but `Follower`, `Peer`, `HeaderGate` and `LedgerRules` do not use them | the follower | `dijkstra.cddl` 56 to 71, 105 to 114; 11.4 |
| **X2** | `Era` has no `Dijkstra`: the era history, `HardForkConfig` (no trigger at protocol version 12, `eraProtVerLow` 12), `ShelleyBasedEra`, the state mirror and the Haskell state-file telescope know seven eras. The chain enters Dijkstra at epoch 4 with no `TestDijkstraHardForkAtEpoch`, so a governance hard fork to protocol version 12 the node would have to derive from its own ledger. Adding the case breaks exhaustive matches in `consensus` (`HardFork.scala`, `SomeEra.scala`), `storage` (`ConsensusCodecs.scala`, `LedgerFileCodecs.scala`) and the era generators of `consensus` tests; this stage did not touch them | consensus, storage | cardano-ledger-core `Internal/Definition/Era.hs` 140 (pinned) |
| **X3** | No Dijkstra ledger: `LEDGER`, `BBODY` with the Leios signal (`LeiosBbodySignal`), the transaction body (key 14 `guards` in place of `required_signers`, sub-transactions, the inline `is_valid` flag, Plutus V4 as `language 3` with the cost model of `dijkstra-genesis.json`), certificates, `pool_params.bls_key`, protocol parameters 40 to 54, the translation from Conway | the rules | `dijkstra.cddl` 3 to 54, 116 and following, 719 to 733 (pinned); the Dijkstra changelog (section 3) |
| **X4** | No stake distribution without a ledger: from epoch 2 the leader threshold of every header (152,586 of 154,698) is unchecked, and a pool's registered VRF key is not compared | header-first validation | 11.4 |
| **X5** | The Leios certificate is decoded, not verified: no committee (the BLS keys are in pool registrations, `PoolParams` has no `bls_key`), no quorum from the stake, no lookup of the announcing ranking block | `LeiosBlock`, `Certificate.verify` | `dijkstra.cddl` 937 to 942; section 9 |
| **X6** | `peras_certificate` is carried as bytes; the ledger's `validatePerasCert` is a placeholder that returns `False` | Peras | cardano-ledger `BlockBody/Internal.hs` 235 to 246 (pinned) |
| **X7** | Endorser blocks: an announcement is recognised in a header (65,469 on this chain) but no EB is fetched, stored, hashed against the announced hash or applied; there is no EB store, no closure, no inlining for node-to-client, no chain selection with a missing closure | storage, consensus, `ntc` | sections 4.4 and 5; `dijkstra.cddl` 105 to 108 |
| **X8** | Mini-protocols 18 and 19 were never answered by the relay (11.7), and the node's multiplexer and servers do not carry protocols 18 and 19 at all (11.5 item 6) | network | consensus `NodeToNode.hs` of the release |
| **X9** | ~~Handshake: the node offers version 14 only; the relay accepts 14 to 16, 16 adding the Peras flag~~ Resolved (G4, [#34](https://github.com/KD5UJC-XYZ/scarab/issues/34)): both sides offer 14 and 15 by default, as cardano-node 11.1.3 (`latestReleasedNodeVersion` is 15, `Cardano/Node.hs` 456 of consensus 4.2.1.0), and 16 with `ExperimentalProtocolsEnabled` (`limitToLatestReleasedVersion`, diffusion `Node.hs` 1152 to 1165). The Peras flag is off, so version 16 differs from 15 only by the fifth element of the version data. The public Leios testnet sets the key, so its relay is offered 14 to 16 and answers 16 | handshake | section 8; `LeiosTestnetHandshakeSuite`, `HandshakeNegotiationSuite` |
| **X10** | `TxSubmissionLogicVersion: TxSubmissionLogicV2` and the Leios mempool (an EB reservation; section 4.4) are not modelled | mempool, TxSubmission2 | `config.json` |
| **X11** | `ConsensusMode: PraosMode` with ledger peers after slot 151,200 and a peer snapshot: no ledger peers, no peer snapshot, one bootstrap relay only | topology | `topology.json`, `peer-snapshot.json` |
| **X12** | The CDDL pipeline (`models-gen`) takes the Leios CDDL it was given: `dijkstra:header` (12-element body, round trip byte for byte, `LeiosTestnetHeaderSuite`), consensus' `leiosNotify.cddl` (a module `leios-notify`; its `;# import header as header` needs `header.cddl` as a second module of that name; `LeiosNotifyCddlSuite` compares the generated reader with the hand-written codec, sixteen votes included) and, since G4, the Blueprint's `leios-fetch/messages.cddl` at `fe4cceb9` (a module `leios-fetch`; no consensus or network branch has a LeiosFetch CDDL; its `base` is the consensus `base` module plus `word16` in `overrides/base-word16.cddl`, its `tx` a module of ours that says `tx = any` because the codec keeps each transaction as the bytes it came with and the Blueprint marks `tx` underspecified; `LeiosFetchCddlSuite`, the cuddle root `leios-fetch:leiosBlockFetchMessage` with one allowlisted float divergence, fuzz targets `leios-fetch` and `leios-notify`). Not generated: `dijkstra:block` and `pool_params` (the 12 rules with a required member after an optional one, `docs/models.md` section 8). The Blueprint moved on 2026-10-16 (`MsgLeiosBlockTxs` is `[4, tx_list]`, transactions are tag 24, non-empty endorser blocks, following consensus#2416): the codec and the pinned copy stay on the older form until the testnet release moves, [#174](https://github.com/KD5UJC-XYZ/scarab/issues/174). The cross-check was run for both new modules (G4) | `models` | `models/src/main/cddl/models.conf` |
| **X13** | `header_version_info` is not interpreted: nothing compares `highest_supported_major_version` with the chain's version or the node's maximum; the earlier eras' `protocol_version` is checked against the ledger's on mainnet | envelope checks | cardano-ledger-core `Block.hs` 147 to 156 |

### 11.7 The Leios mini-protocols against the relay

Three probes, each its own connection and each after ChainSync from a known point to the relay's tip:
(a) one `MsgLeiosNotificationRequestNext` on protocol 18 (version 16, initiator only); (b) one
`MsgLeiosBlockRequest` on protocol 19 for the endorser block the newest announcing header names (slot
3,448,578, EB hash `5cac54a5…fcfb`; version 16); (c) section 10's probes of 2026-10-14: the request
without the Praos protocols, and a duplex connection that only listens. The relay accepts the
handshake, serves ChainSync and BlockFetch normally, and **ends the connection within a second of the
first message on protocol 18 or 19** ("closed by peer"); a duplex connection that sends nothing gets
nothing on 18 or 19 in 60 seconds. No raw LeiosNotify or LeiosFetch message was therefore captured,
and no endorser block, vote or announcement message was seen. Hypotheses, none shown: (1) the
relay's handling of an initiator-only inbound peer has no responder for 18 and 19 (in the release,
`initiator` registers them as `InitiatorProtocolOnly` and only `initiatorAndResponder` as responders
too); (2) the responder exists only for a peer the relay has made hot in its own peer governor, and
our connection is inbound and unknown to it; (3) the sender-driven driver of LeiosNotify
(`runLookaheadFixedSenderPeerWithLimits`, byte limit `smallByteLimit` in `LeiosDemoOnlyTestNotify.hs`)
rejects a first request that is not part of a pipelined credit run (the usage the CIP describes:
request 1,000, then 100 more per 100 received). Settling it needs the relay's trace (`Net.Mux.Remote`,
`LeiosNotify.Remote` at Debug are in its own `config.json`, not ours to read) or a local Haskell node
(11.8).

### 11.8 Proposed plan for #73 (devnet prototype)

1. **Get the reference node** in the reference-image mechanism ([reference-image.md](reference-image.md)): the
   Leios team's relay image (`ghcr.io/input-output-hk/ouroboros-leios/cardano-node-testnet`) or a build of
   `cardano-node` `8c44d145` (the w38a release) for the testnet's behaviour, and the proto-devnet of
   `ouroboros-leios` `demo/proto-devnet` (three pools with BLS keys; section 9's vectors come from it) for a
   private network. Record the revision of every dependency as 11.2 does.
2. **Settle the gating on a loopback network, with the reference's own trace.** One Haskell Leios node (no
   public host), the namespaces `Net.Mux.Remote`, `Net.InboundGovernor.Remote`, `LeiosNotify.Remote` and
   `LeiosFetch.Remote` at Debug, and `LeiosChainProbe` as initiator-only, then as duplex with the Praos
   protocols served. Outcome: which hypothesis of 11.7 holds and the first message the responder accepts.
3. **Let the node follow the devnet's Dijkstra chain** (X1, X2): `HfcHeader` in `Peer`/`NodeToNode.connect`,
   `LeiosHeader` and `LeiosBlock` in the header and block paths, `Era.Dijkstra` with its trigger, a
   Dijkstra `EraLedgerState` starting from the Conway rules (the header and block layouts differ; the
   transaction rules mostly do not on a devnet that submits Conway-shaped transactions), and a typed refusal
   for what is not ported (X3), so a Dijkstra block is never applied as a Conway one.
4. **Committee and certificates** (X5): read `bls_key` from the devnet's pool registrations into the ledger
   state (a `PoolParams` field and its CDDL), seat the committee at the epoch boundary with
   `Committee.select`, verify each certificate against its announcing ranking block with section 9's code, and
   compare with the reference's committee query.
5. **Mini-protocols** (X8): carry 18 and 19 in the multiplexer and the servers, serve and consume LeiosNotify
   and LeiosFetch for the devnet, store EBs and closures (X7), hash-check an EB against its announcement.
6. **The scenario** in `scripts/devnet.py`: Haskell Leios pools plus the Scala node as relay; compare
   announcements, EBs, vote bundles, certificates and the committee with the reference's (hash and bytes), and
   the ledger at epoch boundaries (the K1 comparator extended to Dijkstra). Throughput with the conventions of
   [performance.md](performance.md). Stop at the defined scenario and the divergence list if the reference is
   not stable enough.
7. **Regression**: the header capture of this section stays a test (`LeiosHeaderChainSuite` runs the first
   1,100 headers from the genesis; `LeiosCheckMain` runs all 154,698), and a new capture should be taken when
   the testnet respins.
