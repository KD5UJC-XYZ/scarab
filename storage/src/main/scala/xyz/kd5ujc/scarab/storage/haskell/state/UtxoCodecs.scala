// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{Address, ByteStrings, Datum, TxIn, TxOut, Value}
import xyz.kd5ujc.scarab.ledger.state.{EraTag, ProtVer, ShelleyBasedEra}
import xyz.kd5ujc.scarab.models.runtime.{
  Codec,
  ConstraintViolation,
  DecodeError,
  Decoded,
  Node,
  Writer
}

import DecodeError.Kind
import NodeOrders.given

/** The UTxO as the node-to-client queries answer it (`GetUTxOByAddress`, `GetUTxOByTxIn`,
  * `GetUTxOWhole`): a map from input to output, in input order. It is not in the state codec, whose
  * UTxO is `map(0)` (the entries are in the ledger's `tables`), and the node holds its entries as
  * [[TxIn]] and [[TxOut]], so the codecs are over those.
  *
  * The reference writes a query's UTxO with `toCBOR`, which is `toEraCBOR @era`
  * (`Ouroboros/Consensus/Shelley/Ledger/Query.hs:1015`, ouroboros-consensus 82ecba32;
  * `Cardano/Ledger/State/UTxO.hs:95`, cardano-ledger f649f975): at the lowest protocol version of
  * the era ([[Versions.eraLow]]). [[utxo]] is Conway's; [[utxoIn]] is any Shelley-based era's.
  */
