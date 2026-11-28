// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Applicative, Monad}
import cats.data.{Chain, NonEmptyList}
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{LedgerDefect, TranslationError, TxEnv}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoContextError,
  AlonzoEraTx,
  AlonzoEraUTxO,
  CollectError,
  ContextError,
  EraCertState,
  EraPParamsToBabbage,
  IsPhase2Valid,
  PParams,
  Phase2Deferral,
  PlutusEvaluation,
  PlutusWithContext,
  ScriptFailure,
  ScriptResult,
  ScriptVerdictUnavailable,
  ShelleyEraTxBody,
  ShelleyGovState,
  StAnnTx,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, Sts, ValidationPolicy}
import xyz.kd5ujc.scarab.ledger.state.{Alonzo, Babbage, CertState, Conway, Dijkstra, EraTag, SlotNo}
import xyz.kd5ujc.scarab.plutus.{EvalFailure, ScriptEvaluator}

// UTXOS as the reference writes it (cardano-ledger f649f975, the `Rules/Utxos.hs` of Alonzo, Babbage
// and Conway): the evaluation of a transaction's Plutus scripts, whose verdict must be the one its
// `isValid` flag announces. Each object is the module of the era that defines its functions, each
// function has the reference's name and asks for the reference's classes, and the failures and events
// are the reference's, each era's own type with its injections. Conway's UTXOS has no environment and
// no state, so its transition is a function of the annotated transaction, the validation policy and
// the capabilities it uses: the judgment it records into, the events it tells and the evaluator.

/** `data FailureDescription` (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Rules/Utxos.hs:259-261`,
  * cardano-ledger f649f975). The reference's `PlutusFailure` holds the text `ScriptFailure`
  * explains the failure with and the script with its context serialised in Base64; here it holds
  * the evaluator's failure and the script with its context as they are, which those two are written
  * from.
  */
enum FailureDescription:
  case PlutusFailure(cause: EvalFailure, plutus: PlutusWithContext)

object FailureDescription:
  /** `scriptFailureToFailureDescription` (`Alonzo/Rules/Utxos.hs:288-290`, cardano-ledger
    * f649f975).
    */
  def scriptFailureToFailureDescription(sf: ScriptFailure): FailureDescription =
    PlutusFailure(sf.scriptFailureCause, sf.scriptFailurePlutus)

/** `data TagMismatchDescription` (`Alonzo/Rules/Utxos.hs:292-295`, cardano-ledger f649f975): the
  * scripts passed though the flag says they fail, or they failed, with each failure, though it says
  * they pass.
  */
enum TagMismatchDescription:
  case PassedUnexpectedly
  case FailedUnexpectedly(failures: NonEmptyList[FailureDescription])

/** `EraRuleFailure "PPUP" era` (`Core/Era.hs`, cardano-ledger f649f975), of the eras whose UTXOS is
  * typed: Alonzo's and Babbage's are Shelley's (`type instance EraRuleFailure "PPUP" AlonzoEra =
  * ShelleyPpupPredFailure AlonzoEra`, `Alonzo/Rules/Ppup.hs:12`; `type instance EraRuleFailure
  * "PPUP" BabbageEra = ShelleyPpupPredFailure BabbageEra`, `Babbage/Rules/Ppup.hs:12`); Conway has
  * no PPUP, its failure is `VoidEraRule` (`Conway/Era.hs`), no value, and neither has Dijkstra
  * (`Dijkstra/Era.hs:86-90`, cardano-ledger 9df9f98c).
  */
type PpupPredFailure[E <: EraTag] = E match
  case Alonzo   => ShelleyPpupPredFailure[Alonzo]
  case Babbage  => ShelleyPpupPredFailure[Babbage]
  case Conway   => Nothing
  case Dijkstra => Nothing

/** `EraRuleEvent "PPUP" era`, as [[PpupPredFailure]]: Alonzo's and Babbage's are Shelley's
  * `PpupEvent` ([[ShelleyPpupEvent]]), the event of their PPUP (`type instance EraRuleEvent "PPUP"
  * AlonzoEra = Shelley.PpupEvent AlonzoEra`, `Alonzo/Rules/Ppup.hs:14`; `EraRule "PPUP" BabbageEra
  * = Shelley.PPUP BabbageEra`, `Babbage/Era.hs:106`, cardano-ledger f649f975); Conway's and
  * Dijkstra's are `VoidEraRule`.
  */
type PpupEvent[E <: EraTag] = E match
  case Alonzo   => ShelleyPpupEvent[Alonzo]
  case Babbage  => ShelleyPpupEvent[Babbage]
  case Conway   => Nothing
  case Dijkstra => Nothing

/** `data AlonzoUtxosPredFailure era` (`Alonzo/Rules/Utxos.hs:323-336`, cardano-ledger f649f975):
  * the failures Alonzo's and Babbage's UTXOS functions report, which an era injects into its own
  * ([[InjectAlonzoUtxos]]).
  */
enum AlonzoUtxosPredFailure[E <: EraTag]:
  /** The flag `isValid` (the transaction's), and how the scripts disagreed with it. */
  case ValidationTagMismatch(isValid: IsPhase2Valid, description: TagMismatchDescription)

  /** The scripts could not be collected with their contexts. */
  case CollectErrors(errors: NonEmptyList[CollectError[E]])

  /** PPUP's failure, under UTXOS from Alonzo to Babbage. */
  case UpdateFailure(failure: PpupPredFailure[E])

/** `data AlonzoUtxosEvent era` (`Alonzo/Rules/Utxos.hs:125-129`, cardano-ledger f649f975). */
enum AlonzoUtxosEvent[E <: EraTag]:
  case AlonzoPpupToUtxosEvent(event: PpupEvent[E])
  case SuccessfulPlutusScriptsEvent(scripts: NonEmptyList[PlutusWithContext])
  case FailedPlutusScriptsEvent(scripts: NonEmptyList[PlutusWithContext])

