// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.{Eq, Monad, Order, Show}
import cats.mtl.{Ask, Raise}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger as node

// The ledger side of the mirror: the node's flat ledger state to the Haskell-shaped
// `NewEpochState` of an era and back. Plan sections 2 and 3 of the ledger-state mirror.

/** The mirror of the node's ledger state in era `E`: the Haskell-shaped state and the node-only
  * bookkeeping that has no place in it.
  */
final case class LedgerImage[E <: EraTag](nes: NewEpochState[E], extras: NodeExtras)

object LedgerImage:
  given [E <: EraTag](using ShelleyBasedEra[E]): Eq[LedgerImage[E]] =
    Eq.instance((a, b) => a.nes === b.nes && a.extras === b.extras)

  given [E <: EraTag](using ShelleyBasedEra[E]): Diff[LedgerImage[E]] =
    Diff.record(Diff.at("nes")(_.nes), Diff.at("extras")(_.extras))

  given [E <: EraTag](using ShelleyBasedEra[E]): Show[LedgerImage[E]] =
    Show.show(i => s"LedgerImage(nes = ${i.nes.show}, extras = ${i.extras.show})")

/** The conversion between the node's [[node.LedgerState]] and the [[LedgerImage]] of any
  * Shelley-based era `E`, chosen by the witness.
  *
  * `to` derives what Haskell holds and the node does not (the VRF key hashes, the delegators of a
  * pool, the pointer of an account, `StakePoolSnapShot`, the pool distribution, the future
  * parameters); `from` recomputes them from the rest of the mirror and refuses a mirror whose
  * stored values disagree ([[MirrorError.DerivedMismatch]]), which Haskell, trusting what it
  * decodes, does not. The state of the node is recovered whole: `from(to(s))` is
  * [[LedgerMirror.normalised]]`(network, s)`.
  *
  * The capabilities are the environment ([[MirrorEnv]]) and the error ([[MirrorError]]); the
  * position of the tip is an argument, because it is not a property of the configuration.
  */
trait LedgerMirror[F[_]]:
  /** The mirror of `s`, at the tip `at`, with the gaps it has. */
  def to[E <: EraTag](at: WithOrigin[SlotNo], s: node.LedgerState)(using
      ShelleyBasedEra[E]
  ): F[Mirrored[LedgerImage[E]]]

  /** The node's state of a mirror. */
  def from[E <: EraTag](at: WithOrigin[SlotNo], image: LedgerImage[E])(using
      ShelleyBasedEra[E]
  ): F[node.LedgerState]

