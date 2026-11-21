// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Id, Monad}
import cats.data.Chain
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{
  GovActionId,
  GovActionState as FlatGovActionState,
  LedgerDefect,
  Mismatch,
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.rules.{DijkstraConway, Globals, Judge, Sts, Test, focus}
import xyz.kd5ujc.scarab.ledger.state.{
  Accounts,
  ChainAccountState,
  Coin,
  Dijkstra,
  EpochNo,
  EraTag,
  Hash32,
  LedgerState,
  Network,
  ScriptHash
}
import xyz.kd5ujc.scarab.plutus.ScriptDecoder

// SUBGOV, SUBLEDGER and SUBLEDGERS of Dijkstra as the reference writes them (cardano-ledger
// 9df9f98c, `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/{SubGov,SubLedger,SubLedgers}.hs`):
// the rules of a transaction's sub-transactions, which LEDGER runs before the top level. SUBGOV's
// failure is a newtype over GOV's, and each of its injections is that newtype after GOV's, so it is
// GOV's transition with its failures and events wrapped. SUBLEDGER runs SUBENTITIES and SUBGOV on a
// top level flagged valid, then SUBUTXOW with the certificate state it was given; SUBLEDGERS folds
// SUBLEDGER over the sub-transactions. Conway's LEDGER failure, which the reference injects
// partially (`conwayToDijkstraSubLedgerPredFailure`, `SubLedger.hs:373-394`), has an injection over
// the constructors whose payloads SUBLEDGER's injections take whole.

/** `newtype DijkstraSubGovPredFailure era` (`Dijkstra/Rules/SubGov.hs:43`, cardano-ledger
  * 9df9f98c): Dijkstra's GOV failure.
  */
final case class DijkstraSubGovPredFailure[E <: EraTag](failure: DijkstraGovPredFailure[E])

object DijkstraSubGovPredFailure:
  given [E <: EraTag]: CanEqual[DijkstraSubGovPredFailure[E], DijkstraSubGovPredFailure[E]] =
    CanEqual.derived

/** `newtype DijkstraSubGovEvent era` (`Dijkstra/Rules/SubGov.hs:58`, cardano-ledger 9df9f98c):
  * Conway's GOV event.
  */
final case class DijkstraSubGovEvent[E <: EraTag](event: ConwayGovEvent[E])

object DijkstraSubGovEvent:
  given [E <: EraTag]: CanEqual[DijkstraSubGovEvent[E], DijkstraSubGovEvent[E]] = CanEqual.derived

/** `EraRuleFailure "SUBGOV" era` (`Dijkstra/Rules/SubGov.hs:46`, cardano-ledger 9df9f98c). */
type SubGovPredFailure[E <: EraTag] = E match
  case Dijkstra => DijkstraSubGovPredFailure[Dijkstra]

/** `EraRuleEvent "SUBGOV" era` (`Dijkstra/Rules/SubGov.hs:48`, cardano-ledger 9df9f98c). */
type SubGovEvent[E <: EraTag] = E match
  case Dijkstra => DijkstraSubGovEvent[Dijkstra]

/** `InjectRuleFailure "SUBGOV" f era` (`Core/Era.hs:119-124`, cardano-ledger 9df9f98c). */
trait InjectSubGovFailure[F, E <: EraTag]:
  def injectFailure(failure: F): SubGovPredFailure[E]

object InjectSubGovFailure:
  /** `Dijkstra/Rules/SubGov.hs:53`, cardano-ledger 9df9f98c: the identity. */
  given dijkstra: InjectSubGovFailure[DijkstraSubGovPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraSubGovPredFailure[Dijkstra]): SubGovPredFailure[Dijkstra] =
      failure

  /** `Dijkstra/Rules/SubGov.hs:50-51`, cardano-ledger 9df9f98c: the newtype after
    * `conwayToDijkstraGovPredFailure`, GOV's injection ([[InjectGov.dijkstra]]).
    */
  given dijkstraConway: InjectSubGovFailure[ConwayGovPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: ConwayGovPredFailure[Dijkstra]): SubGovPredFailure[Dijkstra] =
      DijkstraSubGovPredFailure(InjectGov.dijkstra.injectFailure(failure))

  /** `Dijkstra/Rules/SubGov.hs:55-56`, cardano-ledger 9df9f98c: the newtype. */
  given dijkstraGov: InjectSubGovFailure[DijkstraGovPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraGovPredFailure[Dijkstra]): SubGovPredFailure[Dijkstra] =
      DijkstraSubGovPredFailure(failure)

/** SUBGOV (`Dijkstra/Rules/SubGov.hs`, cardano-ledger 9df9f98c). */
object DijkstraSUBGOV:
  /** `transitionRules = [Conway.conwayGovTransition]` (`Dijkstra/Rules/SubGov.hs:86`,
    * cardano-ledger 9df9f98c): GOV's transition at Dijkstra, its failures in SUBGOV's newtype,
    * which is SUBGOV's every injection after GOV's (`SubGov.hs:50-56`), and Conway's events in
    * SUBGOV's (`SubGov.hs:61-64`), as GOV tells them at Dijkstra ([[InjectGov.dijkstra]]). GOV's
    * defects are raised as they are.
    */
  def subGovTransition[F[_]: Monad](
      env: GovEnv[Dijkstra],
      signal: GovSignal[Dijkstra],
      netId: Network
  )(using
      state: Stateful[F, Proposals[Dijkstra]],
      judge: Judge[F, SubGovPredFailure[Dijkstra]],
      events: Tell[F, Chain[SubGovEvent[Dijkstra]]],
      defect: Raise[F, GovDefect]
  ): F[Unit] =
    ConwayGov.conwayGovTransition[F, Dijkstra](env, signal, netId)(using
      summon[Monad[F]],
      EraPParams.dijkstra,
      EraCertState.dijkstra,
      EraTxCert.dijkstra,
      InjectGov.dijkstra,
      state,
      wrapped(judge)(DijkstraSubGovPredFailure[Dijkstra](_)),
      told(events)(DijkstraSubGovEvent[Dijkstra](_)),
      defect
    )

/** `data DijkstraSubLedgerPredFailure era` (`Dijkstra/Rules/SubLedger.hs:109-114`, cardano-ledger
  * 9df9f98c), its constructors in the reference's order; their tags are 1, 2, 3 and 5 (`:351-356`).
  */
enum DijkstraSubLedgerPredFailure[E <: EraTag]:
  case SubUtxowFailure(failure: SubUtxowPredFailure[E])
  case SubEntitiesFailure(failure: SubEntitiesRuleFailure[E])
  case SubGovFailure(failure: SubGovPredFailure[E])

  /** The treasury the sub-transaction's body declares (supplied) and the chain's (expected). */
  case SubTreasuryValueMismatch(mismatch: Mismatch[Coin])

object DijkstraSubLedgerPredFailure:
  given [E <: EraTag]: CanEqual[DijkstraSubLedgerPredFailure[E], DijkstraSubLedgerPredFailure[E]] =
    CanEqual.derived

/** `data DijkstraSubLedgerEvent era` (`Dijkstra/Rules/SubLedger.hs:165-169`, cardano-ledger
  * 9df9f98c).
  */
enum DijkstraSubLedgerEvent[E <: EraTag]:
  case SubEntitiesEvent(event: SubEntitiesRuleEvent[E])
  case SubGovEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.SubGovEvent[E])
  case SubUtxowEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.SubUtxowEvent[E])

