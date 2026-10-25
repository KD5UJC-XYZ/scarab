// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.mempack

import xyz.kd5ujc.scarab.ledger.{ByteStrings, DecodeField}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  CborRead,
  Credential,
  Datum,
  Script,
  ScriptKind,
  TxOut,
  Value
}
import xyz.kd5ujc.scarab.ledger.state.{EraTag, ShelleyBasedEra}
import xyz.kd5ujc.scarab.protocol.Era

import MemPack.{Failure, Feature, Packer, Quantity, Result, TagSite, Unpacker}

/** What a MemPack `TxOut` stores of an output: the ledger's address bytes, the value, the datum and
  * the reference script, with no trace of how the output arrived. The node's [[TxOut]] adds the
  * original encoding, which a MemPack output does not have (the reference's `TxOut` does not
  * either: its sizes come from encoding it again).
  */
final case class TxOutParts(
    address: Bytes,
    value: Value,
    datum: Datum,
    script: Option[RefScript]
)

/** A reference script as the MemPack `Script` holds it: the original CBOR of a native script, or
  * the bytes of a Plutus script (its language is the kind).
  */
final case class RefScript(kind: ScriptKind, bytes: Bytes)

/** The MemPack encoding of the ledger's `TxOut` for the Shelley-based eras, as `cardano-ledger`
  * writes it into the `tables` file of a LedgerDB snapshot and as `mempack` 0.2.1.0 packs it. The
  * grammar and its sources are in `docs/design/haskell-snapshots.md`, section 4; the era is a
  * witness, so a constructor that the era does not have is refused with a typed [[Failure]], not
  * guessed.
  *
  * The constructor of an output depends on how it was built (`mkTxOut`): a basic output (no
  * reference script, a datum hash or none) of ada only to a base address is the compact "Addr28"
  * form (tags 2 and 3), the others the general ones (0, 1, 4, 5). An output carried over from an
  * earlier era keeps the constructor it had, so [[decode]] takes every constructor of the era and
  * [[encode]] the canonical one: the decoded output is equal to the original, the encoded bytes are
  * equal to the original's when the original was canonical.
  */
