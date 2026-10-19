// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import xyz.kd5ujc.scarab.codec.DomainError
import cats.{Order, Show}
import cats.derived.*
import cats.syntax.show.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}

// The vocabulary of the ledger-state mirror: the leaf types of the Haskell ledger state under the
// same names, so that a Haskell field and its mirror read alike. They live beside the types the node
// computes with today (`xyz.kd5ujc.scarab.ledger.{Credential, DRep}`, plain `Long`s); the conversions
// between the two belong to the mirror stage, not to this file.
//
// Orderings are the Haskell `Ord` instances, written by hand: a `SortedMap` of the mirror iterates,
// and so encodes, in the order of its key.

/** Why a raw value is not a member of a vocabulary type. */
enum VocabError extends DomainError derives Show:
  case Negative(what: VocabError.Kind, value: BigInt)
  case OutOfRange(what: VocabError.Kind, value: BigInt)
  case WrongHashLength(what: VocabError.Kind, expected: Int, actual: Int)
  case Zero(what: VocabError.Kind)
  case InvalidRewardRelativeStake(value: Rational)

object VocabError:
  /** The vocabulary type a [[VocabError]] is about. */
  enum Kind derives Show:
    case Coin, CompactCoin, EpochNo, SlotNo, BlockNo, BlockCount, KeyHash, ScriptHash,
      VRFVerKeyHash, Hash32, NonZero, OcertCounter, RewardPulseSize, RewardBlockCount,
      ProtocolMajor, ProtocolMinor

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
private val hashOrder: Order[Bytes] = new SortOrder[Bytes]:
  def compare(a: Bytes, b: Bytes): Int = a.compareTo(b)

// The opaque types below are aliases inside this file, so their orders cannot be summoned from the
// underlying type (the given would resolve to itself): these are the explicit ones.
private val bigOrder: Order[BigInt] = Order.from(_ compare _)

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

  /** The one lovelace that stands in for an empty total (`knownNonZeroCoin @1`,
    * `State/SnapShots.hs:391`, `State/Stake.hs:159`): a total used as a denominator is never zero.
    */
  val oneNonZero: NonZero[Coin] = BigInt(1)

  def fromBigInt(n: BigInt): Either[VocabError, Coin] =
    Either.cond(n.signum >= 0, n, VocabError.Negative(VocabError.Kind.Coin, n))

  extension (c: Coin) def value: BigInt = c

  given HasZero[Coin] = HasZero.instance(_.signum == 0)
  given Order[Coin] = bigOrder
  given Show[Coin] = Show.show(c => s"Coin($c)")

/** `DeltaCoin` (`Coin.hs:115`): a signed change of lovelace; every integer is one. */
opaque type DeltaCoin = BigInt

object DeltaCoin:
  def apply(n: BigInt): DeltaCoin = n

  extension (d: DeltaCoin) def value: BigInt = d

  given Order[DeltaCoin] = bigOrder
  given Show[DeltaCoin] = Show.show(d => s"DeltaCoin($d)")

/** `CompactForm Coin` (`Coin.hs:148`, a `Word64`): the coin as accounts and stake keep it. The
  * whole supply (4.5e16) is far below 2^63, but the type is the full word, as Haskell's.
  */
opaque type CompactCoin = Word64

object CompactCoin:
  def fromBigInt(n: BigInt): Either[VocabError, CompactCoin] =
    Word64.fromBigInt(VocabError.Kind.CompactCoin, n)

  def fromCoin(c: Coin): Either[VocabError, CompactCoin] = fromBigInt(Coin.value(c))

  def fromUnsigned(bits: Long): CompactCoin = Word64.fromUnsigned(bits)

  extension (c: CompactCoin)
    def word: Word64 = c
    def toBigInt: BigInt = Word64.toBigInt(c)

  given HasZero[CompactCoin] = HasZero.instance(Word64.isZero(_))
  given Order[CompactCoin] = Word64.order
  given Show[CompactCoin] = Show.show(c => s"CompactCoin(${Word64.decimal(c)})")

// ---- chain positions (`Cardano.Slotting`: `Word64` newtypes)

opaque type EpochNo = Word64

object EpochNo:
  def fromBigInt(n: BigInt): Either[VocabError, EpochNo] =
    Word64.fromBigInt(VocabError.Kind.EpochNo, n)

  def fromUnsigned(bits: Long): EpochNo = Word64.fromUnsigned(bits)

  extension (e: EpochNo)
    def word: Word64 = e
    def toBigInt: BigInt = Word64.toBigInt(e)

    /** The following epoch; `None` at 2^64 - 1 (`Enum EpochNo` would wrap). */
    def next: Option[EpochNo] = Word64.successor(e)

  given Order[EpochNo] = Word64.order
  given Show[EpochNo] = Show.show(e => s"EpochNo(${Word64.decimal(e)})")

