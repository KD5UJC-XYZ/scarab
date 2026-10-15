// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{NodeState, VolatileChain}
import cats.effect.{Async, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import fs2.io.net.Network
import io.circe.Json
import io.circe.syntax.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.codec.Rational
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
          "total" -> n.ledger.poolDistribution.activeStake.max(BigInt(1)).asJson,
          "pools" -> coinMap(n.ledger.poolDistribution.poolStake, (b: Bytes) => b.hex)
        )
      )
    )

  private def ratio(r: Rational): Json = s"${r.numerator}/${r.denominator}".asJson

  private def double(r: Rational): Json =
    (BigDecimal(r.numerator) / BigDecimal(
      r.denominator,
      java.math.MathContext.DECIMAL64
    )).toDouble.asJson

  private def assets(v: Value): Json =
    Json.fromFields(v.assets.toVector.sortBy(_._1.hex).map { case (p, names) =>
      p.hex -> Json.fromFields(names.toVector.sortBy(_._1.hex).map { case (n, q) =>
        n.hex -> q.asJson
      })
    })

  private[node] def txOut(o: TxOut): Json =
    val base = Json.obj("address" -> o.address.raw.hex.asJson, "lovelace" -> o.value.coin.asJson)
    val multi = if o.value.assets.isEmpty then Json.obj() else Json.obj("assets" -> assets(o.value))
    val datum = o.datum match
      case Datum.NoDatum   => Json.obj()
      case Datum.Hash(h)   => Json.obj("datumHash" -> h.hex.asJson)
      case Datum.Inline(d) => Json.obj("inlineDatum" -> d.hex.asJson)
    val script = o.scriptRef.fold(Json.obj())(s => Json.obj("scriptRef" -> s.hash.hex.asJson))
    base.deepMerge(multi).deepMerge(datum).deepMerge(script)

  def pool(p: PoolParams): Json = Json.obj(
    "pledge" -> p.pledge.asJson,
    "cost" -> p.cost.asJson,
    "margin" -> ratio(p.margin),
    "rewardAccount" -> p.rewardAccount.credential.hash.hex.asJson,
    "owners" -> p.owners.map(_.hex).toVector.sorted.asJson
  )

  private def updates(m: Map[Bytes, ParamUpdate]): Json =
    Json.fromFields(m.toVector.sortBy(_._1.hex).map { case (g, u) =>
      g.hex -> Json.fromFields(u.fields.toVector.sortBy(_._1).map { case (i, raw) =>
        i.toString -> raw.hex.asJson
      })
    })

  /** Protocol parameters under the names `cardano-cli query protocol-parameters` uses. */
  def params(p: ProtocolParams): Json = Json.obj(
    "txFeePerByte" -> p.minFeeA.asJson,
    "txFeeFixed" -> p.minFeeB.asJson,
    "maxBlockBodySize" -> p.maxBlockBodySize.asJson,
    "maxTxSize" -> p.maxTxSize.asJson,
    "maxBlockHeaderSize" -> p.maxBlockHeaderSize.asJson,
    "stakeAddressDeposit" -> p.keyDeposit.asJson,
    "stakePoolDeposit" -> p.poolDeposit.asJson,
    "poolRetireMaxEpoch" -> p.eMax.asJson,
    "stakePoolTargetNum" -> p.nOpt.asJson,
    "poolPledgeInfluence" -> double(p.a0),
    "monetaryExpansion" -> double(p.rho),
    "treasuryCut" -> double(p.tau),
    "decentralization" -> double(p.d),
    "extraPraosEntropy" -> p.extraEntropy.render.asJson,
    "protocolVersion" -> Json
      .obj("major" -> p.protocolMajor.asJson, "minor" -> p.protocolMinor.asJson),
    "minPoolCost" -> p.minPoolCost.asJson,
    "minUTxOValue" -> p.minUTxOValue.asJson,
    "utxoCostPerWord" -> p.coinsPerUTxOWord.asJson,
    "utxoCostPerByte" -> p.coinsPerUTxOByte.asJson,
    "executionUnitPrices" -> Json
      .obj("priceMemory" -> double(p.priceMem), "priceSteps" -> double(p.priceSteps)),
    "maxTxExecutionUnits" -> Json
      .obj("memory" -> p.maxTxExUnits.mem.asJson, "steps" -> p.maxTxExUnits.steps.asJson),
    "maxBlockExecutionUnits" -> Json
      .obj("memory" -> p.maxBlockExUnits.mem.asJson, "steps" -> p.maxBlockExUnits.steps.asJson),
    "maxValueSize" -> p.maxValueSize.asJson,
    "collateralPercentage" -> p.collateralPercentage.asJson,
    "maxCollateralInputs" -> p.maxCollateralInputs.asJson,
    "govActionDeposit" -> p.govActionDeposit.asJson,
    "govActionLifetime" -> p.govActionLifetime.asJson,
    "dRepDeposit" -> p.drepDeposit.asJson,
    "dRepActivity" -> p.drepActivity.asJson,
    "committeeMinSize" -> p.committeeMinSize.asJson,
    "committeeMaxTermLength" -> p.committeeMaxTermLength.asJson,
    "minFeeRefScriptCostPerByte" -> double(p.minFeeRefScriptCostPerByte),
    "poolVotingThresholds" -> Json.obj(
      "motionNoConfidence" -> double(p.poolVotingThresholds.motionNoConfidence),
      "committeeNormal" -> double(p.poolVotingThresholds.committeeNormal),
      "committeeNoConfidence" -> double(p.poolVotingThresholds.committeeNoConfidence),
      "hardForkInitiation" -> double(p.poolVotingThresholds.hardForkInitiation),
      "ppSecurityGroup" -> double(p.poolVotingThresholds.securityGroup)
    ),
    "dRepVotingThresholds" -> Json.obj(
      "motionNoConfidence" -> double(p.drepVotingThresholds.motionNoConfidence),
      "committeeNormal" -> double(p.drepVotingThresholds.committeeNormal),
      "committeeNoConfidence" -> double(p.drepVotingThresholds.committeeNoConfidence),
      "updateToConstitution" -> double(p.drepVotingThresholds.updateToConstitution),
      "hardForkInitiation" -> double(p.drepVotingThresholds.hardForkInitiation),
      "ppNetworkGroup" -> double(p.drepVotingThresholds.ppNetworkGroup),
      "ppEconomicGroup" -> double(p.drepVotingThresholds.ppEconomicGroup),
      "ppTechnicalGroup" -> double(p.drepVotingThresholds.ppTechnicalGroup),
      "ppGovGroup" -> double(p.drepVotingThresholds.ppGovGroup),
      "treasuryWithdrawal" -> double(p.drepVotingThresholds.treasuryWithdrawal)
    )
  )

  private def cred(c: Credential): String = c.render

  private def id(g: GovActionId): Json = s"${g.txId.hex}#${g.index}".asJson

  private def optId(g: Option[GovActionId]): Json = g.fold(Json.Null)(id)

  private def anchor(a: Anchor): Json = Json.obj("url" -> a.url.asJson, "hash" -> a.hash.hex.asJson)

  private def vote(v: Vote): Json = v.toString.asJson

  def govAction(a: GovAction): Json = a match
    case GovAction.ParameterChange(prev, u, policy) =>
      Json.obj(
        "tag" -> "ParameterChange".asJson,
        "prev" -> optId(prev),
        "keys" -> u.fields.keys.toVector.sorted.asJson,
        "policy" -> policy.map(_.hex).asJson
      )
    case GovAction.HardFork(prev, major, minor) =>
      Json.obj(
        "tag" -> "HardForkInitiation".asJson,
        "prev" -> optId(prev),
        "version" -> Vector(major, minor).asJson
      )
    case GovAction.TreasuryWithdrawals(ws, policy) =>
      Json.obj(
        "tag" -> "TreasuryWithdrawals".asJson,
        "withdrawals" -> coinMap(
          ws.groupMapReduce(w => cred(w._1.credential))(_._2)(_ + _),
          (k: String) => k
        ),
        "policy" -> policy.map(_.hex).asJson
      )
    case GovAction.NoConfidence(prev) =>
      Json.obj("tag" -> "NoConfidence".asJson, "prev" -> optId(prev))
    case GovAction.UpdateCommittee(prev, removed, added, threshold) =>
      Json.obj(
        "tag" -> "UpdateCommittee".asJson,
        "prev" -> optId(prev),
        "removed" -> removed.toVector.map(cred).sorted.asJson,
        "added" -> coinMap(added, cred),
        "threshold" -> ratio(threshold)
      )
    case GovAction.NewConstitution(prev, script, a) =>
      Json.obj(
        "tag" -> "NewConstitution".asJson,
        "prev" -> optId(prev),
        "anchor" -> anchor(a),
        "script" -> script.map(_.hex).asJson
      )
    case GovAction.Info => Json.obj("tag" -> "InfoAction".asJson)

  private def votes[K](m: Map[K, Vote], key: K => String): Json =
    Json.fromFields(m.toVector.sortBy(e => key(e._1)).map { case (k, v) => key(k) -> vote(v) })

  def proposal(g: GovActionState): Json = Json.obj(
    "id" -> id(g.id),
    "deposit" -> g.proposal.deposit.asJson,
    "returnAccount" -> cred(g.proposal.returnAccount.credential).asJson,
    "anchor" -> anchor(g.proposal.anchor),
    "action" -> govAction(g.action),
    "committeeVotes" -> votes(g.committeeVotes, cred),
    "drepVotes" -> votes(g.drepVotes, cred),
    "poolVotes" -> votes(g.poolVotes, (b: Bytes) => b.hex),
    "proposedIn" -> g.proposedIn.asJson,
    "expiresAfter" -> g.expiresAfter.asJson
  )

  private def prevIds(p: PrevIds): Json = Json.obj(
    "ParamUpdate" -> optId(p.paramUpdate),
    "HardFork" -> optId(p.hardFork),
    "Committee" -> optId(p.committee),
    "Constitution" -> optId(p.constitution)
  )

  private def committee(c: Option[Committee]): Json = c.fold(Json.Null)(c =>
    Json.obj("members" -> coinMap(c.members, cred), "threshold" -> ratio(c.threshold))
  )

  private def constitution(c: Constitution): Json =
    Json.obj("anchor" -> anchor(c.anchor), "script" -> c.script.map(_.hex).asJson)

  private def drepKey(d: DRep): String = d.render

  def ratifyState(r: RatifyState): Json = Json.obj(
    "enacted" -> r.enacted.map(g => id(g.id)).asJson,
    "expired" -> r.expired.toVector.map(g => s"${g.txId.hex}#${g.index}").sorted.asJson,
    "delayed" -> r.delayed.asJson,
    "committee" -> committee(r.enact.committee),
    "constitution" -> constitution(r.enact.constitution),
    "prevGovActionIds" -> prevIds(r.enact.prevIds),
    "withdrawals" -> coinMap(r.enact.withdrawals, cred),
    "protocolVersion" -> Vector(r.enact.params.protocolMajor, r.enact.params.protocolMinor).asJson
  )

  /** Governance state: proposals, committee, constitution and the pulser's ratification. */
  def gov(s: LedgerState): Json = Json.obj(
    "proposals" -> s.gov.proposals.actions.map(proposal).asJson,
    "committee" -> committee(s.gov.committee),
    "constitution" -> constitution(s.gov.constitution),
    "committeeState" -> Json.fromFields(s.committeeState.toVector.sortBy(e => cred(e._1)).map {
      case (c, CommitteeAuth.Hot(h)) => cred(c) -> Json.obj("hot" -> cred(h).asJson)
      case (c, CommitteeAuth.Resigned(a)) =>
        cred(c) -> Json.obj("resigned" -> true.asJson, "anchor" -> a.map(anchor).asJson)
    }),
    "prevGovActionIds" -> prevIds(s.gov.proposals.roots),
    "ratify" -> s.gov.pulsed.fold(Json.Null)(p => ratifyState(p._2)),
    "drepDistr" -> s.gov.pulsed.fold(Json.Null)(p => coinMap(p._1.drepDistr, drepKey)),
    "donation" -> s.donation.asJson
  )

  def ledger(p: ChainSync.Point, s: LedgerState): Json = Json.obj(
    "point" -> point(p),
    "epoch" -> s.epoch.asJson,
    "utxo" -> Json.fromFields(s.utxo.toVector.sortBy(_._1.render).map { case (i, o) =>
      i.render -> txOut(o)
    }),
    "params" -> params(s.params),
    "poolParams" -> Json.fromFields(s.pools.toVector.sortBy(_._1.hex).map { case (id, ps) =>
      id.hex -> pool(ps.params)
    }),
    "futurePools" -> Json.fromFields(s.futurePools.toVector.sortBy(_._1.hex).map { case (id, pp) =>
      id.hex -> pool(pp)
    }),
    "genDelegs" -> Json.fromFields(s.genDelegs.toVector.sortBy(_._1.hex).map { case (g, d) =>
      g.hex -> Json.obj("delegate" -> d.delegate.hex.asJson, "vrf" -> d.vrf.hex.asJson)
    }),
    "futureGenDelegs" -> s.futureGenDelegs
      .sortBy(f => (f.slot, f.genesisKey.hex))
      .map(f =>
        Json.obj(
          "slot" -> f.slot.asJson,
          "genesisKey" -> f.genesisKey.hex.asJson,
          "delegate" -> f.pair.delegate.hex.asJson,
          "vrf" -> f.pair.vrf.hex.asJson
        )
      )
      .asJson,
    "ppup" -> Json.obj("current" -> updates(s.ppup.current), "future" -> updates(s.ppup.future)),
    "mir" -> Json.obj(
      "reserves" -> coinMap(s.mir.reserves, (c: Credential) => c.render),
      "treasury" -> coinMap(s.mir.treasury, (c: Credential) => c.render),
      "deltaReserves" -> s.mir.deltaReserves.asJson,
      "deltaTreasury" -> s.mir.deltaTreasury.asJson
    ),
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
      c.render -> Json.obj(
        "deposit" -> d.deposit.asJson,
        "expiry" -> d.expiry.asJson,
        "anchor" -> d.anchor.map(anchor).asJson,
        "delegators" -> d.delegs.toVector.map(_.render).sorted.asJson
      )
    }),
    "gov" -> gov(s),
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
      port: Int,
      utxoStore: Option[xyz.kd5ujc.scarab.storage.UtxoStore[F]] = None,
      /** The whole-run scheduling measurements, served at `/scheduling`. */
      scheduling: Option[F[Json]] = None,
      /** The connection manager's table and the inbound sessions, served at `/connections`. */
      connections: Option[F[Json]] = None,
      /** The forger's counters per epoch, served at `/forging` (only on a node that forges). */
      forging: Option[F[Json]] = None
  ): Resource[F, Server] =
    val dsl = Http4sDsl[F]
    import dsl.*
    // With the UTxO set in a backing store the state holds only its recent changes: the whole set
    // is put back together for the projection.
    def complete(s: NodeState): F[NodeState] = utxoStore.fold(Async[F].pure(s)) { store =>
      xyz.kd5ujc.scarab.storage.UtxoStore.materialize(store, s.ledger.utxo).map { all =>
        s.copy(ledger = s.ledger.copy(utxo = xyz.kd5ujc.scarab.ledger.Utxo.fromMap(all)))
      }
    }
    object SlotParam extends QueryParamDecoderMatcher[Long]("slot")
    object HashParam extends QueryParamDecoderMatcher[String]("hash")
    val routes = HttpRoutes.of[F] {
      case GET -> Root / "tip" =>
        view.tip.flatMap { case (p, s) =>
          Ok(Json.obj("point" -> Projection.point(p), "epoch" -> s.ledger.epoch.asJson))
        }
      case GET -> Root / "connections" =>
        connections.fold(Async[F].pure(Response[F](Status.NotFound)))(_.flatMap(j => Ok(j)))
      case GET -> Root / "forging" =>
        forging.fold(Async[F].pure(Response[F](Status.NotFound)))(_.flatMap(j => Ok(j)))
      case GET -> Root / "scheduling" =>
        scheduling.fold(Async[F].pure(Response[F](Status.NotFound)))(_.flatMap(j => Ok(j)))
      case GET -> Root / "state" :? SlotParam(slot) +& HashParam(hash) =>
        Bytes
          .fromHex(hash)
          .toOption
          .flatMap(h => ChainSync.UInt64.from(slot).toOption.map(ChainSync.Point.Block(_, h))) match
          case None => BadRequest("slot and 32-byte hex hash required")
          case Some(p) =>
            view.at(p).flatMap {
              case Some(s) => complete(s).flatMap(c => Ok(Projection.node(p, c)))
              case None    => Async[F].pure(Response[F](Status.NotFound))
            }
      case GET -> Root / "state" =>
        view.tip.flatMap { case (p, s) => complete(s).flatMap(c => Ok(Projection.node(p, c))) }
    }
    EmberServerBuilder
      .default[F]
      .withHost(Host.fromString("127.0.0.1").get)
      .withPort(Port.fromInt(port).get)
      .withHttpApp(routes.orNotFound)
      .build
