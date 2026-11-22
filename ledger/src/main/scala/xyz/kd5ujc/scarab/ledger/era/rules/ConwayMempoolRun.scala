// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Functor, Id, Monad}
import cats.data.Chain
import cats.mtl.{Raise, Tell}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{Horizon, LedgerDefect, TranslationError, TxEnv, Verdicts}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoStAnnTx,
  EraPParams,
  EraTx,
  PParams,
  ProposalsReadError,
  ProposalsWriteError,
  ProtVer,
  ScriptVerdictUnavailable,
  StAnnTx,
  Tx,
  TxLevel,
  UTxO
}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judgment, RuleT, contramap}
import xyz.kd5ujc.scarab.ledger.state.{ChainAccountState, Conway, LedgerState, Network, TxIx}
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** Conway's MEMPOOL on a transaction, run as the node's mempool applies one: `instance ApplyTx
  * ConwayEra` (`eras/conway/impl/src/Cardano/Ledger/Conway.hs:50-59`, cardano-ledger f649f975),
  * whose application and reapplication are the defaults over MEMPOOL
  * (`defaultApplyTxWithValidation` and `defaultReapplyValidatedTx`,
  * `eras/shelley/impl/src/Cardano/Ledger/Shelley/API/Mempool.hs:267-323`), each through
  * `ruleApplyTxValidation` (`:230-262`): one judgment, every failure accumulated
  * (`RuleT.Policy.Accumulate`), the events discarded (`asoEvents = EPDiscard`). The validation is
  * the UTXO context's ([[UtxoContext.validation]]): every check (`ValidateAll`), or the static ones
  * skipped (`ValidateSuchThat (notElem lblStatic)`). The proposals are the node's record in the
  * governance state, as Conway's LEDGER reads and writes them ([[ProposalsRecord]]).
  *
  * The held transaction is applied again by the function the build's consensus calls ([[Reapply]]),
  * chosen by the build the judgment follows (`Globals`' reference build, REF-3), the build that
  * also selects UTXO's injection of Shelley's failures ([[ConwayUtxo.injectShelleyFailure]]), so
  * that the two cannot disagree. Its defects are those of Conway's run on a block
  * ([[ConwayRuleDefect]]).
  */
object ConwayMempoolRun:
  private type F[A] = RuleT[MempoolPredFailure[Conway], ConwayRuleDefect, LedgerState[Conway], A]
  import RuleT.given

  // `asoEvents = EPDiscard` (`Shelley/API/Mempool.hs:249`, cardano-ledger f649f975)
  private given Tell[F, Chain[MempoolEvent[Conway]]] = new Tell[F, Chain[MempoolEvent[Conway]]]:
    val functor: Functor[F] = summon[Monad[F]]
    def tell(events: Chain[MempoolEvent[Conway]]): F[Unit] = Monad[F].unit

  private given Raise[F, ScriptVerdictUnavailable] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.Unavailable(_))
  private given Raise[F, UtxoStateDefect] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.UtxoState(_))
  private given Raise[F, LedgerDefect] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.Ledger(_))
  private given Raise[F, GovDefect] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.Gov(_))
  private given Raise[F, ProposalsReadError] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.ProposalsRead(_))
  private given Raise[F, ProposalsWriteError] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.ProposalsWrite(_))

  private def lifted(evaluator: ScriptEvaluator[Id]): ScriptEvaluator[F] =
    request => Monad[F].unit.map(_ => evaluator.evaluate(request))

  /** A judgment's outcome: a defect, the refusal, or what the transaction leaves. */
  type Outcome[A] = Either[ConwayRuleDefect, Either[ConwayApplyTxError, A]]

  /** `ValidatedTx ConwayEra` (`Shelley/API/Mempool.hs:81-88`, cardano-ledger f649f975), whose
    * constructor the reference does not export (`:27`): the annotation the transaction was admitted
    * with (`vtStAnnTx`, `getValidatedTxStAnnTx`), the protocol version of the environment's
    * parameters then (`vtProtocolVersion`) and the slot (`vtSlotNo`). Only an admission makes one
    * ([[applyTxWithValidation]]), or, for tests, [[unsafeMakeValidatedTx]], as the reference's own;
    * a plain class, so that no `Mirror` builds or copies one from other fields.
    */
  final class ValidatedTx private[ConwayMempoolRun] (
      val stAnnTx: StAnnTx[TxLevel.Top, Conway],
      val protocolVersion: ProtVer,
      val slot: BigInt
  ):
    /** `extractValidatedTx` (`Shelley/API/Mempool.hs:108-109`, cardano-ledger f649f975). */
    def tx: Tx[TxLevel.Top, Conway] =
      EraTx.conway.txStAnnTx[TxLevel.Top](stAnnTx)(using EraTx.conway.bodyOf.top)

  /** What `tx`, annotated as `stAnnTx`, is held with after its validation in `env`
    * (`ruleApplyTxValidation`, `Shelley/API/Mempool.hs:254-261`, cardano-ledger f649f975).
    */
  private def validatedIn(env: LedgerEnv[Conway], stAnnTx: StAnnTx[TxLevel.Top, Conway]) =
    ValidatedTx(stAnnTx, EraPParams.conway.ppProtocolVersionL.get(env.ledgerPp), env.ledgerSlotNo)

  /** What a transaction is judged with besides the environment and the state, the reference's
    * `Globals` and the UTXO rules' context but for its validation: the constants of the rules, the
    * network, the system start and the era history of the script contexts, the clock of the
    * annotation, the Plutus backend, the signature verifiers and the script decoder.
    */
  final case class Judging(
      globals: Globals,
      netId: Network,
      systemStart: BigInt,
      epochInfo: Horizon,
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      evaluator: ScriptEvaluator[Id],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id]
  ):
    /** The build the judgment follows. */
    def build: ReferenceBuild = globals.ledger.referenceBuild

    /** The UTXO rules' context with `validation`, UTXO's injection of Shelley's failures the
      * build's.
      */
    def context(validation: TxEnv.Validation): UtxoContext[Conway] =
      UtxoContext(netId, systemStart, epochInfo, validation, ConwayUtxo.injectShelleyFailure(build))

  /** How a held transaction is applied again, by the build's consensus (`reapplyShelleyTx`). */
  enum Reapply derives CanEqual:
    /** `SL.reapplyTx` (ouroboros-consensus 82ecba32 `Shelley/Ledger/Mempool.hs:325-348`;
      * cardano-ledger f649f975 `Shelley/API/Mempool.hs:465-473`): the transaction annotated again
      * against the state and MEMPOOL without the static checks, whatever version it was validated
      * at ([[reapplyTx]]).
      */
    case ReapplyTx

    /** `SL.reapplyValidatedTx` (ouroboros-consensus 808e4005 `Shelley/Ledger/Mempool.hs:349-372`
      * and 2416493b `:368-391`; cardano-ledger f649f975, 29a7d656 and 9df9f98c
      * `Shelley/API/Mempool.hs:420-439`): the annotation of its validation without the static
      * checks under the same major version, every check on the transaction annotated again under
      * another ([[reapplyValidatedTx]]).
      */
    case ReapplyValidatedTx

  object Reapply:
    /** The reapplication of `build`'s consensus. */
    def of(build: ReferenceBuild): Reapply = build match
      case ReferenceBuild.Release11_1   => ReapplyTx
      case ReferenceBuild.Release11_2   => ReapplyValidatedTx
      case ReferenceBuild.LeiosB004fe0c => ReapplyValidatedTx

  /** `mkMempoolEnv nes slot` (`Shelley/API/Mempool.hs:363-377`, cardano-ledger f649f975): the slot,
    * no epoch (LEDGER takes the slot's), the least index, the parameters in force and the chain's
    * account of the state, which the caller reads from it.
    */
  def mkMempoolEnv(
      slot: BigInt,
      pp: PParams[Conway],
      account: ChainAccountState
  ): LedgerEnv[Conway] =
    LedgerEnv[Conway](slot, None, TxIx.minBound, pp, account)

  /** MEMPOOL on `stAnnTx` with `validation` (`ruleApplyTxValidation`, `Shelley/API/Mempool.hs:
    * 230-262`, cardano-ledger f649f975): a defect, the refusal, or the ledger state after.
    */
  def mempool(
      judging: Judging,
      env: LedgerEnv[Conway],
      stAnnTx: StAnnTx[TxLevel.Top, Conway],
      validation: TxEnv.Validation,
      state: LedgerState[Conway]
  ): Outcome[LedgerState[Conway]] =
    judged(judging, state)(
      ConwayMempool.mempoolTransition[F](
        env,
        stAnnTx,
        judging.context(validation),
        judging.verdicts,
        judging.decoder
      )
    )

  /** `program` judged from `state`, every failure accumulated. */
  private def judged(judging: Judging, state: LedgerState[Conway])(
      program: ScriptEvaluator[F] ?=> F[Unit]
  ): Outcome[LedgerState[Conway]] =
    given ScriptEvaluator[F] = lifted(judging.evaluator)
    Judgment
      .judge(program, RuleT.Policy.Accumulate)(judging.globals, state)
      .map(_.bimap(ConwayApplyTxError(_), _._1))

  /** The stored outputs of the transaction's inputs, reference inputs and collateral
    * (`allInputsTxBodyF`, the keys `getTransactionKeySets` reads, ouroboros-consensus 82ecba32
    * `Shelley/Ledger/Mempool.hs`) in `state`, read into the era: the part of the UTxO set (`utxoG`)
    * that the annotation reads (`defaultApplyTxWithValidation`, `Shelley/API/Mempool.hs:283-296`,
    * cardano-ledger f649f975). A stored output the era cannot read is the state's defect.
    */
  def utxoOf(
      state: LedgerState[Conway],
      tx: Tx[TxLevel.Top, Conway]
  ): Either[UtxoStateDefect, UTxO[Conway]] =
    type G[A] = Either[UtxoStateDefect, A]
    given Raise[G, UtxoStateDefect] = new Raise[G, UtxoStateDefect]:
      val functor: Functor[G] = summon[Monad[G]]
      def raise[E2 <: UtxoStateDefect, A](e: E2): G[A] = Left(e)
    ShelleyUtxo.readStored[G, Conway](
      ShelleyUtxo
        .extractKeys(
          state.utxo.utxo.utxo,
          EraTx.conway.bodyOf
            .allInputs(EraTx.conway.body[TxLevel.Top](tx)(using EraTx.conway.bodyOf.top))
        )
        ._2
    )

  /** `mkStAnnTx` of Conway (`mkAlonzoStAnnTx`, `Conway.hs:55`, cardano-ledger f649f975) as
    * `defaultApplyTxWithValidation` calls it (`Shelley/API/Mempool.hs:283-292`): the transaction
    * annotated against `utxo` with the parameters of the environment and an empty cache (`mempty`).
    */
  def annotate(
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      pp: PParams[Conway],
      utxo: UTxO[Conway],
      tx: Tx[TxLevel.Top, Conway]
  ): StAnnTx[TxLevel.Top, Conway] =
    AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](slotToPOSIXTime, pp, utxo, Map.empty, tx)

  /** `internalApplyTxWithValidation` of Conway, `defaultApplyTxWithValidation @"MEMPOOL"`
    * (`Conway.hs:57`; `Shelley/API/Mempool.hs:283-296`, cardano-ledger f649f975): `tx` annotated
    * against the state's UTxO set ([[annotate]]), then MEMPOOL with `validation`; the ledger state
    * after it, and what the transaction was validated with ([[ValidatedTx]]). The annotation is
    * made where LEDGER first reads it ([[ConwayMempool.mempoolTransitionAnnotating]]), as the
    * reference's lazy fields are, so that a stored output the era cannot read is no defect of a
    * transaction MEMPOOL refuses as a duplicate.
    */
  def applyTxWithValidation(
      judging: Judging,
      env: LedgerEnv[Conway],
      state: LedgerState[Conway],
      tx: Tx[TxLevel.Top, Conway],
      validation: TxEnv.Validation
  ): Outcome[(LedgerState[Conway], ValidatedTx)] =
    val annotated: Either[ConwayRuleDefect, StAnnTx[TxLevel.Top, Conway]] =
      utxoOf(state, tx)
        .bimap(
          ConwayRuleDefect.UtxoState(_),
          annotate(judging.slotToPOSIXTime, env.ledgerPp, _, tx)
        )
    judged(judging, state)(
      ConwayMempool.mempoolTransitionAnnotating[F](
        env,
        tx,
        annotated.fold(
          d =>
            summon[Raise[F, ConwayRuleDefect]]
              .raise[ConwayRuleDefect, StAnnTx[TxLevel.Top, Conway]](d),
          Monad[F].pure
        ),
        judging.context(validation),
        judging.verdicts,
        judging.decoder
      )
    ).flatMap(_.traverse(after => annotated.map(stAnnTx => after -> validatedIn(env, stAnnTx))))

  /** `unsafeMakeValidatedTx` (`Shelley/API/Mempool.hs:131-151`, cardano-ledger f649f975), which the
    * reference keeps for tests: `tx` held as if validated in `env` and `state`, annotated against
    * the state's UTxO set, without running MEMPOOL. Nothing but a test calls it.
    */
  def unsafeMakeValidatedTx(
      judging: Judging,
      env: LedgerEnv[Conway],
      state: LedgerState[Conway],
      tx: Tx[TxLevel.Top, Conway]
  ): Either[UtxoStateDefect, ValidatedTx] =
    utxoOf(state, tx).map(utxo =>
      validatedIn(env, annotate(judging.slotToPOSIXTime, env.ledgerPp, utxo, tx))
    )

  /** `applyTxWithFullValidation` (`Shelley/API/Mempool.hs:406-413`, cardano-ledger f649f975):
    * [[applyTxWithValidation]] with every check (`ValidateAll`).
    */
  def applyTx(
      judging: Judging,
      env: LedgerEnv[Conway],
      state: LedgerState[Conway],
      tx: Tx[TxLevel.Top, Conway]
  ): Outcome[(LedgerState[Conway], ValidatedTx)] =
    applyTxWithValidation(judging, env, state, tx, TxEnv.Validation.All)

  /** `reapplyTx` (`Shelley/API/Mempool.hs:465-473`, cardano-ledger f649f975): the held transaction,
    * annotated again against the state, through [[applyTxWithValidation]] without the static checks
    * (`ValidateSuchThat (notElem lblStatic)`); the annotation of its validation and its version are
    * not read.
    */
  def reapplyTx(
      judging: Judging,
      env: LedgerEnv[Conway],
      state: LedgerState[Conway],
      held: ValidatedTx
  ): Outcome[LedgerState[Conway]] =
    applyTxWithValidation(judging, env, state, held.tx, TxEnv.Validation.NonStatic).map(_.map(_._1))

  /** `reapplyValidatedTx` (`Shelley/API/Mempool.hs:420-439`, cardano-ledger f649f975): under the
    * major version of the state's parameters (`curPParamsGovStateL`, the node's record of them,
    * `:439`) that the transaction was validated at, MEMPOOL without the static checks on the
    * annotation of its validation (`internalReapplyValidatedTx`, `defaultReapplyValidatedTx`,
    * `:313-323`); under another, [[applyTxWithValidation]] with every check on the transaction.
    */
  def reapplyValidatedTx(
      judging: Judging,
      env: LedgerEnv[Conway],
      state: LedgerState[Conway],
      held: ValidatedTx
  ): Outcome[LedgerState[Conway]] =
    if BigInt(held.protocolVersion.pvMajor.value) == state.utxo.gov.curPParams.protocolMajor then
      mempool(judging, env, held.stAnnTx, TxEnv.Validation.NonStatic, state)
    else applyTxWithValidation(judging, env, state, held.tx, TxEnv.Validation.All).map(_.map(_._1))

  /** The held transaction applied again as the judgment's build's consensus applies it
    * ([[Reapply.of]]).
    */
  def reapply(
      judging: Judging,
      env: LedgerEnv[Conway],
      state: LedgerState[Conway],
      held: ValidatedTx
  ): Outcome[LedgerState[Conway]] =
    Reapply.of(judging.build) match
      case Reapply.ReapplyTx          => reapplyTx(judging, env, state, held)
      case Reapply.ReapplyValidatedTx => reapplyValidatedTx(judging, env, state, held)
