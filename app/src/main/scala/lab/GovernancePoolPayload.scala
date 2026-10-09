// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{ConwayEmptyGovernance as G, ConwayStake as S}
import scala.util.control.NonFatal

/** Source-preserving, bounded canonical subset of PV9 StakePoolState, not PoolParams. Pinned
  * core-1.21.0.0 State/StakePool.hs encodes ten fields; AccountId is a credential, not a
  * network-bearing reward address. Binary-1.9.1.0 emits tag 258 for sets at PV9. Definite canonical
  * collections and duplicate-free sets are required here; this is not a complete native decoder or
  * proof of pool registration, ledger admission, or source authority.
  */
private[lab] object GovernancePoolPayload:
  private val Max = (BigInt(1) << 64) - 1
  private val MaxBytes = 65536
  private val MaxEntries = 4096
  enum Relay:
    case SingleHostAddr(port: Option[Int], ipv4: Option[Bytes], ipv6: Option[Bytes])
    case SingleHostName(port: Option[Int], dns: String)
    case MultiHostName(dns: String)
  final case class Metadata(url: String, hash: Bytes)
  final class Checked private[GovernancePoolPayload] (
      val original: Bytes,
      val payload: G.Payload,
      val pool: S.Pool,
      val relays: Vector[Relay],
      val metadata: Option[Metadata],
      val fieldOriginals: Vector[Bytes],
      val sha256: Bytes
  ):
    val ledgerAdmitted = false
    val nativeConformance = false

  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def fail(message: String): Nothing = throw new IllegalArgumentException(message)
  private def arr(n: Node, count: Int): Vector[Node] = n.value match
    case V.Arr(xs) if xs.size == count => xs
    case _                             => fail("pool payload array shape")
  private def uint(n: Node, maximum: BigInt = Max): BigInt = n.value match
    case V.UInt(v) if v >= 0 && v <= maximum => v
    case _                                   => fail("pool payload unsigned bound/shape")
  private def bytes(n: Node, length: Int): Bytes = n.value match
    case V.ByteString(v) if v.size == length => v
    case _                                   => fail("pool payload byte width/shape")
  private def text(n: Node): String = n.value match
    case V.Text(v)
        if v.length <= 128 && v.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 128 =>
      v
    case _ => fail("pool payload URL/DNS text bound/shape")
  private def credential(n: Node): S.Credential =
    val xs = arr(n, 2)
    S.Credential(uint(xs(0), 1) == 1, bytes(xs(1), 28))
  private def ratio(n: Node): S.Ratio = n.value match
    case V.Tag(t, value) if t == 30 =>
      val xs = arr(value, 2)
      val a = uint(xs(0))
      val b = uint(xs(1))
      require(b > 0 && a <= b && a.gcd(b) == 1, "pool payload reduced unit interval")
      S.Ratio(a, b)
    case _ => fail("pool payload rational tag")
  private def set[A](n: Node)(read: Node => A): Set[A] = n.value match
    case V.Tag(t, value) if t == 258 =>
      value.value match
        case V.Arr(xs) if xs.size <= MaxEntries =>
          val values = xs.map(read)
          require(values.distinct.size == values.size, "pool payload duplicate set member")
          values.toSet
        case _ => fail("pool payload set shape/bound")
    case _ => fail("pool payload PV9 set tag")
  private def nullable[A](n: Node)(read: Node => A): Option[A] = n.value match
    case V.Null => None
    case _      => Some(read(n))
  private def port(n: Node): Int = uint(n, 65535).toInt
  private def relay(n: Node): Relay = n.value match
    case V.Arr(xs) if xs.nonEmpty =>
      uint(xs.head, 2).toInt match
        case 0 =>
          val a = arr(n, 4)
          Relay.SingleHostAddr(
            nullable(a(1))(port),
            nullable(a(2))(bytes(_, 4)),
            nullable(a(3))(bytes(_, 16))
          )
        case 1 =>
          val a = arr(n, 3)
          Relay.SingleHostName(nullable(a(1))(port), text(a(2)))
        case 2 => Relay.MultiHostName(text(arr(n, 2)(1)))
    case _ => fail("pool payload relay shape")
  private def metadata(n: Node): Option[Metadata] = n.value match
    case V.Arr(xs) if xs.isEmpty => None
    case V.Arr(xs) if xs.size == 1 =>
      val a = arr(xs.head, 2)
      val hash = a(1).value match
        // Native PoolMetadata uses ByteArray, not a fixed-width hash decoder.
        case V.ByteString(v) => v
        case _               => fail("pool metadata hash shape")
      Some(Metadata(text(a(0)), hash))
    case _ => fail("pool metadata StrictMaybe shape")

  def decode(original: Bytes, expectedSha256: Bytes): Either[String, Checked] = checked {
    require(
      original != null && original.value != null && original.size <= MaxBytes,
      "pool payload byte bound"
    )
    require(
      expectedSha256 != null && expectedSha256.value != null && expectedSha256.size == 32,
      "pool payload digest width"
    )
    val hash = ClusterHeaderObservation.sha256(original)
    require(hash == expectedSha256, "pool payload digest mismatch")
    val payload = get(G.payload(original))
    val root = get(Cbor.decode(original, Cbor.Limits(MaxBytes, 16, 8192, MaxBytes)))
    val a = arr(root, 10)
    val rs = a(6).value match
      case V.Arr(xs) if xs.size <= MaxEntries => xs.map(relay)
      case _                                  => fail("pool payload relay collection")
    val pool = S.Pool(
      bytes(a(0), 32),
      uint(a(1)),
      uint(a(2)),
      ratio(a(3)),
      credential(a(4)),
      set(a(5))(bytes(_, 28)),
      set(a(9))(credential),
      uint(a(8))
    )
    new Checked(original, payload, pool, rs, metadata(a(7)), a.map(_.original), hash)
  }

  def checkProjection(value: Checked, expectedPool: S.Pool): Either[String, Unit] = checked {
    require(
      value != null && expectedPool != null && value.pool == expectedPool,
      "pool payload projection mismatch"
    )
  }
