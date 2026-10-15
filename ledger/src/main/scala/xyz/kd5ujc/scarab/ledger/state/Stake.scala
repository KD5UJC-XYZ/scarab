// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.Eq
import cats.Show
import cats.derived.*
import monocle.{Focus, Lens}
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.Ptr

// The stake accounts and the instant stake of the ledger state. Both changed shape in Conway, so
// each has a Shelley and a Conway record, chosen by the match types `Accounts[E]` and
// `InstantStake[E]` (`Eras.scala`). Maps iterate in the Haskell order of the key.

/** `ShelleyAccountState` (`Shelley/State/Account.hs:42-51`): one stake account before Conway. */
final case class ShelleyAccountState(
    ptr: Ptr,
    balance: CompactCoin,
    deposit: CompactCoin,
    pool: Option[KeyHash[StakePool]]
) derives Eq,
      Show

object ShelleyAccountState:
  val ptr: Lens[ShelleyAccountState, Ptr] = Focus[ShelleyAccountState](_.ptr)
  val balance: Lens[ShelleyAccountState, CompactCoin] = Focus[ShelleyAccountState](_.balance)
  val deposit: Lens[ShelleyAccountState, CompactCoin] = Focus[ShelleyAccountState](_.deposit)
  val pool: Lens[ShelleyAccountState, Option[KeyHash[StakePool]]] =
    Focus[ShelleyAccountState](_.pool)

/** `ConwayAccountState` (`Conway/State/Account.hs:60-109`): one stake account in Conway. Haskell
  * holds it as a sum of the four delegation cases (`CASNoDelegation`, `CASStakePool`, `CASDRep`,
  * `CASStakePoolAndDRep`); the record is its view (`viewConwayAccountState`, the pattern synonym
  * `ConwayAccountState`), which is also what the encoding is.
  */
final case class ConwayAccountState(
    balance: CompactCoin,
    deposit: CompactCoin,
    pool: Option[KeyHash[StakePool]],
    drep: Option[DRep]
) derives Eq,
      Show

object ConwayAccountState:
  val balance: Lens[ConwayAccountState, CompactCoin] = Focus[ConwayAccountState](_.balance)
  val deposit: Lens[ConwayAccountState, CompactCoin] = Focus[ConwayAccountState](_.deposit)
  val pool: Lens[ConwayAccountState, Option[KeyHash[StakePool]]] =
    Focus[ConwayAccountState](_.pool)
  val drep: Lens[ConwayAccountState, Option[DRep]] = Focus[ConwayAccountState](_.drep)

/** `ShelleyAccounts` (`Shelley/State/Account.hs:93-97`): the accounts and the map from a
  * certificate pointer to the credential it registered.
  */
final case class ShelleyAccounts(
    states: SortedMap[Credential[Staking], ShelleyAccountState],
    ptrs: SortedMap[Ptr, Credential[Staking]]
) derives Eq,
      Show

object ShelleyAccounts:
  val states: Lens[ShelleyAccounts, SortedMap[Credential[Staking], ShelleyAccountState]] =
    Focus[ShelleyAccounts](_.states)
  val ptrs: Lens[ShelleyAccounts, SortedMap[Ptr, Credential[Staking]]] =
    Focus[ShelleyAccounts](_.ptrs)

/** `ConwayAccounts` (`Conway/State/Account.hs:160-162`, a newtype over the account map): the
  * pointer map is gone.
  */
final case class ConwayAccounts(states: SortedMap[Credential[Staking], ConwayAccountState])
    derives Eq,
      Show

object ConwayAccounts:
  val states: Lens[ConwayAccounts, SortedMap[Credential[Staking], ConwayAccountState]] =
    Focus[ConwayAccounts](_.states)

/** `ShelleyInstantStake` (`Shelley/State/Stake.hs:57-61`): the lovelace each credential and each
  * certificate pointer controls through the addresses of the UTxO.
  */
final case class ShelleyInstantStake(
    credentialStake: SortedMap[Credential[Staking], CompactCoin],
    ptrStake: SortedMap[Ptr, CompactCoin]
) derives Eq,
      Show

object ShelleyInstantStake:
  val credentialStake: Lens[ShelleyInstantStake, SortedMap[Credential[Staking], CompactCoin]] =
    Focus[ShelleyInstantStake](_.credentialStake)
  val ptrStake: Lens[ShelleyInstantStake, SortedMap[Ptr, CompactCoin]] =
    Focus[ShelleyInstantStake](_.ptrStake)

/** `ConwayInstantStake` (`Conway/State/Stake.hs:49-53`, a newtype over the credential map): the
  * pointer stake is gone.
  */
final case class ConwayInstantStake(credentialStake: SortedMap[Credential[Staking], CompactCoin])
    derives Eq,
      Show

object ConwayInstantStake:
  val credentialStake: Lens[ConwayInstantStake, SortedMap[Credential[Staking], CompactCoin]] =
    Focus[ConwayInstantStake](_.credentialStake)
