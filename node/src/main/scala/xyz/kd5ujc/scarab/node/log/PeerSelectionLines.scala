// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import com.comcast.ip4s.{Host, SocketAddress}
import io.circe.Json
import io.circe.syntax.*
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.node.LedgerPeersTrace

/** The lines of the peer governor: what it decided and what became of it, in the words of the
  * reference's `TracePeerSelection` (`Governor/Types.hs` 1646-1840), the counters it publishes, the
  * outbound connections state, the Genesis State Machine and the fetch mode.
  */
private[log] object PeerSelectionLines:
  type Addr = SocketAddress[Host]

  private def peers(ps: Iterable[Addr]): Json = ps.toList.map(_.toString).sorted.asJson
  private def seconds(d: FiniteDuration): Json = (d.toMillis / 1000.0).asJson
  private def failure(f: FailureClass): Json = f.toString.asJson
  private def temperature(t: Temperature): Json = t.toString.toLowerCase.asJson

  def trace(t: Trace[Addr]): Line = t match
    case Trace.TargetsChanged(x) =>
      EventKind.PeerSelectionTargets.line(
        "root" -> x.root.asJson,
        "known" -> x.known.asJson,
        "established" -> x.established.asJson,
        "active" -> x.active.asJson,
        "knownBigLedger" -> x.knownBig.asJson,
        "establishedBigLedger" -> x.establishedBig.asJson,
        "activeBigLedger" -> x.activeBig.asJson
      )
    case Trace.LocalRootsChanged(before, after) =>
      EventKind.PeerSelectionRoots.line(
        "action" -> "localRootsChanged".asJson,
        "before" -> before.asJson,
        "after" -> after.asJson
      )
    case Trace.RootsRequest(target, current) =>
      EventKind.PeerSelectionRoots.line(
        "action" -> "request".asJson,
        "target" -> target.asJson,
        "current" -> current.asJson
      )
    case Trace.RootsResults(found, backoffs, retryIn) =>
      EventKind.PeerSelectionRoots.line(
        "action" -> "results".asJson,
        "found" -> peers(found),
        "backoffs" -> backoffs.asJson,
        "retryInSeconds" -> seconds(retryIn)
      )
    case Trace.RootsFailure(backoffs, retryIn) =>
      EventKind.PeerSelectionRoots
        .line(
          "action" -> "failure".asJson,
          "backoffs" -> backoffs.asJson,
          "retryInSeconds" -> seconds(retryIn)
        )
        .at(Severity.Warning)
    case Trace.Promote(from, ps, local, target, current) =>
      EventKind.PeerSelectionPromote.line(
        "action" -> "decided".asJson,
        "from" -> temperature(from),
        "peers" -> peers(ps),
        "local" -> local.asJson,
        "target" -> target.asJson,
        "current" -> current.asJson
      )
    case Trace.PromoteDone(to, peer, target, current) =>
      EventKind.PeerSelectionPromote.line(
        "action" -> "done".asJson,
        "to" -> temperature(to),
        "peer" -> peer.toString.asJson,
        "target" -> target.asJson,
        "current" -> current.asJson
      )
    case Trace.PromoteFailed(from, peer, f, retryIn, forgotten) =>
      EventKind.PeerSelectionPromote
        .line(
          "action" -> "failed".asJson,
          "from" -> temperature(from),
          "peer" -> peer.toString.asJson,
          "failure" -> failure(f),
          "retryInSeconds" -> retryIn.map(seconds).asJson,
          "forgotten" -> forgotten.asJson
        )
        .at(Severity.Warning)
    case Trace.PromoteAborted(from, peer) =>
      EventKind.PeerSelectionPromote.line(
        "action" -> "aborted".asJson,
        "from" -> temperature(from),
        "peer" -> peer.toString.asJson
      )
    case Trace.Demote(from, ps, local, target, current, scores) =>
      EventKind.PeerSelectionDemote.line(
        "action" -> "decided".asJson,
        "from" -> temperature(from),
        "peers" -> peers(ps),
        "local" -> local.asJson,
        "target" -> target.asJson,
        "current" -> current.asJson,
        "scores" -> Json.obj(
          scores.toList.sortBy(_._1.toString).map((peer, score) => peer.toString -> score.asJson)*
        )
      )
    case Trace.DemoteDone(from, peer, current) =>
      EventKind.PeerSelectionDemote.line(
        "action" -> "done".asJson,
        "from" -> temperature(from),
        "peer" -> peer.toString.asJson,
        "current" -> current.asJson
      )
    case Trace.DemoteFailed(from, peer, timedOut) =>
      EventKind.PeerSelectionDemote
        .line(
          "action" -> "failed".asJson,
          "from" -> temperature(from),
          "peer" -> peer.toString.asJson,
          "timedOut" -> timedOut.asJson
        )
        .at(Severity.Warning)
    case Trace.DemoteAsynchronous(peer, to, f, reason, returned, delay) =>
      EventKind.PeerSelectionDemote
        .line(
          "action" -> "asynchronous".asJson,
          "to" -> temperature(to),
          "peer" -> peer.toString.asJson,
          "failure" -> f.map(failure).asJson,
          "reason" -> reason.orElse(returned.map(_.message)).asJson,
          "repromoteDelaySeconds" -> delay.map(seconds).asJson
        )
        .at(if f.isDefined then Severity.Warning else Severity.Info)
    case Trace.PeerActionFailed(peer, operation, error) =>
      (if operation == xyz.kd5ujc.scarab.network.PeerOperation.Activate then
         EventKind.PeerSelectionPromote
       else EventKind.PeerSelectionDemote)
        .line(
          "action" -> "failed".asJson,
          "peer" -> peer.toString.asJson,
          "operation" -> operation.toString.asJson,
          "reason" -> error.message.asJson
        )
        .at(Severity.Warning)
    case Trace.CachedBlockRejected(peer, cause, generation, live) =>
      EventKind.PeerSelectionSource.line(
        "action" -> "cachedBlockRejected".asJson,
        "peer" -> peer.toString.asJson,
        "reason" -> cause.message.asJson,
        "sourceGeneration" -> generation.asJson,
        "sourceLive" -> live.asJson
      )
    case Trace.ForgetCold(target, known, ps) =>
      EventKind.PeerSelectionForget.line(
        "action" -> "cold".asJson,
        "target" -> target.asJson,
        "known" -> known.asJson,
        "peers" -> peers(ps)
      )
    case Trace.Forgotten(ps) =>
      EventKind.PeerSelectionForget.line("action" -> "forgotten".asJson, "peers" -> peers(ps))
    case Trace.JudgementChanged(j) =>
      EventKind.PeerSelectionSensitive.line(
        "action" -> "judgement".asJson,
        "judgement" -> j.toString.asJson
      )
    case Trace.OnlyBootstrapPeers() =>
      EventKind.PeerSelectionSensitive.line("action" -> "clean".asJson)
    // the big ledger peers: the same events with `bigLedger` true
    case Trace.BigLedgerRequest(target, current) =>
      EventKind.PeerSelectionRoots.line(
        "action" -> "request".asJson,
        "bigLedger" -> true.asJson,
        "target" -> target.asJson,
        "current" -> current.asJson
      )
    case Trace.BigLedgerResults(found, backoffs, retryIn) =>
      EventKind.PeerSelectionRoots.line(
        "action" -> "results".asJson,
        "bigLedger" -> true.asJson,
        "found" -> peers(found),
        "backoffs" -> backoffs.asJson,
        "retryInSeconds" -> seconds(retryIn)
      )
    case Trace.BigLedgerFailure(backoffs, retryIn) =>
      EventKind.PeerSelectionRoots
        .line(
          "action" -> "failure".asJson,
          "bigLedger" -> true.asJson,
          "backoffs" -> backoffs.asJson,
          "retryInSeconds" -> seconds(retryIn)
        )
        .at(Severity.Warning)
    case Trace.ForgetBigLedger(target, known, ps) =>
      EventKind.PeerSelectionForget.line(
        "action" -> "cold".asJson,
        "bigLedger" -> true.asJson,
        "target" -> target.asJson,
        "known" -> known.asJson,
        "peers" -> peers(ps)
      )
    case Trace.PromoteBigLedger(from, ps, target, current) =>
      EventKind.PeerSelectionPromote.line(
        "action" -> "decided".asJson,
        "bigLedger" -> true.asJson,
        "from" -> temperature(from),
        "peers" -> peers(ps),
        "target" -> target.asJson,
        "current" -> current.asJson
      )
    case Trace.DemoteBigLedger(from, ps, target, current, scores) =>
      EventKind.PeerSelectionDemote.line(
        "action" -> "decided".asJson,
        "bigLedger" -> true.asJson,
        "from" -> temperature(from),
        "peers" -> peers(ps),
        "target" -> target.asJson,
        "current" -> current.asJson,
        "scores" -> Json.obj(
          scores.toList.sortBy(_._1.toString).map((peer, score) => peer.toString -> score.asJson)*
        )
      )

    case Trace.PickInbound(ps) =>
      EventKind.PeerSelectionRoots.line("action" -> "pickInbound".asJson, "peers" -> peers(ps))
    case Trace.ShareRequests(amount, ps) =>
      EventKind.PeerSelectionRoots.line(
        "action" -> "peerShareRequests".asJson,
        "amount" -> amount.asJson,
        "peers" -> peers(ps)
      )
    case Trace.ShareResults(results, added) =>
      EventKind.PeerSelectionRoots.line(
        "action" -> "peerShareResults".asJson,
        "added" -> peers(added),
        "results" -> results
          .map((peer, result) =>
            Json.obj("peer" -> peer.toString.asJson, "result" -> result.toString.asJson)
          )
          .asJson
      )

  def churn(t: Churn.Trace): Line = t match
    case Churn.Trace.Mode(mode) =>
      EventKind.PeerSelectionChurn.line("action" -> "round".asJson, "mode" -> mode.toString.asJson)
    case Churn.Trace.Action(step, took, change) =>
      EventKind.PeerSelectionChurn.line(
        "action" -> "step".asJson,
        "step" -> step.toString.asJson,
        "number" -> step.number.asJson,
        "tookSeconds" -> seconds(took),
        "change" -> change.asJson
      )
    case Churn.Trace.Timeout(step, took, change) =>
      EventKind.PeerSelectionChurn.line(
        "action" -> "timeout".asJson,
        "step" -> step.toString.asJson,
        "number" -> step.number.asJson,
        "tookSeconds" -> seconds(took),
        "change" -> change.asJson
      )
    case Churn.Trace.Wait(delay) =>
      EventKind.PeerSelectionChurn.line("action" -> "wait".asJson, "delaySeconds" -> seconds(delay))

  def counters(c: GovernorState.Counters): Line =
    EventKind.PeerSelectionCounters.line(
      "root" -> c.root.asJson,
      "cold" -> c.cold.asJson,
      "warm" -> c.warm.asJson,
      "hot" -> c.hot.asJson,
      "known" -> c.known.asJson,
      "established" -> c.established.asJson,
      "active" -> c.active.asJson,
      "availableToConnect" -> c.availableToConnect.asJson,
      "coldPromotions" -> c.coldPromotions.asJson,
      "warmPromotions" -> c.warmPromotions.asJson,
      "warmDemotions" -> c.warmDemotions.asJson,
      "activeDemotions" -> c.activeDemotions.asJson,
      "localRoots" -> Json.obj(
        "known" -> c.knownLocalRoots.asJson,
        "established" -> c.establishedLocalRoots.asJson,
        "active" -> c.activeLocalRoots.asJson
      ),
      "bootstrap" -> Json.obj(
        "known" -> c.knownBootstrap.asJson,
        "established" -> c.establishedBootstrap.asJson,
        "active" -> c.activeBootstrap.asJson
      ),
      "bigLedger" -> Json.obj(
        "known" -> c.knownBigLedger.asJson,
        "established" -> c.establishedBigLedger.asJson,
        "active" -> c.activeBigLedger.asJson
      ),
      "origins" -> Json.fromFields(
        c.byOrigin.toList.sortBy(_._1.ordinal).map((o, n) => o.toString -> n.asJson)
      )
    )

  def outbound(state: OutboundConnectionsState): Line =
    EventKind.OutboundConnections.line(
      "state" -> state.toString.asJson,
      "trusted" -> (state == OutboundConnectionsState.TrustedStateWithExternalPeers).asJson
    )

  def gsm(phase: Gsm.Phase, event: Gsm.Event): Line =
    val (what, fields) = event match
      case Gsm.Event.EnterCaughtUp(n) => "enterCaughtUp" -> List("peers" -> n.asJson)
      case Gsm.Event.LeaveCaughtUp(Gsm.DurationFromNow.Already) =>
        "leaveCaughtUp" -> List("tipTooOld" -> "already".asJson)
      case Gsm.Event.LeaveCaughtUp(Gsm.DurationFromNow.After(d)) =>
        "leaveCaughtUp" -> List("tipTooOld" -> s"in ${d.toSeconds}s".asJson)
      case Gsm.Event.PreSyncingToSyncing => "preSyncingToSyncing" -> Nil
      case Gsm.Event.SyncingToPreSyncing => "syncingToPreSyncing" -> Nil
    EventKind.GsmTransition.line(
      (("transition" -> what.asJson) :: ("state" -> phase.toString.asJson) :: fields)*
    )

  def fetchMode(mode: FetchMode): Line =
    EventKind.FetchModeChanged.line("mode" -> mode.toString.asJson)

  /** The ledger-peers worker: the snapshot it was given, its rebuilt stake maps, the pools it left
    * out and the ledger state it could not read.
    */
  def ledger(t: LedgerPeersTrace): Line = t match
    case LedgerPeersTrace.SnapshotLoaded(slot, pools) =>
      EventKind.LedgerPeers.line(
        "action" -> "snapshotLoaded".asJson,
        "slot" -> slotOrOrigin(slot),
        "pools" -> pools.asJson
      )
    case LedgerPeersTrace.SnapshotIgnored(error) =>
      EventKind.LedgerPeers
        .line("action" -> "snapshotIgnored".asJson, "reason" -> error.toString.asJson)
        .at(Severity.Warning)
    case LedgerPeersTrace.SnapshotVerified(matches) =>
      EventKind.LedgerPeers.line(
        "action" -> "snapshotVerified".asJson,
        "matchesImmutablePoint" -> matches.asJson
      )
    case LedgerPeersTrace.Refreshed(source, ordinary, big, slot) =>
      EventKind.LedgerPeers.line(
        "action" -> "refreshed".asJson,
        "source" -> source.toString.asJson,
        "pools" -> ordinary.asJson,
        "bigLedgerPools" -> big.asJson,
        "ledgerSlot" -> slotOrOrigin(slot)
      )
    case LedgerPeersTrace.PoolSkipped(pool, error) =>
      EventKind.LedgerPeers
        .line(
          "action" -> "poolSkipped".asJson,
          "pool" -> pool.hex.asJson,
          "reason" -> error.toString.asJson
        )
        .at(Severity.Warning)
    case LedgerPeersTrace.Unreadable(error) =>
      EventKind.LedgerPeers
        .line("action" -> "unreadable".asJson, "reason" -> error.toString.asJson)
        .at(Severity.Warning)

  private def slotOrOrigin(slot: WithOrigin[BigInt]): Json = slot match
    case WithOrigin.Origin => "origin".asJson
    case WithOrigin.At(s)  => s.asJson
