<!-- SPDX-License-Identifier: Apache-2.0 -->
# Header-first validation and the header clock

Part of [Architecture](../architecture.md).

## Header-first validation (G6)

A header is checked before its block is wanted, as the ouroboros-consensus ChainSync client does
(`Ouroboros.Consensus.MiniProtocol.ChainSync.Client`). In the pipelined peer each batch of
ChainSync instructions passes the session's `HeaderGate` before any block of the batch is requested;
a header that breaks a rule raises `PeerError.InvalidHeader` (the point and a closed `HeaderError`:
`Envelope`, `Praos`, `TPraos`, `Structure`), which ends the session and disconnects the peer, and no
block of that batch is fetched. The candidate chain of a session is thereby a chain of validated
headers (`HeaderChain`: the join point, the header state there and the header state after every
header announced since, with rollbacks and pruning).

**What is checked** (`NodeHeaderRules`, pure; the same consensus functions used for the
block): Byron PBFT and its delegation forecast; first-Shelley handover; and the Shelley-based
envelope (era, protocol version, header and body limits), VRF proof and leader threshold,
operational certificate, KES signature, certificate counter and nonce evolution. TPraos also
checks its overlay schedule and both VRF proofs. `NodeHeaderState` distinguishes the Byron
state with its pending Shelley state from an active Shelley-based protocol state. Header
outcomes are compared with complete block-derived states in `HeaderRulesSuite` and the
unchanged native all-era captures.

**The ledger view is forecast** from the newest ledger state of ours on the peer's chain.
Shelley-based forecasts use the native exclusive horizon: the stability window from Origin,
otherwise the anchor slot plus one plus that window. Byron uses its native exclusive
`NOW + 1 + 2k` bound; first-Shelley translation follows the hard-fork combinator. In the
anchor's epoch the view uses its pool distribution, parameters and due genesis delegations.
The next-epoch view uses the newest stake snapshot and parameters the boundary would adopt,
including TPraos decentralisation and entropy. Envelope checks use those forecast parameters
at epoch boundaries as well.

Every production session with header-first validation enabled uses `HeaderCheck.forNodeChain`.
A missing forecast waits for ledger progress; it never accepts an unchecked header because
an earlier batch finished or a timer elapsed. A candidate that loses its ledger intersection
restarts, and one that is no longer preferred beyond the forecast horizon is refused as the
native ChainSync client does. Streaming publication lets already accepted headers' bodies
advance the ledger while a later header waits. The shared canonical path applies in Praos
and Genesis; GSM, GDD, LoE and ChainSync jumping remain Genesis policies. The explicit
`--header-first off` setting retains block-only validation in Praos mode; Genesis requires
the canonical header path.

**Cost.** The signatures are verified once. The gate's view of the `VerificationCache`
(`forHeaders`) computes a verdict if it is not there and keeps it; the block's own validation, later,
consumes the same verdict, and `warmBlock` no longer overwrites a verdict that is already there. The
signatures of a batch's headers are verified in parallel before the rules fold over them
(`VerificationCache.warmHeaders`). The measurement is in [performance.md](../performance.md).

Tests: `PraosHeaderValidationSuite` (original headers from all seven eras and signature/envelope
refusals before any body request), `HeaderRulesSuite` (valid forged headers validate to the state the block gives, including across
an epoch boundary; a property over eight kinds of tampered header; envelope, counter, horizon and
structure cases), `HeaderGateSuite` (model rules: counting, an invalid header, a header that does
not extend the chain, rollbacks inside the candidate and below the join point, waiting for the chain
and giving up when it stands still, `HeaderChain` as a model), and `InboundGovernorSuite` (a header with a
broken signature drops the peer and the peer's block-fetch server is never asked for a block; when it
is the last header, only the batches before it were fetched).

## Header clock: headers from the future (AUD-01, #153)

Header-first validation checks a header against the ledger forecast, which is a different condition from
the node's clock. The reference also compares the header's slot with the wall clock before it adds the
header to the peer's candidate chain. References are to ouroboros-consensus 4.2.1.0
(`release-ouroboros-consensus-4.2.1.0`, the release cardano-node 11.1.3 pins); the files
`MiniProtocol/ChainSync/Client/InFutureCheck.hs` and the lines cited below are identical on master 5.0.0.0.

**What the reference does.**

- `Node.hs` 589 and 1107-1108: every ChainSync client is built with `realHeaderInFutureCheck
  llrnMaxClockSkew`, and `llrnMaxClockSkew = defaultClockSkew` (`InFutureCheck.hs` 203-207: 2 seconds).
  cardano-node does not expose the skew, so it is not a configuration key here either.
