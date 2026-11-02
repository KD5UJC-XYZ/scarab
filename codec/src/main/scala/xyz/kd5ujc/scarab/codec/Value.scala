// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import xyz.kd5ujc.scarab.codec.Tree.{treeEquals, treeHash, treeString}

/** A decoded value and its exact wire representation, including indefinite forms.
  *
  * A node out of [[Cbor.decode]] is a view of an item of the input, 24 bytes onto the [[Index]] of
  * its document: the value is read from the input each time `value` is called (one level, its
  * children again views) and `original` is a window of the input, so a document costs two ints an
  * item and not an object tree. `value` is therefore a `def` whose cost a caller pays at each call:
  * read it once and keep the result for as long as it is needed. A node built by hand with
  * [[Node.apply]] holds both.
  */
sealed abstract class Node:
  def value: Value

  /** The exact bytes of the item as read: what anything hashed or signed is hashed over. */
  def original: Bytes

  /** Whether the item's head marks an indefinite-length item — string, array or map (additional
    * information 31) — so its payload arrives in chunks. Callers here apply it to byte strings and
    * text, where the reference's decoders refuse the form. A node built by hand without original
    * bytes was not read from such a head. The reference's decoders refuse this form wherever they
    * take a definite string: cborg's `decodeBytes` and `decodeString`, and cardano-ledger-binary's
    * `decodeBytes` below decoder version 12 (`Decoder.hs:350-373`, cardano-ledger f649f975). Byte
    * strings and text ask the same question of the item, not of the reader.
    */
  final def isIndefinite: Boolean = original.byteOr(0)(false)(b => (b & 31) == 31)

  /** Where the item's head is in the array that `original` is a window of (the start of `original`,
    * without making it), for locating an error.
    */
  def offset: Int

  /** The entries of the item if it is a map, one at a time and in order, as the pairs of
    * `Value.Map`; none otherwise. Unlike `value` it does not make the vector of every pair, so a
    * decoder that folds the entries of a map of a million holds the one it is at.
    */
  def entries: Option[Iterator[(Node, Node)]]

  /** The items of the item if it is an array, one at a time and in order, as the items of
    * `Value.Arr`; none otherwise. As [[entries]], it does not make the vector of every item, so a
    * decoder that folds the elements of a set of a million holds the one it is at.
    */
  def items: Option[Iterator[Node]]

  /** `a`, or the value equal to it that this item's decode has shared already (see [[Interns]]). A
    * node built by hand belongs to no decode and shares nothing.
    */
  def share[A <: AnyRef](kind: Interned[A])(a: A): A

  // Structural, and walked on the heap: the generated ones recurse once per level of nesting. The
  // string is truncated on purpose (see `Tree.render`) and leaves the original bytes out.
  override def equals(that: Any): Boolean = that match
    case o: Node => this.treeEquals(o)
    case _       => false
  override def hashCode(): Int = this.treeHash
  override def toString: String = this.treeString

object Node:
  /** A node built by hand, from a value and the bytes it is to stand for. Built without them
    * (`Bytes.empty`), it reads to every check of an item's head as written in the canonical form:
    * definite, with the shortest head ([[Node.isIndefinite]] is false).
    */
  def apply(value: Value, original: Bytes): Node = new Strict(value, original)

  private final class Strict(val value: Value, val original: Bytes) extends Node:
    def offset: Int = original.unsafeOffset
    def share[A <: AnyRef](kind: Interned[A])(a: A): A = a
    def entries: Option[Iterator[(Node, Node)]] = value match
      case Value.Map(pairs) => Some(pairs.iterator)
      case _                => None
    def items: Option[Iterator[Node]] = value match
      case Value.Arr(xs) => Some(xs.iterator)
      case _             => None

  /** Item `index` of `doc`. */
  private[codec] final class Indexed(doc: Document, index: Int) extends Node:
    def value: Value = doc.valueAt(index)
    def original: Bytes = doc.originalAt(index)
    def offset: Int = doc.index.start(index)
    def entries: Option[Iterator[(Node, Node)]] = doc.entriesAt(index)
    def items: Option[Iterator[Node]] = doc.itemsAt(index)
    def share[A <: AnyRef](kind: Interned[A])(a: A): A = doc.interns.share(kind)(a)

  /** A node's children are those of its value; its label adds the exact bytes it was read from. */
  given Tree[Node] with
    def children(n: Node): IndexedSeq[Node] = Value.nodes(n.value)
    def sameLabel(x: Node, y: Node): Boolean =
      x.original == y.original && Value.sameLabel(x.value, y.value)
    def labelHash(n: Node): Int = Value.labelHash(n.value)
    def name(n: Node): String = Value.name(n.value)