object DijkstraSubLedgerEvent:
  given [E <: EraTag]: CanEqual[DijkstraSubLedgerEvent[E], DijkstraSubLedgerEvent[E]] =
    CanEqual.derived

/** `EraRuleFailure "SUBLEDGER" era` (`Dijkstra/Rules/SubLedger.hs:144`, cardano-ledger 9df9f98c).
  */
type SubLedgerPredFailure[E <: EraTag] = E match
  case Dijkstra => DijkstraSubLedgerPredFailure[Dijkstra]

/** `EraRuleEvent "SUBLEDGER" era` (`Dijkstra/Rules/SubLedger.hs:146`, cardano-ledger 9df9f98c). */
type SubLedgerEvent[E <: EraTag] = E match
  case Dijkstra => DijkstraSubLedgerEvent[Dijkstra]

/** `data SubLedgerEnv era` (`Dijkstra/Rules/SubLedger.hs:99-107`, cardano-ledger 9df9f98c): the
  * slot, the epoch if known, the parameters, the chain's accounts, the UTxO set and the accounts
  * before the whole transaction, and the validity flag of its top level. SUBLEDGERS gives each
  * sub-transaction the same.
  */
final case class SubLedgerEnv[E <: EraTag](
    sleSlotNo: BigInt,
    sleEpochNo: Option[EpochNo],
    slePParams: PParams[E],
    sleAccount: ChainAccountState,
    sleOriginalUtxo: UTxO[E],
    sleOriginalAccounts: Accounts[E],
    sleTopTxIsPhase2Valid: IsPhase2Valid
)

