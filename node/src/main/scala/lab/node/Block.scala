// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.syntax.all.*
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.network.Era

/** A block as fetched over node-to-node BlockFetch (`[tag, block]`). Every component keeps its
  * original encoding: hashes and ledger rules are computed over these bytes, never over a
  * re-encoding.
  */
sealed trait Block:
  def era: Era
  def header: Header

object Block:
  /** Shelley..Conway block: header, transactions and the body commitment recomputed from the
    * original bytes. `bodySize` /`bodyHash` are what the header's `block_body_size`
    * /`block_body_hash` must equal.
    */
  final case class Shelley(
      era: Era,
      header: Header.Shelley,
      transactions: Vector[Transaction],
      bodySize: Long,
      bodyHash: Bytes
  ) extends Block

  /** Byron main or epoch-boundary block: only the header is decoded, the body stays raw. */
  final case class Byron(header: Header.Byron, body: Bytes) extends Block:
    def era: Era = Era.Byron

  /** One transaction: original body/witness/auxiliary-data bytes and phase-2 validity flag. */
  final case class Transaction(
      body: Bytes,
      witnesses: Bytes,
      auxiliaryData: Option[Bytes],
      isValid: Boolean
  ):
    lazy val id: Bytes = Blake2b.hash256.hash(body)

  private val limits = Cbor.Limits(
    maxInputBytes = 2500000,
    maxDepth = 64,
    maxItems = 1000000,
    maxStringBytes = 2500000
  )

  def decode(
      raw: Bytes,
      byronEpochSlots: BigInt = Header.DefaultByronEpochSlots
  ): Either[ChainError, Block] =
    for
      root <- Cbor.decode(raw, limits).leftMap(fail)
      outer <- array(root, 2)
      tag <- outer(0).value match
        case Value.UInt(t) => Right(t)
        case _             => Left(fail("expected era tag"))
      era <- Era.fromBlockTag(tag).toRight(fail(s"unknown era tag $tag"))
      block <-
        if era == Era.Byron then byron(tag == 0, outer(1), byronEpochSlots)
        else shelley(era, outer(1))
    yield block

  private def byron(
      boundary: Boolean,
      n: Node,
      epochSlots: BigInt
  ): Either[ChainError, Block] =
    for
      parts <- array(n, 3)
      header <- Header.byronFromRaw(parts(0).original, boundary, epochSlots)
    yield Byron(header, Bytes(parts.tail.flatMap(_.original.value)))

  private def shelley(era: Era, n: Node): Either[ChainError, Block] =
    for
      parts <- array(n, if era.hasInvalidTransactions then 5 else 4)
      header <- Header.fromRaw(era, parts(0).original)
      bodies <- list(parts(1))
      witnesses <- list(parts(2))
      _ <- Either.cond(
        bodies.size == witnesses.size,
        (),
        fail("transaction body/witness counts differ")
      )
      aux <- parts(3).value match
        case Value.Map(entries) =>
          entries
            .traverse { case (k, v) => index(k, bodies.size).map(_ -> v.original) }
            .map(_.toMap)
        case _ => Left(fail("auxiliary data must be a map"))
      invalid <-
        if era.hasInvalidTransactions then
          list(parts(4)).flatMap(_.traverse(index(_, bodies.size))).map(_.toSet)
        else Right(Set.empty[Int])
      components = parts.drop(1).map(_.original)
    yield Shelley(
      era,
      header,
      bodies.indices.toVector.map(i =>
        Transaction(bodies(i).original, witnesses(i).original, aux.get(i), !invalid.contains(i))
      ),
      components.map(_.size.toLong).sum,
      Blake2b.hash256.hash(Bytes(components.flatMap(c => Blake2b.hash256.hash(c).value)))
    )

  private def fail(reason: String): ChainError = ChainError.Decode("block", reason)
  private def array(n: Node, size: Int): Either[ChainError, Vector[Node]] = n.value match
    case Value.Arr(xs) if xs.size == size => Right(xs)
    case _                                => Left(fail(s"expected $size-element array"))
  private def list(n: Node): Either[ChainError, Vector[Node]] = n.value match
    case Value.Arr(xs) => Right(xs)
    case _             => Left(fail("expected array"))
  private def index(n: Node, bound: Int): Either[ChainError, Int] = n.value match
    case Value.UInt(i) if i < bound => Right(i.toInt)
    case _                          => Left(fail("transaction index out of range"))
