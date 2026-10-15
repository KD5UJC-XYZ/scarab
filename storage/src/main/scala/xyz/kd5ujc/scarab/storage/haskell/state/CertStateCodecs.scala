// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.given
import xyz.kd5ujc.scarab.models.runtime.{Codec, Decoded, Node, Writer, at}

import Wire.*

/** The certificate state: accounts, delegation, pools, DReps and the committee state (the
  * `State/CertState.hs`, `State/Account.hs` and `State/StakePool.hs` of `cardano-ledger-core`, and
  * the era packages' `CertState`s). Every codec takes the encoding version, which decides the
  * tags of its sets and the shape of its pointers.
  */
trait CertStateCodecs extends NodeTypeCodecs:
  /** `FutureGenDeleg` (`CertState.hs:110-112`): `[slot, genesis key hash]`. */
  given futureGenDeleg: Codec[FutureGenDeleg] = Record.auto[FutureGenDeleg]

  /** `InstantaneousRewards` (`CertState.hs:403-405`): the two pots' rewards and the two deltas. */
  given instantaneousRewards(using ProtVer): Codec[InstantaneousRewards] =
    Record.of[InstantaneousRewards](
      (
        mapOf(credential[Staking], coin),
        mapOf(credential[Staking], coin),
        deltaCoin,
        deltaCoin
      )
    )

  /** `ShelleyAccountState` (`Shelley/State/Account.hs:60-66`): `[pointer, balance, deposit, pool]`,
    * the pool `null` when there is no delegation.
    */
  given shelleyAccountState(using ProtVer): Codec[ShelleyAccountState] =
    Record.of[ShelleyAccountState](
      (ptr, compactCoin, compactCoin, nullable(keyHash[StakePool]))
    )

  /** `ConwayAccountState` (`Conway/State/Account.hs:127-134`): `[balance, deposit, pool, DRep]`,
    * each delegation `null` when there is none.
    */
  given conwayAccountState: Codec[ConwayAccountState] =
    Record.of[ConwayAccountState](
      (compactCoin, compactCoin, nullable(keyHash[StakePool]), nullable(drep))
    )

  /** `ShelleyAccounts` (`Shelley/State/Account.hs:108-110`): the accounts and the pointer map. */
  given shelleyAccounts(using ProtVer): Codec[ShelleyAccounts] =
    Record.of[ShelleyAccounts](
      (mapOf(credential[Staking], shelleyAccountState), mapOf(ptr, credential[Staking]))
    )

  /** `ConwayAccounts` (`Conway/State/Account.hs:160-164`): the account map itself. */
  given conwayAccounts(using ProtVer): Codec[ConwayAccounts] =
    mapOf(credential[Staking], conwayAccountState).imap(ConwayAccounts(_))(_.states)

  /** `GenDelegPair`s keyed by the genesis key hash. */
  private def genDelegs(using ProtVer) = mapOf(keyHash[Genesis], genDelegPair)

  /** `DState` (`CertState.hs:189-196`): `[accounts, future genesis delegations, genesis
    * delegations, instantaneous rewards]`. The accounts are those of the era (`A`).
    */
  given dState[A <: AccountsRecord](using accounts: Codec[A], v: ProtVer): Codec[DState[A]] =
    Record.of[DState[A]](
      (
        accounts,
        mapOf(futureGenDeleg, genDelegPair),
        genDelegs,
        instantaneousRewards
      )
    )

  /** `StakePoolState` (`StakePool.hs:192-203`): the pool parameters less its id, with the deposit
    * and the delegators. Here the relays are a sequence and the metadata a `StrictMaybe` (a list of
    * zero or one), where `StakePoolParams` writes it `null`able.
    */
  given stakePoolState(using ProtVer): Codec[StakePoolState] =
    Record.of[StakePoolState](
      (
        vrfVerKeyHash,
        coin,
        coin,
        unitInterval,
        credential[Staking],
        setOf(keyHash[Staking]),
        relays,
        strictMaybe(rawItem),
        compactCoin,
        setOf(credential[Staking])
      )
    )

  /** `PState` (`CertState.hs:253-255`): the VRF key hashes with their number of registrations, the
    * pools, their future parameters and their retirements.
    */
  given pState(using ProtVer): Codec[PState] =
    Record.of[PState](
      (
        mapOf(vrfVerKeyHash, nonZero[Word64]),
        mapOf(keyHash[StakePool], stakePoolState),
        mapOf(keyHash[StakePool], poolParams),
        mapOf(keyHash[StakePool], epochNo)
      )
    )

  /** `DRepState` (`DRep.hs:165-198`): `[expiry, anchor, deposit, delegators]`. */
  given drepState(using ProtVer): Codec[DRepState] =
    Record.of[DRepState](
      (epochNo, strictMaybe(anchor), compactCoin, setOf(credential[Staking]))
    )

  /** `CommitteeAuthorization` (`CertState.hs:315-319`): `[0, hot credential]` or `[1, anchor]`. */
  given committeeAuthorization(using ProtVer): Codec[CommitteeAuthorization] =
    new Codec[CommitteeAuthorization]:
      private val hot = credential[HotCommittee]
      private val resigned = strictMaybe(anchor)
      def decode(n: Node): Decoded[CommitteeAuthorization] =
        variant(n).flatMap {
          case (t, Vector(c)) if t == 0 =>
            hot.decode(c).map(CommitteeAuthorization.CommitteeHotCredential(_)).at("credential")
          case (t, Vector(a)) if t == 1 =>
            resigned.decode(a).map(CommitteeAuthorization.CommitteeMemberResigned(_)).at("anchor")
          case (t, xs) => unknownVariant(n, "CommitteeAuthorization", t, xs.size)
        }
      def encode(a: CommitteeAuthorization, out: Writer): Unit =
        out.arrayHead(2)
        a match
          case CommitteeAuthorization.CommitteeHotCredential(c) =>
            out.uint(0L)
            hot.encode(c, out)
          case CommitteeAuthorization.CommitteeMemberResigned(x) =>
            out.uint(1L)
            resigned.encode(x, out)

  /** `CommitteeState` (`CertState.hs:328-331`, a newtype over the map). */
  given committeeState(using ProtVer): Codec[CommitteeState] =
    mapOf(credential[ColdCommittee], committeeAuthorization).imap(CommitteeState(_))(_.creds)

  /** `VState` (`VState.hs:101-107`): `[DReps, committee state, dormant epochs]`. */
  given vState(using ProtVer): Codec[VState] =
    Record.of[VState](
      (mapOf(credential[DRepRole], drepState), committeeState, epochNo)
    )

  /** `ShelleyCertState` (`Shelley/State/CertState.hs:118-122`): `[PState, DState]`. */
  given shelleyCertState(using ProtVer): Codec[ShelleyCertState] =
    Record.of[ShelleyCertState]((pState, dState[ShelleyAccounts]))

  /** `ConwayCertState` (`Conway/State/CertState.hs:142-147`): `[VState, PState, DState]`. */
  given conwayCertState(using ProtVer): Codec[ConwayCertState] =
    Record.of[ConwayCertState]((vState, pState, dState[ConwayAccounts]))
