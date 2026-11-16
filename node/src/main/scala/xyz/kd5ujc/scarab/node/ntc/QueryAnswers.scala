// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.data.NonEmptyVector
import cats.syntax.all.*
import scala.annotation.unused
import java.time.{Instant, ZoneOffset}
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.protocol.{Era, NtcVersion}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.consensus.{BlockRef, HardForkConfig}
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, ConsensusMirrorEnv, Placement}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.rules.{EraLedger, StakeDistribution}
import xyz.kd5ujc.scarab.ledger.state as mirror
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  ConwayCertState,
  ConwayGovState,
  EraTag,
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
  * configuration and the era transitions this node has seen. `ledgerConfig` is the answer to
  * `DebugLedgerConfig` ([[LedgerConfig]]), written once from the configuration, or why it has none.
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
    networkMagic: Long,
    ledgerConfig: Either[Unanswered, Bytes]
):
  /** What the mirror of the ledger state (`DebugEpochState`, `DebugNewEpochState`) reads. */
  def consensusMirror: ConsensusMirrorEnv = ConsensusMirrorEnv(mirror, forks, byron, observedEras)

/** Why a query has no answer: the closed set of the reasons, where the connection then ends (as a
  * decode failure ends it on the reference).
  */
enum Unanswered extends DomainError:
  /** The node does not answer this query (in this era). */
  case Unsupported

  /** The state cannot be read in the vocabulary of the mirror (a value outside the range of its
    * Haskell type).
    */
  case Refused(error: MirrorError)

  /** The answer holds a value outside the range of the Haskell type it is written as. */
  case Unwritable

  /** Stored pool relays could not be decoded; retain every affected pool and typed cause. */
  case StoredRelays(pools: NonEmptyVector[xyz.kd5ujc.scarab.node.SkippedPool])

  /** The ledger configuration (`DebugLedgerConfig`) could not be written from the configuration. */
  case UnwritableLedgerConfig(cause: LedgerConfig.Error)

  /** The genesis configuration (`GetGenesisConfig`) could not be written from the Shelley genesis.
    */
  case UnwritableGenesisConfig(cause: GenesisConfig.Error)

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

  /** What the queries read of the ledger state of the Shelley-based era the chain is in, if it is
    * in one.
    */
  lazy val facts: Read[Option[EraFacts[?]]] = ledger.map(_.map(EraFacts.of))

  /** What the Conway queries read, if the chain is in Conway. */
  lazy val conway: Read[Option[ConwayFacts]] = facts.map(_.flatMap(_.conway))

/** What the queries of a Shelley-based era `E` read of its ledger state, in the vocabulary of the
  * mirror; each part is read the first time and kept. These are the parts every era has; those only
  * Conway's queries read are [[ConwayFacts]]'.
  */
sealed class EraFacts[E <: EraTag](val l: EraRead[E]):
  lazy val scalars: Read[mirror.LedgerScalars] = l.scalars

  def epoch: BigInt = l.epoch.toBigInt

  /** `currentSnapshot` (`Shelley/API/Wallet.hs:261-268`, cardano-ledger f649f975): the stake of the
    * current ledger state as a snapshot, made as SNAP makes the mark (`snapShotFromInstantStake`),
    * with the stake of the pointer addresses before Conway. The wallet queries and
    * `GetStakeDistribution2` read it.
    */
  lazy val currentStake: Read[mirror.SnapShot] = l.nes.map { nes =>
    given ShelleyBasedEra[E] = l.era
    val ls = nes.es.ls
    StakeDistribution.snapshot(EraLedger.activeStake(ls), EraLedger.pState(ls).stakePools)
  }

  /** The same view, as Conway's, if the era is Conway. */
  def conway: Option[ConwayFacts] = None

