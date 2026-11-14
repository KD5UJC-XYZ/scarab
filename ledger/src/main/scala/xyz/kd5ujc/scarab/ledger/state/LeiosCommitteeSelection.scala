// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.Order
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.crypto.bls.{BlsError, MinSig, ProofOfPossession, VerificationKey}

// The selection of the Leios voting committee of an epoch (CIP-0164), as the Leios testnet's
// build seats it: cardano-ledger 9df9f98c `State/LeiosCommittee.hs:58-93` (`selectLeiosCommittee`),
// `State/SnapShots.hs:444-467` (`mkMarkSnapShot`), `:542-550` (`mkSetSnapShot`) and `:630-636`
// (`leiosCandidates`), with cardano-base c1e1ed8d `cardano-crypto-leios/src/Cardano/Crypto/
// Leios.hs:146-169` (`mkLeiosCommittee`). The 11.1 layout has no committee.

/** `EpochInterval` (cardano-base c1e1ed8d `cardano-slotting/src/Cardano/Slotting/Slot.hs:145-147`):
  * a count of epochs, a `Word32`.
  */
opaque type EpochInterval = Long

object EpochInterval:
  val Max: BigInt = (BigInt(1) << 32) - 1

  /** The interval of one epoch, by which a mark snapshot's committee is ahead of it. */
  val one: EpochInterval = 1L

  def fromBigInt(n: BigInt): Either[LeiosCommitteeError, EpochInterval] =
    Either.cond(
      n >= 0 && n <= Max,
      n.toLong,
      LeiosCommitteeError.OutOfRange(LeiosCommitteeError.Parameter.MaxKeyAge, n)
    )

  extension (i: EpochInterval) def toLong: Long = i

extension (e: EpochNo)
  /** `addEpochInterval` (`Slot.hs:156-158`): `EpochNo (n + fromIntegral m)`, a `Word64` sum, which
    * wraps past 2^64 - 1 as the reference's does.
    */
  def addInterval(i: EpochInterval): EpochNo =
    EpochNo.fromUnsigned(Word64.wrappingAdd(e.word, Word64.fromUnsigned(i.toLong)).bits)

/** The number of seats of a Leios committee, `ppLeiosCommitteeSize` and `msLeiosCommitteeSize`: a
  * `Word16`.
  */
opaque type LeiosCommitteeSize = Int

object LeiosCommitteeSize:
  val Max: BigInt = (BigInt(1) << 16) - 1

  def fromBigInt(n: BigInt): Either[LeiosCommitteeError, LeiosCommitteeSize] =
    Either.cond(
      n >= 0 && n <= Max,
      n.toInt,
      LeiosCommitteeError.OutOfRange(LeiosCommitteeError.Parameter.CommitteeSize, n)
    )

  extension (s: LeiosCommitteeSize) def toInt: Int = s

/** Why Scarab gives no committee for a snapshot. None of these is a case of the reference, whose
  * selection is total: each names a state the reference cannot hold or a failure of the native BLS
  * library, and is a defect, never a keyless seat.
  */
enum LeiosCommitteeError extends DomainError:
  /** A selection parameter outside its word; the Leios snapshot codec refuses such a state. */
  case OutOfRange(parameter: LeiosCommitteeError.Parameter, value: BigInt)

  /** A seated pool whose snapshot is of the 11.1 layout, which has no voting key. */
  case LayoutMismatch(pool: KeyHash[StakePool])

  /** A seated pool's voting key or proof that the reference's strict decoder refuses
    * (`rawDecodeFixedSized`, cardano-base c1e1ed8d `BLS12381/Internal.hs:441-458, 605-623`), so no
    * state of the reference holds it.
    */
  case UndecodableKey(pool: KeyHash[StakePool], cause: BlsError)

  /** The native library failed while checking a seated pool's proof of possession. */
  case PossessionCheckFailed(pool: KeyHash[StakePool], cause: BlsError)

object LeiosCommitteeError:
  enum Parameter:
    case CommitteeSize, MaxKeyAge

