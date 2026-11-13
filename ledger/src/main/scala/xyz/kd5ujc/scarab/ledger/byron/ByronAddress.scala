// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import xyz.kd5ujc.scarab.ledger.{DecodeField, DecodeSite, LedgerDecodeError}
import cats.syntax.all.*
import java.util.zip.CRC32
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.CborRead

/** Byte-level helpers for the Byron CBOR encodings that signatures and hashes cover. */
object ByronCbor:
  /** The limits for everything decoded from a Byron block body, and for the items nested in it (a
    * transaction, an address, an output). The reference has no size limit on any of them: an
    * address is decoded by `decodeCrcProtected` and its derivation path by `decodeBytesCanonical`
    * (cardano-ledger `Cardano.Chain.Common.Address`, `instance DecCBOR Address`, and
    * `Cardano.Chain.Common.AddrAttributes`, `instance DecCBOR HDAddressPayload`), and the ledger
    * rule counts only the attributes it does not interpret (`Cardano.Chain.UTxO.Validation`,
    * `validateTxOutNM`: `unknownAttributesLength < 128`). Only the block size and `maxTxSize`
    * (protocol parameters, checked by the ledger rules) bound them, and no decoder counts items or
    * nesting, so these limits have no bound but the input's size ([[Cbor.Limits.Unbounded]], #749).
    * Mainnet has an address of 7,689 bytes (a derivation path of 7,641; slot 1,769,882).
    */
  val BodyLimits: Cbor.Limits = Cbor.Limits.Unbounded

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

  /** The minimal head of major type `major` (0 to 7) with argument `n`. */
  private def head(major: Int, n: BigInt): Bytes =
    val encoded = uint(n)
    Bytes.fromArray(encoded.toArray.updated(0, ((major << 5) | (encoded.toArray(0) & 0x1f)).toByte))

  /** A definite CBOR byte string around `content`, its length written minimally. */
  def bytes(content: Bytes): Bytes = head(2, content.size) ++ content

  /** The minimal head of a definite map of `n` entries. */
  def mapHead(n: Int): Bytes = head(5, n)

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
  * attribute map, and `canonicalAttributes` that map encoded again as the reference encodes it,
  * which the address root covers.
  */
final case class ByronAddress(
    raw: Bytes,
    root: Bytes,
    attributes: Bytes,
    canonicalAttributes: Bytes,
    addrType: Int,
    networkMagic: Option[Long],
    unknownAttributesLength: Int,
    derivationPathLength: Int
):
  def isRedeem: Boolean = addrType == ByronAddress.Redeem

  /** `bootstrapAddressAttrsSize` (`Cardano/Ledger/Address.hs:334`): the derivation path and the
    * attributes the ledger does not interpret, of which an output may carry 64 bytes.
    */
  def attributesSize: Int = derivationPathLength + unknownAttributesLength

  /** `getNetwork` of a bootstrap address (`Cardano/Ledger/Address.hs:175`): the main network (1) if
    * the address carries no network magic, the test network (0) if it does.
    */
  def network: Int = if networkMagic.isEmpty then 1 else 0

  /** The root `blake2b-224(sha3-256([type, [type, key], attributes]))` of an address that is spent
    * by `key` (64 bytes for a verification key, 32 for a redeem key), over the attributes encoded
    * again: `checkAddrSpendingData` hashes `Address' (addrType addr, asd, addrAttributes addr)`,
    * the decoded attributes, whatever widths they were written in (cardano-ledger f649f975,
    * `Cardano/Chain/Common/Address.hs:306-313`, `AddressHash.hs:16-25`).
    */
  def rootFor(key: Bytes): Bytes = ByronAddress.rootOf(addrType, key, canonicalAttributes)

  /** `checkVerKeyAddress` / `checkRedeemAddress`: the key (of the right kind) hashes to the root.
    */
  def checks(key: Bytes): Boolean =
    key.size == (if isRedeem then 32 else 64) && rootFor(key) == root