/** `InjectRuleFailure "SUBLEDGER" f era` (`Core/Era.hs:119-124`, cardano-ledger 9df9f98c):
  * contravariant in `F`, as [[InjectSubEntitiesFailure]] is, so that the instance over the
  * constructors of Conway's LEDGER failure SUBLEDGER's injections take whole serves a predicate
  * declaring one of them.
  */
trait InjectSubLedgerFailure[-F, E <: EraTag]:
  def injectFailure(failure: F): SubLedgerPredFailure[E]

object InjectSubLedgerFailure:
  /** `Dijkstra/Rules/SubLedger.hs:148`, cardano-ledger 9df9f98c: the identity. */
  given dijkstra: InjectSubLedgerFailure[DijkstraSubLedgerPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: DijkstraSubLedgerPredFailure[Dijkstra]
    ): SubLedgerPredFailure[Dijkstra] = failure

  /** `Dijkstra/Rules/SubLedger.hs:150-151`, cardano-ledger 9df9f98c: `SubGovFailure`. */
  given dijkstraSubGov: InjectSubLedgerFailure[DijkstraSubGovPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: DijkstraSubGovPredFailure[Dijkstra]
    ): SubLedgerPredFailure[Dijkstra] = DijkstraSubLedgerPredFailure.SubGovFailure(failure)

  /** `Dijkstra/Rules/SubLedger.hs:153-154`, cardano-ledger 9df9f98c: `SubUtxowFailure`. */
  given dijkstraSubUtxow: InjectSubLedgerFailure[DijkstraSubUtxowPredFailure[Dijkstra], Dijkstra]
  with
    def injectFailure(
        failure: DijkstraSubUtxowPredFailure[Dijkstra]
    ): SubLedgerPredFailure[Dijkstra] = DijkstraSubLedgerPredFailure.SubUtxowFailure(failure)

  /** `Dijkstra/Rules/SubLedger.hs:156-157`, cardano-ledger 9df9f98c: `SubEntitiesFailure`. */
  given dijkstraSubEntities: InjectSubLedgerFailure[SubEntitiesPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: SubEntitiesPredFailure[Dijkstra]): SubLedgerPredFailure[Dijkstra] =
      DijkstraSubLedgerPredFailure.SubEntitiesFailure(failure)

  /** `Dijkstra/Rules/SubLedger.hs:159-160`, cardano-ledger 9df9f98c: `SubEntitiesFailure` after
    * `SubCertsFailure`.
    */
  given dijkstraSubCerts: InjectSubLedgerFailure[DijkstraSubCertsPredFailure[Dijkstra], Dijkstra]
  with
    def injectFailure(
        failure: DijkstraSubCertsPredFailure[Dijkstra]
    ): SubLedgerPredFailure[Dijkstra] = DijkstraSubLedgerPredFailure.SubEntitiesFailure(
      SubEntitiesPredFailure.SubCertsFailure(failure)
    )

  /** `Dijkstra/Rules/SubLedger.hs:162-163`, cardano-ledger 9df9f98c, on the constructors the
    * reference maps whose payloads SUBLEDGER's injections take whole
    * ([[DijkstraSubLedger.ConwayFailure]]).
    */
  given dijkstraConway: InjectSubLedgerFailure[DijkstraSubLedger.ConwayFailure[Dijkstra], Dijkstra]
  with
    def injectFailure(
        failure: DijkstraSubLedger.ConwayFailure[Dijkstra]
    ): SubLedgerPredFailure[Dijkstra] = DijkstraSubLedger.conwayToDijkstraSubLedgerPredFailure(
      failure
    )