object LeiosCommitteeSelection:

  /** `selectLeiosCommittee` (9df9f98c `State/LeiosCommittee.hs:65-93`) over `leiosCandidates` of
    * the snapshot's pools (`State/SnapShots.hs:630-636`): the `size` pools with the most stake,
    * largest first, pools of equal stake by ascending id, each seat weighted by the pool's stored
    * stake ratio. A size of zero is the empty committee, the pools not inspected. A seated pool's
    * key is offered while `epoch < registeredIn + maxKeyAge` (both `Word64`, the sum wrapping); a
    * pool with no key, an aged-out key or a proof of possession that does not verify is seated
    * keyless (`mkLeiosCommittee`, cardano-base c1e1ed8d `Leios.hs:154-169`). Only the keys of
    * seated pools are read or checked.
    *
    * @param epoch
    *   the epoch the committee governs, against which a key's age is judged
    */
  def select(
      epoch: EpochNo,
      maxKeyAge: EpochInterval,
      size: LeiosCommitteeSize,
      pools: SortedMap[KeyHash[StakePool], StakePoolSnapShot]
  ): Either[LeiosCommitteeError, LeiosCommittee] =
    if size.toInt == 0 then Right(LeiosCommittee.empty)
    else
      pools.toVector
        .sorted(using seating)
        .take(size.toInt)
        .traverse { case (pool, p) => seat(epoch, maxKeyAge, pool, p) }
        .map(LeiosCommittee(_))

  /** The committee a Leios mark snapshot memoizes and `mkSetSnapShot` moves into the set position
    * (9df9f98c `State/SnapShots.hs:453-467, 544-550`): selected from the mark snapshot's pools,
    * with its committee size and key age, for the epoch after the one it was taken in
    * (`addEpochInterval epochNo (EpochInterval 1)`, wrapping at 2^64 - 1). The rotation derives it
    * once; it is not stored with the mark snapshot.
    */
  def ofMark(
      mark: SnapShot,
      layout: SnapShots.Layout.Leios
  ): Either[LeiosCommitteeError, LeiosCommittee] =
    for
      size <- LeiosCommitteeSize.fromBigInt(layout.markCommitteeSize)
      age <- EpochInterval.fromBigInt(layout.markMaxKeyAge)
      committee <- select(layout.markEpoch.addInterval(EpochInterval.one), age, size, mark.pools)
    yield committee

  /** `higherStake` (`State/LeiosCommittee.hs:82-83`): `compare (Down stake, poolId)`, both
    * unsigned.
    */
  private val seating: Ordering[(KeyHash[StakePool], StakePoolSnapShot)] = (a, b) =>
    val byStake = Order[CompactCoin].compare(b._2.stake, a._2.stake)
    if byStake != 0 then byStake else Order[KeyHash[StakePool]].compare(a._1, b._1)

  private def seat(
      epoch: EpochNo,
      maxKeyAge: EpochInterval,
      pool: KeyHash[StakePool],
      p: StakePoolSnapShot
  ): Either[LeiosCommitteeError, LeiosSeat] =
    honoured(epoch, maxKeyAge, pool, p.blsKey)
      .flatMap(_.flatTraverse(verified(pool, _)))
      .map(LeiosSeat(p.stakeRatio, _))

  /** `honouredKey` (`State/LeiosCommittee.hs:89-93`). */
  private def honoured(
      epoch: EpochNo,
      maxKeyAge: EpochInterval,
      pool: KeyHash[StakePool],
      key: PoolBlsKey
  ): Either[LeiosCommitteeError, Option[BlsKey]] = key match
    case PoolBlsKey.Node11_1 => Left(LeiosCommitteeError.LayoutMismatch(pool))
    case PoolBlsKey.Leios(state) =>
      Right(
        state
          .filter(s => Order[EpochNo].lt(epoch, s.registeredIn.addInterval(maxKeyAge)))
          .map(_.key)
      )

  /** `mkLeiosCommittee` 's check of one seat (cardano-base c1e1ed8d `Leios.hs:158-169`): the key
    * when its proof of possession verifies, none when it does not.
    */
  private def verified(
      pool: KeyHash[StakePool],
      key: BlsKey
  ): Either[LeiosCommitteeError, Option[Bytes]] =
    for
      vk <- VerificationKey
        .fromBytes(key.key)
        .leftMap(LeiosCommitteeError.UndecodableKey(pool, _))
      pop <- ProofOfPossession
        .fromBytes(key.proofOfPossession)
        .leftMap(LeiosCommitteeError.UndecodableKey(pool, _))
      held <- MinSig.verifyPossession(vk, pop) match
        case Right(())                               => Right(Some(key.key))
        case Left(BlsError.ProofOfPossessionInvalid) => Right(None)
        case Left(other) => Left(LeiosCommitteeError.PossessionCheckFailed(pool, other))
    yield held
