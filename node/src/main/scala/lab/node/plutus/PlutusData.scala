// SPDX-License-Identifier: Apache-2.0
package lab.node.plutus

import cats.syntax.all.*
import lab.cbor.{Bytes, Cbor, Node, Value}

/** Plutus `Data`: the one universal value passed to scripts. This is the node's own type; the
  * evaluator interpreters translate it to their VM's representation.
  */
enum PlutusData:
  case Constr(tag: BigInt, fields: Vector[PlutusData])

  /** An association list: keys may repeat and the order is significant. */
  case Map(entries: Vector[(PlutusData, PlutusData)])
  case List(items: Vector[PlutusData])
  case I(value: BigInt)
  case B(value: Bytes)

object PlutusData:
  val unit: PlutusData = Constr(0, Vector.empty)

  def constr(tag: Int, fields: PlutusData*): PlutusData = Constr(tag, fields.toVector)
  def bytes(b: Bytes): PlutusData = B(b)
  def int(i: BigInt): PlutusData = I(i)

  /** Haskell `Maybe`: `Just x = Constr 0 [x]`, `Nothing = Constr 1 []`. */
  def maybe(x: Option[PlutusData]): PlutusData = x.fold(constr(1))(constr(0, _))

  /** Haskell `Bool`: `False = Constr 0 []`, `True = Constr 1 []`. */
  def bool(b: Boolean): PlutusData = constr(if b then 1 else 0)

  /** Decoding limits are generous: datums are user-controlled, but a transaction is at most a few
    * tens of kilobytes.
    */
  private val limits =
    Cbor.Limits(
      maxInputBytes = 1 << 24,
      maxDepth = 1024,
      maxItems = 1 << 22,
      maxStringBytes = 1 << 24
    )

  def decode(bytes: Bytes): Either[String, PlutusData] =
    Cbor.stackSafe(Cbor.decode(bytes, limits).flatMap(fromNode))

  /** `plutus_data` of the Conway CDDL: constructors in tags 121 to 127, 1280 to 1400 and 102,
    * integers (with tag 2 and 3 big numbers), byte strings, lists and maps.
    */
  def fromNode(n: Node): Either[String, PlutusData] = n.value match
    case Value.UInt(v)       => Right(I(v))
    case Value.NInt(v)       => Right(I(v))
    case Value.ByteString(b) => Right(B(b))
    case Value.Arr(xs)       => xs.traverse(fromNode).map(List(_))
    case Value.Map(kvs) =>
      kvs.traverse { case (k, v) => (fromNode(k), fromNode(v)).tupled }.map(Map(_))
    case Value.Tag(t, inner) if t == 2 || t == 3 =>
      inner.value match
        case Value.ByteString(b) =>
          val magnitude = BigInt(1, b.toArray)
          Right(I(if t == 2 then magnitude else -1 - magnitude))
        case _ => Left("plutus data: big number without bytes")
    case Value.Tag(t, inner) if t >= 121 && t <= 127   => fields(inner).map(Constr(t - 121, _))
    case Value.Tag(t, inner) if t >= 1280 && t <= 1400 => fields(inner).map(Constr(t - 1280 + 7, _))
    case Value.Tag(t, inner) if t == 102 =>
      inner.value match
        case Value.Arr(Vector(ix, fs)) =>
          (
            ix.value match
              case Value.UInt(i) => Right(i)
              case _             => Left("plutus data: constructor index")
            ,
            fields(fs)
          ).mapN(Constr(_, _))
        case _ => Left("plutus data: tag 102 expects [index, fields]")
    case Value.Tag(t, _) => Left(s"plutus data: unexpected tag $t")
    case other           => Left(s"plutus data: unexpected CBOR item $other")

  private def fields(n: Node): Either[String, Vector[PlutusData]] = n.value match
    case Value.Arr(xs) => xs.traverse(fromNode)
    case _             => Left("plutus data: constructor fields must be a list")