/** `data ConwayUtxosPredFailure era` (`eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Utxos.hs:
  * 53-65`, cardano-ledger f649f975): Alonzo's without PPUP's.
  */
enum ConwayUtxosPredFailure[E <: EraTag]:
  case ValidationTagMismatch(isValid: IsPhase2Valid, description: TagMismatchDescription)
  case CollectErrors(errors: NonEmptyList[CollectError[E]])

/** `data ConwayUtxosEvent era` (`Conway/Rules/Utxos.hs:67-70`, cardano-ledger f649f975). */
enum ConwayUtxosEvent[E <: EraTag]:
  case SuccessfulPlutusScriptsEvent(scripts: NonEmptyList[PlutusWithContext])
  case FailedPlutusScriptsEvent(scripts: NonEmptyList[PlutusWithContext])

/** `EraRuleFailure "UTXOS" era`: Alonzo's is its own (`Alonzo/Rules/Utxos.hs:338`, cardano-ledger
  * f649f975), Babbage's is Alonzo's (`Babbage/Rules/Utxos.hs:46`, cardano-ledger f649f975),
  * Conway's is its own (`Conway/Rules/Utxos.hs:76`, cardano-ledger f649f975), and Dijkstra's is
  * Conway's, as its UTXOS is (`Dijkstra/Rules/Utxos.hs:18`, cardano-ledger 9df9f98c). Each era adds
  * its case with its rule.
  */
type UtxosPredFailure[E <: EraTag] = E match
  case Alonzo   => AlonzoUtxosPredFailure[Alonzo]
  case Babbage  => AlonzoUtxosPredFailure[Babbage]
  case Conway   => ConwayUtxosPredFailure[Conway]
  case Dijkstra => ConwayUtxosPredFailure[Dijkstra]

/** `EraRuleEvent "UTXOS" era`: Alonzo's is the event of its UTXOS (`type Event (UTXOS era) =
  * AlonzoUtxosEvent era`, `Alonzo/Rules/Utxos.hs:115`, cardano-ledger f649f975, which has no
  * `EraRuleEvent` instance at the pin), Babbage's is Alonzo's (`Babbage/Rules/Utxos.hs:48`,
  * cardano-ledger f649f975), Conway's is its own (`Conway/Rules/Utxos.hs:78`, cardano-ledger
  * f649f975), and Dijkstra's is Conway's (`Dijkstra/Rules/Utxos.hs:20`, cardano-ledger 9df9f98c).
  */
type UtxosEvent[E <: EraTag] = E match
  case Alonzo   => AlonzoUtxosEvent[Alonzo]
  case Babbage  => AlonzoUtxosEvent[Babbage]
  case Conway   => ConwayUtxosEvent[Conway]
  case Dijkstra => ConwayUtxosEvent[Dijkstra]

/** `InjectRuleFailure "UTXOS" AlonzoUtxosPredFailure era` and `InjectRuleEvent "UTXOS"
  * AlonzoUtxosEvent era` (`Core/Era.hs`, cardano-ledger f649f975): how era `E` reports the failures
  * and events of the UTXOS functions it shares with Alonzo and Babbage. One instance per era, in
  * the companion.
  */
trait InjectAlonzoUtxos[E <: EraTag]:
  def injectFailure(failure: AlonzoUtxosPredFailure[E]): UtxosPredFailure[E]
  def injectEvent(event: AlonzoUtxosEvent[E]): UtxosEvent[E]

object InjectAlonzoUtxos:
  /** Alonzo's, whose UTXOS failures and events are its own (`instance InjectRuleFailure "UTXOS"
    * AlonzoUtxosPredFailure AlonzoEra`, the class's default, `Alonzo/Rules/Utxos.hs:340`,
    * cardano-ledger f649f975; its UTXOS tells its events as they are, `:216, 240-241`): each as it
    * is.
    */
  given alonzo: InjectAlonzoUtxos[Alonzo] with
    def injectFailure(failure: AlonzoUtxosPredFailure[Alonzo]): UtxosPredFailure[Alonzo] = failure
    def injectEvent(event: AlonzoUtxosEvent[Alonzo]): UtxosEvent[Alonzo] = event

  /** Babbage's, whose UTXOS failures and events are Alonzo's (`instance InjectRuleFailure "UTXOS"
    * AlonzoUtxosPredFailure BabbageEra` and its event's, `Babbage/Rules/Utxos.hs:50-52`,
    * cardano-ledger f649f975, the class's default): each as it is.
    */
  given babbage: InjectAlonzoUtxos[Babbage] with
    def injectFailure(failure: AlonzoUtxosPredFailure[Babbage]): UtxosPredFailure[Babbage] =
      failure
    def injectEvent(event: AlonzoUtxosEvent[Babbage]): UtxosEvent[Babbage] = event

  /** Conway's (`Conway/Rules/Utxos.hs:84-88`, cardano-ledger f649f975). */
  given conway: InjectAlonzoUtxos[Conway] with
    def injectFailure(failure: AlonzoUtxosPredFailure[Conway]): UtxosPredFailure[Conway] =
      ConwayUtxos.alonzoToConwayUtxosPredFailure[Conway](failure)
    def injectEvent(event: AlonzoUtxosEvent[Conway]): UtxosEvent[Conway] =
      ConwayUtxos.alonzoToConwayUtxosEvent[Conway](event)

  /** Dijkstra's (`Dijkstra/Rules/Utxos.hs:26-30`, cardano-ledger 9df9f98c): Conway's functions. */
  given dijkstra: InjectAlonzoUtxos[Dijkstra] with
    def injectFailure(failure: AlonzoUtxosPredFailure[Dijkstra]): UtxosPredFailure[Dijkstra] =
      ConwayUtxos.alonzoToConwayUtxosPredFailure[Dijkstra](failure)
    def injectEvent(event: AlonzoUtxosEvent[Dijkstra]): UtxosEvent[Dijkstra] =
      ConwayUtxos.alonzoToConwayUtxosEvent[Dijkstra](event)

