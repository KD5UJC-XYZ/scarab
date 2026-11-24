// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.ledger.{Block, LedgerFailure, PosixClock}
import xyz.kd5ujc.scarab.ledger.era.{
  EraTx as EraTxOf,
  EraTxError,
  IsPhase2Valid,
  ProposalsReadError,
  ScriptVerdictUnavailable,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTx
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayMempoolRun,
  ConwayRuleDefect,
  GovDefect,
  UtxoStateDefect
}
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** A Conway transaction as a client or a peer submitted it, read into the era's types in the layout
  * of the pool's ledger: its parts as they were received, with the flag, which a block takes as
  * they are ([[xyz.kd5ujc.scarab.node.forge.BlockBuilder.piece]]), and the transaction in Conway's
  * types (`ConwayTx.decode`), which the rules judge and whose inputs the pool's read set takes
  * ([[inputs]]). The two are set together ([[withIsValid]]), so they hold the same flag.
  */
final case class ConwayRead private (parts: Block.Transaction, tx: ConwayTx):
  /** The id, the hash of the body. */
  def id: Bytes = parts.id

  /** The phase-two flag (`isValidTxL`). */
  def isValid: IsPhase2Valid = tx.isValid

  /** The two forms with the flag `valid` (`tx & isValidTxL .~ valid`). */
  def withIsValid(valid: IsPhase2Valid): ConwayRead =
    ConwayRead(parts.copy(isValid = valid == IsPhase2Valid.Phase2Valid), tx.withIsValid(valid))

  /** What the transaction reads from the UTxO set, which the pool fetches from the store before the
    * rules run: every input of its body, the spent, collateral and reference ones
    * (`allInputsTxBodyF`, `Conway/TxBody.hs:344`, cardano-ledger f649f975), as the block path
    * fetches them ([[xyz.kd5ujc.scarab.ledger.era.conway.ConwayBlock.inputs]]).
    */
  def inputs: Set[xyz.kd5ujc.scarab.ledger.TxIn] =
    EraTxOf.conway.bodyOf.allInputs(
      EraTxOf.conway.body[TxLevel.Top](tx)(using EraTxOf.conway.bodyOf.top)
    )

  /** The transaction as the reference writes it to relay and to list it, `[body, witnesses, flag,
    * auxiliary data]`, each part the bytes it was read from ([[EraInjection.envelope]]).
    */
  lazy val raw: Bytes = EraInjection.envelope(parts, Era.Conway)

  /** `sizeTxF` (`sizeAlonzoTxF`, `Alonzo/Tx.hs:323-332`, cardano-ledger f649f975): the bytes of the
    * body, the witnesses and the auxiliary data in a three-element list, the flag left out.
    */
  def size: Long = EraTxOf.conway.sizeTxF[TxLevel.Top](tx)(using EraTxOf.conway.bodyOf.top)

  /** The execution units of the redeemers (`totExUnits`, `Alonzo/Tx.hs:460-464`, cardano-ledger
    * f649f975).
    */
  def units: ExUnits =
    EraTxOf.conway.getTotalExUnits[TxLevel.Top](tx)(using EraTxOf.conway.bodyOf.top)

  /** `txMeasureAlonzo` 's components but the reference scripts' (`Shelley/Ledger/Mempool.hs:
    * 590-612`, ouroboros-consensus 82ecba32): [[size]] plus [[Capacity.PerTxOverhead]], and
    * [[units]].
    */
  def measure: TxMeasure = TxMeasure(size + Capacity.PerTxOverhead, units.mem, units.steps, 0L)

object ConwayRead:
  /** The transaction `t`, read by the flat decoders, read again by Conway's in `layout`: the
    * projection's failure where the era's decoders refuse what the flat ones read.
    */
  def of(t: EraTx, layout: StateLayout): Either[EraTxError, ConwayRead] =
    ConwayTx.decode(t.parts, layout).map(ConwayRead(t.parts, _))

/** A Conway transaction the pool holds: the transaction with the flag of its verdict, and what it
  * was validated with (`Validated (GenTx (ShelleyBlock proto ConwayEra))`, `ShelleyValidatedTx`,
  * ouroboros-consensus 82ecba32 `Shelley/Ledger/Mempool.hs:138-141`; `ValidatedTx`,
  * [[ConwayMempoolRun.ValidatedTx]]).
  */
final case class ConwayAdmitted(read: ConwayRead, validated: ConwayMempoolRun.ValidatedTx)

/** Why the pool has no verdict on a Conway transaction, none of them a refusal of it: the node's
  * own limits and defects, which no peer is charged for. Conway's rules raise the first five
  * ([[ConwayRuleDefect]]); the rest of its defects are the flat rules' kinds, which keep their
  * routes ([[ConwayUnjudged.rejection]]).
  */
