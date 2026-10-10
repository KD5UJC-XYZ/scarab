// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.syntax.all.*
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}

/** A Conway block as fetched over node-to-node BlockFetch (`[era, block]`). Every component keeps
  * its original encoding: hashes and ledger rules are computed over these bytes, never over a
  * re-encoding.
  */
final case class Block(
    header: Header,
    transactions: Vector[Block.Transaction],
    bodySize: Long,
    bodyHash: Bytes
)

object Block:
  /** One transaction: original body/witness/auxiliary-data bytes and phase-2 validity flag. */
  final case class Transaction(
      body: Bytes,
      witnesses: Bytes,
      auxiliaryData: Option[Bytes],
      isValid: Boolean
  ):
    lazy val id: Bytes = Blake2b.hash256.hash(body)

  /** Disk/BlockFetch era tag for Conway blocks. */
  val ConwayTag: BigInt = 7

  private val limits = Cbor.Limits(
    maxInputBytes = 2500000,
    maxDepth = 64,
    maxItems = 1000000,
    maxStringBytes = 2500000
  )

  def decode(raw: Bytes): Either[ChainError, Block] =
    for
      root <- Cbor.decode(raw, limits).leftMap(fail)
      outer <- array(root, 2)
      tag <- outer(0).value match
        case Value.UInt(t) => Right(t)
        case _             => Left(fail("expected era tag"))
      _ <- Either.cond(tag == ConwayTag, (), fail(s"era tag $tag is not Conway"))
      parts <- array(outer(1), 5)
      header <- Header.fromRaw(parts(0).original)
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
      invalid <- list(parts(4)).flatMap(_.traverse(index(_, bodies.size))).map(_.toSet)
      components = Vector(parts(1), parts(2), parts(3), parts(4)).map(_.original)
    yield Block(
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
