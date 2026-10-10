// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.{Async, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import fs2.io.net.Network
import io.circe.Json
import io.circe.syntax.*
import lab.cbor.Bytes
import lab.network.ChainSync
import lab.node.ledger.*
import org.http4s.{HttpRoutes, Response, Status}
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server

/** Read access to the states the volatile chain retains. */
trait ChainView[F[_], S]:
  def tip: F[(ChainSync.Point, S)]
  def at(point: ChainSync.Point): F[Option[S]]

object ChainView:
  def fromRef[F[_]: cats.Functor, S](ref: Ref[F, VolatileChain[S]]): ChainView[F, S] =
    new ChainView[F, S]:
      def tip = ref.get.map(c => c.tip -> c.state)
      def at(point: ChainSync.Point) = ref.get.map { c =>
        if point == c.anchor.point then Some(c.anchor.state)
        else c.blocks.collectFirst { case (b, s) if b.point == point => s }
      }

/** Ledger state rendered with the field names `cardano-cli query ledger-state` uses. */
object Projection:
  private def coinMap[K](m: Iterable[(K, BigInt)], key: K => String): Json =
    Json.fromFields(m.toVector.sortBy(e => key(e._1)).map { case (k, v) => key(k) -> v.asJson })

  def point(p: ChainSync.Point): Json = p match
    case ChainSync.Point.Origin => Json.obj("origin" -> true.asJson)
    case ChainSync.Point.Block(slot, hash) =>
      Json.obj("slot" -> slot.value.asJson, "hash" -> hash.hex.asJson)

  def snapshot(s: Snapshot): Json = Json.obj(
    "stake" -> coinMap(s.stake, _.render),
    "delegations" -> Json.fromFields(s.delegations.toVector.sortBy(_._1.render).map { case (c, p) =>
      c.render -> p.hex.asJson
    }),
    "pools" -> s.pools.keys.map(_.hex).toVector.sorted.asJson
  )

  def node(p: ChainSync.Point, n: NodeState): Json =
    ledger(p, n.ledger).deepMerge(
      Json.obj(
        "protocolState" -> Json.obj(
          "epochNonce" -> n.praos.epochNonce.render.asJson,
          "evolvingNonce" -> n.praos.evolving.render.asJson,
          "candidateNonce" -> n.praos.candidate.render.asJson,
          "labNonce" -> n.praos.lab.render.asJson,
          "lastEpochBlockNonce" -> n.praos.lastEpochBlock.render.asJson,
          "oCertCounters" -> coinMap(n.praos.counters, (b: Bytes) => b.hex)
        ),
        "stakeDistrib" -> Json.obj(
          "total" -> n.ledger.poolDistribution.activeStake.asJson,
          "pools" -> coinMap(n.ledger.poolDistribution.poolStake, (b: Bytes) => b.hex)
        )
      )
    )

  def ledger(p: ChainSync.Point, s: LedgerState): Json = Json.obj(
    "point" -> point(p),
    "epoch" -> s.epoch.asJson,
    "utxo" -> Json.fromFields(s.utxo.toVector.sortBy(_._1.render).map { case (i, o) =>
      i.render -> Json.obj("address" -> o.address.raw.hex.asJson, "lovelace" -> o.value.coin.asJson)
    }),
    "fees" -> s.fees.asJson,
    "deposited" -> s.deposited.asJson,
    "treasury" -> s.treasury.asJson,
    "reserves" -> s.reserves.asJson,
    "accounts" -> Json.fromFields(s.accounts.toVector.sortBy(_._1.render).map { case (c, a) =>
      c.render -> Json.obj(
        "balance" -> a.balance.asJson,
        "deposit" -> a.deposit.asJson,
        "spool" -> a.pool.map(_.hex).asJson,
        "drep" -> a.drep.map(_.render).asJson
      )
    }),
    "pools" -> Json.fromFields(s.pools.toVector.sortBy(_._1.hex).map { case (id, ps) =>
      id.hex -> Json.obj("deposit" -> ps.deposit.asJson, "vrf" -> ps.params.vrfKeyHash.hex.asJson)
    }),
    "retiring" -> coinMap(s.retiring, (b: Bytes) => b.hex),
    "dreps" -> Json.fromFields(s.dreps.toVector.sortBy(_._1.render).map { case (c, d) =>
      c.render -> Json.obj("deposit" -> d.deposit.asJson, "expiry" -> d.expiry.asJson)
    }),
    "numDormantEpochs" -> s.dormantEpochs.asJson,
    "blocksCurrent" -> coinMap(s.blocksCurrent, (b: Bytes) => b.hex),
    "blocksBefore" -> coinMap(s.blocksPrevious, (b: Bytes) => b.hex),
    "snapshots" -> Json.obj(
      "mark" -> snapshot(s.snapshots.mark),
      "set" -> snapshot(s.snapshots.set),
      "go" -> snapshot(s.snapshots.go),
      "fee" -> s.snapshots.fee.asJson
    ),
    "rewardUpdate" -> s.rewardUpdate.fold(Json.Null)(ru =>
      Json.obj(
        "deltaT" -> ru.deltaTreasury.asJson,
        "deltaR" -> ru.deltaReserves.asJson,
        "deltaF" -> ru.deltaFees.asJson,
        "rs" -> coinMap(ru.rewards, _.render)
      )
    )
  )

/** Loopback HTTP query service: `GET /tip` and `GET /state?slot=S&hash=H`. */
object QueryServer:
  def resource[F[_]: Async: Network](
      view: ChainView[F, NodeState],
      port: Int
  ): Resource[F, Server] =
    val dsl = Http4sDsl[F]
    import dsl.*
    object SlotParam extends QueryParamDecoderMatcher[Long]("slot")
    object HashParam extends QueryParamDecoderMatcher[String]("hash")
    val routes = HttpRoutes.of[F] {
      case GET -> Root / "tip" =>
        view.tip.flatMap { case (p, s) =>
          Ok(Json.obj("point" -> Projection.point(p), "epoch" -> s.ledger.epoch.asJson))
        }
      case GET -> Root / "state" :? SlotParam(slot) +& HashParam(hash) =>
        Bytes
          .fromHex(hash)
          .toOption
          .flatMap(h => ChainSync.UInt64.from(slot).toOption.map(ChainSync.Point.Block(_, h))) match
          case None => BadRequest("slot and 32-byte hex hash required")
          case Some(p) =>
            view.at(p).flatMap {
              case Some(s) => Ok(Projection.node(p, s))
              case None    => Async[F].pure(Response[F](Status.NotFound))
            }
      case GET -> Root / "state" =>
        view.tip.flatMap { case (p, s) => Ok(Projection.node(p, s)) }
    }
    EmberServerBuilder
      .default[F]
      .withHost(Host.fromString("127.0.0.1").get)
      .withPort(Port.fromInt(port).get)
      .withHttpApp(routes.orNotFound)
      .build