enum Value:
  case UInt(value: BigInt)

  /** The mathematical negative integer, not CBOR's encoded (-1 - n) argument. */
  case NInt(value: BigInt)
  case ByteString(value: Bytes)
  case Text(value: String)
  case Arr(value: Vector[Node])
  case Map(value: Vector[(Node, Node)])
  case Tag(number: BigInt, value: Node)
  case Bool(value: Boolean)
  case Null

  /** Undefined (23) or an unassigned simple value (0..19, 32..255). Distinct from null. */
  case Simple(value: Int)

  /** A floating-point number, with the width it was (or is to be) written in. Only produced by a
    * decoder whose [[Cbor.Limits]] allow floats; the value is held as a `Double`, which every width
    * converts to exactly.
    */
  case Float(value: Double, width: FloatWidth)

  // As `Node`: structural and stack-safe, ignoring the original bytes of the nodes.
  override def equals(that: Any): Boolean = that match
    case o: Value => this.treeEquals(o)
    case _        => false
  override def hashCode(): Int = this.treeHash
  override def toString: String = this.treeString

/** The IEEE 754 width of a CBOR float: additional information 25, 26 or 27 (RFC 8949 section 3.3).
  */
enum FloatWidth:
  case Half, Single, Double

object Value:
  /** The children of a value: array items, map keys and values in turn, a tag's content. */
  private[codec] def nodes(v: Value): IndexedSeq[Node] = v match
    case Arr(xs)      => xs
    case Map(kvs)     => TreeFold.flatten(kvs)
    case Tag(_, node) => Vector(node)
    case _            => Vector.empty

  private[codec] def sameLabel(x: Value, y: Value): Boolean = (x, y) match
    case (UInt(a), UInt(b))             => a == b
    case (NInt(a), NInt(b))             => a == b
    case (ByteString(a), ByteString(b)) => a == b
    case (Text(a), Text(b))             => a == b
    case (Bool(a), Bool(b))             => a == b
    case (Simple(a), Simple(b))         => a == b
    // the bits, so that a NaN equals itself and 0.0 differs from -0.0: the wire representation
    case (Float(a, wa), Float(b, wb)) =>
      wa == wb && java.lang.Double.doubleToLongBits(a) == java.lang.Double.doubleToLongBits(b)
    case (Tag(a, _), Tag(b, _)) => a == b
    case (Arr(_), Arr(_))       => true
    case (Map(_), Map(_))       => true
    // not `case Null`: a stable identifier pattern compares with `==`, which is this walk
    case _ if (x eq Null) && (y eq Null) => true
    case _                               => false

  private[codec] def labelHash(v: Value): Int = v match
    case UInt(a)       => a.hashCode
    case NInt(a)       => 31 + a.hashCode
    case ByteString(a) => a.hashCode
    case Text(a)       => a.hashCode
    case Bool(a)       => a.hashCode
    case Simple(a)     => 43 + a.hashCode
    case Float(a, _)   => 41 + java.lang.Double.hashCode(a)
    case Tag(a, _)     => 37 + a.hashCode
    case Arr(_)        => 1
    case Map(_)        => 2
    case _             => 3

  private[codec] def name(v: Value): String = v match
    case UInt(a)       => s"UInt($a)"
    case NInt(a)       => s"NInt($a)"
    case ByteString(a) => s"ByteString(${Tree.shown(a)})"
    case Text(a)       => s"Text(${Tree.shown(a)})"
    case Bool(a)       => s"Bool($a)"
    case Simple(a)     => s"Simple($a)"
    case Float(a, w)   => s"Float($a, $w)"
    case Tag(a, _)     => s"Tag($a)"
    case Arr(_)        => "Arr"
    case Map(_)        => "Map"
    case _             => "Null"

  given Tree[Value] with
    def children(v: Value): IndexedSeq[Value] = nodes(v).map(_.value)
    def sameLabel(x: Value, y: Value): Boolean = Value.sameLabel(x, y)
    def labelHash(v: Value): Int = Value.labelHash(v)
    def name(v: Value): String = Value.name(v)

trait Encoder[A]:
  type Error <: DomainError
  def encode(value: A): Either[Error, Bytes]

object Encoder:
  type Aux[A, E <: DomainError] = Encoder[A] { type Error = E }

trait Serde[A] extends Encoder[A]:
  def decode(bytes: Bytes): Either[Error, A]

object Serde:
  type Aux[A, E <: DomainError] = Serde[A] { type Error = E }
