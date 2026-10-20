// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.syntax.all.*
import scala.annotation.unused
import scala.collection.immutable.SortedMap
import java.time.{Instant, ZoneOffset}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Era, NtcVersion}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.consensus.{BlockRef, HardForkConfig}
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, ConsensusMirrorEnv}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.rules.StakeDistribution
import xyz.kd5ujc.scarab.ledger.state as mirror
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  ConwayCertState,
  ConwayGovState,
  FuturePParams,
  MirrorEnv,
  MirrorError,
  ProtVer,
  ShelleyBasedEra
}
import xyz.kd5ujc.scarab.node.read.{ChainRead, EraRead, EraViews, InEra, Read}
import xyz.kd5ujc.scarab.models.runtime.Codec
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
    observedEras: Map[Era, BigInt] = Map.empty,
    networkMagic: Long
):
  /** What the mirror of the ledger state (`DebugEpochState`, `DebugNewEpochState`) reads. */
  def consensusMirror: ConsensusMirrorEnv = ConsensusMirrorEnv(mirror, forks, byron, observedEras)

/** Why a query has no answer: the closed set of the reasons, where the connection then ends (as a
  * decode failure ends it on the reference).
  */
enum Unanswered:
  /** The node does not answer this query (in this era). */
  case Unsupported

  /** The state cannot be read in the vocabulary of the mirror (a value outside the range of its
    * Haskell type).
    */
  case Refused(error: MirrorError)

  /** The answer holds a value outside the range of the Haskell type it is written as. */
  case Unwritable

/** The acquired state: the point it was taken at, the block number there and the state of the chain
  * (the mirror: [[ChainRead]] reads it).
  *
  * What several queries on the acquired state share is taken once per view, and only if a query
  * asks for it.
  */
final case class QueryView[S](point: Point, blockNo: Option[BigInt], state: S)(using
    val read: ChainRead[S]
):
  /** The era the chain is in (`GetCurrentEra`). */
  def era: Era = read.era(state)

  /** The UTxO set of the state: the handle on the diff over the backing store. */
  def utxo: Utxo = read.utxo(state)

  private def tip: Option[BlockRef] = point match
    case Point.Block(slot, hash) =>
      Some(BlockRef(slot.value, hash, blockNo.getOrElse(BigInt(0))))
    case Point.Origin => None

  /** The ledger state of the era the chain is in. */
  lazy val ledger: Read[Option[InEra]] = read.ledger(state)(tip)

  /** What the Conway queries read, if the chain is in Conway. */
  lazy val conway: Read[Option[ConwayFacts]] = ledger.map(_.flatMap { case InEra.Of(era, l) =>
    era match
      case ShelleyBasedEra.SConway => Some(new ConwayFacts(l))
      case _                       => None
  })

/** What the queries of the Conway era read of its ledger state, in the vocabulary of the mirror;
  * each part is read the first time and kept.
  */
