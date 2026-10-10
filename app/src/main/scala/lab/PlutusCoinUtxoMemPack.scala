// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.PlutusOutput
import scala.util.control.NonFatal

/** Opt-in x86_64 native state codec. Source: cardano-ledger-babbage Babbage/TxOut.hs, tag 4 =
  * CompactAddr, compact coin value, BinaryData; core Plutus/Data.hs derives BinaryData's MemPack
  * from ShortByteString. mempack-0.2.1.0 packs ShortByteString with a Length (unsigned base-128
  * varint), then unchanged bytes, WITHOUT Datum tag 2. Reconstructed output CBOR has fresh framing;
  * original native and datum bytes are retained.
  */
private[lab] object PlutusCoinUtxoMemPack:
  final class Decoded private[PlutusCoinUtxoMemPack] (
      val originalNative: Bytes,
      val utxo: Bytes,
      val snapshot: PlutusOutput.Snapshot
  ):
    val fullLedgerValidated = false
  private def get[A](v: Either[?, A]): A =
    v.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def n(v: V) = Node(v, Bytes.empty)
  private def bytes(n: Node): Bytes = n.value match
    case V.ByteString(b) if (n.original.value.head & 31) != 31 => b
    case _ => throw new IllegalArgumentException("definite MemPack bytes required")
  private final class Reader(raw: Bytes):
    private var position = 0
    def take(count: Int): Bytes =
      require(count >= 0 && count <= raw.size - position, "truncated MemPack payload")
      val result = Bytes(raw.value.slice(position, position + count))
      position += count
      result
    def byte(): Int = take(1).value.head & 255
    def variable(): BigInt =
      var result = BigInt(0)
      var count = 0
      var more = true
      while more do
        require(count < 10, "MemPack varint length")
        val b = byte()
        require(count != 0 || b != 128, "noncanonical MemPack varint")
        result = (result << 7) | (b & 127)
        require(result <= (BigInt(1) << 64) - 1, "MemPack varint overflow")
        count += 1
        more = (b & 128) != 0
      result
    def end(): Unit = require(position == raw.size, "trailing MemPack payload")

  def decode(raw: Bytes, networkId: Int): Either[String, Decoded] =
    try
      require(raw != null && raw.value != null && raw.size <= 524288, "MemPack input bound")
      val root = get(Cbor.decode(raw, Cbor.Limits(524288, 32, 4096, 4096)))
      val rows = root.value match
        case V.Map(xs) if xs.size <= 4096 => xs
        case _ => throw new IllegalArgumentException("bounded MemPack UTxO map required")
      val decoded = rows.map { (key, value) =>
        val k = bytes(key)
        val v = bytes(value)
        require(k.size == 34 && v.size <= 4096 && v.size > 0, "MemPack key/value width")
        if (v.value.head & 255) == 4 then
          val r = new Reader(v)
          require(r.byte() == 4 && r.variable() == 29, "enterprise inline CompactAddr required")
          val address = r.take(29)
          require(r.byte() == 0, "only scalar ADA compact value supported")
          val coin = r.variable()
          val length = r.variable()
          require(length > 0 && length <= PlutusOutput.MaxDatumBytes, "inline datum length bound")
          val datum = r.take(length.toInt)
          r.end()
          val index = BigInt((k.value(32) & 255) | ((k.value(33) & 255) << 8))
          val ref = n(V.Arr(Vector(n(V.ByteString(Bytes(k.value.take(32)))), n(V.UInt(index)))))
          val out = n(
            V.Map(
              Vector(
                n(V.UInt(0)) -> n(V.ByteString(address)),
                n(V.UInt(1)) -> n(V.UInt(coin)),
                n(V.UInt(2)) -> n(V.Arr(Vector(n(V.UInt(1)), n(V.Tag(24, n(V.ByteString(datum)))))))
              )
            )
          )
          ref -> out
        else
          // Keep existing tag 0/2 implementation and all its exclusions untouched.
          val single = get(Cbor.encode(V.Map(Vector(key -> value))))
          val legacy = get(NativeCoinUtxoMemPack.decode(single))
          get(Cbor.decode(legacy)).value.asInstanceOf[V.Map].value.head
      }
      val utxo = get(Cbor.encode(V.Map(decoded)))
      val snapshot = get(PlutusOutput.snapshot(utxo, networkId))
      Right(new Decoded(raw, utxo, snapshot))
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