/** `Embed (EraRule "PPUP" era) (UTXOS era)` (`Alonzo/Rules/Utxos.hs:135-145`,
  * `Babbage/Rules/Utxos.hs:93-102`, cardano-ledger f649f975): how era `E` 's UTXOS reports PPUP's
  * failures and events, the eras whose UTXOS runs Shelley's PPUP. One instance per era, in the
  * companion.
  */
trait EmbedPpupInUtxos[E <: EraTag]:
  def wrapFailed(failure: ShelleyPpupPredFailure[E]): UtxosPredFailure[E]
  def wrapEvent(event: ShelleyPpupEvent[E]): UtxosEvent[E]

object EmbedPpupInUtxos:
  /** Alonzo's: `wrapFailed = UpdateFailure`, `wrapEvent = AlonzoPpupToUtxosEvent`
    * (`Alonzo/Rules/Utxos.hs:144-145`, cardano-ledger f649f975).
    */
  given alonzo: EmbedPpupInUtxos[Alonzo] with
    def wrapFailed(failure: ShelleyPpupPredFailure[Alonzo]): UtxosPredFailure[Alonzo] =
      AlonzoUtxosPredFailure.UpdateFailure(failure)
    def wrapEvent(event: ShelleyPpupEvent[Alonzo]): UtxosEvent[Alonzo] =
      AlonzoUtxosEvent.AlonzoPpupToUtxosEvent(event)

  /** Babbage's: `wrapFailed = Alonzo.UpdateFailure`, `wrapEvent = Alonzo.AlonzoPpupToUtxosEvent`.
    */
  given babbage: EmbedPpupInUtxos[Babbage] with
    def wrapFailed(failure: ShelleyPpupPredFailure[Babbage]): UtxosPredFailure[Babbage] =
      AlonzoUtxosPredFailure.UpdateFailure(failure)
    def wrapEvent(event: ShelleyPpupEvent[Babbage]): UtxosEvent[Babbage] =
      AlonzoUtxosEvent.AlonzoPpupToUtxosEvent(event)

/** `data UtxosEnv era = UtxosEnv {ueSlot, uePParams, ueCertState}`
  * (`Alonzo/Rules/Utxos.hs:118-122`, cardano-ledger f649f975): the environment of the UTXOS of
  * Alonzo and Babbage, whose PPUP reads it, its slot a `SlotNo` as the reference's is.
  */
final case class UtxosEnv[E <: EraTag](slot: SlotNo, pp: PParams[E], certState: CertState[E])

/** Where a failure to translate a transaction to an Alonzo script context comes from, for Alonzo's
  * `scriptsTransition`, which does not record the reference's (#1534).
  */
enum TranslationOrigin:
  /** A failure of Alonzo's `ContextError` at the pin (`data AlonzoContextError`,
    * `Alonzo/Plutus/TxInfo.hs:213-216`, cardano-ledger f649f975): an input the UTxO set does not
    * hold (`TranslationLogicMissingInput`), or a slot past the forecast horizon
    * (`TimeTranslationPastHorizon`).
    */
  case Reference

  /** A failure only the node gives: its clock with no era history or no sample of the slot, data it
    * reads lazily, and the cases of other eras' and builds' errors, none of which Alonzo's error at
    * the pin has.
    */
  case Node

object TranslationOrigin:
  /** Alonzo's context error by origin. An unsupported certificate or purpose is Alonzo's error at
    * 9df9f98c only (Conway's own at f649f975), the impossible context 9df9f98c's, and an
    * undecodable datum the node's.
    */
  def ofAlonzo[E <: EraTag](error: AlonzoContextError[E]): TranslationOrigin = error match
    case AlonzoContextError.TranslationLogicMissingInput(_)   => Reference
    case AlonzoContextError.TimeTranslationPastHorizon(cause) => ofTime(cause)
    case AlonzoContextError.CertificateNotSupported(_) |
        AlonzoContextError.PlutusPurposeNotSupported(_) | AlonzoContextError.Undecodable(_, _) |
        AlonzoContextError.ImpossibleContextError(_) =>
      Node

  /** The cause of a time translation by origin: a slot past the horizon is the reference's
    * (`epochInfoSlotToUTCTime`'s failure); every other case is the node's, each named so that a new
    * one is classified.
    */
  def ofTime(cause: TranslationError): TranslationOrigin = cause match
    case TranslationError.PastHorizon(_, _) => Reference
    case TranslationError.NoEraHistory | TranslationError.NoClockSample(_) |
        TranslationError.RedeemerPointerPointsToNothing(_, _) |
        TranslationError.UnresolvedInput(_) | TranslationError.InlineDatumsNotSupported(_) |
        TranslationError.ReferenceScriptsNotSupported(_) |
        TranslationError.ReferenceInputsNotSupported(_) |
        TranslationError.ReferenceInputsNotDisjointFromInputs(_) |
        TranslationError.ByronTxOutInContext(_) | TranslationError.CertificateNotSupported(_) |
        TranslationError.PlutusPurposeNotSupported(_) |
        TranslationError.DelegationWithoutDelegatee |
        TranslationError.VotingProceduresFieldNotSupported(_) |
        TranslationError.ProposalProceduresFieldNotSupported(_) |
        TranslationError.TreasuryDonationFieldNotSupported(_) |
        TranslationError.CurrentTreasuryFieldNotSupported(_) | TranslationError.Undecodable(_) |
        TranslationError.NoScriptBytes(_) | TranslationError.NativeScriptRedeemer |
        TranslationError.LanguageWithoutContext(_) | TranslationError.CostModelParameterTooLarge =>
      Node

/** Alonzo's functions of UTXOS (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Rules/Utxos.hs`,
  * cardano-ledger f649f975).
  */
