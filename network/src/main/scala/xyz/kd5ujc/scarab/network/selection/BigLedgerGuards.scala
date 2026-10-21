// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** The decisions about big ledger peers: the six families the reference lists first in each of its
  * groups (`Governor.hs` 695-727, ouroboros-network `main` 1.3.0.0, whose files agree with the pin
  * at the cited lines):
  *
  *   - `BigLedgerPeers.belowTarget` and `aboveTarget` (`Governor/BigLedgerPeers.hs`): ask for more
  *     big ledger peers below the known target, forget cold ones above it;
  *   - `EstablishedPeers.belowTargetBigLedgerPeers` (342-430) and `aboveTargetBigLedgerPeers`
  *     (780-882): connect below the established target, close above it;
  *   - `ActivePeers.belowTargetBigLedgerPeers` (122-198) and `aboveTargetBigLedgerPeers` (679-754):
  *     activate below the active target, deactivate above it.
  *
  * Big ledger peers are a population of their own: their sets are [[GovernorState.Big]], and the
  * ordinary families do not see them ([[GovernorState.View]]). The two that make progress (the
  * request, and the promotions to warm and hot) are off in the sensitive state, where the node
  * talks only to trusted peers (`enableProgressMakingActions`, `Cardano/.../Governor/Types.hs`
  * 170-172); forgetting and the demotions are not, so the targets of zero a node sets on becoming
  * too old shed its big ledger peers like all others.
  *
  * A separate file from [[PeerSelection]] on purpose: `decide` lists these families in the
  * reference's order and nothing else of it knows what is in them.
  *
  * Deviations: the number of warm big ledger peers to close and of hot ones to deactivate takes the
  * transitions in progress *among the big ledger peers* where the reference subtracts those of all
  * peers (`EstablishedPeers.hs` 848-849), so an ordinary demotion in flight does not delay a big
  * one.
  */
