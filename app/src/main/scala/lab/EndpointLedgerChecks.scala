// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.header.PraosCertificateState as Certificate
import lab.ledger.{ConwayEmptyGovernance as G, ConwayStake as S}
import scala.util.control.NonFatal

/** Shared finite endpoint comparison primitives; no general ledger acceptance. */
private[lab] object EndpointLedgerChecks:
  private val Max = (BigInt(1) << 64) - 1
  private def get[A](v: Either[?, A]): A =
    v.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def protect[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def parse(b: Bytes): Node =
    get(Cbor.decode(b, Cbor.Limits(524288, 48, 100000, 524288)))
  private def arr(n: Node, size: Int): Vector[Node] = n.value match
    case V.Arr(xs) if xs.size == size => xs
    case _                            => throw new IllegalArgumentException("endpoint array shape")
  private def uint(n: Node): BigInt = n.value match
    case V.UInt(v) if v >= 0 && v <= Max => v
    case _                               => throw new IllegalArgumentException("endpoint uint64")
  private def bytes(n: Node, size: Int): Bytes = n.value match
    case V.ByteString(v) if v.size == size => v
    case _ => throw new IllegalArgumentException("endpoint hash width")
  private def canonical(n: Node): Bytes = get(Cbor.encode(n.value))
  private def map(n: Node): Vector[(Node, Node)] = n.value match
    case V.Map(xs) if xs.size <= 4096 =>
      require(xs.map((k, _) => canonical(k)).distinct.size == xs.size, "endpoint duplicate map key")
      xs
    case _ => throw new IllegalArgumentException("endpoint map bound/shape")
  private def credential(n: Node): S.Credential =
    val a = arr(n, 2); val tag = uint(a(0))
    require(tag <= 1, "endpoint credential tag")
    S.Credential(tag == 1, bytes(a(1), 28))
  private def ratio(n: Node): S.Ratio = n.value match
    case V.Tag(t, inner) if t == 30 =>
      val a = arr(inner, 2); val x = uint(a(0)); val y = uint(a(1))
      require(y > 0 && x <= y && x.gcd(y) == 1, "endpoint exact ratio")
      S.Ratio(x, y)
    case _ => throw new IllegalArgumentException("endpoint ratio encoding")
  private def set[A](n: Node)(f: Node => A): Set[A] = n.value match
    case V.Tag(t, inner) if t == 258 =>
      inner.value match
        case V.Arr(xs) if xs.size <= 4096 =>
          val values = xs.map(f)
          require(values.distinct.size == values.size, "endpoint duplicate set entry")
          values.toSet
        case _ => throw new IllegalArgumentException("endpoint set shape")
    case _ => throw new IllegalArgumentException("endpoint PV9 set")
  private def empty(n: Node, label: String): Unit =
    require(map(n).isEmpty, "endpoint unsupported nonempty " + label)
  private def seqEmpty(n: Node, label: String): Unit =
    require(
      n.value match {
        case V.Arr(xs) => xs.isEmpty
        case _         => false
      },
      "endpoint unsupported " + label
    )
  private def path(n: Node, indexes: Int*): Node = indexes.foldLeft(n) { (at, i) =>
    at.value match
      case V.Arr(xs) if i >= 0 && i < xs.size => xs(i)
      case _ => throw new IllegalArgumentException("endpoint source path")
  }

  /** Every non-UTxO sibling must remain the exact original span, not a re-encoding. */
  private[lab] def checkReplacement(seed: Bytes, debug: Bytes, whole: Bytes): Either[String, Unit] =
    protect {
      val a = parse(seed); val b = parse(debug)
      def descend(left: Node, right: Node, remaining: List[Int]): Unit = remaining match
        case Nil =>
          empty(right, "debug UTxO placeholder")
          val unpacked = get(NativeCoinUtxoMemPack.decode(left.original))
          require(
            get(utxoSemantics(unpacked)) == get(utxoSemantics(whole)),
            "endpoint mempack/whole UTxO mismatch"
          )
        case index :: rest =>
          val l = left.value match
            case V.Arr(v) => v
            case _        => throw new IllegalArgumentException("endpoint replacement array")
          val r = arr(right, l.size)
          require(index < l.size, "endpoint replacement path")
          l.indices
            .filter(_ != index)
            .foreach(i =>
              require(l(i).original == r(i).original, "endpoint changed non-UTxO original")
            )
          descend(l(index), r(index), rest)
      descend(a, b, List(3, 1, 1, 0))
    }

  private[lab] def utxoSemantics(raw: Bytes) = protect {
    get(S.decodeUtxo(raw)).map { (input, out) =>
      val pair = parse(out.original).value match
        case V.Arr(Vector(address, coin)) => (address, coin)
        case V.Map(fields) if fields.size == 2 =>
          (fields.find(_._1.value == V.UInt(0)).get._2, fields.find(_._1.value == V.UInt(1)).get._2)
        case _ => throw new IllegalArgumentException("endpoint coin-only TxOut shape")
      require(uint(pair._2) == out.coin, "endpoint coin-only value required")
      val address = pair._1.value match
        case V.ByteString(b) => b
        case _               => throw new IllegalArgumentException("endpoint output address")
      input -> (out.credential, out.coin, address)
    }
  }

  private[lab] def checkSnapshot(raw: Bytes, expected: S.Snapshot): Either[String, Unit] = protect {
    val a = arr(parse(raw), 2)
    val active = map(a(0)).map { (k, v) =>
      val x = arr(v, 2); credential(k) -> S.Active(uint(x(0)), bytes(x(1), 28))
    }.toMap
    val pools = map(a(1)).map { (k, v) =>
      val x = arr(v, 10); val count = uint(x(8))
      require(count <= Int.MaxValue, "endpoint delegator count")
      bytes(k, 28) -> S.PoolSnapshot(
        uint(x(0)),
        ratio(x(1)),
        set(x(2))(bytes(_, 28)),
        uint(x(3)),
        bytes(x(4), 32),
        uint(x(5)),
        uint(x(6)),
        ratio(x(7)),
        count.toInt,
        credential(x(9))
      )
    }.toMap
    require(
      active == expected.active && pools == expected.pools &&
        active.values.map(_.coin).sum.max(BigInt(1)) == expected.total,
      "endpoint snapshot mismatch"
    )
  }