/** The `Embed` instances of SUBLEDGER's sub-rules (`Dijkstra/Rules/SubLedger.hs:313-341`,
  * cardano-ledger 9df9f98c): how SUBENTITIES', SUBGOV's and SUBUTXOW's failures and events sit in
  * SUBLEDGER's (`wrapFailed`, `wrapEvent`).
  */
trait EmbedInSubLedger[E <: EraTag]:
  def wrapSubEntitiesFailed(failure: SubEntitiesRuleFailure[E]): SubLedgerPredFailure[E]
  def wrapSubEntitiesEvent(event: SubEntitiesRuleEvent[E]): SubLedgerEvent[E]
  def wrapSubGovFailed(failure: SubGovPredFailure[E]): SubLedgerPredFailure[E]
  def wrapSubGovEvent(event: SubGovEvent[E]): SubLedgerEvent[E]
  def wrapSubUtxowFailed(failure: SubUtxowPredFailure[E]): SubLedgerPredFailure[E]
  def wrapSubUtxowEvent(event: SubUtxowEvent[E]): SubLedgerEvent[E]

object EmbedInSubLedger:
  /** Dijkstra's: SUBLEDGER's constructors of each sub-rule. */
  given dijkstra: EmbedInSubLedger[Dijkstra] with
    def wrapSubEntitiesFailed(
        failure: SubEntitiesRuleFailure[Dijkstra]
    ): SubLedgerPredFailure[Dijkstra] = DijkstraSubLedgerPredFailure.SubEntitiesFailure(failure)
    def wrapSubEntitiesEvent(event: SubEntitiesRuleEvent[Dijkstra]): SubLedgerEvent[Dijkstra] =
      DijkstraSubLedgerEvent.SubEntitiesEvent(event)
    def wrapSubGovFailed(failure: SubGovPredFailure[Dijkstra]): SubLedgerPredFailure[Dijkstra] =
      DijkstraSubLedgerPredFailure.SubGovFailure(failure)
    def wrapSubGovEvent(event: SubGovEvent[Dijkstra]): SubLedgerEvent[Dijkstra] =
      DijkstraSubLedgerEvent.SubGovEvent(event)
    def wrapSubUtxowFailed(failure: SubUtxowPredFailure[Dijkstra]): SubLedgerPredFailure[Dijkstra] =
      DijkstraSubLedgerPredFailure.SubUtxowFailure(failure)
    def wrapSubUtxowEvent(event: SubUtxowEvent[Dijkstra]): SubLedgerEvent[Dijkstra] =
      DijkstraSubLedgerEvent.SubUtxowEvent(event)

