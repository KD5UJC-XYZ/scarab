// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.syntax.either.*
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.crypto.bls.{BlsError, ProofOfPossession, VerificationKey}
import xyz.kd5ujc.scarab.ledger.LedgerDecodeError
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.given
import xyz.kd5ujc.scarab.models.runtime.{
  Codec,
  ConstraintViolation,
  DecodeError,
  Decoded,
  Node,
  Writer,
  at
}
import xyz.kd5ujc.scarab.protocol.StateLayout

import DecodeError.Kind
import Wire.*

/** `StakePoolState` without the voting key: the ten fields of the 11.1 layout, in their order. */
private[state] final case class StakePoolState11(
    vrf: VRFVerKeyHash,
    pledge: Coin,
    cost: Coin,
    margin: Rational,
    accountId: Credential[Staking],
    owners: SortedSet[KeyHash[Staking]],
    relays: Bytes,
    metadata: Option[Bytes],
    deposit: CompactCoin,
    delegators: SortedSet[Credential[Staking]]
)

/** The certificate state: accounts, delegation, pools, DReps and the committee state (the
  * `State/CertState.hs`, `State/Account.hs` and `State/StakePool.hs` of `cardano-ledger-core`, and
  * the era packages' `CertState` s). Every codec takes the encoding version, which decides the tags
  * of its sets and the shape of its pointers.
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

  // ---- the Leios voting keys (cardano-ledger 9df9f98c `State/StakePool.hs:490-570`)

  /** A byte string `decodeFixedSized` reads (cardano-ledger 9df9f98c `Binary/Decoding/Decoder.hs:
    * 355-367`): definite below version 12, either form from it (`decodeBytes`), `size` bytes that
    * `valid` accepts (`rawDecodeFixedSized`); written definite (`encodeFixedSized`).
    */
  private def fixedSized(size: Int, valid: Bytes => Either[BlsError, Unit])(using
      v: ProtVer
  ): Codec[Bytes] =
    new Codec[Bytes]:
      private val sized = Codec.bytesSized(size, size)
      def decode(n: Node): Decoded[Bytes] =
        if n.isIndefinite && !v.atLeast(12) then Left(Codec.mismatch("definite byte string", n))
        else
          sized
            .decode(n)
            .flatMap(b =>
              valid(b).bimap(
                e => DecodeError.at(n, Kind.Domain(LedgerDecodeError.LeiosCertificate(e))),
                _ => b
              )
            )
      def encode(a: Bytes, out: Writer): Unit = out.bytes(a)
      override def check(a: Bytes): Option[ConstraintViolation] = sized.check(a)

  /** A BLS12-381 MinSig verification key (`VerKeyDSIGN BLS12381MinSigDSIGN`): a compressed G2 point
    * of the subgroup, not the identity (cardano-base c1e1ed8d `cardano-crypto-class/src/Cardano/
    * Crypto/DSIGN/BLS12381/Internal.hs:441-458`).
    */
  def blsVerificationKey(using ProtVer): Codec[Bytes] =
    fixedSized(96, b => VerificationKey.fromBytes(b).map(_ => ()))

  /** `BlsKey` (9df9f98c `State/StakePool.hs:532-542`): `[verification key, proof of possession]`,
    * the proof a compressed G1 point of the subgroup, not the identity (cardano-base c1e1ed8d
    * `Internal.hs:605-623`).
    */
  given blsKey(using ProtVer): Codec[BlsKey] =
    Record.of[BlsKey](
      (blsVerificationKey, fixedSized(48, b => ProofOfPossession.fromBytes(b).map(_ => ())))
    )

  /** `BlsKeyState` (9df9f98c `State/StakePool.hs:559-570`): `[key, registration epoch]`. */
  given blsKeyState(using ProtVer): Codec[BlsKeyState] = Record.of[BlsKeyState]((blsKey, epochNo))

  /** The voting key of a pool in the Leios layout, `StrictMaybe BlsKeyState` (`spsBlsKey`,
    * `spssBlsKey`); a value of the 11.1 layout is not written.
    */
  def leiosPoolBlsKey(using ProtVer): Codec[PoolBlsKey] = new Codec[PoolBlsKey]:
    private val key = strictMaybe(blsKeyState)
    def decode(n: Node): Decoded[PoolBlsKey] = key.decode(n).map(PoolBlsKey.Leios(_))
    def encode(a: PoolBlsKey, out: Writer): Unit = a match
      case PoolBlsKey.Leios(k) => key.encode(k, out)
      case PoolBlsKey.Node11_1 => () // refused by `check`
    override def check(a: PoolBlsKey): Option[ConstraintViolation] = a match
      case PoolBlsKey.Leios(k) => key.check(k)
      case PoolBlsKey.Node11_1 =>
        Some(ConstraintViolation("PoolBlsKey", "a part of the Leios layout", "Node11_1"))

  /** `StakePoolState` (`StakePool.hs:192-203`): the pool parameters less its id, with the deposit
    * and the delegators. Here the relays are a sequence and the metadata a `StrictMaybe` (a list of
    * zero or one), where `StakePoolParams` writes it `null` able. The 11.1 layout writes ten
    * fields; the Leios layout eleven, the voting key after the VRF key hash (9df9f98c
    * `State/StakePool.hs:220-266`).
    */
  given stakePoolState(using ProtVer): Codec[StakePoolState] = stateLayout match
    case StateLayout.Node11_1 =>
      val record = Record.of[StakePoolState11](
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
      new Codec[StakePoolState]:
        def decode(n: Node): Decoded[StakePoolState] = record.decode(n).map { p =>
          StakePoolState(
            p.vrf,
            PoolBlsKey.Node11_1,
            p.pledge,
            p.cost,
            p.margin,
            p.accountId,
            p.owners,
            p.relays,
            p.metadata,
            p.deposit,
            p.delegators
          )
        }
        private def view(p: StakePoolState): StakePoolState11 =
          StakePoolState11(
            p.vrf,
            p.pledge,
            p.cost,
            p.margin,
            p.accountId,
            p.owners,
            p.relays,
            p.metadata,
            p.deposit,
            p.delegators
          )
        def encode(a: StakePoolState, out: Writer): Unit = record.encode(view(a), out)
        override def check(a: StakePoolState): Option[ConstraintViolation] =
          ofThisLayout("StakePoolState", a.blsKey)
            .map(_.at("blsKey"))
            .orElse(record.check(view(a)))
    case StateLayout.Leios =>
      Record.of[StakePoolState](
        (
          vrfVerKeyHash,
          leiosPoolBlsKey,
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
