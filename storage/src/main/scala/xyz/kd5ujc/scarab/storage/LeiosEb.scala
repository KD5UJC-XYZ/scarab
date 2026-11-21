// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.data.NonEmptyVector
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, DomainError, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.{EbHash, LeiosTxHash}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word32

/** An endorser block's body: the hash and the size of each transaction it lists, in order
  * (`LeiosEb`, ouroboros-consensus 2416493b `Ouroboros/Consensus/Leios/Types.hs:209-213`). A body
  * lists at least one transaction: the reference refuses to store an empty one (`writeEbBody: empty
  * EB body`, `Storage/LeiosDB/Impl/SQLite/Insert.hs:129-130`), and its wire decoder refuses one
  * too. Every hash is 32 bytes, as the reference's `TxHash` (`PackedBytes 32`) is: a body is made
  * by [[LeiosEb.from]] or [[LeiosEb.decode]], which refuse another length, so that what is stored
  * reads back. It is not a case class: a case class's product mirror (`fromProduct`) would make one
  * without either.
  */
final class LeiosEb private (val transactions: NonEmptyVector[(LeiosTxHash, Word32)]):
  def size: Int = transactions.length

  override def equals(that: Any): Boolean = that match
    case other: LeiosEb => transactions == other.transactions
    case _              => false

  override def hashCode: Int = transactions.hashCode

  override def toString: String = s"LeiosEb(${transactions.toVector.mkString(", ")})"

  /** The declared size of the transaction at `offset`, if the body lists one there. */
  def declared(offset: Int): Option[Long] = transactions.get(offset).map(_._2.toLong)

  /** The body's encoding, as the reference stores and serves it (`encodeLeiosEb`,
    * `Types.hs:247-252` at 2416493b): a definite map of each transaction hash, a byte string, to
    * its size, an unsigned integer in its shortest form.
    */
  def encoded: Bytes = LeiosEb.encode(transactions.toVector)

  /** The length of [[encoded]], as the reference computes it without encoding (`encodeLeiosEbSize`,
    * `Types.hs:276-279` at 2416493b): the map's head and each transaction's [[LeiosEb.itemSize]].
    */
  def encodedSize: Long =
    LeiosEb.cborIntBytesSize(size.toLong) +
      transactions.foldLeft(0L)((sum, item) => sum + LeiosEb.itemSize(item._2.toLong))

  /** Where each transaction is in the closure: `n + 1` positions, each the sum of the sizes before
    * it (`encodeTxBounds`, `Storage/LeiosDB/Impl/SQLite/Read.hs:264-267` at 2416493b).
    */
  def bounds: Vector[Long] = transactions.toVector.scanLeft(0L)(_ + _._2.toLong)

  /** [[bounds]] as the reference stores them in `txBounds`, each a 32-bit big-endian word; none
    * when a bound does not fit one (`encodeTxBounds`, `Read.hs:264-267` at 2416493b).
    */
  def txBounds: Option[Bytes] =
    Option.when(bounds.forall(_ <= 0xffffffffL))(
      Bytes.wrap(bounds.toArray.flatMap(b => Array.tabulate(4)(i => (b >>> (8 * (3 - i))).toByte)))
    )

