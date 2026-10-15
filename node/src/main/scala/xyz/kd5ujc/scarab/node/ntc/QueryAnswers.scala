// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import scala.annotation.unused
import java.time.{Instant, ZoneOffset}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.consensus.HardForkConfig
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.consensus.state.ConsensusMirrorEnv
import xyz.kd5ujc.scarab.ledger.state.{FuturePParams, MirrorEnv, ProtVer, ShelleyBasedEra}
import xyz.kd5ujc.scarab.models.runtime.{Codec, Writer}
import xyz.kd5ujc.scarab.storage.haskell.state.{
  HaskellStateCodecs,
  Params,
  Record,
  UtxoCodecs,
  Versions,
  Wire
}
import xyz.kd5ujc.scarab.codec.Rational

/** What the answers to a query depend on besides the acquired state: the chain's static
  * configuration and the era transitions this node has seen.
  */
final case class QueryEnv(
    systemStart: Instant,
    geometry: EpochGeometry,
    forks: HardForkConfig,
    byron: EraHistory.ByronParams,
    maxSupply: BigInt,
    shelleyGenesis: io.circe.Json,
    mirror: MirrorEnv,
    observedEras: Map[Era, BigInt] = Map.empty
):
  /** What the mirror of the ledger state (`DebugEpochState`, `DebugNewEpochState`) reads. */
  def consensusMirror: ConsensusMirrorEnv = ConsensusMirrorEnv(mirror, forks, byron, observedEras)

/** The acquired state: the point it was taken at, the block number there and the node state. */
final case class QueryView(point: Point, blockNo: Option[BigInt], state: NodeState):
  def ledger: LedgerState = state.ledger

  // What several queries on the acquired state share, taken once per view.

  /** The pulser the governance state reports ([[ConwayEpoch.reportedPulser]]). */
  lazy val pulsed: (PulsingSnapshot, RatifyState) = ConwayEpoch.reportedPulser(ledger)

  /** DRep state as the queries report it: expiries brought forward by the dormant epochs. */
  lazy val drepStates: Map[Credential, DRepState] =
    val s = ledger
    if s.dormantEpochs == 0 then s.dreps
    else
      s.dreps.view.mapValues { d =>
        val actual = d.expiry + s.dormantEpochs
        if actual < s.epoch then d else d.copy(expiry = actual)
      }.toMap

  /** The current stake as a snapshot, which `GetStakeDistribution2` reports. */
  lazy val currentStake: Snapshot = Stake.snapshot(ledger)

/** Pure answers to node-to-client queries. `answer` returns the result item of the `MsgResult`
  * (`None` for a query this node does not answer; the connection then ends, as a decode failure
  * ends it on the reference). The UTxO queries take the entries they need as `utxo` (the caller
  * resolves them against the backing store first, see [[UtxoNeeds]]).
  *
  * Only the Conway era is answered: its `IfCurrent` queries get `[result]`, queries naming another
  * era get the era-mismatch reply, as the reference sends.
  */
