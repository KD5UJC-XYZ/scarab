// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, Tree}
import xyz.kd5ujc.scarab.codec.Tree.{treeEquals, treeHash, treeString}
import xyz.kd5ujc.scarab.uplc.bls.{G1Element, G2Element, MlResult}

/** A constant: a value of a type of the universe, the reference's `Some (ValueOf DefaultUni)`
  * (`PlutusCore/Default/Universe.hs:136-150`, plutus 1.70.0.0 `507cab8c`). Every constant knows its
  * [[typeOf]] in constant time: a list or an array carries its element type (an empty one has no
  * element to tell it), and a pair computes its type once from its components.
  *
  * Lists and arrays are well typed by construction: every element has the element type. Constants
  * nest as deep as their type, so equality, hashing and rendering walk them on the heap. Equality
  * is that of the reference's `Some (ValueOf uni)`: equal types and equal values.
  */
sealed abstract class Constant:
  def typeOf: DefaultUni

  // Two constants are equal when their types are and their values are. The types of the elements
  // follow from the type of the whole for well-typed lists and arrays, so the walk below compares
  // values only, and a constant nested as deep as its type is compared in time linear in its size.
  override def equals(that: Any): Boolean = that match
    case o: Constant => (this eq o) || (typeOf == o.typeOf && this.treeEquals(o))
    case _           => false
  override def hashCode(): Int = this.treeHash
  override def toString: String = this.treeString

