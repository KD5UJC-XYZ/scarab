// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.{Eq, Show}
import cats.derived.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.state.*

/** What the rules of the transaction maintain incrementally, and the definition it must agree with.
  * The old side derived these from the accounts whenever it needed them; the new rules keep them as
  * the reference does (`spsDelegators` by DELEG, the DReps' delegators by DELEG and GOVCERT), and a
  * transaction that forgot to keep one shows here, naming the credential. Pure and total: the tests
  * and the harness's debugging use it, the rules do not.
  */
enum Inconsistency derives Eq, Show:
  /** The account delegates to the pool (it is `pool` in its state), and the pool does not list it.
    */
  case DelegatorMissing(pool: KeyHash[StakePool], account: Credential[Staking])

  /** The pool lists the account as a delegator and the account does not delegate to it. */
  case DelegatorStale(pool: KeyHash[StakePool], account: Credential[Staking])

object Inconsistency:
  private given Show[KeyHash[StakePool]] = KeyHash.given_Show_KeyHash[StakePool]
  private given Show[Credential[Staking]] = Credential.given_Show_Credential[Staking]
  private given Eq[KeyHash[StakePool]] = Eq.fromUniversalEquals
  private given Eq[Credential[Staking]] = Eq.fromUniversalEquals

object Invariants:
  /** The pools' delegators against the accounts' delegations. */
  def poolDelegators(cert: ConwayCertState): Vector[Inconsistency] =
    poolDelegators(
      cert.pState.stakePools,
      cert.dState.accounts.states.iterator.map((c, a) => c -> a.pool)
    )

  /** The same for the certificate state of the eras before Conway, whose DELEG keeps them too. */
  def poolDelegators(cert: ShelleyCertState): Vector[Inconsistency] =
    poolDelegators(
      cert.pState.stakePools,
      cert.dState.accounts.states.iterator.map((c, a) => c -> a.pool)
    )

  private def poolDelegators(
      pools: SortedMap[KeyHash[StakePool], StakePoolState],
      accounts: Iterator[(Credential[Staking], Option[KeyHash[StakePool]])]
  ): Vector[Inconsistency] =
    val delegation = accounts.toMap
    val missing = delegation.iterator.collect {
      case (c, Some(p)) if pools.get(p).exists(!_.delegators.contains(c)) =>
        Inconsistency.DelegatorMissing(p, c)
    }
    val stale = pools.iterator.flatMap { (p, s) =>
      s.delegators.iterator.collect {
        case c if !delegation.get(c).exists(_.contains(p)) => Inconsistency.DelegatorStale(p, c)
      }
    }
    (missing ++ stale).toVector