object LeiosEb:
  /** Why stored or imported body bytes are not an endorser block body. */
  enum Error extends DomainError:
    case Syntax(cause: Cbor.Error)
    case NotAMap
    case Empty

    /** Entry `index` is not a 32-byte hash mapped to a 32-bit size. */
    case BadEntry(index: Int)

  /** A body of these transactions, refused if a hash is not 32 bytes (`BadEntry` of its index): the
    * wire's hashes keep any length (`LeiosTxHash`), the reference's are 32 bytes.
    */
  def from(transactions: NonEmptyVector[(LeiosTxHash, Word32)]): Either[Error, LeiosEb] =
    transactions.toVector.indexWhere(_._1.bytes.size != HashBytes) match
      case -1    => Right(new LeiosEb(transactions))
      case index => Left(Error.BadEntry(index))

  /** The body of the transactions `txs`, each as the bytes the closure holds, in order: the body
    * part of `forgeLeiosEb` (`LeiosDemoTypes.hs:1273-1295` at 2416493b), each transaction's hash
    * ([[hashTx]]) and its length. The hashes are Blake2b-256 digests, 32 bytes, so the body is one
    * [[from]] accepts; a length is a JVM array's, which a `Word32` holds.
    */
  def of(txs: NonEmptyVector[Bytes]): LeiosEb =
    new LeiosEb(txs.map(tx => hashTx(tx) -> Word32.wrap(tx.size.toLong)))

  /** `encodeLeiosEbItemSize` (`Types.hs:229-232` at 2416493b): the bytes one reference takes in a
    * body, for a transaction of `txSize` bytes: the 32-byte hash under its head, and the size.
    */
  def itemSize(txSize: Long): Long =
    cborIntBytesSize(HashBytes.toLong) + HashBytes + cborIntBytesSize(txSize)

  /** `encodeLeiosEbMaxFramingSize` (`Types.hs:263-264` at 2416493b): the widest map head a body can
    * have, that of a count as wide as a `Word32`.
    */
  val MaxFramingSize: Long = cborIntBytesSize(0xffffffffL)

  /** `leiosReferencesCapacity` (`Types.hs:266-271` at 2416493b): the bytes of references a body may
    * hold under a `maxEndorserBlockReferencesSize` of `parameter`, the widest head taken off; a
    * parameter smaller than the head leaves none.
    */
  def referencesCapacity(parameter: Long): Long = parameter - parameter.min(MaxFramingSize)

  /** `cborIntBytesSize` (`Types.hs:283-288` at 2416493b): the length of the head of an unsigned
    * integer `n` in its shortest form, at most that of a `Word32`, as the reference counts it.
    */
  def cborIntBytesSize(n: Long): Long =
    if n < 24 then 1
    else if n < 0x100L then 2
    else if n < 0x10000L then 3
    else 5

  /** The bytes of `hash` 's body, read back: the decoder of the reference's stored bodies
    * (`decodeStoredTxHashes`, `Read.hs:186-198` at 2416493b), which reads 32-byte hashes and 32-bit
    * sizes.
    */
  def decode(bytes: Bytes): Either[Error, LeiosEb] =
    Cbor.decode(bytes, Cbor.Limits.Unbounded).leftMap(Error.Syntax(_)).flatMap { node =>
      node.value match
        case Value.Map(entries) =>
          entries.zipWithIndex
            .traverse { case ((key, value), index) =>
              (key.value, value.value) match
                case (Value.ByteString(hash), Value.UInt(size)) if hash.size == HashBytes =>
                  Word32
                    .from(size)
                    .leftMap(_ => Error.BadEntry(index))
                    .map(LeiosTxHash.fromBytes(hash) -> _)
                case _ => Left(Error.BadEntry(index))
            }
            .flatMap(NonEmptyVector.fromVector(_).toRight(Error.Empty))
            .flatMap(from)
        case _ => Left(Error.NotAMap)
    }

  /** The encoding of a body that lists these transactions, whatever their number and the lengths of
    * their hashes (`encodeLeiosEb`, `Types.hs:247-252` at 2416493b): a definite map of each hash, a
    * byte string, to its size, an unsigned integer in its shortest form.
    */
  def encode(transactions: Vector[(LeiosTxHash, Word32)]): Bytes =
    val parts = transactions.flatMap { case (hash, bytes) =>
      Vector(head(2, hash.bytes.size.toLong), hash.bytes.toArray, head(0, bytes.toLong))
    }
    Bytes.wrap((head(5, transactions.size.toLong) +: parts).toArray.flatten)

  /** The endorser-block hash of a body that lists these transactions: the Blake2b-256 of its
    * encoding (`hashLeiosEb`, `LeiosDemoTypes.hs:1297-1299` at 2416493b).
    */
  def hashOf(transactions: Vector[(LeiosTxHash, Word32)]): EbHash =
    EbHash.fromBytes(Blake2b.hash256.hash(encode(transactions)))

  /** The length of an endorser-block hash and of a transaction hash (`PackedBytes 32`). */
  val HashBytes: Int = 32

  /** A transaction's hash as an endorser block lists it (`hashTxBytes`, `Types.hs:162-163` at
    * 2416493b).
    */
  def hashTx(tx: Bytes): LeiosTxHash = LeiosTxHash.fromBytes(Blake2b.hash256.hash(tx))

  /** Whether the bytes of a closure's transaction were written: unwritten transactions are zeroes,
    * and no transaction's CBOR starts with a zero byte (`txIsWritten`,
    * `Storage/LeiosDB/Impl/SQLite/Completion.hs:32-35` at 2416493b).
    */
  def written(tx: Bytes): Boolean = tx.headOption.exists(_ != 0)

  /** The head of a CBOR item of major type `major` and argument `n`, in its shortest form. */
  private def head(major: Int, n: Long): Array[Byte] =
    def be(width: Int): Array[Byte] =
      Array.tabulate(width)(i => (n >>> (8 * (width - 1 - i))).toByte)
    val m = major << 5
    if n < 24 then Array((m | n.toInt).toByte)
    else if n < 0x100L then (m | 24).toByte +: be(1)
    else if n < 0x10000L then (m | 25).toByte +: be(2)
    else if n < 0x100000000L then (m | 26).toByte +: be(4)
    else (m | 27).toByte +: be(8)
