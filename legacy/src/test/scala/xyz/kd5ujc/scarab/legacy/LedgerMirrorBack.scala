// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import cats.{Eq, Monad}
import cats.data.Kleisli
import cats.mtl.Handle.{handleForApplicativeError, handleKleisli}
import cats.mtl.{Ask, Raise}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.state.{GovState as _, LedgerState as _, *}
import xyz.kd5ujc.scarab.ledger.state.{GovState, LedgerState}
import xyz.kd5ujc.scarab.legacy as flat

/** TEST SUPPORT of the frozen flat conversion: the node's flat ledger state of a [[LedgerImage]],
  * the direction `LedgerMirror.from` had until J2.9. The production code has only `to` (the older
  * snapshots are brought to the mirror and nothing goes back); the suites that run the era-indexed
  * rules on fixtures written in the flat state ([[RuleSet]]) read their results through this, so
  * that their assertions are written once over the flat state. It goes with the module (design
  * `ledger-mirror-step2.md` J2.9).
  */
object LedgerMirrorBack:
  private type Read[A] = Either[MirrorError, A]
  private type Run[A] = Kleisli[Read, MirrorEnv, A]
  private val runner: LedgerMirror[Run] = LedgerMirror[Run]

  /** The flat ledger state a new-epoch state stands for, as the old rules would have held it: the
    * state the rules made is read back whole, whatever the derived pieces the flat state cannot say
    * (the pulser, the attributed rewards) differ in, with the phase-2 counters `plutus`.
    */
  def flatOf[E <: EraTag](
      env: MirrorEnv,
      at: WithOrigin[SlotNo],
      nes: NewEpochState[E],
      plutus: node.PlutusStats
  )(using ShelleyBasedEra[E]): Either[MirrorError, flat.LedgerState] =
    runner
      .rebuilt[E](at, LedgerImage[E](nes, flat.NodeExtras.empty.copy(plutus = plutus)))
      .run(env)
      .map(_.state)

  private def isConway[E <: EraTag](using era: ShelleyBasedEra[E]): Boolean = era match
    case ShelleyBasedEra.SConway => true
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      false

  /** What a state becomes through the mirror, on the node's `network`: the future genesis
    * delegations are a map in Haskell, ordered by slot and key and with one entry of each, and a
    * vector with no order in the node; nothing reads their order. The reward account of a
    * registered pool, and of a pool in a stake snapshot, is on the node's network
    * ([[node.PoolParams.registeredOn]]): Haskell keeps its credential only, as the rules read it.
    * The future parameters of a pool keep theirs.
    */
  def normalised(network: Int, s: flat.LedgerState): flat.LedgerState =
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
        mark = LedgerMirror.poolsOn(network, s.snapshots.mark),
        set = LedgerMirror.poolsOn(network, s.snapshots.set),
        go = LedgerMirror.poolsOn(network, s.snapshots.go)
      ),
      poolDistribution = LedgerMirror.poolsOn(network, s.poolDistribution)
    )

  // ---- the conversions of the leaf types that need no effect

  def credBack[R <: KeyRole](c: Credential[R]): node.Credential = c match
    case Credential.ScriptHashObj(h) => node.Credential.Script(h.bytes)
    case Credential.KeyHashObj(h)    => node.Credential.Key(h.bytes)

  def drepBack(d: DRep): node.DRep = d match
    case DRep.DRepKeyHash(h)         => node.DRep.Registered(node.Credential.Key(h.bytes))
    case DRep.DRepScriptHash(h)      => node.DRep.Registered(node.Credential.Script(h.bytes))
    case DRep.DRepAlwaysAbstain      => node.DRep.AlwaysAbstain
    case DRep.DRepAlwaysNoConfidence => node.DRep.AlwaysNoConfidence

  def stakeMap[R <: KeyRole, A, B](m: SortedMap[Credential[R], A])(
      f: A => B
  ): Map[node.Credential, B] =
    m.iterator.map((c, a) => credBack(c) -> f(a)).toMap

  def coinMap(
      m: SortedMap[Credential[Staking], Coin]
  ): Map[node.Credential, BigInt] =
    stakeMap(m)(_.value)

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

  def conwayAccount(a: ConwayAccountState): node.Account =
    node.Account(
      a.balance.toBigInt,
      a.deposit.toBigInt,
      a.pool.map(_.bytes),
      a.drep.map(drepBack)
    )

  def shelleyAccount(a: ShelleyAccountState): node.Account =
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
      conway: Option[(flat.GovState, DRComplete)],
      ppup: Option[flat.Ppup]
  )

  private def govParts[E <: EraTag](g: GovState[E])(using era: ShelleyBasedEra[E]): GovParts =
    era match
      case ShelleyBasedEra.SConway =>
        GovParts(
          g.curPParams,
          g.prevPParams,
          Some((flat.GovState(g.proposals, g.committee, g.constitution, None), g.drepPulsing)),
          None
        )
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        GovParts(
          g.curPParams,
          g.prevPParams,
          None,
          Some(
            flat.Ppup(
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

  def pools(
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
      residue: Map[Bytes, flat.PoolResidue]
  ): flat.Snapshot =
    val staked = s.activeStake.entries
    val counts = staked.valuesIterator.toVector.groupMapReduce(_.delegation)(_ => 1)(_ + _)
    flat.Snapshot(
      stakeMap(staked)(_.stake.value.toBigInt),
      stakeMap(staked)(_.delegation.bytes),
      s.pools.iterator.map { (id, p) =>
        val extra = residue.getOrElse(
          id.bytes,
          flat.PoolResidue(p.selfDelegatedOwners.iterator.map(_.bytes).toSet, Bytes.empty, None)
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

  extension [F[_]](mirror: LedgerMirror[F])(using
      monad: Monad[F],
      asks: Ask[F, MirrorEnv],
      errors: Raise[F, MirrorError]
  )
    private def fail[A](e: MirrorError): F[A] = errors.raise(e)

    def from[E <: EraTag](at: WithOrigin[SlotNo], image: LedgerImage[E])(using
        era: ShelleyBasedEra[E]
    ): F[flat.LedgerState] =
      rebuilt[E](at, image).flatMap(r =>
        r.differing.headOption.fold(monad.pure(r.state))(field =>
          fail[flat.LedgerState](MirrorError.DerivedMismatch(field))
        )
      )

    def rebuilt[E <: EraTag](at: WithOrigin[SlotNo], image: LedgerImage[E])(using
        era: ShelleyBasedEra[E]
    ): F[Rebuilt] =
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
        val rewards: Option[flat.RewardUpdate] = nes.ru match
          case None => None
          case Some(PulsingRewUpdate.Complete(u)) =>
            Some(
              flat.RewardUpdate(
                u.deltaT.value,
                u.deltaR.value,
                if u.rs.isEmpty then x.rewardAmounts
                else
                  // what each credential is paid (`aggregateRewards`, `Rewards.hs:170-186`): before
                  // Allegra one that earns from several sources keeps the least of them, the leader
                  // reward
                  stakeMap(u.rs)(set =>
                    (if gov.prev.protocolMajor >= 3 then set.rewards else set.rewards.take(1))
                      .foldLeft(BigInt(0))(_ + _.rewardAmount.value)
                  )
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
        val prev = flat.ConwayEpoch.heldPrevious(
          x.params.prev.restore(gov.prev),
          cur,
          beforeBoundary
        )
        val pulsed =
          if x.pulserAbsent then None
          else
            gov.conway.map((_, d) =>
              (d.snapshot, flat.ConwayEpoch.heldRatify(d.ratify, cur, beforeBoundary))
            )
        val rebuilt = flat.LedgerState(
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
          snapshots = flat.Snapshots(
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
          mir = flat.InstantRewards(
            coinMap(cert.iRewards.reserves),
            coinMap(cert.iRewards.treasury),
            cert.iRewards.deltaReserves.value,
            cert.iRewards.deltaTreasury.value
          ),
          ptrs = node.PtrMap(if conway then x.residue.ptrs else cert.ptrs),
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
        if pulsing then fail[Rebuilt](MirrorError.IllFormed(Shape.PulsingRewardUpdate))
        else differing(at, image, rebuilt).map(Rebuilt(rebuilt, _))
      }

    /** The mirror of the rebuilt state should be the mirror it was rebuilt from, in what is
      * derived: the pieces that are not, in the order of [[Derived]]'s checks.
      */
    private def differing[E <: EraTag](
        at: WithOrigin[SlotNo],
        image: LedgerImage[E],
        rebuilt: flat.LedgerState
    )(using era: ShelleyBasedEra[E]): F[List[Derived]] =
      mirror.to[E](at, rebuilt).flatMap { again =>
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
        monad.pure(checks.collect { case (field, false) => field })
      }

final case class Rebuilt(state: flat.LedgerState, differing: List[Derived])
