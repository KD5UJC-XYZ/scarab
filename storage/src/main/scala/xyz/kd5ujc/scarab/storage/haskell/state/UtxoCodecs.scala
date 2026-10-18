// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{Datum, TxIn, TxOut, Value}
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, ShelleyBasedEra}
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError, Decoded, Node, Writer}

import DecodeError.Kind
import NodeOrders.given

/** The UTxO as the node-to-client queries answer it (`GetUTxOByAddress`, `GetUTxOByTxIn`,
  * `GetUTxOWhole`): a map from input to output, in input order. It is not in the state codec, whose
  * UTxO is `map(0)` (the entries are in the ledger's `tables`), and the node holds its entries as
  * [[TxIn]] and [[TxOut]], so the codecs are over those.
  *
  * Written at the lowest protocol version of the Conway era, which is how the reference writes the
  * ledger state of the era (`Versions.eraLow`).
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
    * inline datum or a reference script. The datum and script items are the ones the output was
    * created with.
    */
  given txOut: Codec[TxOut] = new Codec[TxOut]:
    def decode(n: Node): Decoded[TxOut] =
      TxOut.decode(n).left.map(why => DecodeError.at(n, Kind.Domain(why)))

    /** The items of the output as it came, by key: the inline datum and the reference script. */
    private def items(o: TxOut): Map[Int, Bytes] =
      Cbor
        .decode(o.raw, limits)
        .toOption
        .map(_.value)
        .collect {
          case CborValue.Map(kvs) =>
            kvs.flatMap { (k, v) =>
              k.value match
                case CborValue.UInt(i) => Some(i.toInt -> v.original)
                case _                 => None
            }.toMap
          case CborValue.Arr(xs) =>
            xs.zipWithIndex.drop(2).map((n, i) => i -> n.original).toMap
        }
        .getOrElse(Map.empty)

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
        val fields = items(o)
        val datumItem: Option[Writer => Unit] = o.datum match
          case Datum.NoDatum => None
          case Datum.Hash(h) =>
            Some { w =>
              w.arrayHead(2)
              w.uint(0L)
              w.bytes(h)
            }
          case Datum.Inline(_) => fields.get(2).map(d => (w: Writer) => w.raw(d))
        val scriptItem = o.scriptRef.flatMap(_ => fields.get(3))
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

  /** `UTxO` (`UTxO.hs`): the outputs by input, in input order. */
  given utxo: Codec[Map[TxIn, TxOut]] = Wire.sortedMapOf(txIn, txOut)
