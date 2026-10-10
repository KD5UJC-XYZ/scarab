// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import scala.util.control.NonFatal

/** Lossless, bounded state-output view for the opt-in reviewed Plutus profile. This is structural
  * state decoding, not spending authorization or ledger validity. Datumless kinds 0/6/7 retain the
  * old native state subset; inline data is kind 7 only.
  */
object PlutusOutput:
  val MaxOutputBytes = 4096
  val MaxDatumBytes = 256
  val MaxSnapshotBytes = 1048576
  private val Max = (BigInt(1) << 64) - 1
  final class Datum private[PlutusOutput] (
      val original: Bytes,
      val beneficiary: Bytes,
      val minimumPayment: BigInt
  )
  final class Output private[PlutusOutput] (
      val original: Bytes,
      val address: Bytes,
      val coin: BigInt,
      val datum: Option[Datum]
  ):
    val kind: Int = (address.value.head & 255) >>> 4
    val paymentCredential: Bytes = Bytes(address.value.slice(1, 29))
    val fullLedgerValidated = false
    def stakeCredential: Option[ConwayStake.Credential] =
      Option.when(kind == 0)(ConwayStake.Credential(false, Bytes(address.value.drop(29))))
  final class Snapshot private[PlutusOutput] (
      val original: Bytes,
      val outputs: Map[TxIn, Output],
      val networkId: Int
  ):
    val fullLedgerValidated = false

  private def checked[A](a: => A): Either[String, A] =
    try Right(a)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](a: Either[?, A]): A =
    a.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def parse(b: Bytes, maximum: Int): Node =
    require(b != null && b.value != null && b.size <= maximum, "bounded non-null CBOR required")
    get(Cbor.decode(b, Cbor.Limits(maximum, 32, 4096, maximum)))
  private def uint(n: Node): BigInt = n.value match
    case V.UInt(x) if x >= 0 && x <= Max => x
    case _ => throw new IllegalArgumentException("uint64 amount required")
  private def definite(n: Node): Bytes = n.value match
    case V.ByteString(b) if n.original.value.nonEmpty && (n.original.value.head & 31) != 31 => b
    case _ => throw new IllegalArgumentException("definite bytes required")

  def decodeDatum(original: Bytes): Either[String, Datum] = checked {
    val root = parse(original, MaxDatumBytes)
    val (beneficiary, minimum) = root.value match
      case V.Tag(tag, data) if tag == 121 =>
        data.value match
          case V.Arr(Vector(b, m)) => (definite(b), uint(m))
          case _ =>
            throw new IllegalArgumentException("Constr 0 requires beneficiary and minimum payment")
      case _ =>
        throw new IllegalArgumentException("only tag-121 reviewed datum constructor supported")
    require(beneficiary.size == 28, "beneficiary credential width")
    new Datum(original, beneficiary, minimum)
  }

  def decode(original: Bytes, networkId: Int): Either[String, Output] = checked {
    require(networkId == 0 || networkId == 1, "checked Cardano network id required")
    val root = parse(original, MaxOutputBytes)
    val (a, v, d) = root.value match
      case V.Arr(Vector(a, v)) => (a, v, None)
      case V.Map(fields) =>
        val keys = fields.map(_._1.value)
        require(
          keys.distinct.size == keys.size &&
            (keys.toSet == Set(V.UInt(0), V.UInt(1)) || keys.toSet == Set(
              V.UInt(0),
              V.UInt(1),
              V.UInt(2)
            )),
          "only unique address/coin/optional-inline-datum fields supported"
        )
        val fs = fields.map((k, v) => k.value -> v).toMap
        (fs(V.UInt(0)), fs(V.UInt(1)), fs.get(V.UInt(2)))
      case _ => throw new IllegalArgumentException("unsupported output shape")
    val addr = definite(a)
    require(addr.size > 0, "empty address")
    val kind = (addr.value.head & 255) >>> 4
    require(
      (addr.value.head & 15) == networkId &&
        ((kind == 0 && addr.size == 57) || ((kind == 6 || kind == 7) && addr.size == 29)),
      "unsupported state address kind/length/network"
    )
    val datum = d.map { node =>
      require(kind == 7, "inline datum requires enterprise script address")
      val embedded = node.value match
        case V.Arr(Vector(choice, tagged)) if choice.value == V.UInt(1) =>
          tagged.value match
            case V.Tag(tag, payload) if tag == 24 => definite(payload)
            case _ => throw new IllegalArgumentException("inline datum requires tag 24 bytes")
        case _ => throw new IllegalArgumentException("datum hash or unsupported datum option")
      get(decodeDatum(embedded))
    }
    new Output(original, addr, uint(v), datum)
  }

  /** Original complete map is the checkpoint image: no re-encoding of output or datum spans. */
  def snapshot(original: Bytes, networkId: Int): Either[String, Snapshot] = checked {
    require(networkId == 0 || networkId == 1, "checked Cardano network id required")
    val fields = parse(original, MaxSnapshotBytes).value match
      case V.Map(fs) if fs.size <= 4096 => fs
      case _ => throw new IllegalArgumentException("bounded UTxO map required")
    val pairs = fields.map { (key, out) =>
      val ref = key.value match
        case V.Arr(Vector(id, index)) => get(TxIn.create(definite(id), uint(index)))
        case _ => throw new IllegalArgumentException("UTxO input reference required")
      ref -> get(decode(out.original, networkId))
    }
    require(pairs.map(_._1).distinct.size == pairs.size, "duplicate semantic UTxO reference")
    new Snapshot(original, pairs.toMap, networkId)
  }
