// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import cats.syntax.order.*
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.DecodeSite.Qualifier
import xyz.kd5ujc.scarab.ledger.state.Word64
import xyz.kd5ujc.scarab.protocol.Era

/** A 28-byte key or script hash identifying who may spend or who owns stake. */
enum Credential:
  case Key(keyHash: Bytes)
  case Script(scriptHash: Bytes)

  def hash: Bytes = this match
    case Key(h)    => h
    case Script(h) => h

  /** The ledger's JSON rendering, e.g. `keyHash-ab12…`. */
  def render: String = this match
    case Key(h)    => s"keyHash-${h.hex}"
    case Script(h) => s"scriptHash-${h.hex}"

object Credential:
  /** The kind first (key before script), then the hash: the order of the ledger's `Credential`. */
  given Ordering[Credential] = (a, b) =>
    val kinds = Integer.compare(a.ordinal, b.ordinal)
    if kinds != 0 then kinds else a.hash.compareTo(b.hash)

  /** Conway credential encoding `[0, keyhash] / [1, scripthash]`. */
  def decode(n: Node, what: DecodeSite)(using ByteStrings): Either[LedgerDecodeError, Credential] =
    CborRead.arrayN(n, 2, what).flatMap { xs =>
      (CborRead.uint(xs(0), what).map(_.toInt), CborRead.bytesN(xs(1), 28, what)).tupled.flatMap {
        case (0, h) => Right(Key(h))
        case (1, h) => Right(Script(h))
        case (t, _) => Left(LedgerDecodeError.UnknownTag(what, t))
      }
    }

/** Certificate pointer of a pointer address: slot, transaction index, certificate index. The
  * reference holds them as a `Word32` and two `Word16` s (`Ptr`, `Credential.hs` 258); the fields
  * are wider here so the persisted codec stays as it was, and every pointer read from an address is
  * made to fit by [[Ptr.read]].
  */
final case class Ptr(slot: BigInt, txIx: BigInt, certIx: BigInt):
  /** Whether the fields fit a `Word32` and two `Word16`s. */
  def fits: Boolean =
    slot.signum >= 0 && txIx.signum >= 0 && certIx.signum >= 0 &&
      slot.bitLength <= 32 && txIx.bitLength <= 16 && certIx.bitLength <= 16

  /** `mkPtrNormalized` (`Credential.hs` 279-285): this pointer, or [[Ptr.zero]] if a field does not
    * fit.
    */
  def normalised: Ptr = if fits then this else Ptr.zero

