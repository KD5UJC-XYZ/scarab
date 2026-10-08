// SPDX-License-Identifier: Apache-2.0
package lab.cbor

/** A decoded value and its exact wire representation, including indefinite forms. */
final case class Node(value: Value, original: Bytes)

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

trait Encoder[A]:
  def encode(value: A): Either[String, Bytes]

trait Serde[A] extends Encoder[A]:
  def decode(bytes: Bytes): Either[String, A]