/** SUBLEDGER (`Dijkstra/Rules/SubLedger.hs`, cardano-ledger 9df9f98c). */
object DijkstraSubLedger:
  /** The constructors of Conway's LEDGER failure that `conwayToDijkstraSubLedgerPredFailure` maps
    * (`Dijkstra/Rules/SubLedger.hs:373-394`, cardano-ledger 9df9f98c) and whose payloads
    * SUBLEDGER's injections take whole: the four it calls impossible
    * (`ConwayWdrlNotDelegatedToDRep`, `ConwayTxRefScriptsSizeTooBig`, `ConwayMempoolFailure`,
    * `ConwayIncompleteWithdrawals`) left out, and `ConwayUtxowFailure` too, whose payload,
    * Dijkstra's UTXOW failure, the reference injects into SUBUTXOW's partially
    * (`SubUtxow.hs:337-364`) and which no predicate SUBLEDGER runs reports, as UTXOW's
    * `UtxoFailure` is left out of SUBUTXOW's ([[DijkstraSubUtxow]]).
    */
  type ConwayFailure[E <: EraTag] = ConwayLedgerPredFailure.ConwayCertsFailure[E] |
    ConwayLedgerPredFailure.ConwayGovFailure[E] |
    ConwayLedgerPredFailure.ConwayWithdrawalsMissingAccounts[E] |
    ConwayLedgerPredFailure.ConwayTreasuryValueMismatch[E]

  /** `conwayToDijkstraSubLedgerPredFailure` (`Dijkstra/Rules/SubLedger.hs:373-394`, cardano-ledger
    * 9df9f98c), on [[ConwayFailure]]: CERTS's failure SUBENTITIES's, after SUBENTITIES's injection
    * of it; GOV's SUBGOV's, after SUBGOV's; the accounts missing SUBENTITIES's own; the treasury
    * SUBLEDGER's own.
    */
  def conwayToDijkstraSubLedgerPredFailure(
      failure: ConwayFailure[Dijkstra]
  ): DijkstraSubLedgerPredFailure[Dijkstra] = failure match
    case ConwayLedgerPredFailure.ConwayCertsFailure(f) =>
      DijkstraSubLedgerPredFailure.SubEntitiesFailure(
        InjectSubEntitiesFailure.dijkstraCerts.injectFailure(f)
      )
    case ConwayLedgerPredFailure.ConwayGovFailure(f) =>
      DijkstraSubLedgerPredFailure.SubGovFailure(InjectSubGovFailure.dijkstraGov.injectFailure(f))
    case ConwayLedgerPredFailure.ConwayWithdrawalsMissingAccounts(x) =>
      DijkstraSubLedgerPredFailure.SubEntitiesFailure(
        SubEntitiesPredFailure.SubWithdrawalAccountsMissing(x)
      )
    case ConwayLedgerPredFailure.ConwayTreasuryValueMismatch(x) =>
      DijkstraSubLedgerPredFailure.SubTreasuryValueMismatch(x)

  /** `proposalsWithPurpose grCommitteeL proposals` (`Dijkstra/Rules/SubLedger.hs:272`,
    * cardano-ledger 9df9f98c), in the node's record of each action that [[SubEntitiesEnv]] holds
    * (the Gap of [#85's
    * item](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-6923349468)): each action of
    * the committee's purpose, a no-confidence or a committee update, which the flat action holds,
    * as it holds every action but a parameter change.
    */
  def committeeProposals(proposals: Proposals[Dijkstra]): Map[GovActionId, FlatGovActionState] =
    Proposals
      .proposalsWithPurpose[Dijkstra, GovActionPurpose.Committee](
        GovRelation.grCommitteeL,
        proposals
      )
      .valuesIterator
      .flatMap(gas =>
        DijkstraConway
          .proposal(gas.gasProposalProcedure.read)
          .toOption
          .map(p =>
            gas.gasId -> FlatGovActionState(
              gas.gasId,
              gas.gasCommitteeVotes,
              gas.gasDRepVotes,
              gas.gasStakePoolVotes,
              p,
              gas.gasProposedIn.toBigInt,
              gas.gasExpiresAfter.toBigInt
            )
          )
      )
      .toMap

  private val t = EraTx.dijkstra
  private val b = EraTxBody.dijkstra
  private given BothLevels[TxLevel.Sub] = BothLevels.Sub

  /** `dijkstraSubLedgersTransition` of SUBLEDGER (`Dijkstra/Rules/SubLedger.hs:229-311`,
    * cardano-ledger 9df9f98c), every failure recorded and injected into SUBLEDGER's, on the state
    * it is given, the reference's `LedgerState`:
    *   - the treasury the sub-transaction declares against the chain's, whatever the top level's
    *     flag;
    *   - when the top level is flagged valid, SUBENTITIES on the certificate state, with the
    *     committee and its proposals of the governance state, and the accounts before the whole
    *     transaction; then SUBGOV on the proposals, with the sub-transaction's id, the
    *     constitution's guardrails script and the certificate state after SUBENTITIES; flagged
    *     invalid, neither runs and the state is left as it is;
    *   - SUBUTXOW on the UTxO state, with the certificate state SUBLEDGER was given, from before
    *     SUBENTITIES (`SubLedger.hs:248, 307`).
    *
    * Each sub-rule's failures and events are embedded in SUBLEDGER's ([[EmbedInSubLedger]]). The
    * certificate state after is SUBENTITIES', or the one given (`SubLedger.hs:311`).
    *
    * The proposals are the era's ([[Proposals]]), as SUBGOV judges them, in their own capability
    * beside the ledger state: the node's record of them in the governance state cannot hold a
    * Dijkstra parameter change (the Gap of [#85's
    * item](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-6923488137)). The committee
    * and the constitution are the governance state's. The epoch is the environment's, or the slot's
    * by the geometry of `Globals`, as in Conway's LEDGER ([[ConwayLedger]]); an id, a script hash
    * or an epoch outside the state's vocabulary is raised ([[LedgerFailure.Vocab]]), and the
    * sub-rules raise their own defects.
    */
  def dijkstraSubLedgerTransition[F[_]: Monad](
      env: SubLedgerEnv[Dijkstra],
      stAnnTx: StAnnTx[TxLevel.Sub, Dijkstra],
      context: DijkstraUtxoContext,
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id]
  )(using
      ask: Ask[F, Globals],
      state: Stateful[F, LedgerState[Dijkstra]],
      proposals: Stateful[F, Proposals[Dijkstra]],
      judge: Judge[F, SubLedgerPredFailure[Dijkstra]],
      events: Tell[F, Chain[SubLedgerEvent[Dijkstra]]],
      stateDefect: Raise[F, UtxoStateDefect],
      defect: Raise[F, LedgerDefect],
      poolDefect: Raise[F, PoolDefect],
      govDefect: Raise[F, GovDefect]
  ): F[Unit] =
    val embed = EmbedInSubLedger.dijkstra
    val tx = t.txStAnnTx[TxLevel.Sub](stAnnTx)
    val txBody = t.body[TxLevel.Sub](tx)
    val pp = env.slePParams
    val certL = LedgerState.cert[Dijkstra]
    val utxoL = LedgerState.utxo[Dijkstra]
    def test[A](check: Test[A])(using inject: InjectSubLedgerFailure[A, Dijkstra]): F[Unit] =
      Sts.runTest[F, SubLedgerPredFailure[Dijkstra]](check.leftMap(_.map(inject.injectFailure)))
    for
      globals <- ask.ask[Globals]
      curEpochNo <- env.sleEpochNo.fold(
        vocab[F, EpochNo](EpochNo.fromBigInt(globals.epochOf(env.sleSlotNo)))
      )(Monad[F].pure)
      ledgerState <- state.get
      govState = ledgerState.utxo.gov
      committee = govState.committee
      proposalsBefore <- proposals.get
      _ <- test(
        ConwayLedger.validateTreasuryValue[Dijkstra, TxLevel.Sub](
          txBody,
          env.sleAccount.treasury
        )(using b, summon[BothLevels[TxLevel.Sub]])
      )
      _ <- env.sleTopTxIsPhase2Valid match
        case IsPhase2Valid.Phase2Valid =>
          for
            _ <- judge.nest(
              DijkstraSubEntities.dijkstraSubEntitiesTransition[F](
                SubEntitiesEnv[Dijkstra](
                  curEpochNo,
                  pp,
                  committee,
                  committeeProposals(proposalsBefore),
                  env.sleOriginalAccounts
                ),
                tx,
                context.validation
              )(using
                summon[Monad[F]],
                ask,
                state.focus(certL),
                defect,
                poolDefect,
                judge.contramap(embed.wrapSubEntitiesFailed),
                told(events)(embed.wrapSubEntitiesEvent)
              )
            )
            certStateAfterSubEntities <- state.inspect(certL.get)
            txId <- vocab[F, Hash32](Hash32.fromBytes(b.id[TxLevel.Sub](txBody)))
            guardrails <- vocab[F, Option[ScriptHash]](
              govState.constitution.script.traverse(ScriptHash.fromBytes)
            )
            _ <- judge.nest(
              DijkstraSUBGOV.subGovTransition[F](
                GovEnv[Dijkstra](
                  txId,
                  curEpochNo,
                  pp,
                  guardrails,
                  certStateAfterSubEntities,
                  committee
                ),
                GovSignal[Dijkstra](
                  b.votingProcedures[TxLevel.Sub](txBody),
                  b.proposalProcedures[TxLevel.Sub](txBody),
                  b.certs[TxLevel.Sub](txBody)
                ),
                context.netId
              )(using
                summon[Monad[F]],
                proposals,
                judge.contramap(embed.wrapSubGovFailed),
                told(events)(embed.wrapSubGovEvent),
                govDefect
              )
            )
          yield ()
        case IsPhase2Valid.Phase2Invalid => Monad[F].unit
      _ <- judge.nest(
        DijkstraSubUtxow.dijkstraSubUtxowTransition[F](
          SubUtxoEnv[Dijkstra](
            env.sleSlotNo,
            pp,
            ledgerState.cert,
            env.sleOriginalUtxo,
            env.sleTopTxIsPhase2Valid
          ),
          stAnnTx,
          context,
          verdicts,
          decoder
        )(using
          summon[Monad[F]],
          state.focus(utxoL),
          judge.contramap(embed.wrapSubUtxowFailed),
          told(events)(embed.wrapSubUtxowEvent),
          stateDefect,
          defect
        )
      )
    yield ()

