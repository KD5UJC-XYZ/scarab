// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

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

  /** A list constant (`DefaultUniList`): `items` all of type `elementType`. */
  final case class List private[Constant] (elementType: DefaultUni, items: Vector[Constant])
      extends Constant:
    val typeOf: DefaultUni = DefaultUni.List(elementType)

    /** The list without its head, none when it is empty. */
    def tail: Option[List] = Option.when(items.nonEmpty)(new List(elementType, items.drop(1)))

    /** The list without its first `n` elements. */
    def drop(n: Int): List = new List(elementType, items.drop(n))

    /** `head` in front, refused unless it has the element type. */
    def cons(head: Constant): Either[ElementTypeMismatch, List] =
      Either.cond(
        head.typeOf == elementType,
        new List(elementType, head +: items),
        ElementTypeMismatch(elementType, head.typeOf, 0)
      )

    /** The array of the same elements (`listToArray`). */
    def toArray: Array = new Array(elementType, items)

  object List:
    def empty(elementType: DefaultUni): List = new List(elementType, Vector.empty)

    /** The list of `items`, refused at the first element whose type is not `elementType`. */
    def of(elementType: DefaultUni, items: Vector[Constant]): Either[ElementTypeMismatch, List] =
      well(elementType, items).map(new List(elementType, _))

  /** An array constant (`DefaultUniArray`): `items` all of type `elementType`. */
  final case class Array private[Constant] (elementType: DefaultUni, items: Vector[Constant])
      extends Constant:
    val typeOf: DefaultUni = DefaultUni.Array(elementType)

    /** The list of the same elements. */
    def toList: List = new List(elementType, items)

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
      case List(_, items)  => items
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
      case (List(_, _), List(_, _))                           => true
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
      case List(_, _)              => 12
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
      case List(t, _)              => s"List($t)"
      case Array(t, _)             => s"Array($t)"
      case Pair(_, _)              => "Pair"
