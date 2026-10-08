// SPDX-License-Identifier: Apache-2.0
package lab.chain

import java.security.MessageDigest
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}

/** Offline structural indexing of original disk-era CBOR, not header/ledger validation. Accepts
  * disk tags 2–7 (Shelley through Conway), not NtN headers, NtC tag-24 wrappers, or BlockFetch
  * messages. Hashes the original header bytes, never a re-encoding.
  */
object CardanoBlockIndex:
  /** One shared whitelist for indexing and persisted records; no era is inferred from ProtVer. */
  enum Era(val diskTag: Int, val label: String, val blockArity: Int, val praos: Boolean):
    case Shelley extends Era(2, "shelley", 4, false)
    case Allegra extends Era(3, "allegra", 4, false)
    case Mary extends Era(4, "mary", 4, false)
    case Alonzo extends Era(5, "alonzo", 5, false)
    case Babbage extends Era(6, "babbage", 5, true)
    case Conway extends Era(7, "conway", 5, true)
  val supportedEraLabels: Set[String] = Era.values.map(_.label).toSet

  final class IndexedBlock private[CardanoBlockIndex] (
      val rawBytes: Bytes,
      val rawSha256: Bytes,
      val headerBytes: Bytes,
      val headerOffset: Int,
      val headerLength: Int,
      val headerHash: Bytes,
      val parentHash: Bytes,
      val slot: BigInt,
      val blockNo: BigInt,
      val era: String
  )

  /** One validated layout shared by structural indexing and optional pure observations. The
    * constructor stays here: callers cannot supply a forged parsed tree. The component byte values
    * are already-owned immutable Node originals, not copied or re-encoded.
    */
  private[chain] final class ParsedBlock private[CardanoBlockIndex] (
      val indexed: IndexedBlock,
      val era: Era,
      val declaredBodySize: BigInt,
      val declaredBodyHash: Bytes,
      val headerNode: Node,
      val components: Vector[Bytes]
  )

  /** Local hard ceilings bound the allocation cost of the byte-preserving CBOR tree. */
  final case class Limits(
      maxBytes: Int = 1048576,
      maxDepth: Int = 32,
      maxItems: Int = 100000,
      maxStringBytes: Int = 1048576
  ):
    private[CardanoBlockIndex] def valid: Boolean =
      maxBytes > 0 && maxBytes <= 1048576 && maxDepth >= 0 && maxDepth <= 32 &&
        maxItems > 0 && maxItems <= 100000 && maxStringBytes >= 0 && maxStringBytes <= 1048576

  private final case class Invalid(message: String) extends RuntimeException(message)
  private def fail(message: String): Nothing = throw Invalid(message)
  private def array(node: Node, size: Int, label: String): Vector[Node] = node.value match
    case Value.Arr(items) if items.size == size => items
    case _                                      => fail(s"$label must be a $size-element array")
  private def list(node: Node, label: String): Vector[Node] = node.value match
    case Value.Arr(items) => items
    case _                => fail(s"$label must be an array")
  private def map(node: Node, label: String): Vector[(Node, Node)] = node.value match
    case Value.Map(items) => items
    case _                => fail(s"$label must be a map")
  private def uint(node: Node, label: String): BigInt = node.value match
    case Value.UInt(value) if value >= 0 && value <= ((BigInt(1) << 64) - 1) => value
    case _ => fail(s"$label must be uint64")
  private def bytes(node: Node, size: Int, label: String): Bytes = node.value match
    case Value.ByteString(value) if value.size == size => value
    case _ => fail(s"$label must be a $size-byte string")
  private def vrf(node: Node, label: String, evidenceProfile: Boolean): Unit =
    val fields = array(node, 2, label)
    if evidenceProfile then
      fields(0).value match
        case Value.ByteString(value) if value.size != 64 =>
          fail("unsupported evidence profile: VRF output must contain 64 bytes")
        case _ => ()
    bytes(fields(0), 64, s"$label output")
    bytes(fields(1), 80, s"$label proof")
    ()
  // Container head widths include non-shortest definite lengths and indefinite arrays.
  private def headWidth(node: Node): Int = (node.original.value.head & 31) match
    case 24 => 2
    case 25 => 3
    case 26 => 5
    case 27 => 9
    case _  => 1

  def inspect(raw: Bytes, maxBytes: Int): Either[String, IndexedBlock] =
    inspect(raw, Limits(maxBytes = maxBytes))

  def inspect(raw: Bytes, limits: Limits = Limits()): Either[String, IndexedBlock] =
    parse(raw, limits).map(_.indexed)

  private[chain] def parse(raw: Bytes, limits: Limits): Either[String, ParsedBlock] =
    parseProfile(raw, limits, false)

  // Same acceptance and owned tree; profile-specific VRF width and envelope diagnostics differ.
  private[chain] def parseForEvidence(raw: Bytes, limits: Limits): Either[String, ParsedBlock] =
    parseProfile(raw, limits, true)

  private def parseProfile(
      raw: Bytes,
      limits: Limits,
      evidenceProfile: Boolean
  ): Either[String, ParsedBlock] =
    if !limits.valid then Left("invalid block index limits (hard maximum input is 1048576 bytes)")
    else
      Cbor
        .decode(
          raw,
          Cbor.Limits(limits.maxBytes, limits.maxDepth, limits.maxItems, limits.maxStringBytes)
        )
        .flatMap { root =>
          try
            val envelope = array(root, 2, "disk-era block envelope")
            val tag = uint(envelope(0), "disk era")
            val era = Era.values
              .find(e => BigInt(e.diskTag) == tag)
              .getOrElse(
                fail(
                  "unsupported disk era: only post-Byron Shelley (2) through Conway (7) are indexed"
                )
              )
            if evidenceProfile then
              envelope(1).value match
                case Value.Arr(_) => ()
                case _ =>
                  fail(
                    "unsupported evidence profile: expected a raw disk block array, not a wire wrapper"
                  )
            val block = array(envelope(1), era.blockArity, s"${era.label} block")
            val header = array(block(0), 2, "header")
            val body = array(header(0), if era.praos then 10 else 15, "header body")
            val blockNo = uint(body(0), "block number")
            val slot = uint(body(1), "slot")
            if body(2).value == Value.Null then
              fail("null parent is unsupported: this index requires a concrete 32-byte parent hash")
            val parent = bytes(body(2), 32, "parent hash")
            bytes(body(3), 32, "issuer verification key")
            bytes(body(4), 32, "VRF verification key")
            // All numeric fields use this bounded uint64 structural ceiling. This is not
            // reference Word32/Version width parity or protocol-version acceptance.
            if era.praos then
              vrf(body(5), "VRF certificate", evidenceProfile)
              uint(body(6), "body size")
              bytes(body(7), 32, "body hash")
              val cert = array(body(8), 4, "operational certificate")
              bytes(cert(0), 32, "operational certificate hot key")
              uint(cert(1), "operational certificate sequence")
              uint(cert(2), "KES period")
              bytes(cert(3), 64, "operational certificate signature")
              val version = array(body(9), 2, "protocol version")
              uint(version(0), "protocol major")
              uint(version(1), "protocol minor")
            else
              vrf(body(5), "nonce VRF certificate", evidenceProfile)
              vrf(body(6), "leader VRF certificate", evidenceProfile)
              uint(body(7), "body size")
              bytes(body(8), 32, "body hash")
              bytes(body(9), 32, "operational certificate hot key")
              uint(body(10), "operational certificate sequence")
              uint(body(11), "KES period")
              bytes(body(12), 64, "operational certificate signature")
              uint(body(13), "protocol major")
              uint(body(14), "protocol minor")
            bytes(header(1), 448, "KES signature")
            // Only container shape is checked here. Transaction keys, values, correspondence,
            // auxiliary-data schemas, commitments and every ledger rule remain unchecked.
            list(block(1), "transaction bodies").foreach(n => { map(n, "transaction body"); () })
            list(block(2), "transaction witnesses").foreach(n => {
              map(n, "transaction witness"); ()
            })
            map(block(3), "auxiliary data").foreach { case (key, _) =>
              uint(key, "auxiliary data index"); ()
            }
            if era.blockArity == 5 then
              list(block(4), "invalid transaction indices").foreach { n =>
                uint(n, "invalid transaction index"); ()
              }
            val originalHeader = block(0).original
            val offset = headWidth(root) + envelope(0).original.size + headWidth(envelope(1))
            val indexed = new IndexedBlock(
              raw,
              Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(raw.toArray)),
              originalHeader,
              offset,
              originalHeader.size,
              Blake2b.hash256.hash(originalHeader),
              parent,
              slot,
              blockNo,
              era.label
            )
            Right(
              new ParsedBlock(
                indexed,
                era,
                uint(body(if era.praos then 6 else 7), "body size"),
                bytes(body(if era.praos then 7 else 8), 32, "body hash"),
                block(0),
                block.drop(1).map(_.original)
              )
            )
          catch case Invalid(message) => Left(message)
        }
