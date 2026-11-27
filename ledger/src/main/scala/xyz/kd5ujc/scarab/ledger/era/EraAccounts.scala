// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import monocle.{Getter, Lens}
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.Ptr
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  Accounts,
  Babbage,
  Coin,
  CompactCoin,
  Conway,
  Dijkstra,
  ConwayAccountState,
  Credential,
  DRep,
  EraTag,
  KeyHash,
  ShelleyAccountState,
  StakePool,
  Staking,
  Withdrawals
}
import xyz.kd5ujc.scarab.ledger.era.Relation.*

/** The state of one stake account in era `E` (`type AccountState era`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/State/Account.hs:89`): one case per era that has
  * its instance of [[EraAccounts]]. The accounts themselves are the state's family
  * ([[xyz.kd5ujc.scarab.ledger.state.Accounts]], `Account.hs:90`). Babbage's is Shelley's
  * (`eras/babbage/impl/src/Cardano/Ledger/Babbage/State/Account.hs:12`, cardano-ledger f649f975).
  */
type AccountState[E <: EraTag] = E match
  case Babbage  => ShelleyAccountState
  case Conway   => ConwayAccountState
  case Dijkstra => ConwayAccountState

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
  given babbage: ShelleyEraAccounts[Babbage] =
    xyz.kd5ujc.scarab.ledger.era.babbage.BabbageState.accounts
  given conway: ConwayEraAccounts[Conway] =
    xyz.kd5ujc.scarab.ledger.era.conway.ConwayState.accounts
  given dijkstra: ConwayEraAccounts[Dijkstra] =
    xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraState.accounts

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

  /** `addToBalanceAccounts` (`State/Account.hs:132-149`, cardano-ledger 9df9f98c): each account of
    * `addBalanceMap` with its amount added to its balance (`<>`, which wraps); an amount of an
    * account that is not registered is dropped (`assert False Nothing`, compiled out).
    */
  def addToBalanceAccounts[E <: EraTag](
      addBalanceMap: Map[Credential[Staking], CompactCoin],
      accounts: Accounts[E]
  )(using a: EraAccounts[E]): Accounts[E] =
    a.accountsMapL.modify(m =>
      addBalanceMap.foldLeft(m) { case (acc, (credential, balanceToAdd)) =>
        acc.updatedWith(credential)(
          _.map(a.balanceAccountStateL.modify(_.add(balanceToAdd)))
        )
      }
    )(accounts)

  /** `updateLookupAccountState` (`State/Account.hs:167-176`, cardano-ledger 9df9f98c): the account
    * of `credential` before `f`, if it is registered, and the accounts with `f` applied to it.
    */
  def updateLookupAccountState[E <: EraTag](
      f: AccountState[E] => AccountState[E],
      credential: Credential[Staking],
      accounts: Accounts[E]
  )(using a: EraAccounts[E]): (Option[AccountState[E]], Accounts[E]) =
    val m = a.accountsMapL.get(accounts)
    m.get(credential) match
      case None      => (None, accounts)
      case Some(old) => (Some(old), a.accountsMapL.replace(m.updated(credential, f(old)))(accounts))

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

  /** The withdrawals of no registered account of the network, and those that do not take their
    * account's whole balance.
    */
  type Undrained = (Withdrawals, Map[AccountAddress, Mismatch[RelEQ, Coin]])

  /** `withdrawalsThatDoNotDrainAccounts` (`State/Account.hs:202-215`, by `categorizeWithdrawals`,
    * `:230-264`, cardano-ledger f649f975): none if every withdrawal drains a registered account of
    * the network `network`; otherwise the withdrawals of an account that is not registered or is on
    * another network, and those that do not take the account's whole balance, with that balance.
    */
  def withdrawalsThatDoNotDrainAccounts[E <: EraTag](
      withdrawals: Withdrawals,
      network: Int,
      accounts: Accounts[E]
  )(using
      a: EraAccounts[E]
  ): Option[Undrained] =
    val found = withdrawals.unWithdrawals.toVector.map((account, amount) =>
      (
        account,
        amount,
        Option
          .when(account.networkId.wire == network)(account.id.credential)
          .flatMap(lookupAccountState[E](_, accounts))
      )
    )
    val missing = found.collect { case (account, amount, None) => account -> amount }.toMap
    val incomplete = found.collect {
      case (account, amount, Some(state))
          if amount.value != a.balanceAccountStateL.get(state).toBigInt =>
        account -> Mismatch[RelEQ, Coin](amount, a.balanceAccountStateL.get(state).toCoin)
    }.toMap
    Option.when(missing.nonEmpty || incomplete.nonEmpty)((Withdrawals(missing), incomplete))

  /** `drainAccounts` (`State/Account.hs:272-277`, by `updateAccountBalances`, `:311-330`,
    * cardano-ledger f649f975): each withdrawn account's balance set to zero, whatever its network;
    * a withdrawal of no registered account changes nothing.
    */
  def drainAccounts[E <: EraTag](withdrawals: Withdrawals, accounts: Accounts[E])(using
      a: EraAccounts[E]
  ): Accounts[E] =
    withdrawals.unWithdrawals.keysIterator.foldLeft(accounts)((acc, account) =>
      adjustAccountState[E](
        a.balanceAccountStateL.replace(CompactCoin.zero),
        account.id.credential,
        acc
      )
    )

