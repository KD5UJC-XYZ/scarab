# Ouroboros Genesis (#33)

Status: current implementation; review corrections rebased onto current main in `1399b69a5`
(2026-10-22). The original stage acceptance at `43d940871` remains separately recorded.
Main integration and CI remain the definition-of-done boundary for #33. See the
[acceptance evidence](../compatibility.md#evidence-log).

The separate network follow-ups remain [rolling ChainSync pipelining (#310)](https://github.com/KD5UJC-XYZ/scarab/issues/310)
and [Genesis churn regimes and targets (#311)](https://github.com/KD5UJC-XYZ/scarab/issues/311).
The Genesis scenario acceptance here does not establish those separate scheduler policies.

## Reference and scope

The behavioral reference is consensus `82ecba329d7d054340bf707d44fe6e9ac27cec40`
and network `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`, as pinned for
cardano-node 11.1.3. The implementation follows the production Genesis design,
including its resource policies. The original paper's deep-fork comparator is
not substituted for the implemented GDD/LoE architecture. Paper statements and
implementation conformance supply different evidence.

[#33](https://github.com/KD5UJC-XYZ/scarab/issues/33) owns the complete integration;
the existing governor hooks and Praos fetch coordination do not establish Genesis
support. This work does not alter the network's signed block formats or ledger
transition predicates. It changes which validated chains may be selected, when
selection is retriggered, and how upstream work and disconnections are coordinated.

## Shared early-header validation (#318)

`NodeHeaderRules` and `HeaderCheck.forNodeChain` implement the same canonical all-era
header path for Genesis and header-first-enabled Praos sessions. This enables Byron
PBFT checks, the initial Shelley forecast and parameter-dependent epoch-boundary
envelopes in Praos without enabling GSM, LoE, GDD, LoP or jumping. Praos retains the
explicit `--header-first off` setting.

At the consensus pin above, `MiniProtocol/ChainSync/Client.hs:1498–1510` obtains the
intersection-ledger time forecast before validating the header;
`HeaderValidation.hs:219–230` ticks the chain-dependent state with the forecast ledger
view. The shared implementation preserves this ordering, Byron's exclusive
`NOW+1+2k` forecast (`Byron/Ledger/Ledger.hs:268–320`) and the initial Shelley forecast
(`CanHardFork.hs:421–470`). A missing forecast waits for ledger progress; it cannot
fall back to an unchecked header. The private `ShelleyHeaderForecast` checks the
same forecast parameters and envelope on both sides of an epoch boundary.

`PraosHeaderValidationSuite` uses actual ChainSync/BlockFetch clients without a
Genesis runtime, original signed headers from all seven eras, and typed signature
and epoch-boundary envelope refusals before any body request. Existing original
all-era captures and the unchanged Genesis replay corpus remain separate controls.

## Shared contracts

- **Candidate state:** publish original decoded headers and the validated header-state
  history independently of body fetching/application. Track the most recently received
  slot separately, including a header not yet within the forecast horizon, and retain
  `AwaitReply` state. GDD never counts an unchecked header as validated.
- **Selection state:** read the immutable anchor, complete volatile selection and the
  immutable ledger's era-history query coherently. An unavailable Genesis-window query
  postpones density comparisons; it does not choose a convenient window.
- **Control state:** one GSM phase drives LoE, LoP configuration and devoted fetching;
  one registry covers all participating ChainSync clients, including duplex clients.
  Versioned notifications prevent missed wakeups. No caller-scoped error capability
  is retained in a worker.
- **Jump state:** a jump carries both chain fragments, the most recent intersection
  and validated header-state history. Accepting an intersection grafts the matching
  original history; it must not manufacture validation state or count replay as progress.
- **Work accounting:** distinguish actual outstanding transport requests from the
  requests currently influencing devoted-peer choice. Handover does not erase old
  byte/request credit, reassign ordered replies, or release another source's attribution.
- **Restart retention:** persist received original bodies before exposing them to selection,
  including forks and LoE-postponed blocks. A separate `received/` log reuses the existing
  volatile-record encoding; the adopted-chain log records the previously adopted suffix.
  Startup ranks the complete received forest from the immutable anchor, preserving the native
  initial `k`-block cap when LoE is enabled (`ChainSel.hs:115–211`). It reuses the selected
  suffix already validated by storage resume when that suffix wins; it does not persist a
  temporary rollback or apply those blocks twice. A different winner reuses the validated
  common prefix before switching. Older databases populate this store from their known
  selected suffix without manufacturing missing fork history. Validated selected originals
  repair inconsistent cache entries before recovered extras are checked. Malformed extras
  are logged with their typed cause and excluded from cache lookup, permitting a later valid
  receipt; their journal bytes are checked again at reopen until replacement or collection.
  Selected-journal validation keeps its valid-prefix recovery policy; storage IO failures
  still propagate. Recovered bodies have their
  own source case, rather than borrowing a live session identity. Collection follows a durable
  immutable boundary; an LoE stall can retain a growing forest. With a received log the forest
  holds each block's metadata and reads its body back from the log (#416); without one the raw
  bodies stay in memory.
  [Measured scan and idle-decision costs](../performance/peers-and-network.md#39-received-forest-selection-and-idle-decisions-309-review)
  state the scope of this retention policy.
- **Source attribution:** an original body's punishment belongs to its adding BlockFetch run,
  not a peer address or the lifetime of its TCP connection. The native client captures its
  thread at `ClientInterface.hs:192–193`; BlockFetch is a hot protocol and each activation
  starts a fresh run (`NodeToNode.hs:252–272`, `PeerStateActions.hs:979,1000–1016,1164–1195`).
  Retain the typed refusal after that run ends, without terminating a subsequent run on
  either the same bearer or a replacement connection. Register attribution before a run
  can publish bodies and retire its abort callback after its hot protocols have drained.
  Retirement also follows successful BlockFetch `ClientDone`, before another hot protocol's
  remaining drain can delay warm return. Receiving tentative invalid tips follows the pin's
  diffusion-pipelining punishment criterion; FIFO request pipelining is a separate mechanism.

## Mechanisms and resource policy

| Mechanism | Required behavior and engineering balance |
|---|---|
| GSM | `PreSyncing` caps selection at the immutable boundary until honest-availability inputs hold. `Syncing` enables the defenses. `CaughtUp` restores Praos behavior; catching up depends on peer idleness and chain preference, not merely a young tip. Preserve the caught-up marker and stale-tip transition behavior across restart. |
| LoE | Limit selection to at most `k` headers beyond the peers' shared prefix. Treat a temporarily disconnected candidate conservatively as empty at the immutable anchor. The shared prefix may extend beyond the selected chain. A regressing limit does not itself force rollback. Advancing or disabling LoE must reconsider retained blocks without requiring another block arrival. |
| GDD | Preserve lower/upper density bounds, strict window endpoints, unknown versus known-Origin slots, AwaitReply treatment, equal-density and CSJ cases, and ordered disconnection outcomes. Query the window at the first slot after the shared prefix. Rate-limit evaluation to avoid repeatedly scanning candidates on every header. |
| LoP | Debit exact elapsed time, replenish only for a new record-high validated block number, cap overflow and preserve the record across rollback. A GSM configuration transition refills the bucket. Pause for native AwaitReply, clock/forecast and jumping conditions; do not add a local backpressure exemption. |
| CSJ | Preserve registration order, dynamo/jumper/objector transitions, jump trimming, accepted/rejected intersection behavior, historical checks and rotation. Grafting updates the record-high block number without earning a token. One peer supplies the bulk of the headers while other peers check agreement. |
| Devoted BlockFetch | Retain a peer while it supplies the desired chain fast enough to feed validation. The grace-period rule needs an actual selection-work starvation observation. Rotation may retry a block still outstanding elsewhere; retain that old transport accounting and preserve the first successful source. Grace expiry deprioritizes rather than automatically disconnects the peer. |
| Checkpoints | Preserve configured lightweight checkpoint validation and provenance. They are trust inputs, not replacements for the missing mechanisms or a way to make failing tests pass. |

Production defaults are taken from `Node/Genesis.hs`, not copied from illustrative
values in the high-level design:

| Setting | Pinned default | Consequence |
|---|---:|---|
| LoP capacity | 100,000 tokens | 200 seconds of initial slack at the configured rate; tolerates pauses while bounding a withholding peer's delay. |
| LoP drain | 500 tokens/second | One newly validated record-high header earns one token; block-validation backpressure must not throttle the header reader and then charge the peer for that throttle. |
| GDD interval | 1 second | Bounds comparison CPU cost; does not replace event-driven selection wakeups. |
| CSJ jump size | 4,320 slots | Uses the Byron-compatible forecast range rather than assuming Shelley throughout. |
| Historicity cutoff | 133,200 seconds | Preserves the pin's historical-message treatment and its margin beyond the mainnet Shelley stability window. |
| BlockFetch grace | 10 seconds | Allows initial network latency before selection starvation can justify trying another provider. |

`LowLevelGenesisOptions` follows the reference configuration fields. Tests with
explicit small-network parameters must retain their input configuration and compare
the same configuration on both implementations; production defaults are not changed
to make scenarios pass.

## Body admission and selection ownership

The block-selection worker belongs to the node, independently of the peer that delivers a body
or the session that announced its headers. Mirror the native bounded add queue (capacity 10,
`ChainDB/Args.hs:122`): BlockFetch waits for queue capacity, while written/processed promises belong
to the worker (`BlockFetch/ClientInterface.hs:192–228`). Queued original bodies count as fetched
(`ChainDB/Impl/Query.hs:225`), but the candidate forest contains only bodies the worker has actually
processed (`ChainSel.hs:449`). A later queued body must not affect an earlier admission's selection.

Each successful first admission carries its original bytes, checked header and immutable sending
BlockFetch-run context. Duplicate deliveries do not create another admission or change its source.
One serial worker processes that FIFO, records semantic invalidity and applies the native punishment
criterion, then reinserts any valid prefix into that admission’s original candidate pool. It
projects every surviving path through LoE and eligibility before ordering; only the final valid
winner commits. Recovery and LoE reconsideration
carry no new adding client. Peer cancellation cannot remove a queued admission or cancel ledger
selection. Worker shutdown and infrastructure failure settle current and queued work, following
`Background.hs:658–708` and `Types.hs:740–755`; they never masquerade as a peer refusal.

Startup resets selection to the immutable anchor and considers the complete recovered forest,
including originals beyond the ordinary delivery-cache capacity. Opening network clients and servers
waits for that initial selection to finish. Readiness failures land through the acquiring caller’s
error capability; the long-lived worker creates its own scope. Mempool updates use the worker’s
current error capability and follow the selected state, including an immutable-only startup result.

Eligibility and candidate ordering are separate upstream operations. Hard-fork eligibility uses
the later era’s configuration across Shelley-based eras; Byron/Shelley has no equal-height
tie-breaker. Global candidate and fetch priority use unrestricted native `Ord`, with the native
stable list-sort comparison sequence. The finite native observer covers 24 protocol-view cases,
seven explicitly synthetic sort cases, and 55 actual Cardano hard-fork cases over Byron through
Conway. These are independently observed boundary cases, not upstream-generated or exhaustive inputs.

## Handshake and negotiated Mux ownership

The native connection handler runs a raw handshake bearer before constructing the
negotiated application Mux (`ConnectionHandler.hs:335–381`). Scarab preserves that
consumed-SDU boundary with a bounded one-SDU handoff. The handshake consumes payloads
regardless of their incoming protocol/direction labels, as `bearerAsChannel` does.
Decoder trailing bytes within its final consumed SDU are discarded; the next SDU
is retained for application dispatch. The reader waits for installation before
starting that next SDU’s deadline. Native framing still rejects zero-length SDUs.

Both inbound acceptance and outbound negotiation, including client-only connections,
install the actual protocol/direction map. Absent PeerSharing and late Handshake
traffic are rejected before queueing or responder activation. Already acquired
channels and queued egress obey the installed map. Installation and wakeup signals
are cancellation-atomic, with errors raised through the acquiring caller’s capability.
KeepAlive uses native `StartOnDemandAny`; an unused reverse direction starts no
97-second deadline. SDU deadlines remain 10 seconds during handshake and 30 seconds
for the application.

`NativeHandshakeBoundarySuite`, `NegotiatedDispatchSuite` and
`ResponderActivationSuite` exercise original-byte retention, next-frame dispatch,
large final-SDU trailing payloads, fragmented handshakes, installation pauses,
negotiated roles, cancellation and deadline behavior. The complete reader also
passes 100,000 SDUs on a 256 KiB stack. These checks assert observable boundaries,
not identical thread schedules.

## Implementation sequence and acceptance

1. Pure LoE/GDD, bucket, jumping and devoted-fetch decisions, with closed types and
   complete native observation comparisons. Preserve original generators and every
   generated input/output in the selected finite corpus.
2. Streaming header validation and coalesced candidate publication, bounded by the
   actual forecast/history requirements. Body dispatch keeps its own bounded queue.
   Full-era validation, clock waits, forecast waits and grafted histories must remain
   valid across rollback, reconnect and cancellation.
3. Integrate the shared registry, GSM, selection cap/retrigger, historical checks,
   CSJ and devoted-fetch work accounting into outbound and inbound clients. Test
   starvation and published-body handover races with controlled observations.
4. Replay original upstream Genesis scenario families through Scarab using the
   exported inputs and schedules, comparing complete selected fragments and every
   original native termination predicate. Assert every native peer/component multiplicity,
   exact typed cause attribution, production refusal-decision-before-termination and per-peer cleanup
   causality, plus the complete ordered GDD callback batches. The original DensityDisconnect
   property permits either density or sparse-candidate refusal; CSJ/Uniform retain their original
   trace/safety properties and closed exception set. Independent per-peer registries do not impose
   a cross-peer finalizer schedule. Preserve both full transcripts for diagnosis; wrong causes or
   required causal orders fail the gate. See the [native contract](../../fixtures/reference/genesis-density/README.md).
   Run Docker `check`, private Genesis sync/restart,
   relay and the issue's soak gate. Record performance and cleanup receipts.
5. Preserve the reference's invalid-block punishment boundary: reject transport/body-hash
   mismatches immediately, retain semantic-invalidity attribution, apply the native
   tentative-header criterion per BlockFetch run, and reject known-invalid prefixes without
   disconnecting merely for a first forgiven tentative tip. Compare native policy state and
   outcomes and test continued rollback/recovery through the actual client runtime.

No expected-result masks, disabled assertions, excluded generated cases or compatibility
waivers are permitted. A reference crash, input adapter problem or behavioral mismatch
remains a failing acceptance result until resolved. A bounded seed count is an explicit
sampling choice, never a claim of exhaustive coverage. Matching outputs on those inputs
does not establish the paper's security theorem or identical thread scheduling.

The existing conformance-source CI check watches the pinned consensus implementation,
original Genesis scenarios, schedule generators, peer simulator, CSJ and bucket tests.
The network watch includes devoted-fetch decisions and concrete connection identity.
Its pin comes from the maintained native fixture manifest. Drift is a review signal;
the check neither refreshes fixtures nor substitutes source freshness for execution.

The private runtime gates explicitly select Genesis for Scarab; leaving the mode
unspecified exercises the harness's existing configuration instead:

```sh
python3 scripts/devnet.py --name scarab-33-restart restart --consensus-mode GenesisMode
python3 scripts/devnet.py --name scarab-33-relay relay --consensus-mode GenesisMode --no-peer-sharing
python3 scripts/devnet.py --name scarab-33-soak soak --consensus-mode GenesisMode
python3 scripts/devnet.py --name scarab-33-e2e e2e --consensus-mode GenesisMode
```

The private Genesis relay disables peer sharing so its explicit trusted local pools satisfy
`LocalRootsOnly` honest availability. Unrestricted Genesis instead requires enough active
big-ledger peers; this private topology supplies none. Peer-sharing coverage uses the
separate relay scenario with its ordinary Praos configuration. The inbound observation
uses the configured sync timeout: a normal missing-intersection return can require the
reference's 120-second repromotion delay before the same bearer follows again.

The option writes a separate Scala configuration next to the copied genesis files.
It does not change the reference pools' configuration. Restart forwards the mode
to both processes, and soak forwards it to its follower. Each run uses its own
disposable directory and is removed with the corresponding `down` command after
retaining compact results and reproduction identities.

Genesis runs also write an explicit topology marking only that run's controlled
reference pools as trustable local roots. Plain `--peer` inputs are untrusted in
Scarab and cannot establish the honest-availability assumption needed to leave
`PreSyncing`. No bootstrap peers, guessed ledger snapshot or forced GSM phase are
introduced by the harness.