private[selection] final class BigLedgerGuards[A](
    st: GovernorState[A],
    view: GovernorState.View[A],
    now: Time,
    hotScore: A => Option[HotScore]
)(using Ordering[A]):
  import PeerSelection.Guard
  import Guard.*

  private val big: GovernorState.Big[A] = view.big

  /** Everything is as the reference's `enableProgressMakingActions`: off in the sensitive state. */
  private def progress: Boolean = !st.sensitivity.sensitive

  private def withStatus(peers: Iterable[A], status: Status): GovernorState[A] =
    st.copy(peers = peers.foldLeft(st.peers) { (m, p) =>
      m.updated(p, m(p).copy(status = status))
    })

  /** `BigLedgerPeers.belowTarget`: below the known target, ask for more, not while a request is in
    * flight and not before the retry time. (The reference does not name the retry time as a
    * wake-up, `GuardedSkip Nothing`; the governor here would otherwise wait for an unrelated event
    * to ask again.)
    */
  def request: Guard[A] =
    val missing = st.targets.knownBig - big.known.size
    if progress && missing > 0 && !st.bigLedgerInFlight then
      if now >= st.bigLedgerRetryAt then
        val (_, next) = st.rng.split
        Go(
          Decision(
            st.copy(bigLedgerInFlight = true, rng = next),
            List(Job.RequestBigLedger(missing, st.rootsGeneration)),
            List(Trace.BigLedgerRequest(st.targets.knownBig, big.known.size))
          )
        )
      else Skip(Some(st.bigLedgerRetryAt))
    else Skip(None)

  /** `BigLedgerPeers.aboveTarget`: above the known target, forget cold big ledger peers that are
    * not being connected to.
    */
  def forget: Guard[A] =
    val known = big.known.size
    val available = big.known -- big.established -- big.connecting
    if known > st.targets.knownBig && known > big.established.size && available.nonEmpty then
      val (picked, next) =
        Picks.forget(available, known - st.targets.knownBig, st.rng, p => st.peers(p).failCount)
      Go(
        Decision(
          st.copy(peers = st.peers -- picked, rng = next),
          Nil,
          List(Trace.ForgetBigLedger(st.targets.knownBig, known, picked))
        )
      )
    else Skip(None)

  private def retryTimes: Option[Time] =
    st.peers.values.collect {
      case PeerRecord(Status.Cold(Some(at)), Origin.BigLedger, _, _, _, _, _, _, _) if at > now =>
        at
    }.minOption

  /** `EstablishedPeers.belowTargetBigLedgerPeers`: connect to the shortfall of the established
    * target among the cold big ledger peers that are available, as an outbound duplex connection.
    */
  def connect: Guard[A] =
    val missing = st.targets.establishedBig - big.established.size - big.connecting.size
    val available = big.availableToConnect -- big.established -- big.connecting
    if progress && missing > 0 && available.nonEmpty then
      val (picked, next) = Picks.promotion(available, missing, st.rng)
      Go(
        Decision(
          withStatus(picked, Status.Connecting).copy(rng = next),
          picked.map(p =>
            Job.Connect(
              p,
              DiffusionMode.InitiatorAndResponder,
              Provenance.Outbound,
              st.rootsGeneration
            )
          ),
          List(
            Trace.PromoteBigLedger(
              Temperature.Cold,
              picked,
              st.targets.establishedBig,
              big.established.size
            )
          )
        )
      )
    else if missing > 0 then Skip(retryTimes)
    else Skip(None)

  /** `EstablishedPeers.aboveTargetBigLedgerPeers`: above the established target, close warm big
    * ledger peers that are not being promoted, demoted already or lingering; the number is the
    * smaller of the excess over the target and the warm peers there are.
    */
  def close: Guard[A] =
    val warm = big.established -- big.active
    val demotable = (big.established.size - st.targets.establishedBig)
      .min(big.established.size - big.active.size) -
      big.closing.size - big.activating.size - warm.intersect(big.cooling).size
    val available = warm -- big.closing -- big.activating -- big.cooling
    if demotable > 0 && available.nonEmpty then
      val (picked, next) =
        Picks.warmDemotion(available, demotable, st.rng, p => st.peers(p).tepid)
      Go(
        Decision(
          withStatus(picked, Status.Closing).copy(rng = next),
          picked.map(Job.Close(_)),
          List(
            Trace.DemoteBigLedger(
              Temperature.Warm,
              picked,
              st.targets.establishedBig,
              big.established.size
            )
          )
        )
      )
    else Skip(None)

  private def readyTimes: Option[Time] =
    st.peers.collect {
      case (_, PeerRecord(Status.Warm(at), Origin.BigLedger, _, _, _, _, _, _, _)) if at > now => at
    }.minOption

  /** `ActivePeers.belowTargetBigLedgerPeers`: activate the shortfall of the active target among the
    * ready warm big ledger peers.
    */
  def activate: Guard[A] =
    val missing = st.targets.activeBig - big.active.size - big.activating.size
    val available: SortedSet[A] =
      big.ready -- big.active -- big.activating -- big.closing -- big.cooling
    if progress && missing > 0 && available.nonEmpty then
      val (picked, next) = Picks.warmPromotion(available, missing, st.rng, p => st.peers(p).tepid)
      Go(
        Decision(
          withStatus(picked, Status.Activating).copy(rng = next),
          picked.map(Job.Activate(_)),
          List(
            Trace.PromoteBigLedger(Temperature.Warm, picked, st.targets.activeBig, big.active.size)
          )
        )
      )
    else if missing > 0 then Skip(readyTimes)
    else Skip(None)

  /** `ActivePeers.aboveTargetBigLedgerPeers`: above the active target, deactivate hot big ledger
    * peers, the least useful first by the peer metrics.
    */
  def deactivate: Guard[A] =
    val excess = big.active.size - st.targets.activeBig - big.deactivating.size
    val available = big.active -- big.deactivating -- big.cooling
    if excess > 0 && available.nonEmpty then
      val (picked, next) =
        Picks.hotDemotion(available, excess, st.rng, p => hotScore(p).getOrElse(HotScore.Unknown))
      Go(
        Decision(
          withStatus(picked, Status.Deactivating).copy(rng = next),
          picked.map(Job.Deactivate(_)),
          List(
            Trace.DemoteBigLedger(
              Temperature.Hot,
              picked,
              st.targets.activeBig,
              big.active.size,
              Picks.hotDemotionScores(available, hotScore)
            )
          )
        )
      )
    else Skip(None)