/** `newtype DijkstraSubLedgersPredFailure era` (`Dijkstra/Rules/SubLedgers.hs:56-57`,
  * cardano-ledger 9df9f98c), whose encoding is SUBLEDGER's (`:71-77`).
  */
enum DijkstraSubLedgersPredFailure[E <: EraTag]:
  case SubLedgerFailure(failure: SubLedgerPredFailure[E])

object DijkstraSubLedgersPredFailure:
  given [E <: EraTag]: CanEqual[
    DijkstraSubLedgersPredFailure[E],
    DijkstraSubLedgersPredFailure[E]
  ] = CanEqual.derived

/** `newtype DijkstraSubLedgersEvent era` (`Dijkstra/Rules/SubLedgers.hs:98-99`, cardano-ledger
  * 9df9f98c).
  */
enum DijkstraSubLedgersEvent[E <: EraTag]:
  case SubLedgerEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.SubLedgerEvent[E])

object DijkstraSubLedgersEvent:
  given [E <: EraTag]: CanEqual[DijkstraSubLedgersEvent[E], DijkstraSubLedgersEvent[E]] =
    CanEqual.derived

/** `EraRuleFailure "SUBLEDGERS" era` (`Dijkstra/Rules/SubLedgers.hs:87`, cardano-ledger 9df9f98c).
  */
