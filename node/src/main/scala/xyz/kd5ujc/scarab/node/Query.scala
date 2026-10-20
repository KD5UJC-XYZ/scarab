// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.VolatileChain
import cats.effect.{Async, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port, SocketAddress}
import fs2.io.net.Network
import io.circe.Json
import io.circe.syntax.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.KeyHash.bytes
import xyz.kd5ujc.scarab.ledger.state.Word64.toBigInt
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state as mirror
import xyz.kd5ujc.scarab.ledger.state.{EraTag, KeyRole, ShelleyBasedEra}
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.node.read.{ChainRead, EraRead, EraViews, InEra, Read}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.codec.Rational
import org.http4s.{HttpRoutes, Query, Response, Status}
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.node.metrics.Exposition

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
  private[node] def coinMap[K](m: Iterable[(K, BigInt)], key: K => String): Json =
    Json.fromFields(m.toVector.sortBy(e => key(e._1)).map { case (k, v) => key(k) -> v.asJson })

  def point(p: ChainSync.Point): Json = p match
    case ChainSync.Point.Origin => Json.obj("origin" -> true.asJson)
    case ChainSync.Point.Block(slot, hash) =>
      Json.obj("slot" -> slot.value.asJson, "hash" -> hash.hex.asJson)

  private def blockOf(p: ChainSync.Point): Option[BlockRef] = p match
    case ChainSync.Point.Origin            => None
    case ChainSync.Point.Block(slot, hash) => Some(BlockRef(slot.value, hash, BigInt(0)))

  /** The projection of the chain state `n` at `p`, the UTxO set being `utxo` (the entries, which
    * the state itself holds only as a diff over the backing store). Written over the read paths
    * ([[ChainRead]], [[EraRead]]): the state is read in the vocabulary of the mirror. A chain still
    * in Byron has no ledger state to project: the consensus part only.
    */
  def node[S](p: ChainSync.Point, n: S, utxo: Utxo)(using read: ChainRead[S]): Read[Json] =
    val praos = n.protocol.view
    val consensus = Vector(
      "protocolState" -> Json.obj(
        "epochNonce" -> praos.epochNonce.render.asJson,
        "evolvingNonce" -> praos.evolvingNonce.render.asJson,
        "candidateNonce" -> praos.candidateNonce.render.asJson,
        "labNonce" -> praos.labNonce.render.asJson,
        "lastEpochBlockNonce" -> praos.lastEpochBlockNonce.render.asJson,
        "oCertCounters" -> coinMap(
          praos.ocertCounters.iterator.map((k, c) => k.bytes -> c.toBigInt).toMap,
          (b: Bytes) => b.hex
        )
      )
    )
    n.ledger(blockOf(p)).flatMap {
      case None => Right(Json.obj("point" -> point(p)).deepMerge(Json.obj(consensus*)))
      case Some(InEra.Of(era, l)) =>
        ledger(p, l, utxo)(using era).map { ledgerJson =>
          val leaders = l.leaderPools(nextEpoch = false)
          ledgerJson.deepMerge(
            Json.obj(
              consensus :+ (
                "stakeDistrib" -> Json.obj(
                  "total" -> leaders.total.max(BigInt(1)).asJson,
                  "pools" -> coinMap(leaders.stake, (b: Bytes) => b.hex)
                )
              )*
            )
          )
        }
    }

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

  private def credName[R <: KeyRole](c: mirror.Credential[R]): String =
    EraRead.credential(c).render

  private def keyHex[R <: KeyRole](k: mirror.KeyHash[R]): String = k.bytes.hex

  private def poolState(ps: mirror.StakePoolState): Json = Json.obj(
    "pledge" -> ps.pledge.value.asJson,
    "cost" -> ps.cost.value.asJson,
    "margin" -> ratio(ps.margin),
    "rewardAccount" -> EraRead.credential(ps.accountId).hash.hex.asJson,
    "owners" -> ps.owners.toVector.map(keyHex).sorted.asJson
  )

  /** Governance state: proposals, committee, constitution and the pulser's ratification. The state
    * of an era before Conway has none: the empty one.
    */
  private def govJson[E <: EraTag](
      gov: mirror.GovState[E],
      voting: Option[mirror.VState],
      pulsed: Option[mirror.DRComplete],
      donation: mirror.Coin
  )(using ShelleyBasedEra[E]): Json =
    val conway = EraViews.conwayGov(gov)
    val proposals = conway.fold(flat.Proposals.empty)(_.proposals)
    Json.obj(
      "proposals" -> proposals.actions.map(proposal).asJson,
      "committee" -> committee(conway.flatMap(_.committee)),
      "constitution" -> constitution(
        conway.fold(flat.ConwayGenesis.noConstitution)(_.constitution)
      ),
      "committeeState" -> Json.fromFields(
        voting.toVector.flatMap(_.committeeState.creds.toVector).sortBy(e => credName(e._1)).map {
          case (c, mirror.CommitteeAuthorization.CommitteeHotCredential(h)) =>
            credName(c) -> Json.obj("hot" -> credName(h).asJson)
          case (c, mirror.CommitteeAuthorization.CommitteeMemberResigned(a)) =>
            credName(c) -> Json.obj("resigned" -> true.asJson, "anchor" -> a.map(anchor).asJson)
        }
      ),
      "prevGovActionIds" -> prevIds(proposals.roots),
      "ratify" -> pulsed.fold(Json.Null)(p => ratifyState(p.ratify)),
      "drepDistr" -> pulsed.fold(Json.Null)(p => coinMap(p.snapshot.drepDistr, drepKey)),
      "donation" -> donation.value.asJson
    )

  private def snapshotJson(s: mirror.SnapShot): Json =
    val entries = s.activeStake.entries.toVector
    Json.obj(
      "stake" -> coinMap(entries.map((c, w) => c -> w.stake.value.toBigInt), credName),
      "delegations" -> Json.fromFields(
        entries
          .sortBy(e => credName(e._1))
          .map((c, w) => credName(c) -> w.delegation.bytes.hex.asJson)
      ),
      "pools" -> s.pools.keys.map(keyHex).toVector.sorted.asJson
    )

  /** The projection of the ledger state of one era. */
  private def ledger[E <: EraTag](p: ChainSync.Point, l: EraRead[E], utxo: Utxo)(using
      era: ShelleyBasedEra[E]
  ): Read[Json] =
    for
      sc <- l.scalars
      cert <- l.cert
      gov <- l.gov
      snaps <- l.snapshots
      pulsed <- l.pulsed
      due <- l.rewardsDue
      proposed <- l.ppup
    yield
      val pools = EraViews.pState(cert)
      val voting = EraViews.vState(cert)
      val iRewards = EraViews.iRewards(cert)
      Json.obj(
        "point" -> point(p),
        "epoch" -> l.epoch.toBigInt.asJson,
        "utxo" -> Json.fromFields(utxo.toVector.sortBy(_._1.render).map { case (i, o) =>
          i.render -> txOut(o)
        }),
        "params" -> params(l.params),
        "poolParams" -> Json.fromFields(pools.stakePools.toVector.sortBy(_._1.bytes.hex).map {
          case (id, ps) => id.bytes.hex -> poolState(ps)
        }),
        "futurePools" -> Json.fromFields(pools.future.toVector.sortBy(_._1.bytes.hex).map {
          case (id, pp) => id.bytes.hex -> pool(pp)
        }),
        "genDelegs" -> Json.fromFields(
          EraViews.genDelegs(cert).toVector.sortBy(_._1.bytes.hex).map { case (g, d) =>
            g.bytes.hex -> Json.obj("delegate" -> d.delegate.hex.asJson, "vrf" -> d.vrf.hex.asJson)
          }
        ),
        "futureGenDelegs" -> EraViews
          .futureGenDelegs(cert)
          .toVector
          .sortBy((f, _) => (f.slot.toBigInt, f.genKeyHash.bytes.hex))
          .map((f, pair) =>
            Json.obj(
              "slot" -> f.slot.toBigInt.asJson,
              "genesisKey" -> f.genKeyHash.bytes.hex.asJson,
              "delegate" -> pair.delegate.hex.asJson,
              "vrf" -> pair.vrf.hex.asJson
            )
          )
          .asJson,
        "ppup" -> Json.obj("current" -> updates(proposed._1), "future" -> updates(proposed._2)),
        "mir" -> Json.obj(
          "reserves" -> coinMap(
            iRewards.reserves.toVector.map((c, v) => c -> v.value),
            credName
          ),
          "treasury" -> coinMap(
            iRewards.treasury.toVector.map((c, v) => c -> v.value),
            credName
          ),
          "deltaReserves" -> iRewards.deltaReserves.value.asJson,
          "deltaTreasury" -> iRewards.deltaTreasury.value.asJson
        ),
        "fees" -> sc.fees.value.asJson,
        "deposited" -> sc.deposited.value.asJson,
        "treasury" -> sc.chainAccount.treasury.value.asJson,
        "reserves" -> sc.chainAccount.reserves.value.asJson,
        "accounts" -> Json.fromFields(
          EraViews.accounts(cert).toVector.sortBy(e => credName(e._1)).map { case (c, a) =>
            credName(c) -> Json.obj(
              "balance" -> a.balance.toBigInt.asJson,
              "deposit" -> a.deposit.toBigInt.asJson,
              "spool" -> a.pool.map(keyHex).asJson,
              "drep" -> a.drep.map(d => EraViews.drep(d).render).asJson
            )
          }
        ),
        "pools" -> Json.fromFields(pools.stakePools.toVector.sortBy(_._1.bytes.hex).map {
          case (id, ps) =>
            id.bytes.hex -> Json.obj(
              "deposit" -> ps.deposit.toBigInt.asJson,
              "vrf" -> ps.vrf.bytes.hex.asJson
            )
        }),
        "retiring" -> coinMap(
          pools.retiring.toVector.map((id, e) => id.bytes -> e.toBigInt),
          (b: Bytes) => b.hex
        ),
        "dreps" -> Json.fromFields(
          voting.toVector.flatMap(_.dreps.toVector).sortBy(e => credName(e._1)).map { case (c, d) =>
            credName(c) -> Json.obj(
              "deposit" -> d.deposit.toBigInt.asJson,
              "expiry" -> d.expiry.toBigInt.asJson,
              "anchor" -> d.anchor.map(anchor).asJson,
              "delegators" -> d.delegators.toVector.map(credName).sorted.asJson
            )
          }
        ),
        "gov" -> govJson(gov, voting, pulsed, sc.donation),
        "numDormantEpochs" -> voting.fold(BigInt(0))(_.numDormantEpochs.toBigInt).asJson,
        "blocksCurrent" -> coinMap(
          sc.bcur.blocks.toVector.map((k, n) => k.bytes -> n.toBigInt),
          (b: Bytes) => b.hex
        ),
        "blocksBefore" -> coinMap(
          sc.bprev.blocks.toVector.map((k, n) => k.bytes -> n.toBigInt),
          (b: Bytes) => b.hex
        ),
        "snapshots" -> Json.obj(
          "mark" -> snapshotJson(snaps.mark),
          "set" -> snapshotJson(snaps.set),
          "go" -> snapshotJson(snaps.go),
          "fee" -> snaps.fee.value.asJson
        ),
        "rewardUpdate" -> due.fold(Json.Null)(ru =>
          Json.obj(
            "deltaT" -> ru.deltaT.value.asJson,
            "deltaR" -> ru.deltaR.value.asJson,
            "deltaF" -> ru.deltaF.value.asJson,
            "rs" -> coinMap(ru.byCredential.toVector, credName)
          )
        )
      )