object LedgerMirror:
  def apply[F[_]](using
      monad: Monad[F],
      env: Ask[F, MirrorEnv],
      errors: Raise[F, MirrorError]
  ): LedgerMirror[F] = Impl[F]

  /** What a state becomes through the mirror, on the node's `network`: the future genesis
    * delegations are a map in Haskell, ordered by slot and key and with one entry of each, and a
    * vector with no order in the node; nothing reads their order. The reward account of a
    * registered pool, and of a pool in a stake snapshot, is on the node's network
    * ([[node.PoolParams.registeredOn]]): Haskell keeps its credential only, as the rules read it.
    * The future parameters of a pool keep theirs.
    */
  def normalised(network: Int, s: node.LedgerState): node.LedgerState =
    s.copy(
      futureGenDelegs = s.futureGenDelegs
        .groupBy(d => (d.slot, d.genesisKey))
        .values
        .map(_.last)
        .toVector
        .sortWith((a, b) =>
          a.slot < b.slot || (a.slot == b.slot && (a.genesisKey compareTo b.genesisKey) < 0)
        ),
      pools = s.pools.view.mapValues(p => p.copy(params = p.params.registeredOn(network))).toMap,
      snapshots = s.snapshots.copy(
        mark = poolsOn(network, s.snapshots.mark),
        set = poolsOn(network, s.snapshots.set),
        go = poolsOn(network, s.snapshots.go)
      ),
      poolDistribution = poolsOn(network, s.poolDistribution)
    )

  /** The snapshot with its pools' reward accounts on `network`. */
  private def poolsOn(network: Int, s: node.Snapshot): node.Snapshot =
    s.copy(pools = s.pools.view.mapValues(_.registeredOn(network)).toMap)

  /** The gaps of the mirror of `s` (`Gap`), a function of the state alone. */
  def gaps(s: node.LedgerState): Set[Gap] =
    val snapshots = List(s.snapshots.set, s.snapshots.go, s.poolDistribution)
    Set(Gap.NonMyopic) ++
      Option.when(s.rewardUpdate.exists(_.rewards.nonEmpty))(Gap.RewardAttribution) ++
      Option.when(s.rewardUpdate.isEmpty && s.rewardDraft.isDefined)(Gap.PendingPulsingUpdate) ++
      Option.when(snapshots.exists(_.idlePools.nonEmpty))(Gap.NumDelegatorsLowerBound)

  /** `FuturePParams` of the state at the tip (`Governance.hs:90-102`, `Tick.hs:144-155`).
    *
    * Conway: the parameters of the ratification the pulser finished, when it enacted a parameter
    * change or a hard fork; `Potential` right after the block that crossed a boundary and until the
    * point of no return (two stability windows before the end of the epoch), `Definite` or `No`
    * after. Before Conway: the update the genesis delegates voted for (`votedFuturePParams`),
    * `Potential` until the point of no return, `Definite` or `No` after. The first epoch of a
    * chain, where Haskell has `NoPParamsUpdate` until the first boundary, is not told apart.
    */
  def futurePParams(
      env: MirrorEnv,
      at: WithOrigin[SlotNo],
      s: node.LedgerState,
      conway: Boolean
  ): FuturePParams =
    val slot = at match
      case WithOrigin.Origin => BigInt(0)
      case WithOrigin.At(sl) => sl.toBigInt
    val pointOfNoReturn =
      env.geometry.firstSlot(s.epoch + 1) - 2 * env.geometry.stabilityWindow
    def settle(next: Option[node.ProtocolParams], potential: Boolean): FuturePParams =
      if potential || slot < pointOfNoReturn then FuturePParams.PotentialPParamsUpdate(next)
      else next.fold(FuturePParams.NoPParamsUpdate)(FuturePParams.DefinitePParamsUpdate(_))
    if conway then
      s.gov.pulsed.fold(FuturePParams.NoPParamsUpdate) { (_, ratify) =>
        val changes = ratify.enacted.exists(g =>
          g.action match
            case _: node.GovAction.ParameterChange | _: node.GovAction.HardFork => true
            case _                                                              => false
        )
        settle(
          Option.when(changes)(ratify.enact.params),
          potential = s.blocksCurrent.valuesIterator.sum <= 1
        )
      }
    else settle(node.PpupRules.voted(s.params, s.ppup.current, env.quorum), potential = false)

  /** The parameters as the era's `PParams` has them: the fields it has no place for are zero (the
    * Haskell Alonzo parameters have no `minUTxOValue`, Babbage and Conway have neither it nor
    * `coinsPerUTxOWord`); [[ParamFields]] keeps the originals.
    */
  private def eraParams[E <: EraTag](p: node.ProtocolParams)(using
      era: ShelleyBasedEra[E]
  ): node.ProtocolParams = era match
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary =>
      p.copy(coinsPerUTxOWord = 0)
    case ShelleyBasedEra.SAlonzo => p.copy(minUTxOValue = 0)
    case ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway =>
      p.copy(minUTxOValue = 0, coinsPerUTxOWord = 0)

  /** The future parameters as the era's `PParams` has them. Before Babbage they are also without
    * `coinsPerUTxOByte`, which the node's update rule derives when the update crosses into Babbage
    * (`ProtocolParams.update`): an Alonzo update that raises the version has the Alonzo parameters
    * of the new version, and the translation to Babbage (`upgradeBabbagePParams`,
    * `Babbage/PParams.hs:321-345`) comes when they take effect.
    */
  private def eraFuture[E <: EraTag](f: FuturePParams)(using
      era: ShelleyBasedEra[E]
  ): FuturePParams =
    def fields(p: node.ProtocolParams): node.ProtocolParams =
      val q = eraParams[E](p)
      era match
        case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
            ShelleyBasedEra.SAlonzo =>
          q.copy(coinsPerUTxOByte = 0)
        case ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway => q
    f match
      case FuturePParams.NoPParamsUpdate => f
      case FuturePParams.DefinitePParamsUpdate(p) =>
        FuturePParams.DefinitePParamsUpdate(fields(p))
      case FuturePParams.PotentialPParamsUpdate(next) =>
        FuturePParams.PotentialPParamsUpdate(next.map(fields))

  /** The accounts that delegate to each pool, stake or no stake. */
  private def accountDelegators(s: node.LedgerState): Map[Bytes, Vector[node.Credential]] =
    s.accounts.iterator
      .collect { case (c, node.Account(_, _, Some(p), _)) => p -> c }
      .toVector
      .groupMap(_._1)(_._2)

  private def isConway[E <: EraTag](using era: ShelleyBasedEra[E]): Boolean = era match
    case ShelleyBasedEra.SConway => true
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      false

  // ---- the conversions of the leaf types that need no effect

  private[ledger] def credBack[R <: KeyRole](c: Credential[R]): node.Credential = c match
    case Credential.ScriptHashObj(h) => node.Credential.Script(h.bytes)
    case Credential.KeyHashObj(h)    => node.Credential.Key(h.bytes)

  private[ledger] def drepBack(d: DRep): node.DRep = d match
    case DRep.DRepKeyHash(h)         => node.DRep.Registered(node.Credential.Key(h.bytes))
    case DRep.DRepScriptHash(h)      => node.DRep.Registered(node.Credential.Script(h.bytes))
    case DRep.DRepAlwaysAbstain      => node.DRep.AlwaysAbstain
    case DRep.DRepAlwaysNoConfidence => node.DRep.AlwaysNoConfidence

  private[ledger] def stakeMap[R <: KeyRole, A, B](m: SortedMap[Credential[R], A])(
      f: A => B
  ): Map[node.Credential, B] =
    m.iterator.map((c, a) => credBack(c) -> f(a)).toMap

  private[ledger] def coinMap(
      m: SortedMap[Credential[Staking], Coin]
  ): Map[node.Credential, BigInt] =
    stakeMap(m)(_.value)

  private val genesisPointer: node.Ptr = node.Ptr(0, 0, 0)

  /** The era-independent view of a certificate state, what `from` needs of it. */
  private final case class CertParts(
      pState: PState,
      accounts: Map[node.Credential, node.Account],
      ptrs: Map[node.Ptr, node.Credential],
      futureGenDelegs: SortedMap[FutureGenDeleg, node.GenDelegPair],
      genDelegs: SortedMap[KeyHash[Genesis], node.GenDelegPair],
      iRewards: InstantaneousRewards,
      vState: Option[VState]
  )

  private[ledger] def conwayAccount(a: ConwayAccountState): node.Account =
    node.Account(
      a.balance.toBigInt,
      a.deposit.toBigInt,
      a.pool.map(_.bytes),
      a.drep.map(drepBack)
    )

  private[ledger] def shelleyAccount(a: ShelleyAccountState): node.Account =
    node.Account(a.balance.toBigInt, a.deposit.toBigInt, a.pool.map(_.bytes), None)

  private def certParts[E <: EraTag](c: CertState[E])(using era: ShelleyBasedEra[E]): CertParts =
    era match
      case ShelleyBasedEra.SConway =>
        CertParts(
          c.pState,
          stakeMap(c.dState.accounts.states)(conwayAccount),
          Map.empty,
          c.dState.futureGenDelegs,
          c.dState.genDelegs,
          c.dState.iRewards,
          Some(c.vState)
        )
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        CertParts(
          c.pState,
          stakeMap(c.dState.accounts.states)(shelleyAccount),
          c.dState.accounts.ptrs.iterator.map((p, cr) => p -> credBack(cr)).toMap,
          c.dState.futureGenDelegs,
          c.dState.genDelegs,
          c.dState.iRewards,
          None
        )

  /** The era-independent view of a governance state. */
  private final case class GovParts(
      params: node.ProtocolParams,
      prev: node.ProtocolParams,
      conway: Option[(node.GovState, DRComplete)],
      ppup: Option[node.Ppup]
  )

  private def govParts[E <: EraTag](g: GovState[E])(using era: ShelleyBasedEra[E]): GovParts =
    era match
      case ShelleyBasedEra.SConway =>
        GovParts(
          g.curPParams,
          g.prevPParams,
          Some((node.GovState(g.proposals, g.committee, g.constitution, None), g.drepPulsing)),
          None
        )
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        GovParts(
          g.curPParams,
          g.prevPParams,
          None,
          Some(
            node.Ppup(
              g.curProposals.iterator.map((k, u) => k.bytes -> u).toMap,
              g.futureProposals.iterator.map((k, u) => k.bytes -> u).toMap
            )
          )
        )

  private def futureOf[E <: EraTag](g: GovState[E])(using era: ShelleyBasedEra[E]): FuturePParams =
    era match
      case ShelleyBasedEra.SConway => g.futurePParams
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        g.futurePParams

  private[ledger] def pools(
      network: Int,
      ps: SortedMap[KeyHash[StakePool], StakePoolState]
  ): Map[Bytes, node.PoolState] =
    ps.iterator.map { (id, p) =>
      id.bytes -> node.PoolState(
        node.PoolParams(
          id.bytes,
          p.vrf.bytes,
          p.pledge.value,
          p.cost.value,
          p.margin,
          node.RewardAccount(network, credBack(p.accountId)),
          p.owners.iterator.map(_.bytes).toSet,
          p.relays,
          p.metadata
        ),
        p.deposit.toBigInt
      )
    }.toMap

  /** The node's snapshot of a mirror's, with the pool details from the residue (the owners that do
    * not delegate to the pool are not in `StakePoolSnapShot`; without residue the snapshot's own
    * are all there is, and the relays are empty). The pools with more delegators than the snapshot
    * lists are the idle ones.
    */
  private def snapshotBack(
      network: Int,
      s: SnapShot,
      residue: Map[Bytes, PoolResidue]
  ): node.Snapshot =
    val staked = s.activeStake.entries
    val counts = staked.valuesIterator.toVector.groupMapReduce(_.delegation)(_ => 1)(_ + _)
    node.Snapshot(
      stakeMap(staked)(_.stake.value.toBigInt),
      stakeMap(staked)(_.delegation.bytes),
      s.pools.iterator.map { (id, p) =>
        val extra = residue.getOrElse(
          id.bytes,
          PoolResidue(p.selfDelegatedOwners.iterator.map(_.bytes).toSet, Bytes.empty, None)
        )
        id.bytes -> node.PoolParams(
          id.bytes,
          p.vrf.bytes,
          p.pledge.value,
          p.cost.value,
          p.margin,
          node.RewardAccount(network, credBack(p.accountId)),
          extra.owners,
          extra.relays,
          extra.metadata
        )
      }.toMap,
      s.pools.iterator.collect {
        case (id, p) if p.numDelegators.toBigInt > BigInt(counts.getOrElse(id, 0)) => id.bytes
      }.toSet
    )

  private final class Impl[F[_]](using
      monad: Monad[F],
      asks: Ask[F, MirrorEnv],
      errors: Raise[F, MirrorError]
  ) extends LedgerMirror[F]:

    // ---- the capabilities, in the shapes the conversions use them

    private def fail[A](e: MirrorError): F[A] = errors.raise(e)

    private def vocab[A](e: Either[VocabError, A]): F[A] =
      e.fold(v => fail(MirrorError.Vocab(v)), monad.pure)

    private def hash[R <: KeyRole](b: Bytes): F[KeyHash[R]] = vocab(KeyHash.fromBytes[R](b))
    private def vrfHash(b: Bytes): F[VRFVerKeyHash] = vocab(VRFVerKeyHash.fromBytes(b))
    private def coin(n: BigInt): F[Coin] = vocab(Coin.fromBigInt(n))
    private def compact(n: BigInt): F[CompactCoin] = vocab(CompactCoin.fromBigInt(n))

    private def cred[R <: KeyRole](c: node.Credential): F[Credential[R]] = c match
      case node.Credential.Key(h) =>
        hash[R](h).map[Credential[R]](Credential.KeyHashObj[R](_))
      case node.Credential.Script(h) =>
        vocab(ScriptHash.fromBytes(h)).map[Credential[R]](Credential.ScriptHashObj[R](_))

    private def drep(d: node.DRep): F[DRep] = d match
      case node.DRep.Registered(node.Credential.Key(h)) =>
        hash[DRepRole](h).map[DRep](DRep.DRepKeyHash(_))
      case node.DRep.Registered(node.Credential.Script(h)) =>
        vocab(ScriptHash.fromBytes(h)).map[DRep](DRep.DRepScriptHash(_))
      case node.DRep.AlwaysAbstain      => monad.pure(DRep.DRepAlwaysAbstain)
      case node.DRep.AlwaysNoConfidence => monad.pure(DRep.DRepAlwaysNoConfidence)

    private def sorted[K1, V1, K2: Order, V2](entries: Iterable[(K1, V1)])(
        f: (K1, V1) => F[(K2, V2)]
    ): F[SortedMap[K2, V2]] =
      entries.toList.traverse((k, v) => f(k, v)).map(Sorted.map(_))

    private def stakeCredMap(
        m: Map[node.Credential, BigInt]
    ): F[SortedMap[Credential[Staking], Coin]] =
      sorted(m)((c, n) => (cred[Staking](c), coin(n)).tupled)

    // ---- the certificate state

    private def pState(s: node.LedgerState): F[PState] =
      // `psVRFKeyHashes` is kept from protocol version 10 (the hard fork populates it,
      // `Conway/Rules/HardFork.hs:106-118`): the VRF key of every registered pool and of every
      // pending re-registration, once (`Shelley/Rules/Pool.hs:262-306`).
      // Haskell counts the registered pools that use a key: a key two pools share is 2.
      val vrfs: List[(Bytes, Int)] =
        if s.params.protocolMajor >= 10 then
          (s.pools.keySet ++ s.futurePools.keySet).iterator
            .flatMap(id =>
              (s.pools
                .get(id)
                .map(_.params.vrfKeyHash) ++ s.futurePools.get(id).map(_.vrfKeyHash)).toSet
            )
            .toList
            .groupMapReduce(identity)(_ => 1)(_ + _)
            .toList
        else Nil
      val delegators = accountDelegators(s)
      for
        vrfKeys <- vrfs
          .traverse((key, n) =>
            (vrfHash(key), vocab(NonZero.from(Word64.fromUnsigned(n.toLong)))).tupled
          )
          .map(Sorted.map(_))
        stakePools <- sorted(s.pools) { (id, ps) =>
          val pp = ps.params
          for
            key <- hash[StakePool](id)
            vrf <- vrfHash(pp.vrfKeyHash)
            pledge <- coin(pp.pledge)
            cost <- coin(pp.cost)
            // the account's credential only, whatever its network (`mkStakePoolState`,
            // `State/StakePool.hs:256-269`); `from` puts it on the node's
            account <- cred[Staking](pp.rewardAccount.credential)
            owners <- pp.owners.toList.traverse(hash[Staking](_)).map(Sorted.set(_))
            deposit <- compact(ps.deposit)
            dels <- delegators
              .getOrElse(id, Vector.empty)
              .toList
              .traverse(cred[Staking](_))
              .map(Sorted.set(_))
          yield key -> StakePoolState(
            vrf,
            pledge,
            cost,
            pp.margin,
            account,
            owners,
            pp.relays,
            pp.metadata,
            deposit,
            dels
          )
        }
        future <- sorted(s.futurePools)((id, pp) => hash[StakePool](id).map(_ -> pp))
        retiring <- sorted(s.retiring)((id, e) =>
          (hash[StakePool](id), vocab(EpochNo.fromBigInt(e))).tupled
        )
      yield PState(vrfKeys, stakePools, future, retiring)

    private def instantaneous(m: node.InstantRewards): F[InstantaneousRewards] =
      (stakeCredMap(m.reserves), stakeCredMap(m.treasury)).mapN(
        InstantaneousRewards(_, _, DeltaCoin(m.deltaReserves), DeltaCoin(m.deltaTreasury))
      )

    private def genDelegs(
        s: node.LedgerState
    ): F[SortedMap[KeyHash[Genesis], node.GenDelegPair]] =
      sorted(s.genDelegs)((k, p) => hash[Genesis](k).map(_ -> p))

    private def futureGenDelegs(
        s: node.LedgerState
    ): F[SortedMap[FutureGenDeleg, node.GenDelegPair]] =
      s.futureGenDelegs.toList
        .traverse(d =>
          (vocab(SlotNo.fromBigInt(d.slot)), hash[Genesis](d.genesisKey))
            .mapN((slot, k) => FutureGenDeleg(slot, k) -> d.pair)
        )
        .map(Sorted.map(_))

    private def dState[A <: AccountsRecord](
        s: node.LedgerState,
        accounts: A
    ): F[DState[A]] =
      (futureGenDelegs(s), genDelegs(s), instantaneous(s.mir)).mapN(
        DState(accounts, _, _, _)
      )

    private def conwayAccounts(s: node.LedgerState): F[ConwayAccounts] =
      sorted(s.accounts) { (c, a) =>
        (
          cred[Staking](c),
          (
            compact(a.balance),
            compact(a.deposit),
            a.pool.traverse(hash[StakePool](_)),
            a.drep.traverse(drep)
          ).mapN(ConwayAccountState(_, _, _, _))
        ).tupled
      }.map(ConwayAccounts(_))

    private def shelleyAccounts(s: node.LedgerState): F[ShelleyAccounts] =
      val ordering = summon[Ordering[node.Ptr]]
      // Each credential registered by one certificate, whose pointer it is; a credential
      // registered by none (genesis) has the zero pointer.
      val pointer: Map[node.Credential, node.Ptr] =
        s.ptrs.toVector
          .groupMapReduce(_._2)(_._1)((a, b) => if ordering.gteq(a, b) then a else b)
      for
        states <- sorted(s.accounts) { (c, a) =>
          (
            cred[Staking](c),
            (
              compact(a.balance),
              compact(a.deposit),
              a.pool.traverse(hash[StakePool](_))
            ).mapN(ShelleyAccountState(pointer.getOrElse(c, genesisPointer), _, _, _))
          ).tupled
        }
        ptrs <- sorted(s.ptrs)((p, c) => cred[Staking](c).map(p -> _))
      yield ShelleyAccounts(states, ptrs)

    private def vState(s: node.LedgerState): F[VState] =
      for
        dreps <- sorted(s.dreps) { (c, d) =>
          (
            cred[DRepRole](c),
            (
              vocab(EpochNo.fromBigInt(d.expiry)),
              compact(d.deposit),
              d.delegs.toList.traverse(cred[Staking](_)).map(Sorted.set(_))
            ).mapN(DRepState(_, d.anchor, _, _))
          ).tupled
        }
        committee <- sorted(s.committeeState) { (c, a) =>
          val auth: F[CommitteeAuthorization] = a match
            case node.CommitteeAuth.Hot(h) =>
              cred[HotCommittee](h).map[CommitteeAuthorization](
                CommitteeAuthorization.CommitteeHotCredential(_)
              )
            case node.CommitteeAuth.Resigned(anchor) =>
              monad.pure(CommitteeAuthorization.CommitteeMemberResigned(anchor))
          (cred[ColdCommittee](c), auth).tupled
        }
        dormant <- vocab(EpochNo.fromBigInt(s.dormantEpochs))
      yield VState(dreps, CommitteeState(committee), dormant)

    private def certState[E <: EraTag](s: node.LedgerState, p: PState)(using
        era: ShelleyBasedEra[E]
    ): F[CertState[E]] = era match
      case ShelleyBasedEra.SConway =>
        for
          v <- vState(s)
          accounts <- conwayAccounts(s)
          d <- dState(s, accounts)
        yield ConwayCertState(v, p, d)
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        for
          accounts <- shelleyAccounts(s)
          d <- dState(s, accounts)
        yield ShelleyCertState(p, d)

    // ---- the governance state and the instant stake

    private def govState[E <: EraTag](at: WithOrigin[SlotNo], s: node.LedgerState)(using
        era: ShelleyBasedEra[E]
    ): F[GovState[E]] =
      asks.ask.flatMap { env =>
        val future = eraFuture[E](futurePParams(env, at, s, conway = isConway[E]))
        era match
          case ShelleyBasedEra.SConway =>
            monad.pure(
              ConwayGovState(
                s.gov.proposals,
                s.gov.committee,
                s.gov.constitution,
                eraParams[E](s.params),
                eraParams[E](node.ConwayEpoch.reportedPrevious(s)),
                future,
                pulser(s)
              )
            )
          case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
              ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
            for
              cur <- sorted(s.ppup.current)((k, u) => hash[Genesis](k).map(_ -> u))
              fut <- sorted(s.ppup.future)((k, u) => hash[Genesis](k).map(_ -> u))
            yield ShelleyGovState(
              cur,
              fut,
              eraParams[E](s.params),
              eraParams[E](s.previousParams),
              future
            )
      }

    /** The completed pulser as the governance state reports it (`ConwayEpoch.reportedPulser`, the
      * pulser the node-to-client queries answer): before the first boundary the node has none, and
      * the mirror holds the ratification of nothing over the state it is in
      * (`NodeExtras.pulserAbsent`).
      */
    private def pulser(s: node.LedgerState): DRComplete =
      val (snapshot, ratify) = node.ConwayEpoch.reportedPulser(s)
      DRComplete(snapshot, ratify)

    /** `utxosInstantStake`: the stake of the UTxO by credential (and by pointer before Conway). */
    private def instantStake[E <: EraTag](u: node.Utxo)(using
        era: ShelleyBasedEra[E]
    ): F[InstantStake[E]] =
      def credentials: F[SortedMap[Credential[Staking], CompactCoin]] =
        sorted(u.stake)((c, n) => (cred[Staking](c), compact(n)).tupled)
      era match
        case ShelleyBasedEra.SConway => credentials.map(ConwayInstantStake(_))
        case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
            ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
          (credentials, sorted(u.ptrStake)((p, n) => compact(n).map(p -> _))).mapN(
            ShelleyInstantStake(_, _)
          )

    // ---- the snapshots

    private final case class Snap(snapshot: SnapShot, residue: Map[Bytes, PoolResidue])

    /** `SnapShot` of the node's snapshot (`mkSnapShot`, `State/SnapShots.hs:397-403`, and
      * `StakePoolSnapShot`, `239-252`), whose delegators are the stake-bearing ones and whose idle
      * pools count one more.
      */
    /** `exact`: the accounts' delegations, for the snapshot the node took from them (`mark`, on the
      * [[SnapshotBasis.Accounts]] basis): the pool's delegators then include those with no stake,
      * which `numDelegators` and the owners that delegate to the pool count. Without it (`set`,
      * `go`, and `mark` on [[SnapshotBasis.AsTaken]]), the count is a lower bound.
      */
    private def snapShot(
        snap: node.Snapshot,
        exact: Option[Map[Bytes, Vector[node.Credential]]] = None
    ): F[Snap] =
      val delegators: Map[Bytes, Vector[node.Credential]] =
        snap.delegations.toVector.groupMap(_._2)(_._1)
      def shape(s: Shape): F[Unit] = fail(MirrorError.IllFormed(s))
      def check(ok: Boolean, s: Shape): F[Unit] = if ok then monad.unit else shape(s)
      for
        _ <- check(
          snap.stake.keySet.subsetOf(snap.delegations.keySet),
          Shape.StakeWithoutDelegation
        )
        _ <- check(
          snap.delegations.keySet.subsetOf(snap.stake.keySet),
          Shape.DelegationWithoutStake
        )
        _ <- check(snap.stake.valuesIterator.forall(_.signum > 0), Shape.ZeroStake)
        _ <- check(
          snap.delegations.valuesIterator.forall(snap.pools.contains),
          Shape.DelegationToUnknownPool
        )
        _ <- check(snap.idlePools.subsetOf(snap.pools.keySet), Shape.IdleUnknownPool)
        entries <- sorted(snap.stake) { (c, n) =>
          for
            cr <- cred[Staking](c)
            amount <- compact(n)
            nonZero <- vocab(NonZero.from(amount))
            pool <- hash[StakePool](snap.delegations(c))
          yield cr -> StakeWithDelegation(nonZero, pool)
        }
        active = ActiveStake(entries)
        total = active.total.value.value
        poolSnapshots <- sorted(snap.pools) { (id, pp) =>
          val members = delegators.getOrElse(id, Vector.empty)
          val stake = members.foldLeft(BigInt(0))((sum, c) => sum + snap.stake(c))
          val registered = exact.map(_.getOrElse(id, Vector.empty))
          val selfOwners = registered.fold(
            pp.owners.filter(o => snap.delegations.get(node.Credential.Key(o)).contains(id))
          )(rs => pp.owners.filter(o => rs.contains(node.Credential.Key(o))))
          val selfStake =
            selfOwners.foldLeft(BigInt(0))((sum, o) =>
              sum + snap.stake.getOrElse(node.Credential.Key(o), BigInt(0))
            )
          val idle = BigInt(members.size) + (if snap.idlePools.contains(id) then 1 else 0)
          val count = registered.fold(idle)(rs => idle.max(BigInt(rs.size)))
          for
            key <- hash[StakePool](id)
            poolStake <- compact(stake)
            owners <- selfOwners.toList.traverse(hash[Staking](_)).map(Sorted.set(_))
            ownersStake <- coin(selfStake)
            vrf <- vrfHash(pp.vrfKeyHash)
            pledge <- coin(pp.pledge)
            cost <- coin(pp.cost)
            numDelegators <- vocab(Word64.fromBigInt(VocabError.Kind.BlockCount, count))
            account <- cred[Staking](pp.rewardAccount.credential)
          yield key -> StakePoolSnapShot(
            poolStake,
            Rational(stake, total),
            owners,
            ownersStake,
            vrf,
            pledge,
            cost,
            pp.margin,
            numDelegators,
            account
          )
        }
      yield Snap(SnapShot(active, poolSnapshots), snap.pools.view.mapValues(PoolResidue.of).toMap)

    private def blocks(m: Map[Bytes, BigInt]): F[BlocksMade] =
      sorted(m)((k, n) =>
        (hash[StakePool](k), vocab(Word64.fromBigInt(VocabError.Kind.BlockCount, n))).tupled
      ).map(BlocksMade.iso.reverseGet)

    private def rewardUpdate(
        ru: Option[node.RewardUpdate]
    ): F[Option[PulsingRewUpdate]] =
      monad.pure(
        ru.map(r =>
          PulsingRewUpdate.Complete(
            RewardUpdate(
              DeltaCoin(r.deltaTreasury),
              DeltaCoin(r.deltaReserves),
              Sorted.empty,
              DeltaCoin(r.deltaFees),
              NonMyopic.empty
            )
          )
        )
      )

    // ---- the whole state

    def to[E <: EraTag](at: WithOrigin[SlotNo], s: node.LedgerState)(using
        era: ShelleyBasedEra[E]
    ): F[Mirrored[LedgerImage[E]]] =
      val conway = isConway[E]
      for
        epoch <- vocab(EpochNo.fromBigInt(s.epoch))
        bprev <- blocks(s.blocksPrevious)
        bcur <- blocks(s.blocksCurrent)
        treasury <- coin(s.treasury)
        reserves <- coin(s.reserves)
        deposited <- coin(s.deposited)
        fees <- coin(s.fees)
        donation <- coin(s.donation)
        fee <- coin(s.snapshots.fee)
        p <- pState(s)
        cert <- certState[E](s, p)
        gov <- govState[E](at, s)
        instant <- instantStake[E](s.utxo)
        env <- asks.ask
        mark <- snapShot(
          s.snapshots.mark,
          env.snapshots match
            case SnapshotBasis.Accounts => Some(accountDelegators(s))
            case SnapshotBasis.AsTaken  => None
        )
        set <- snapShot(s.snapshots.set)
        go <- snapShot(s.snapshots.go)
        // the distribution as it comes back: its pools' accounts on the node's network
        distribution = poolsOn(env.network, s.poolDistribution)
        carried = Option.unless(distribution == poolsOn(env.network, s.snapshots.set))(
          distribution
        )
        distr <- carried.traverse(snapShot(_))
        ru <- rewardUpdate(s.rewardUpdate)
      yield
        val pd = PoolDistr.of(distr.fold(set.snapshot)(_.snapshot))
        val ls = LedgerState[E](
          cert,
          UTxOState[E](UtxoTables(s.utxo), deposited, fees, gov, instant, donation)
        )
        val es = EpochState[E](
          ChainAccountState.pair.reverseGet((treasury, reserves)),
          ls,
          SnapShots(mark.snapshot, set.snapshot, go.snapshot, fee),
          NonMyopic.empty
        )
        val nes = NewEpochState[E](epoch, bprev, bcur, es, ru, pd, Empty.stashedAvvm[E])
        val extras = NodeExtras(
          s.plutus,
          s.rewardDraft,
          s.rewardRegistered,
          s.rewardUpdate.fold(Map.empty[node.Credential, BigInt])(_.rewards),
          s.prevParams.isDefined,
          pulserAbsent = conway && s.gov.pulsed.isEmpty,
          SnapshotsResidue(mark.residue, set.residue, go.residue, s.snapshots.mark.idlePools),
          carried,
          DroppedParams(ParamFields.of(s.params), ParamFields.of(s.previousParams)),
          residue(s, conway)
        )
        Mirrored(LedgerImage(nes, extras), gaps(s))

    /** What the era has no place for. */
    private def residue(s: node.LedgerState, conway: Boolean): Residue =
      if conway then Residue.empty.copy(ppup = s.ppup, ptrs = s.ptrs)
      else
        Residue.empty.copy(
          gov = s.gov,
          dreps = s.dreps,
          committeeState = s.committeeState,
          dormantEpochs = s.dormantEpochs,
          accountDreps = s.accounts.collect { case (c, node.Account(_, _, _, Some(d))) => c -> d }
        )

    def from[E <: EraTag](at: WithOrigin[SlotNo], image: LedgerImage[E])(using
        era: ShelleyBasedEra[E]
    ): F[node.LedgerState] =
      val nes = image.nes
      val x = image.extras
      val ls = nes.es.ls
      val cert = certParts[E](ls.cert)
      val gov = govParts[E](ls.utxo.gov)
      val conway = isConway[E]
      asks.ask.flatMap { env =>
        val net = env.network
        val set = nes.es.snapshots.set
        val distribution = x.poolDistribution.getOrElse(
          snapshotBack(net, set, x.snapshots.set)
        )
        val rewards: Option[node.RewardUpdate] = nes.ru match
          case None => None
          case Some(PulsingRewUpdate.Complete(u)) =>
            Some(
              node.RewardUpdate(
                u.deltaT.value,
                u.deltaR.value,
                if u.rs.isEmpty then x.rewardAmounts
                else
                  stakeMap(u.rs)(_.rewards.iterator.foldLeft(BigInt(0))(_ + _.rewardAmount.value))
                ,
                u.deltaF.value
              )
            )
          case Some(PulsingRewUpdate.Pulsing(_, _)) => None
        val pulsing = nes.ru.exists {
          case PulsingRewUpdate.Pulsing(_, _) => true
          case _                              => false
        }
        val cur = x.params.cur.restore(gov.params)
        // a Conway state with no previous parameters reports its pulser and previous parameters as of
        // the era translation (`ConwayEpoch.reportedPulser`): put back what that took out
        val beforeBoundary = conway && !x.prevParamsExplicit
        val prev = node.ConwayEpoch.heldPrevious(
          x.params.prev.restore(gov.prev),
          cur,
          beforeBoundary
        )
        val pulsed =
          if x.pulserAbsent then None
          else
            gov.conway.map((_, d) =>
              (d.snapshot, node.ConwayEpoch.heldRatify(d.ratify, cur, beforeBoundary))
            )
        val rebuilt = node.LedgerState(
          epoch = nes.epoch.toBigInt,
          params = cur,
          utxo = ls.utxo.utxo.utxo,
          fees = ls.utxo.fees.value,
          deposited = ls.utxo.deposited.value,
          accounts = cert.accounts ++ x.residue.accountDreps.flatMap((c, d) =>
            cert.accounts.get(c).map(a => c -> a.copy(drep = Some(d)))
          ),
          pools = pools(net, cert.pState.stakePools),
          futurePools = cert.pState.future.iterator.map((id, p) => id.bytes -> p).toMap,
          retiring = cert.pState.retiring.iterator.map((id, e) => id.bytes -> e.toBigInt).toMap,
          dreps =
            if conway then
              cert.vState.fold(Map.empty[node.Credential, node.DRepState])(v =>
                stakeMap(v.dreps)(d =>
                  node.DRepState(
                    d.deposit.toBigInt,
                    d.expiry.toBigInt,
                    d.anchor,
                    d.delegators.iterator.map(credBack).toSet
                  )
                )
              )
            else x.residue.dreps,
          dormantEpochs =
            if conway then cert.vState.fold(BigInt(0))(_.numDormantEpochs.toBigInt)
            else x.residue.dormantEpochs,
          treasury = nes.es.chainAccount.treasury.value,
          reserves = nes.es.chainAccount.reserves.value,
          snapshots = node.Snapshots(
            snapshotBack(net, nes.es.snapshots.mark, x.snapshots.mark)
              .copy(idlePools = x.snapshots.markIdle),
            snapshotBack(net, set, x.snapshots.set),
            snapshotBack(net, nes.es.snapshots.go, x.snapshots.go),
            nes.es.snapshots.fee.value
          ),
          blocksCurrent = nes.bcur.blocks.iterator.map((k, n) => k.bytes -> n.toBigInt).toMap,
          blocksPrevious = nes.bprev.blocks.iterator.map((k, n) => k.bytes -> n.toBigInt).toMap,
          rewardUpdate = rewards,
          poolDistribution = distribution,
          genDelegs = cert.genDelegs.iterator.map((k, p) => k.bytes -> p).toMap,
          futureGenDelegs = cert.futureGenDelegs.iterator
            .map((d, p) => node.FutureGenDeleg(d.slot.toBigInt, d.genKeyHash.bytes, p))
            .toVector,
          ppup = gov.ppup.getOrElse(x.residue.ppup),
          mir = node.InstantRewards(
            coinMap(cert.iRewards.reserves),
            coinMap(cert.iRewards.treasury),
            cert.iRewards.deltaReserves.value,
            cert.iRewards.deltaTreasury.value
          ),
          ptrs = if conway then x.residue.ptrs else cert.ptrs,
          prevParams = Option.when(x.prevParamsExplicit || prev != cur)(prev),
          rewardRegistered = x.rewardRegistered,
          plutus = x.plutus,
          gov = gov.conway.fold(x.residue.gov)((g, _) => g.copy(pulsed = pulsed)),
          committeeState =
            if conway then
              cert.vState.fold(Map.empty[node.Credential, node.CommitteeAuth])(v =>
                stakeMap(v.committeeState.creds) {
                  case CommitteeAuthorization.CommitteeHotCredential(h) =>
                    node.CommitteeAuth.Hot(credBack(h))
                  case CommitteeAuthorization.CommitteeMemberResigned(a) =>
                    node.CommitteeAuth.Resigned(a)
                }
              )
            else x.residue.committeeState,
          donation = ls.utxo.donation.value,
          rewardDraft = x.rewardDraft
        )
        if pulsing then fail[node.LedgerState](MirrorError.IllFormed(Shape.PulsingRewardUpdate))
        else verify(at, image, rebuilt).as(rebuilt)
      }

    /** The mirror of the rebuilt state must be the mirror it was rebuilt from, in what is derived:
      * the first piece that is not, is named.
      */
    private def verify[E <: EraTag](
        at: WithOrigin[SlotNo],
        image: LedgerImage[E],
        rebuilt: node.LedgerState
    )(using era: ShelleyBasedEra[E]): F[Unit] =
      to[E](at, rebuilt).flatMap { again =>
        val a = image.nes
        val b = again.value.nes
        val pa = LedgerState.pState[E].get(a.es.ls)
        val pb = LedgerState.pState[E].get(b.es.ls)
        val ga = a.es.ls.utxo.gov
        val gb = b.es.ls.utxo.gov
        import EraInstances.given
        val checks: List[(Derived, Boolean)] = List(
          Derived.VrfKeyHashes -> (pa.vrfKeyHashes == pb.vrfKeyHashes),
          Derived.PoolDelegators -> (pa.stakePools == pb.stakePools),
          Derived.AccountPointer -> Eq[CertState[E]].eqv(a.es.ls.cert, b.es.ls.cert),
          Derived.InstantStake ->
            Eq[InstantStake[E]].eqv(a.es.ls.utxo.instantStake, b.es.ls.utxo.instantStake),
          Derived.FuturePParams -> (futureOf[E](ga) == futureOf[E](gb)),
          Derived.PulserPlaceholder -> Eq[GovState[E]].eqv(ga, gb),
          Derived.PoolDistr -> (a.pd == b.pd),
          Derived.Snapshot(Derived.Which.Mark) -> (a.es.snapshots.mark == b.es.snapshots.mark),
          Derived.Snapshot(Derived.Which.Set) -> (a.es.snapshots.set == b.es.snapshots.set),
          Derived.Snapshot(Derived.Which.Go) -> (a.es.snapshots.go == b.es.snapshots.go),
          Derived.Extras -> (image.extras == again.value.extras)
        )
        checks.collectFirst { case (field, false) => field } match
          case Some(field) => fail(MirrorError.DerivedMismatch(field))
          case None        => monad.unit
      }