object Ptr:
  given Ordering[Ptr] = Ordering.by(p => (p.slot, p.txIx, p.certIx))

  /** The pointer to no certificate (`Ptr 0 0 0`): what a pointer that does not fit is made into. */
  val zero: Ptr = Ptr(0, 0, 0)

  /** What a decoder does with a pointer whose natural does not fit its field. The decoder of a
    * protocol version below 9 normalises it (`mkPtrNormalized`, `Credential.hs` 270-285, chosen by
    * `decodePtrLenient`, `Address.hs` 780-788); from 9 (Conway) it refuses the address
    * (`decodePtr`, `Address.hs` 769-777, whose naturals are read by `decodeVariableLengthWord32`
    * and `decodeVariableLengthWord16`, 832-870: at most 5 and 3 groups of 7 bits, and no more bits
    * than the field has).
    */
  enum Policy:
    case Normalise, Refuse

  /** Why a pointer could not be read. */
  enum Failure(val message: String):
    case Truncated extends Failure("pointer address: truncated natural")
    case OutOfRange extends Failure("pointer address: pointer out of range")

  /** A natural of a pointer, as the reference reads one it does not bound
    * (`decodeVariableLengthWord64`, `Address.hs` 872-882): the 7-bit groups shifted into a
    * `Word64`, so the bits beyond the 64th are lost, and how many groups there were.
    */
  private final case class Natural(word: Word64, groups: Int)

  /** A field of the pointer: the widest value it holds and the most groups its strict decoder
    * reads.
    */
  private final case class Field(max: Word64, groups: Int):
    def holds(n: Natural, policy: Policy): Boolean =
      n.word <= max && (policy == Policy.Normalise || n.groups <= groups)

  private val slotField = Field(Word64.fromUnsigned(0xffffffffL), 5)
  private val indexField = Field(Word64.fromUnsigned(0xffffL), 3)

  /** The natural that starts at `from`, in time linear in its groups, and where it ends. */
  private def natural(bytes: Vector[Byte], from: Int): Either[Failure, (Natural, Int)] =
    @tailrec def go(at: Int, acc: Long): Either[Failure, (Natural, Int)] =
      if at >= bytes.size then Left(Failure.Truncated)
      else
        val b = bytes(at)
        val shifted = (acc << 7) | (b & 0x7f)
        if (b & 0x80) != 0 then go(at + 1, shifted)
        else Right((Natural(Word64.fromUnsigned(shifted), at + 1 - from), at + 1))
    go(from, 0L)

  /** The pointer at the start of `bytes` and the number of bytes it took; what follows is the
    * caller's (the reference reads the three naturals and stops). A pointer whose fields do not fit
    * is the [[zero]] pointer or a [[Failure.OutOfRange]], as the `policy` says.
    */
  def read(bytes: Vector[Byte], policy: Policy): Either[Failure, (Ptr, Int)] =
    for
      slot <- natural(bytes, 0)
      txIx <- natural(bytes, slot._2)
      certIx <- natural(bytes, txIx._2)
      end = certIx._2
      ptr <-
        if slotField.holds(slot._1, policy) && indexField.holds(txIx._1, policy) &&
          indexField.holds(certIx._1, policy)
        then Right(Ptr(slot._1.word.toBigInt, txIx._1.word.toBigInt, certIx._1.word.toBigInt))
        else if policy == Policy.Normalise then Right(zero)
        else Left(Failure.OutOfRange)
    yield (ptr, end)

  /** The pointer as the reference writes it (`putPtr`, `Address.hs` 347-351): each field as the
    * shortest run of 7-bit groups, high bit set on all but the last. Only for a pointer that fits.
    */
  def encode(ptr: Ptr): Bytes =
    def field(n: BigInt): Array[Byte] =
      val groups = math.max(1, (n.bitLength + 6) / 7)
      Array.tabulate(groups) { i =>
        val group = (n >> (7 * (groups - 1 - i))).toInt & 0x7f
        (if i < groups - 1 then group | 0x80 else group).toByte
      }
    Bytes.fromArray(field(ptr.slot) ++ field(ptr.txIx) ++ field(ptr.certIx))

/** A ledger address with its original bytes. Only the parts the ledger acts on are decoded. */
final case class Address(raw: Bytes, kind: Address.Kind)

