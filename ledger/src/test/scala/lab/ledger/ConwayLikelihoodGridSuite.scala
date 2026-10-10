// SPDX-License-Identifier: Apache-2.0
package lab.ledger

/** Finite supplied arithmetic observations; no runtime generator or freeze authority. */
class ConwayLikelihoodGridSuite extends munit.FunSuite:
  private val stream = getClass.getResourceAsStream("/non-myopic-grid/native-result.json")
  require(stream != null, "native grid resource missing")
  private val raw =
    try stream.readNBytes(65537)
    finally stream.close()
  private val sha = java.security.MessageDigest
    .getInstance("SHA-256")
    .digest(raw)
    .map(b => f"${b & 255}%02x")
    .mkString
  require(
    sha == "2774bb0bcb85ad4ac6187067e8eb7fc3d57cfd65ae1ebedbb74415541d76401a",
    "exact complete native grid required"
  )
  private val text = new String(raw, java.nio.charset.StandardCharsets.UTF_8)
  // This fixed-file extraction follows the complete golden digest check, not a native decoder.
  private val vectors = "\"weights\":\\[(.*?)\\]".r
    .findAllMatchIn(text)
    .map(m => "[0-9a-f]{8}".r.findAllIn(m.group(1)).toVector)
    .toVector
  private val probabilities =
    "\"leaderProbabilityBits\":\"([0-9a-f]{16})\"".r.findAllMatchIn(text).map(_.group(1)).toVector
  private val identifiers = "\"id\":\"([^\"]+)\"".r.findAllMatchIn(text).map(_.group(1)).toVector
  private val inputs = for denominator <- Vector(3, 6); blocks <- Vector(0, 1, 4, 50)
  yield (denominator, blocks)
  require(
    vectors.size == 8 && vectors.forall(_.size == 100) && probabilities.size == 8,
    "exact eight100-word probes"
  )
  require(identifiers == inputs.map((d, b) => s"sigma-1-$d-blocks-$b"), "native case ordering")
  private def bits(d: Double): String = f"${java.lang.Double.doubleToRawLongBits(d)}%016x"
  private def words(blocks: Int, probability: Double, log: Double => Double): Vector[String] =
    Vector.tabulate(100) { i =>
      val x = (i.toDouble + 0.5) / 100.0
      val first = blocks.toDouble * log(x)
      val second = (1000 - blocks).toDouble * log(1.0 - probability * x)
      f"${java.lang.Float.floatToRawIntBits((first + second).toFloat)}%08x"
    }
  private def compare(label: String, actual: Vector[String], expected: Vector[String]): Unit =
    val mismatch =
      actual.zip(expected).zipWithIndex.collect { case ((a, e), i) if a != e => s"$i:$e:$a" }
    println(
      s"LIKELIHOOD_GRID $label mismatches=${mismatch.size} rows=${mismatch.mkString(",")} actual=${actual.mkString(",")}"
    )
    assertEquals(actual, expected, label)

  for
    ((denominator, blocks), index) <- inputs.zipWithIndex
    (name, log, pow) <- Vector[(String, Double => Double, (Double, Double) => Double)](
      ("Math", java.lang.Math.log, java.lang.Math.pow),
      ("StrictMath", java.lang.StrictMath.log, java.lang.StrictMath.pow)
    )
  do
    test(s"injected $name sigma1/$denominator blocks$blocks: all100 native raw bits") {
      val native = java.lang.Double.longBitsToDouble(
        java.lang.Long.parseUnsignedLong(probabilities(index), 16)
      )
      compare(s"A/$name/${identifiers(index)}", words(blocks, native, log), vectors(index))
    }
    test(
      s"computed $name sigma1/$denominator blocks$blocks: probability and all100 native raw bits"
    ) {
      val computed = (1.0 - pow(1.0 - (1.0 / 20.0), 1.0 / denominator.toDouble)) * (1.0 - 0.0)
      println(
        s"LIKELIHOOD_GRID B/$name/${identifiers(index)} actualProbability=${bits(computed)} expectedProbability=${probabilities(index)}"
      )
      compare(s"B/$name/${identifiers(index)}", words(blocks, computed, log), vectors(index))
      assertEquals(bits(computed), probabilities(index))
    }
