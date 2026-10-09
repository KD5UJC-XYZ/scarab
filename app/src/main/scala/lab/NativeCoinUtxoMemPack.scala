// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import scala.util.control.NonFatal

/** Checked subset of the pinned x86_64 little-endian native MemPack profile. Only coin-only
  * enterprise (tag 0) and base (tag 2) outputs are admitted. Produces ordinary UTxO CBOR for
  * semantic comparison; never imports runtime state.
  */
private[lab] object NativeCoinUtxoMemPack:
  private val MaxCoin = (BigInt(1) << 64) - 1
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def node(v: V): Node = Node(v, Bytes.empty)
  private def bytes(n: Node): Bytes = n.value match
    case V.ByteString(b) => b
    case _               => throw new IllegalArgumentException("MemPack map requires byte strings")

  private final class Reader(raw: Bytes):
    private var offset = 0
    def take(n: Int): Vector[Byte] =
      require(n >= 0 && n <= raw.size - offset, "truncated MemPack payload")
      val out = raw.value.slice(offset, offset + n)
      offset += n
      out
    def byte(): Int = take(1).head & 255
    def little(n: Int): BigInt = BigInt(1, take(n).reverse.toArray)
    def variable(): BigInt =
      var result = BigInt(0)
      var count = 0
      var more = true
      while more do
        require(count < 10, "MemPack varint length")
        val b = byte()
        require(count != 0 || b != 128, "noncanonical MemPack varint")
        result = (result << 7) | BigInt(b & 127)
        require(result <= MaxCoin, "MemPack varint overflow")
        count += 1
        more = (b & 128) != 0
      result
    def end(): Unit = require(offset == raw.size, "trailing MemPack payload")

  private def output(raw: Bytes): Node =
    val r = new Reader(raw)
    val address = r.byte() match
      case 0 =>
        require(r.variable() == 29, "only enterprise CompactAddr admitted")
        val a = r.take(29)
        val header = a.head & 255
        require((header >> 4) == 6 || (header >> 4) == 7, "enterprise address type")
        require((header & 15) <= 1, "native network profile")
        a
      case 2 =>
        // Credential MemPack tags are Script=0/Key=1, unlike credential CBOR.
        val stakeTag = r.byte()
        require(stakeTag == 0 || stakeTag == 1, "staking credential MemPack tag")
        val stake = r.take(28)
        val words = Vector.fill(4)(r.little(8))
        val flags = words(3) & ((BigInt(1) << 32) - 1)
        require(flags <= 3, "Addr28Extra reserved bits")
        def big(n: BigInt, width: Int): Vector[Byte] =
          Vector.tabulate(width)(i => ((n >> (8 * (width - i - 1))) & 255).toByte)
        val payment = words.take(3).flatMap(w => big(w, 8)) ++ big(words(3) >> 32, 4)
        val network = ((flags >> 1) & 1).toInt
        val paymentScript = 1 - (flags & 1).toInt
        val stakeScript = 1 - stakeTag
        Vector((network | (paymentScript << 4) | (stakeScript << 5)).toByte) ++ payment ++ stake
      case _ => throw new IllegalArgumentException("unsupported MemPack TxOut constructor")
    require(r.byte() == 0, "only coin-only compact value admitted")
    val coin = r.variable()
    r.end()
    node(V.Arr(Vector(node(V.ByteString(Bytes(address))), node(V.UInt(coin)))))

  def decode(raw: Bytes): Either[String, Bytes] =
    try
      require(raw != null && raw.value != null && raw.size <= 524288, "MemPack input bound")
      val root = get(Cbor.decode(raw, Cbor.Limits(524288, 4, 20000, 128)))
      val entries = root.value match
        case V.Map(xs) => xs
        case _         => throw new IllegalArgumentException("MemPack UTxO map required")
      require(entries.size <= 4096, "MemPack UTxO entry bound")
      val decoded = entries.map { (k, v) =>
        val key = bytes(k)
        require(key.size == 34, "MemPack TxIn width")
        val txid = Bytes(key.value.take(32))
        val index = BigInt((key.value(32) & 255) | ((key.value(33) & 255) << 8))
        ((txid, index), output(bytes(v)))
      }
      require(decoded.map(_._1).distinct.size == decoded.size, "duplicate MemPack TxIn")
      Right(get(Cbor.encode(V.Map(decoded.map { case ((txid, index), out) =>
        node(V.Arr(Vector(node(V.ByteString(txid)), node(V.UInt(index))))) -> out
      }))))
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