type SubLedgersPredFailure[E <: EraTag] = E match
  case Dijkstra => DijkstraSubLedgersPredFailure[Dijkstra]

/** `EraRuleEvent "SUBLEDGERS" era` (`Dijkstra/Rules/SubLedgers.hs:89`, cardano-ledger 9df9f98c). */
type SubLedgersEvent[E <: EraTag] = E match
  case Dijkstra => DijkstraSubLedgersEvent[Dijkstra]

/** `InjectRuleFailure "SUBLEDGERS" f era` (`Core/Era.hs:119-124`, cardano-ledger 9df9f98c). */
trait InjectSubLedgersFailure[F, E <: EraTag]:
  def injectFailure(failure: F): SubLedgersPredFailure[E]

object InjectSubLedgersFailure:
  /** `Dijkstra/Rules/SubLedgers.hs:91`, cardano-ledger 9df9f98c: the identity. */
  given dijkstra: InjectSubLedgersFailure[DijkstraSubLedgersPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: DijkstraSubLedgersPredFailure[Dijkstra]
    ): SubLedgersPredFailure[Dijkstra] = failure

  /** `Dijkstra/Rules/SubLedgers.hs:93-94`, cardano-ledger 9df9f98c: `SubLedgerFailure`. */
  given dijkstraSubLedger: InjectSubLedgersFailure[
    DijkstraSubLedgerPredFailure[Dijkstra],
    Dijkstra
  ] with
    def injectFailure(
        failure: DijkstraSubLedgerPredFailure[Dijkstra]
    ): SubLedgersPredFailure[Dijkstra] =
      DijkstraSubLedgersPredFailure.SubLedgerFailure(failure)

