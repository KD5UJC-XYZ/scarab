// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{LedgerFailure, PredicateFailure, Tx}
import xyz.kd5ujc.scarab.ledger.rules.{
  ConwayLedger,
  Globals,
  Judge,
  JudgedSts,
  Judgment,
  Ledger,
  LedgerEnv,
  LedgersEnv
}

/** Diagnostic adapter for the native LEDGERS observations. Production block application uses its
  * first-failure policy; this adapter runs the same LEDGER rules with complete accumulation.
  */
private[node] object UpstreamLedgerJudgments:
  /** cardano-ledger f649f975, Shelley/Rules/Ledgers.hs:198–220: fold through the transactions,
    * nesting each LEDGER transition, with the bounded TxIx enumeration `zip [minBound ..]`.
    */
  val ledgers: JudgedSts[LedgersEnv, ConwayLedger, Vector[Tx], PredicateFailure, LedgerFailure] =
    new JudgedSts[LedgersEnv, ConwayLedger, Vector[Tx], PredicateFailure, LedgerFailure]:
      def transition[F[_]: Monad](env: LedgersEnv, txs: Vector[Tx])(using
          Ask[F, Globals],
          Stateful[F, ConwayLedger],
          Raise[F, LedgerFailure],
          Judge[F, PredicateFailure]
      ): F[Unit] =
        txs.take(1 << 16).zipWithIndex.traverse_ { (tx, index) =>
          JudgedSts.trans(
            Ledger,
            LedgerEnv(env.ctx.copy(txIndex = index), env.treasury),
            tx
          )
        }

  def run[Env, S, Sig](rule: JudgedSts[Env, S, Sig, PredicateFailure, LedgerFailure])(
      globals: Globals,
      env: Env,
      state: S,
      signal: Sig
  ): Either[LedgerFailure, S] =
    Judgment
      .run(rule, Judgment.Policy.Accumulate)(globals, env, state, signal)
      .flatMap(_.leftMap(LedgerFailure.Rejected(Bytes.empty, _)))
