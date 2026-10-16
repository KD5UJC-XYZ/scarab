// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Applicative
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import monocle.{Focus, Lens}
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  LedgerFailure,
  PlutusStats,
  PreConwayRules,
  PredicateFailure,
  Script,
  Tx,
  TxOut,
  TxRules,
  Utxo as NodeUtxo
}
import xyz.kd5ujc.scarab.ledger.state.*

// What the rules of the Conway LEDGER share: the node's counters beside the Haskell state, the
// environments (Haskell's `LedgerEnv`, `UtxoEnv`, `CertEnv` ...), the transaction annotated with the
// outputs it resolves (`StAnnTx`), the lenses into the state, and the raising of a refusal.

/** A state with the node's phase-2 counters beside it. The counters are the node's bookkeeping of
  * how many scripts were evaluated (`NodeExtras.plutus`); Haskell's `UTXOS` has no state for them,
  * and keeping them in the state of the rules, not in a side channel, is what lets the rules stay
  * pure and the counters be tested like the rest.
  */
final case class Counted[S](state: S, plutus: PlutusStats)

object Counted:
  def state[S]: Lens[Counted[S], S] = Focus[Counted[S]](_.state)
  def plutus[S]: Lens[Counted[S], PlutusStats] = Focus[Counted[S]](_.plutus)

  /** The same for the ledger state of any era. */
  def utxoOfEra[E <: EraTag]: Lens[Counted[LedgerState[E]], Counted[UTxOState[E]]] =
    Lens[Counted[LedgerState[E]], Counted[UTxOState[E]]](c => Counted(c.state.utxo, c.plutus))(u =>
      c => Counted(c.state.copy[E](utxo = u.state), u.plutus)
    )

  /** The counters beside the UTxO state of a ledger state counted together: what UTXOW sees. */
  val utxoOf: Lens[Counted[LedgerState[Conway]], Counted[UTxOState[Conway]]] =
    Lens[Counted[LedgerState[Conway]], Counted[UTxOState[Conway]]](c =>
      Counted(c.state.utxo, c.plutus)
    )(u => c => Counted(c.state.copy[Conway](utxo = u.state), u.plutus))

/** `LedgerEnv` (`Shelley/Rules/Ledger.hs`): the block-level context of a transaction (slot, epoch,
  * the position in the block, the evaluator and the clock, `TxRules.Env`) and the treasury of the
  * chain account (`esChainAccountState`), which `currentTreasuryValue` of a transaction is checked
  * against. The parameters are the ones in force, read from the governance state.
  */
final case class LedgerEnv(ctx: TxRules.Env, treasury: Coin)

/** A transaction with what its rules resolve from the UTxO set before it (`StAnnTx`, the
  * transaction `AnnotatedTx` of `Alonzo/UTxO.hs`): the outputs of its inputs, reference inputs and
  * collateral, as the set holds them (an input the set does not hold is absent: UTXO refuses it).
  */
final case class StAnnTx(tx: Tx, utxo: NodeUtxo):
  def id: Bytes = tx.body.id

  /** The outputs of the inputs, in the order of the inputs. */
  lazy val spent: Vector[TxOut] = tx.body.inputs.flatMap(utxo.get)

  /** The outputs of the reference inputs. */
  lazy val referenced: Vector[TxOut] = tx.body.referenceInputs.flatMap(utxo.get)

  /** The scripts the transaction can use: its witnesses and the reference scripts of its inputs and
    * reference inputs (`scriptsProvided`).
    */
  lazy val scripts: Map[Bytes, Script] =
    PreConwayRules.providedScripts(tx.witnesses, spent ++ referenced)

  /** The hashes of the scripts it needs, with the redeemer pointers that use each
    * (`scriptsNeeded`); defined for a transaction whose inputs the set holds.
    */
  lazy val needed: Map[Bytes, Set[(Int, BigInt)]] = PreConwayRules.scriptsNeeded(tx.body, spent)

  /** The redeemer pointers of the needed scripts that are Plutus scripts. */
  lazy val plutusPurposes: Set[(Int, BigInt)] =
    needed.collect { case (h, ps) if scripts.get(h).exists(_.kind.isPlutus) => ps }.flatten.toSet

/** The lenses into the Conway certificate state that its rules use (each step of a path is one
  * concrete record, as `ConwayLedgerState` does).
  */
object CertLens:
  val accounts: Lens[ConwayCertState, SortedMap[Credential[Staking], ConwayAccountState]] =
    ConwayCertState.dState
      .andThen(DState.accounts[ConwayAccounts])
      .andThen(ConwayAccounts.states)

  val pools: Lens[ConwayCertState, SortedMap[KeyHash[StakePool], StakePoolState]] =
    ConwayCertState.pState.andThen(PState.stakePools)

  val dreps: Lens[ConwayCertState, SortedMap[Credential[DRepRole], DRepState]] =
    ConwayCertState.vState.andThen(VState.dreps)

  val committee
      : Lens[ConwayCertState, SortedMap[Credential[ColdCommittee], CommitteeAuthorization]] =
    ConwayCertState.vState.andThen(VState.committeeState).andThen(CommitteeState.creds)

/** What a rule of the transaction does when it refuses: the failure the node has always reported
  * for it, `LedgerFailure.Rejected` of the transaction and the nested predicate failure.
  */
private[rules] object Refuse:
  /** Raises `failure` for the transaction `id`. */
  def apply[F[_]: Applicative, A](id: Bytes, failure: PredicateFailure)(using
      Raise[F, LedgerFailure]
  ): F[A] = (LedgerFailure.Rejected(id, failure): LedgerFailure).raise[F, A]

  /** `cond ?! failure`: raises unless `cond` holds. */
  def unless[F[_]: Applicative](cond: Boolean, id: Bytes, failure: => PredicateFailure)(using
      Raise[F, LedgerFailure]
  ): F[Unit] = Sts.check(cond, LedgerFailure.Rejected(id, failure))

  /** The value of a conversion into the state's vocabulary, or its refusal. */
  def vocab[F[_]: Applicative, A](result: Either[VocabError, A])(using
      Raise[F, LedgerFailure]
  ): F[A] = Sts.lift[F, LedgerFailure, A](result.leftMap(LedgerFailure.Vocab(_)))