object AlonzoUtxos:
  /** `when2Phase` (`Alonzo/Rules/Utxos.hs:420-421`, cardano-ledger f649f975): `fa` labelled static
    * and 2-phase, so it runs only under the policy that validates every check
    * ([[xyz.kd5ujc.scarab.ledger.TxEnv.Validation]]); a transaction applied again (`reapplyTx`)
    * skips it.
    */
  def when2Phase[F[_]: Applicative](validation: TxEnv.Validation)(fa: => F[Unit]): F[Unit] =
    validation match
      case TxEnv.Validation.All       => fa
      case TxEnv.Validation.NonStatic => Applicative[F].unit

  /** `when2Phase` as the judgment interprets a labelled clause (`runClause (Label ...)`,
    * `libs/small-steps/src/Control/State/Transition/Extended.hs:709-712, 727-730`, cardano-ledger
    * f649f975): under `ValidateNone` no label is run, so `fa` is skipped whatever `validation`
    * says; under `ValidateAll` it is [[when2Phase]].
    */
  private def judged2Phase[F[_]: Applicative, Rec](validation: TxEnv.Validation)(fa: => F[Unit])(
      using judge: Judge[F, Rec]
  ): F[Unit] = judge.policy match
    case ValidationPolicy.ValidateAll  => when2Phase[F](validation)(fa)
    case ValidationPolicy.ValidateNone => Applicative[F].unit

  /** The event of the scripts that passed, if any did (`mapM_ (tellEvent ...) (nonEmpty ps)`). */
  private def tellPassed[F[_]: Applicative, E <: EraTag](passed: List[PlutusWithContext])(using
      inject: InjectAlonzoUtxos[E],
      events: Tell[F, Chain[UtxosEvent[E]]]
  ): F[Unit] =
    NonEmptyList
      .fromList(passed)
      .traverse_(ps =>
        events.tell(
          Chain.one(inject.injectEvent(AlonzoUtxosEvent.SuccessfulPlutusScriptsEvent(ps)))
        )
      )

  /** PPUP as a sub-rule (`trans @(EraRule "PPUP" era)`) on the body's update proposal, with the
    * slot, the parameters and the genesis delegations of the certificate state (`certDStateL .
    * dsGenDelegsL`), its failures and events embedded ([[EmbedPpupInUtxos]]), the last step of
    * `alonzoEvalScriptsTxValid` (`Alonzo/Rules/Utxos.hs:220-221`, cardano-ledger f649f975). The
    * proposal is read with the bytes the node keeps it by ([[KeptUpdate]], the body's
    * [[ShelleyEraTxBody.keptUpdate]]).
    */
  private def transPpup[F[_]: Monad, E <: EraTag](
      env: UtxosEnv[E],
      stAnnTx: StAnnTx[TxLevel.Top, E]
  )(using
      t: AlonzoEraTx[E],
      b: ShelleyEraTxBody[E],
      c: EraCertState[E],
      p: EraPParamsToBabbage[E],
      embed: EmbedPpupInUtxos[E],
      ask: Ask[F, Globals],
      state: Stateful[F, ShelleyGovState[E]],
      judge: Judge[F, UtxosPredFailure[E]],
      events: Tell[F, Chain[UtxosEvent[E]]],
      defect: Raise[F, LedgerDefect]
  ): F[Unit] =
    val tx = t.txStAnnTx[TxLevel.Top](stAnnTx)(using t.bodyOf.top)
    val genDelegs = c.certDStateL.get(env.certState).genDelegs
    given Judge[F, ShelleyPpupPredFailure[E]] = judge.contramap(embed.wrapFailed)
    given Tell[F, Chain[ShelleyPpupEvent[E]]] = new Tell[F, Chain[ShelleyPpupEvent[E]]]:
      val functor: cats.Functor[F] = events.functor
      def tell(l: Chain[ShelleyPpupEvent[E]]): F[Unit] = events.tell(l.map(embed.wrapEvent))
    judge.nest(
      ShelleyPpup.ppupTransitionNonEmpty[F, E](
        PpupEnv[E](env.slot, env.pp, genDelegs),
        b.keptUpdate(t.body[TxLevel.Top](tx)(using t.bodyOf.top))
      )
    )

  /** `isNotBadTranslation` (`Alonzo/Rules/Utxos.hs:185-187`, cardano-ledger f649f975): a failure of
    * the collection that is not a translation's the reference can give (`BadTranslation` of
    * Alonzo's `ContextError`, [[TranslationOrigin.Reference]]). A translation failure only the node
    * gives ([[TranslationOrigin.Node]], a clock with no era history among them) is kept, as
    * Babbage's rule keeps every one, so that a missing clock refuses the transaction and never
    * skips its scripts (#1534). The two differ only on causes no input of the reference reaches.
    */
  def isNotBadTranslation[E <: EraTag](error: CollectError[E])(using
      alonzoContext: ContextError[E] =:= AlonzoContextError[E]
  ): Boolean = error match
    case CollectError.BadTranslation(e) =>
      TranslationOrigin.ofAlonzo[E](alonzoContext(e)) match
        case TranslationOrigin.Reference => false
        case TranslationOrigin.Node      => true
    case CollectError.NoRedeemer(_) | CollectError.NoWitness(_) | CollectError.NoCostModel(_) =>
      true

  /** `scriptsTransition` (`Alonzo/Rules/Utxos.hs:168-187`, cardano-ledger f649f975): the failures
    * of the collection of the transaction's scripts with their contexts recorded (`failOnNonEmpty`,
    * `CollectErrors`) but a translation's the reference can give, which Alonzo does not record
    * ("BadTranslation was introduced in Babbage"; a failure only the node gives, such as a missing
    * clock, is recorded, [[TranslationOrigin]], #1534), and `action` on the scripts' evaluation
    * only under the policy that validates every check (`when2Phase`, skipped too by a judgment of
    * `ValidateNone`, which runs no labelled clause) and only if nothing failed so far in the
    * judgment (`whenFailureFree`). A collection whose failures are all a translation's the
    * reference can give records nothing and evaluates nothing. Where the scripts are evaluated is
    * the run's ([[Phase2Deferral]]), as in Babbage's functions; a transaction whose collection's
    * failures are recorded is told to the run with them before they are recorded
    * ([[Phase2Deferral.uncollected]]), which the reference has no counterpart for.
    */
  def scriptsTransition[F[_]: Monad, E <: EraTag](
      stAnnTx: StAnnTx[TxLevel.Top, E],
      validation: TxEnv.Validation
  )(action: ScriptResult => F[Unit])(using
      u: AlonzoEraUTxO[E],
      inject: InjectAlonzoUtxos[E],
      judge: Judge[F, UtxosPredFailure[E]],
      evaluator: ScriptEvaluator[F],
      raise: Raise[F, ScriptVerdictUnavailable],
      deferral: Phase2Deferral[F, E],
      alonzoContext: ContextError[E] =:= AlonzoContextError[E]
  ): F[Unit] =
    // collected only for the predicate or the evaluation, neither run under `ValidateNone`
    lazy val scriptsWithContextEither =
      u.plutusScriptsWithContextStAnnTx[TxLevel.Top](stAnnTx)(using u.txOf.bodyOf.top)
    val tx = u.txOf.txStAnnTx[TxLevel.Top](stAnnTx)(using u.txOf.bodyOf.top)
    for
      _ <- Sts.validating[F, UtxosPredFailure[E]](
        scriptsWithContextEither.left.toOption
          .flatMap(errors => NonEmptyList.fromList(errors.filter(isNotBadTranslation[E])))
          .traverse_(errors =>
            deferral.uncollected(tx, errors) *>
              judge.fail(inject.injectFailure(AlonzoUtxosPredFailure.CollectErrors(errors)))
          )
      )
      _ <- judged2Phase[F, UtxosPredFailure[E]](validation)(
        Sts.whenFailureFree[F, UtxosPredFailure[E]](
          scriptsWithContextEither.toOption.traverse_ { scriptsWithContext =>
            val now = PlutusEvaluation.evalPlutusScripts[F](scriptsWithContext).flatMap(action)
            NonEmptyList
              .fromList(scriptsWithContext)
              .fold(now)(deferral.evalScripts(stAnnTx, _)(now))
          }
        )
      )
    yield ()

  /** `utxosTransition` (`Alonzo/Rules/Utxos.hs:147-164`, cardano-ledger f649f975): by the
    * transaction's flag, the scripts expected to pass and then PPUP (`alonzoEvalScriptsTxValid`),
    * or the scripts expected to fail (`alonzoEvalScriptsTxInvalid`), the governance state then left
    * as it was. The state is the governance state ([[ShelleyGovState]]), the only part of the UTxO
    * state UTXOS changes; UTXO changes the rest (`updateUTxOState`,
    * [[AlonzoUtxo.updateUTxOState]]).
    */
  def utxosTransition[F[_]: Monad, E <: EraTag](
      env: UtxosEnv[E],
      stAnnTx: StAnnTx[TxLevel.Top, E],
      validation: TxEnv.Validation
  )(using
      t: AlonzoEraTx[E],
      b: ShelleyEraTxBody[E],
      u: AlonzoEraUTxO[E],
      c: EraCertState[E],
      p: EraPParamsToBabbage[E],
      inject: InjectAlonzoUtxos[E],
      embed: EmbedPpupInUtxos[E],
      ask: Ask[F, Globals],
      state: Stateful[F, ShelleyGovState[E]],
      judge: Judge[F, UtxosPredFailure[E]],
      events: Tell[F, Chain[UtxosEvent[E]]],
      evaluator: ScriptEvaluator[F],
      raise: Raise[F, ScriptVerdictUnavailable],
      defect: Raise[F, LedgerDefect],
      deferral: Phase2Deferral[F, E],
      alonzoContext: ContextError[E] =:= AlonzoContextError[E]
  ): F[Unit] =
    t.isValid(t.txStAnnTx[TxLevel.Top](stAnnTx)(using t.bodyOf.top)) match
      case IsPhase2Valid.Phase2Valid => alonzoEvalScriptsTxValid[F, E](env, stAnnTx, validation)
      case IsPhase2Valid.Phase2Invalid =>
        alonzoEvalScriptsTxInvalid[F, E](stAnnTx, validation)

  /** `alonzoEvalScriptsTxValid` (`Alonzo/Rules/Utxos.hs:189-221`, cardano-ledger f649f975): the
    * scripts of a transaction flagged valid must pass (`scriptsTransition`): if any fails,
    * `ValidationTagMismatch` with each failing script's failure, and the scripts that passed beside
    * them are not told (`Fails _ps fs`); if all pass, they are told
    * (`SuccessfulPlutusScriptsEvent`). Then PPUP, after the scripts as Alonzo runs it (Babbage runs
    * it before them), whatever they gave.
    */
  def alonzoEvalScriptsTxValid[F[_]: Monad, E <: EraTag](
      env: UtxosEnv[E],
      stAnnTx: StAnnTx[TxLevel.Top, E],
      validation: TxEnv.Validation
  )(using
      t: AlonzoEraTx[E],
      b: ShelleyEraTxBody[E],
      u: AlonzoEraUTxO[E],
      c: EraCertState[E],
      p: EraPParamsToBabbage[E],
      inject: InjectAlonzoUtxos[E],
      embed: EmbedPpupInUtxos[E],
      ask: Ask[F, Globals],
      state: Stateful[F, ShelleyGovState[E]],
      judge: Judge[F, UtxosPredFailure[E]],
      events: Tell[F, Chain[UtxosEvent[E]]],
      evaluator: ScriptEvaluator[F],
      raise: Raise[F, ScriptVerdictUnavailable],
      defect: Raise[F, LedgerDefect],
      deferral: Phase2Deferral[F, E],
      alonzoContext: ContextError[E] =:= AlonzoContextError[E]
  ): F[Unit] =
    val tx = t.txStAnnTx[TxLevel.Top](stAnnTx)(using t.bodyOf.top)
    scriptsTransition[F, E](stAnnTx, validation) {
      case ScriptResult.Fails(_, fs) =>
        judge.fail(
          inject.injectFailure(
            AlonzoUtxosPredFailure.ValidationTagMismatch(
              t.isValid(tx),
              TagMismatchDescription.FailedUnexpectedly(
                fs.map(FailureDescription.scriptFailureToFailureDescription)
              )
            )
          )
        )
      case ScriptResult.Passes(ps) => tellPassed[F, E](ps)
    } *> transPpup[F, E](env, stAnnTx)

  /** `alonzoEvalScriptsTxInvalid` (`Alonzo/Rules/Utxos.hs:223-245`, cardano-ledger f649f975): the
    * scripts of a transaction flagged invalid must not all pass (`scriptsTransition`); if they do,
    * `ValidationTagMismatch` with `PassedUnexpectedly`, and otherwise the scripts that passed and
    * those that failed are told. No PPUP runs.
    */
  def alonzoEvalScriptsTxInvalid[F[_]: Monad, E <: EraTag](
      stAnnTx: StAnnTx[TxLevel.Top, E],
      validation: TxEnv.Validation
  )(using
      t: AlonzoEraTx[E],
      u: AlonzoEraUTxO[E],
      inject: InjectAlonzoUtxos[E],
      judge: Judge[F, UtxosPredFailure[E]],
      events: Tell[F, Chain[UtxosEvent[E]]],
      evaluator: ScriptEvaluator[F],
      raise: Raise[F, ScriptVerdictUnavailable],
      deferral: Phase2Deferral[F, E],
      alonzoContext: ContextError[E] =:= AlonzoContextError[E]
  ): F[Unit] =
    val tx = t.txStAnnTx[TxLevel.Top](stAnnTx)(using t.bodyOf.top)
    scriptsTransition[F, E](stAnnTx, validation) {
      case ScriptResult.Passes(_) =>
        judge.fail(
          inject.injectFailure(
            AlonzoUtxosPredFailure.ValidationTagMismatch(
              t.isValid(tx),
              TagMismatchDescription.PassedUnexpectedly
            )
          )
        )
      case ScriptResult.Fails(ps, fs) =>
        tellPassed[F, E](ps) *> events.tell(
          Chain.one(
            inject.injectEvent(
              AlonzoUtxosEvent.FailedPlutusScriptsEvent(fs.map(_.scriptFailurePlutus))
            )
          )
        )
    }