object EraFacts:
  /** The view of a ledger state, in the era its witness names: Conway's own in Conway, and in every
    * other era, Dijkstra too, the parts every era has (a Dijkstra state is not Conway's; which of
    * the queries of every era it answers, the answers say).
    */
  def of(in: InEra): EraFacts[?] = in match
    case InEra.Of(era, l) =>
      era match
        case ShelleyBasedEra.SConway => new ConwayFacts(l)
        case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
            ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage | ShelleyBasedEra.SDijkstra =>
          new EraFacts(l)

/** What the queries of the Conway era read of its ledger state, besides what every era's do. */
final class ConwayFacts(conwayRead: EraRead[Conway]) extends EraFacts[Conway](conwayRead):
  override def conway: Option[ConwayFacts] = Some(this)

  lazy val cert: Read[ConwayCertState] = l.cert
  lazy val gov: Read[ConwayGovState] = l.gov
  lazy val snapshots: Read[mirror.SnapShots] = l.snapshots

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

/** Pure answers to node-to-client queries. `answer` returns the result item of the `MsgResult`
  * (`None` for a query this node does not answer; the connection then ends, as a decode failure
  * ends it on the reference). The UTxO queries take the entries they need as `utxo` (the caller
  * resolves them against the backing store first, see [[UtxoNeeds]]).
  *
  * The Conway era is answered, and in every Shelley-based era the ledger state, the chain-dependent
  * state, the UTxO, the reward provenance, the queries that read nothing of the era's own, those of
  * the governance state and, up to Conway, those of the certificate state, the distributions, the
  * wallet's queries and the ledger peers: their `IfCurrent` queries get `[result]`, queries naming
  * another era get the era-mismatch reply, as the reference sends. The ledger state is read through
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
        if index == QueryEra.of(view.era) &&
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

  /** The result item of the answer, or why there is none. No Shelley block query is refused for the
    * negotiated version, as the reference's server refuses none; the version selects the encoding
    * of some answers only (`encodeShelleyResult`, `Shelley/Ledger/Query.hs:1000-1043`,
    * ouroboros-consensus 82ecba32).
    */
  def answerOrWhy[S](
      env: QueryEnv,
      view: QueryView[S],
      utxo: Map[TxIn, TxOut],
      query: NtcQuery,
      version: NtcVersion = NtcVersion.LatestReleased
  ): Either[Unanswered, Bytes] = query match
    case NtcQuery.SystemStart => Right(CborOut.build(systemStart(_, env.systemStart)))
    case NtcQuery.ChainBlockNo =>
      Right(CborOut.build { w =>
        view.blockNo match
          case Some(n) => w.array(2).uint(1).uint(n)
          case None    => w.array(1).uint(0)
      })
    case NtcQuery.ChainPoint        => Right(CborOut.build(point(_, view.point)))
    case NtcQuery.DebugLedgerConfig => env.ledgerConfig
    case NtcQuery.HardFork(HardForkQuery.GetCurrentEra) =>
      Right(CborOut.build(_.uint(view.era.index)))
    case NtcQuery.HardFork(HardForkQuery.GetInterpreter) =>
      // `mkInterpreter (hardForkSummary cfg st)`: the eras the state has passed and the end its
      // tip tells of its own (`interpretQueryHardFork`, `HardFork/Combinator/Ledger/Query.hs:529`)
      val placement = Placement(geometry(env, view), env.byron, env.forks)
      val eras = EraHistory.eras(view.read.summary(view.state)(placement))
      Right(CborOut.build(EraHistory.encode(_, eras)))
    case NtcQuery.EraStart(index) =>
      // the start of an era the state has entered, from the same history as `GetInterpreter`
      val era = view.era
      val placement = Placement(geometry(env, view), env.byron, env.forks)
      val eras = EraHistory.eras(view.read.summary(view.state)(placement))
      Right(CborOut.build { w =>
        eras.lift(index.index).filter(_ => index.index <= era.index) match
          case Some(e) =>
            w.array(1).array(3).integer(e.start.timePs).uint(e.start.slot).uint(e.start.epoch)
          case None => w.array(0)
      })
    case NtcQuery.ByronUpdateInterfaceState =>
      // Byron's update-interface state while the ledger is in Byron (`answerPureBlockQuery`,
      // `Ouroboros/Consensus/Byron/Ledger/Ledger.hs:243-246`, ouroboros-consensus 82ecba32), the
      // era mismatch in any later era, as the reference sends. A Byron state without its ledger
      // has no update state to answer with.
      if view.era != Era.Byron then Right(CborOut.build(mismatch(_, QueryEra.Byron, view.era)))
      else
        view.read
          .consensus(view.state)
          .byron
          .ledger
          .toRight(Unanswered.Unsupported)
          .flatMap(ledger => ByronUpdateInterface.encode(ledger.update))
          .map(r => CborOut.build(_.array(1).raw(r)))
    case NtcQuery.IfCurrent(index, inner) =>
      val era = view.era
      if index != QueryEra.of(era) then Right(CborOut.build(mismatch(_, index, era)))
      else if era != Era.Conway && !inEveryEra(inner) then Left(Unanswered.Unsupported)
      else ledgerAnswer(env, view, utxo, inner, version).map(r => CborOut.build(_.array(1).raw(r)))

  /** The queries answered in every Shelley-based era (the others are Conway's): the ledger state as
    * the Haskell node writes it, the chain-dependent state, the UTxO, the reward provenance, the
    * queries that read nothing of the era's own, the ledger tip, the epoch, the proposed parameter
    * updates, the genesis configuration, the chain account state and the maximum major protocol
    * version, those of the governance state, the current parameters and the governance state, and
    * those of the certificate state, the delegations and reward accounts, the pools, their
    * parameters, state and stake snapshots and the delegation deposits, and the distributions, the
    * wallet's queries and the ledger peers: the pool distributions, the stake distributions, the
    * reward information of the pools, the non-myopic member rewards and the ledger peer snapshots.
    * The reference answers each of them in every Shelley-based era, with the same
    * `answerPureBlockQuery` (`Ouroboros/Consensus/Shelley/Ledger/Query.hs:406-535`,
    * ouroboros-consensus 82ecba32), at every node-to-client version.
    */
  private def inEveryEra(q: LedgerQuery): Boolean = LedgerQuery.cborLayers(q)._2 match
    case LedgerQuery.DebugEpochState | LedgerQuery.DebugNewEpochState |
        LedgerQuery.DebugChainDepState | LedgerQuery.GetUTxOWhole |
        LedgerQuery.GetUTxOByAddress(_) | LedgerQuery.GetUTxOByTxIn(_) |
        LedgerQuery.GetRewardProvenance | LedgerQuery.GetLedgerTip | LedgerQuery.GetEpochNo |
        LedgerQuery.GetProposedPParamsUpdates | LedgerQuery.GetGenesisConfig |
        LedgerQuery.GetAccountState | LedgerQuery.GetMaxMajorProtocolVersion |
        LedgerQuery.GetCurrentPParams | LedgerQuery.GetGovState |
        LedgerQuery.GetFilteredDelegationsAndRewardAccounts(_) | LedgerQuery.GetStakePools |
        LedgerQuery.GetStakePoolParams(_) | LedgerQuery.GetPoolState(_) |
        LedgerQuery.GetStakeSnapshots(_) | LedgerQuery.GetStakeDelegDeposits(_) |
        LedgerQuery.GetNonMyopicMemberRewards(_) | LedgerQuery.GetRewardInfoPools |
        LedgerQuery.GetStakeDistribution | LedgerQuery.GetPoolDistr(_) |
        LedgerQuery.GetPoolDistr2(_) | LedgerQuery.GetStakeDistribution2 |
        LedgerQuery.GetLedgerPeerSnapshotLegacy | LedgerQuery.GetLedgerPeerSnapshot(_) =>
      true
    case _ => false

  private def mismatch(w: CborOut, queried: QueryEra, ledger: Era): CborOut =
    w.array(2)
      .array(2)
      .uint(queried.index)
      .text(queried.name)
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

  /** The version the answers of Conway's queries are written at: the lowest of the Conway era,
    * which is how the reference encodes them (`Versions.eraLow`): rationals under tag 30, sets
    * under tag 258. The pool distributions take their own era's (`throughConway`).
    */
  private given ProtVer = Versions.eraLow(ShelleyBasedEra.SConway)

  import HaskellStateCodecs.{
    anchor,
    constitution,
    govActionState,
    nodeCredential,
    nodeDRep,
    nodeDRepState,
    ratifyState
  }
  import xyz.kd5ujc.scarab.storage.haskell.state.NodeOrders.given

  // the codecs of the answers: the node's own types, written in the ledger's orders

  private val coin = Wire.word(64)
  private val poolId = Wire.hash(28)
  private val protocolParams = Params.conwayCodec

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

  /** A pool distribution as the answers write it: each pool's share, then the total active stake.
    */
  private type Shares = (Map[Bytes, PoolShare], BigInt)

  /** `PoolDistr` written at `ProtVer` (`toEraCBOR @era`, `State/PoolDistr.hs:70-77, 117-122`,
    * cardano-ledger f649f975): the ratio is a plain `Rational`, under tag 30 from version 9 only
    * (`encodeRatio`, `Binary/Encoding/Encoder.hs:301-306`), so untagged before Conway.
    */
  private def poolDistribution(using ProtVer): Codec[Shares] =
    Record.of[Shares](
      (Wire.sortedMapOf(poolId, Record.of[PoolShare]((Wire.ratio, coin, Wire.hash(32)))), coin)
    )

  /** The retired `PoolDistr`: the map alone and each pool's ratio and VRF hash, with no stake or
    * total (`Query/Types.hs:32-72` at ouroboros-consensus
    * 82ecba329d7d054340bf707d44fe6e9ac27cec40), written at `ProtVer` as the current one is.
    */
  private def legacyPoolDistribution(using ProtVer): Codec[Map[Bytes, (Rational, Bytes)]] =
    Wire.sortedMapOf(poolId, Record.of[(Rational, Bytes)]((Wire.ratio, Wire.hash(32))))

  /** `fromLedgerPoolDistr` (`Query/Types.hs:69-73`, ouroboros-consensus 82ecba32): each pool's
    * ratio and VRF hash.
    */
  private def legacyDistribution(distribution: Shares)(using ProtVer): Either[Unanswered, Bytes] =
    legacyPoolDistribution.answer(distribution._1.map { case (id, share) =>
      id -> (share.ratio, share.vrf)
    })

  /** The shares of a `PoolDistr` of the mirror, as it holds them. */
  private def shares(pd: mirror.PoolDistr): Shares =
    (
      pd.distribution.iterator.map { (id, p) =>
        id.bytes -> PoolShare(p.stake, p.totalPoolStake.toBigInt, p.vrf.bytes)
      }.toMap,
      pd.totalActiveStake.value.value
    )

  /** `querySetSnapshotStakePoolDistr` (`Api/State/Query.hs:743-751`, cardano-ledger f649f975) as
    * `GetPoolDistr2` and `GetPoolDistr` ask it, `maybe Set.empty id`
    * (`Shelley/Ledger/Query.hs:450-451, 511-512`, ouroboros-consensus 82ecba32): `nesPd`,
    * restricted to the pools named, every pool when none are, whether the set is absent or empty
    * (#933); the total stays the whole distribution's.
    */
  private def setSnapshotDistr(pd: mirror.PoolDistr, ids: Option[Set[Bytes]]): Shares =
    val (pools, total) = shares(pd)
    (restrict(pools, ids.getOrElse(Set.empty)), total)

  /** `poolsByTotalStakeFraction` (`Shelley/API/Wallet.hs:185-202`, cardano-ledger f649f975): the
    * pool distribution of the current stake (`calculatePoolDistr`), each pool's ratio scaled by the
    * active stake's share of the lovelace in circulation (`%?`: zero when none circulates); the
    * total stays the active stake.
    */
  private def byTotalStakeFraction(current: mirror.SnapShot, circulation: BigInt): Shares =
    val pd = mirror.PoolDistr.of(current)
    val active = pd.totalActiveStake.value.value
    val scale = if circulation == 0 then Rational.zero else Rational(active, circulation)
    val (pools, total) = shares(pd)
    (pools.map((id, s) => id -> s.copy(ratio = s.ratio * scale)), total)

  private def restrict[K, V](m: Map[K, V], keys: Set[K]): Map[K, V] =
    if keys.isEmpty then m else m.filter { case (k, _) => keys(k) }

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
    // the outputs as the era's `TxOut` writes them (`UtxoCodecs.utxoIn`)
    def utxoAnswer(entries: Map[TxIn, TxOut]): Either[Unanswered, Bytes] =
      Versions.shelleyBased(view.era) match
        case Some(era) => UtxoCodecs.utxoIn(era).answer(entries)
        case None      => Left(Unanswered.Unsupported)
    def shelleyBased[A](f: EraFacts[?] => Either[Unanswered, A]): Either[Unanswered, A] =
      view.facts.leftMap(Unanswered.Refused(_)).flatMap {
        case Some(facts) => f(facts)
        case None        => Left(Unanswered.Unsupported)
      }
    def conway[A](f: ConwayFacts => Either[Unanswered, A]): Either[Unanswered, A] =
      view.conway.leftMap(Unanswered.Refused(_)).flatMap {
        case Some(facts) => f(facts)
        case None        => Left(Unanswered.Unsupported)
      }
    // The view of an era from Shelley to Conway and the era's lowest version, which its `toEraCBOR`
    // writes at; a Dijkstra state answers none of the queries that ask it, as before.
    def throughConway[A](f: (EraFacts[?], ProtVer) => Either[Unanswered, A]) =
      shelleyBased { facts =>
        facts.l.era match
          case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
              ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway =>
            f(facts, Versions.eraLow(facts.l.era))
          case ShelleyBasedEra.SDijkstra => Left(Unanswered.Unsupported)
      }
    q match
      // Six queries read nothing of the era's own, and are answered and written alike in every
      // Shelley-based era (`answerPureBlockQuery` and `encodeShelleyResult`, Query.hs:406-535 and
      // 1007-1047, ouroboros-consensus 82ecba32). The first is the ledger's tip, `encodePoint
      // encode` (Query.hs:408-409, 1008).
      case GetLedgerTip => Right(CborOut.build(point(_, view.point)))
      // `nesEL`, the epoch (Query.hs:410-411, 1009, ouroboros-consensus 82ecba32).
      case GetEpochNo => shelleyBased(f => coin.answer(f.epoch))
      // The current parameters of the era's governance state, in the era's encoding, the legacy
      // one below NTC 21 before Conway (Query.hs:415-416, 1011, 1098-1111, ouroboros-consensus
      // 82ecba32; `GovStateAnswers`).
      case GetCurrentPParams => shelleyBased(f => GovStateAnswers.currentPParams(f.l, version))
      // `ProposedPPUpdates Map.empty` whatever the state, the proposals of the eras before Conway
      // too (Query.hs:417-418, 1012, ouroboros-consensus 82ecba32).
      case GetProposedPParamsUpdates => Right(CborOut.build(_.map(0)))
      // The compact Shelley genesis of the era's ledger configuration (Query.hs:432-433), the same
      // genesis in every era: each era's configuration takes it from its transition configuration
      // (`mkPartialLedgerConfigShelley`, `Cardano/Node.hs:1105-1120`), and those are one chain of
      // previous-era configurations (633-639, ouroboros-consensus 82ecba32). It is written at
      // Shelley's protocol version whatever the era (`shelleyProtVer`,
      // `Query/LegacyShelleyGenesis.hs:39, 111`; `Shelley/Genesis.hs:595` at
      // cardano-ledger f649f975); the version selects the record (Query.hs:1019, 1113-1125).
      case GetGenesisConfig =>
        GenesisConfig
          .encode(env.shelleyGenesis, version)
          .leftMap(Unanswered.UnwritableGenesisConfig(_))
      case DebugChainDepState => chainDepState(view)
      // `snd $ getRewardProvenance globals st` (`Shelley/Ledger/Query.hs:438-439`, ouroboros-consensus
      // 82ecba32), which is `def` whatever the state (`Shelley/API/Wallet.hs:373-384`, cardano-ledger
      // f649f975), written at the era's lowest version (`toEraCBOR`, `Query.hs:1022`)
      case GetRewardProvenance =>
        Versions.shelleyBased(view.era) match
          case Some(era) =>
            RewardProvenance.codec(using Versions.eraLow(era)).answer(RewardProvenance.default)
          case None => Left(Unanswered.Unsupported)
      case DebugEpochState    => MirrorAnswers.epochState(view)
      case DebugNewEpochState => MirrorAnswers.newEpochState(view)
      case GetUTxOWhole       => utxoAnswer(utxo)
      case GetUTxOByAddress(addresses) =>
        utxoAnswer(utxo.filter { case (_, o) => addresses(o.address.raw) })
      case GetUTxOByTxIn(inputs) =>
        utxoAnswer(utxo.filter { case (i, _) => inputs(i) })
      // `getRewardInfoPools` and `getNonMyopicMemberRewards` of the current stake (Query.hs:412-414,
      // 444-445, ouroboros-consensus 82ecba32; `Shelley/API/Wallet.hs:217-259, 327-366`,
      // cardano-ledger f649f975), written alike in every era: the first's `a0` and margins are
      // bounded ratios, under tag 30 at every version (`BaseTypes.hs:380-404`, Query.hs:1026), the
      // second has no ratio (`toCBOR`, Query.hs:1010).
      case GetRewardInfoPools => throughConway((f, _) => RewardQueries.poolInfo(env, f))
      case GetNonMyopicMemberRewards(credentials) =>
        throughConway((f, _) => RewardQueries.queryMembers(env, f, credentials))
      // The queries of the certificate state, answered alike in every Shelley-based era
      // (Query.hs:430-431, 440-453, ouroboros-consensus 82ecba32; `CertStateAnswers`).
      case GetFilteredDelegationsAndRewardAccounts(credentials) =>
        shelleyBased(f => CertStateAnswers.delegationsAndRewards(f.l, credentials))
      case GetStakePools => shelleyBased(f => CertStateAnswers.stakePools(f.l))
      case GetStakePoolParams(ids) =>
        shelleyBased(f => CertStateAnswers.stakePoolParams(f.l, env.mirror.network, ids))
      case GetPoolState(ids) =>
        shelleyBased(f => CertStateAnswers.poolState(f.l, env.mirror.network, ids))
      case GetStakeSnapshots(ids) => shelleyBased(f => CertStateAnswers.stakeSnapshots(f.l, ids))
      case GetStakeDelegDeposits(credentials) =>
        shelleyBased(f => CertStateAnswers.stakeDelegDeposits(f.l, credentials))
      case GetConstitution => conway(f => constitution.answerRead(f.gov.map(_.constitution)))
      // The era's governance state, at every version (Query.hs:456-457, 1032, ouroboros-consensus
      // 82ecba32; `GovStateAnswers`).
      case GetGovState => shelleyBased(f => GovStateAnswers.govState(f.l))
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
                restrict(all, credentials).flatMap((c, a) => a.drep.map(d => c -> EraViews.drep(d)))
              )
            )
        }
      // `queryChainAccountState`, the treasury and the reserves (Query.hs:466-467, 1037,
      // ouroboros-consensus 82ecba32; `State/ChainAccount.hs:46-48`, cardano-ledger f649f975).
      case GetAccountState =>
        shelleyBased { f =>
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
      // `nesPd` (`GetPoolDistr2`) and its retired form (`GetPoolDistr`, `fromLedgerPoolDistr`
      // of the first), the current stake as a fraction of the lovelace in circulation
      // (`GetStakeDistribution2`) and its retired form (`GetStakeDistribution`), in every era, each
      // written at the era's lowest version (Query.hs:419-420, 450-451, 511-514, 1013, 1029,
      // 1044-1045, ouroboros-consensus 82ecba32).
      case GetPoolDistr(ids) =>
        throughConway { (f, v) =>
          f.l.pd.leftMap(Unanswered.Refused(_)).flatMap { pd =>
            legacyDistribution(setSnapshotDistr(pd, ids))(using v)
          }
        }
      case GetStakeDistribution =>
        throughConway((f, v) => currentFraction(env, f).flatMap(legacyDistribution(_)(using v)))
      case GetPoolDistr2(ids) =>
        throughConway { (f, v) =>
          poolDistribution(using v).answerRead(f.l.pd.map(setSnapshotDistr(_, ids)))
        }
      case GetStakeDistribution2 =>
        throughConway((f, v) => currentFraction(env, f).flatMap(poolDistribution(using v).answer))
      // `getPeers` of the ledger state, written by the network's encoder whatever the era
      // (Query.hs:476-508, 1042, ouroboros-consensus 82ecba32).
      case GetLedgerPeerSnapshotLegacy =>
        throughConway((f, _) =>
          PeerSnapshotQueries.answer(env, f, view.point, None, version.number >= 22)
        )
      case GetLedgerPeerSnapshot(kind) =>
        throughConway((f, _) => PeerSnapshotQueries.answer(env, f, view.point, Some(kind), true))
      // `protoMaxMajorPV` of the era's protocol (Query.hs:515-519, 1046), the one maximum TPraos
      // and Praos are configured with in every era (`Cardano/Node.hs:644-645, 677, 690`,
      // ouroboros-consensus 82ecba32).
      case GetMaxMajorProtocolVersion =>
        coin.answer(BigInt(env.forks.maxMajorProtVer))
      case GetDRepDelegations(dreps) =>
        conway { f =>
          Wire
            .sortedMapOf(nodeDRep, Wire.sortedSetOf(nodeCredential))
            .answerRead((f.cert, f.accounts).mapN((c, all) => drepDelegations(c, all, dreps)))
        }
      case _ => Left(Unanswered.Unsupported)

  /** The chain-dependent state of the protocol the chain runs, as the reference serialises its
    * `ChainDepState` (`DebugChainDepState`): TPraos's from Shelley to Alonzo, Praos's from Babbage.
    */
  private def chainDepState[S](view: QueryView[S]): Either[Unanswered, Bytes] =
    view.read.protocol(view.state).dep match
      case ChainDepState.TPraos(t) => HaskellStateCodecs.tpraosState.answer(t)
      case ChainDepState.Praos(p)  => HaskellStateCodecs.praosState.answer(p)
      case ChainDepState.PBft(_)   => Left(Unanswered.Unsupported)

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
    if dreps.nonEmpty && registered.sizeIs == dreps.size then
      c.vState.dreps.iterator.collect {
        case (cred, d) if registered(EraRead.credential(cred)) =>
          DRep.Registered(EraRead.credential(cred)) -> d.delegators.iterator
            .map(EraRead.credential)
            .toSet
      }.toMap
    else
      accounts.toVector
        .flatMap((cred, a) => a.drep.map(d => EraViews.drep(d) -> cred))
        .filter((d, _) => dreps.isEmpty || dreps(d))
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toSet)
        .toMap

  /** `poolsByTotalStakeFraction` of the view's current stake and circulation (`getTotalStake`,
    * `Shelley/API/Wallet.hs:204-209`, cardano-ledger f649f975).
    */
  private def currentFraction(env: QueryEnv, f: EraFacts[?]): Either[Unanswered, Shares] =
    (f.currentStake, f.scalars).tupled.leftMap(Unanswered.Refused(_)).map { (current, scalars) =>
      byTotalStakeFraction(current, env.maxSupply - scalars.chainAccount.reserves.value)
    }

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
