// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import munit.Assertions.{assert, assertEquals, fail}
import xyz.kd5ujc.scarab.ledger.{PredicateFailure, Tx}
import xyz.kd5ujc.scarab.ledger.era.EraUtxoRun
import xyz.kd5ujc.scarab.ledger.state.{Conway, LedgerState}

/** The withdrawals the 11.2 and Leios builds check in LEDGER at every version, where the flat rules
  * check them in CERTS below 11 under every build: the named difference
  * (https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7055304484), on the corpus cases
  * whose withdrawal fails, and the reference's form of their refusal, for the suites that compare
  * the era rules with the flat ones (`ConwayLedgerChecksSuite`, `ConwayMempoolRunSuite`).
  */
object MovedWithdrawals:
  /** What the reference's LEDGER makes of a case's one withdrawal under the moved withdrawals
    * (`testIncompleteAndMissingWithdrawals`, `Shelley/Rules/Ledger.hs:341-359`, cardano-ledger
    * f649f975): an account the chain does not hold, or holds on another network, is missing, with
    * the amount; one it holds whose balance the amount does not drain is incomplete, the amount
    * supplied against the balance.
    */
  enum Kind:
    case Missing
    case Incomplete

  /** The corpus cases whose withdrawal the 11.2 and Leios builds refuse in LEDGER below version 11.
    */
  val Cases: Map[String, Kind] = Map(
    "conway-devnet/withdrawal-more-than-balance" -> Kind.Incomplete,
    "conway-devnet/withdrawal-unregistered-account" -> Kind.Missing,
    "conway-devnet/withdrawal-network-flag-true" -> Kind.Missing
  )

  /** The era rules' failures on the case `base` of the moved withdrawals, `label` as judged, of the
    * transaction `flat` in the ledger state `ls`: the flat rules' `failures`, in their order, with
    * CERTS's refusal of the withdrawal in its place replaced by LEDGER's, the account and amount
    * the transaction withdraws, and an incomplete one's balance the chain's.
    */
  def expected(
      label: String,
      base: String,
      flat: Tx,
      ls: LedgerState[Conway],
      failures: List[EraUtxoRun.Failure]
  ): List[EraUtxoRun.Failure] =
    val (account, amount) = flat.body.withdrawals.toVector match
      case Vector(one) => one
      case other       => fail(s"$label: withdrawals $other")
    val ledgers =
      Cases.getOrElse(base, fail(s"$label: not a case of the moved withdrawals")) match
        case Kind.Missing =>
          PredicateFailure.WithdrawalsMissingAccounts(Map(account -> amount.value))
        case Kind.Incomplete =>
          val credential = xyz.kd5ujc.scarab.ledger.rules.Convert
            .credential[xyz.kd5ujc.scarab.ledger.state.Staking](account.credential)
            .fold(e => fail(s"$label: credential $e"), identity)
          val balance = xyz.kd5ujc.scarab.ledger.rules.EraLedger
            .accounts(ls)
            .collectFirst { case (`credential`, view) => view.balance.toBigInt }
            .getOrElse(fail(s"$label: the account is not held"))
          assert(amount.value < balance, s"$label: $amount of $balance")
          PredicateFailure.IncompleteWithdrawals(
            Map(account -> xyz.kd5ujc.scarab.ledger.Mismatch(amount.value, balance))
          )
    val certs: EraUtxoRun.Failure = EraUtxoRun.Failure.Utxo(
      PredicateFailure.Certs(
        xyz.kd5ujc.scarab.ledger.CertsFailure.WithdrawalsNotInRewards(Map(account -> amount.value))
      )
    )
    assertEquals(failures.count(_ == certs), 1, s"$label: the flat CERTS's refusal")
    failures.map(f => if f == certs then EraUtxoRun.Failure.Utxo(ledgers) else f)
