// SPDX-License-Identifier: Apache-2.0
package lab.cbor

/** Immutable bytes; arrays crossing this boundary are always copied. */
final case class Bytes(value: Vector[Byte]):
  def hex: String = value.iterator.map(b => f"${b & 0xff}%02x").mkString
  def toArray: Array[Byte] = value.toArray
  def size: Int = value.size

object Bytes:
  val empty: Bytes = Bytes(Vector.empty)
  def fromArray(bytes: Array[Byte]): Bytes = Bytes(bytes.toVector)
  def fromHex(hex: String): Either[String, Bytes] =
    if hex.length % 2 != 0 then Left("hex must have an even number of characters")
    else if !hex.forall(c =>
        (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
      )
    then Left("invalid hex character")
    else Right(Bytes(hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toVector))
