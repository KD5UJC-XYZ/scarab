// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.{Order, Show}
import cats.derived.*
import cats.syntax.show.*
import xyz.kd5ujc.scarab.codec.Bytes

// The vocabulary of the ledger-state mirror: the leaf types of the Haskell ledger state under the
// same names, so that a Haskell field and its mirror read alike. They live beside the types the node
// computes with today (`xyz.kd5ujc.scarab.ledger.{Credential, DRep}`, plain `Long`s); the conversions
// between the two belong to the mirror stage, not to this file.
//
// Orderings are the Haskell `Ord` instances, written by hand: a `SortedMap` of the mirror iterates,
// and so encodes, in the order of its key.

/** Why a raw value is not a member of a vocabulary type. */
enum VocabError derives Show:
  case Negative(what: VocabError.Kind, value: BigInt)
  case OutOfRange(what: VocabError.Kind, value: BigInt)
  case WrongHashLength(what: VocabError.Kind, expected: Int, actual: Int)
  case Zero(what: VocabError.Kind)

object VocabError:
  /** The vocabulary type a [[VocabError]] is about. */
  enum Kind derives Show:
    case Coin, CompactCoin, EpochNo, SlotNo, BlockNo, KeyHash, ScriptHash, VRFVerKeyHash, Hash32,
      NonZero

// ---- key roles (`Cardano.Ledger.Keys`: `KeyRole`, a phantom of key hashes and credentials)

/** The role a key hash plays. Phantom: a `KeyHash[Staking]` is not a `KeyHash[StakePool]`. */
sealed trait KeyRole
sealed trait StakePool extends KeyRole
sealed trait Staking extends KeyRole
sealed trait Genesis extends KeyRole
sealed trait GenesisDelegate extends KeyRole
sealed trait BlockIssuer extends KeyRole
sealed trait DRepRole extends KeyRole
sealed trait ColdCommittee extends KeyRole
sealed trait HotCommittee extends KeyRole

/** `Hash.Hash` orders as its bytes, unsigned and lexicographic (`Bytes.compareTo`). */
private val hashOrder: Order[Bytes] = Order.from(_ compareTo _)

// The opaque types below are aliases inside this file, so their orders cannot be summoned from the
// underlying type (the given would resolve to itself): these are the explicit ones.
private val bigOrder: Order[BigInt] = Order.from(_ compare _)
private val longOrder: Order[Long] = Order.from(java.lang.Long.compare(_, _))

private def checked(kind: VocabError.Kind, size: Int, bytes: Bytes): Either[VocabError, Bytes] =
  Either.cond(bytes.size == size, bytes, VocabError.WrongHashLength(kind, size, bytes.size))

// ---- hashes

/** `KeyHash r` (`Hashes.hs:166-168`, `deriving (Show, Eq, Ord)`): Blake2b-224 of a verification
  * key.
  */
opaque type KeyHash[R <: KeyRole] = Bytes

object KeyHash:
  def fromBytes[R <: KeyRole](bytes: Bytes): Either[VocabError, KeyHash[R]] =
    checked(VocabError.Kind.KeyHash, 28, bytes)

  extension [R <: KeyRole](h: KeyHash[R]) def bytes: Bytes = h

  given [R <: KeyRole]: Order[KeyHash[R]] = hashOrder
  given [R <: KeyRole]: Show[KeyHash[R]] = Show.show(h => s"KeyHash(${h.hex})")

/** `ScriptHash` (`Hashes.hs:203-205`, `deriving (Show, Eq, Ord, Generic)`): Blake2b-224 of a
  * script.
  */
opaque type ScriptHash = Bytes

object ScriptHash:
  def fromBytes(bytes: Bytes): Either[VocabError, ScriptHash] =
    checked(VocabError.Kind.ScriptHash, 28, bytes)

  extension (h: ScriptHash) def bytes: Bytes = h

  given Order[ScriptHash] = hashOrder
  given Show[ScriptHash] = Show.show(h => s"ScriptHash(${h.hex})")

/** `VRFVerKeyHash r` (`Hashes.hs:230-232`): Blake2b-256 of a VRF verification key. */
opaque type VRFVerKeyHash = Bytes

object VRFVerKeyHash:
  def fromBytes(bytes: Bytes): Either[VocabError, VRFVerKeyHash] =
    checked(VocabError.Kind.VRFVerKeyHash, 32, bytes)

  extension (h: VRFVerKeyHash) def bytes: Bytes = h

  given Order[VRFVerKeyHash] = hashOrder
  given Show[VRFVerKeyHash] = Show.show(h => s"VRFVerKeyHash(${h.hex})")

/** A 32-byte hash: transaction ids, block hashes, nonces. */
opaque type Hash32 = Bytes

object Hash32:
  def fromBytes(bytes: Bytes): Either[VocabError, Hash32] =
    checked(VocabError.Kind.Hash32, 32, bytes)

  extension (h: Hash32) def bytes: Bytes = h

  given Order[Hash32] = hashOrder
  given Show[Hash32] = Show.show(h => s"Hash32(${h.hex})")

// ---- amounts

/** `Coin` (`Coin.hs:93`, an `Integer`): lovelace, never negative here (`Negative` otherwise). */
opaque type Coin = BigInt

object Coin:
  val zero: Coin = BigInt(0)

  def fromBigInt(n: BigInt): Either[VocabError, Coin] =
    Either.cond(n.signum >= 0, n, VocabError.Negative(VocabError.Kind.Coin, n))

  extension (c: Coin) def value: BigInt = c

  given Order[Coin] = bigOrder
  given Show[Coin] = Show.show(c => s"Coin($c)")

/** `DeltaCoin` (`Coin.hs:115`): a signed change of lovelace; every integer is one. */
opaque type DeltaCoin = BigInt