object Address:
  enum Kind:
    /** Shelley payment address; `stake` is the delegating credential of a base address and
      * `pointer` the certificate pointer of a pointer address.
      */
    case Shelley(network: Int, payment: Credential, stake: Option[Credential], pointer: Option[Ptr])

    /** Byron bootstrap address; `root` is the 28-byte address root (empty if it did not parse). */
    case Byron(root: Bytes)

  /** How an era's decoder reads the address of a transaction output. The reference picks it by the
    * decoder version, which is the lowest protocol version of the era (`fromCborBothAddr`,
    * `libs/cardano-ledger-core/src/Cardano/Ledger/Address.hs` of cardano-ledger-core 1.21.0.0,
    * lines 459-477): below 7 (Shelley to Alonzo) "we did not check if a binary blob representing an
    * address was fully consumed, so unfortunately we must preserve this behavior", and the bytes
    * after the address are dropped (`fromCborBackwardsBothAddr`, 489-503); from 7 (Babbage) the
    * address must take every byte (`fromCborRigorousBothAddr`, 479-487). A pointer that does not
    * fit its fields is normalised to `Ptr 0 0 0` before version 9 and refused from it
    * (`Ptr.Policy`). Multi-asset values follow the same version: pruned below 9, strict from 9
    * (`Value.Decoding`); in Shelley and Allegra the value is a coin and nothing else. In every
    * version a pointer address is kept as the pointer is written again (`cAddrNormalized`, 470-476,
    * `putPtr`): the bytes that were read are not kept.
    */
  enum Mode(val pointers: Ptr.Policy, val values: Value.Decoding, val dropsTrailing: Boolean):
    /** Shelley and Allegra: bytes after a Shelley address are ignored and not kept, and an output
      * value is a coin: the era's `Value` is `Coin` (`type instance Value ShelleyEra = Coin`,
      * `eras/shelley/impl/src/Cardano/Ledger/Shelley/Era.hs`, and the same for `AllegraEra` in
      * `eras/allegra/impl/src/Cardano/Ledger/Allegra/Era.hs`, cardano-ledger f649f975).
      */
    case CoinOnly extends Mode(Ptr.Policy.Normalise, Value.Decoding.CoinOnly, true)

    /** Mary and Alonzo: bytes after a Shelley address are ignored and not kept. */
    case Backwards extends Mode(Ptr.Policy.Normalise, Value.Decoding.Pruning, true)

    /** Babbage: the address is the whole byte string; a pointer that does not fit is normalised. It
      * is also the mode for what the node itself wrote (the stores, the state codecs, the genesis):
      * those addresses are already normalised, and an output an earlier build stored with the
      * pointer as it came is read as the reference reads it.
      */
    case Rigorous extends Mode(Ptr.Policy.Normalise, Value.Decoding.Pruning, false)

    /** Conway: the address is the whole byte string and a pointer that does not fit is refused. */
    case Strict extends Mode(Ptr.Policy.Refuse, Value.Decoding.Rejecting, false)

  object Mode:
    def of(era: Era): Mode =
      if era.ordinal < Era.Mary.ordinal then CoinOnly
      else if era.ordinal < Era.Babbage.ordinal then Backwards
      else if era.ordinal < Era.Conway.ordinal then Rigorous
      else Strict

  /** The first byte of a bootstrap address, exactly: `headerByron = 0b10000010` and
    * `isByronAddress` is an equality test (`Cardano/Ledger/Address.hs`, cardano-ledger-core
    * 1.21.0.0). Any other header of kind 8 is read on the Shelley path, which has no such kind and
    * fails.
    */
  private val ByronHeader = 0x82

  /** The address in `raw`. A Byron-kind address (header byte 0x82) is decoded as the reference
    * decodes a bootstrap address, over the whole byte string in every era
    * (`decodeBootstrapAddress`, `Cardano/Ledger/Address.hs`, which runs `decodeFull'` of the Byron
    * `Address`, cardano-ledger-core 1.21.0.0): the CBOR envelope, the CRC-32 of the payload and the
    * payload itself must all read ([[byron.ByronAddress.decode]]), and the failure is the typed
    * cause of that decoder. A Shelley address of an era that drops trailing bytes
    * ([[Mode.CoinOnly]], [[Mode.Backwards]]) is cut where the reference cuts it: [[Address.raw]] is
    * the address without the bytes after it, as the reference keeps it in the UTxO
    * (`UnsafeCompactAddr sbsCropped`); the output that carried it keeps its own bytes. A pointer
    * address is kept as the normalised pointer, written again.
    */
  def decode(raw: Bytes, mode: Mode = Mode.Rigorous): Either[LedgerDecodeError, Address] =
    if raw.size == 0 then Left(LedgerDecodeError.Empty(DecodeField.Address))
    else
      val header = raw.value(0) & 0xff
      val kind = header >> 4
      val network = header & 0x0f
      def hash(offset: Int) =
        Either.cond(
          raw.size >= offset + 28,
          Bytes(raw.value.slice(offset, offset + 28)),
          LedgerDecodeError.AddressLength(DecodeField.PaymentCredential, offset + 28, raw.size)
        )
      def payment =
        hash(1).map(h => if (kind & 1) == 0 then Credential.Key(h) else Credential.Script(h))
      // The address of `length` bytes; the bytes after it are dropped if the era allows them.
      def own(length: Int, context: DecodeSite): Either[LedgerDecodeError, Bytes] =
        if raw.size == length then Right(raw)
        else if raw.size > length && mode.dropsTrailing then Right(raw.take(length))
        else Left(LedgerDecodeError.AddressLength(context, length, raw.size))
      kind match
        case 0 | 1 | 2 | 3 =>
          for
            p <- payment
            s <- hash(29)
            kept <- own(57, DecodeField.BaseAddressLength)
          yield Address(
            kept,
            Kind.Shelley(
              network,
              p,
              Some(if (kind & 2) == 0 then Credential.Key(s) else Credential.Script(s)),
              None
            )
          )
        case 4 | 5 =>
          for
            p <- payment
            read <- Ptr
              .read(raw.value.drop(29), mode.pointers)
              .left
              .map(LedgerDecodeError.Pointer(_))
            _ <- own(29 + read._2, DecodeField.PointerAddressTrailingBytes)
          yield
            // `putAddr`: the header from its fields, the payment credential, the pointer written again
            val head = Bytes.fromArray(Array((header & 0xf1).toByte))
            Address(
              head ++ raw.slice(1, 29) ++ Ptr.encode(read._1),
              Kind.Shelley(network, p, None, Some(read._1))
            )
        case 6 | 7 =>
          for
            kept <- own(29, DecodeField.EnterpriseAddressLength)
            p <- payment
          yield Address(kept, Kind.Shelley(network, p, None, None))
        case 8 if header == ByronHeader =>
          byron.ByronAddress.decode(raw).map(b => Address(raw, Kind.Byron(b.root)))
        case _ => Left(LedgerDecodeError.AddressHeader(header))

  /** Byron bootstrap address root for a genesis non-AVVM balance is the address itself. */
  def byronGenesisTxId(raw: Bytes): Bytes = Blake2b.hash256.hash(raw)

