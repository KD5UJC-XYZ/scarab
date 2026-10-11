// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import com.comcast.ip4s.{Host, SocketAddress}
import lab.network.ChainSync

/** One JSON object per line on stdout, for the soak harness to consume. */
object JsonLog:
  private def line(kind: String, fields: (String, Json)*): IO[Unit] =
    IO.realTimeInstant.flatMap { now =>
      IO.println(
        Json.obj((("event" -> kind.asJson) +: ("at" -> now.toString.asJson) +: fields)*).noSpaces
      )
    }

  private def point(p: ChainSync.Point): Json = p match
    case ChainSync.Point.Origin => Json.obj("origin" -> true.asJson)
    case ChainSync.Point.Block(slot, hash) =>
      Json.obj("slot" -> slot.value.asJson, "hash" -> hash.hex.asJson)
  private def block(b: BlockRef): Json =
    Json.obj("slot" -> b.slot.asJson, "blockNo" -> b.blockNo.asJson, "hash" -> b.hash.hex.asJson)

  def started(
      peer: SocketAddress[Host],
      genesis: ShelleyGenesis,
      config: NodeConfig = NodeConfig.empty
  ): IO[Unit] =
    line(
      "started",
      "peer" -> peer.toString.asJson,
      "networkMagic" -> genesis.networkMagic.asJson,
      "epochLength" -> genesis.geometry.epochLength.asJson,
      "securityParam" -> genesis.geometry.securityParam.asJson,
      "byronEpochs" -> genesis.geometry.byron.epochs.asJson,
      "byronEpochSlots" -> genesis.geometry.byron.epochSlots.asJson,
      "hardForkEpochs" -> config.hardForkEpochs.map((e, n) => e.toString -> n).asJson
    )

  def finished[S](chain: VolatileChain[S]): IO[Unit] =
    line("finished", "tip" -> point(chain.tip), "retained" -> chain.length.asJson)

  def error(e: NodeError): IO[Unit] =
    line("error", "kind" -> e.getClass.getSimpleName.asJson, "message" -> e.message.asJson)

  val events: FollowerEvents[IO] = new FollowerEvents[IO]:
    def emit(event: FollowerEvent): IO[Unit] = event match
      case FollowerEvent.Connected(p) => line("connected", "intersection" -> point(p))
      case FollowerEvent.Extended(b, epoch, tip, era) =>
        line(
          "extended",
          "block" -> block(b),
          "epoch" -> epoch.asJson,
          "era" -> era.toString.asJson,
          "peerTip" -> point(tip.point)
        )
      case FollowerEvent.RolledBack(from, to, depth) =>
        line("rolledBack", "from" -> point(from), "to" -> point(to), "depth" -> depth.asJson)
      case FollowerEvent.EpochEntered(epoch, first) =>
        line("epoch", "epoch" -> epoch.asJson, "first" -> block(first))
      case FollowerEvent.Immutable(_) => IO.unit
      case FollowerEvent.EraEntered(from, to, first, epoch) =>
        line(
          "eraTransition",
          "from" -> from.map(_.toString).asJson,
          "to" -> to.toString.asJson,
          "epoch" -> epoch.asJson,
          "slot" -> first.slot.asJson,
          "block" -> block(first)
        )
      case FollowerEvent.ByronEpochValidated(epoch, boundary, verified, unchecked) =>
        line(
          "epochValidated",
          "era" -> "Byron".asJson,
          "epoch" -> epoch.asJson,
          "headers" -> (boundary + verified + unchecked).asJson,
          "boundary" -> boundary.asJson,
          "signatureVerified" -> verified.asJson,
          "signatureUnchecked" -> unchecked.asJson
        )
      case FollowerEvent.EpochValidated(epoch, era, threshold, overlay, unchecked) =>
        line(
          "epochValidated",
          "era" -> era.toString.asJson,
          "epoch" -> epoch.asJson,
          "headers" -> (threshold + overlay + unchecked).asJson,
          "thresholdChecked" -> threshold.asJson,
          "overlay" -> overlay.asJson,
          "leaderUnchecked" -> unchecked.asJson
        )
      case FollowerEvent.Reconnecting(reason, attempt) =>
        line("reconnecting", "reason" -> reason.message.asJson, "attempt" -> attempt.asJson)
