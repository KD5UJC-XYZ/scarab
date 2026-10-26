// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{LedgerFailure, MempoolProblem, PredicateFailure, Tx}

/** MEMPOOL (cardano-ledger `f649f975`,
  * `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Mempool.hs`, `mempoolTransition`, lines
  * 103-138): the rule a transaction submitted to the mempool is applied by, whose sub-rule is
  * LEDGER. A transaction none of whose inputs is unspent is probably a duplicate
  * (`ConwayMempoolFailure`), and then nothing else is checked (`whenFailureFreeDefault`).
  * Otherwise, before protocol version 11 (`hardforkConwayDisallowUnelectedCommitteeFromVoting`),
  * the votes of committee members that are not elected are refused here, as GOV refuses them from
  * 11; then LEDGER runs (`trans`).
  */
object Mempool extends JudgedSts[LedgerEnv, ConwayLedger, Tx, PredicateFailure, LedgerFailure]:
  def transition[F[_]: Monad](env: LedgerEnv, tx: Tx)(using
      ask: Ask[F, Globals],
      st: Stateful[F, ConwayLedger],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    for
      ls <- st.inspect(_.state)
      utxo = ls.utxo.utxo.utxo
      _ <- Sts.predicate[F, PredicateFailure](
        tx.body.inputs.exists(utxo.contains),
        PredicateFailure.Mempool(MempoolProblem.AllInputsSpent)
      )
      _ <- Sts.whenFailureFree[F, PredicateFailure](
        for
          _ <-
            if ls.utxo.gov.curPParams.protocolMajor > 10 then Monad[F].unit
            else
              Sts.failOnNonEmpty[F, Vector[xyz.kd5ujc.scarab.ledger.Credential], PredicateFailure](
                Gov.unelected(ls.utxo.gov.committee, ls.cert, tx.body.votes.map(_._1))
              )(_.isEmpty)(voters =>
                PredicateFailure.Mempool(MempoolProblem.UnelectedCommittee(voters))
              )
          _ <- JudgedSts.trans[
            F,
            LedgerEnv,
            ConwayLedger,
            ConwayLedger,
            Tx,
            PredicateFailure,
            LedgerFailure,
            LedgerFailure
          ](Ledger, env, tx)
        yield ()
      )
    yield ()
