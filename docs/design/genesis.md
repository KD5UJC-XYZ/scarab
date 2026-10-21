# Ouroboros Genesis (#33)

Status: implementation in progress on `feature/33-genesis`; acceptance has not passed.

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
  volatile-record encoding; the adopted-chain log still determines the recovered selected
  tip. Older databases populate this store from their known selected suffix without
  manufacturing missing fork history. Recovered bodies have their own source case, rather
  than borrowing a live session identity. Collection follows a durable immutable boundary.

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
   exported inputs and schedules, comparing their complete observable selections
   and termination outcomes. Run Docker `check`, private Genesis sync/restart,
   relay and the issue's soak gate. Record performance and cleanup receipts.

No expected-result masks, disabled assertions, excluded generated cases or compatibility
waivers are permitted. A reference crash, input adapter problem or behavioral mismatch
remains a failing acceptance result until resolved. A bounded seed count is an explicit
sampling choice, never a claim of exhaustive coverage. Matching outputs on those inputs
does not establish the paper's security theorem or identical thread scheduling.

The private runtime gates explicitly select Genesis for Scarab; leaving the mode
unspecified exercises the harness's existing configuration instead:

```sh
python3 scripts/devnet.py --name scarab-33-restart restart --consensus-mode GenesisMode
python3 scripts/devnet.py --name scarab-33-relay relay --consensus-mode GenesisMode
python3 scripts/devnet.py --name scarab-33-soak soak --consensus-mode GenesisMode
```

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
