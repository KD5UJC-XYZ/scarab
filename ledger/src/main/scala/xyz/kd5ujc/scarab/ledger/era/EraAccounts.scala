// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import monocle.Lens
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.state.{
  Accounts,
  Coin,
  CompactCoin,
  Conway,
  ConwayAccountState,
  Credential,
  DRep,
  EraTag,
  KeyHash,
  StakePool,
  Staking
}

/** The state of one stake account in era `E` (`type AccountState era`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/State/Account.hs:89`): one case per era that has
  * its instance of [[EraAccounts]]. The accounts themselves are the state's family
  * ([[xyz.kd5ujc.scarab.ledger.state.Accounts]], `Account.hs:90`).
  */
type AccountState[E <: EraTag] = E match
  case Conway => ConwayAccountState

/** The stake accounts of era `E` (`class EraAccounts`, `State/Account.hs:67-117`, cardano-ledger
  * f649f975).
  */
trait EraAccounts[E <: EraTag]:
  /** `addAccountState`: the account added, whether or not it was registered. */
  def addAccountState(
      credential: Credential[Staking],
      accountState: AccountState[E],
      accounts: Accounts[E]
  ): Accounts[E]

  def accountsMapL: Lens[Accounts[E], SortedMap[Credential[Staking], AccountState[E]]]
  def balanceAccountStateL: Lens[AccountState[E], CompactCoin]
  def depositAccountStateL: Lens[AccountState[E], CompactCoin]
  def stakePoolDelegationAccountStateL: Lens[AccountState[E], Option[KeyHash[StakePool]]]

  /** `unregisterAccount`: the account as it was, if it was registered, and the accounts without it.
    * The DReps' and the pools' delegators are not changed.
    */
  def unregisterAccount(
      credential: Credential[Staking],
      accounts: Accounts[E]
  ): (Option[AccountState[E]], Accounts[E])

/** The instances of each era, the one place that lists the eras having each accounts class, and the
  * functions of `State/Account.hs` (cardano-ledger f649f975) over the class.
  */
object EraAccounts:
  given conway: ConwayEraAccounts[Conway] =
    xyz.kd5ujc.scarab.ledger.era.conway.ConwayState.accounts

  /** `sumBalancesAccounts` (`State/Account.hs:119-121`, cardano-ledger f649f975): the compact
    * balances summed as `CompactForm Coin` 's monoid sums them.
    */
  def sumBalancesAccounts[E <: EraTag](accounts: Accounts[E])(using a: EraAccounts[E]): Coin =
    CompactCoin
      .sum(a.accountsMapL.get(accounts).valuesIterator.map(a.balanceAccountStateL.get))
      .toCoin

  /** `sumDepositsAccounts` (`State/Account.hs:123-125`, cardano-ledger f649f975). */
  def sumDepositsAccounts[E <: EraTag](accounts: Accounts[E])(using a: EraAccounts[E]): Coin =
    CompactCoin
      .sum(a.accountsMapL.get(accounts).valuesIterator.map(a.depositAccountStateL.get))
      .toCoin

  /** `lookupAccountState` (`State/Account.hs:151-153`, cardano-ledger f649f975). */
  def lookupAccountState[E <: EraTag](credential: Credential[Staking], accounts: Accounts[E])(using
      a: EraAccounts[E]
  ): Option[AccountState[E]] = a.accountsMapL.get(accounts).get(credential)

  /** `lookupAccountDeposit` (`State/Account.hs:162-164`, cardano-ledger f649f975). */
  def lookupAccountDeposit[E <: EraTag](credential: Credential[Staking], accounts: Accounts[E])(
      using a: EraAccounts[E]
  ): Option[Coin] =
    lookupAccountState[E](credential, accounts).map(a.depositAccountStateL.get(_).toCoin)

  /** `isAccountRegistered` (`State/Account.hs:178-179`, cardano-ledger f649f975). */
  def isAccountRegistered[E <: EraTag](credential: Credential[Staking], accounts: Accounts[E])(using
      a: EraAccounts[E]
  ): Boolean = a.accountsMapL.get(accounts).contains(credential)

  /** `adjustAccountState` (`State/Account.hs:181-184`, cardano-ledger f649f975): no change to an
    * account that is not registered.
    */
  def adjustAccountState[E <: EraTag](
      f: AccountState[E] => AccountState[E],
      credential: Credential[Staking],
      accounts: Accounts[E]
  )(using a: EraAccounts[E]): Accounts[E] =
    a.accountsMapL.modify(_.updatedWith(credential)(_.map(f)))(accounts)

  /** `lookupStakePoolDelegation` (`State/Account.hs:188-195`, cardano-ledger f649f975). */
  def lookupStakePoolDelegation[E <: EraTag](
      credential: Credential[Staking],
      accounts: Accounts[E]
  )(using a: EraAccounts[E]): Option[KeyHash[StakePool]] =
    lookupAccountState[E](credential, accounts).flatMap(a.stakePoolDelegationAccountStateL.get)