final class ConwayFacts(val l: EraRead[Conway]):
  lazy val cert: Read[ConwayCertState] = l.cert
  lazy val gov: Read[ConwayGovState] = l.gov
  lazy val scalars: Read[mirror.LedgerScalars] = l.scalars
  lazy val snapshots: Read[mirror.SnapShots] = l.snapshots
  lazy val instantStake: Read[mirror.ConwayInstantStake] = l.instantStake

  def epoch: BigInt = l.epoch.toBigInt

  /** The accounts by credential, in the vocabulary of the node (what the queries are asked in). */
  lazy val accounts: Read[Map[Credential, mirror.ConwayAccountState]] =
    cert.map(_.dState.accounts.states.iterator.map((c, a) => EraRead.credential(c) -> a).toMap)

  /** The registered pools by id. */
  lazy val pools: Read[Map[Bytes, mirror.StakePoolState]] =
    cert.map(_.pState.stakePools.iterator.map((id, p) => id.bytes -> p).toMap)

  /** DRep state as the queries report it: expiries brought forward by the dormant epochs. */
  lazy val drepStates: Read[Map[Credential, DRepState]] = cert.map { c =>
    val dormant = c.vState.numDormantEpochs.toBigInt
    c.vState.dreps.iterator.map { (cred, d) =>
      val expiry = d.expiry.toBigInt
      val actual = expiry + dormant
      EraRead.credential(cred) -> DRepState(
        d.deposit.toBigInt,
        if dormant == 0 || actual < epoch then expiry else actual,
        d.anchor,
        d.delegators.iterator.map(EraRead.credential).toSet
      )
    }.toMap
  }

  /** The committee state by cold credential. */
  lazy val committeeState: Read[Map[Credential, CommitteeAuth]] =
    cert.map(
      _.vState.committeeState.creds.iterator
        .map { (c, a) =>
          EraRead.credential(c) -> (a match
            case mirror.CommitteeAuthorization.CommitteeHotCredential(h) =>
              CommitteeAuth.Hot(EraRead.credential(h))
            case mirror.CommitteeAuthorization.CommitteeMemberResigned(anchor) =>
              CommitteeAuth.Resigned(anchor))
        }
        .toMap
    )

  /** The current stake as a snapshot, which `GetStakeDistribution2` reports: the instant stake of
    * the UTxO and the rewards, over the accounts that delegate to a registered pool.
    */
  lazy val currentStake: Read[mirror.SnapShot] =
    for
      c <- cert
      instant <- instantStake
      active <- StakeDistribution
        .resolve(
          instant.credentialStake,
          EraViews
            .accounts[Conway](c)
            .map((k, a) =>
              k -> xyz.kd5ujc.scarab.ledger.rules.EraLedger
                .AccountView(a.balance, a.deposit, a.pool)
            ),
          SortedMap.empty[Ptr, mirror.Credential[mirror.Staking]],
          SortedMap.empty[Ptr, mirror.CompactCoin]
        )
        .leftMap(MirrorError.Vocab(_))
      snap <- StakeDistribution.snapshot(active, c.pState.stakePools).leftMap(MirrorError.Vocab(_))
    yield snap

/** Pure answers to node-to-client queries. `answer` returns the result item of the `MsgResult`
  * (`None` for a query this node does not answer; the connection then ends, as a decode failure
  * ends it on the reference). The UTxO queries take the entries they need as `utxo` (the caller
  * resolves them against the backing store first, see [[UtxoNeeds]]).
  *
  * Only the Conway era is answered: its `IfCurrent` queries get `[result]`, queries naming another
  * era get the era-mismatch reply, as the reference sends. The ledger state is read through
  * [[ChainRead]]: from the mirror's records, whichever form the state is in.
  */
