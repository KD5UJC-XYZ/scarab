// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.genesis

import cats.Monad
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, DomainError, Node, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{ChainError, Header}
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era, NtNHeader}

import UpstreamGenesisCases.Scenario

/** A lossless point bijection from native TestBlock's variable-width reverse ancestry hash to real
  * CBOR headers. The native mock ledger has no transactions or cryptographic predicates; original
  * identity is committed in bodyHash, with identical VRF selection values on all forks.
  */
object UpstreamGenesisBlocks:
  enum Problem extends DomainError:
    case Encoding(cause: Cbor.Error)
    case HeaderDecode(cause: ChainError)
    case MissingParent(point: ChainSync.Point)
    case MissingPoint(point: ChainSync.Point)
    case IdentityCollision
    case BlockEstimate(actual: BigInt)

  final case class Block(native: ChainSync.Point.Block, header: Header.Shelley, body: Bytes):
    def wire: NtNHeader = NtNHeader(header.era, header.raw, None)

  final case class Universe(blocks: Vector[Block]):
    val native: Map[ChainSync.Point, Block] =
      blocks.map(b => (b.native: ChainSync.Point) -> b).toMap
    val encoded: Map[ChainSync.Point, Block] = blocks.map(b => b.header.point -> b).toMap
    def encode(point: ChainSync.Point): Either[Problem, ChainSync.Point] = point match
      case ChainSync.Point.Origin => Right(ChainSync.Point.Origin)
      case _ => native.get(point).map(_.header.point).toRight(Problem.MissingPoint(point))
    def decode(point: ChainSync.Point): Either[Problem, ChainSync.Point] = point match
      case ChainSync.Point.Origin => Right(ChainSync.Point.Origin)
      case _ => encoded.get(point).map(_.native).toRight(Problem.MissingPoint(point))
    def chain(point: ChainSync.Point): Vector[Block] = point match
      case ChainSync.Point.Origin => Vector.empty
      case ChainSync.Point.Block(_, hash) =>
        blocks
          .filter(b => hash.value.takeRight(b.native.hash.size) == b.native.hash.value)
          .sortBy(_.native.hash.size)

  private def node(value: Value): Node = Node(value, Bytes.empty)
  private def arr(values: Value*): Value = Value.Arr(values.toVector.map(node))
  private def bytes(size: Int): Value = Value.ByteString(Bytes(Vector.fill(size)(0.toByte)))

  private type Result[A] = Either[Problem, A]
  private def encode(value: Value): Result[Bytes] = Cbor.encode(value).leftMap(Problem.Encoding(_))

  /** The reference TestBlock has opaque payloads and a mock ledger, not a Cardano BBODY rule. Embed
    * its identity in legal auxiliary metadata and retain the original 1000-byte cost in a real CBOR
    * envelope. The declared size continues to calibrate the native header estimate; it is not a
    * claim that this mock block passes Cardano ledger or signature validation.
    */
  private def block(
      point: ChainSync.Point.Block,
      prev: Option[Bytes],
      padding: Int,
      wide: Boolean
  ): Result[Block] =
    val metadata = Value.Map(
      Vector(
        node(Value.UInt(0)) -> node(Value.ByteString(Blake2b.hash256.hash(point.hash))),
        node(Value.UInt(1)) -> node(
          Value.Arr(
            Vector
              .fill(padding)(0.toByte)
              .grouped(64)
              .map(chunk => node(Value.ByteString(Bytes(chunk.toVector))))
              .toVector
          )
        ),
        node(Value.UInt(2)) -> node(Value.UInt(if wide then 24 else 0))
      )
    )
    for
      auxiliary <- encode(metadata)
      tx = Value.Map(
        Vector(
          node(Value.UInt(0)) -> node(arr()),
          node(Value.UInt(1)) -> node(arr()),
          node(Value.UInt(2)) -> node(Value.UInt(0)),
          node(Value.UInt(7)) -> node(Value.ByteString(Blake2b.hash256.hash(auxiliary)))
        )
      )
      components <- Vector(
        arr(tx),
        arr(Value.Map(Vector.empty)),
        Value.Map(Vector(node(Value.UInt(0)) -> node(metadata))),
        arr()
      ).traverse(encode)
      bodyHash = Blake2b.hash256.hash(Bytes(components.flatMap(c => Blake2b.hash256.hash(c).value)))
      header <-
        def encoded(size: BigInt): Result[Header.Shelley] =
          val signed = arr(
            Value.UInt(point.hash.size / 8),
            Value.UInt(point.slot.value),
            prev.fold[Value](Value.Null)(Value.ByteString(_)),
            bytes(32),
            bytes(32),
            arr(bytes(64), bytes(80)),
            Value.UInt(size),
            Value.ByteString(bodyHash),
            arr(bytes(32), Value.UInt(0), Value.UInt(0), bytes(64)),
            arr(Value.UInt(8), Value.UInt(0))
          )
          encode(arr(signed, bytes(448)))
            .flatMap(raw => Header.fromRaw(Era.Babbage, raw).leftMap(Problem.HeaderDecode(_)))
        Vector
          .fill(3)(())
          .foldLeftM(BigInt(0)) { (size, _) =>
            encoded(size).flatMap { h =>
              val next = BigInt(1000 - h.raw.size - 10)
              Either.cond(next >= 0, next, Problem.BlockEstimate(next))
            }
          }
          .flatMap(encoded)
      raw = Bytes(Vector(0x82.toByte, Era.Babbage.blockTag.toByte, 0x85.toByte)) ++
        header.raw ++ components.foldLeft(Bytes.empty)(_ ++ _)
    yield Block(point, header, raw)

  private def sized(point: ChainSync.Point.Block, prev: Option[Bytes]): Result[Block] =
    def search(wide: Boolean): Result[Option[Block]] =
      Monad[Result].tailRecM((0, 1000)) { (low, high) =>
        if low > high then Right(Right(None))
        else
          val middle = low + (high - low) / 2
          block(point, prev, middle, wide).map { candidate =>
            candidate.body.size.compare(1000) match
              case 0          => Right(Some(candidate))
              case n if n < 0 => Left((middle + 1, high))
              case _          => Left((low, middle - 1))
          }
      }
    search(false).flatMap {
      case Some(found) => Right(found)
      // CBOR chunk-length transitions can skip one encoded size; the alternate metadata integer
      // contributes exactly one byte and fills that gap without changing the native identity.
      case None => search(true).flatMap(_.toRight(Problem.BlockEstimate(1000)))
    }

  def build(scenario: Scenario): Either[Problem, Universe] =
    val points = scenario.tree.flatMap(_.headers).distinct.sortBy(_.hash.size)
    points
      .foldLeftM(Vector.empty[Block]) { (done, point) =>
        val parent = point.hash.value.drop(8)
        val previous =
          if parent.isEmpty then Right(None)
          else
            done
              .find(_.native.hash.value == parent)
              .map(b => Some(b.header.hash))
              .toRight(Problem.MissingParent(point))
        previous.flatMap { prev =>
          sized(point, prev).flatMap { block =>
            val estimate = block.header.bodySize + block.header.raw.size + 10
            Either.cond(estimate == 1000, done :+ block, Problem.BlockEstimate(estimate))
          }
        }
      }
      .flatMap { rows =>
        Either.cond(
          rows.map(_.header.hash).distinct.size == rows.size,
          Universe(rows),
          Problem.IdentityCollision
        )
      }