/** Reward (stake) address: header 0xe0/0xf0 | network, then a 28-byte credential hash. */
final case class RewardAccount(network: Int, credential: Credential)

object RewardAccount:
  def decode(raw: Bytes): Either[LedgerDecodeError, RewardAccount] =
    if raw.size != 29 then
      Left(LedgerDecodeError.AddressLength(DecodeField.RewardAccount, 29, raw.size))
    else
      val header = raw.value(0) & 0xff
      val h = Bytes(raw.value.drop(1))
      header >> 4 match
        case 14 => Right(RewardAccount(header & 0x0f, Credential.Key(h)))
        case 15 => Right(RewardAccount(header & 0x0f, Credential.Script(h)))
        case _  => Left(LedgerDecodeError.RewardAccountHeader(header))

/** Lovelace plus native assets. Mint values may be negative; UTxO values never are. */
final case class Value(coin: BigInt, assets: Map[Bytes, Map[Bytes, BigInt]]):
  def +(o: Value): Value = Value(coin + o.coin, Value.merge(assets, o.assets, 1))
  def -(o: Value): Value = Value(coin - o.coin, Value.merge(assets, o.assets, -1))
  def isZero: Boolean = coin == 0 && assets.isEmpty

  /** Bytes of the value as the ledger serialises it (`serialize v` in `validateOutputTooBigUTxO`):
    * a bare unsigned integer for an ada-only value, otherwise `[coin, {policy: {name: quantity}}]`
    * whatever encoding the transaction arrived in. A map of more than 23 entries is written
    * indefinite-length (`encodeMap`, `variableMapLenEncoding`, `lengthThreshold = 23` in
    * cardano-ledger-binary `Encoding/Encoder.hs`, every protocol major from 2): two bytes in total,
    * one byte fewer than the definite head of a map of 256 entries or more.
    */
  def serializedSize: Int =
    import Value.{headSize, mapHead, uintSize}
    if assets.isEmpty then uintSize(coin)
    else
      val inner = assets.valuesIterator.map { names =>
        val entries = names.iterator.map { case (name, q) =>
          headSize(name.size) + name.size + uintSize(q.abs)
        }.sum
        2 + 28 + mapHead(names.size) + entries
      }.sum
      1 + uintSize(coin) + mapHead(assets.size) + inner

