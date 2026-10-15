// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}

/** Total readers over decoded CBOR. Every failure names what was being read. */
private[ledger] object CborRead:
  type Result[A] = Either[String, A]

  def uint(n: Node, what: String): Result[BigInt] = n.value match
    case Value.UInt(v) => Right(v)
    case _             => Left(s"$what: expected unsigned integer")

  def int(n: Node, what: String): Result[BigInt] = n.value match
    case Value.UInt(v) => Right(v)
    case Value.NInt(v) => Right(v)
    case _             => Left(s"$what: expected integer")

  def bytes(n: Node, what: String): Result[Bytes] = n.value match
    case Value.ByteString(v) => Right(v)
    case _                   => Left(s"$what: expected byte string")

  def bytesN(n: Node, size: Int, what: String): Result[Bytes] =
    bytes(n, what).flatMap(b => Either.cond(b.size == size, b, s"$what: expected $size bytes"))

  def text(n: Node, what: String): Result[String] = n.value match
    case Value.Text(v) => Right(v)
    case _             => Left(s"$what: expected text")

  def bool(n: Node, what: String): Result[Boolean] = n.value match
    case Value.Bool(v) => Right(v)
    case _             => Left(s"$what: expected bool")

  def array(n: Node, what: String): Result[Vector[Node]] = n.value match
    case Value.Arr(xs) => Right(xs)
    case _             => Left(s"$what: expected array")

  def arrayN(n: Node, size: Int, what: String): Result[Vector[Node]] =
    array(n, what).flatMap(xs =>
      Either.cond(xs.size == size, xs, s"$what: expected $size elements")
    )

  def map(n: Node, what: String): Result[Vector[(Node, Node)]] = n.value match
    case Value.Map(kvs) => Right(kvs)
    case _              => Left(s"$what: expected map")

  /** A list that may be wrapped in tag 258 (Conway sets); elements are kept as they are. */
  def list(n: Node, what: String): Result[Vector[Node]] = n.value match
    case Value.Tag(t, inner) if t == 258 => array(inner, what)
    case _                               => array(n, what)

  /** Sets decode as arrays, optionally wrapped in tag 258. Before Conway an encoding may repeat an
    * element; the set keeps the first occurrence.
    */
  def set(n: Node, what: String): Result[Vector[Node]] =
    list(n, what).map(xs => xs.distinctBy(_.original))

  /** Map with unsigned-integer keys, rejecting duplicate keys. */
  def fields(n: Node, what: String): Result[Map[Int, Node]] =
    map(n, what).flatMap { kvs =>
      kvs
        .traverse { case (k, v) => uint(k, s"$what key").map(_.toInt -> v) }
        .flatMap(pairs =>
          Either
            .cond(pairs.map(_._1).distinct.size == pairs.size, pairs.toMap, s"$what: duplicate key")
        )
    }

  def rational(n: Node, what: String): Result[Rational] = n.value match
    case Value.Tag(t, inner) if t == 30 =>
      arrayN(inner, 2, what).flatMap(xs =>
        (uint(xs(0), what), uint(xs(1), what)).tupled.flatMap { case (num, den) =>
          Either.cond(den > 0, Rational(num, den), s"$what: zero denominator")
        }
      )
    case _ => Left(s"$what: expected tag-30 rational")