object QueryAnswers:
  @unused private val ConwayIndex = Era.Conway.index

  /** What the caller must fetch from the UTxO store before calling `answer`. */
  enum UtxoNeeds:
    case Nothing
    case Whole
    case Inputs(inputs: Set[TxIn])

  def utxoNeeds(q: NtcQuery): UtxoNeeds = q match
    case NtcQuery.IfCurrent(_, inner) => ledgerNeeds(inner)
    case _                            => UtxoNeeds.Nothing

  private def ledgerNeeds(q: LedgerQuery): UtxoNeeds = q match
    case LedgerQuery.GetUTxOWhole | LedgerQuery.GetUTxOByAddress(_) => UtxoNeeds.Whole
    case LedgerQuery.GetUTxOByTxIn(ins)                             => UtxoNeeds.Inputs(ins)
    case LedgerQuery.GetCBOR(inner)                                 => ledgerNeeds(inner)
    case _                                                          => UtxoNeeds.Nothing

  def currentEra(env: QueryEnv, view: QueryView): Era =
    view.state.boot.era.getOrElse(
      env.forks.eraAt(view.ledger.epoch, view.ledger.params.protocolMajor)
    )

  def answer(
      env: QueryEnv,
      view: QueryView,
      utxo: Map[TxIn, TxOut],
      query: NtcQuery
  ): Option[Bytes] = query match
    case NtcQuery.SystemStart => Some(CborOut.build(systemStart(_, env.systemStart)))
    case NtcQuery.ChainBlockNo =>
      Some(CborOut.build { w =>
        view.blockNo match
          case Some(n) => w.array(2).uint(1).uint(n)
          case None    => w.array(1).uint(0)
      })
    case NtcQuery.ChainPoint        => Some(CborOut.build(point(_, view.point)))
    case NtcQuery.DebugLedgerConfig => None
    case NtcQuery.HardFork(HardForkQuery.GetCurrentEra) =>
      Some(CborOut.build(_.uint(currentEra(env, view).index)))
    case NtcQuery.HardFork(HardForkQuery.GetInterpreter) =>
      val era = currentEra(env, view)
      val tipSlot = view.point match
        case Point.Block(slot, _) => slot.value
        case Point.Origin         => BigInt(0)
      val eras =
        EraHistory.summary(env.geometry, env.byron, env.forks, env.observedEras, era, tipSlot)
      Some(CborOut.build(EraHistory.encode(_, eras)))
    case NtcQuery.EraStart(index) =>
      val era = currentEra(env, view)
      val eras = EraHistory.summary(
        env.geometry,
        env.byron,
        env.forks,
        env.observedEras,
        era,
        BigInt(0)
      )
      Some(CborOut.build { w =>
        eras.lift(index).filter(_ => index <= era.index) match
          case Some(e) =>
            w.array(1).array(3).uint(e.start.timePs).uint(e.start.slot).uint(e.start.epoch)
          case None => w.array(0)
      })
    case NtcQuery.IfCurrent(index, inner) =>
      val era = currentEra(env, view)
      if index != era.index then Some(CborOut.build(mismatch(_, index, era)))
      else if era != Era.Conway && !isStateDump(inner) then None
      else ledgerAnswer(env, view, utxo, inner).map(r => CborOut.build(_.array(1).raw(r)))

  /** The queries answered in every Shelley-based era: the ledger state as the Haskell node writes
    * it (the others are Conway's).
    */
  private def isStateDump(q: LedgerQuery): Boolean = q match
    case LedgerQuery.DebugEpochState | LedgerQuery.DebugNewEpochState => true
    case LedgerQuery.GetCBOR(inner)                                   => isStateDump(inner)
    case _                                                            => false

  private def mismatch(w: CborOut, queried: Int, ledger: Era): CborOut =
    def name(i: Int): String = Era.values.find(_.index == i).fold("Unknown")(_.toString)
    w.array(2)
      .array(2)
      .uint(queried)
      .text(name(queried))
      .array(2)
      .uint(ledger.index)
      .text(ledger.toString)

  /** `UTCTime` as the system start is serialised: `[year, day of year, picoseconds of the day]`. */
  private def systemStart(w: CborOut, at: Instant): CborOut =
    val utc = at.atZone(ZoneOffset.UTC)
    val seconds = utc.getHour * 3600L + utc.getMinute * 60L + utc.getSecond
    val picos = BigInt(seconds) * BigInt(10).pow(12) + BigInt(utc.getNano) * 1000
    w.array(3).uint(utc.getYear).uint(utc.getDayOfYear).uint(picos)

  private def point(w: CborOut, p: Point): CborOut = p match
    case Point.Origin            => w.array(0)
    case Point.Block(slot, hash) => w.array(2).uint(slot.value).bytes(hash)

  // ---- the ledger queries

  /** The version the answers are written at: the lowest of the Conway era, which is how the
    * reference encodes them (`Versions.eraLow`): rationals under tag 30, sets under tag 258.
    */
  private given ProtVer = Versions.eraLow(ShelleyBasedEra.SConway)

  import HaskellStateCodecs.{
    anchor,
    committee,
    constitution,
    futurePParams,
    govActionState,
    nodeCredential,
    nodeDRep,
    nodeDRepState,
    poolParams,
    proposals as proposalStore,
    pulsingSnapshot,
    ratifyState
  }
  import xyz.kd5ujc.scarab.storage.haskell.state.NodeOrders.given

  // the codecs of the answers: the node's own types, written in the ledger's orders

  private val coin = Wire.word(64)
  private val poolId = Wire.hash(28)
  private val protocolParams = Params.conwayCodec
  private val pools = Wire.sortedMapOf(poolId, poolParams)

  /** The bytes of an answer, or none when its value is outside what the Haskell type holds. */
  extension [A](codec: Codec[A]) private def answer(a: A): Option[Bytes] = codec.write(a).toOption

  /** A pool's share of the stake: its ratio of the total, its stake and its VRF key hash. */
  private final case class PoolShare(ratio: Rational, stake: BigInt, vrf: Bytes)

  private val poolShare: Codec[PoolShare] =
    Record.of[PoolShare]((Wire.ratio, coin, Wire.hash(32)))

  private val poolDistribution: Codec[(Map[Bytes, PoolShare], BigInt)] =
    Record.of[(Map[Bytes, PoolShare], BigInt)]((Wire.sortedMapOf(poolId, poolShare), coin))

  private def restrict[K, V](m: Map[K, V], keys: Set[K]): Map[K, V] =
    if keys.isEmpty then m else m.filter { case (k, _) => keys(k) }

  private def restrictAlways[K, V](m: Map[K, V], keys: Set[K]): Map[K, V] =
    m.filter { case (k, _) => keys(k) }

  private def ledgerAnswer(
      env: QueryEnv,
      view: QueryView,
      utxo: Map[TxIn, TxOut],
      q: LedgerQuery
  ): Option[Bytes] =
    val s = view.ledger
    import LedgerQuery.*
    q match
      case GetLedgerTip       => Some(CborOut.build(point(_, view.point)))
      case GetEpochNo         => coin.answer(s.epoch)
      case GetCurrentPParams  => protocolParams.answer(s.params)
      case GetGenesisConfig   => GenesisConfig.encode(env.shelleyGenesis).toOption
      case DebugChainDepState => chainDepState(view)
      case DebugEpochState =>
        MirrorAnswers.epochState(env.consensusMirror, view).toOption
      case DebugNewEpochState =>
        MirrorAnswers.newEpochState(env.consensusMirror, view).toOption
      case GetUTxOWhole => UtxoCodecs.utxo.answer(utxo)
      case GetUTxOByAddress(addresses) =>
        UtxoCodecs.utxo.answer(utxo.filter { case (_, o) => addresses(o.address.raw) })
      case GetUTxOByTxIn(inputs) =>
        UtxoCodecs.utxo.answer(utxo.filter { case (i, _) => inputs(i) })
      case GetCBOR(inner) =>
        ledgerAnswer(env, view, utxo, inner).map(r => CborOut.build(_.tag(24).bytes(r)))
      case GetFilteredDelegationsAndRewardAccounts(credentials) =>
        val accounts = restrictAlways(s.accounts, credentials)
        delegationsAndRewards.answer(
          (
            accounts.collect { case (c, Account(_, _, Some(pool), _)) => c -> pool },
            accounts.map { case (c, a) => c -> a.balance }
          )
        )
      case GetStakePools =>
        Wire.definiteSetOf(poolId).answer(s.pools.keySet)
      case GetStakePoolParams(ids) =>
        pools.answer(restrictAlways(s.pools, ids).map { case (id, p) => id -> p.params })
      case GetPoolState(ids) =>
        def pick[V](m: Map[Bytes, V]): Map[Bytes, V] = ids.fold(m)(restrictAlways(m, _))
        poolState.answer(
          (
            pick(s.pools).map { case (id, p) => id -> p.params },
            pick(s.futurePools),
            pick(s.retiring),
            pick(s.pools).map { case (id, p) => id -> p.deposit }
          )
        )
      case GetStakeSnapshots(ids) => stakeSnapshots.answer(stakeSnapshotsOf(s, ids))
      case GetStakeDelegDeposits(credentials) =>
        Wire
          .sortedMapOf(nodeCredential, coin)
          .answer(restrict(s.accounts, credentials).map { case (c, a) => c -> a.deposit })
      case GetConstitution => constitution.answer(s.gov.constitution)
      case GetGovState     => govState.answer(govStateOf(env, view))
      case GetDRepState(credentials) =>
        Wire
          .sortedMapOf(nodeCredential, nodeDRepState)
          .answer(restrict(view.drepStates, credentials))
      case GetDRepStakeDistr(dreps) =>
        Wire.sortedMapOf(nodeDRep, coin).answer(restrict(view.pulsed._1.drepDistr, dreps))
      case GetCommitteeMembersState(cold, hot, statuses) =>
        committeeMembers.answer(committeeMembersState(view, cold, hot, statuses))
      case GetFilteredVoteDelegatees(credentials) =>
        Wire
          .sortedMapOf(nodeCredential, nodeDRep)
          .answer(restrict(s.accounts, credentials).collect { case (c, Account(_, _, _, Some(d))) =>
            c -> d
          })
      case GetAccountState =>
        Record.of[(BigInt, BigInt)]((coin, coin)).answer((s.treasury, s.reserves))
      case GetSPOStakeDistr(ids) =>
        Wire.sortedMapOf(poolId, coin).answer(restrict(view.pulsed._1.poolDistr, ids))
      case GetProposals(ids) =>
        val all = view.pulsed._1.proposals
        Wire.seqOf(govActionState).answer(if ids.isEmpty then all else all.filter(g => ids(g.id)))
      case GetRatifyState => ratifyState.answer(view.pulsed._2)
      case GetFuturePParams =>
        Wire.strictMaybe(protocolParams).answer(futureParams(env, view))
      case QueryStakePoolDefaultVote(pool) => Codec.intRange(0, 2).answer(defaultVote(s, pool))
      case GetPoolDistr2(ids) =>
        poolDistribution.answer(poolDistr(s.poolDistribution, ids, None))
      case GetStakeDistribution2 =>
        // the current stake (not a snapshot), as a fraction of the lovelace in circulation
        poolDistribution.answer(
          poolDistr(view.currentStake, None, Some(env.maxSupply - s.reserves))
        )
      case GetMaxMajorProtocolVersion =>
        coin.answer(BigInt(env.forks.maxMajorProtVer))
      case GetDRepDelegations(dreps) =>
        Wire
          .sortedMapOf(nodeDRep, Wire.sortedSetOf(nodeCredential))
          .answer(drepDelegations(s, dreps))
      case _ => None

  private val delegationsAndRewards: Codec[(Map[Credential, Bytes], Map[Credential, BigInt])] =
    Record.of[(Map[Credential, Bytes], Map[Credential, BigInt])](
      (Wire.sortedMapOf(nodeCredential, poolId), Wire.sortedMapOf(nodeCredential, coin))
    )

  /** `QueryPoolState`: the pools' parameters, their future parameters, their retirements and their
    * deposits.
    */
  private val poolState: Codec[
    (Map[Bytes, PoolParams], Map[Bytes, PoolParams], Map[Bytes, BigInt], Map[Bytes, BigInt])
  ] =
    Record.of[
      (Map[Bytes, PoolParams], Map[Bytes, PoolParams], Map[Bytes, BigInt], Map[Bytes, BigInt])
    ]((pools, pools, Wire.sortedMapOf(poolId, coin), Wire.sortedMapOf(poolId, coin)))

  /** `PraosState` as `Serialise` writes it: a version tag, then the last slot, the operational
    * certificate counters and the six nonces.
    */
  private def chainDepState(view: QueryView): Option[Bytes] =
    val p = view.state.praos
    val counters = Wire.definiteMapOf(poolId, coin)
    Some(build { w =>
      w.arrayHead(2)
      w.uint(0L)
      w.arrayHead(8)
      view.point match
        case Point.Block(slot, _) =>
          w.arrayHead(2)
          w.uint(1L)
          w.uint(slot.value)
        case Point.Origin =>
          w.arrayHead(1)
          w.uint(0L)
      counters.encode(p.counters, w)
      List(p.evolving, p.candidate, p.epochNonce, p.previousEpochNonce, p.lab, p.lastEpochBlock)
        .foreach(Wire.nonce.encode(_, w))
    })

  private def build(write: Writer => Unit): Bytes =
    val w = new Writer
    write(w)
    w.result

  /** `FuturePParams` of the governance state (the mirror's [[FuturePParams]]): nothing before the
    * first epoch boundary; after it `PotentialPParamsUpdate` right after the block that crossed a
    * boundary and until two stability windows before the end of the epoch (the point of no return,
    * when the tick solidifies it), then `DefinitePParamsUpdate` when ratification enacted a
    * parameter change or hard fork and `NoPParamsUpdate` otherwise. What it holds is the parameters
    * of the ratification the pulser will finish.
    */
  private def future(env: QueryEnv, view: QueryView): FuturePParams =
    view.ledger.gov.pulsed match
      case None => FuturePParams.NoPParamsUpdate
      case Some((_, ratify)) =>
        val changes = ratify.enacted.exists(g =>
          g.action match
            case _: GovAction.ParameterChange | _: GovAction.HardFork => true
            case _                                                    => false
        )
        val next = Option.when(changes)(ratify.enact.params)
        val slot = view.point match
          case Point.Block(s, _) => s.value
          case Point.Origin      => BigInt(0)
        val firstOfEpoch = view.ledger.blocksCurrent.valuesIterator.sum <= 1
        val pointOfNoReturn =
          env.geometry.firstSlot(view.ledger.epoch + 1) - 2 * env.geometry.stabilityWindow
        if firstOfEpoch || slot < pointOfNoReturn then FuturePParams.PotentialPParamsUpdate(next)
        else next.fold(FuturePParams.NoPParamsUpdate)(FuturePParams.DefinitePParamsUpdate(_))

  private def futureParams(env: QueryEnv, view: QueryView): Option[ProtocolParams] =
    future(env, view).nextParams

  /** The governance state (`ConwayGovState`) as `GetGovState` answers it: the proposals, the
    * committee, the constitution, the current, previous and future parameters, and the completed
    * pulser.
    */
  private type GovAnswer = (
      Proposals,
      Option[Committee],
      Constitution,
      ProtocolParams,
      ProtocolParams,
      FuturePParams,
      (PulsingSnapshot, RatifyState)
  )

  private val govState: Codec[GovAnswer] =
    Record.of[GovAnswer](
      (
        proposalStore,
        Wire.strictMaybe(committee),
        constitution,
        protocolParams,
        protocolParams,
        futurePParams(protocolParams),
        Record.of[(PulsingSnapshot, RatifyState)]((pulsingSnapshot, ratifyState))
      )
    )

  private def govStateOf(env: QueryEnv, view: QueryView): GovAnswer =
    val s = view.ledger
    (
      s.gov.proposals,
      s.gov.committee,
      s.gov.constitution,
      s.params,
      ConwayEpoch.reportedPrevious(s),
      future(env, view),
      view.pulsed
    )

  /** The default vote of a pool: DefaultNo (0), DefaultAbstain (1) or DefaultNoConfidence (2), by
    * the DRep the pool's reward account delegates to.
    */
  private def defaultVote(s: LedgerState, pool: Bytes): Int =
    s.pools.get(pool).flatMap { p =>
      s.accounts.get(p.params.rewardAccount.credential).flatMap(_.drep)
    } match
      case Some(DRep.AlwaysAbstain)      => 1
      case Some(DRep.AlwaysNoConfidence) => 2
      case _                             => 0

  /** `queryDRepDelegations`: for a non-empty set of registered DReps their reverse delegation sets;
    * otherwise (an empty set, or a set naming always-abstain or always-no-confidence) the accounts
    * grouped by the DRep they delegate to, restricted to the set when it is not empty.
    */
  private def drepDelegations(s: LedgerState, dreps: Set[DRep]): Map[DRep, Set[Credential]] =
    val registered = dreps.collect { case DRep.Registered(c) => c }
    if dreps.nonEmpty && registered.size == dreps.size then
      s.dreps.collect { case (c, d) if registered(c) => DRep.Registered(c) -> d.delegs }
    else
      s.accounts.toVector
        .collect { case (c, Account(_, _, _, Some(d))) if dreps.isEmpty || dreps(d) => d -> c }
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toSet)
        .toMap

  /** `PoolDistr`: each pool's stake as a fraction of `denominator` (the snapshot's active stake
    * unless a circulating supply is given), its stake and its VRF key hash, then the active stake.
    */
  private def poolDistr(
      dist: Snapshot,
      ids: Option[Set[Bytes]],
      circulation: Option[BigInt]
  ): (Map[Bytes, PoolShare], BigInt) =
    val total = dist.activeStake
    val denominator = circulation.getOrElse(total)
    val shares = dist.poolStake.collect {
      case (id, stake) if ids.forall(_(id)) =>
        val ratio = if denominator == 0 then Rational.zero else Rational(stake, denominator)
        id -> PoolShare(ratio, stake, dist.pools.get(id).fold(Bytes.empty)(_.vrfKeyHash))
    }
    (shares, total.max(1))

  /** `GetStakeSnapshots`: each pool's stake in the mark, set and go snapshots, and their totals. */
  private type StakeSnapshotsAnswer =
    (Map[Bytes, (BigInt, BigInt, BigInt)], BigInt, BigInt, BigInt)

  private val stakeSnapshots: Codec[StakeSnapshotsAnswer] =
    Record.of[StakeSnapshotsAnswer](
      (
        Wire.sortedMapOf(poolId, Record.of[(BigInt, BigInt, BigInt)]((coin, coin, coin))),
        coin,
        coin,
        coin
      )
    )

  private def stakeSnapshotsOf(s: LedgerState, ids: Option[Set[Bytes]]): StakeSnapshotsAnswer =
    val snaps = Vector(s.snapshots.mark, s.snapshots.set, s.snapshots.go)
    // Without a list of pools: before protocol version 11 the pools that have delegators in any
    // snapshot, from 11 those with stake.
    val chosen: Set[Bytes] = ids.getOrElse {
      if s.params.protocolMajor < 11 then
        snaps.flatMap(sn => sn.delegations.valuesIterator.filter(sn.pools.contains)).toSet
      else snaps.flatMap(_.poolStake.collect { case (p, st) if st > 0 => p }).toSet
    }
    def stakeOf(snap: Snapshot, p: Bytes): BigInt = snap.poolStake.getOrElse(p, BigInt(0))
    val rows = chosen.toVector
      .map(p => p -> snaps.map(stakeOf(_, p)))
      .filter { case (_, st) => s.params.protocolMajor < 11 || st.exists(_ > 0) }
      .map { case (p, st) => p -> (st(0), st(1), st(2)) }
    // the totals are `NonZero`: an empty snapshot counts as 1
    (
      rows.toMap,
      snaps(0).activeStake.max(1),
      snaps(1).activeStake.max(1),
      snaps(2).activeStake.max(1)
    )

  // ---- the committee members' state

  /** Where a committee member stands (`MemberStatus`): the code is the constructor's index. */
  private enum MemberStatus(val code: Int):
    case Active extends MemberStatus(0)
    case Expired extends MemberStatus(1)
    case Unrecognized extends MemberStatus(2)

  /** What the next epoch holds for a member (`NextEpochChange`). */
  private enum NextChange:
    case ToBeEnacted, ToBeRemoved, NoChangeExpected, ToBeExpired
    case TermAdjusted(epoch: BigInt)

  private final case class MemberRow(
      auth: Option[CommitteeAuth],
      status: MemberStatus,
      expiry: Option[BigInt],
      change: NextChange
  )

  /** `MemberAuthorization`: `[0, hot credential]`, `[1]` for none, `[2, anchor?]` for resigned. */
  private val memberAuth: Codec[Option[CommitteeAuth]] =
    Wire.writeOnly("a committee member's authorization") { (auth, w) =>
      auth match
        case Some(CommitteeAuth.Hot(h)) =>
          w.arrayHead(2)
          w.uint(0L)
          nodeCredential.encode(h, w)
        case Some(CommitteeAuth.Resigned(a)) =>
          w.arrayHead(2)
          w.uint(2L)
          Wire.strictMaybe(anchor).encode(a, w)
        case None =>
          w.arrayHead(1)
          w.uint(1L)
    }

  private val nextChange: Codec[NextChange] = Wire.writeOnly("a committee member's next change") {
    (change, w) =>
      def constructor(index: Long): Unit =
        w.arrayHead(1)
        w.uint(index)
      change match
        case NextChange.ToBeEnacted      => constructor(0)
        case NextChange.ToBeRemoved      => constructor(1)
        case NextChange.NoChangeExpected => constructor(2)
        case NextChange.ToBeExpired      => constructor(3)
        case NextChange.TermAdjusted(e) =>
          w.arrayHead(2)
          w.uint(4L)
          w.uint(e)
  }

  private val memberRow: Codec[MemberRow] = Wire.writeOnly("a committee member's state") {
    (row, w) =>
      w.arrayHead(4)
      memberAuth.encode(row.auth, w)
      w.uint(row.status.code.toLong)
      Wire.strictMaybe(coin).encode(row.expiry, w)
      nextChange.encode(row.change, w)
  }

  private type CommitteeAnswer = (Map[Credential, MemberRow], Option[Rational], BigInt)

  private val committeeMembers: Codec[CommitteeAnswer] =
    Record.of[CommitteeAnswer](
      (Wire.sortedMapOf(nodeCredential, memberRow), Wire.strictMaybe(Wire.ratio), coin)
    )

  private def committeeMembersState(
      view: QueryView,
      cold: Set[Credential],
      hot: Set[Credential],
      statuses: Set[Int]
  ): CommitteeAnswer =
    val s = view.ledger
    val members: Map[Credential, BigInt] =
      s.gov.committee.fold(Map.empty[Credential, BigInt])(_.members)
    val next: Map[Credential, BigInt] =
      view.pulsed._2.enact.committee.fold(Map.empty[Credential, BigInt])(_.members)
    val stateMembers = s.committeeState
    def filterCold(c: Set[Credential]) = if cold.isEmpty then c else c.intersect(cold)
    val relevantCold: Set[Credential] =
      if statuses.isEmpty || statuses.contains(MemberStatus.Unrecognized.code) then
        filterCold(members.keySet ++ stateMembers.keySet ++ next.keySet)
      else filterCold(members.keySet)
    val relevantHot: Set[Credential] =
      stateMembers.collect { case (c, CommitteeAuth.Hot(h)) if hot(h) => c }.toSet
    val relevant = if hot.isEmpty then relevantCold else relevantCold.intersect(relevantHot)
    val epoch = s.epoch
    def status(c: Credential): MemberStatus = members.get(c) match
      case None                 => MemberStatus.Unrecognized
      case Some(e) if epoch > e => MemberStatus.Expired
      case Some(_)              => MemberStatus.Active
    def change(c: Credential): NextChange =
      val cur = members.get(c)
      val nxt = next.get(c)
      val expiringCurrent = cur.contains(epoch)
      val expiringNext = nxt.contains(epoch)
      (cur, nxt) match
        case (None, Some(_))                               => NextChange.ToBeEnacted
        case (_, None)                                     => NextChange.ToBeRemoved
        case (Some(a), Some(b)) if a != b && !expiringNext => NextChange.TermAdjusted(b)
        case _ if expiringCurrent || expiringNext          => NextChange.ToBeExpired
        case _                                             => NextChange.NoChangeExpected
    val rows = relevant.toVector
      .filter(c => statuses.isEmpty || statuses.contains(status(c).code))
      .map(c => c -> MemberRow(stateMembers.get(c), status(c), members.get(c), change(c)))
    (rows.toMap, s.gov.committee.map(_.threshold), epoch)
