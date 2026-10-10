// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.plutus.PlutusExecution.Budget
import scala.util.control.NonFatal

/** Checked projection of an externally pinned complete PV9 parameter record. Hash equality is not
  * acquisition authentication; the owner must bind this record to its state/epoch source.
  * Genesis/time geometry and full protocol-parameter validity remain outside this component.
  */
object PlutusParameters:
  val ModelSHA256 = "6ab455d588e186649a6aae2761fec85ae5a2647cb002a2acb737604f698b21a2"
  private val Max = (BigInt(1) << 64) - 1
  private val SignedMax = (BigInt(1) << 63) - 1
  final class Checked private[PlutusParameters] (
      val original: Bytes,
      val sourceSHA256: Bytes,
      val modelText: Bytes,
      val modelValues: Vector[BigInt],
      val linear: FeeSize.Parameters,
      val minimumOutput: MinimumOutput.Parameters,
      val execution: PlutusFees.Parameters,
      val maxValueSize: BigInt
  ):
    val fullParameterValidity = false

  private def get[A](e: Either[?, A]): A =
    e.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def sha(b: Bytes): Bytes =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(b.toArray))
  private def bounded(b: Bytes, max: Int): Boolean =
    b != null && b.value != null && b.size > 0 && b.size <= max
  private def array(n: Node, width: Int): Vector[Node] = n.value match
    case V.Arr(xs) if xs.size == width => xs
    case _ => throw new IllegalArgumentException("parameter array width")
  private def uint(n: Node, maximum: BigInt = Max): BigInt = n.value match
    case V.UInt(v) if v >= 0 && v <= maximum => v
    case _ => throw new IllegalArgumentException("parameter unsigned bound")
  private def signed(n: Node): BigInt = n.value match
    case V.UInt(v) if v <= SignedMax      => v
    case V.NInt(v) if v >= -SignedMax - 1 => v
    case _ => throw new IllegalArgumentException("cost-model signed Int64 bound")
  private def price(n: Node): PlutusFees.Price = n.value match
    case V.Tag(tag, value) if tag == 30 =>
      val xs = array(value, 2)
      val a = uint(xs(0)); val b = uint(xs(1))
      require(b > 0 && a.gcd(b) == 1, "canonical nonnegative price rational")
      get(PlutusFees.price(a, b))
    case _ => throw new IllegalArgumentException("price tag30 required")
  private def budget(n: Node): Budget =
    val xs = array(n, 2)
    Budget(uint(xs(0), SignedMax), uint(xs(1), SignedMax))

  def decode(original: Bytes, expectedSHA256: Bytes, modelText: Bytes): Either[String, Checked] =
    try
      require(bounded(original, 262144), "bounded complete parameter bytes required")
      require(
        bounded(expectedSHA256, 32) && expectedSHA256.size == 32 &&
          sha(original) == expectedSHA256,
        "parameter source hash mismatch"
      )
      require(
        bounded(modelText, 16384) && sha(modelText).hex == ModelSHA256,
        "registered model bytes required before parsing"
      )
      // The exact hash fixes the grammar before text parsing; arbitrary JSON is never parsed here.
      val text = new String(modelText.toArray, UTF_8).trim
      val model =
        text.stripPrefix("[").stripSuffix("]").split(",").toVector.map(x => BigInt(x.trim))
      require(model.size == 251, "registered V3 model length")
      val fields = array(get(Cbor.decode(original, Cbor.Limits(262144, 12, 8192, 262144))), 31)
      val pv = array(fields(12), 2)
      require(uint(pv(0)) == 9 && uint(pv(1)) == 0, "PV9.0 required")
      val models = fields(15).value match
        case V.Map(entries) if entries.size <= 3 =>
          val rows = entries.map { (key, value) =>
            val language = uint(key, 2).toInt
            val length = Vector(166, 175, 251)(language)
            language -> array(value, length).map(signed)
          }
          require(rows.map(_._1).distinct.size == rows.size, "duplicate cost-model language")
          rows.toMap
        case _ => throw new IllegalArgumentException("bounded cost-model map required")
      require(models.get(2).contains(model), "acquired V3 model differs from registered evaluator")
      val prices = array(fields(16), 2)
      val execution = get(
        PlutusFees.parameters(
          price(prices(0)),
          price(prices(1)),
          budget(fields(17)),
          budget(fields(18)),
          uint(fields(20), 65535),
          uint(fields(21), 65535)
        )
      )
      val linear = get(
        FeeSize.Parameters.create(
          "Conway",
          9,
          uint(fields(0)),
          uint(fields(1)),
          uint(fields(3), (BigInt(1) << 32) - 1)
        )
      )
      val minimum = get(MinimumOutput.Parameters.checked("Conway", 9, 0, uint(fields(14))))
      Right(
        new Checked(
          original,
          expectedSHA256,
          modelText,
          model,
          linear,
          minimum,
          execution,
          uint(fields(19), (BigInt(1) << 32) - 1)
        )
      )
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
