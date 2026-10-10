// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import lab.ledger.ConwayStake as S
import ReferenceJson.Json as J
import RepeatedTerminalCbor.{Node, Value as V}
import scala.util.boundary
import scala.util.boundary.break

/** Typed, pure validation shared by the diagnostic terminal projection. */
private[lab] object RepeatedTerminalSupport:
  enum Failure:
    case Invalid(field: String, reason: String)
    case Cbor(field: String, error: RepeatedTerminalCbor.Failure)
    case Unsupported(feature: String)
    case Mismatch(component: String)
  type Scope = boundary.Label[Either[Failure, Nothing]]
  def reject(error: Failure)(using Scope): Nothing = break(Left(error))
  def ensure(test: Boolean, error: Failure)(using Scope): Unit = if !test then reject(error)
  def valid(test: Boolean, name: String)(using Scope): Unit =
    ensure(test, Failure.Invalid(name, "invalid shape, bound or binding"))
  def equal[A](actual: A, expected: A, name: String)(using Scope): Unit =
    ensure(actual == expected, Failure.Mismatch(name))
  def get[A](value: Either[?, A], name: String)(using Scope): A =
    value.fold(e => reject(Failure.Invalid(name, e.toString)), identity)
  def unwrap[A](value: Either[Failure, A])(using Scope): A = value.fold(reject, identity)
  def cbor[A](value: Either[RepeatedTerminalCbor.Failure, A], name: String)(using Scope): A =
    value.fold(e => reject(Failure.Cbor(name, e)), identity)
  def sha(raw: Bytes): Bytes = ClusterHeaderObservation.sha256(raw)
  val MaxUInt64: BigInt = (BigInt(1) << 64) - 1
  def uint(n: Node)(using Scope): BigInt = cbor(RepeatedTerminalCbor.unsigned(n), "uint64")
  def signed(n: Node)(using Scope): BigInt = cbor(RepeatedTerminalCbor.signed(n), "signed coin")
  def arr(n: Node, width: Int)(using Scope): Vector[Node] =
    cbor(RepeatedTerminalCbor.array(n, width), "array")
  def rows(n: Node)(using Scope): Vector[Node] = cbor(RepeatedTerminalCbor.rows(n), "array")
  def bytes(n: Node, width: Int)(using Scope): Bytes =
    cbor(RepeatedTerminalCbor.bytes(n, width), "bytes")
  def parse(raw: Bytes)(using Scope): Node = cbor(RepeatedTerminalCbor.decode(raw), "CBOR")
  def mapping[K, A](n: Node)(key: Node => K, value: Node => A)(using Scope): Map[K, A] =
    val pairs = n.value match
      case V.Map(xs) => xs.map((k, v) => key(k) -> value(v))
      case _         => reject(Failure.Invalid("map", "map required"))
    valid(pairs.size <= 4096 && pairs.map(_._1).distinct.size == pairs.size, "map keys/bound")
    pairs.toMap
  def emptyMap(n: Node, name: String)(using Scope): Unit = n.value match
    case V.Map(xs) if xs.isEmpty => ()
    case _                       => reject(Failure.Unsupported(name))
  def emptySeq(n: Node, name: String)(using Scope): Unit =
    ensure(rows(n).isEmpty, Failure.Unsupported(name))
  def credential(n: Node)(using Scope): S.Credential =
    val a = arr(n, 2); val kind = uint(a(0))
    valid(kind <= 1, "credential kind")
    S.Credential(kind == 1, bytes(a(1), 28))
  def ratio(n: Node)(using Scope): S.Ratio = n.value match
    case V.Tag(tag, inner) if tag == 30 =>
      val a = arr(inner, 2); val x = uint(a(0)); val y = uint(a(1))
      valid(y > 0 && x <= y && x.gcd(y) == 1, "reduced unit interval")
      S.Ratio(x, y)
    case _ => reject(Failure.Invalid("ratio", "tag 30 required"))
  def set[A](n: Node)(f: Node => A)(using Scope): Set[A] = n.value match
    case V.Tag(tag, inner) if tag == 258 =>
      val values = rows(inner).map(f)
      valid(values.distinct.size == values.size, "duplicate set member")
      values.toSet
    case _ => reject(Failure.Invalid("set", "PV9 tag 258 required"))
  def nullable[A](n: Node)(f: Node => A)(using Scope): Option[A] = n.value match
    case V.Null => None
    case _      => Some(f(n))
  def maybe[A](n: Node)(f: Node => A)(using Scope): Option[A] = rows(n) match
    case Vector()  => None
    case Vector(x) => Some(f(x))
    case _         => reject(Failure.Invalid("StrictMaybe", "zero or one element required"))
  def obj(j: J)(using Scope): Map[String, J] = j match
    case J.Obj(m) => m
    case _        => reject(Failure.Invalid("JSON", "object required"))
  def field(j: J, key: String)(using Scope): J =
    obj(j).getOrElse(key, reject(Failure.Invalid(key, "missing field")))
  def text(j: J)(using Scope): String = j match
    case J.Str(s) => s
    case _        => reject(Failure.Invalid("JSON", "string required"))
  def number(j: J)(using Scope): BigInt = j match
    case J.Num(s) if s.matches("0|[1-9][0-9]{0,19}") =>
      val n = BigInt(s); valid(n <= MaxUInt64, "JSON uint64"); n
    case _ => reject(Failure.Invalid("JSON", "canonical uint64 required"))
  def hash(j: J)(using Scope): Bytes =
    val s = text(j); valid(s.matches("[0-9a-f]{64}"), "hash spelling")
    get(Bytes.fromHex(s), "hash")
  def jsonRows(j: J)(using Scope): Vector[J] = j match
    case J.Arr(xs) if xs.size <= 4096 => xs
    case _                            => reject(Failure.Invalid("JSON", "bounded array required"))
  def record(fields: (String, J)*): J = J.Obj(fields.toMap)
  def num(n: BigInt): J = J.Num(n.toString)
  def str(s: String): J = J.Str(s)
  def bool(b: Boolean): J = J.Lit(b.toString)
  def option[A](x: Option[A])(f: A => J): J = x.fold[J](J.Lit("null"))(f)
  def cred(c: S.Credential): String = (if c.script then "script:" else "key:") + c.hash.hex
  def ratioJson(r: S.Ratio): J = J.Arr(Vector(num(r.numerator), num(r.denominator)))
  def mapJson[K, A](values: Map[K, A])(key: K => String, value: A => J): J =
    J.Obj(values.map((k, v) => key(k) -> value(v)))
  def credentialCoins(values: Map[S.Credential, BigInt]): J = mapJson(values)(cred, num)
  def poolCoins(values: Map[Bytes, BigInt]): J = mapJson(values)(_.hex, num)