object ByronAddress:
  val VerKey = 0
  val Redeem = 2

  def rootOf(addrType: Int, key: Bytes, attributes: Bytes): Bytes =
    val spending = ByronCbor.concat(ByronCbor.of(0x82, addrType), ByronCbor.bytes(key))
    ByronCbor.addressHash(ByronCbor.concat(ByronCbor.of(0x83, addrType), spending, attributes))

  /** The attribute map of an address or a transaction: numeric key to byte string. The reference
    * reads it with `decodeMapSkel` at `byronProtVer` (`Cardano.Chain.Common.Attributes`,
    * `Decoder.hs`): a definite map whose keys are `Word8` and strictly ascending (a repeated or
    * descending key is a canonicity violation) and whose values are definite byte strings.
    */
  private def attributeFields(
      n: Node,
      what: DecodeSite
  ): Either[LedgerDecodeError, Vector[(BigInt, Bytes)]] =
    ByronTransport
      .attributes(n, what)
      .flatMap(_.traverse((k, v) => CborRead.bytes(v, what).map(k -> _)))

  /** An address's attribute map as the reference encodes `Attributes AddrAttributes`
    * (`encCBORAttributes`, cardano-ledger f649f975, `Cardano/Chain/Common/Attributes.hs:192-196`,
    * and `AddrAttributes.hs:96-119`, at `byronProtVer`): a definite map with a minimal head
    * (`encodeMap`'s `exactMapLenEncoding` below version 2, cardano-ledger-binary 048b78d2,
    * `Encoding/Encoder.hs:397-430`), the keys in ascending order and minimal, each value a byte
    * string with a minimal length. The values are those read: the derivation path and the network
    * magic are read only in their canonical encodings, which `serialize` writes again, and the
    * other attributes are kept as bytes.
    */
  private def canonicalAttributes(fields: Vector[(BigInt, Bytes)]): Bytes =
    ByronCbor.concat(
      ByronCbor.mapHead(fields.size) +:
        fields.flatMap((k, v) => Vector(ByronCbor.uint(k), ByronCbor.bytes(v)))*
    )

  /** The total length of the values of attributes the ledger does not interpret. */
  private def unknownLength(fields: Vector[(BigInt, Bytes)], known: Set[Int]): Int =
    fields.filterNot((k, _) => known.contains(k.toInt)).map(_._2.size).sum

  /** Length of the attribute values of a transaction (none of its attributes is interpreted). */
  def attributesLength(n: Node, what: DecodeSite): Either[LedgerDecodeError, Int] =
    attributeFields(n, what).map(unknownLength(_, Set.empty))

  /** The Byron `Address` as the reference reads it at `byronProtVer` (`decodeFullDecoder`, version
    * 1: definite lengths only; cardano-ledger f649f975, `Cardano.Chain.Common.Address`,
    * `AddrAttributes`, `AddrSpendingData`, `Hashing`): `[#6.24(bytes .cbor [root, attributes,
    * type]), crc32]` with a 28-byte root, strictly ascending `Word8` attribute keys, a canonical
    * derivation path (attribute 1), a canonical `Word32` network magic (attribute 2), a canonical
    * `Word8` type that is 0 or 2, and no byte after the address. The reference keeps any width of
    * the integers and lengths of the envelope (the tag, the payload length, the checksum, the list,
    * map and key heads), and so does this reader. `limits` bound the decoding: a block's, by
    * default.
    */
  def decode(
      raw: Bytes,
      limits: Cbor.Limits = ByronCbor.BodyLimits
  ): Either[LedgerDecodeError, ByronAddress] =
    for
      root <- CborRead.decode(raw, limits)
      outer <- ByronTransport.tuple(root, 2, DecodeField.ByronAddress)
      payload <- outer(0).value match
        case Value.Tag(t, inner) if t == 24 =>
          ByronTransport.definite(inner, DecodeField.ByronAddressPayload) *>
            CborRead.bytes(inner, DecodeField.ByronAddressPayload)
        case _ => Left(LedgerDecodeError.ExpectedTag(DecodeField.ByronAddress, 24, outer(0).value))
      crc <- ByronTransport.word(outer(1), 32, DecodeField.ByronAddressCrc)
      _ <- Either.cond(
        crc == BigInt(ByronCbor.crc32(payload)),
        (),
        LedgerDecodeError.CrcMismatch(BigInt(ByronCbor.crc32(payload)), crc)
      )
      parts <- CborRead
        .decode(payload, limits)
        .flatMap(ByronTransport.tuple(_, 3, DecodeField.ByronAddressBody))
      addrRoot <- ByronTransport.definite(parts(0), DecodeField.ByronAddressRoot) *>
        CborRead.bytesN(parts(0), 28, DecodeField.ByronAddressRoot)
      fields <- attributeFields(parts(1), DecodeField.ByronAddressAttributes)
      magic <- fields.find(_._1 == 2).traverse { (_, v) =>
        CborRead
          .decode(v, limits)
          .flatMap(ByronTransport.canonicalWord(_, 32, DecodeField.NetworkMagic))
          .map(_.toLong)
      }
      // the derivation path is a byte string, itself the content of the attribute's byte string
      derivationPath <- fields.find(_._1 == 1).traverse { (_, v) =>
        CborRead
          .decode(v, limits)
          .flatMap(n =>
            ByronTransport.canonicalBytes(n, DecodeField.DerivationPath) *>
              CborRead.bytes(n, DecodeField.DerivationPath)
          )
          .map(_.size)
      }
      ty <- ByronTransport.canonicalWord(parts(2), 8, DecodeField.ByronAddressType).map(_.toInt)
      _ <- Either.cond(
        ty == VerKey || ty == Redeem,
        (),
        LedgerDecodeError.UnknownTag(DecodeField.ByronAddress, ty)
      )
    yield ByronAddress(
      raw,
      addrRoot,
      parts(1).original,
      canonicalAttributes(fields),
      ty,
      magic,
      unknownLength(fields, Set(1, 2)),
      derivationPath.getOrElse(0)
    )

  /** The address `raw` decodes to, encoded again as the reference encodes an `Address` (`encCBOR`
    * of `Address`, `encodeCrcProtected`, and of its `Attributes AddrAttributes`,
    * `Cardano.Chain.Common.Address` and `Cardano.Chain.Common.Attributes`, cardano-ledger
    * f649f975): every head and length minimal, the attributes in ascending key order with the
    * values they carried. A genesis balance's address is keyed and stored so (`UTxO.fromBalances`,
    * `Cardano/Chain/UTxO/UTxO.hs:121-131`), whatever widths its text used (#728). A genesis address
    * is in no block, and the reference's reader bounds it by nothing but its own length.
    */
  def reencoded(raw: Bytes): Either[LedgerDecodeError, Bytes] =
    val size = raw.size.max(1)
    val limits = Cbor.Limits(size, Int.MaxValue, size, size)
    decode(raw, limits).map { address =>
      val body = ByronCbor.concat(
        ByronCbor.of(0x83),
        ByronCbor.bytes(address.root),
        address.canonicalAttributes,
        ByronCbor.uint(address.addrType)
      )
      ByronCbor.concat(
        ByronCbor.of(0x82, 0xd8, 0x18),
        ByronCbor.bytes(body),
        ByronCbor.uint(ByronCbor.crc32(body))
      )
    }

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
