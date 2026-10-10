// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.Bytes
import scala.util.control.NonFatal

/** Bounded experimental JVM generation; recorded finite parity is not universal pow/log proof. */
object ConwayLikelihoodGeneration:
  val Profile = "conway-jvm-likelihood-result-v1"
  private[ledger] final class Calculation private[ConwayLikelihoodGeneration] (
      val rows: Vector[(Bytes, Long, Vector[Int])],
      val original: Bytes
  )
  private[ledger] def calculate(
      request: ConwayNativeLikelihood.Request
  ): Either[String, Calculation] =
    try
      require(request != null, "captured likelihood request required")
      val rows = request.rows.map { (pool, stake, circulation, blocks) =>
        val probability =
          1.0 - java.lang.Math.pow(1.0 - 1.0 / 20.0, stake.toDouble / circulation.toDouble)
        require(java.lang.Double.isFinite(probability), "nonfinite JVM probability")
        val words = Vector.tabulate(100) { i =>
          val x = (i.toDouble + 0.5) / 100.0
          val value = (blocks.toDouble * java.lang.Math.log(x) +
            (1000 - blocks).toDouble * java.lang.Math.log(1.0 - probability * x)).toFloat
          require(java.lang.Float.isFinite(value), "nonfinite JVM likelihood")
          java.lang.Float.floatToRawIntBits(value)
        }
        (pool, java.lang.Double.doubleToRawLongBits(probability), words)
      }
      val output = s"$Profile\n" + new String(request.original.value.toArray, "US-ASCII") +
        "--jvm--\n" + rows.map { (pool, probability, words) =>
          pool.hex + " " + f"$probability%016x" + " " + words.map(w => f"$w%08x").mkString + "\n"
        }.mkString
      Right(new Calculation(rows, Bytes.fromArray(output.getBytes("US-ASCII"))))
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))

  /** Exact captured inputs and canonical JVM output are retained for independent post-run audit. No
    * native bytes, executable, boolean attestation or fallback can influence this calculation.
    */
  def generateJvm(
      frozen: ConwayEpochBoundary.Frozen,
      expectedId: Bytes
  ): Either[String, ConwayNativeLikelihood.Generated] =
    ConwayNativeLikelihood.generatePureJvm(frozen, expectedId)