- `Client.hs` 1491: `recordHeaderArrival` reads the wall clock as the first step of `rollForward`, before the
  known-invalid check (1496) and the header rules (`checkValid`, 1513); `setCandidate` (1518) comes after.
- `Client.hs` 1759-1776 (`checkArrivalTime`): the slot is placed on the wall clock with the era history of the
  ledger state at the intersection (`slotToWallclock`, `InFutureCheck.hs` 147-153). If that state cannot place the
  slot (`PastHorizon`) the client waits for the ledger instead; it does not reject.
- `InFutureCheck.hs` 154-175: `ageUponArrival = arrival - onset`; `tooEarly = skew < negate ageUponArrival`,
  that is, the slot begins strictly more than `skew` after the arrival. A header that is not too early is
  delayed: `threadDelay` for `onset - now` (160-164), once, and the ChainSync client of that peer is blocked
  for the delay, so the headers behind it wait too. A header that is too early raises `FarFutureHeaderException`,
  and the client disconnects (`Client.hs` 1775, `InFutureHeaderExceedsClockSkew`). A slot that begins exactly
  `skew` after the arrival waits.
- There is no Genesis or bulk-sync exception. The check runs for every header; a node that is far behind
  needs no special case, because the onset of an old header is long past, `onset - now` is negative and
  `threadDelay` returns at once. The skew compares the header's slot with now, never with the node's tip.
  (What Genesis adds is the historicity check, `HistoricityCheck`, which rejects messages that are too old; it
  is part of the Genesis stage, G3.)

**What Scarab does.** `HeaderClock.clocked` wraps the `HeaderCheck` of a live session, so it applies with
`--header-first on` and `off` alike; a session made without a `HeaderCheck` (offline replay, `--db` replay,
tools, a node restored from a snapshot, which only sees headers of its peers) never meets it. For each
batch of ChainSync updates:

1. The arrival time is read once, as the batch reaches the gate. It lags the reference's per-header arrival by the
   decode time of the batch.
2. `HeaderEligibility.judge` places each header's slot with the era history of the node's chain
   (`SlotClock`: the wall clock, and `EpochGeometry` as the chain knows it now) and returns `Eligible` (the
   slot has begun), `WaitUntil(onset)` (it begins within the skew) or `FarFuture` (strictly beyond it). The
   arithmetic is exact over the slot number the peer chose; it cannot overflow.
3. A `FarFuture` header raises `PeerError.InvalidHeader(point, HeaderError.FarFuture(slot, aheadMillis,
   skewMillis))`; `FailurePolicy.classify` makes it `Misbehaved`, so the governor forgets the peer (owner
   decision 8). Nothing of the batch is validated or fetched, and nothing waits first.
4. Otherwise the batch waits until the latest onset among its headers, reading the wall clock again after each
   sleep (a clock that stepped back is waited out), and then goes on to header-first validation. Block
   selection never sees a header before its slot begins.

**Deviations, with reasons.** (a) The reference adds the headers before a waiting header to the candidate at
once; here the batch is released together at the latest onset, at most the skew later, because the gate hands a
batch on as a unit (`Peer.scala`). (b) The reference decides the far-future header after the ones before it have been
added; here a far-future header anywhere in a batch refuses the batch, which is stricter only for the headers
that precede it in that batch. (c) While the Byron span is still derived (the Byron ledger has not yet seen the
transition to Shelley), a Shelley slot cannot be placed. Reading it as a Byron slot would put it years ahead, so
the slot is placed with the shorter slot length: that is never later than the true onset, so no honest header is
refused, at the price of not judging such a header until the ledger has caught up (the reference waits for the
ledger there). (d) The skew is the reference's 2 seconds, fixed.

Tests: `HeaderClockSuite` (slot onset, the skew boundary in both directions, a property over the verdict,
overflow-safe slots, the derived span, a header that waits and is then offered, a refusal that does not wait,
a waiting header holding its batch, steps of the clock in both directions, reconnection, old headers, 200000
stale clock reads in `tailRecM`) and `InboundGovernorSuite` (a peer whose headers are in the future is dropped
with the typed error while a healthy peer is followed). The devnet has 0.1 second slots, so its skew is 20 slots;
making a producer's clock run ahead needs a test-only flag in the forger, so the typed refusal is exercised in
the suites rather than as a scenario, and the devnet scenarios show that honest peers are never delayed or refused.
