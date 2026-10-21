// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.genesis

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

  final case class Block(native: ChainSync.Point.Block, header: Header.Shelley):
    def wire: NtNHeader = NtNHeader(header.era, header.raw, None)
    def body: Bytes = native.hash

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
          val body = arr(
            Value.UInt(point.hash.size / 8),
            Value.UInt(point.slot.value),
            prev.fold[Value](Value.Null)(Value.ByteString(_)),
            bytes(32),
            bytes(32),
            arr(bytes(64), bytes(80)),
            Value.UInt(point.hash.size),
            Value.ByteString(Blake2b.hash256.hash(point.hash)),
            arr(bytes(32), Value.UInt(0), Value.UInt(0), bytes(64)),
            arr(Value.UInt(8), Value.UInt(0))
          )
          for
            raw <- Cbor.encode(arr(body, bytes(448))).leftMap(Problem.Encoding(_))
            header <- Header.fromRaw(Era.Babbage, raw).leftMap(Problem.HeaderDecode(_))
          yield done :+ Block(point, header)
        }
      }
      .flatMap { rows =>
        Either.cond(
          rows.map(_.header.hash).distinct.size == rows.size,
          Universe(rows),
          Problem.IdentityCollision
        )
      }