/** Babbage's functions of UTXOS (`eras/babbage/impl/src/Cardano/Ledger/Babbage/Rules/Utxos.hs`,
  * cardano-ledger f649f975), which Conway's UTXOS runs.
  */
object BabbageUtxos:
  /** The scripts of the annotated transaction with their contexts, their collection's failures
    * recorded (`?!:`, `CollectErrors`), and the scripts evaluated with `run` only under the policy
    * that validates every check (`when2Phase`) and only if nothing failed so far in the judgment
    * (`whenFailureFree`): the steps `expectScriptsToPass` and `babbageEvalScriptsTxInvalid` share.
    * Where they are evaluated is the run's ([[Phase2Deferral]]): a block the node prepares defers
    * them, so that neither their verdict nor `run` is reached before its plan checks them. A
    * transaction whose scripts are not collected is told to the run with the failures before they
    * are recorded ([[Phase2Deferral.uncollected]]), which the reference has no counterpart for.
    */
  private def evalScripts[F[_]: Monad, E <: EraTag](
      stAnnTx: StAnnTx[TxLevel.Top, E],
      validation: TxEnv.Validation
  )(run: ScriptResult => F[Unit])(using
      u: AlonzoEraUTxO[E],
      inject: InjectAlonzoUtxos[E],
      judge: Judge[F, UtxosPredFailure[E]],
      evaluator: ScriptEvaluator[F],
      raise: Raise[F, ScriptVerdictUnavailable],
      deferral: Phase2Deferral[F, E]
  ): F[Unit] =
    // collected only for the predicate or the evaluation, neither run under `ValidateNone`
    lazy val scriptsWithContextEither =
      u.plutusScriptsWithContextStAnnTx[TxLevel.Top](stAnnTx)(using u.txOf.bodyOf.top)
    val tx = u.txOf.txStAnnTx[TxLevel.Top](stAnnTx)(using u.txOf.bodyOf.top)
    for
      _ <- Sts.validating[F, UtxosPredFailure[E]](
        scriptsWithContextEither.left.toOption.traverse_(errors =>
          deferral.uncollected(tx, errors) *>
            judge.fail(inject.injectFailure(AlonzoUtxosPredFailure.CollectErrors(errors)))
        )
      )
      _ <- AlonzoUtxos.when2Phase[F](validation)(
        Sts.whenFailureFree[F, UtxosPredFailure[E]](
          scriptsWithContextEither.toOption.traverse_ { scriptsWithContext =>
            val now = PlutusEvaluation.evalPlutusScripts[F](scriptsWithContext).flatMap(run)
            NonEmptyList
              .fromList(scriptsWithContext)
              .fold(now)(deferral.evalScripts(stAnnTx, _)(now))
          }
        )
      )
    yield ()

  /** The event of the scripts that passed, if any did (`mapM_ (tellEvent ...) (nonEmpty ps)`). */
  private def tellPassed[F[_]: Applicative, E <: EraTag](passed: List[PlutusWithContext])(using
      inject: InjectAlonzoUtxos[E],
      events: Tell[F, Chain[UtxosEvent[E]]]
  ): F[Unit] =
    NonEmptyList
      .fromList(passed)
      .traverse_(ps =>
        events.tell(
          Chain.one(inject.injectEvent(AlonzoUtxosEvent.SuccessfulPlutusScriptsEvent(ps)))
        )
      )

  /** `utxosTransition` (`Babbage/Rules/Utxos.hs:104-126`, cardano-ledger f649f975): by the
    * transaction's flag, PPUP and then the scripts expected to pass (`babbageEvalScriptsTxValid`),
    * or the scripts expected to fail (`babbageEvalScriptsTxInvalid`), the governance state then
    * left as it was. The state is the governance state ([[ShelleyGovState]]), the only part of the
    * UTxO state the reference's UTXOS changes; UTXO changes the rest (`updateUTxOState`,
    * [[BabbageUtxo.updateUTxOState]]).
    */
  def utxosTransition[F[_]: Monad, E <: EraTag](
      env: UtxosEnv[E],
      stAnnTx: StAnnTx[TxLevel.Top, E],
      validation: TxEnv.Validation
  )(using
      t: AlonzoEraTx[E],
      b: ShelleyEraTxBody[E],
      u: AlonzoEraUTxO[E],
      c: EraCertState[E],
      p: EraPParamsToBabbage[E],
      inject: InjectAlonzoUtxos[E],
      embed: EmbedPpupInUtxos[E],
      ask: Ask[F, Globals],
      state: Stateful[F, ShelleyGovState[E]],
      judge: Judge[F, UtxosPredFailure[E]],
      events: Tell[F, Chain[UtxosEvent[E]]],
      evaluator: ScriptEvaluator[F],
      raise: Raise[F, ScriptVerdictUnavailable],
      defect: Raise[F, LedgerDefect],
      deferral: Phase2Deferral[F, E]
  ): F[Unit] =
    t.isValid(t.txStAnnTx[TxLevel.Top](stAnnTx)(using t.bodyOf.top)) match
      case IsPhase2Valid.Phase2Valid => babbageEvalScriptsTxValid[F, E](env, stAnnTx, validation)
      case IsPhase2Valid.Phase2Invalid =>
        babbageEvalScriptsTxInvalid[F, E](stAnnTx, validation)

  /** `babbageEvalScriptsTxValid` (`Babbage/Rules/Utxos.hs:157-190`, cardano-ledger f649f975): PPUP
    * as a sub-rule (`trans`) on the body's update proposal, with the slot, the parameters and the
    * genesis delegations of the certificate state (`certDStateL . dsGenDelegsL`), its failures and
    * events embedded ([[EmbedPpupInUtxos]]), before any script is evaluated; then
    * `expectScriptsToPass`. The proposal is read with the bytes the node keeps it by
    * ([[KeptUpdate]], the body's [[ShelleyEraTxBody.keptUpdate]]).
    */
  def babbageEvalScriptsTxValid[F[_]: Monad, E <: EraTag](
      env: UtxosEnv[E],
      stAnnTx: StAnnTx[TxLevel.Top, E],
      validation: TxEnv.Validation
  )(using
      t: AlonzoEraTx[E],
      b: ShelleyEraTxBody[E],
      u: AlonzoEraUTxO[E],
      c: EraCertState[E],
      p: EraPParamsToBabbage[E],
      inject: InjectAlonzoUtxos[E],
      embed: EmbedPpupInUtxos[E],
      ask: Ask[F, Globals],
      state: Stateful[F, ShelleyGovState[E]],
      judge: Judge[F, UtxosPredFailure[E]],
      events: Tell[F, Chain[UtxosEvent[E]]],
      evaluator: ScriptEvaluator[F],
      raise: Raise[F, ScriptVerdictUnavailable],
      defect: Raise[F, LedgerDefect],
      deferral: Phase2Deferral[F, E]
  ): F[Unit] =
    val tx = t.txStAnnTx[TxLevel.Top](stAnnTx)(using t.bodyOf.top)
    val genDelegs = c.certDStateL.get(env.certState).genDelegs
    val ppup =
      given Judge[F, ShelleyPpupPredFailure[E]] = judge.contramap(embed.wrapFailed)
      given Tell[F, Chain[ShelleyPpupEvent[E]]] = new Tell[F, Chain[ShelleyPpupEvent[E]]]:
        val functor: cats.Functor[F] = events.functor
        def tell(l: Chain[ShelleyPpupEvent[E]]): F[Unit] = events.tell(l.map(embed.wrapEvent))
      judge.nest(
        ShelleyPpup.ppupTransitionNonEmpty[F, E](
          PpupEnv[E](env.slot, env.pp, genDelegs),
          b.keptUpdate(t.body[TxLevel.Top](tx)(using t.bodyOf.top))
        )
      )
    ppup *> expectScriptsToPass[F, E](stAnnTx, validation)

  /** `expectScriptsToPass` (`Babbage/Rules/Utxos.hs:130-155`, cardano-ledger f649f975): the scripts
    * of a transaction flagged valid must pass; a failure is `ValidationTagMismatch` with each
    * script's failure, and the scripts that passed are told.
    */
  def expectScriptsToPass[F[_]: Monad, E <: EraTag](
      stAnnTx: StAnnTx[TxLevel.Top, E],
      validation: TxEnv.Validation
  )(using
      t: AlonzoEraTx[E],
      u: AlonzoEraUTxO[E],
      inject: InjectAlonzoUtxos[E],
      judge: Judge[F, UtxosPredFailure[E]],
      events: Tell[F, Chain[UtxosEvent[E]]],
      evaluator: ScriptEvaluator[F],
      raise: Raise[F, ScriptVerdictUnavailable],
      deferral: Phase2Deferral[F, E]
  ): F[Unit] =
    val tx = t.txStAnnTx[TxLevel.Top](stAnnTx)(using t.bodyOf.top)
    evalScripts[F, E](stAnnTx, validation) {
      case ScriptResult.Fails(_, fs) =>
        judge.fail(
          inject.injectFailure(
            AlonzoUtxosPredFailure.ValidationTagMismatch(
              t.isValid(tx),
              TagMismatchDescription.FailedUnexpectedly(
                fs.map(FailureDescription.scriptFailureToFailureDescription)
              )
            )
          )
        )
      case ScriptResult.Passes(ps) => tellPassed[F, E](ps)
    }

  /** `babbageEvalScriptsTxInvalid` (`Babbage/Rules/Utxos.hs:192-222`, cardano-ledger f649f975): the
    * scripts of a transaction flagged invalid must not all pass; if they do,
    * `ValidationTagMismatch` with `PassedUnexpectedly`, and otherwise the scripts that passed and
    * those that failed are told.
    */
  def babbageEvalScriptsTxInvalid[F[_]: Monad, E <: EraTag](
      stAnnTx: StAnnTx[TxLevel.Top, E],
      validation: TxEnv.Validation
  )(using
      t: AlonzoEraTx[E],
      u: AlonzoEraUTxO[E],
      inject: InjectAlonzoUtxos[E],
      judge: Judge[F, UtxosPredFailure[E]],
      events: Tell[F, Chain[UtxosEvent[E]]],
      evaluator: ScriptEvaluator[F],
      raise: Raise[F, ScriptVerdictUnavailable],
      deferral: Phase2Deferral[F, E]
  ): F[Unit] =
    val tx = t.txStAnnTx[TxLevel.Top](stAnnTx)(using t.bodyOf.top)
    evalScripts[F, E](stAnnTx, validation) {
      case ScriptResult.Passes(_) =>
        judge.fail(
          inject.injectFailure(
            AlonzoUtxosPredFailure.ValidationTagMismatch(
              t.isValid(tx),
              TagMismatchDescription.PassedUnexpectedly
            )
          )
        )
      case ScriptResult.Fails(ps, fs) =>
        tellPassed[F, E](ps) *> events.tell(
          Chain.one(
            inject.injectEvent(
              AlonzoUtxosEvent.FailedPlutusScriptsEvent(fs.map(_.scriptFailurePlutus))
            )
          )
        )
    }