object Value:
  /** Size of a CBOR head carrying `n` (a length or an unsigned integer up to 2^32). */
  private[ledger] def headSize(n: BigInt): Int =
    if n < 24 then 1
    else if n < 256 then 2
    else if n < 65536 then 3
    else if n < TwoToThe32 then 5
    else 9

  private val TwoToThe32 = BigInt(1) << 32

  private def uintSize(n: BigInt): Int = headSize(n)

  /** Bytes of a map's length prefix (and break): exact head up to 23 entries, else `0xbf ... 0xff`.
    */
  private[ledger] def mapHead(n: Int): Int = if n <= 23 then headSize(n) else 2

  val zero: Value = Value(0, Map.empty)
  def coin(c: BigInt): Value = Value(c, Map.empty)

  /** `a + sign * b` per (policy, name), with the zero quantities and the policies left empty
    * dropped (so a map with zero quantities in it comes out without them, as it always did).
    *
    * Sums are taken over a transaction's inputs one after the other, so the left side is the one
    * that grows. The entries of `b` are folded into `a`: a persistent map shares what they do not
    * touch, and an ada-only value costs nothing (issue 133: this was a third of the time the rules
    * spent on mainnet, where the inputs carry many tokens).
    */
  private def merge(
      a: Map[Bytes, Map[Bytes, BigInt]],
      b: Map[Bytes, Map[Bytes, BigInt]],
      sign: Int
  ): Map[Bytes, Map[Bytes, BigInt]] =
    if b.isEmpty then normal(a)
    else
      b.foldLeft(normal(a)) { case (acc, (policy, names)) =>
        val merged = names.foldLeft(acc.getOrElse(policy, Map.empty[Bytes, BigInt])) {
          case (held, (name, quantity)) =>
            val sum = held.getOrElse(name, BigInt(0)) + (if sign > 0 then quantity else -quantity)
            if sum.signum == 0 then held - name else held.updated(name, sum)
        }
        if merged.isEmpty then acc - policy else acc.updated(policy, merged)
      }

  /** `assets` without zero quantities and empty policies: itself, when it has none. */
  private def normal(assets: Map[Bytes, Map[Bytes, BigInt]]): Map[Bytes, Map[Bytes, BigInt]] =
    if assets.forall((_, names) => names.nonEmpty && names.forall(_._2.signum != 0)) then assets
    else
      assets.iterator
        .map((policy, names) => policy -> names.filter(_._2.signum != 0))
        .filter(_._2.nonEmpty)
        .toMap

  /** How a multi-asset map is read, by the decoder version of the era (`decodeMultiAsset`,
    * cardano-ledger `eras/mary/impl/src/Cardano/Ledger/Mary/Value.hs`).
    */
  enum Decoding:
    /** Shelley and Allegra: the era's value is a `Coin` and nothing else (`ShelleyTxOut`,
      * `decCBOR`, `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxOut.hs` lines 156-160, over
      * `type instance Value ShelleyEra = Coin` and `Value AllegraEra = Coin`, cardano-ledger
      * f649f975), so a value that carries a multi-asset part does not decode.
      */
    case CoinOnly

    /** Decoder version below 9 (Mary to Babbage): duplicate keys are silently merged, the last
      * entry wins (`decodeMap` below version 9, `Binary/Decoding/Decoder.hs`), then zero quantities
      * and policies left empty are pruned (`decodeWithPrunning`, `pruneZeroMultiAsset`).
      */
    case Pruning

    /** Decoder version 9 and above (Conway): a duplicate policy or asset name, a zero quantity and
      * an empty asset map are decode failures (`decodeConway`, `decodeNonEmptyMap`,
      * `decodeNonZeroAmount`).
      */
    case Rejecting

  private val Int64Min = -(BigInt(1) << 63)
  private val Int64Max = (BigInt(1) << 63) - 1

  /** The most the compact form holds: `44 * assets + 28 * policies <= 65535`
    * (`isMultiAssetSmallEnough`, checked after the maps are read in every decoder version).
    */
  private val CompactLimit = 65535

  private def int64(what: DecodeSite, q: BigInt) =
    Either.cond(
      q >= Int64Min && q <= Int64Max,
      q,
      LedgerDecodeError.NumberOutOfRange(what, q, Int64Min, Int64Max)
    )

  /** `coin / [coin, multiasset<uint>]`. Coin and quantities are `Word64` (`decodeWord64`): an
    * unsigned integer item holds no more, and a bignum is not one.
    */
  def decode(
      n: Node,
      what: DecodeSite,
      decoding: Decoding
  )(using ByteStrings): Either[LedgerDecodeError, Value] = n.value match
    case xyz.kd5ujc.scarab.codec.Value.UInt(c) => Right(coin(c))
    case _ if decoding == Decoding.CoinOnly    => Left(LedgerDecodeError.MultiAssetNotAllowed(what))
    case _ =>
      for
        xs <- CborRead.arrayN(n, 2, what)
        c <- CborRead.uint(xs(0), what)
        assets <- multiasset(xs(1), what, signed = false, decoding)
      yield Value(c, assets)

  /** A multi-asset map: output quantities are `Word64` (`decodeWord64`; the unsigned integer item
    * already holds no more), mint quantities `Int64` (`decodeIntegerBounded64`), under the rules of
    * `decoding`.
    */
  def multiasset(
      n: Node,
      what: DecodeSite,
      signed: Boolean,
      decoding: Decoding
  )(using ByteStrings): Either[LedgerDecodeError, Map[Bytes, Map[Bytes, BigInt]]] =
    for
      policies <- CborRead
        .map(n, what)
        .flatMap(_.traverse { case (p, inner) =>
          for
            policy <- CborRead.bytesN(p, 28, what / Qualifier.Policy)
            names <- CborRead.map(inner, what)
            quantities <- names.traverse { case (name, q) =>
              for
                assetName <- CborRead.bytes(name, what / Qualifier.AssetName)
                _ <- Either.cond(
                  assetName.size <= 32,
                  (),
                  LedgerDecodeError.AssetNameLength(what, assetName.size)
                )
                quantity <-
                  if signed then CborRead.int(q, what).flatMap(int64(what, _))
                  else CborRead.uint(q, what)
              yield assetName -> quantity
            }
          yield policy -> quantities
        })
      assets <- decoding match
        case Decoding.Pruning =>
          Right(normal(policies.map((p, qs) => p -> qs.toMap).toMap))
        case Decoding.Rejecting => rejecting(policies, what)
        case Decoding.CoinOnly  => Left(LedgerDecodeError.MultiAssetNotAllowed(what))
      count = assets.valuesIterator.map(_.size).sum
      _ <- Either.cond(
        44 * count + 28 * assets.size <= CompactLimit,
        (),
        LedgerDecodeError.MultiAssetTooBig(what, count, assets.size)
      )
    yield assets

  private def rejecting(
      policies: Vector[(Bytes, Vector[(Bytes, BigInt)])],
      what: DecodeSite
  ): Either[LedgerDecodeError, Map[Bytes, Map[Bytes, BigInt]]] =
    for
      _ <- Either.cond(
        policies.map(_._1).distinct.size == policies.size,
        (),
        LedgerDecodeError.DuplicateKey(what / Qualifier.Policy)
      )
      checked <- policies.traverse { case (policy, qs) =>
        for
          _ <- Either.cond(qs.nonEmpty, (), LedgerDecodeError.Empty(what / Qualifier.Assets))
          _ <- Either.cond(
            qs.map(_._1).distinct.size == qs.size,
            (),
            LedgerDecodeError.DuplicateKey(what / Qualifier.AssetName)
          )
          _ <- Either.cond(qs.forall(_._2.signum != 0), (), LedgerDecodeError.ZeroQuantity(what))
        yield policy -> qs.toMap
      }
    yield checked.toMap

