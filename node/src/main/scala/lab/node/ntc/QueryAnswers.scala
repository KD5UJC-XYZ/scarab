// SPDX-License-Identifier: Apache-2.0
package lab.node.ntc

import java.time.{Instant, ZoneOffset}
import lab.cbor.Bytes
import lab.network.Era
import lab.network.ChainSync.Point
import lab.node.{EpochGeometry, NodeState}
import lab.node.consensus.HardForkConfig
import lab.node.ledger.*
import lab.node.ntc.LedgerEncoders as E

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
    observedEras: Map[Era, BigInt] = Map.empty
)

/** The acquired state: the point it was taken at, the block number there and the node state. */
final case class QueryView(point: Point, blockNo: Option[BigInt], state: NodeState):
  def ledger: LedgerState = state.ledger

/** Pure answers to node-to-client queries. `answer` returns the result item of the `MsgResult`
  * (`None` for a query this node does not answer; the connection then ends, as a decode failure
  * ends it on the reference). The UTxO queries take the entries they need as `utxo` (the caller
  * resolves them against the backing store first, see [[UtxoNeeds]]).
  *
  * Only the Conway era is answered: its `IfCurrent` queries get `[result]`, queries naming another
  * era get the era-mismatch reply, as the reference sends.
  */
object QueryAnswers:
  private val ConwayIndex = Era.Conway.index

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
      else if era != Era.Conway then None
      else ledgerAnswer(env, view, utxo, inner).map(r => CborOut.build(_.array(1).raw(r)))

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

  private def coin(w: CborOut, c: BigInt): Unit = w.uint(c)

  private def pulsed(view: QueryView): (PulsingSnapshot, RatifyState) =
    view.ledger.gov.pulsed.getOrElse(
      (PulsingSnapshot.empty, initialRatify(view.ledger))
    )

  /** The pulser state before the first epoch boundary: the enact state of the current ledger. */
  private def initialRatify(s: LedgerState): RatifyState =
    RatifyState(
      EnactState(
        s.gov.committee,
        s.gov.constitution,
        s.params,
        s.previousParams,
        s.treasury,
        Map.empty,
        s.gov.proposals.roots
      ),
      Vector.empty,
      Set.empty,
      delayed = false
    )

  /** DRep state as the query reports it: expiries brought forward by the dormant epochs. */
  private def drepStates(s: LedgerState): Map[Credential, DRepState] =
    if s.dormantEpochs == 0 then s.dreps
    else
      s.dreps.view.mapValues { d =>
        val actual = d.expiry + s.dormantEpochs
        if actual < s.epoch then d else d.copy(expiry = actual)
      }.toMap

  private def drepState(w: CborOut, d: DRepState): Unit =
    w.array(4).uint(d.expiry)
    E.maybe(w, d.anchor)(E.anchor)
    w.uint(d.deposit)
    E.set(w, d.delegs, E.credentialOrdering)(E.credential)

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
    def build(f: CborOut => Unit): Option[Bytes] = Some(CborOut.build(f(_)))
    q match
      case GetLedgerTip      => build(point(_, view.point))
      case GetEpochNo        => build(_.uint(s.epoch))
      case GetCurrentPParams => build(E.protocolParams(_, s.params))
      case GetGenesisConfig  => GenesisConfig.encode(env.shelleyGenesis).toOption
      case GetUTxOWhole      => build(E.utxo(_, utxo))
      case GetUTxOByAddress(addresses) =>
        build(E.utxo(_, utxo.filter { case (_, o) => addresses(o.address.raw) }))
      case GetUTxOByTxIn(inputs) =>
        build(E.utxo(_, utxo.filter { case (i, _) => inputs(i) }))
      case GetCBOR(inner) =>
        ledgerAnswer(env, view, utxo, inner).map(r => CborOut.build(_.tag(24).bytes(r)))
      case GetFilteredDelegationsAndRewardAccounts(credentials) =>
        val accounts = restrictAlways(s.accounts, credentials)
        build { w =>
          w.array(2)
          E.map(
            w,
            accounts.collect { case (c, a) if a.pool.isDefined => c -> a.pool.get }.toVector,
            E.credentialOrdering
          )(
            E.credential,
            (k, p) => k.bytes(p)
          )
          E.map(w, accounts.map { case (c, a) => c -> a.balance }.toVector, E.credentialOrdering)(
            E.credential,
            (k, b) => k.uint(b)
          )
        }
      case GetStakePools =>
        build(E.set(_, s.pools.keys, E.bytesOrdering, variable = false)((k, h) => k.bytes(h)))
      case GetStakePoolParams(pools) =>
        build(
          E.map(
            _,
            restrictAlways(s.pools, pools).map { case (id, p) => id -> p.params }.toVector,
            E.bytesOrdering
          )(
            (k, h) => k.bytes(h),
            E.poolParams
          )
        )
      case GetPoolState(pools) =>
        def pick[V](m: Map[Bytes, V]): Map[Bytes, V] = pools.fold(m)(restrictAlways(m, _))
        build { w =>
          w.array(4)
          E.map(w, pick(s.pools).map { case (id, p) => id -> p.params }.toVector, E.bytesOrdering)(
            (k, h) => k.bytes(h),
            E.poolParams
          )
          E.map(w, pick(s.futurePools).toVector, E.bytesOrdering)(
            (k, h) => k.bytes(h),
            E.poolParams
          )
          E.map(w, pick(s.retiring).toVector, E.bytesOrdering)(
            (k, h) => k.bytes(h),
            (k, e) => k.uint(e)
          )
          E.map(w, pick(s.pools).map { case (id, p) => id -> p.deposit }.toVector, E.bytesOrdering)(
            (k, h) => k.bytes(h),
            (k, d) => k.uint(d)
          )
        }
      case GetStakeSnapshots(pools) => build(stakeSnapshots(_, s, pools))
      case GetStakeDelegDeposits(credentials) =>
        build(
          E.map(
            _,
            restrict(s.accounts, credentials).map { case (c, a) => c -> a.deposit }.toVector,
            E.credentialOrdering
          )(
            E.credential,
            (k, d) => k.uint(d)
          )
        )
      case GetConstitution => build(E.constitution(_, s.gov.constitution))
      case GetGovState     => build(govState(_, view))
      case GetDRepState(credentials) =>
        build(
          E.map(_, restrict(drepStates(s), credentials).toVector, E.credentialOrdering)(
            E.credential,
            drepState
          )
        )
      case GetDRepStakeDistr(dreps) =>
        build(
          E.map(_, restrict(pulsed(view)._1.drepDistr, dreps).toVector, E.drepOrdering)(
            E.drep,
            (k, c) => k.uint(c)
          )
        )
      case GetCommitteeMembersState(cold, hot, statuses) =>
        build(committeeMembersState(_, view, cold, hot, statuses))
      case GetFilteredVoteDelegatees(credentials) =>
        build(
          E.map(
            _,
            restrict(s.accounts, credentials).collect {
              case (c, a) if a.drep.isDefined => c -> a.drep.get
            }.toVector,
            E.credentialOrdering
          )(E.credential, E.drep)
        )
      case GetAccountState =>
        build(_.array(2).uint(s.treasury).uint(s.reserves))
      case GetSPOStakeDistr(pools) =>
        build(
          E.map(_, restrict(pulsed(view)._1.poolDistr, pools).toVector, E.bytesOrdering)(
            (k, h) => k.bytes(h),
            (k, c) => k.uint(c)
          )
        )
      case GetProposals(ids) =>
        val all = pulsed(view)._1.proposals
        val chosen = if ids.isEmpty then all else all.filter(g => ids(g.id))
        build { w =>
          E.listHead(w, chosen.size)
          chosen.foreach(E.govActionState(w, _))
          E.end(w, chosen.size)
        }
      case GetRatifyState => build(E.ratifyState(_, pulsed(view)._2))
      case GetFuturePParams =>
        build(E.maybe(_, futureParams(view))(E.protocolParams))
      case QueryStakePoolDefaultVote(pool) => build(defaultVote(_, s, pool))
      case GetPoolDistr2(pools)            => build(poolDistr(_, s.poolDistribution, pools, None))
      case GetStakeDistribution2           =>
        // the current stake (not a snapshot), as a fraction of the lovelace in circulation
        build(poolDistr(_, Stake.snapshot(s), None, Some(env.maxSupply - s.reserves)))
      case GetMaxMajorProtocolVersion =>
        build(_.uint(env.forks.maxMajorProtVer))
      case GetDRepDelegations(dreps) => build(drepDelegations(_, s, dreps))
      case _                         => None

  /** The parameters that take effect at the next boundary, when one is already decided. */
  private def futureParams(view: QueryView): Option[ProtocolParams] =
    view.ledger.gov.pulsed.map(_._2.enact.params).filter(_ != view.ledger.params)

  private def defaultVote(w: CborOut, s: LedgerState, pool: Bytes): CborOut =
    // DefaultNo (0), DefaultAbstain (1) or DefaultNoConfidence (2), by the DRep the pool's reward
    // account delegates to.
    val vote = s.pools.get(pool).flatMap { p =>
      s.accounts.get(p.params.rewardAccount.credential).flatMap(_.drep)
    } match
      case Some(DRep.AlwaysAbstain)      => 1
      case Some(DRep.AlwaysNoConfidence) => 2
      case _                             => 0
    w.uint(vote)

  private def drepDelegations(w: CborOut, s: LedgerState, dreps: Set[DRep]): CborOut =
    val delegated: Map[DRep, Set[Credential]] =
      s.accounts.toVector
        .collect { case (c, a) if a.drep.isDefined => a.drep.get -> c }
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toSet)
        .toMap
    val registered: Map[DRep, Set[Credential]] =
      s.dreps.map { case (c, d) => DRep.Registered(c) -> d.delegs }
    val all =
      if dreps.isEmpty then
        registered ++ delegated.filter { case (k, _) =>
          k == DRep.AlwaysAbstain || k == DRep.AlwaysNoConfidence
        }
      else
        dreps.toVector
          .map { d =>
            d -> (d match
              case DRep.Registered(_) => registered.getOrElse(d, Set.empty)
              case _                  => delegated.getOrElse(d, Set.empty))
          }
          .filter(_._2.nonEmpty || dreps.exists {
            case DRep.Registered(_) => true; case _ => false
          })
          .toMap
    E.map(w, all.toVector, E.drepOrdering)(
      E.drep,
      (k, creds) => E.set(k, creds, E.credentialOrdering)(E.credential)
    )

  /** `PoolDistr`: each pool's stake as a fraction of `denominator` (the snapshot's active stake
    * unless a circulating supply is given), its stake and its VRF key hash, then the active stake.
    */
  private def poolDistr(
      w: CborOut,
      dist: Snapshot,
      pools: Option[Set[Bytes]],
      circulation: Option[BigInt]
  ): CborOut =
    val total = dist.activeStake
    val denominator = circulation.getOrElse(total)
    val chosen = dist.poolStake.filter { case (id, _) => pools.forall(_(id)) }
    w.array(2)
    E.map(w, chosen.toVector.map { case (id, stake) => id -> (id, stake) }, E.bytesOrdering)(
      (k, h) => k.bytes(h),
      (k, entry) =>
        val (id, stake) = entry
        val ratio = if denominator == 0 then Rational.zero else Rational(stake, denominator)
        k.array(3)
        E.rational(k, ratio)
        k.uint(stake)
        k.bytes(dist.pools.get(id).fold(Bytes.empty)(_.vrfKeyHash))
    )
    w.uint(total)

  private def stakeSnapshots(w: CborOut, s: LedgerState, pools: Option[Set[Bytes]]): CborOut =
    val snaps = Vector(s.snapshots.mark, s.snapshots.set, s.snapshots.go)
    val ids: Set[Bytes] = pools.getOrElse {
      if s.params.protocolMajor < 11 then
        snaps
          .flatMap(_.delegations.valuesIterator.filter(p => snaps.exists(_.pools.contains(p))))
          .toSet
          .filter(p =>
            snaps.exists(sn => sn.pools.contains(p) && sn.delegations.valuesIterator.contains(p))
          )
      else snaps.flatMap(_.poolStake.collect { case (p, st) if st > 0 => p }).toSet
    }
    def stakeOf(snap: Snapshot, p: Bytes): BigInt = snap.poolStake.getOrElse(p, BigInt(0))
    val rows: Vector[(Bytes, Vector[BigInt])] =
      ids.toVector.map(p => p -> snaps.map(stakeOf(_, p))).filter { case (_, st) =>
        s.params.protocolMajor < 11 || st.exists(_ > 0)
      }
    w.array(4)
    E.map(w, rows, E.bytesOrdering)(
      (k, h) => k.bytes(h),
      (k, st) => { k.array(3); st.foreach(k.uint) }
    )
    snaps.foreach(sn => w.uint(sn.activeStake))
    w

  private def committeeMembersState(
      w: CborOut,
      view: QueryView,
      cold: Set[Credential],
      hot: Set[Credential],
      statuses: Set[Int]
  ): CborOut =
    val s = view.ledger
    val members: Map[Credential, BigInt] =
      s.gov.committee.fold(Map.empty[Credential, BigInt])(_.members)
    val next: Map[Credential, BigInt] =
      pulsed(view)._2.enact.committee.fold(Map.empty[Credential, BigInt])(_.members)
    val stateMembers = s.committeeState
    def filterCold(c: Set[Credential]) = if cold.isEmpty then c else c.intersect(cold)
    val relevantCold: Set[Credential] =
      if statuses.isEmpty || statuses.contains(2) then
        filterCold(members.keySet ++ stateMembers.keySet ++ next.keySet)
      else filterCold(members.keySet)
    val relevantHot: Set[Credential] =
      stateMembers.collect { case (c, CommitteeAuth.Hot(h)) if hot(h) => c }.toSet
    val relevant = if hot.isEmpty then relevantCold else relevantCold.intersect(relevantHot)
    val epoch = s.epoch
    def status(c: Credential): Int = members.get(c) match
      case None                 => 2
      case Some(e) if epoch > e => 1
      case Some(_)              => 0
    def nextChange(c: Credential): Vector[BigInt] =
      val cur = members.get(c)
      val nxt = next.get(c)
      val expiringCurrent = cur.contains(epoch)
      val expiringNext = nxt.contains(epoch)
      if cur.isEmpty && nxt.isDefined then Vector(0)
      else if nxt.isEmpty then Vector(1)
      else if cur.isDefined && nxt.isDefined && cur != nxt && !expiringNext then Vector(4, nxt.get)
      else if expiringCurrent || expiringNext then Vector(3)
      else Vector(2)
    val rows = relevant.toVector.filter(c => statuses.isEmpty || statuses.contains(status(c)))
    w.array(3)
    E.map(w, rows.map(c => c -> c), E.credentialOrdering)(
      E.credential,
      (k, c) =>
        k.array(4)
        stateMembers.get(c) match
          case Some(CommitteeAuth.Hot(h)) => k.array(2).uint(0); E.credential(k, h)
          case Some(CommitteeAuth.Resigned(a)) =>
            k.array(2).uint(2); E.maybe(k, a)(E.anchor)
          case None => k.array(1).uint(1)
        k.uint(status(c))
        E.maybe(k, members.get(c))((k2, e) => k2.uint(e))
        val change = nextChange(c)
        if change.size == 2 then k.array(2).uint(change(0)).uint(change(1))
        else k.array(1).uint(change(0))
    )
    E.maybe(w, s.gov.committee.map(_.threshold))(E.rational)
    w.uint(epoch)

  private def govState(w: CborOut, view: QueryView): CborOut =
    val s = view.ledger
    w.array(7)
    E.proposals(w, s.gov.proposals)
    E.maybe(w, s.gov.committee)(E.committee)
    E.constitution(w, s.gov.constitution)
    E.protocolParams(w, s.params)
    E.protocolParams(w, s.previousParams)
    futureParams(view) match
      case None     => w.array(1).uint(0)
      case Some(pp) => w.array(2).uint(1); E.protocolParams(w, pp)
    val (snap, rs) = pulsed(view)
    w.array(2)
    w.array(4)
    E.listHead(w, snap.proposals.size)
    snap.proposals.foreach(E.govActionState(w, _))
    E.end(w, snap.proposals.size)
    E.map(w, snap.drepDistr.toVector, E.drepOrdering)(E.drep, (k, c) => k.uint(c))
    E.map(w, drepStatesOf(snap), E.credentialOrdering)(E.credential, drepState)
    E.map(w, snap.poolDistr.toVector, E.bytesOrdering)((k, h) => k.bytes(h), (k, c) => k.uint(c))
    E.ratifyState(w, rs)

  private def drepStatesOf(snap: PulsingSnapshot): Vector[(Credential, DRepState)] =
    snap.drepState.toVector