/** Loopback HTTP query service: `GET /tip`, `GET /state?slot=S&hash=H`, `GET /metrics` and the
  * others below.
  */
object QueryServer:
  /** @param scheduling
    *   The whole-run scheduling measurements, served at `/scheduling`.
    * @param connections
    *   The connection manager's table and the inbound sessions, served at `/connections`.
    * @param forging
    *   The forger's counters per epoch, served at `/forging` (only on a node that forges).
    * @param metrics
    *   The metrics in the Prometheus text format, served at `/metrics` (see [[MetricsServer]]).
    */
  def resource[F[_]: Async: Network, S](
      view: ChainView[F, S],
      port: Int,
      utxoStore: Option[xyz.kd5ujc.scarab.storage.UtxoStore[F]] = None,
      scheduling: Option[F[Json]] = None,
      connections: Option[F[Json]] = None,
      forging: Option[F[Json]] = None,
      metrics: Option[F[String]] = None
  )(fatal: Fatal => F[Unit])(using read: ChainRead[S]): Resource[F, Server] =
    EmberServerBuilder
      .default[F]
      .withHost(Host.fromString("127.0.0.1").get)
      .withPort(Port.fromInt(port).get)
      .withHttpApp(
        routes(view, utxoStore, scheduling, connections, forging, metrics)(fatal).orNotFound
      )
      .build

  private enum StateTarget:
    case Tip
    case At(point: ChainSync.Point)

  private enum PointError:
    case Parameters, Slot, Hash

  private def parseSlot(raw: String): Either[PointError, ChainSync.UInt64] =
    if raw.isEmpty then Left(PointError.Slot)
    else
      raw.toList.foldLeftM(ChainSync.UInt64.saturating(0)) { (slot, digit) =>
        if digit >= '0' && digit <= '9' then
          ChainSync.UInt64.from(slot.value * 10 + (digit - '0')).leftMap(_ => PointError.Slot)
        else Left(PointError.Slot)
      }

  private def stateTarget(query: Query): Either[PointError, StateTarget] =
    val params = query.multiParams
    def single(name: String): Either[PointError, String] =
      params.get(name).collect { case Seq(value) => value }.toRight(PointError.Parameters)
    if !params.contains("slot") && !params.contains("hash") then Right(StateTarget.Tip)
    else
      for
        rawSlot <- single("slot")
        rawHash <- single("hash")
        slot <- parseSlot(rawSlot)
        hash <- Bytes.fromHex(rawHash).leftMap(_ => PointError.Hash)
        _ <- Either.cond(hash.size == 32, (), PointError.Hash)
      yield StateTarget.At(ChainSync.Point.Block(slot, hash))

  private[node] def routes[F[_]: Async, S](
      view: ChainView[F, S],
      utxoStore: Option[xyz.kd5ujc.scarab.storage.UtxoStore[F]] = None,
      scheduling: Option[F[Json]] = None,
      connections: Option[F[Json]] = None,
      forging: Option[F[Json]] = None,
      metrics: Option[F[String]] = None
  )(fatal: Fatal => F[Unit])(using read: ChainRead[S]): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*
    // With the UTxO set in a backing store the state holds only its recent changes: the whole set
    // is put back together for the projection.
    def complete(s: S): F[xyz.kd5ujc.scarab.ledger.Utxo] =
      utxoStore.fold(Async[F].pure(s.utxo)) { store =>
        StorageBoundary.run(fatal) {
          xyz.kd5ujc.scarab.storage.UtxoStore
            .materialize(store, s.utxo)
            .map(xyz.kd5ujc.scarab.ledger.Utxo.fromMap)
        }
      }
    def projected(p: ChainSync.Point, s: S): F[Response[F]] =
      complete(s).flatMap { utxo =>
        Projection.node(p, s, utxo) match
          case Right(json)   => Ok(json)
          case Left(refused) => InternalServerError(s"the state cannot be projected: $refused")
      }
    HttpRoutes.of[F] {
      case GET -> Root / "tip" =>
        view.tip.flatMap { case (p, s) =>
          Ok(Json.obj("point" -> Projection.point(p), "epoch" -> s.ledgerEpoch.asJson))
        }
      case GET -> Root / "connections" =>
        connections.fold(Async[F].pure(Response[F](Status.NotFound)))(_.flatMap(j => Ok(j)))
      case GET -> Root / "forging" =>
        forging.fold(Async[F].pure(Response[F](Status.NotFound)))(_.flatMap(j => Ok(j)))
      case GET -> Root / "metrics" =>
        metrics.fold(Async[F].pure(Response[F](Status.NotFound)))(
          _.flatMap(MetricsServer.respond(dsl, _))
        )
      case GET -> Root / "scheduling" =>
        scheduling.fold(Async[F].pure(Response[F](Status.NotFound)))(_.flatMap(j => Ok(j)))
      case request @ GET -> Root / "state" =>
        stateTarget(request.uri.query) match
          case Left(_) => BadRequest("slot and 32-byte hex hash required")
          case Right(StateTarget.Tip) =>
            view.tip.flatMap { case (p, s) => projected(p, s) }
          case Right(StateTarget.At(p)) =>
            view.at(p).flatMap {
              case Some(s) => projected(p, s)
              case None    => Async[F].pure(Response[F](Status.NotFound))
            }
    }