object QueryAnswers:
  @unused private val ConwayIndex = Era.Conway.index

  /** What the caller must fetch from the UTxO store before calling `answer`. */
  enum UtxoNeeds:
    case Nothing
    case Whole
    case Addresses(addresses: Set[Bytes])
    case Inputs(inputs: Set[TxIn])

  def utxoNeeds[S](view: QueryView[S], q: NtcQuery): UtxoNeeds = q match
    case NtcQuery.IfCurrent(index, inner)
        if index == view.era.index &&
          (view.era == Era.Conway || inEveryEra(inner)) =>
      ledgerNeeds(inner)
    case _ => UtxoNeeds.Nothing

  private def ledgerNeeds(q: LedgerQuery): UtxoNeeds = LedgerQuery.cborLayers(q)._2 match
    case LedgerQuery.GetUTxOWhole           => UtxoNeeds.Whole
    case LedgerQuery.GetUTxOByAddress(some) => UtxoNeeds.Addresses(some)
    case LedgerQuery.GetUTxOByTxIn(ins)     => UtxoNeeds.Inputs(ins)
    case _                                  => UtxoNeeds.Nothing

  /** The era history of the acquired state: a chain that starts in Byron has an open-ended Byron
    * span in the configuration until it has entered Shelley, and the state knows where it did.
    */
  def geometry[S](env: QueryEnv, view: QueryView[S]): EpochGeometry =
    view.read
      .consensus(view.state)
      .byron
      .firstShelleyEpoch
      .fold(env.geometry)(env.geometry.resolved)

  def currentEra[S](view: QueryView[S]): Era = view.era

  /** The result item of the answer, or `None` for a query that has none. */
  def answer[S](
      env: QueryEnv,
      view: QueryView[S],
      utxo: Map[TxIn, TxOut],
      query: NtcQuery,
      version: NtcVersion = NtcVersion.LatestReleased
  ): Option[Bytes] = answerOrWhy(env, view, utxo, query, version).toOption

  /** The result item of the answer, or why there is none. */
  def answerOrWhy[S](
      env: QueryEnv,
      view: QueryView[S],
      utxo: Map[TxIn, TxOut],
      query: NtcQuery,
      version: NtcVersion = NtcVersion.LatestReleased
  ): Either[Unanswered, Bytes] =
    if !QueryVersion.supports(version, query) then Left(Unanswered.Unsupported)
    else answerSupported(env, view, utxo, query, version)

  private def answerSupported[S](
      env: QueryEnv,
      view: QueryView[S],
      utxo: Map[TxIn, TxOut],
      query: NtcQuery,
      version: NtcVersion
  ): Either[Unanswered, Bytes] = query match
    case NtcQuery.SystemStart => Right(CborOut.build(systemStart(_, env.systemStart)))
    case NtcQuery.ChainBlockNo =>
      Right(CborOut.build { w =>
        view.blockNo match
          case Some(n) => w.array(2).uint(1).uint(n)
          case None    => w.array(1).uint(0)
      })
    case NtcQuery.ChainPoint        => Right(CborOut.build(point(_, view.point)))
    case NtcQuery.DebugLedgerConfig => Left(Unanswered.Unsupported)
    case NtcQuery.HardFork(HardForkQuery.GetCurrentEra) =>
      Right(CborOut.build(_.uint(view.era.index)))
    case NtcQuery.HardFork(HardForkQuery.GetInterpreter) =>
      val tipSlot = view.point match
        case Point.Block(slot, _) => slot.value
        case Point.Origin         => BigInt(0)
      val eras = EraHistory.summary(
        geometry(env, view),
        env.byron,
        env.forks,
        view.read.provenance(view.state).observed,
        view.era,
        tipSlot
      )
      Right(CborOut.build(EraHistory.encode(_, eras)))
    case NtcQuery.EraStart(index) =>
      val era = view.era
      val eras = EraHistory.summary(
        geometry(env, view),
        env.byron,
        env.forks,
        view.read.provenance(view.state).observed,
        era,
        BigInt(0)
      )
      Right(CborOut.build { w =>
        eras.lift(index).filter(_ => index <= era.index) match
          case Some(e) =>
            w.array(1).array(3).integer(e.start.timePs).uint(e.start.slot).uint(e.start.epoch)
          case None => w.array(0)
      })
    case NtcQuery.IfCurrent(index, inner) =>
      val era = view.era
      if index != era.index then Right(CborOut.build(mismatch(_, index, era)))
      else if era != Era.Conway && !inEveryEra(inner) then Left(Unanswered.Unsupported)
      else ledgerAnswer(env, view, utxo, inner, version).map(r => CborOut.build(_.array(1).raw(r)))

  /** The queries answered in every Shelley-based era: the ledger state as the Haskell node writes
    * it, and the chain-dependent state (the others are Conway's).
    */
  private def inEveryEra(q: LedgerQuery): Boolean = LedgerQuery.cborLayers(q)._2 match
    case LedgerQuery.DebugEpochState | LedgerQuery.DebugNewEpochState |
        LedgerQuery.DebugChainDepState =>
      true
    case _ => false

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

  /** The bytes of an answer, or why there are none: its value is outside what the Haskell type
    * holds.
    */
  extension [A](codec: Codec[A])
    private def answer(a: A): Either[Unanswered, Bytes] =
      codec.write(a).leftMap(_ => Unanswered.Unwritable)

    private def answerRead(a: Read[A]): Either[Unanswered, Bytes] =
      a.leftMap(Unanswered.Refused(_)).flatMap(answer)

  /** A pool's share of the stake: its ratio of the total, its stake and its VRF key hash. */
  private final case class PoolShare(ratio: Rational, stake: BigInt, vrf: Bytes)

  private val poolShare: Codec[PoolShare] =
    Record.of[PoolShare]((Wire.ratio, coin, Wire.hash(32)))

  private val poolDistribution: Codec[(Map[Bytes, PoolShare], BigInt)] =
    Record.of[(Map[Bytes, PoolShare], BigInt)]((Wire.sortedMapOf(poolId, poolShare), coin))

  /** The retired `PoolDistr`: the map alone and each pool's ratio and VRF hash, with no stake or
    * total (`Query/Types.hs:32-72` at ouroboros-consensus
    * 82ecba329d7d054340bf707d44fe6e9ac27cec40).
    */
  private val legacyPoolDistribution: Codec[Map[Bytes, (Rational, Bytes)]] =
    Wire.sortedMapOf(poolId, Record.of[(Rational, Bytes)]((Wire.ratio, Wire.hash(32))))

  private def legacyDistribution(
      distribution: (Map[Bytes, PoolShare], BigInt)
  ): Either[Unanswered, Bytes] =
    legacyPoolDistribution.answer(distribution._1.map { case (id, share) =>
      id -> (share.ratio, share.vrf)
    })

  private def restrict[K, V](m: Map[K, V], keys: Set[K]): Map[K, V] =
    if keys.isEmpty then m else m.filter { case (k, _) => keys(k) }

  private def restrictAlways[K, V](m: Map[K, V], keys: Set[K]): Map[K, V] =
    m.filter { case (k, _) => keys(k) }

  /** The parameters of a registered pool as the reference reads them back (`mkQueryPoolResult`,
    * `Api/State/Query.hs:485-525`): the reward account on the node's network.
    */
  private def registered(network: Int, id: Bytes, p: mirror.StakePoolState): PoolParams =
    PoolParams(
      id,
      p.vrf.bytes,
      p.pledge.value,
      p.cost.value,
      p.margin,
      RewardAccount(network, EraRead.credential(p.accountId)),
      p.owners.iterator.map(_.bytes).toSet,
      p.relays,
      p.metadata
    )

  private def ledgerAnswer[S](
      env: QueryEnv,
      view: QueryView[S],
      utxo: Map[TxIn, TxOut],
      q: LedgerQuery,
      version: NtcVersion
  ): Either[Unanswered, Bytes] =
    val (depth, leaf) = LedgerQuery.cborLayers(q)
    // GetCBOR intentionally assumes the latest result encoding, independent of the connection
    // (`answerPureBlockQuery`, Shelley/Ledger/Query.hs:427-433 at the pinned consensus).
    val encodingVersion = if depth == 0 then version else NtcVersion.LatestReleased
    ledgerLeafAnswer(env, view, utxo, leaf, encodingVersion).map { answer =>
      // Build inside out, then emit outside in once: repeatedly copying the encoded inner
      // result would make a legal deeply nested query quadratic in time and allocation.
      val (_, headers) = (0 until depth).foldLeft((answer.size, List.empty[Bytes])) {
        case ((size, done), _) =>
          val header = CborOut.build(_.tag(24).head(2, size))
          (size + header.size, header :: done)
      }
      CborOut.build { w =>
        headers.foreach(w.raw)
        w.raw(answer)
      }
    }

  private def ledgerLeafAnswer[S](
      env: QueryEnv,
      view: QueryView[S],
      utxo: Map[TxIn, TxOut],
      q: LedgerQuery,
      version: NtcVersion
  ): Either[Unanswered, Bytes] =
    import LedgerQuery.*
    def conway[A](f: ConwayFacts => Either[Unanswered, A]): Either[Unanswered, A] =
      view.conway.leftMap(Unanswered.Refused(_)).flatMap {
        case Some(facts) => f(facts)
        case None        => Left(Unanswered.Unsupported)
      }
    q match
      case GetLedgerTip => Right(CborOut.build(point(_, view.point)))
      case GetEpochNo   => conway(f => coin.answer(f.epoch))
      // The reference's LegacyPParams Conway instance delegates to the current codec, so unlike
      // the Shelley genesis parameters these bytes do not change at NTC 21.
      case GetCurrentPParams         => conway(f => protocolParams.answer(f.l.params))
      case GetProposedPParamsUpdates => Right(CborOut.build(_.map(0)))
      case GetGenesisConfig =>
        GenesisConfig.encode(env.shelleyGenesis, version).leftMap(_ => Unanswered.Unwritable)
      case DebugChainDepState => chainDepState(view)
      case DebugEpochState    => MirrorAnswers.epochState(view)
      case DebugNewEpochState => MirrorAnswers.newEpochState(view)
      case GetUTxOWhole       => UtxoCodecs.utxo.answer(utxo)
      case GetUTxOByAddress(addresses) =>
        UtxoCodecs.utxo.answer(utxo.filter { case (_, o) => addresses(o.address.raw) })
      case GetUTxOByTxIn(inputs) =>
        UtxoCodecs.utxo.answer(utxo.filter { case (i, _) => inputs(i) })
      case GetFilteredDelegationsAndRewardAccounts(credentials) =>
        conway { f =>
          delegationsAndRewards.answerRead(f.accounts.map { all =>
            val accounts = restrictAlways(all, credentials)
            (
              accounts.collect { case (c, a) if a.pool.isDefined => c -> a.pool.get.bytes },
              accounts.map { case (c, a) => c -> a.balance.toBigInt }
            )
          })
        }
      case GetRewardInfoPools => conway(f => RewardQueries.poolInfo(env, f))
      case GetNonMyopicMemberRewards(credentials) =>
        conway(f => RewardQueries.queryMembers(env, f, credentials))
      case GetStakePools =>
        conway(f => Wire.definiteSetOf(poolId).answerRead(f.pools.map(_.keySet)))
      // a registered pool's reward account on the node's network, its future parameters' as they
      // are (`queryPoolParameters`, `mkQueryPoolStateResult`, `Api/State/Query.hs:485-525`)
      case GetStakePoolParams(ids) =>
        conway { f =>
          pools.answerRead(
            f.pools.map(all =>
              restrictAlways(all, ids).map { case (id, p) =>
                id -> registered(env.mirror.network, id, p)
              }
            )
          )
        }
      case GetPoolState(ids) =>
        conway { f =>
          def pick[V](m: Map[Bytes, V]): Map[Bytes, V] = ids.fold(m)(restrictAlways(m, _))
          poolState.answerRead((f.pools, f.cert).mapN { (all, c) =>
            val state = c.pState
            (
              pick(all).map { case (id, p) => id -> registered(env.mirror.network, id, p) },
              pick(state.future.iterator.map((id, p) => id.bytes -> p).toMap),
              pick(state.retiring.iterator.map((id, e) => id.bytes -> e.toBigInt).toMap),
              pick(all).map { case (id, p) => id -> p.deposit.toBigInt }
            )
          })
        }
      case GetStakeSnapshots(ids) =>
        conway(f =>
          stakeSnapshots.answerRead(
            f.snapshots.map(stakeSnapshotsOf(f.l.params, _, ids))
          )
        )
      case GetStakeDelegDeposits(credentials) =>
        conway { f =>
          Wire
            .sortedMapOf(nodeCredential, coin)
            .answerRead(
              f.accounts.map(all =>
                restrict(all, credentials).map { case (c, a) => c -> a.deposit.toBigInt }
              )
            )
        }
      case GetConstitution => conway(f => constitution.answerRead(f.gov.map(_.constitution)))
      case GetGovState =>
        conway(f => govState.answerRead(f.gov.map(g => govStateOf(g))))
      case GetDRepState(credentials) =>
        conway { f =>
          Wire
            .sortedMapOf(nodeCredential, nodeDRepState)
            .answerRead(f.drepStates.map(restrict(_, credentials)))
        }
      case GetDRepStakeDistr(dreps) =>
        conway { f =>
          Wire
            .sortedMapOf(nodeDRep, coin)
            .answerRead(f.gov.map(g => restrict(g.drepPulsing.snapshot.drepDistr, dreps)))
        }
      case GetCommitteeMembersState(cold, hot, statuses) =>
        conway { f =>
          committeeMembers.answerRead((f.gov, f.committeeState).mapN { (g, state) =>
            committeeMembersState(f.epoch, g, state, cold, hot, statuses)
          })
        }
      case GetFilteredVoteDelegatees(credentials) =>
        conway { f =>
          Wire
            .sortedMapOf(nodeCredential, nodeDRep)
            .answerRead(
              f.accounts.map(all =>
                restrict(all, credentials).collect {
                  case (c, a) if a.drep.isDefined => c -> EraViews.drep(a.drep.get)
                }
              )
            )
        }
      case GetAccountState =>
        conway { f =>
          Record
            .of[(BigInt, BigInt)]((coin, coin))
            .answerRead(
              f.scalars.map(s => (s.chainAccount.treasury.value, s.chainAccount.reserves.value))
            )
        }
      case GetSPOStakeDistr(ids) =>
        conway { f =>
          Wire
            .sortedMapOf(poolId, coin)
            .answerRead(f.gov.map(g => restrict(g.drepPulsing.snapshot.poolDistr, ids)))
        }
      case GetProposals(ids) =>
        conway { f =>
          Wire
            .seqOf(govActionState)
            .answerRead(f.gov.map { g =>
              val all = g.drepPulsing.snapshot.proposals
              if ids.isEmpty then all else all.filter(a => ids(a.id))
            })
        }
      case GetRatifyState =>
        conway(f => ratifyState.answerRead(f.gov.map(_.drepPulsing.ratify)))
      case GetFuturePParams =>
        conway(f =>
          Wire.strictMaybe(protocolParams).answerRead(f.gov.map(_.futurePParams.nextParams))
        )
      case QueryStakePoolDefaultVote(pool) =>
        conway(f =>
          Codec.intRange(0, 2).answerRead((f.pools, f.accounts).mapN(defaultVote(_, _, pool)))
        )
      case GetPoolDistr(ids) =>
        conway(f => legacyDistribution(poolDistr(f.l.leaderPools(nextEpoch = false), ids, None)))
      case GetStakeDistribution =>
        conway { f =>
          (f.currentStake, f.scalars).tupled.leftMap(Unanswered.Refused(_)).flatMap {
            (current, scalars) =>
              legacyDistribution(
                poolDistr(
                  leaders(current),
                  None,
                  Some(env.maxSupply - scalars.chainAccount.reserves.value)
                )
              )
          }
        }
      case GetPoolDistr2(ids) =>
        conway { f =>
          poolDistribution.answer(poolDistr(f.l.leaderPools(nextEpoch = false), ids, None))
        }
      case GetStakeDistribution2 =>
        // the current stake (not a snapshot), as a fraction of the lovelace in circulation
        conway { f =>
          (f.currentStake, f.scalars).tupled.leftMap(Unanswered.Refused(_)).flatMap {
            (current, scalars) =>
              poolDistribution.answer(
                poolDistr(
                  leaders(current),
                  None,
                  Some(env.maxSupply - scalars.chainAccount.reserves.value)
                )
              )
          }
        }
      case GetLedgerPeerSnapshotLegacy =>
        conway(f => PeerSnapshotQueries.answer(env, f, view.point, None, version.number >= 22))
      case GetLedgerPeerSnapshot(kind) =>
        conway(f => PeerSnapshotQueries.answer(env, f, view.point, Some(kind), true))
      case GetMaxMajorProtocolVersion =>
        coin.answer(BigInt(env.forks.maxMajorProtVer))
      case GetDRepDelegations(dreps) =>
        conway { f =>
          Wire
            .sortedMapOf(nodeDRep, Wire.sortedSetOf(nodeCredential))
            .answerRead((f.cert, f.accounts).mapN((c, all) => drepDelegations(c, all, dreps)))
        }
      case _ => Left(Unanswered.Unsupported)

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

  /** The chain-dependent state of the protocol the chain runs, as the reference serialises its
    * `ChainDepState` (`DebugChainDepState`): TPraos's from Shelley to Alonzo, Praos's from Babbage.
    */
  private def chainDepState[S](view: QueryView[S]): Either[Unanswered, Bytes] =
    view.read.protocol(view.state).dep match
      case ChainDepState.TPraos(t) => HaskellStateCodecs.tpraosState.answer(t)
      case ChainDepState.Praos(p)  => HaskellStateCodecs.praosState.answer(p)
      case ChainDepState.PBft(_)   => Left(Unanswered.Unsupported)

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

  private def govStateOf(g: ConwayGovState): GovAnswer =
    (
      g.proposals,
      g.committee,
      g.constitution,
      g.curPParams,
      g.prevPParams,
      g.futurePParams,
      (g.drepPulsing.snapshot, g.drepPulsing.ratify)
    )

  /** The default vote of a pool: DefaultNo (0), DefaultAbstain (1) or DefaultNoConfidence (2), by
    * the DRep the pool's reward account delegates to.
    */
  private def defaultVote(
      pools: Map[Bytes, mirror.StakePoolState],
      accounts: Map[Credential, mirror.ConwayAccountState],
      pool: Bytes
  ): Int =
    pools.get(pool).flatMap { p =>
      accounts.get(EraRead.credential(p.accountId)).flatMap(_.drep).map(EraViews.drep)
    } match
      case Some(DRep.AlwaysAbstain)      => 1
      case Some(DRep.AlwaysNoConfidence) => 2
      case _                             => 0

  /** `queryDRepDelegations`: for a non-empty set of registered DReps their reverse delegation sets;
    * otherwise (an empty set, or a set naming always-abstain or always-no-confidence) the accounts
    * grouped by the DRep they delegate to, restricted to the set when it is not empty.
    */
  private def drepDelegations(
      c: ConwayCertState,
      accounts: Map[Credential, mirror.ConwayAccountState],
      dreps: Set[DRep]
  ): Map[DRep, Set[Credential]] =
    val registered = dreps.collect { case DRep.Registered(cred) => cred }
    if dreps.nonEmpty && registered.size == dreps.size then
      c.vState.dreps.iterator.collect {
        case (cred, d) if registered(EraRead.credential(cred)) =>
          DRep.Registered(EraRead.credential(cred)) -> d.delegators.iterator
            .map(EraRead.credential)
            .toSet
      }.toMap
    else
      accounts.toVector
        .collect { case (cred, a) if a.drep.isDefined => EraViews.drep(a.drep.get) -> cred }
        .filter((d, _) => dreps.isEmpty || dreps(d))
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toSet)
        .toMap

  /** The stake and VRF key hash of the pools of a snapshot that have a delegator (the pool
    * distribution it makes, `calculatePoolDistr`).
    */
  private def leaders(s: mirror.SnapShot): PoolDistribution =
    val live = s.pools.iterator.filter((_, p) => !p.numDelegators.isZero).toVector
    PoolDistribution(
      live.map((id, p) => id.bytes -> p.stake.toBigInt).toMap,
      live.map((id, p) => id.bytes -> p.vrf.bytes).toMap
    )

  /** `PoolDistr`: each pool's stake as a fraction of `denominator` (the distribution's active stake
    * unless a circulating supply is given), its stake and its VRF key hash, then the active stake.
    */
  private def poolDistr(
      dist: PoolDistribution,
      ids: Option[Set[Bytes]],
      circulation: Option[BigInt]
  ): (Map[Bytes, PoolShare], BigInt) =
    val total = dist.total
    val denominator = circulation.getOrElse(total)
    val shares = dist.stake.collect {
      case (id, stake) if ids.forall(_(id)) =>
        val ratio = if denominator == 0 then Rational.zero else Rational(stake, denominator)
        id -> PoolShare(ratio, stake, dist.vrf.getOrElse(id, Bytes.empty))
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

  private def stakeSnapshotsOf(
      params: ProtocolParams,
      snapshots: mirror.SnapShots,
      ids: Option[Set[Bytes]]
  ): StakeSnapshotsAnswer =
    val snaps = Vector(snapshots.mark, snapshots.set, snapshots.go)
    // each pool's stake in each snapshot, by pool id
    val stakes: Vector[Map[Bytes, BigInt]] =
      snaps.map(_.pools.iterator.map((id, s) => id.bytes -> s.stake.toBigInt).toMap)
    def stakeOf(i: Int, p: Bytes): BigInt = stakes(i).getOrElse(p, BigInt(0))
    // Without a list of pools: before protocol version 11 the pools that have delegators in any
    // snapshot, from 11 those with stake.
    val chosen: Set[Bytes] = ids.getOrElse {
      if params.protocolMajor < 11 then
        snaps
          .flatMap { sn =>
            val known = sn.pools.keySet
            sn.activeStake.entries.valuesIterator.map(_.delegation).filter(known.contains)
          }
          .map(_.bytes)
          .toSet
      else
        snaps
          .flatMap(_.pools.iterator.collect { case (p, s) if s.stake.toBigInt > 0 => p.bytes })
          .toSet
    }
    val rows = chosen.toVector
      .map(p => p -> snaps.indices.map(stakeOf(_, p)))
      .filter { case (_, st) => params.protocolMajor < 11 || st.exists(_ > 0) }
      .map { case (p, st) => p -> (st(0), st(1), st(2)) }
    // the totals are `NonZero`: an empty snapshot counts as 1
    (
      rows.toMap,
      activeStake(snaps(0)).max(1),
      activeStake(snaps(1)).max(1),
      activeStake(snaps(2)).max(1)
    )

  /** The stake of the pools of a snapshot, summed. */
  private def activeStake(s: mirror.SnapShot): BigInt =
    s.pools.valuesIterator.foldLeft(BigInt(0))(_ + _.stake.toBigInt)

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
      epoch: BigInt,
      gov: ConwayGovState,
      stateMembers: Map[Credential, CommitteeAuth],
      cold: Set[Credential],
      hot: Set[Credential],
      statuses: Set[Int]
  ): CommitteeAnswer =
    val members: Map[Credential, BigInt] =
      gov.committee.fold(Map.empty[Credential, BigInt])(_.members)
    val next: Map[Credential, BigInt] =
      gov.drepPulsing.ratify.enact.committee.fold(Map.empty[Credential, BigInt])(_.members)
    def filterCold(c: Set[Credential]) = if cold.isEmpty then c else c.intersect(cold)
    val relevantCold: Set[Credential] =
      if statuses.isEmpty || statuses.contains(MemberStatus.Unrecognized.code) then
        filterCold(members.keySet ++ stateMembers.keySet ++ next.keySet)
      else filterCold(members.keySet)
    val relevantHot: Set[Credential] =
      stateMembers.collect { case (c, CommitteeAuth.Hot(h)) if hot(h) => c }.toSet
    val relevant = if hot.isEmpty then relevantCold else relevantCold.intersect(relevantHot)
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
    (rows.toMap, gov.committee.map(_.threshold), epoch)
