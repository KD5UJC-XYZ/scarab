// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Tree
import xyz.kd5ujc.scarab.codec.Tree.{treeEquals, treeHash, treeString}

/** The type of a constant: the reference's universe `DefaultUni` at kind `*`
  * (`PlutusCore/Default/Universe.hs:136-159`, plutus 1.70.0.0 `507cab8c`). `List`, `Pair` and
  * `Array` are the applications `DefaultUniList`, `DefaultUniPair` and `DefaultUniArray` of the
  * type operators `DefaultUniProtoList`, `DefaultUniProtoPair` and `DefaultUniProtoArray`; an
  * operator alone has no values, so it is not a type here (the Flat decoder's kinded intermediate
  * form is its own).
  *
  * A type is as deep as its script makes it (the type header is unbounded before protocol 11,
  * `Common/Versions.hs:394-398`), so equality, hashing and rendering walk it on the heap.
  */
enum DefaultUni:
  case Integer
  case ByteString
  case String
  case Unit
  case Bool
  case Data
  case Bls12_381_G1_Element
  case Bls12_381_G2_Element
  case Bls12_381_MlResult
  case Value
  case List(element: DefaultUni)
  case Pair(first: DefaultUni, second: DefaultUni)
  case Array(element: DefaultUni)

  override def equals(that: Any): Boolean = that match
    case o: DefaultUni => this.treeEquals(o)
    case _             => false
  override def hashCode(): Int = this.treeHash
  override def toString: java.lang.String = this.treeString

object DefaultUni:
  given Tree[DefaultUni] with
    def children(u: DefaultUni): IndexedSeq[DefaultUni] = u match
      case List(e)    => Vector(e)
      case Pair(a, b) => Vector(a, b)
      case Array(e)   => Vector(e)
      case _          => Vector.empty
    def sameLabel(x: DefaultUni, y: DefaultUni): Boolean = x.ordinal == y.ordinal
    def labelHash(u: DefaultUni): Int = u.ordinal
    def name(u: DefaultUni): java.lang.String = u match
      case Integer              => "Integer"
      case ByteString           => "ByteString"
      case String               => "String"
      case Unit                 => "Unit"
      case Bool                 => "Bool"
      case Data                 => "Data"
      case Bls12_381_G1_Element => "Bls12_381_G1_Element"
      case Bls12_381_G2_Element => "Bls12_381_G2_Element"
      case Bls12_381_MlResult   => "Bls12_381_MlResult"
      case Value                => "Value"
      case List(_)              => "List"
      case Pair(_, _)           => "Pair"
      case Array(_)             => "Array"

  extension (u: DefaultUni)
    /** The reference's `defaultUniSize` (`Universe.hs:161-164`): an application counts itself and
      * its operator, so `List(a)` is `size(a) + 2` and `Pair(a, b)` is `size(a) + size(b) + 3`. It
      * is the number of tags of the type's Flat encoding, which the ledger bounds from protocol 11
      * (`Common/SerialisedScript.hs:207-214`).
      */
    def size: Int =
      @tailrec def go(pending: scala.List[DefaultUni], total: Int): Int = pending match
        case Nil => total
        case t :: rest =>
          t match
            case List(e)    => go(e :: rest, total + 2)
            case Pair(a, b) => go(a :: b :: rest, total + 3)
            case Array(e)   => go(e :: rest, total + 2)
            case _          => go(rest, total + 1)
      go(scala.List(u), 0)
