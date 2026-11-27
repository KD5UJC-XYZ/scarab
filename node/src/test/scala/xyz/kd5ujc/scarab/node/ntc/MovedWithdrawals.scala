// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

/** The withdrawals the 11.2 and Leios builds check in LEDGER at every version, where the flat
  * rules, since deleted, checked them in CERTS below 11 under every build
  * (https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7055304484), on the corpus cases
  * whose withdrawal fails, and the reference's form of their refusal, for the suites that compared
  * the era rules with the flat ones and hold them to their recorded judgments
  * (`ConwayLedgerChecksSuite`, `ConwayMempoolRunSuite`).
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
