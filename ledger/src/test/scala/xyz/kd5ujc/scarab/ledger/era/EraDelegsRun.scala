// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.{Functor, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Stateful, Tell}
import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Certificate,
  DelegFailure,
  LedgerDefect,
  PoolFailure,
  Pot,
  PredicateFailure,
  ProtocolParams,
  TestLedger,
  TxEnv
}
import xyz.kd5ujc.scarab.ledger.era.alonzo.{AlonzoPParams, AlonzoTestKit}
import xyz.kd5ujc.scarab.ledger.era.babbage.{BabbagePParams, BabbageTestKit}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  DELEGS,
  DelegEnv,
  DelegEvent,
  DelegPredFailure,
  DelegsEnv,
  DelplEvent,
  DelplPredFailure,
  EmbedDelplInDelegs,
  EmbedInDelpl,
  InjectPool,
  PoolEvent,
  PoolPredFailure,
  PoolRuleEvent,
  ShelleyDelegEvent,
  ShelleyDelegPredFailure,
  ShelleyDelegsEvent,
  ShelleyDelegsPredFailure,
  ShelleyDelplEvent,
  ShelleyDELEG,
  ShelleyDelplPredFailure,
  ShelleyPoolPredFailure
}
import xyz.kd5ujc.scarab.ledger.rules.{
  Convert,
  DelegsEnv as FlatDelegsEnv,
  Globals,
  Judge,
  Judgment,
  RuleT,
  ShelleyDelegs as FlatDelegs,
  focus
}
import xyz.kd5ujc.scarab.ledger.state.{
  Alonzo,
  Babbage,
  CertState,
  ChainAccountState,
  EpochNo,
  EraTag,
  ShelleyCertState,
  SlotNo,
  TxIx
}

/** Shelley's DELEGS on the era types (`DELEGS.delegsTransition`, cardano-ledger f649f975
  * `Shelley/Rules/Delegs.hs`) and the flat `ShelleyDelegs` on the same certificates and state, for
  * the suites that compare them (`ShelleyDelegsCases`, and `ShelleyDelegsCorpus` in the node), each
  * era's instances and the evidence that its families are Shelley's in one value ([[DelegsEra]]).
  */