object TxOutMemPack:
  // What this reads is stored: the encoding of an output already decoded, or a snapshot's
  // output. It keeps reading in any form that was stored.
  private given ByteStrings = ByteStrings.Chunked

  /** The output packed, in the format of era `E`. */
  def encode[E <: EraTag](out: TxOut)(using ShelleyBasedEra[E]): Result[Bytes] =
    parts(out).flatMap(encodeParts)

  /** The output stored in `bytes`, with a canonical CBOR encoding in [[TxOut.raw]]. */
  def decode[E <: EraTag](bytes: Bytes)(using ShelleyBasedEra[E]): Result[TxOut] =
    decodeParts(bytes).flatMap(toTxOut)

  def encodeParts[E <: EraTag](p: TxOutParts)(using era: ShelleyBasedEra[E]): Result[Bytes] =
    choose(p).map { rep =>
      val packer = new Packer(size(rep))
      pack(rep, packer)
      packer.result
    }

  def decodeParts[E <: EraTag](bytes: Bytes)(using ShelleyBasedEra[E]): Result[TxOutParts] =
    val in = Unpacker.of(bytes)
    unpackRep(in).flatMap(rep => in.finished.flatMap(_ => fromRep(rep)))

  // ---- the output and its parts

  /** The parts of an output. The reference script's bytes are read from the output's encoding; an
    * output that has a script but no encoding (built, not read) is refused.
    */
  def parts(out: TxOut): Result[TxOutParts] =
    out.scriptRef match
      case None => Right(TxOutParts(out.address.raw, out.value, out.datum, None))
      case Some(_) =>
        scriptOf(out.raw).map(s => TxOutParts(out.address.raw, out.value, out.datum, Some(s)))

  private def scriptOf(raw: Bytes): Result[RefScript] =
    if raw.size == 0 then Left(Failure.ScriptBytesMissing)
    else
      for
        node <- CborRead.decode(raw).leftMap(Failure.ScriptRefused(_))
        fields <- CborRead.fields(node, DecodeField.Output).leftMap(Failure.ScriptRefused(_))
        wrapped <- fields.get(3).toRight(Failure.ReferenceScriptMissing)
        inner <- wrapped.value match
          case CborValue.Tag(t, i) if t == 24 =>
            CborRead.bytes(i, DecodeField.ReferenceScript).leftMap(Failure.ScriptRefused(_))
          case _ => Left(Failure.ReferenceScriptWrapper)
        script <- CborRead.decode(inner).leftMap(Failure.ScriptRefused(_))
        two <- CborRead.arrayN(script, 2, DecodeField.Script).leftMap(Failure.ScriptRefused(_))
        tag <- CborRead.uint(two(0), DecodeField.ScriptType).leftMap(Failure.ScriptRefused(_))
        kind <- ScriptKind.values.find(_.tag == tag).toRight(Failure.UnknownScriptKind(tag))
        bytes <-
          if kind == ScriptKind.Native then Right(two(1).original)
          else CborRead.bytes(two(1), DecodeField.PlutusScript).leftMap(Failure.ScriptRefused(_))
      yield RefScript(kind, bytes)

  /** The node's output for `p`, with the canonical encoding (the legacy array without an inline
    * datum or a script, else the keyed map) as its original bytes.
    */
  def toTxOut(p: TxOutParts): Result[TxOut] =
    for
      address <- Address.decode(p.address).left.map(Failure.AddressRefused(_))
      scriptRef <- p.script.traverse(refScript)
    yield
      val valueCbor = CanonicalCbor.value(p.value)
      TxOut(
        CanonicalCbor.output(p, valueCbor),
        address,
        p.value,
        p.datum,
        scriptRef,
        valueCbor.size
      )

  private def refScript(s: RefScript): Result[Script] = s.kind match
    case ScriptKind.Native =>
      CborRead
        .decode(s.bytes)
        .leftMap(Failure.ScriptRefused(_))
        .flatMap(n => Script.native(n).leftMap(Failure.ScriptRefused(_)))
    case plutus => Right(Script.plutus(plutus, s.bytes).copy(plutus = None))

  // ---- the constructors

  private final case class Addr28Extra(a: Long, b: Long, c: Long, d: Long)

  private enum PackedValue:
    case AdaOnly(coin: Long)
    case Assets(coin: Long, count: Int, rep: Bytes)

  /** The constructors of `AlonzoTxOut` and `BabbageTxOut` (`Alonzo/TxOut.hs:144`,
    * `Babbage/TxOut.hs:133`), by tag; the Shelley and Mary `TxOutCompact` is tag 0.
    */
  private enum Rep:
    case Compact(address: Bytes, value: PackedValue)
    case CompactDH(address: Bytes, value: PackedValue, dataHash: Bytes)
    case Addr28(stake: Credential, extra: Addr28Extra, coin: Long)
    case Addr28DH(stake: Credential, extra: Addr28Extra, coin: Long, dataHash: Bytes)
    case CompactDatum(address: Bytes, value: PackedValue, inline: Bytes)
    case CompactRefScript(address: Bytes, value: PackedValue, datum: Datum, script: RefScript)

  // ---- what an era has

  private def eraOf[E <: EraTag](using era: ShelleyBasedEra[E]): Era = era match
    case ShelleyBasedEra.SShelley => Era.Shelley
    case ShelleyBasedEra.SAllegra => Era.Allegra
    case ShelleyBasedEra.SMary    => Era.Mary
    case ShelleyBasedEra.SAlonzo  => Era.Alonzo
    case ShelleyBasedEra.SBabbage => Era.Babbage
    case ShelleyBasedEra.SConway  => Era.Conway

  /** The highest constructor tag of the era's `TxOut`. */
  private def maxTag[E <: EraTag](using ShelleyBasedEra[E]): Int = eraOf[E] match
    case Era.Alonzo               => 3
    case Era.Babbage | Era.Conway => 5
    case _                        => 0

  /** Shelley and Allegra values are a `CompactForm Coin`; from Mary a `CompactValue`. */
  private def coinOnlyValues[E <: EraTag](using ShelleyBasedEra[E]): Boolean =
    eraOf[E] match
      case Era.Shelley | Era.Allegra => true
      case _                         => false

  /** The Plutus languages of the era's `PlutusScript` (`Babbage/Scripts.hs:134`,
    * `Conway/Scripts.hs:184`).
    */
  private def languages[E <: EraTag](using ShelleyBasedEra[E]): Int = eraOf[E] match
    case Era.Conway  => 3
    case Era.Babbage => 2
    case _           => 1

  private def requireTag[E <: EraTag](tag: Int, feature: Feature)(using
      ShelleyBasedEra[E]
  ): Result[Unit] =
    Either.cond(maxTag[E] >= tag, (), Failure.NotInEra(feature, eraOf[E]))

  // ---- choosing the constructor (`mkTxOut`)

  private val TwoTo64 = BigInt(1) << 64

  private def word64(v: BigInt, what: Quantity): Result[Long] =
    if v.signum < 0 || v.bitLength > 64 then Left(Failure.OutOfRange(what)) else Right(v.toLong)

  private def packedValue[E <: EraTag](v: Value)(using ShelleyBasedEra[E]): Result[PackedValue] =
    word64(v.coin, Quantity.Coin).flatMap { coin =>
      MultiAssetRep.build(v.assets).flatMap {
        case (0, _) => Right(PackedValue.AdaOnly(coin))
        case (n, rep) =>
          Either.cond(
            !coinOnlyValues[E],
            PackedValue.Assets(coin, n, rep),
            Failure.NotInEra(Feature.MultiAssetValue, eraOf[E])
          )
      }
    }

  /** Network, payment and stake credential of a base address of exactly the 57 bytes the compact
    * form rebuilds.
    */
  private def baseAddress(a: Bytes): Option[(Int, Credential, Credential)] =
    Address.decode(a).toOption.flatMap { address =>
      address.kind match
        case Address.Kind.Shelley(net, pay, Some(stake), None)
            if (net == 0 || net == 1) && address.raw.size == 57 =>
          Some((net, pay, stake))
        case _ => None
    }

  private def be64(b: Bytes, at: Int): Long =
    var v = 0L
    var i = 0
    while i < 8 do
      v = (v << 8) | (b(at + i) & 0xffL)
      i += 1
    v

  private def addr28Extra(net: Int, payment: Credential): Addr28Extra =
    val h = payment.hash
    val last = ((b4(h, 24)) & 0xffffffffL) << 32
    val key = payment match
      case Credential.Key(_)    => 1L
      case Credential.Script(_) => 0L
    Addr28Extra(be64(h, 0), be64(h, 8), be64(h, 16), last | (net.toLong << 1) | key)

  private def b4(b: Bytes, at: Int): Long =
    ((b(at) & 0xffL) << 24) | ((b(at + 1) & 0xffL) << 16) | ((b(at + 2) & 0xffL) << 8) |
      (b(at + 3) & 0xffL)

  private def choose[E <: EraTag](p: TxOutParts)(using ShelleyBasedEra[E]): Result[Rep] =
    for
      value <- packedValue[E](p.value)
      language <- p.script.traverse(checkLanguage[E])
      rep <- (p.datum, language) match
        case (datum, Some(script)) =>
          requireTag[E](5, Feature.ReferenceScript).as(
            Rep.CompactRefScript(p.address, value, datum, script)
          )
        case (Datum.NoDatum, None) =>
          Right(compactOrAddr28(p.address, value, None))
        case (Datum.Hash(h), None) =>
          requireTag[E](1, Feature.DatumHash).as(compactOrAddr28(p.address, value, Some(h)))
        case (Datum.Inline(d), None) =>
          requireTag[E](4, Feature.InlineDatum).as(Rep.CompactDatum(p.address, value, d))
    yield rep

  private def checkLanguage[E <: EraTag](s: RefScript)(using
      ShelleyBasedEra[E]
  ): Result[RefScript] =
    s.kind match
      case ScriptKind.PlutusV2 if languages[E] < 2 =>
        Left(Failure.NotInEra(Feature.PlutusV2, eraOf[E]))
      case ScriptKind.PlutusV3 if languages[E] < 3 =>
        Left(Failure.NotInEra(Feature.PlutusV3, eraOf[E]))
      case _ => Right(s)

  /** Tags 2 and 3 for an ada-only output to a base address, tags 0 and 1 otherwise. */
  private def compactOrAddr28[E <: EraTag](
      address: Bytes,
      value: PackedValue,
      dataHash: Option[Bytes]
  )(using ShelleyBasedEra[E]): Rep =
    val compact = dataHash match
      case None    => Rep.Compact(address, value)
      case Some(h) => Rep.CompactDH(address, value, h)
    (value, maxTag[E] >= 2) match
      case (PackedValue.AdaOnly(coin), true) =>
        baseAddress(address) match
          case Some((net, pay, stake)) =>
            val extra = addr28Extra(net, pay)
            dataHash match
              case None    => Rep.Addr28(stake, extra, coin)
              case Some(h) => Rep.Addr28DH(stake, extra, coin, h)
          case None => compact
      case _ => compact

  // ---- sizes and packing

  private def valueSize(v: PackedValue): Int = v match
    case PackedValue.AdaOnly(coin) => 1 + MemPack.varLenSize(coin)
    case PackedValue.Assets(coin, n, rep) =>
      1 + MemPack.varLenSize(coin) + MemPack.varLenSize(n.toLong) + MemPack.sizedSize(rep.size)

  private def datumSize(d: Datum): Int = d match
    case Datum.NoDatum   => 1
    case Datum.Hash(_)   => 1 + 32
    case Datum.Inline(b) => 1 + MemPack.sizedSize(b.size)

  private def scriptSize(s: RefScript): Int = s.kind match
    case ScriptKind.Native => 1 + MemPack.sizedSize(s.bytes.size)
    case _                 => 2 + MemPack.sizedSize(s.bytes.size)

  private def size(rep: Rep): Int = 1 + (rep match
    case Rep.Compact(a, v)        => MemPack.sizedSize(a.size) + valueSize(v)
    case Rep.CompactDH(a, v, _)   => MemPack.sizedSize(a.size) + valueSize(v) + 32
    case Rep.Addr28(_, _, coin)   => 29 + 32 + MemPack.varLenSize(coin) + 1
    case Rep.Addr28DH(_, _, c, _) => 29 + 32 + MemPack.varLenSize(c) + 1 + 32
    case Rep.CompactDatum(a, v, d) =>
      MemPack.sizedSize(a.size) + valueSize(v) + MemPack.sizedSize(d.size)
    case Rep.CompactRefScript(a, v, d, s) =>
      MemPack.sizedSize(a.size) + valueSize(v) + datumSize(d) + scriptSize(s))

  private def packValue(v: PackedValue, out: Packer): Unit =
    v match
      case PackedValue.AdaOnly(coin) =>
        out.tag(0)
        out.varLen(coin)
      case PackedValue.Assets(coin, n, rep) =>
        out.tag(1)
        out.varLen(coin)
        out.varLen(n.toLong)
        out.sized(rep)

  private def packCredential(c: Credential, out: Packer): Unit =
    c match
      case Credential.Script(h) =>
        out.tag(0)
        out.raw(h)
      case Credential.Key(h) =>
        out.tag(1)
        out.raw(h)

  private def packWords(h: Bytes, out: Packer): Unit =
    out.word64le(be64(h, 0))
    out.word64le(be64(h, 8))
    out.word64le(be64(h, 16))
    out.word64le(be64(h, 24))

  private def packDatum(d: Datum, out: Packer): Unit = d match
    case Datum.NoDatum => out.tag(0)
    case Datum.Hash(h) =>
      out.tag(1)
      out.raw(h)
    case Datum.Inline(b) =>
      out.tag(2)
      out.sized(b)

  private def packScript(s: RefScript, out: Packer): Unit = s.kind match
    case ScriptKind.Native =>
      out.tag(0)
      out.sized(s.bytes)
    case plutus =>
      out.tag(1)
      out.tag(plutus.tag - 1)
      out.sized(s.bytes)

  private def pack(rep: Rep, out: Packer): Unit = rep match
    case Rep.Compact(a, v) =>
      out.tag(0)
      out.sized(a)
      packValue(v, out)
    case Rep.CompactDH(a, v, h) =>
      out.tag(1)
      out.sized(a)
      packValue(v, out)
      out.raw(h)
    case Rep.Addr28(stake, extra, coin) =>
      out.tag(2)
      packCredential(stake, out)
      packExtra(extra, out)
      packCoin(coin, out)
    case Rep.Addr28DH(stake, extra, coin, h) =>
      out.tag(3)
      packCredential(stake, out)
      packExtra(extra, out)
      packCoin(coin, out)
      packWords(h, out)
    case Rep.CompactDatum(a, v, d) =>
      out.tag(4)
      out.sized(a)
      packValue(v, out)
      out.sized(d)
    case Rep.CompactRefScript(a, v, d, s) =>
      out.tag(5)
      out.sized(a)
      packValue(v, out)
      packDatum(d, out)
      packScript(s, out)

  private def packCoin(coin: Long, out: Packer): Unit =
    out.tag(0)
    out.varLen(coin)

  private def packExtra(e: Addr28Extra, out: Packer): Unit =
    out.word64le(e.a)
    out.word64le(e.b)
    out.word64le(e.c)
    out.word64le(e.d)

  // ---- unpacking

  private def unpackValue[E <: EraTag](
      in: Unpacker
  )(using ShelleyBasedEra[E]): Result[PackedValue] =
    in.tag.flatMap {
      case 0 => in.varLen.map(PackedValue.AdaOnly(_))
      case 1 if !coinOnlyValues[E] =>
        for
          coin <- in.varLen
          count <- in.length
          rep <- in.sized
        yield PackedValue.Assets(coin, count, rep)
      case 1 if coinOnlyValues[E] =>
        Left(Failure.UnknownTag(TagSite.CompactCoin, 1))
      case t => Left(Failure.UnknownTag(TagSite.CompactValue, t))
    }

  private def unpackCoin(in: Unpacker): Result[Long] =
    in.tag.flatMap {
      case 0 => in.varLen
      case t => Left(Failure.UnknownTag(TagSite.CompactCoin, t))
    }

  private def unpackCredential(in: Unpacker): Result[Credential] =
    in.tag.flatMap {
      case 0 => in.raw(28).map(Credential.Script(_))
      case 1 => in.raw(28).map(Credential.Key(_))
      case t => Left(Failure.UnknownTag(TagSite.Credential, t))
    }

  private def unpackExtra(in: Unpacker): Result[Addr28Extra] =
    for
      a <- in.word64le
      b <- in.word64le
      c <- in.word64le
      d <- in.word64le
    yield Addr28Extra(a, b, c, d)

  /** `DataHash32`: four words, each the little-endian write of a big-endian word of the hash. */
  private def unpackHash32(in: Unpacker): Result[Bytes] =
    for
      a <- in.word64le
      b <- in.word64le
      c <- in.word64le
      d <- in.word64le
    yield
      val out = new Array[Byte](32)
      List(a, b, c, d).zipWithIndex.foreach { (w, i) =>
        var k = 0
        while k < 8 do
          out(8 * i + k) = (w >>> (56 - 8 * k)).toByte
          k += 1
      }
      Bytes.wrap(out)

  private def unpackDatum(in: Unpacker): Result[Datum] =
    in.tag.flatMap {
      case 0 => Right(Datum.NoDatum)
      case 1 => in.raw(32).map(Datum.Hash(_))
      case 2 => in.sized.map(Datum.Inline(_))
      case t => Left(Failure.UnknownTag(TagSite.Datum, t))
    }

  private def unpackScript[E <: EraTag](in: Unpacker)(using ShelleyBasedEra[E]): Result[RefScript] =
    in.tag.flatMap {
      case 0 => in.sized.map(RefScript(ScriptKind.Native, _))
      case 1 =>
        in.tag.flatMap { language =>
          val kind = language match
            case 0 => Right(ScriptKind.PlutusV1)
            case 1 =>
              Either.cond(
                languages[E] >= 2,
                ScriptKind.PlutusV2,
                Failure.NotInEra(Feature.PlutusV2, eraOf[E])
              )
            case 2 =>
              Either.cond(
                languages[E] >= 3,
                ScriptKind.PlutusV3,
                Failure.NotInEra(Feature.PlutusV3, eraOf[E])
              )
            case t => Left(Failure.UnknownTag(TagSite.PlutusLanguage, t))
          kind.flatMap(k => in.sized.map(RefScript(k, _)))
        }
      case t => Left(Failure.UnknownTag(TagSite.Script, t))
    }

  private def unpackRep[E <: EraTag](in: Unpacker)(using ShelleyBasedEra[E]): Result[Rep] =
    in.tag.flatMap { tag =>
      val feature = tag match
        case 1     => Some(Feature.DatumHash)
        case 2 | 3 => Some(Feature.Addr28Constructor)
        case 4     => Some(Feature.InlineDatum)
        case 5     => Some(Feature.ReferenceScript)
        case _     => None
      if tag > maxTag[E] then
        Left(feature match
          case Some(f) if tag <= 5 => Failure.NotInEra(f, eraOf[E])
          case _                   => Failure.UnknownTag(TagSite.TxOutConstructor, tag))
      else
        tag match
          case 0 =>
            for
              a <- in.sized
              v <- unpackValue[E](in)
            yield Rep.Compact(a, v)
          case 1 =>
            for
              a <- in.sized
              v <- unpackValue[E](in)
              h <- in.raw(32)
            yield Rep.CompactDH(a, v, h)
          case 2 =>
            for
              stake <- unpackCredential(in)
              extra <- unpackExtra(in)
              coin <- unpackCoin(in)
            yield Rep.Addr28(stake, extra, coin)
          case 3 =>
            for
              stake <- unpackCredential(in)
              extra <- unpackExtra(in)
              coin <- unpackCoin(in)
              h <- unpackHash32(in)
            yield Rep.Addr28DH(stake, extra, coin, h)
          case 4 =>
            for
              a <- in.sized
              v <- unpackValue[E](in)
              d <- in.sized
            yield Rep.CompactDatum(a, v, d)
          case 5 =>
            for
              a <- in.sized
              v <- unpackValue[E](in)
              d <- unpackDatum(in)
              s <- unpackScript[E](in)
            yield Rep.CompactRefScript(a, v, d, s)
          case t => Left(Failure.UnknownTag(TagSite.TxOutConstructor, t))
    }

  // ---- from the constructors back to the parts

  private def unsigned(l: Long): BigInt = if l >= 0 then BigInt(l) else BigInt(l) + TwoTo64

  private def value(v: PackedValue): Result[Value] = v match
    case PackedValue.AdaOnly(coin) => Right(Value(unsigned(coin), Map.empty))
    case PackedValue.Assets(coin, n, rep) =>
      MultiAssetRep.read(n, rep).map(Value(unsigned(coin), _))

  /** The 57-byte base address a compact output stands for (`decodeAddress28`,
    * `Alonzo/TxOut.hs:127`).
    */
  private def address28(stake: Credential, e: Addr28Extra): Bytes =
    val out = new Array[Byte](57)
    val keyPayment = (e.d & 1L) != 0
    val network = ((e.d >>> 1) & 1L).toInt
    val keyStake = stake match
      case Credential.Key(_)    => true
      case Credential.Script(_) => false
    out(0) = ((if keyPayment then 0 else 0x10) | (if keyStake then 0 else 0x20) | network).toByte
    def put(at: Int, w: Long, bytes: Int): Unit =
      var k = 0
      while k < bytes do
        out(at + k) = (w >>> (8 * (bytes - 1 - k))).toByte
        k += 1
    put(1, e.a, 8)
    put(9, e.b, 8)
    put(17, e.c, 8)
    put(25, e.d >>> 32, 4)
    stake.hash.copyTo(out, 29)
    Bytes.wrap(out)

  private def fromRep(rep: Rep): Result[TxOutParts] = rep match
    case Rep.Compact(a, v)      => value(v).map(TxOutParts(a, _, Datum.NoDatum, None))
    case Rep.CompactDH(a, v, h) => value(v).map(TxOutParts(a, _, Datum.Hash(h), None))
    case Rep.Addr28(stake, e, coin) =>
      Right(TxOutParts(address28(stake, e), Value(unsigned(coin), Map.empty), Datum.NoDatum, None))
    case Rep.Addr28DH(stake, e, coin, h) =>
      Right(TxOutParts(address28(stake, e), Value(unsigned(coin), Map.empty), Datum.Hash(h), None))
    case Rep.CompactDatum(a, v, d)        => value(v).map(TxOutParts(a, _, Datum.Inline(d), None))
    case Rep.CompactRefScript(a, v, d, s) => value(v).map(TxOutParts(a, _, d, Some(s)))