/** The stake accounts of the eras before Conway, each registered by a certificate its pointer names
  * (`class ShelleyEraAccounts`, `eras/shelley/impl/src/Cardano/Ledger/Shelley/State/
  * Account.hs:158-193`, cardano-ledger f649f975).
  */
trait ShelleyEraAccounts[E <: EraTag] extends EraAccounts[E]:
  /** `mkShelleyAccountState`: an account with no balance and no delegation, holding `deposit`,
    * registered by the certificate `ptr` names.
    */
  def mkShelleyAccountState(ptr: Ptr, deposit: CompactCoin): AccountState[E]

  /** `accountsPtrsMapL`, which the reference does not export (`Account.hs:173-179`, cardano-ledger
    * f649f975): writing a pointer apart from its account breaks the accounts' invariant, so only
    * the functions below write it, and others read it by [[accountsPtrsMapG]].
    */
  private[era] def accountsPtrsMapL: Lens[Accounts[E], SortedMap[Ptr, Credential[Staking]]]

  /** `accountsPtrsMapG` (`Account.hs:181-183`, cardano-ledger f649f975): each pointer with the
    * credential it registered.
    */
  final def accountsPtrsMapG: Getter[Accounts[E], SortedMap[Ptr, Credential[Staking]]] =
    accountsPtrsMapL.asGetter

  /** `ptrAccountStateG` (`Account.hs:185-193`, cardano-ledger f649f975): the pointer of the
    * certificate that registered the account, which does not change once it is registered.
    */
  def ptrAccountStateG: Getter[AccountState[E], Ptr]

/** The functions of `Shelley/State/Account.hs` (cardano-ledger f649f975) over the class. */
object ShelleyEraAccounts:
  /** `shelleyAddAccountState` (`Shelley/State/Account.hs:195-204`, cardano-ledger f649f975): the
    * account added, and its pointer with it.
    */
  def shelleyAddAccountState[E <: EraTag](
      credential: Credential[Staking],
      accountState: AccountState[E],
      accounts: Accounts[E]
  )(using a: ShelleyEraAccounts[E]): Accounts[E] =
    a.accountsPtrsMapL.modify(_.updated(a.ptrAccountStateG.get(accountState), credential))(
      a.accountsMapL.modify(_.updated(credential, accountState))(accounts)
    )

  /** `registerShelleyAccount` (`Shelley/State/Account.hs:206-219`, cardano-ledger f649f975): a new
    * account of `credential`, registered by the certificate `ptr` names, holding `deposit` and
    * delegating to `stakePool`, added by the class's `addAccountState`.
    */
  def registerShelleyAccount[E <: EraTag](
      credential: Credential[Staking],
      ptr: Ptr,
      deposit: CompactCoin,
      stakePool: Option[KeyHash[StakePool]],
      accounts: Accounts[E]
  )(using a: ShelleyEraAccounts[E]): Accounts[E] =
    a.addAccountState(
      credential,
      a.stakePoolDelegationAccountStateL.replace(stakePool)(a.mkShelleyAccountState(ptr, deposit)),
      accounts
    )

  /** `unregisterShelleyAccount` (`Shelley/State/Account.hs:221-237`, cardano-ledger f649f975): the
    * account as it was, if it was registered, and the accounts without it and without its pointer.
    */
  def unregisterShelleyAccount[E <: EraTag](
      credential: Credential[Staking],
      accounts: Accounts[E]
  )(using a: ShelleyEraAccounts[E]): (Option[AccountState[E]], Accounts[E]) =
    val map = a.accountsMapL.get(accounts)
    val found = map.get(credential)
    val without = a.accountsMapL.replace(map - credential)(accounts)
    (
      found,
      found.fold(without)(s => a.accountsPtrsMapL.modify(_ - a.ptrAccountStateG.get(s))(without))
    )

/** The stake accounts from Conway, which delegate their votes too (`class ConwayEraAccounts`,
  * `eras/conway/impl/src/Cardano/Ledger/Conway/State/Account.hs:281-295`, cardano-ledger f649f975).
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