opaque type SlotNo = Word64

object SlotNo:
  def fromBigInt(n: BigInt): Either[VocabError, SlotNo] =
    Word64.fromBigInt(VocabError.Kind.SlotNo, n)

  def fromUnsigned(bits: Long): SlotNo = Word64.fromUnsigned(bits)

  extension (s: SlotNo)
    def word: Word64 = s
    def toBigInt: BigInt = Word64.toBigInt(s)

  given Order[SlotNo] = Word64.order
  given Show[SlotNo] = Show.show(s => s"SlotNo(${Word64.decimal(s)})")

opaque type BlockNo = Word64

object BlockNo:
  def fromBigInt(n: BigInt): Either[VocabError, BlockNo] =
    Word64.fromBigInt(VocabError.Kind.BlockNo, n)

  def fromUnsigned(bits: Long): BlockNo = Word64.fromUnsigned(bits)

  extension (b: BlockNo)
    def word: Word64 = b
    def toBigInt: BigInt = Word64.toBigInt(b)

  given Order[BlockNo] = Word64.order
  given Show[BlockNo] = Show.show(b => s"BlockNo(${Word64.decimal(b)})")

/** `HasZero a` (`BaseTypes/NonZero.hs:80-91`): the types that have a zero to refuse. */
trait HasZero[A]:
  def isZero(a: A): Boolean

object HasZero:
  def instance[A](f: A => Boolean): HasZero[A] = f(_)

  given HasZero[Int] = instance(_ == 0)
  given HasZero[Long] = instance(_ == 0L)
  given HasZero[BigInt] = instance(_.signum == 0)
  given HasZero[Word64] = instance(Word64.isZero(_))

/** `NonZero a` (`BaseTypes/NonZero.hs:76-78`: `newtype NonZero a`,
  * `deriving newtype (Eq, Ord, ..)`, so it orders as the value does): a value that is not zero, by
  * construction.
  */
opaque type NonZero[A] = A

object NonZero:
  def from[A](a: A)(using z: HasZero[A]): Either[VocabError, NonZero[A]] =
    Either.cond(!z.isZero(a), a, VocabError.Zero(VocabError.Kind.NonZero))

  extension [A](z: NonZero[A]) def value: A = z

  /** The word 1, the count of a VRF key hash registered once (`knownNonZeroBounded @1`). */
  val oneWord: NonZero[Word64] = Word64.fromUnsigned(1L)

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

  // one order for every role, so that the maps keyed by a credential share one ordering
  private val scriptOrder: Order[ScriptHash] = Order[ScriptHash]
  private val keyOrder: Order[KeyHash[KeyRole]] = Order[KeyHash[KeyRole]]
  private val order: Order[Credential[KeyRole]] = new SortOrder[Credential[KeyRole]]:
    def compare(x: Credential[KeyRole], y: Credential[KeyRole]): Int = x match
      case ScriptHashObj(a) =>
        y match
          case ScriptHashObj(b) => scriptOrder.compare(a, b)
          case _                => -1
      case KeyHashObj(a) =>
        y match
          case KeyHashObj(b) => keyOrder.compare(a, b)
          case _             => 1

  given [R <: KeyRole]: Order[Credential[R]] = order.asInstanceOf[Order[Credential[R]]]

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

/** `WithOrigin a` (cardano-slotting `Slot.hs:71-82`, `data WithOrigin t = Origin | At !t deriving
  * (Eq, Ord, ..)`): the genesis point, or a value. Derived `Ord` orders by constructor position, so
  * `Origin` is the least; the sources are pinned in
  * `2026-10-15-upstream-pinned/cardano-base-slotting-0.2.2.0`.
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

  given [A](using d: Diff[A]): Diff[WithOrigin[A]] = new Diff[WithOrigin[A]]:
    def diff(a: WithOrigin[A], b: WithOrigin[A]): Option[Difference] = (a, b) match
      case (Origin, Origin) => None
      case (At(x), At(y))   => d.diff(x, y).map(_.under(Step.Field("value")))
      case _                => Some(Difference(Path.root, show(a), show(b)))
    def show(a: WithOrigin[A]): String = a match
      case Origin => "Origin"
      case At(x)  => s"At(${d.show(x)})"

  given [A: Show]: Show[WithOrigin[A]] = Show.show {
    case Origin => "Origin"
    case At(a)  => s"At(${Show[A].show(a)})"
  }