/** `Embed (SUBLEDGER era) (SUBLEDGERS era)` (`Dijkstra/Rules/SubLedgers.hs:145-153`, cardano-ledger
  * 9df9f98c): how SUBLEDGER's failures and events sit in SUBLEDGERS's.
  */
trait EmbedSubLedgerInSubLedgers[E <: EraTag]:
  def wrapFailed(failure: SubLedgerPredFailure[E]): SubLedgersPredFailure[E]
  def wrapEvent(event: SubLedgerEvent[E]): SubLedgersEvent[E]

object EmbedSubLedgerInSubLedgers:
  /** Dijkstra's: `SubLedgerFailure` and `SubLedgerEvent`. */
  given dijkstra: EmbedSubLedgerInSubLedgers[Dijkstra] with
    def wrapFailed(failure: SubLedgerPredFailure[Dijkstra]): SubLedgersPredFailure[Dijkstra] =
      DijkstraSubLedgersPredFailure.SubLedgerFailure(failure)
    def wrapEvent(event: SubLedgerEvent[Dijkstra]): SubLedgersEvent[Dijkstra] =
      DijkstraSubLedgersEvent.SubLedgerEvent(event)

/** SUBLEDGERS (`Dijkstra/Rules/SubLedgers.hs`, cardano-ledger 9df9f98c). */
object DijkstraSubLedgers:
  /** `dijkstraSubLedgersTransition` (`Dijkstra/Rules/SubLedgers.hs:129-143`, cardano-ledger
    * 9df9f98c): SUBLEDGER on each sub-transaction in order, each a sub-rule run on the state the
    * one before left (`foldM`), the same environment for all; with no sub-transaction the state is
    * left as it is. Each SUBLEDGER's failures and events are embedded in SUBLEDGERS's
    * ([[EmbedSubLedgerInSubLedgers]]). The fold is `foldM`, which `tailRecM` runs in constant
    * stack.
    */
  def dijkstraSubLedgersTransition[F[_]: Monad](
      env: SubLedgerEnv[Dijkstra],
      subTxs: List[StAnnTx[TxLevel.Sub, Dijkstra]],
      context: DijkstraUtxoContext,
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id]
  )(using
      ask: Ask[F, Globals],
      state: Stateful[F, LedgerState[Dijkstra]],
      proposals: Stateful[F, Proposals[Dijkstra]],
      judge: Judge[F, SubLedgersPredFailure[Dijkstra]],
      events: Tell[F, Chain[SubLedgersEvent[Dijkstra]]],
      stateDefect: Raise[F, UtxoStateDefect],
      defect: Raise[F, LedgerDefect],
      poolDefect: Raise[F, PoolDefect],
      govDefect: Raise[F, GovDefect]
  ): F[Unit] =
    val embed = EmbedSubLedgerInSubLedgers.dijkstra
    subTxs.foldM(()) { (_, subTx) =>
      judge.nest(
        DijkstraSubLedger
          .dijkstraSubLedgerTransition[F](env, subTx, context, verdicts, decoder)(using
            summon[Monad[F]],
            ask,
            state,
            proposals,
            judge.contramap(embed.wrapFailed),
            told(events)(embed.wrapEvent),
            stateDefect,
            defect,
            poolDefect,
            govDefect
          )
      )
    }
