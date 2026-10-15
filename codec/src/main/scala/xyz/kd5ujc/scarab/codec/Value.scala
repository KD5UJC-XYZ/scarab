// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import xyz.kd5ujc.scarab.codec.Tree.{treeEquals, treeHash, treeString}

/** A decoded value and its exact wire representation, including indefinite forms. */
final case class Node(value: Value, original: Bytes):
  // Structural, and walked on the heap: the generated ones recurse once per level of nesting. The
  // string is truncated on purpose (see `Tree.render`) and leaves the original bytes out.
  override def equals(that: Any): Boolean = that match
    case o: Node => this.treeEquals(o)
    case _       => false
  override def hashCode(): Int = this.treeHash
  override def toString: String = this.treeString

object Node:
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

  // As `Node`: structural and stack-safe, ignoring the original bytes of the nodes.
  override def equals(that: Any): Boolean = that match
    case o: Value => this.treeEquals(o)
    case _        => false
  override def hashCode(): Int = this.treeHash
  override def toString: String = this.treeString

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
    case (Tag(a, _), Tag(b, _))         => a == b
    case (Arr(_), Arr(_))               => true
    case (Map(_), Map(_))               => true
    // not `case Null`: a stable identifier pattern compares with `==`, which is this walk
    case _ if (x eq Null) && (y eq Null) => true
    case _                               => false

  private[codec] def labelHash(v: Value): Int = v match
    case UInt(a)       => a.hashCode
    case NInt(a)       => 31 + a.hashCode
    case ByteString(a) => a.hashCode
    case Text(a)       => a.hashCode
    case Bool(a)       => a.hashCode
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
  def encode(value: A): Either[String, Bytes]

trait Serde[A] extends Encoder[A]:
  def decode(bytes: Bytes): Either[String, A]
