// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger.byron

import cats.syntax.all.*
import java.util.zip.CRC32
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.node.ledger.CborRead

/** Byte-level helpers for the Byron CBOR encodings that signatures and hashes cover. */
object ByronCbor:
  /** The minimal CBOR encoding of an unsigned integer (what `encCBOR` of a `Word` produces). */
  def uint(n: BigInt): Bytes =
    val v = n.toLong
    val raw: Array[Byte] =
      if n < 24 then Array(v.toByte)
      else if n < 256 then Array(0x18.toByte, v.toByte)
      else if n < 65536 then Array(0x19.toByte, (v >> 8).toByte, v.toByte)
      else if n < 4294967296L then
        Array(0x1a.toByte, (v >> 24).toByte, (v >> 16).toByte, (v >> 8).toByte, v.toByte)
      else Array(0x1b.toByte) ++ (0 until 8).map(i => (v >> (56 - 8 * i)).toByte)
    Bytes.fromArray(raw)

  /** A definite CBOR byte string around `content`. */
  def bytes(content: Bytes): Bytes =
    val n = content.size
    val head: Array[Byte] =
      if n < 24 then Array((0x40 + n).toByte)
      else if n < 256 then Array(0x58.toByte, n.toByte)
      else Array(0x59.toByte, (n >> 8).toByte, n.toByte)
    Bytes.fromArray(head) ++ content

  def concat(parts: Bytes*): Bytes = parts.foldLeft(Bytes.empty)(_ ++ _)

  def of(bytes: Int*): Bytes = Bytes.fromArray(bytes.map(_.toByte).toArray)

  def sha3(b: Bytes): Bytes =
    Bytes.fromArray(java.security.MessageDigest.getInstance("SHA3-256").digest(b.toArray))

  /** `blake2b-224(sha3-256(x))`, the Byron address and key hash. */
  def addressHash(encoded: Bytes): Bytes = Blake2b.hash224.hash(sha3(encoded))

  /** The key hash of a 64-byte extended verification key (hash of its CBOR byte string). */
  def keyHash(vk: Bytes): Bytes = addressHash(bytes(vk))

  def crc32(b: Bytes): Long =
    val c = new CRC32
    c.update(b.unsafeArray, b.unsafeOffset, b.size)
    c.getValue

/** A decoded Byron address. `raw` is its CBOR encoding (the byte string a genesis balance, a
  * transaction output or a Shelley output carries), `attributes` the original encoding of its
  * attribute map, which the address root covers.
  */
final case class ByronAddress(
    raw: Bytes,
    root: Bytes,
    attributes: Bytes,
    addrType: Int,
    networkMagic: Option[Long],
    unknownAttributesLength: Int
):
  def isRedeem: Boolean = addrType == ByronAddress.Redeem

  /** The root `blake2b-224(sha3-256([type, [type, key], attributes]))` of an address that is spent
    * by `key` (64 bytes for a verification key, 32 for a redeem key).
    */
  def rootFor(key: Bytes): Bytes = ByronAddress.rootOf(addrType, key, attributes)

  /** `checkVerKeyAddress` / `checkRedeemAddress`: the key (of the right kind) hashes to the root.
    */
  def checks(key: Bytes): Boolean =
    key.size == (if isRedeem then 32 else 64) && rootFor(key) == root

object ByronAddress:
  val VerKey = 0
  val Redeem = 2

  private val limits = Cbor.Limits(4096, 16, 256, 4096)

  def rootOf(addrType: Int, key: Bytes, attributes: Bytes): Bytes =
    val spending = ByronCbor.concat(ByronCbor.of(0x82, addrType), ByronCbor.bytes(key))
    ByronCbor.addressHash(ByronCbor.concat(ByronCbor.of(0x83, addrType), spending, attributes))

  /** The attribute map of an address (or a transaction): numeric key to byte string. */
  private def attributeFields(n: Node, what: String): Either[String, Vector[(BigInt, Bytes)]] =
    CborRead
      .map(n, what)
      .flatMap(
        _.traverse((k, v) => (CborRead.uint(k, s"$what key"), CborRead.bytes(v, what)).tupled)
      )

  /** The total length of the values of attributes the ledger does not interpret. */
  private def unknownLength(fields: Vector[(BigInt, Bytes)], known: Set[Int]): Int =
    fields.filterNot((k, _) => known.contains(k.toInt)).map(_._2.size).sum

  /** Length of the attribute values of a transaction (none of its attributes is interpreted). */
  def attributesLength(n: Node, what: String): Either[String, Int] =
    attributeFields(n, what).map(unknownLength(_, Set.empty))

  def decode(raw: Bytes): Either[String, ByronAddress] =
    for
      root <- Cbor.decode(raw, limits)
      outer <- CborRead.arrayN(root, 2, "Byron address")
      payload <- outer(0).value match
        case Value.Tag(t, inner) if t == 24 => CborRead.bytes(inner, "Byron address payload")
        case _                              => Left("Byron address: expected tag 24")
      crc <- CborRead.uint(outer(1), "Byron address crc")
      _ <- Either.cond(crc == BigInt(ByronCbor.crc32(payload)), (), "Byron address: bad crc")
      parts <- Cbor.decode(payload, limits).flatMap(CborRead.arrayN(_, 3, "Byron address body"))
      addrRoot <- CborRead.bytesN(parts(0), 28, "Byron address root")
      fields <- attributeFields(parts(1), "Byron address attributes")
      ty <- CborRead.uint(parts(2), "Byron address type").map(_.toInt)
      _ <- Either.cond(ty == VerKey || ty == Redeem, (), s"Byron address: unknown type $ty")
      magic <- fields.find(_._1 == 2).traverse { (_, v) =>
        Cbor
          .decode(v, limits)
          .flatMap(CborRead.uint(_, "network magic"))
          .filterOrElse(_ < (BigInt(1) << 32), "network magic out of range")
          .map(_.toLong)
      }
    yield ByronAddress(
      raw,
      addrRoot,
      parts(1).original,
      ty,
      magic,
      unknownLength(fields, Set(1, 2))
    )

  /** The address that `key` spends (a 64-byte extended key for `VerKey`, 32 bytes for `Redeem`) on
    * a network: `networkMagic` is carried as attribute 2 on networks that require one. This is the
    * encoding genesis balances, outputs and Shelley bootstrap outputs use.
    */
  def make(addrType: Int, key: Bytes, networkMagic: Option[Long]): Bytes =
    val attributes = networkMagic.fold(ByronCbor.of(0xa0)) { m =>
      ByronCbor.concat(ByronCbor.of(0xa1, 0x02), ByronCbor.bytes(ByronCbor.uint(m)))
    }
    val body = ByronCbor.concat(
      ByronCbor.of(0x83),
      ByronCbor.bytes(rootOf(addrType, key, attributes)),
      attributes,
      ByronCbor.uint(addrType)
    )
    ByronCbor.concat(
      ByronCbor.of(0x82, 0xd8, 0x18),
      ByronCbor.bytes(body),
      ByronCbor.uint(ByronCbor.crc32(body))
    )

  /** The address of a redeem key: used for the AVVM part of the genesis UTxO. */
  def redeem(key: Bytes, networkMagic: Option[Long]): Bytes = make(Redeem, key, networkMagic)