/** Conway's UTXOS (`eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Utxos.hs`, cardano-ledger
  * f649f975; the same at 29a7d656, the 11.2 build, but for the instances it derives).
  */
object ConwayUtxos:
  /** `alonzoToConwayUtxosPredFailure` (`Conway/Rules/Utxos.hs:90-98`, cardano-ledger f649f975):
    * each failure as Conway's own; PPUP's has no value where the era has no PPUP (`absurdEraRule`),
    * which `void` witnesses.
    */
  def alonzoToConwayUtxosPredFailure[E <: EraTag](failure: AlonzoUtxosPredFailure[E])(using
      void: PpupPredFailure[E] =:= Nothing
  ): ConwayUtxosPredFailure[E] = failure match
    case AlonzoUtxosPredFailure.ValidationTagMismatch(t, x) =>
      ConwayUtxosPredFailure.ValidationTagMismatch(t, x)
    case AlonzoUtxosPredFailure.CollectErrors(x) => ConwayUtxosPredFailure.CollectErrors(x)
    case AlonzoUtxosPredFailure.UpdateFailure(x) => void(x)

  /** `alonzoToConwayUtxosEvent` (`Conway/Rules/Utxos.hs:100-108`, cardano-ledger f649f975). */
  def alonzoToConwayUtxosEvent[E <: EraTag](event: AlonzoUtxosEvent[E])(using
      void: PpupEvent[E] =:= Nothing
  ): ConwayUtxosEvent[E] = event match
    case AlonzoUtxosEvent.AlonzoPpupToUtxosEvent(x) => void(x)
    case AlonzoUtxosEvent.SuccessfulPlutusScriptsEvent(l) =>
      ConwayUtxosEvent.SuccessfulPlutusScriptsEvent(l)
    case AlonzoUtxosEvent.FailedPlutusScriptsEvent(l) =>
      ConwayUtxosEvent.FailedPlutusScriptsEvent(l)

  /** `utxosTransition` (`Conway/Rules/Utxos.hs:207-224`, cardano-ledger f649f975): by the
    * transaction's flag, the scripts expected to pass (`conwayEvalScriptsTxValid`) or not
    * (`babbageEvalScriptsTxInvalid`). The environment and the state are `()`.
    */
  def utxosTransition[F[_]: Monad, E <: EraTag](
      stAnnTx: StAnnTx[TxLevel.Top, E],
      validation: TxEnv.Validation
  )(using
      t: AlonzoEraTx[E],
      u: AlonzoEraUTxO[E],
      inject: InjectAlonzoUtxos[E],
      judge: Judge[F, UtxosPredFailure[E]],
      events: Tell[F, Chain[UtxosEvent[E]]],
      evaluator: ScriptEvaluator[F],
      raise: Raise[F, ScriptVerdictUnavailable],
      deferral: Phase2Deferral[F, E]
  ): F[Unit] =
    t.isValid(t.txStAnnTx[TxLevel.Top](stAnnTx)(using t.bodyOf.top)) match
      case IsPhase2Valid.Phase2Valid => conwayEvalScriptsTxValid[F, E](stAnnTx, validation)
      case IsPhase2Valid.Phase2Invalid =>
        BabbageUtxos.babbageEvalScriptsTxInvalid[F, E](stAnnTx, validation)

  /** `conwayEvalScriptsTxValid` (`Conway/Rules/Utxos.hs:226-242`, cardano-ledger f649f975):
    * Babbage's `expectScriptsToPass`, without Babbage's PPUP before it.
    */
  def conwayEvalScriptsTxValid[F[_]: Monad, E <: EraTag](
      stAnnTx: StAnnTx[TxLevel.Top, E],
      validation: TxEnv.Validation
  )(using
      t: AlonzoEraTx[E],
      u: AlonzoEraUTxO[E],
      inject: InjectAlonzoUtxos[E],
      judge: Judge[F, UtxosPredFailure[E]],
      events: Tell[F, Chain[UtxosEvent[E]]],
      evaluator: ScriptEvaluator[F],
      raise: Raise[F, ScriptVerdictUnavailable],
      deferral: Phase2Deferral[F, E]
  ): F[Unit] =
    BabbageUtxos.expectScriptsToPass[F, E](stAnnTx, validation)
