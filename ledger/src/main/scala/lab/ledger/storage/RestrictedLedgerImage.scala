// SPDX-License-Identifier: Apache-2.0
package lab.ledger.storage

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import java.nio.charset.StandardCharsets.US_ASCII
import java.security.MessageDigest
import lab.cbor.Bytes
import lab.ledger.PlutusOutput
import lab.plutus.PlutusExecution
import scala.util.control.NonFatal

/** Pure wire codec for the ledger/provenance slice, NOT a complete validator checkpoint. Expected
  * bindings must come from an independently authorized publication, never this image. Hashes detect
  * corruption; neither hashes nor decoding confer source or restore authority.
  */
object RestrictedLedgerImage:
  val Version = 1
  val MaxBytes = 3 * 1024 * 1024
  val MaxOutputBytes = 1024 * 1024
  val SourceLimits: Vector[(String, Int)] = Vector(
    "genesis" -> (1024 * 1024),
    "parameters" -> 262144,
    "manifest" -> 65536
  )
  private val Magic = Bytes.fromArray("RSLIMG01".getBytes(US_ASCII))
  private val MaxWord = (BigInt(1) << 64) - 1
  private val MaxFees = (BigInt(1) << 128) - 1
  final case class Point(slot: BigInt, blockNo: BigInt, hash: Bytes)
  final case class Binding(
      profile: String,
      networkMagic: BigInt,
      epoch: BigInt,
      point: Point,
      environmentId: Bytes,
      sourcePins: Map[String, Bytes]
  )
  final class UntrustedImage private[RestrictedLedgerImage] (
      val original: Bytes,
      val binding: Binding,
      val sources: Map[String, Bytes],
      val originalUtxo: Bytes,
      val fees: BigInt,
      val snapshot: PlutusOutput.Snapshot
  ):
    val imageSHA256: Bytes = sha(original)
    val utxoSHA256: Bytes = sha(originalUtxo)
    val completeValidatorState = false
    val restoreAuthorized = false
    val fullLedgerValidated = false

  private def sha(raw: Bytes): Bytes =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(raw.toArray))
  private def hash(b: Bytes): Boolean = b != null && b.value != null && b.size == 32
  private def word(n: BigInt): Boolean = n != null && n >= 0 && n <= MaxWord
  private def validate(b: Binding): Unit =
    require(b != null && b.profile == PlutusExecution.ProfileId, "unsupported image profile")
    require(
      word(b.networkMagic) && b.networkMagic > 0 && b.networkMagic <= 0xffffffffL &&
        !Set(BigInt(1), BigInt(2), BigInt(764824073)).contains(b.networkMagic),
      "private network magic required"
    )
    require(b.epoch == 0, "only restricted epoch-zero image supported")
    require(
      b.point != null && word(b.point.slot) && b.point.slot > 0 && b.point.slot < 1000 &&
        word(b.point.blockNo) && hash(b.point.hash) && hash(b.environmentId),
      "bounded full point/environment required"
    )
    require(
      b.sourcePins != null && b.sourcePins.keySet == SourceLimits.map(_._1).toSet &&
        b.sourcePins.values.forall(hash),
      "exact source pin domain required"
    )
  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
  private def bounded(raw: Bytes, maximum: Int): Boolean =
    raw != null && raw.value != null && raw.size > 0 && raw.size <= maximum
  private def snapshot(raw: Bytes): PlutusOutput.Snapshot =
    PlutusOutput.snapshot(raw, 0).fold(e => throw new IllegalArgumentException(e), identity)

  /** Serializes supplied facts only; it cannot certify that they were read from one runtime cell.
    */
  def encode(
      binding: Binding,
      sources: Map[String, Bytes],
      originalUtxo: Bytes,
      fees: BigInt
  ): Either[String, Bytes] = checked {
    validate(binding)
    require(sources != null && sources.keySet == binding.sourcePins.keySet, "exact source domain")
    SourceLimits.foreach { (name, maximum) =>
      require(bounded(sources(name), maximum), "source byte bound: " + name)
      require(sha(sources(name)) == binding.sourcePins(name), "source pin mismatch: " + name)
    }
    require(bounded(originalUtxo, MaxOutputBytes), "UTxO byte bound")
    snapshot(originalUtxo)
    require(fees != null && fees >= 0 && fees <= MaxFees, "uint128 fee pot required")
    val buffer = new ByteArrayOutputStream()
    val out = new DataOutputStream(buffer)
    def bytes(raw: Bytes): Unit = out.write(raw.toArray)
    def blob(raw: Bytes): Unit = { out.writeInt(raw.size); bytes(raw) }
    def uint(value: BigInt, width: Int): Unit =
      val a = value.toByteArray.dropWhile(_ == 0)
      out.write(Array.fill[Byte](width - a.length)(0)); out.write(a)
    bytes(Magic)
    out.writeInt(Version)
    blob(Bytes.fromArray(binding.profile.getBytes(US_ASCII)))
    uint(binding.networkMagic, 8); uint(binding.epoch, 8)
    uint(binding.point.slot, 8); uint(binding.point.blockNo, 8)
    bytes(binding.point.hash); bytes(binding.environmentId)
    SourceLimits.foreach { (name, _) =>
      bytes(binding.sourcePins(name)); blob(sources(name))
    }
    uint(fees, 16)
    blob(originalUtxo)
    out.flush()
    val payload = Bytes.fromArray(buffer.toByteArray)
    require(payload.size + 32 <= MaxBytes, "aggregate image byte bound")
    Bytes(payload.value ++ sha(payload).value)
  }

  /** All-or-nothing bounded parsing. No state, owner, undo receipt or historical ID is hydrated. */
  def decode(original: Bytes, expected: Binding): Either[String, UntrustedImage] = checked {
    validate(expected)
    require(bounded(original, MaxBytes) && original.size >= 44, "image byte bound")
    val payload = Bytes(original.value.dropRight(32))
    require(sha(payload) == Bytes(original.value.takeRight(32)), "image checksum mismatch")
    val in = new DataInputStream(new ByteArrayInputStream(payload.toArray))
    def bytes(count: Int): Bytes =
      require(count >= 0 && count <= in.available(), "truncated image field")
      Bytes.fromArray(in.readNBytes(count))
    def blob(maximum: Int): Bytes =
      val size = in.readInt()
      require(size > 0 && size <= maximum, "bounded field length required")
      bytes(size)
    def uint(width: Int): BigInt = BigInt(1, bytes(width).toArray)
    require(bytes(8) == Magic, "image magic mismatch")
    require(in.readInt() == Version, "unsupported image version")
    val profile = new String(blob(96).toArray, US_ASCII)
    val magic = uint(8); val epoch = uint(8)
    val point = Point(uint(8), uint(8), bytes(32))
    val environment = bytes(32)
    val rows = SourceLimits.map { (name, limit) =>
      val pin = bytes(32)
      val raw = blob(limit)
      require(sha(raw) == pin, "source checksum mismatch: " + name)
      (name, pin, raw)
    }
    val binding =
      Binding(profile, magic, epoch, point, environment, rows.map((n, p, _) => n -> p).toMap)
    validate(binding)
    require(binding == expected, "independent expected binding mismatch")
    val fees = uint(16)
    val utxo = blob(MaxOutputBytes)
    require(in.available() == 0, "trailing image payload")
    val state = snapshot(utxo)
    new UntrustedImage(original, binding, rows.map((n, _, b) => n -> b).toMap, utxo, fees, state)
  }