/** The datum of an output. */
enum Datum:
  case NoDatum
  case Hash(hash: Bytes)

  /** Inline datum: the bytes of the plutus data (the content of the `#6.24` wrapper). */
  case Inline(data: Bytes)

/** A transaction output with its original encoding.
  *
  * @param valueSize
  *   Size in bytes of the value as it arrived (informational: the size rules use
  *   [[Value.serializedSize]], the canonical re-serialisation).
  */
final case class TxOut(
    raw: Bytes,
    address: Address,
    value: Value,
    datum: Datum,
    scriptRef: Option[Script],
    valueSize: Int
):
  def hasDatum: Boolean = datum != Datum.NoDatum
  def hasScriptRef: Boolean = scriptRef.isDefined

  // The reads below are of `raw`, which this output was already decoded from under the policy of
  // its decoder: they take any form and any key that decode took.
  private given ByteStrings = ByteStrings.Chunked

  /** The reference script with its Plutus bytes, re-read from the output's encoding. */
  def referenceScript: Option[Script] =
    scriptRef.flatMap { kept =>
      val reread = for
        node <- CborRead.decode(raw)
        f <- CborRead.fields(node, DecodeField.Output, FieldKeys.Stored)
        field <- f
          .get(3)
          .toRight(LedgerDecodeError.MissingField(DecodeField.Output, DecodeField.ReferenceScript))
        script <- Script.decodeWrappedWithBytes(field)
      yield script
      reread.toOption.orElse(Some(kept))
    }

  /** Size in bytes of the reference script as the ledger counts it for the reference-script fee:
    * the Plutus script bytes, or the encoding of a native script.
    */
  def referenceScriptSize: Int =
    if scriptRef.isEmpty then 0
    else
      val size = for
        node <- CborRead.decode(raw)
        f <- CborRead.fields(node, DecodeField.Output, FieldKeys.Stored)
        field <- f
          .get(3)
          .toRight(LedgerDecodeError.MissingField(DecodeField.Output, DecodeField.ReferenceScript))
        inner <- field.value match
          case xyz.kd5ujc.scarab.codec.Value.Tag(t, i) if t == 24 =>
            CborRead.bytes(i, DecodeField.ReferenceScript)
          case _ =>
            Left(LedgerDecodeError.ExpectedTag(DecodeField.ReferenceScript, 24, field.value))
        script <- CborRead.decode(inner)
        parts <- CborRead.arrayN(script, 2, DecodeField.Script)
        tag <- CborRead.uint(parts(0), DecodeField.ScriptType)
        payload <-
          if tag == 0 then Right(parts(1).original.size)
          else CborRead.bytes(parts(1), DecodeField.PlutusScript).map(_.size)
      yield payload
      size.getOrElse(0)

  /** Size of the output for the Babbage and Conway minimum: the length of its original encoding.
    * The reference measures the output as it was decoded, `Sized` by the span the decoder consumed
    * (`decodeSized`, `Decoding/Sized.hs`, cardano-ledger f649f975), and the body keeps that size
    * (`btbrOutputs :: StrictSeq (Sized (TxOut era))`, `Babbage/TxBody.hs`;
    * `validateOutputTooSmallUTxO`, `Babbage/Rules/Utxo.hs`). The array and the map form, the
    * address as it came (a pointer included) and any non-minimal encoding therefore count as they
    * arrived.
    */
  def ledgerSize: Int = raw.size