object UtxoCodecs:
  private given ProtVer = Versions.eraLow(ShelleyBasedEra.SConway)

  private val limits =
    Cbor.Limits(maxInputBytes = 1048576, maxItems = 100000, maxStringBytes = 1048576)

  /** `TxIn` (`TxIn.hs`): `[transaction id, index]`. */
  given txIn: Codec[TxIn] = Record.of[TxIn]((Wire.hash(32), Wire.word16))

  /** `MaryValue` (`Mary/Value.hs`): a bare coin, or `[coin, {policy: {asset: quantity}}]`. */
  given value: Codec[Value] = new Codec[Value]:
    private val coin = Wire.word(64)
    private val assets = Wire.sortedMapOf(Codec.bytes, Wire.sortedMapOf(Codec.bytes, Codec.int))
    private val withAssets = Record.of[(BigInt, Map[Bytes, Map[Bytes, BigInt]])]((coin, assets))
    def decode(n: Node): Decoded[Value] = n.value match
      case CborValue.UInt(_) => coin.decode(n).map(Value(_, Map.empty))
      case _                 => withAssets.decode(n).map((c, a) => Value(c, a))
    def encode(a: Value, out: Writer): Unit =
      if a.assets.isEmpty then out.uint(a.coin)
      else
        out.arrayHead(2)
        out.uint(a.coin)
        assets.encode(a.assets, out)

  /** `BabbageTxOut`: `[address, value]`, `[address, value, datum hash]`, or the keyed form with an
    * inline datum or a reference script (`encodeTxOut`, `Babbage/TxOut.hs:581-595`). The keyed
    * form's items are written as the reference writes them, not copied as they came:
    *   - the datum as `[1, #6.24(bytes)]` around the bytes of the data (`EncCBOR (Datum era)`,
    *     `BinaryData`, `Plutus/Data.hs:217-218,297-301`);
    *   - the reference script as `#6.24` around the bytes of `[kind, script]` (`encodeNestedCbor`,
    *     `Binary/Encoding.hs:82-85`; `encodeScript`, `Alonzo/Scripts.hs:686-689`): a native script
    *     in the encoding it came with (its `MemoBytes`), a Plutus script's bytes as one byte
    *     string.
    */
  given txOut: Codec[TxOut] = new Codec[TxOut]:
    // A stored output: it reads in any form of byte string it was stored with.
    def decode(n: Node): Decoded[TxOut] =
      TxOut
        .decode(n, Address.Mode.Rigorous, TxOut.Forms.Stored)(using ByteStrings.Chunked)
        .left
        .map(why => DecodeError.at(n, Kind.Domain(why)))

    override def check(o: TxOut): Option[ConstraintViolation] =
      Codec.refuse(
        o.scriptRef.isDefined && scriptRef(o).isEmpty,
        "TxOut",
        "a reference script its encoding holds",
        o.raw.hex
      )

    def encode(o: TxOut, out: Writer): Unit = o.datum match
      case Datum.NoDatum if o.scriptRef.isEmpty =>
        out.arrayHead(2)
        out.bytes(o.address.raw)
        value.encode(o.value, out)
      case Datum.Hash(h) if o.scriptRef.isEmpty =>
        out.arrayHead(3)
        out.bytes(o.address.raw)
        value.encode(o.value, out)
        out.bytes(h)
      case _ =>
        val datumItem: Option[Writer => Unit] = o.datum match
          case Datum.NoDatum => None
          case Datum.Hash(h) =>
            Some { w =>
              w.arrayHead(2)
              w.uint(0L)
              w.bytes(h)
            }
          case Datum.Inline(data) =>
            Some { w =>
              w.arrayHead(2)
              w.uint(1L)
              w.tag(24)
              w.bytes(data)
            }
        val scriptItem = scriptRef(o)
        out.mapHead(2 + datumItem.size + scriptItem.size)
        out.uint(0L)
        out.bytes(o.address.raw)
        out.uint(1L)
        value.encode(o.value, out)
        datumItem.foreach { write =>
          out.uint(2L)
          write(out)
        }
        scriptItem.foreach { s =>
          out.uint(3L)
          out.raw(s)
        }

  /** The reference script of `o`, written as the reference writes it (see [[txOut]]); none when `o`
    * has none, or when its encoding holds no script it can be written from.
    */
  private def scriptRef(o: TxOut): Option[Bytes] =
    // a byte string in either form: a chunked one is its chunks joined
    def bytes(n: Node): Option[Bytes] = n.value match
      case CborValue.ByteString(b) => Some(b)
      case _                       => None
    def nested(script: Writer => Unit): Bytes =
      val inner = new Writer
      script(inner)
      val outer = new Writer
      outer.tag(24)
      outer.bytes(inner.result)
      outer.result
    for
      _ <- o.scriptRef
      root <- TxOut.node(o.raw).toOption
      field <- root.value match
        case CborValue.Map(kvs) =>
          kvs.collectFirst { case (k, v) if k.value == CborValue.UInt(BigInt(3)) => v }
        case _ => None
      wrapped <- field.value match
        case CborValue.Tag(t, inner) if t == 24 => bytes(inner)
        case _                                  => None
      script <- Cbor.decode(wrapped, limits).toOption
      written <- script.value match
        case CborValue.Arr(Vector(kind, body)) =>
          kind.value match
            case CborValue.UInt(k) if k == 0 =>
              Some(nested { w =>
                w.arrayHead(2)
                w.uint(0L)
                w.raw(body.original)
              })
            case CborValue.UInt(k) if k >= 1 && k <= 3 =>
              bytes(body)
                .map(plutus =>
                  nested { w =>
                    w.arrayHead(2)
                    w.uint(k)
                    w.bytes(plutus)
                  }
                )
            case _ => None
        case _ => None
    yield written

  /** `UTxO` (`UTxO.hs`): the outputs by input, in input order. */
  given utxo: Codec[Map[TxIn, TxOut]] = Wire.sortedMapOf(txIn, txOut)

  /** The form of an output in an era: its era's `TxOut` and the parts the type holds. Every era
    * writes the parts it holds as [[txOut]] does, so an output that fits its era's form is written
    * the same in each.
    */
  enum OutputForm:
    /** `ShelleyTxOut` over `Coin`: `[address, coin]` (`Shelley/TxOut.hs:150-154`). */
    case CoinOnly

    /** `ShelleyTxOut` over `MaryValue`: `[address, value]`, the value a bare coin or
      * `[coin, assets]` (`Shelley/TxOut.hs:150-154`, `Mary/Value.hs:342-350`).
      */
    case WithAssets

    /** `AlonzoTxOut`: `[address, value]` or `[address, value, datum hash]`
      * (`Alonzo/TxOut.hs:376-386`).
      */
    case WithDatumHash

    /** `BabbageTxOut`: the arrays of Alonzo, or the keyed form with an inline datum or a reference
      * script (`Babbage/TxOut.hs:501-506`).
      */
    case Babbage

    /** Whether `o` has only parts this form holds. */
    def holds(o: TxOut): Boolean = this match
      case CoinOnly   => o.value.assets.isEmpty && withoutDatum(o)
      case WithAssets => withoutDatum(o)
      case WithDatumHash =>
        o.scriptRef.isEmpty && (o.datum match
          case Datum.Inline(_)               => false
          case Datum.NoDatum | Datum.Hash(_) => true)
      case Babbage => true

    private def withoutDatum(o: TxOut): Boolean = o.datum == Datum.NoDatum && o.scriptRef.isEmpty

  object OutputForm:
    /** The form of `era`'s outputs: the `TxOut` of its `EraTxOut` instance. */
    def of[E <: EraTag](era: ShelleyBasedEra[E]): OutputForm = era match
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra => CoinOnly
      case ShelleyBasedEra.SMary                               => WithAssets
      case ShelleyBasedEra.SAlonzo                             => WithDatumHash
      case ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway  => Babbage

  /** An output of `era`: [[txOut]], refusing to write an output with a part the era's `TxOut` does
    * not hold.
    */
  def txOutIn[E <: EraTag](era: ShelleyBasedEra[E]): Codec[TxOut] =
    val form = OutputForm.of(era)
    new Codec[TxOut]:
      def decode(n: Node): Decoded[TxOut] = txOut.decode(n)
      def encode(o: TxOut, out: Writer): Unit = txOut.encode(o, out)
      override def check(o: TxOut): Option[ConstraintViolation] =
        txOut
          .check(o)
          .orElse(Codec.refuse(!form.holds(o), "TxOut", s"an output of $era ($form)", o.datum))

  /** `UTxO` of `era`, written at the era's lowest protocol version as the reference's queries write
    * it.
    */
  def utxoIn[E <: EraTag](era: ShelleyBasedEra[E]): Codec[Map[TxIn, TxOut]] =
    given ProtVer = Versions.eraLow(era)
    Wire.sortedMapOf(txIn, txOutIn(era))
