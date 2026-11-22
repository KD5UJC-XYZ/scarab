// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Functor, Id, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.{
  Horizon,
  LedgerDefect,
  SystemStart,
  TranslationError,
  TxEnv,
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.era.{
  Block,
  EraBlockHeader,
  PraosEraBlockHeader,
  Proposals,
  ProposalsReadError,
  ProposalsWriteError,
  ScriptVerdictUnavailable
}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judgment, RuleT, contramap, focus}
import xyz.kd5ujc.scarab.ledger.state.{Conway, LedgerState, UTxOState}
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}

/** What ends a run of Conway's rules on a block without a verdict: the defects the rules raise,
  * each typed as its rule raises it, GOV's proposals the node's record cannot give or hold among
  * them ([[ProposalsRecord]]).
  */
enum ConwayRuleDefect:
  case Unavailable(cause: ScriptVerdictUnavailable)
  case UtxoState(cause: UtxoStateDefect)
  case Ledger(cause: LedgerDefect)
  case Gov(cause: GovDefect)
  case ProposalsRead(cause: ProposalsReadError)
  case ProposalsWrite(cause: ProposalsWriteError)

/** Conway's rules on a block, run as the node applies the block (`applyBlockEither` with
  * `ValidateAll`, cardano-ledger f649f975 `Shelley/API/Validation.hs`): BBODY in one judgment
  * ([[ConwayBbody.conwayBbody]]), every failure accumulated (`RuleT.Policy.Accumulate`), every
  * check of its transactions validated (`TxEnv.Validation.All`, the `ValidateAll` of
  * `applyBlockEither`); the events are discarded. The proposals are held beside the BBODY state,
  * read from the node's record in the governance state once, where a rule first reads them, and
  * written back once, after the block ([[Held]]), as Dijkstra's run holds its own
  * ([[DijkstraBlockRun]]). The node does not run it yet: its blocks run the flat BBODY until the
  * Conway switch (#85).
  */
object ConwayBlockRun:
  /** The proposals of a run: not yet read from the node's record, or the era's, as the rules left
    * them.
    */
  private enum Held:
    case Unread
    case Read(proposals: Proposals[Conway])

  private type S = (ShelleyBbodyState[Conway], Held)
  private type F[A] = RuleT[BbodyPredFailure[Conway], ConwayRuleDefect, S, A]
  import RuleT.given

  private given Stateful[F, ShelleyBbodyState[Conway]] =
    summon[Stateful[F, S]].focus(Lens[S, ShelleyBbodyState[Conway]](_._1)(b => s => (b, s._2)))

  // the events of a block applied by the node are not kept (`ComputeLedgerEvents` off)
  private given Tell[F, Chain[BbodyEvent[Conway]]] = new Tell[F, Chain[BbodyEvent[Conway]]]:
    val functor: Functor[F] = summon[Monad[F]]
    def tell(events: Chain[BbodyEvent[Conway]]): F[Unit] = Monad[F].unit

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

  private def lifted(evaluator: ScriptEvaluator[Id]): ScriptEvaluator[F] =
    request => Monad[F].unit.map(_ => evaluator.evaluate(request))

  /** The proposals the rules judge: read from the ledger state's record at the first read
    * ([[ProposalsRecord.read]]; one the era cannot read is raised, [[ConwayRuleDefect]]), then
    * held, so that a block reads them once and a block whose rules never reach GOV not at all.
    */
  private given Stateful[F, Proposals[Conway]] = new Stateful[F, Proposals[Conway]]:
    val monad: Monad[F] = summon[Monad[F]]
    def get: F[Proposals[Conway]] = summon[Stateful[F, S]].get.flatMap {
      case (_, Held.Read(proposals)) => Monad[F].pure(proposals)
      case (bbody, Held.Unread) =>
        ProposalsRecord.conway
          .read(bbody.ls.utxo.gov.proposals)
          .fold(
            summon[Raise[F, ProposalsReadError]].raise[ProposalsReadError, Proposals[Conway]],
            proposals => set(proposals).as(proposals)
          )
    }
    def set(proposals: Proposals[Conway]): F[Unit] =
      summon[Stateful[F, S]].modify((bbody, _) => (bbody, Held.Read(proposals)))

  /** The BBODY state with the proposals the run holds written back into the node's record
    * ([[ProposalsRecord.write]]; ones it cannot hold are a defect), the record as it was if the
    * rules never read them.
    */
  private def written(after: S): Either[ConwayRuleDefect, ShelleyBbodyState[Conway]] =
    after match
      case (bbody, Held.Unread) => Right(bbody)
      case (bbody, Held.Read(proposals)) =>
        ProposalsRecord.conway
          .write(proposals)
          .bimap(
            ConwayRuleDefect.ProposalsWrite(_),
            stored =>
              ShelleyBbodyState
                .ls[Conway]
                .andThen(LedgerState.utxo[Conway])
                .andThen(UTxOState.gov[Conway])
                .modify(_.copy(proposals = stored))(bbody)
          )

  /** BBODY on `block` from `state`: a defect, the refusal's failures, or the BBODY state after. The
    * context of its transactions' rules is made here from `globals`, as the reference's rules read
    * it from theirs: the network (`networkId`, the Shelley genesis's) and the build's injection of
    * Shelley's UTXO failures ([[ConwayUtxo.injectShelleyFailure]]), with the system start and the
    * epoch information the caller gives (`systemStart`, `epochInfo`), and every
    * check validated, so that no caller runs the block's rules under another policy or on another
    * network.
    */
  def bbody[H](
      globals: Globals,
      env: BbodyEnv[Conway],
      block: Block[H, Conway],
      systemStart: SystemStart,
      epochInfo: Horizon,
      state: ShelleyBbodyState[Conway],
      evaluator: ScriptEvaluator[Id],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt]
  )(using
      EraBlockHeader[H, Conway],
      PraosEraBlockHeader[H, Conway]
  ): Either[ConwayRuleDefect, Either[
    NonEmptyChain[BbodyPredFailure[Conway]],
    ShelleyBbodyState[Conway]
  ]] =
    given ScriptEvaluator[F] = lifted(evaluator)
    val netId = globals.networkId
    val context = UtxoContext[Conway](
      netId,
      systemStart,
      epochInfo,
      TxEnv.Validation.All,
      ConwayUtxo.injectShelleyFailure(globals.ledger.referenceBuild)
    )
    Judgment
      .judge(
        ConwayBbody.conwayBbody[F, H](env, block, context, verdicts, decoder, slotToPOSIXTime),
        RuleT.Policy.Accumulate
      )(globals, (state, Held.Unread))
      .flatMap(_.traverse((after, _) => written(after)))