object TxOut:
  def apply(address: Address, value: Value): TxOut =
    TxOut(Bytes.empty, address, value, Datum.NoDatum, None, 0)

  /** The forms of an output that an era's decoder reads. `ShelleyTxOut` (Shelley, Allegra and Mary)
    * is the two-element array alone (`decodeRecordNamed "ShelleyTxOut" (const 2)`,
    * `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxOut.hs`); `AlonzoTxOut` adds the three-element
    * array with a datum hash (`DecCBOR (AlonzoTxOut era)`,
    * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxOut.hs`); `BabbageTxOut` (Babbage and Conway)
    * adds the map (`decodeBabbageTxOut`, `eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs`),
    * all of cardano-ledger f649f975. An indefinite array is read as a definite one in every era.
    */
  enum Forms(val arities: Set[Int], val admitsMap: Boolean, val keys: FieldKeys):
    case Pair extends Forms(Set(2), false, FieldKeys.FullWidth)
    case PairOrDatumHash extends Forms(Set(2, 3), false, FieldKeys.FullWidth)
    case PairDatumHashOrMap extends Forms(Set(2, 3), true, FieldKeys.FullWidth)

    /** A stored output is read in any form and with the map keys an earlier build accepted and
      * stored, so the stores keep reading what they wrote (ADR 0006): every form of the latest era,
      * the keys as their low 32 bits ([[FieldKeys.Stored]]).
      */
    case Stored extends Forms(Set(2, 3), true, FieldKeys.Stored)

  object Forms:
    def of(era: Era): Forms =
      if era.ordinal < Era.Alonzo.ordinal then Pair
      else if era.ordinal < Era.Babbage.ordinal then PairOrDatumHash
      else PairDatumHashOrMap

  def decode(
      n: Node,
      mode: Address.Mode,
      forms: Forms
  )(using ByteStrings): Either[LedgerDecodeError, TxOut] =
    n.value match
      case xyz.kd5ujc.scarab.codec.Value.Arr(xs) if forms.arities.contains(xs.size) =>
        for
          a <- CborRead.bytes(xs(0), DecodeField.OutputAddress).flatMap(Address.decode(_, mode))
          v <- Value.decode(xs(1), DecodeField.OutputValue, mode.values)
          d <-
            if xs.size == 3 then
              CborRead.bytesN(xs(2), 32, DecodeField.DatumHash).map(Datum.Hash(_))
            else Right(Datum.NoDatum)
        yield TxOut(n.original, a, v, d, None, xs(1).original.size)
      case xyz.kd5ujc.scarab.codec.Value.Map(_) if forms.admitsMap =>
        for
          f <- CborRead.fields(n, DecodeField.Output, forms.keys)
          a <- f
            .get(0)
            .toRight(LedgerDecodeError.MissingField(DecodeField.Output, DecodeField.Address))
            .flatMap(CborRead.bytes(_, DecodeField.OutputAddress))
            .flatMap(Address.decode(_, mode))
          vn <- f
            .get(1)
            .toRight(LedgerDecodeError.MissingField(DecodeField.Output, DecodeField.Value))
          v <- Value.decode(vn, DecodeField.OutputValue, mode.values)
          _ <- Either.cond(
            f.keySet.subsetOf(Set(0, 1, 2, 3)),
            (),
            LedgerDecodeError.UnknownFields(DecodeField.Output, f.keySet -- Set(0, 1, 2, 3))
          )
          d <- f.get(2).traverse(datumOption)
          s <- f.get(3).traverse(Script.decodeWrapped)
        yield TxOut(n.original, a, v, d.getOrElse(Datum.NoDatum), s, vn.original.size)
      case _ => Left(LedgerDecodeError.OutputForm(forms, n.value))

  /** `datum_option = [0, hash] / [1, #6.24(bytes .cbor plutus_data)]` */
  private def datumOption(n: Node)(using ByteStrings): Either[LedgerDecodeError, Datum] =
    CborRead.arrayN(n, 2, DecodeField.DatumOption).flatMap { xs =>
      CborRead.uint(xs(0), DecodeField.DatumOptionTag).flatMap {
        case t if t == 0 => CborRead.bytesN(xs(1), 32, DecodeField.DatumHash).map(Datum.Hash(_))
        case t if t == 1 =>
          xs(1).value match
            case xyz.kd5ujc.scarab.codec.Value.Tag(g, inner) if g == 24 =>
              CborRead.bytes(inner, DecodeField.InlineDatum).map(Datum.Inline(_))
            case _ => Left(LedgerDecodeError.ExpectedTag(DecodeField.InlineDatum, 24, xs(1).value))
        case t => Left(LedgerDecodeError.UnknownTag(DecodeField.DatumOption, t))
      }
    }

  /** An output read from its own bytes, as the stores keep it and as a rule re-reads it: in every
    * form of [[Forms.Stored]].
    */
  def fromBytes(
      raw: Bytes,
      mode: Address.Mode = Address.Mode.Rigorous
  )(using ByteStrings): Either[LedgerDecodeError, TxOut] =
    CborRead.decode(raw).flatMap(decode(_, mode, Forms.Stored))

/** `[transaction_id, index]` */
final case class TxIn(id: Bytes, index: BigInt):
  def render: String = s"${id.hex}#$index"

object TxIn:
  /** The transaction id (as bytes, as its hex sorts), then the index: the ledger's `TxIn` order.
    * Sorting a transaction's inputs is on the path of every script purpose (issue 133), so no
    * string is built per comparison.
    */
  given Ordering[TxIn] = (a, b) =>
    val ids = a.id.compareTo(b.id)
    if ids != 0 then ids else a.index.compare(b.index)
  def decode(n: Node)(using ByteStrings): Either[LedgerDecodeError, TxIn] =
    CborRead
      .arrayN(n, 2, DecodeField.Input)
      .flatMap(xs =>
        (
          CborRead.bytesN(xs(0), 32, DecodeField.InputId),
          CborRead.uint(xs(1), DecodeField.InputIndex)
        )
          .mapN(TxIn.apply)
      )
