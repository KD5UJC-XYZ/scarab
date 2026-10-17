// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import com.comcast.ip4s.{Host, SocketAddress}
import io.circe.Json
import io.circe.syntax.*
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.network.selection.*

/** The lines of the peer governor (stage G1.1): what it decided and what became of it, in the words
  * of the reference's `TracePeerSelection` (`Governor/Types.hs` 1646-1840), the counters it
  * publishes, the outbound connections state, the Genesis State Machine and the fetch mode.
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
    case Trace.Demote(from, ps, local, target, current) =>
      EventKind.PeerSelectionDemote.line(
        "action" -> "decided".asJson,
        "from" -> temperature(from),
        "peers" -> peers(ps),
        "local" -> local.asJson,
        "target" -> target.asJson,
        "current" -> current.asJson
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
    case Trace.DemoteAsynchronous(peer, to, f, reason) =>
      EventKind.PeerSelectionDemote
        .line(
          "action" -> "asynchronous".asJson,
          "to" -> temperature(to),
          "peer" -> peer.toString.asJson,
          "failure" -> f.map(failure).asJson,
          "reason" -> reason.asJson
        )
        .at(if f.isDefined then Severity.Warning else Severity.Info)
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
