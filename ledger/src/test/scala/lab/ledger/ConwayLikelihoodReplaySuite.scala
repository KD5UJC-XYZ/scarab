// SPDX-License-Identifier: Apache-2.0
package lab.ledger

/** Test-only arithmetic replay, never an admitted likelihood generator. */
class ConwayLikelihoodReplaySuite extends munit.FunSuite:
  private val in = getClass.getResourceAsStream("/non-myopic/native-result.json")
  private val raw =
    try in.readNBytes(1048577)
    finally in.close()
  private val digest = java.security.MessageDigest
    .getInstance("SHA-256")
    .digest(raw)
    .map(b => f"${b & 255}%02x")
    .mkString
  require(
    digest == "e336935edca7af26f79c4497074e16ad72d3bcf92075adcb372f92950efce214",
    "exact native golden required"
  )
  // Extraction is safe only after exact complete-file identity; no permissive native decoder.
  private val section =
    new String(raw, java.nio.charset.StandardCharsets.UTF_8).split("\"generationProbes\":", -1)(1)
  private val vectors = "\"weights\":\\[(.*?)\\]".r
    .findAllMatchIn(section)
    .take(3)
    .map(m => "[0-9a-f]{8}".r.findAllIn(m.group(1)).toVector)
    .toVector
  require(vectors.size == 3 && vectors.forall(_.size == 100), "three exact native probes")
  private val nativeProbability =
    java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong("3f702126612e5f00", 16))
  private def bits(value: Double) = f"${java.lang.Double.doubleToRawLongBits(value)}%016x"
  private def sample(blocks: Int, probability: Double, log: Double => Double): Vector[String] =
    Vector.tabulate(100) { index =>
      val x = (index.toDouble + 0.5) / 100.0
      val first = blocks.toDouble * log(x)
      val second = (500 - blocks).toDouble * log(1.0 - probability * x)
      val value = (first + second).toFloat
      f"${java.lang.Float.floatToRawIntBits(value)}%08x"
    }
  private def compare(label: String, actual: Vector[String], expected: Vector[String]): Unit =
    val mismatches =
      actual.zip(expected).zipWithIndex.collect { case ((a, e), i) if a != e => s"$i:$e:$a" }
    println(
      s"LIKELIHOOD_REPLAY $label mismatches=${mismatches.size} rows=${mismatches.mkString(",")} actual=${actual.mkString(",")}"
    )
    assertEquals(actual, expected, label)

  for (name, log, pow) <- Vector[(String, Double => Double, (Double, Double) => Double)](
      ("Math", java.lang.Math.log, java.lang.Math.pow),
      ("StrictMath", java.lang.StrictMath.log, java.lang.StrictMath.pow)
    )
  do
    test(s"stage A $name logs with injected native probability match all100 raw Float bits") {
      compare(s"A/$name/blocks1", sample(1, nativeProbability, log), vectors(1))
    }
    test(s"stage B $name probability and all100 raw Float bits match native") {
      val probability = (1.0 - pow(1.0 - (1.0 / 20.0), 1.0 / 13.0)) * (1.0 - 0.0)
      println(
        s"LIKELIHOOD_REPLAY B/$name actualProbability=${bits(probability)} expectedProbability=${bits(nativeProbability)}"
      )
      compare(s"B/$name/blocks1", sample(1, probability, log), vectors(1))
      assertEquals(bits(probability), bits(nativeProbability))
    }
    test(s"$name zero-block and zero-stake controls preserve exact raw bits") {
      compare(s"A/$name/blocks0", sample(0, nativeProbability, log), vectors(0))
      val zero = (1.0 - pow(1.0 - (1.0 / 20.0), 0.0)) * (1.0 - 0.0)
      assertEquals(bits(zero), "0000000000000000")
      compare(s"B/$name/zeroStake", sample(0, zero, log), vectors(2))
    }
