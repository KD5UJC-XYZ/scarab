<!-- SPDX-License-Identifier: Apache-2.0 -->
# Glossary

Terms that recur in issues, commits and documents, with the place that defines each. Checked by `python3 scripts/check_onramp.py`: every symbol, path and anchor in "Where defined" exists. Where to look for a question is in [questions.md](questions.md); decisions are in [decisions.md](decisions.md).

## Work tracking

| Term | Meaning | Where defined |
|---|---|---|
| Roadmap ID | A stable item ID (letter and number, for example B21); issues and commits cite it; done items are marked, never renumbered | [roadmap.md](roadmap.md) |
| P0, P1, P2 | Priority: P0 needed for the research preview, P1 for a full reference-compatible node, P2 hardening | [roadmap.md](roadmap.md#roadmap) |
| A series | Publication and project hygiene | [roadmap A](roadmap.md#a-publication-and-project-hygiene) |
| B series | Rules implemented but not yet checked against the reference | [roadmap B](roadmap.md#b-unconfirmed-rules-implemented-but-not-yet-checked-against-the-reference-p1) |
| C series | Byron ledger and consensus | [roadmap C](roadmap.md#c-byron-p1-needed-for-preprod-and-mainnet) |
| D series | Scale: memory, throughput, scheduling, persisted formats | [roadmap D](roadmap.md#d-scale-p1) |
| E series | Fast start: Mithril bootstrap and Haskell snapshot import and export | [roadmap E](roadmap.md#e-fast-start-p1) |
| F series | Node-to-client: queries, submission, failure encodings | [roadmap F](roadmap.md#f-node-to-client-p1) |
| G series | Networking: governor, connections, Genesis, versions, limits | [roadmap G](roadmap.md#g-networking-p1p2) |
| H series | Block production and the pool | [roadmap H](roadmap.md#h-block-production-p1p2) |
| I series | Plutus: conformance, evaluator, era coverage | [roadmap I](roadmap.md#i-plutus-p2) |
| J series | Operational parity: configuration, CLI, observability | [roadmap J](roadmap.md#j-operational-parity-p2) |
| K series | Verification at scale: oracle comparison, conformance, fuzzing, long runs | [roadmap K](roadmap.md#k-verification-at-scale-p1) |
| L series | Future eras (Dijkstra) | [roadmap L](roadmap.md#l-future-eras-p2) |
| M series | Code hygiene from the pre-publication audit | [roadmap M](roadmap.md#m-code-hygiene-from-the-pre-publication-audit) |
| N series | Ouroboros Leios | [roadmap N](roadmap.md#n-ouroboros-leios-v03) |
| O series | Review readiness and operations | [roadmap O](roadmap.md#o-review-readiness-and-operations-v03) |
| Lower-case suffix | A sub-item of a roadmap item (J3c is the dashboards under J3) | [roadmap J](roadmap.md#j-operational-parity-p2) |
| Dotted suffix | A stage of an item in its design document (G1.2 is stage 2 of the governor design) | [design peer-selection](design/peer-selection.md) |
| -F suffix | A numbered follow-up of an item (G5-F1, G7-F3) | [roadmap G](roadmap.md#g-networking-p1p2) |
| REF-nn | A reference-equivalence item (REF-01 to REF-12, issues #163 to #177) | [roadmap B](roadmap.md#b-unconfirmed-rules-implemented-but-not-yet-checked-against-the-reference-p1) |
| X1 to X13 | Leios work items under N4, titled `[N4-Xn]` | [roadmap N](roadmap.md#n-ouroboros-leios-v03) |
| IN-nn | An entry of the implementer notes: a reference behaviour or specification gap | [implementer-notes.md](implementer-notes.md) |
| AUD | The first independent audit of main; findings are issues titled `[AUD-nn]` | [process.md](process.md#findings-from-an-independent-audit) |
| AUD2 | The second audit round; issues titled `[AUD2-nn]` | [process.md](process.md#findings-from-an-independent-audit) |
| AUD3 | The reference-equivalence audit round (tracked in #294); issues titled `[AUD3-nn]` | [process.md](process.md#findings-from-an-independent-audit) |
| Boundary, trusted assumptions, invariant | The fields of an audit ticket: what it concerns, what it relies on, what must hold | [process.md](process.md#findings-from-an-independent-audit) |
| Confirmed from source, agent-read | Evidence markers in a finding: lines read on both sides, against lines a review pass cited that still need a confirming read | [write-an-audit-issue](playbooks/write-an-audit-issue.md) |
| K2 follow-up | An issue titled `[K2 follow-up]` that closes a gap left by the upstream conformance work | [roadmap K](roadmap.md#k-verification-at-scale-p1) |
| J1.x, J2.x, J3.x | Stage x of jump N of the ledger-mirror step 2 (J2.9 removed the old rules); not the roadmap items J1 to J3 | [design ledger-mirror-step2](design/ledger-mirror-step2.md) |
| Jump | One of the three larger steps of mirror step 2: edges, rules, gaps | [ADR 0009](adr/0009-ledger-mirror-step-2.md) |
| D1 to D10 (design) | Decision numbers inside a design document, such as step 2 D3; not the roadmap D series | [design ledger-mirror-step2](design/ledger-mirror-step2.md#1-decisions-for-the-owner-my-recommendation-first) |
| Level of change | The ceremony ladder 0 to 4 that fixes which gates a change runs and what paperwork it carries | [CONTRIBUTING](../CONTRIBUTING.md#gates-and-ceremony-by-level-of-change) |
| Gate | A check a change passes before it lands: `check`, plus one devnet scenario for the area | [CONTRIBUTING](../CONTRIBUTING.md#gates-and-ceremony-by-level-of-change) |
| Status label | `status:` label on an issue; state lives in labels, not in text | [process.md](process.md#lifecycle-of-an-item) |
| owner-review | Label for the owner's review feedback, triaged by rule | [CONTRIBUTING](../CONTRIBUTING.md#owner-review-feedback) |
| Changelog fragment | A file in `changes/` that a release folds into the changelog | [changes/README.md](../changes/README.md) |
| Document status | Current, proposal, spike or historical, on the first line of a document | [process.md](process.md#documents-have-a-status) |
| Evidence log | The dated record of scenario runs with commit, command and image | [compatibility.md](compatibility.md#evidence-log) |
| Compatibility matrix | What is verified, against what, with limitations | [compatibility.md](compatibility.md#matrix) |
| Findings | Behaviours learned against real chain data | [findings.md](findings.md) |
| Codemap | The generated map of modules, packages, entry points and suites | [codemap.md](codemap.md) |
| Docs baseline | The ratchet of known documentation drift; it only shrinks | `docs-check-baseline.json` |
| Pin | A registered upstream revision that documents may cite | `reference-pins.json` |

## Reference and verification

| Term | Meaning | Where defined |
|---|---|---|
| Reference | The Haskell cardano-node (11.1.3) whose types, formats and behaviour are mirrored | [ADR 0001](adr/0001-haskell-node-as-reference.md) |
| Mirror | The Haskell-shaped, era-indexed ledger and consensus state, with conversions to and from the older flat state | `MirrorState` |
| Era-indexed | State typed by a phantom era with a closed witness, so another era's data is unreachable | [ADR 0007](adr/0007-era-indexed-state.md) |
| Flat state | The node's older own state shape, kept in `legacy` for the genesis start path | `FlatNodeState` |
| Oracle | A tool that runs unchanged upstream code to produce expected results for comparison | `scripts/oracle/` |
| Devnet | A local cluster of reference and Scarab nodes run in Docker | [running.md](running.md#devnet-scenarios-scriptsdevnetpy) |
| Scenario | One devnet run with its own checks: e2e, relay, pool, restart, ntc, kes-rotate | `scripts/devnet/scenarios/` |
| relay300 | The network gate: the `relay` scenario run for 300 seconds | `relay.py` |
| Soak | The long mixed scenario run nightly | `soak.py` |
| Projection | The view of node state that is dumped and compared with the reference | `Projection` |
| K1 oracle | The db-analyser comparison of ledger state at epoch boundaries | [roadmap K](roadmap.md#k-verification-at-scale-p1) |
| Original-bytes rule | Anything hashed or signed uses the bytes as received, never a re-encoding | [CONTRIBUTING](../CONTRIBUTING.md#style-rules) |
| Fixture register | The inventory of fixtures with provenance | `fixture-inventory.json` |
| Coverage register | The register of reference coverage per area, with its required policy | `test-coverage-register.json` |

## Storage and formats

| Term | Meaning | Where defined |
|---|---|---|
| V5 | The current native snapshot format: Haskell ledger-state CBOR plus a typed sidecar, with framing and a checksum | `FormatVersion.V5` |
| Sidecar | The typed binary part of a V5 snapshot that carries what the Haskell layout does not (extras, gaps, observed eras) | `SnapshotSidecar` |
| Gap | A named part of the mirror that the source state did not carry, saved with the snapshot | `Gap` |
| File kind | A persisted file family with a version history and a readable-from version | `FileKind` |
| Preflight | The read-only check of identity and supported versions before any store is opened for writing | `ChainStorage.preflight` |
| Network identity | The record binding protocol magic and the genesis inputs to a database | `NetworkIdentity` |
| Immutable and volatile | The settled chain and the last k blocks that can roll back | `ImmutableStore` |
| UTxO store | The RocksDB-backed unspent output set | `UtxoStore` |
| SCLS | The canonical ledger-state interchange format of cardano-cls | [design scls-interchange](design/scls-interchange.md) |
| Mithril | Certified snapshot bootstrap of the immutable database | `MithrilBootstrap` |
| MemPack | The reference's binary output encoding, used only at snapshot import and export | [design haskell-snapshots](design/haskell-snapshots.md) |

## Ledger and consensus

| Term | Meaning | Where defined |
|---|---|---|
| Ledger-lite | The part of the pre-Conway ledger that header validation needs, kept from block bodies without a UTxO in the bootstrap follow mode | `LiteLedger` |
| Phase 1, phase 2 | Structural ledger validation, then Plutus script execution | `Phase2` |
| STS | A state transition system: a rule over environment, state and signal, run by an interpreter | `Sts` |
| HFC | The hard-fork combinator: carries the state across eras and ticks epoch boundaries | `Hfc` |
| Telescope | The era-indexed chain state of the combinator | `Telescope` |
| Pulser | The incremental reward calculation that advances at the reference's pulse and force points | [ADR 0019](adr/0019-incremental-reward-calculation.md) |
| Mark, set, go | The three stake snapshots taken at epoch boundaries | `SnapShots` |
| Praos, TPraos | The Shelley-based consensus protocols | `Praos` |
| KES | The key-evolving signature scheme of block signing, Sum6 | `Sum6Kes` |
| DecodeSite | The closed description of where in a ledger value a decoder was reading when it failed | `DecodeSite` |
| Compute pool | The bounded pool on which CPU-bound ledger work runs, apart from the runtime's timers | `Compute` |
| Canonical headers | The one all-era header validation path shared by early header checks and block validation | [design genesis](design/genesis.md#shared-early-header-validation-318) |
| Header-first | Validate every header of a ChainSync batch before fetching any of its blocks | `HeaderGate` |

## Network

| Term | Meaning | Where defined |
|---|---|---|
| NtN, NtC | Node-to-node and node-to-client protocol versions | `NtnVersion` |
| Mux | The multiplexer that carries mini-protocols over one connection | `Mux` |
| Governor | The pure peer-selection decision function over cold, warm and hot peers | [ADR 0016](adr/0016-peer-states-and-the-governor.md) |
| Local roots, ledger peers | Configured peers and peers read from registered pool relays | `LocalRoots` |
| Genesis | Ouroboros Genesis: the safe-bootstrap rules around selection | [design genesis](design/genesis.md) |
| GSM | The Genesis state machine: PreSyncing, Syncing, CaughtUp | `Gsm` |
| LoE | Limit on eagerness: selection stays within k headers of the peers' shared prefix | [design genesis](design/genesis.md#mechanisms-and-resource-policy) |
| GDD | Genesis density disconnection: compares header density of candidates over a window | `GenesisDensity` |
| LoP | Limit on patience: a per-peer token bucket on the time spent waiting for new headers | `LimitOnPatience` |
| CSJ | ChainSync jumping: one peer supplies the bulk of the headers while others check agreement | `ChainSyncJumping` |
| Rolling pipeline | ChainSync requests issued and collected continuously between low and high marks | `ChainSyncPipeline` |
| Dijkstra, Leios, Peras | The next era, the concurrent-block-production proposal and the voting-based settlement proposal | [leios.md](leios.md) |