object EraDelegsRun:
  /** An era whose certificate rules are Shelley's: its instances, the evidence that its families
    * reduce to Shelley's types, its parameters read from the node's and a transaction of the era,
    * which DELEGS's environment carries.
    */
  final case class DelegsEra[E <: EraTag](
      pparams: ProtocolParams => PParams[E],
      tx: () => Tx[TxLevel.Top, E],
      certState: CertState[E] =:= ShelleyCertState,
      delpl: DelplPredFailure[E] =:= ShelleyDelplPredFailure[E],
      deleg: DelegPredFailure[E] =:= ShelleyDelegPredFailure[E],
      pool: PoolPredFailure[E] =:= ShelleyPoolPredFailure[E],
      delplEvent: DelplEvent[E] =:= ShelleyDelplEvent[E],
      delegEvent: DelegEvent[E] =:= ShelleyDelegEvent[E],
      poolEvent: PoolRuleEvent[E] =:= PoolEvent[E]
  )(using
      val rep: IsShelleyTxCert[E],
      val p: EraPParams[E],
      val c: EraCertState[E],
      val a: ShelleyEraAccounts[E],
      val t: ShelleyEraTxCertToBabbage[E],
      val embed: EmbedDelplInDelegs[E],
      val embedDelpl: EmbedInDelpl[E],
      val inject: InjectPool[E]
  )

  object DelegsEra:
    given alonzo: DelegsEra[Alonzo] =
      DelegsEra[Alonzo](
        pp => PParams[Alonzo](AlonzoPParams.fromStored(pp).fold(e => sys.error(s"$e"), identity)),
        () =>
          AlonzoTestKit
            .encode(BabbageTestKit.flatBody(Vector(TestLedger.txIn(1)), Vector.empty, 0))
            .tx,
        summon,
        summon,
        summon,
        summon,
        summon,
        summon,
        summon
      )

    given babbage: DelegsEra[Babbage] =
      DelegsEra[Babbage](
        pp => PParams[Babbage](BabbagePParams.fromStored(pp).fold(e => sys.error(s"$e"), identity)),
        () =>
          BabbageTestKit
            .encode(BabbageTestKit.flatBody(Vector(TestLedger.txIn(1)), Vector.empty, 0))
            .tx,
        summon,
        summon,
        summon,
        summon,
        summon,
        summon,
        summon
      )

  type Rec[E <: EraTag] = ShelleyDelegsPredFailure[E]
  type Events[E <: EraTag] = Chain[ShelleyDelegsEvent[E]]

  private final class Run[E <: EraTag, R, Ev]:
    type S = (CertState[E], Chain[Ev])
    type F[A] = RuleT[R, LedgerDefect, S, A]
    import RuleT.given

    given Stateful[F, CertState[E]] =
      summon[Stateful[F, S]].focus(Lens[S, CertState[E]](_._1)(s => all => (s, all._2)))

    given Tell[F, Chain[Ev]] = new Tell[F, Chain[Ev]]:
      val functor: Functor[F] = summon[Monad[F]]
      def tell(events: Chain[Ev]): F[Unit] =
        summon[Stateful[F, S]].modify((s, e) => (s, e ++ events))

    /** `program` from `state`, `depth` levels below the root (LEDGER runs DELEGS one level down).
      */
    def run(globals: Globals, state: CertState[E], policy: RuleT.Policy, depth: Int = 0)(
        program: F[Unit]
    ): Either[LedgerDefect, Either[NonEmptyChain[R], (CertState[E], Chain[Ev])]] =
      val nested = (0 until depth).foldLeft(program)((fa, _) => summon[Judge[F, R]].nest(fa))
      Judgment.judge(nested, policy)(globals, (state, Chain.empty)).map(_.map(_._1))

  /** The era DELEGS on `certificates` from `state`: a defect, its failures in the reference's
    * order, or the state after with its events.
    */
  def era[E <: EraTag](
      globals: Globals,
      env: DelegsEnv[E],
      state: CertState[E],
      certificates: Vector[TxCert[E]],
      policy: RuleT.Policy,
      depth: Int = 0
  )(using
      d: DelegsEra[E]
  ): Either[LedgerDefect, Either[NonEmptyChain[Rec[E]], (CertState[E], Events[E])]] =
    val r = Run[E, Rec[E], ShelleyDelegsEvent[E]]
    import RuleT.given
    import r.given
    r.run(globals, state, policy, depth)(
      DELEGS.delegsTransition[E, r.F](env, certificates)(using
        summon[Monad[r.F]],
        d.rep,
        d.p,
        d.c,
        d.a,
        d.t,
        d.embed,
        d.embedDelpl,
        d.inject
      )
    )

  /** Shelley's DELEG alone on `certificate` from `state`, every failure accumulated. */
  def deleg[E <: EraTag](
      globals: Globals,
      env: DelegEnv[E],
      state: CertState[E],
      certificate: TxCert[E]
  )(using
      d: DelegsEra[E]
  ): Either[
    LedgerDefect,
    Either[NonEmptyChain[ShelleyDelegPredFailure[E]], (CertState[E], Chain[ShelleyDelegEvent[E]])]
  ] =
    val r = Run[E, ShelleyDelegPredFailure[E], ShelleyDelegEvent[E]]
    import RuleT.given
    import r.given
    r.run(globals, state, RuleT.Policy.Accumulate)(
      ShelleyDELEG.delegationTransition[E, r.F](env, certificate)(using
        summon[Monad[r.F]],
        d.c,
        d.a,
        d.t,
        d.p
      )
    )

  /** A judgment as the suites compare it: the failures in the reference's order, in the node's flat
    * form, or the state after.
    */
  enum Outcome:
    case Refused(failures: List[PredicateFailure])
    case Accepted(state: ShelleyCertState)

  /** What the era rules told, in the order told. */
  enum Told:
    case RegisterPool(pool: Bytes)
    case ReregisterPool(pool: Bytes)
    case NewEpoch(epoch: EpochNo)

  /** One judgment both ways: what the era rules and the flat rules came to, and what the era rules
    * told where they accepted.
    */
  final case class Both(era: Outcome, flat: Outcome, told: Option[Vector[Told]])

  /** The flat rules' context of `slot` in `epoch`, of the geometry the era rules ask (`Globals`).
    */
  def ctx(globals: Globals, slot: SlotNo, epoch: EpochNo, txIx: TxIx): TxEnv =
    TestLedger.env.copy(
      slot = slot.toBigInt,
      epoch = epoch.toBigInt,
      txIndex = txIx.index,
      network = globals.networkId.wire,
      firstSlotNextEpoch = globals.firstSlot(epoch.toBigInt + 1),
      stabilityWindow = globals.stabilityWindow,
      updateQuorum = globals.quorum
    )

  /** The flat DELEGS on `certificates`. */
  def flat(
      globals: Globals,
      ctx: TxEnv,
      pp: ProtocolParams,
      account: ChainAccountState,
      state: ShelleyCertState,
      certificates: Vector[Certificate],
      policy: RuleT.Policy
  ) =
    Judgment.run(FlatDelegs, policy)(
      globals,
      FlatDelegsEnv(ctx, pp, Bytes.empty, account),
      state,
      certificates
    )

  /** DELEGS both ways on `certificates` of the transaction at `txIx` in `slot`, from `state`, the
    * parameters `pp` and the chain's accounts `account`. A defect of either side fails the suite.
    */
  def both[E <: EraTag](
      globals: Globals,
      slot: SlotNo,
      epoch: EpochNo,
      txIx: TxIx,
      pp: ProtocolParams,
      account: ChainAccountState,
      state: ShelleyCertState,
      certificates: Vector[TxCert[E]],
      policy: RuleT.Policy = RuleT.Policy.Accumulate
  )(using d: DelegsEra[E]): Both =
    val env = DelegsEnv[E](slot, epoch, txIx, d.pparams(pp), d.tx(), account)
    val eraOutcome = era[E](globals, env, d.certState.flip(state), certificates, policy) match
      case Left(defect)          => munit.Assertions.fail(s"era defect $defect")
      case Right(Left(failures)) => (Outcome.Refused(failures.toList.map(flatOf[E])), None)
      case Right(Right((after, events))) =>
        (Outcome.Accepted(d.certState(after)), Some(events.toVector.map(toldOf[E])))
    val flatOutcome = flat(
      globals,
      ctx(globals, slot, epoch, txIx),
      pp,
      account,
      state,
      certificates.map(c => BabbageTestKit.flatCertRep(d.rep.shelleyTxCert(c))),
      policy
    ) match
      case Left(defect)          => munit.Assertions.fail(s"flat defect $defect")
      case Right(Left(failures)) => Outcome.Refused(failures.toList)
      case Right(Right(after))   => Outcome.Accepted(after)
    Both(eraOutcome._1, flatOutcome, eraOutcome._2)

  /** What DELEGS tells of `certificates` from a state whose pools are `pools`, in the epoch
    * `epoch`: a pool registered, or registered again (by the state or an earlier certificate), and
    * the next epoch for a MIR certificate.
    */
  def expectedTold[E <: EraTag](
      epoch: EpochNo,
      pools: Set[Bytes],
      certificates: Vector[TxCert[E]]
  )(using d: DelegsEra[E]): Vector[Told] =
    certificates
      .foldLeft((pools, Vector.empty[Told])) { case ((known, told), c) =>
        d.rep.shelleyTxCert(c) match
          case ShelleyTxCertRep.ShelleyTxCertPool(PoolCert.RegPool(params)) =>
            val id = params.id.bytes
            if known.contains(id) then (known, told :+ Told.ReregisterPool(id))
            else (known + id, told :+ Told.RegisterPool(id))
          case ShelleyTxCertRep.ShelleyTxCertMir(_) =>
            (known, told :+ Told.NewEpoch(EpochNo.fromUnsigned(epoch.word.bits + 1)))
          case _ => (known, told)
      }
      ._2

  private def toldOf[E <: EraTag](event: ShelleyDelegsEvent[E])(using d: DelegsEra[E]): Told =
    event match
      case ShelleyDelegsEvent.DelplEvent(e) =>
        d.delplEvent(e) match
          case ShelleyDelplEvent.PoolEvent(p) =>
            d.poolEvent(p) match
              case PoolEvent.RegisterPool(id)   => Told.RegisterPool(id.bytes)
              case PoolEvent.ReregisterPool(id) => Told.ReregisterPool(id.bytes)
          case ShelleyDelplEvent.DelegEvent(g) =>
            d.delegEvent(g) match
              case ShelleyDelegEvent.DelegNewEpoch(epoch) => Told.NewEpoch(epoch)

  /** The era DELEGS's failure in the node's flat form, which the flat encoder writes for the eras
    * before Conway: the same values, a hash as its bytes, a coin, an epoch or a slot as its
    * integer, a network as its wire byte.
    */
  def flatOf[E <: EraTag](f: Rec[E])(using d: DelegsEra[E]): PredicateFailure = f match
    case ShelleyDelegsPredFailure.DelplFailure(delpl) =>
      d.delpl(delpl) match
        case ShelleyDelplPredFailure.PoolFailure(p) => PredicateFailure.pool(flatPool(d.pool(p)))
        case ShelleyDelplPredFailure.DelegFailure(g) =>
          PredicateFailure.deleg(flatDeleg(d.deleg(g)))

  private def flatPot(pot: MIRPot): Pot = pot match
    case MIRPot.ReservesMIR => Pot.Reserves
    case MIRPot.TreasuryMIR => Pot.Treasury

  def flatDeleg[E <: EraTag](f: ShelleyDelegPredFailure[E]): DelegFailure =
    import ShelleyDelegPredFailure.*
    f match
      case StakeKeyAlreadyRegisteredDELEG(c) =>
        DelegFailure.StakeKeyAlreadyRegistered(Convert.back(c))
      case StakeKeyNotRegisteredDELEG(c) => DelegFailure.StakeKeyNotRegistered(Convert.back(c))
      case StakeKeyNonZeroAccountBalanceDELEG(coin) =>
        DelegFailure.StakeKeyHasNonZeroAccountBalance(coin.value)
      case StakeDelegationImpossibleDELEG(c) =>
        DelegFailure.StakeDelegationImpossible(Convert.back(c))
      case WrongCertificateTypeDELEG()      => DelegFailure.WrongCertificateType
      case GenesisKeyNotInMappingDELEG(g)   => DelegFailure.GenesisKeyNotInMapping(g.bytes)
      case DuplicateGenesisDelegateDELEG(k) => DelegFailure.DuplicateGenesisDelegate(k.bytes)
      case InsufficientForInstantaneousRewardsDELEG(pot, m) =>
        DelegFailure.InsufficientForInstantaneousRewards(flatPot(pot), m.storedBy(_.value))
      case MIRCertificateTooLateinEpochDELEG(m) =>
        DelegFailure.MIRCertificateTooLateinEpoch(m.storedBy(_.toBigInt))
      case DuplicateGenesisVRFDELEG(vrf)     => DelegFailure.DuplicateGenesisVRF(vrf.bytes)
      case MIRTransferNotCurrentlyAllowed()  => DelegFailure.MIRTransferNotCurrentlyAllowed
      case MIRNegativesNotCurrentlyAllowed() => DelegFailure.MIRNegativesNotCurrentlyAllowed
      case InsufficientForTransferDELEG(pot, m) =>
        DelegFailure.InsufficientForTransfer(flatPot(pot), m.storedBy(_.value))
      case MIRProducesNegativeUpdate()    => DelegFailure.MIRProducesNegativeUpdate
      case MIRNegativeTransfer(pot, c)    => DelegFailure.MIRNegativeTransfer(flatPot(pot), c.value)
      case DelegateeNotRegisteredDELEG(p) => DelegFailure.DelegateeNotRegistered(p.bytes)

  def flatPool[E <: EraTag](f: ShelleyPoolPredFailure[E]): PoolFailure = f match
    case ShelleyPoolPredFailure.StakePoolNotRegisteredOnKeyPOOL(p) =>
      PoolFailure.StakePoolNotRegisteredOnKey(p.bytes)
    case ShelleyPoolPredFailure.StakePoolRetirementWrongEpochPOOL(current, latest) =>
      PoolFailure.StakePoolRetirementWrongEpoch(
        current.storedBy(_.toBigInt),
        latest.storedBy(_.toBigInt)
      )
    case ShelleyPoolPredFailure.StakePoolCostTooLowPOOL(m) =>
      PoolFailure.StakePoolCostTooLow(m.storedBy(_.value))
    case ShelleyPoolPredFailure.WrongNetworkPOOL(m, p) =>
      PoolFailure.WrongNetworkPool(m.storedBy(_.wire), p.bytes)
    case ShelleyPoolPredFailure.PoolMedataHashTooBig(p, size) =>
      PoolFailure.PoolMedataHashTooBig(p.bytes, size)
    case ShelleyPoolPredFailure.VRFKeyHashAlreadyRegistered(p, v) =>
      PoolFailure.VRFKeyHashAlreadyRegistered(p.bytes, v.bytes)
