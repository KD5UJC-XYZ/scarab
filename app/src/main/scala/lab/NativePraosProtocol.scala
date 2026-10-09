// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.header.PraosNonceEvolution as Nonces
import scala.util.control.NonFatal

/** Narrow decoder for the original DebugChainDepState PraosState payload.
  *
  * Ouroboros-consensus 4.2.1.0 Protocol/Praos.hs serialises version 0 around an eight-field record;
  * Util/Versioned.hs encodes [version, payload]. Cardano-slotting 0.2.2.0 WithOrigin derives
  * Serialise: Origin=[0], At=[1, slot]. Shelley Nonce is [0] or [1, bytes32]. The mandatory
  * previous-epoch nonce is retained, including explicit Neutral.
  *
  * Parsing establishes shape only, not point/source authenticity, cryptography, or admission.
  * Original bytes are preserved; no alternate-era or legacy-format inference is attempted.
  */
private[lab] object NativePraosProtocol:
  val MaxBytes = 8 * 1024 * 1024
  val MaxCounters = 4096
  private val MaxUInt64 = (BigInt(1) << 64) - 1

  final class Decoded private[NativePraosProtocol] (
      val snapshot: PraosNonceSnapshot.Snapshot,
      val counters: Map[Bytes, BigInt],
      val original: Bytes,
      val digest: Bytes
  )

  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))

  private def get[A](result: Either[String, A]): A =
    result.fold(message => throw new IllegalArgumentException(message), identity)

  private def array(node: Node, width: Int): Vector[Node] = node.value match
    case V.Arr(values) if values.size == width => values
    case _ => throw new IllegalArgumentException("native Praos record width/shape")

  private def uint(node: Node): BigInt = node.value match
    case V.UInt(value) if value >= 0 && value <= MaxUInt64 => value
    case _ => throw new IllegalArgumentException("native Praos uint64 required")

  private def bytes(node: Node, width: Int): Bytes = node.value match
    case V.ByteString(value) if value.size == width => value
    case _ => throw new IllegalArgumentException("native Praos hash width/shape")

  private def nonce(node: Node): Nonces.Nonce = node.value match
    case V.Arr(values) if values.size == 1 && values.head.value == V.UInt(0) =>
      Nonces.Nonce.Neutral
    case V.Arr(values) if values.size == 2 && values.head.value == V.UInt(1) =>
      Nonces.Nonce.Hash(bytes(values(1), 32))
    case _ => throw new IllegalArgumentException("native Praos nonce constructor/width")

  def decode(raw: Bytes): Either[String, Decoded] = checked {
    require(
      raw != null && raw.value != null && raw.size > 0 && raw.size <= MaxBytes,
      "bounded original native Praos bytes required"
    )
    val root = get(Cbor.decode(raw, Cbor.Limits(MaxBytes, 8, 2 * MaxCounters + 128, 32)))
    val versioned = array(root, 2)
    require(uint(versioned(0)) == 0, "unsupported native Praos serialisation version")
    val fields = array(versioned(1), 8)
    val last = array(fields(0), 2)
    require(uint(last(0)) == 1, "native Praos non-origin last slot required")
    val slot = uint(last(1))
    val counters = fields(1).value match
      case V.Map(entries) =>
        require(entries.size <= MaxCounters, "native Praos counter count bound")
        val parsed = entries.map((key, value) => bytes(key, 28) -> uint(value))
        require(
          parsed.map(_._1).distinct.size == parsed.size,
          "duplicate native Praos counter issuer"
        )
        parsed.toMap
      case _ => throw new IllegalArgumentException("native Praos counter map required")
    val snapshot = PraosNonceSnapshot.Snapshot(
      slot,
      Nonces.Fields(
        nonce(fields(2)),
        nonce(fields(3)),
        nonce(fields(4)),
        Some(nonce(fields(5))),
        nonce(fields(6)),
        nonce(fields(7))
      )
    )
    new Decoded(snapshot, counters, raw, ClusterHeaderObservation.sha256(raw))
  }
