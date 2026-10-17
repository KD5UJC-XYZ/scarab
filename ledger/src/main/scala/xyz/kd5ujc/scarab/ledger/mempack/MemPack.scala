// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.mempack

import cats.Show
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era

/** The binary format of the Haskell `mempack` package (0.2.1.0, `src/Data/MemPack.hs`) as far as
  * the ledger's `TxOut` uses it: a tag is one byte (`packTagM`, 1547); `Word16` and `Word64` are
  * fixed width and little endian (581); `VarLen` is base-128, the most significant group first, the
  * high bit of a byte meaning that more follows (`packIntoCont7`, 1432; `unpack7BitVarLen`, 1448);
  * a byte string is a `Length` (a `VarLen Word`, 1505) and the bytes (989); a hash is its bytes
  * with no length (`PackedBytes`, `cardano-crypto-class`). See `docs/design/haskell-snapshots.md`,
  * section 4.
  */
object MemPack:

  /** What `MemPack` byte strings are refused for, and what cannot be packed. Closed, so a caller
    * can match on the cause; `show` renders it.
    */
  enum Failure:
    /** The input ended: `wanted` bytes were needed, `remaining` were left. */
    case Truncated(wanted: Int, remaining: Int)

    /** The value was read and `count` bytes follow it (`unpackError` of the library). */
    case TrailingBytes(count: Int)

    /** A `VarLen` of more than 64 bits (ten groups, or set bits above the 64th in the first). */
    case VarLenTooLong

    /** A length that does not fit an `Int` (this reader holds a value in one array). */
    case LengthOutOfRange(length: Long)

    /** A constructor tag this era does not have (`unknownTagM`). */
    case UnknownTag(site: TagSite, tag: Int)

    /** A well-formed constructor of a later era. */
    case NotInEra(feature: Feature, era: Era)

    /** The packed multi-asset value is not one the ledger's `from` can read. */
    case BadMultiAsset(fault: MultiAssetFault)

    /** A quantity or a coin outside `[0, 2^64 - 1]`, which has no compact form (`integerToWord64`).
      */
    case OutOfRange(field: Quantity)

    /** More assets or policies than the 16-bit offsets of the compact form can address
      * (`isMultiAssetSmallEnough`, `Value.hs:710`).
      */
    case MultiAssetTooLarge(assets: Int, policies: Int)

    /** The address bytes are not an address the node reads (its own message). */
    case AddressRefused(reason: String)

    /** The reference script bytes are not a script (its own message). */
    case ScriptRefused(reason: String)

    /** The output has a reference script but no bytes to pack it from: an output that was not read
      * from an encoding (built in a test or from genesis), whose script the node did not keep.
      */
    case ScriptBytesMissing

  /** Where an unknown tag was met. */
  enum TagSite:
    case TxOutConstructor, Datum, Script, PlutusLanguage, Credential, CompactValue, CompactCoin

  /** Constructs of the grammar that only some eras have. */
  enum Feature:
    case MultiAssetValue, DatumHash, Addr28Constructor, InlineDatum, ReferenceScript, PlutusV2,
      PlutusV3

  /** Faults of the packed multi-asset representation (`Value.hs:475-578`). */
  enum MultiAssetFault:
    /** The representation is shorter than the three fixed regions of its asset count. */
    case ShorterThanRegions

    /** A policy id offset whose 28 bytes are not inside the representation. */
    case PolicyOutOfRange

    /** An asset name offset beyond the end of the representation. */
    case NameOutOfRange

    /** Name offsets must not decrease from one asset to the next (the writer orders them). */
    case NameOffsetsDescend

  enum Quantity:
    case Coin, Asset

  given Show[Failure] = Show.show {
    case Failure.Truncated(w, r)     => s"MemPack: $w bytes wanted, $r left"
    case Failure.TrailingBytes(n)    => s"MemPack: $n bytes after the value"
    case Failure.VarLenTooLong       => "MemPack: VarLen of more than 64 bits"
    case Failure.LengthOutOfRange(n) => s"MemPack: length $n does not fit"
    case Failure.UnknownTag(s, t)    => s"MemPack: unknown tag $t of $s"
    case Failure.NotInEra(f, e)      => s"MemPack: $f is not in $e"
    case Failure.BadMultiAsset(f)    => s"MemPack: multi-asset representation: $f"
    case Failure.OutOfRange(q)       => s"MemPack: $q outside [0, 2^64 - 1]"
    case Failure.MultiAssetTooLarge(a, p) =>
      s"MemPack: $a assets of $p policies are too many to compact"
    case Failure.AddressRefused(r)  => s"MemPack: address: $r"
    case Failure.ScriptRefused(r)   => s"MemPack: script: $r"
    case Failure.ScriptBytesMissing => "MemPack: reference script without bytes"
  }

  type Result[A] = Either[Failure, A]

  /** Bytes of `VarLen n` for the unsigned 64-bit `n` (`packedVarLenByteCount`). */
  def varLenSize(n: Long): Int =
    val bits = 64 - java.lang.Long.numberOfLeadingZeros(n)
    if bits == 0 then 1 else (bits + 6) / 7

  /** Bytes of a `Length` prefix and `n` bytes. */
  def sizedSize(n: Int): Int = varLenSize(n.toLong) + n

  /** A fixed-size output buffer: the sizes are computed first (as `packedByteCount` does), then
    * every write lands in place.
    */
  final class Packer(size: Int):
    private val array = new Array[Byte](size)
    private var pos = 0

    def tag(t: Int): Unit =
      array(pos) = t.toByte
      pos += 1

    def word16le(v: Int): Unit =
      array(pos) = v.toByte
      array(pos + 1) = (v >>> 8).toByte
      pos += 2

    def word64le(v: Long): Unit =
      var i = 0
      while i < 8 do
        array(pos + i) = (v >>> (8 * i)).toByte
        i += 1
      pos += 8

    /** `VarLen` of an unsigned 64-bit value: groups of seven bits, the most significant first. */
    def varLen(v: Long): Unit =
      val groups = varLenSize(v)
      var g = groups - 1
      while g >= 0 do
        val group = ((v >>> (7 * g)) & 0x7f).toInt
        array(pos) = (if g == 0 then group else group | 0x80).toByte
        pos += 1
        g -= 1

    /** The bytes as they are (a hash, or a part of a larger structure). */
    def raw(b: Bytes): Unit =
      b.copyTo(array, pos)
      pos += b.size

    /** A byte string: its length as a `VarLen`, then the bytes. */
    def sized(b: Bytes): Unit =
      varLen(b.size.toLong)
      raw(b)

    /** The bytes written; the buffer is full when the sizes were right. */
    def result: Bytes = Bytes.wrap(array)

  /** A cursor over the bytes of one packed value. Reads fail with a typed [[Failure]]; nothing
    * throws.
    */
  final class Unpacker(data: Array[Byte], from: Int, until: Int):
    private var pos = from

    def remaining: Int = until - pos

    private def need(n: Int): Result[Unit] =
      if remaining >= n then Right(()) else Left(Failure.Truncated(n, remaining))

    def tag: Result[Int] =
      if pos < until then
        val b = data(pos) & 0xff
        pos += 1
        Right(b)
      else Left(Failure.Truncated(1, 0))

    def word64le: Result[Long] =
      need(8).map { _ =>
        var v = 0L
        var i = 7
        while i >= 0 do
          v = (v << 8) | (data(pos + i) & 0xffL)
          i -= 1
        pos += 8
        v
      }

    /** An unsigned 64-bit `VarLen`; more than ten groups, or bits beyond the 64th, are refused. */
    def varLen: Result[Long] =
      var acc = 0L
      var groups = 0
      var done = false
      var failure: Option[Failure] = None
      while !done && failure.isEmpty do
        if pos >= until then failure = Some(Failure.Truncated(1, 0))
        else
          val b = data(pos) & 0xff
          pos += 1
          groups += 1
          // After nine groups the value has at most 63 bits; a tenth may carry only the 64th
          // (the library's `unpack7BitVarLenLast`), and an eleventh does not exist.
          if groups > 10 || (groups == 10 && (acc >>> 57) != 0L) then
            failure = Some(Failure.VarLenTooLong)
          else
            acc = (acc << 7) | (b & 0x7f).toLong
            if (b & 0x80) == 0 then done = true
      failure.toLeft(acc)

    /** A `Length`, as an `Int` the buffer can hold. */
    def length: Result[Int] =
      varLen.flatMap { n =>
        if n < 0L || n > Int.MaxValue then Left(Failure.LengthOutOfRange(n)) else Right(n.toInt)
      }

    /** `n` bytes as they are, sharing the buffer. */
    def raw(n: Int): Result[Bytes] =
      need(n).map { _ =>
        val b = Bytes.wrap(data, pos, n)
        pos += n
        b
      }

    /** A byte string: its `Length`, then the bytes. */
    def sized: Result[Bytes] = length.flatMap(raw)

    /** The value is complete: nothing may follow (`unpackFail`). */
    def finished: Result[Unit] =
      if remaining == 0 then Right(()) else Left(Failure.TrailingBytes(remaining))

  object Unpacker:
    def of(bytes: Bytes): Unpacker =
      bytes.withRange((array, offset, size) => new Unpacker(array, offset, offset + size))
