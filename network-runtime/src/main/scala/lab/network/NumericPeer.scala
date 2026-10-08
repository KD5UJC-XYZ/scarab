// SPDX-License-Identifier: Apache-2.0
package lab.network

import java.net.{InetAddress, InetSocketAddress}

/** Numeric-only peer. IPv4-mapped IPv6 is rejected rather than silently changing family. Brackets,
  * scopes, abbreviated/legacy IPv4, whitespace, unspecified and multicast are rejected. Equality
  * and identity depend on address bytes/family and port, never input spelling or DNS.
  */
final class NumericPeer private (
    val addressBytes: Vector[Byte],
    val port: Int
):
  val family: String = if addressBytes.size == 4 then "ipv4" else "ipv6"
  val canonicalAddress: String =
    if addressBytes.size == 4 then addressBytes.map(b => (b & 255).toString).mkString(".")
    else
      addressBytes.grouped(2).map(p => f"${((p(0) & 255) << 8) | (p(1) & 255)}%04x").mkString(":")
  val identity: String = s"$family:$canonicalAddress:$port"
  def socketAddress: InetSocketAddress =
    new InetSocketAddress(InetAddress.getByAddress(addressBytes.toArray), port)
  override def equals(other: Any): Boolean = other match
    case p: NumericPeer => addressBytes == p.addressBytes && port == p.port
    case _              => false
  override def hashCode(): Int = (addressBytes, port).hashCode()
  override def toString: String = identity

object NumericPeer:
  private def ipv4(text: String): Option[Vector[Byte]] =
    val parts = text.split("\\.", -1).toVector
    Option.when(
      parts.size == 4 && parts.forall(p =>
        p.nonEmpty && p.length <= 3 && p.forall(c => c >= '0' && c <= '9') &&
          (p.length == 1 || p.head != '0') && p.toInt <= 255
      )
    )(parts.map(_.toInt.toByte))

  private def ipv6(text: String): Option[Vector[Byte]] =
    // Embedded dotted IPv4 is deliberately excluded along with mapped hexadecimal spelling.
    def groups(s: String): Option[Vector[Int]] =
      if s.isEmpty then Some(Vector.empty)
      else
        val pieces = s.split(":", -1).toVector
        Option.when(
          pieces.forall(p =>
            p.nonEmpty && p.length <= 4 && p.forall(c =>
              (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
            )
          )
        )(pieces.map(Integer.parseInt(_, 16)))
    val split = text.split("::", -1).toVector
    val words = split match
      case Vector(single) => groups(single).filter(_.size == 8)
      case Vector(left, right) =>
        for
          a <- groups(left)
          b <- groups(right)
          if a.size + b.size < 8
        yield a ++ Vector.fill(8 - a.size - b.size)(0) ++ b
      case _ => None
    words
      .filterNot(w => w.take(5).forall(_ == 0) && w(5) == 0xffff)
      .map(_.flatMap(w => Vector((w >>> 8).toByte, w.toByte)))

  def checked(literal: String, port: Int): Either[String, NumericPeer] =
    if port < 1 || port > 65535 then Left("TCP port must be 1..65535")
    else if literal == null || literal.isEmpty || literal.length > 39 then
      Left("expected an unscoped numeric IPv4 or IPv6 literal")
    else
      val parsed = if literal.contains(':') then ipv6(literal) else ipv4(literal)
      parsed
        .toRight("expected an unscoped numeric IPv4 or IPv6 literal (mapped IPv6 excluded)")
        .flatMap { bytes =>
          if bytes.forall(_ == 0) then Left("unspecified peer address is not allowed")
          else if (bytes.size == 4 && (bytes.head & 0xf0) == 0xe0) ||
            (bytes.size == 16 && (bytes.head & 255) == 255)
          then Left("multicast peer address is not allowed")
          else Right(new NumericPeer(bytes, port))
        }