object DeltaCoin:
  def apply(n: BigInt): DeltaCoin = n

  extension (d: DeltaCoin) def value: BigInt = d

  given Order[DeltaCoin] = bigOrder
  given Show[DeltaCoin] = Show.show(d => s"DeltaCoin($d)")

/** `CompactForm Coin` (`Coin.hs:148`, a `Word64`). Held in a `Long`: the whole supply (4.5e16) is
  * far below 2^63, so a coin from 2^63 up is refused (`OutOfRange`) rather than carried.
  */
opaque type CompactCoin = Long

object CompactCoin:
  def fromCoin(c: Coin): Either[VocabError, CompactCoin] =
    val n = Coin.value(c)
    Either.cond(n.isValidLong, n.toLong, VocabError.OutOfRange(VocabError.Kind.CompactCoin, n))

  extension (c: CompactCoin)
    def value: Long = c
    def toCoin: Coin = BigInt(c)

  given Order[CompactCoin] = longOrder
  given Show[CompactCoin] = Show.show(c => s"CompactCoin($c)")

// ---- chain positions (`Cardano.Slotting`: Word64 newtypes; held in a `Long`, 0 <= n < 2^63)

private def nonNegative(kind: VocabError.Kind, n: Long): Either[VocabError, Long] =
  Either.cond(n >= 0, n, VocabError.Negative(kind, BigInt(n)))

opaque type EpochNo = Long

object EpochNo:
  def fromLong(n: Long): Either[VocabError, EpochNo] = nonNegative(VocabError.Kind.EpochNo, n)

  extension (e: EpochNo)
    def value: Long = e
    def next: EpochNo = e + 1

  given Order[EpochNo] = longOrder
  given Show[EpochNo] = Show.show(e => s"EpochNo($e)")

opaque type SlotNo = Long

object SlotNo:
  def fromLong(n: Long): Either[VocabError, SlotNo] = nonNegative(VocabError.Kind.SlotNo, n)

  extension (s: SlotNo) def value: Long = s

  given Order[SlotNo] = longOrder
  given Show[SlotNo] = Show.show(s => s"SlotNo($s)")

opaque type BlockNo = Long

object BlockNo:
  def fromLong(n: Long): Either[VocabError, BlockNo] = nonNegative(VocabError.Kind.BlockNo, n)

  extension (b: BlockNo) def value: Long = b

  given Order[BlockNo] = longOrder
  given Show[BlockNo] = Show.show(b => s"BlockNo($b)")

/** `NonZero a` (`Cardano.Ledger.BaseTypes`): a number that is not zero, by construction. */
opaque type NonZero[A] = A

object NonZero:
  def from[A](a: A)(using n: Numeric[A]): Either[VocabError, NonZero[A]] =
    Either.cond(n.compare(a, n.zero) != 0, a, VocabError.Zero(VocabError.Kind.NonZero))

  extension [A](z: NonZero[A]) def value: A = z

  given [A: Order]: Order[NonZero[A]] = Order[A]
  given [A: Show]: Show[NonZero[A]] = Show.show(z => s"NonZero(${Show[A].show(z)})")

// ---- credentials

/** `Credential r` (`Credential.hs:98-101`, `deriving Ord`): `ScriptHashObj` is declared first, so
  * it sorts before `KeyHashObj`; within a constructor, by hash.
  */
enum Credential[R <: KeyRole]:
  case ScriptHashObj[R <: KeyRole](hash: ScriptHash) extends Credential[R]
  case KeyHashObj[R <: KeyRole](hash: KeyHash[R]) extends Credential[R]

object Credential:
  // Not derived: kittens would ask for a `Show` of the phantom role.
  given [R <: KeyRole]: Show[Credential[R]] = Show.show {
    case ScriptHashObj(h) => s"ScriptHashObj(${h.show})"
    case KeyHashObj(h)    => s"KeyHashObj(${h.show})"
  }

  given [R <: KeyRole]: Order[Credential[R]] = Order.from {
    case (ScriptHashObj(a), ScriptHashObj(b)) => Order[ScriptHash].compare(a, b)
    case (KeyHashObj(a), KeyHashObj(b))       => Order[KeyHash[R]].compare(a, b)
    case (a, b)                               => Integer.compare(a.ordinal, b.ordinal)
  }

/** `DRep` (`DRep.hs:67-72`, `deriving Ord`): key hash, script hash, always-abstain,
  * always-no-confidence.
  */
enum DRep derives Show:
  case DRepKeyHash(hash: KeyHash[DRepRole])
  case DRepScriptHash(hash: ScriptHash)
  case DRepAlwaysAbstain
  case DRepAlwaysNoConfidence

object DRep:
  given Order[DRep] = Order.from {
    case (DRepKeyHash(a), DRepKeyHash(b))       => Order[KeyHash[DRepRole]].compare(a, b)
    case (DRepScriptHash(a), DRepScriptHash(b)) => Order[ScriptHash].compare(a, b)
    case (a, b)                                 => Integer.compare(a.ordinal, b.ordinal)
  }

/** `WithOrigin a` (`Cardano.Slotting.Slot`, `deriving Ord`): the genesis point, or a value;
  * `Origin` is the least.
  */
enum WithOrigin[+A]:
  case Origin
  case At(value: A)

object WithOrigin:
  given [A: Order]: Order[WithOrigin[A]] = Order.from {
    case (Origin, Origin) => 0
    case (Origin, At(_))  => -1
    case (At(_), Origin)  => 1
    case (At(a), At(b))   => Order[A].compare(a, b)
  }

  given [A: Show]: Show[WithOrigin[A]] = Show.show {
    case Origin => "Origin"
    case At(a)  => s"At(${Show[A].show(a)})"
  }