object Constant:
  final case class Integer(value: BigInt) extends Constant:
    def typeOf: DefaultUni = DefaultUni.Integer

  final case class ByteString(value: Bytes) extends Constant:
    def typeOf: DefaultUni = DefaultUni.ByteString

  final case class String(value: Text) extends Constant:
    def typeOf: DefaultUni = DefaultUni.String

  case object Unit extends Constant:
    def typeOf: DefaultUni = DefaultUni.Unit

  final case class Bool(value: Boolean) extends Constant:
    def typeOf: DefaultUni = DefaultUni.Bool

  final case class Data(value: PlutusData) extends Constant:
    def typeOf: DefaultUni = DefaultUni.Data

  final case class Bls12_381_G1_Element(value: G1Element) extends Constant:
    def typeOf: DefaultUni = DefaultUni.Bls12_381_G1_Element

  final case class Bls12_381_G2_Element(value: G2Element) extends Constant:
    def typeOf: DefaultUni = DefaultUni.Bls12_381_G2_Element

  final case class Bls12_381_MlResult(value: MlResult) extends Constant:
    def typeOf: DefaultUni = DefaultUni.Bls12_381_MlResult

  final case class Value(value: xyz.kd5ujc.scarab.uplc.Value) extends Constant:
    def typeOf: DefaultUni = DefaultUni.Value

  /** A list constant (`DefaultUniList`): elements all of type `elementType`.
    *
    * The reference's value of a list type is a Haskell list of the element type's values
    * (`DefaultUniProtoList`, `PlutusCore/Default/Universe.hs:144`, `:155-156`, plutus 1.71.0.0
    * `261766ad`), and a builtin reads it as it is (`readKnownConstant`,
    * `PlutusCore/Builtin/KnownType.hs:310-323` there; unlifting is to be cheap, `:169-200`). So a
    * list of `Data` is the very `[Data]` of a `Data` node, which `unListData`, `unConstrData` and
    * `listData` share in constant time, as `unMapData` and `mapData` share the `[(Data, Data)]` of
    * a map (`Builtins.hs:1763-1838` there). Here too a list of `Data` keeps the `PlutusData` values
    * themselves ([[List.OfData]]) and a list of pairs of `Data` the pairs ([[List.OfDataPairs]]),
    * shared with the node; a list of any other type keeps its constants ([[List.OfConstants]]).
    * Which of the three a list is follows from its element type alone ([[List.General]]), so each
    * list type has one form, and an element is a constant only when it is read.
    */
  sealed abstract class List(val elementType: DefaultUni) extends Constant:
    val typeOf: DefaultUni = DefaultUni.List(elementType)

    /** The number of elements. */
    def size: Int = this match
      case List.OfData(ds)         => ds.size
      case List.OfDataPairs(es)    => es.size
      case List.OfConstants(_, cs) => cs.size

    def isEmpty: Boolean = size == 0

    /** The first element, none when the list is empty. */
    def headOption: Option[Constant] = this match
      case List.OfData(ds)         => ds.headOption.map(Data(_))
      case List.OfDataPairs(es)    => es.headOption.map(List.dataPair)
      case List.OfConstants(_, cs) => cs.headOption

    /** The list without its head, none when it is empty. */
    def tail: Option[List] = Option.when(!isEmpty)(drop(1))

    /** The list without its first `n` elements. */
    def drop(n: Int): List = this match
      case List.OfData(ds)         => List.OfData(ds.drop(n))
      case List.OfDataPairs(es)    => List.OfDataPairs(es.drop(n))
      case List.OfConstants(t, cs) => new List.OfConstants(t, cs.drop(n))

    /** `head` in front, refused unless it has the element type. */
    def cons(head: Constant): Either[ElementTypeMismatch, List] =
      def mismatch = ElementTypeMismatch(elementType, head.typeOf, 0)
      this match
        case List.OfData(ds) =>
          List.data.lift(head).toRight(mismatch).map(d => List.OfData(d +: ds))
        case List.OfDataPairs(es) =>
          List.dataPairs.lift(head).toRight(mismatch).map(e => List.OfDataPairs(e +: es))
        case List.OfConstants(t, cs) =>
          Either.cond(head.typeOf == elementType, new List.OfConstants(t, head +: cs), mismatch)

    /** The elements as constants. A list of `Data` or of pairs of `Data` makes each one as it is
      * read, so taking this view is constant time, and walking it is linear.
      */
    def items: IndexedSeq[Constant] = this match
      case List.OfData(ds)         => List.Elements(ds, Data(_))
      case List.OfDataPairs(es)    => List.Elements(es, List.dataPair)
      case List.OfConstants(_, cs) => cs

    /** The array of the same elements (`listToArray`, whose cost is linear in the list's length).
      */
    def toArray: Array = new Array(elementType, items.toVector)

  object List:
    private val DataPair: DefaultUni = DefaultUni.Pair(DefaultUni.Data, DefaultUni.Data)

    /** A list of `Data`: the values themselves, as a `Data` node holds them. */
    final case class OfData(data: Vector[PlutusData]) extends List(DefaultUni.Data)

    /** A list of pairs of `Data`: the entries themselves, as a `Data` map holds them. */
    final case class OfDataPairs(entries: Vector[(PlutusData, PlutusData)]) extends List(DataPair)

    /** A list of any other type: its constants, each of type `general`. */
    final case class OfConstants private[Constant] (general: General, constants: Vector[Constant])
        extends List(general)

    /** An element type other than `Data` and `(Data, Data)`: a type whose lists keep their
      * constants. Only `layout` makes one, so no list of constants has elements of those two.
      */
    opaque type General <: DefaultUni = DefaultUni

    // The form of a list of a type, by the type alone.
    private enum Layout:
      case Values
      case Pairs
      case Constants(general: General)

    private def layout(elementType: DefaultUni): Layout = elementType match
      case DefaultUni.Data                                   => Layout.Values
      case DefaultUni.Pair(DefaultUni.Data, DefaultUni.Data) => Layout.Pairs
      case DefaultUni.Integer | DefaultUni.ByteString | DefaultUni.String | DefaultUni.Unit |
          DefaultUni.Bool | DefaultUni.Bls12_381_G1_Element | DefaultUni.Bls12_381_G2_Element |
          DefaultUni.Bls12_381_MlResult | DefaultUni.Value | DefaultUni.List(_) |
          DefaultUni.Pair(_, _) | DefaultUni.Array(_) =>
        Layout.Constants(elementType)

    // The value of a constant of type `Data`, and of one of type `(Data, Data)`: the constants of
    // each type are exactly those these read.
    private val data: PartialFunction[Constant, PlutusData] = { case Data(d) => d }
    private val dataPairs: PartialFunction[Constant, (PlutusData, PlutusData)] = {
      case Pair(Data(k), Data(v)) => (k, v)
    }

    private def dataPair(entry: (PlutusData, PlutusData)): Constant =
      Pair(Data(entry._1), Data(entry._2))

    // The elements of a list that keeps values, each made a constant as it is read. `apply` is
    // the indexed read of `IndexedSeq`, partial as `Vector`'s is.
    private final class Elements[A](values: Vector[A], element: A => Constant)
        extends scala.collection.immutable.IndexedSeq[Constant]:
      def apply(i: Int): Constant = element(values(i))
      def length: Int = values.length
      override def iterator: Iterator[Constant] = values.iterator.map(element)

    def empty(elementType: DefaultUni): List = layout(elementType) match
      case Layout.Values             => OfData(Vector.empty)
      case Layout.Pairs              => OfDataPairs(Vector.empty)
      case Layout.Constants(general) => new OfConstants(general, Vector.empty)

    /** The list of `items`, refused at the first element whose type is not `elementType`. */
    def of(elementType: DefaultUni, items: Vector[Constant]): Either[ElementTypeMismatch, List] =
      layout(elementType) match
        case Layout.Values => values(elementType, items, data).map(OfData(_))
        case Layout.Pairs  => values(elementType, items, dataPairs).map(OfDataPairs(_))
        case Layout.Constants(general) =>
          well(elementType, items).map(new OfConstants(general, _))

    // The values of `items`, refused at the first that `value` does not read.
    private def values[A](
        elementType: DefaultUni,
        items: Vector[Constant],
        value: PartialFunction[Constant, A]
    ): Either[ElementTypeMismatch, Vector[A]] =
      val read = value.lift
      @tailrec def go(i: Int, done: Vector[A]): Either[ElementTypeMismatch, Vector[A]] =
        items.lift(i) match
          case None => Right(done)
          case Some(c) =>
            read(c) match
              case Some(a) => go(i + 1, done :+ a)
              case None    => Left(ElementTypeMismatch(elementType, c.typeOf, i))
      go(0, Vector.empty)

  /** An array constant (`DefaultUniArray`): `items` all of type `elementType`. */
  final case class Array private[Constant] (elementType: DefaultUni, items: Vector[Constant])
      extends Constant:
    val typeOf: DefaultUni = DefaultUni.Array(elementType)

  object Array:
    /** The array of `items`, refused at the first element whose type is not `elementType`. */
    def of(elementType: DefaultUni, items: Vector[Constant]): Either[ElementTypeMismatch, Array] =
      well(elementType, items).map(new Array(elementType, _))

  /** A pair constant (`DefaultUniPair`). */
  final case class Pair(first: Constant, second: Constant) extends Constant:
    val typeOf: DefaultUni = DefaultUni.Pair(first.typeOf, second.typeOf)

  /** An element whose type is not the element type of its list or array.
    *
    * @param position
    *   the element's index among those given
    */
  final case class ElementTypeMismatch(expected: DefaultUni, found: DefaultUni, position: Int)

  private def well(
      elementType: DefaultUni,
      items: Vector[Constant]
  ): Either[ElementTypeMismatch, Vector[Constant]] =
    items.iterator.zipWithIndex
      .collectFirst {
        case (c, i) if c.typeOf != elementType => ElementTypeMismatch(elementType, c.typeOf, i)
      }
      .toLeft(items)

  // The values of constants as trees: a list's or an array's element type is not part of its label,
  // since `equals` compares the types once, at the top.
  given Tree[Constant] with
    def children(c: Constant): IndexedSeq[Constant] = c match
      case l: List         => l.items
      case Array(_, items) => items
      case Pair(a, b)      => Vector(a, b)
      case _               => Vector.empty
    def sameLabel(x: Constant, y: Constant): Boolean = (x, y) match
      case (Integer(a), Integer(b))                           => a == b
      case (ByteString(a), ByteString(b))                     => a == b
      case (String(a), String(b))                             => a == b
      case (_: Unit.type, _: Unit.type)                       => true
      case (Bool(a), Bool(b))                                 => a == b
      case (Data(a), Data(b))                                 => a == b
      case (Bls12_381_G1_Element(a), Bls12_381_G1_Element(b)) => a.compressed == b.compressed
      case (Bls12_381_G2_Element(a), Bls12_381_G2_Element(b)) => a.compressed == b.compressed
      case (Bls12_381_MlResult(a), Bls12_381_MlResult(b))     => a.raw == b.raw
      case (Value(a), Value(b))                               => a == b
      case (_: List, _: List)                                 => true
      case (Array(_, _), Array(_, _))                         => true
      case (Pair(_, _), Pair(_, _))                           => true
      case _                                                  => false
    def labelHash(c: Constant): Int = c match
      case Integer(a)              => 1 + a.hashCode
      case ByteString(a)           => 2 + a.hashCode
      case String(a)               => 3 + a.hashCode
      case _: Unit.type            => 4
      case Bool(a)                 => if a then 5 else 6
      case Data(a)                 => 7 + a.hashCode
      case Bls12_381_G1_Element(a) => 8 + a.compressed.hashCode
      case Bls12_381_G2_Element(a) => 9 + a.compressed.hashCode
      case Bls12_381_MlResult(a)   => 10 + a.raw.hashCode
      case Value(a)                => 11 + a.hashCode
      case _: List                 => 12
      case Array(_, _)             => 13
      case Pair(_, _)              => 14
    def name(c: Constant): scala.Predef.String = c match
      case Integer(a)              => s"Integer($a)"
      case ByteString(a)           => s"ByteString(${Tree.shown(a)})"
      case String(a)               => s"String(${Tree.shown(a.string)})"
      case _: Unit.type            => "Unit"
      case Bool(a)                 => s"Bool($a)"
      case Data(a)                 => s"Data($a)"
      case Bls12_381_G1_Element(a) => s"G1(${Tree.shown(a.compressed)})"
      case Bls12_381_G2_Element(a) => s"G2(${Tree.shown(a.compressed)})"
      case Bls12_381_MlResult(a)   => s"MlResult(${Tree.shown(a.raw)})"
      case Value(a)                => s"Value(${a.totalSize} entries)"
      case l: List                 => s"List(${l.elementType})"
      case Array(t, _)             => s"Array($t)"
      case Pair(_, _)              => "Pair"