enum ConwayUnjudged extends DomainError:
  /** A script the evaluator gave no verdict for: answered as the flat rules' unavailable script is,
    * the client's service unavailable and the peer's connection closed.
    */
  case Unavailable(cause: ScriptVerdictUnavailable)

  /** A stored output of the transaction's inputs that Conway's types cannot read. */
  case UtxoState(cause: UtxoStateDefect.StoredOutput)

  /** GOV numbered a proposal past the reference's `Word16` index. */
  case Gov(cause: GovDefect.ProposalIndexOverflow)

  /** The node's record of the proposals cannot be read into the era's. */
  case ProposalsRead(cause: ProposalsReadError)

  /** The node's two readings of the transaction disagree: Conway's decoders refuse what the flat
    * ones read ([[ConwayRead.of]]).
    */
  case Projection(cause: EraTxError)

  def message: String = this match
    case Unavailable(cause) =>
      s"no verdict on a script: ${cause.reason}"
    case UtxoState(cause)     => s"a stored output Conway's types cannot read: $cause"
    case Gov(cause)           => s"a proposal past the reference's index: $cause"
    case ProposalsRead(cause) => s"the proposals cannot be read: $cause"
    case Projection(cause)    => s"the era's decoders refuse the transaction: $cause"

object ConwayUnjudged:
  /** What a defect of Conway's rules on the transaction `id` comes to for the pool, each kind
    * placed by a total match with no default, as the block path places them
    * (`MirrorRules.conwayDefect`):
    *   - a defect the flat rules raise too ([[xyz.kd5ujc.scarab.ledger.LedgerDefect]]: a script
    *     named by its redeemer, a verifier's failure, a value outside the state's vocabulary) keeps
    *     the flat rules' route, and so does a quantity of the state out of range
    *     ([[LedgerFailure.StateOutOfRange]]);
    *   - a script the evaluator gave no verdict for, unnamed, is answered as the flat rules' is
    *     ([[ConwayUnjudged.Unavailable]]);
    *   - the rest, which the flat rules have no counterpart for, is no verdict
    *     ([[Rejection.ConwayUnjudged]]): no answer, the submitter not charged.
    */
  def rejection(id: Bytes, defect: ConwayRuleDefect): Rejection = defect match
    case ConwayRuleDefect.Unavailable(cause) =>
      Rejection.ConwayUnjudged(Some(id), Unavailable(cause))
    case ConwayRuleDefect.ScriptUnavailable(failure) => Rejection.Ledger(failure)
    case ConwayRuleDefect.Ledger(cause)              => Rejection.Ledger(cause)
    case ConwayRuleDefect.UtxoState(UtxoStateDefect.Vocab(error)) =>
      Rejection.Ledger(LedgerFailure.StateOutOfRange(id, error))
    case ConwayRuleDefect.UtxoState(cause: UtxoStateDefect.StoredOutput) =>
      Rejection.ConwayUnjudged(Some(id), UtxoState(cause))
    case ConwayRuleDefect.Gov(GovDefect.Vocab(error)) =>
      Rejection.Ledger(LedgerFailure.StateOutOfRange(id, error))
    case ConwayRuleDefect.Gov(cause: GovDefect.ProposalIndexOverflow) =>
      Rejection.ConwayUnjudged(Some(id), Gov(cause))
    case ConwayRuleDefect.ProposalsRead(cause) =>
      Rejection.ConwayUnjudged(Some(id), ProposalsRead(cause))

/** What a Conway transaction is judged with besides the ledger: the slot (`mkMempoolEnv`), and the
  * reference's `Globals` and the UTXO rules' context but for the validation
  * ([[ConwayMempoolRun.Judging]]): the network, the system start and the era history of the clock
  * at the pool's tip, as the block path's `MirrorClock.conway` gives its own, the Plutus backend
  * and the signature verifiers.
  */
final case class ConwayPoolEnv(slot: BigInt, judging: ConwayMempoolRun.Judging)

object ConwayPoolEnv:
  /** The environment of a transaction of the pool, judged at `slot`. A clock with no horizon
    * ([[PosixClock.Total]], pools of tests without a chain) is one era with no end of the
    * geometry's slots ([[DijkstraPoolEnv.horizonOf]]).
    */
  def of(env: MempoolEnv, slot: BigInt): ConwayPoolEnv =
    of(
      env,
      slot,
      env.clock.fold(PosixClock.total(env.globals.systemStart, env.geometry))(_(Era.Conway))
    )

  /** The environment of a transaction judged at `slot` with the clock `clock`, which a forging
    * snapshot gives: the era history of the chain the block of `slot` is forged on, as the block
    * rules translate that block's time (`getSnapshotFor` of the ticked ledger state of the slot,
    * ouroboros-consensus 82ecba32 `Mempool/Query.hs:14-55`).
    */
  def of(env: MempoolEnv, slot: BigInt, clock: PosixClock): ConwayPoolEnv =
    val (start, horizon) = DijkstraPoolEnv.horizonOf(clock, env.rules)
    ConwayPoolEnv(
      slot,
      ConwayMempoolRun.Judging(
        env.rules,
        env.globals.network,
        start,
        horizon,
        clock.posix,
        env.evaluator,
        env.verdicts,
        env.decoder
      )
    )