/** The stake accounts from Conway, which delegate their votes too (`class ConwayEraAccounts`,
  * `eras/conway/impl/src/Cardano/Ledger/Conway/State/Account.hs:281-294`, cardano-ledger f649f975).
  */
trait ConwayEraAccounts[E <: EraTag] extends EraAccounts[E]:
  /** `mkConwayAccountState`: an account with no balance and no delegation, holding `deposit`. */
  def mkConwayAccountState(deposit: CompactCoin): AccountState[E]

  def dRepDelegationAccountStateL: Lens[AccountState[E], Option[DRep]]

/** The functions of `Conway/State/Account.hs` (cardano-ledger f649f975) over the class. */
object ConwayEraAccounts:
  /** `lookupDRepDelegation` (`Conway/State/Account.hs:301-304`, cardano-ledger f649f975). */
  def lookupDRepDelegation[E <: EraTag](credential: Credential[Staking], accounts: Accounts[E])(
      using a: ConwayEraAccounts[E]
  ): Option[DRep] =
    EraAccounts
      .lookupAccountState[E](credential, accounts)
      .flatMap(a.dRepDelegationAccountStateL.get)

  /** `registerConwayAccount` (`Conway/State/Account.hs:306-324`, cardano-ledger f649f975): a new
    * account holding `deposit`, delegating to what `delegatee` names.
    */
  def registerConwayAccount[E <: EraTag](
      credential: Credential[Staking],
      deposit: CompactCoin,
      delegatee: Option[Delegatee],
      accounts: Accounts[E]
  )(using a: ConwayEraAccounts[E]): Accounts[E] =
    val fresh = a.mkConwayAccountState(deposit)
    val accountState = delegatee.fold(fresh)(d =>
      a.dRepDelegationAccountStateL.replace(Delegatee.getDRepDelegatee(d))(
        a.stakePoolDelegationAccountStateL.replace(Delegatee.getStakePoolDelegatee(d))(fresh)
      )
    )
    a.accountsMapL.modify(_.updated(credential, accountState))(accounts)

  /** `unregisterConwayAccount` (`Conway/State/Account.hs:326-338`, cardano-ledger f649f975): the
    * account as it was, if it was registered, and the accounts without it.
    */
  def unregisterConwayAccount[E <: EraTag](
      credential: Credential[Staking],
      accounts: Accounts[E]
  )(using a: EraAccounts[E]): (Option[AccountState[E]], Accounts[E]) =
    val map = a.accountsMapL.get(accounts)
    (map.get(credential), a.accountsMapL.replace(map - credential)(accounts))

  /** `accountStateDelegatee` (`Conway/State/Account.hs:340-344`, cardano-ledger f649f975). */
  def accountStateDelegatee[E <: EraTag](accountState: AccountState[E])(using
      a: ConwayEraAccounts[E]
  ): Option[Delegatee] =
    Delegatee.mkDelegatee(
      a.stakePoolDelegationAccountStateL.get(accountState),
      a.dRepDelegationAccountStateL.get(accountState)
    )
