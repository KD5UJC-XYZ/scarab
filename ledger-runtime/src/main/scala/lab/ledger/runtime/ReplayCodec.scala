// SPDX-License-Identifier: Apache-2.0
package lab.ledger.runtime

import java.io.{
  ByteArrayInputStream,
  ByteArrayOutputStream,
  DataInputStream,
  DataOutputStream,
  EOFException
}
import java.nio.charset.StandardCharsets.US_ASCII
import java.security.MessageDigest
import lab.cbor.Bytes
import lab.ledger.RestrictedReplay as R
import ReplayStore.*

/** Strict bounded binary framing. All persisted claims are checked against pure replay on reopen.
  */
private[runtime] object ReplayCodec:
  val MaxObjectBytes = 10 * 1048576
  val MaxHeadBytes = 128
  private val SeedMagic = "cardano-lab:replay-seed:v1\n"
  private val OperationMagic = "cardano-lab:replay-operation:v1\n"
  private val HeadMagic = "cardano-lab:replay-head:v1\n"
  final case class Head(seed: Bytes, tip: Bytes, revision: BigInt, count: Int)
  enum Operation:
    case Apply(transactions: Vector[Bytes])
    case Undo(transition: Bytes)
  final case class Record(
      seed: Bytes,
      parent: Bytes,
      operation: Operation,
      revision: BigInt,
      stateId: Bytes,
      fees: BigInt,
      outputMap: Bytes,
      top: Option[Bytes]
  )
  def corrupt(reason: String): Nothing = throw new StorageException(StorageFailure.Corrupt, reason)
  def sha(bytes: Array[Byte]): Bytes =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(bytes))
  private def encoded(body: DataOutputStream => Unit): Array[Byte] =
    val buffer = new ByteArrayOutputStream()
    val out = new DataOutputStream(buffer)
    body(out)
    out.flush()
    val result = buffer.toByteArray
    if result.length > MaxObjectBytes then corrupt("encoded object exceeds hard bound")
    result
  private def fixed(out: DataOutputStream, raw: Bytes, size: Int): Unit =
    require(raw != null && raw.value != null && raw.size == size, "fixed field length")
    out.write(raw.toArray)
  private def blob(out: DataOutputStream, raw: Bytes): Unit =
    out.writeInt(raw.size)
    out.write(raw.toArray)
  private def uint(out: DataOutputStream, value: BigInt, size: Int): Unit =
    require(value >= 0 && value.bitLength <= size * 8, "unsigned integer bound")
    val raw = value.toByteArray.takeRight(size)
    out.write(new Array[Byte](size - raw.length))
    out.write(raw)
  private def magic(out: DataOutputStream, value: String): Unit =
    out.write(value.getBytes(US_ASCII))
  private final class Reader(raw: Array[Byte]):
    private val in = new DataInputStream(new ByteArrayInputStream(raw))
    def fixed(size: Int): Bytes =
      if size < 0 || size > in.available() then corrupt("truncated object field")
      val bytes = new Array[Byte](size)
      in.readFully(bytes)
      Bytes.fromArray(bytes)
    def blob(maximum: Int): Bytes =
      val size = in.readInt()
      if size < 0 || size > maximum then corrupt("object field length bound")
      fixed(size)
    def uint(size: Int): BigInt = BigInt(1, fixed(size).toArray)
    def byte(): Int = in.readUnsignedByte()
    def count(maximum: Int): Int =
      val n = in.readInt()
      if n < 0 || n > maximum then corrupt("object count bound")
      n
    def magic(value: String): Unit =
      if fixed(value.length) != Bytes.fromArray(value.getBytes(US_ASCII)) then
        corrupt("object format")
    def done(): Unit = if in.available() != 0 then corrupt("trailing object bytes")
  private def decoded[A](raw: Array[Byte])(body: Reader => A): A =
    if raw.length > MaxObjectBytes then corrupt("object byte bound")
    try
      val in = new Reader(raw)
      val result = body(in)
      in.done()
      result
    catch case _: EOFException => corrupt("truncated object")
  def seed(checkpoint: Checkpoint): Array[Byte] = encoded { out =>
    magic(out, SeedMagic)
    fixed(out, R.ProfileHash, 32)
    blob(out, checkpoint.parameters)
    blob(out, checkpoint.originalUtxo)
    fixed(out, checkpoint.attributionDigest, 32)
  }
  def readSeed(raw: Array[Byte]): Checkpoint = decoded(raw) { in =>
    in.magic(SeedMagic)
    if in.fixed(32) != R.ProfileHash then corrupt("unsupported pure profile identity")
    Checkpoint(in.blob(R.MaxStateBytes), in.blob(R.MaxStateBytes), in.fixed(32))
  }
  def head(value: Head): Array[Byte] = encoded { out =>
    magic(out, HeadMagic)
    fixed(out, value.seed, 32)
    fixed(out, value.tip, 32)
    uint(out, value.revision, 8)
    out.writeInt(value.count)
  }
  def readHead(raw: Array[Byte]): Head = decoded(raw) { in =>
    in.magic(HeadMagic)
    Head(in.fixed(32), in.fixed(32), in.uint(8), in.count(128))
  }
  def record(value: Record): Array[Byte] = encoded { out =>
    magic(out, OperationMagic)
    fixed(out, value.seed, 32)
    fixed(out, value.parent, 32)
    value.operation match
      case Operation.Apply(transactions) =>
        out.writeByte(1)
        out.writeInt(transactions.size)
        transactions.foreach(blob(out, _))
      case Operation.Undo(transition) =>
        out.writeByte(2)
        fixed(out, transition, 32)
    uint(out, value.revision, 8)
    fixed(out, value.stateId, 32)
    uint(out, value.fees, 16)
    blob(out, value.outputMap)
    out.writeBoolean(value.top.nonEmpty)
    value.top.foreach(fixed(out, _, 32))
  }
  def readRecord(raw: Array[Byte]): Record = decoded(raw) { in =>
    in.magic(OperationMagic)
    val seed = in.fixed(32)
    val parent = in.fixed(32)
    val operation = in.byte() match
      case 1 =>
        val count = in.count(R.MaxBatchTransactions)
        if count == 0 then corrupt("empty recorded apply")
        var total = 0L
        val transactions = Vector.fill(count) {
          val tx = in.blob(R.MaxTransactionBytes)
          total += tx.size
          if total > R.MaxBatchBytes then corrupt("recorded batch byte bound")
          tx
        }
        Operation.Apply(transactions)
      case 2 => Operation.Undo(in.fixed(32))
      case _ => corrupt("unknown operation kind")
    val revision = in.uint(8)
    val stateId = in.fixed(32)
    val fees = in.uint(16)
    val outputMap = in.blob(R.MaxStateBytes)
    val top = in.byte() match
      case 0 => None
      case 1 => Some(in.fixed(32))
      case _ => corrupt("noncanonical optional transition")
    Record(seed, parent, operation, revision, stateId, fees, outputMap, top)
  }
  def evidence(delta: R.BatchDelta): Long = delta.transactions.iterator.map { tx =>
    tx.originalTransaction.size.toLong + 256L +
      tx.spent.iterator.map((_, out) => out.original.size.toLong + 38L).sum +
      tx.created.iterator.map((_, out) => out.original.size.toLong + 38L).sum
  }.sum