/** The Prometheus endpoint on its own: `GET /metrics` and nothing else, on an address of its
  * choice. The query server answers on loopback only and also serves the whole ledger state; this
  * is the listener to bind beyond loopback (in a container, for the scraper of the monitoring
  * stack).
  */
object MetricsServer:
  /** The text of a scrape as the response of `GET /metrics`, with the content type the format names
    * (written as the specification spells it, the version parameter unquoted).
    */
  def respond[F[_]: Async](dsl: Http4sDsl[F], text: String): F[Response[F]] =
    import dsl.*
    Ok(text).map(
      _.putHeaders(
        org.http4s.Header.Raw(org.typelevel.ci.CIString("Content-Type"), Exposition.ContentType)
      )
    )

  def resource[F[_]: Async: Network](
      address: SocketAddress[Host],
      scrape: F[String]
  ): Resource[F, Server] =
    val dsl = Http4sDsl[F]
    import dsl.*
    val routes = HttpRoutes.of[F] { case GET -> Root / "metrics" =>
      scrape.flatMap(respond(dsl, _))
    }
    EmberServerBuilder
      .default[F]
      .withHost(address.host)
      .withPort(address.port)
      .withHttpApp(routes.orNotFound)
      // a scraper keeps no state: do not wait for its idle connection when the node stops
      .withShutdownTimeout(1.second)
      .build
