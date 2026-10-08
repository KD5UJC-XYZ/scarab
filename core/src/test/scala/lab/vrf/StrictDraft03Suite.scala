// SPDX-License-Identifier: Apache-2.0
package lab.vrf

import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, Executors, TimeUnit}
import lab.cbor.Bytes

class StrictDraft03Suite extends munit.FunSuite:
  import StrictDraft03.Result.*
  private val dir = Path.of("fixtures", "vrf")
  private def lines(name: String): Vector[String] =
    Files.readString(dir.resolve(name)).linesIterator.toVector
  private def bytes(s: String): Bytes = if s == "NULL" then null else Bytes.fromHex(s).toOption.get
  private def outcome(row: String): String =
    val p = row.split("\t", -1)
    StrictDraft03.verify(bytes(p(1)), bytes(p(2)), bytes(p(3))) match
      case Verified(out)        => "VALID:" + out.hex
      case Rejected(_)          => "INVALID"
      case MalformedEnvelope(_) => "MALFORMED"
      case other                => fail(other.toString)
  private lazy val vector = lines("vectors.tsv").head.split("\t", -1).drop(1).map(bytes)

  // Deterministic finite tests keep exhaustive work out of one 30-second MUnit budget.
  // No production timing policy or test timeout is changed.
  private val originalRows = lines("vectors.tsv")
  private val originalExpected = lines("expected.tsv")
  private val additionalRows = lines("additional.tsv")
  private val additionalExpected = lines("additional-results.tsv")
  private def registerCorpus(
      name: String,
      rows: Vector[String],
      expected: Vector[String],
      count: Int,
      validCount: Int,
      malformedCount: Int
  ): Unit =
    val batches = rows.indices.toVector.grouped(128).toVector
    test(s"$name exhaustive batch coverage: $count rows and exact outcome classes") {
      assertEquals(rows.size, count)
      assertEquals(expected.size, count)
      assertEquals(batches.size, (count + 127) / 128)
      assert(batches.forall(batch => batch.nonEmpty && batch.size <= 128))
      assertEquals(batches.flatten, rows.indices.toVector)
      assertEquals(batches.flatten.distinct.size, count)
      assertEquals(rows.map(_.takeWhile(_ != '\t')).distinct.size, count)
      assertEquals(expected.count(_.contains("\tVALID:")), validCount)
      assertEquals(expected.count(_.endsWith("\tMALFORMED")), malformedCount)
      assertEquals(expected.count(_.endsWith("\tINVALID")), count - validCount - malformedCount)
    }
    for (indices, batch) <- batches.zipWithIndex do
      test(
        s"$name exhaustive batch ${batch + 1}/${batches.size}: rows ${indices.head}..${indices.last}"
      ) {
        var valid = 0
        var invalid = 0
        var malformed = 0
        indices.foreach { index =>
          val row = rows(index)
          val actual = outcome(row)
          assertEquals(row.takeWhile(_ != '\t') + "\t" + actual, expected(index))
          if actual.startsWith("VALID:") then valid += 1
          else if actual == "INVALID" then invalid += 1
          else if actual == "MALFORMED" then malformed += 1
          else fail("unknown outcome class")
        }
        val slice = indices.map(expected)
        assertEquals(valid, slice.count(_.contains("\tVALID:")))
        assertEquals(invalid, slice.count(_.endsWith("\tINVALID")))
        assertEquals(malformed, slice.count(_.endsWith("\tMALFORMED")))
      }
  registerCorpus("original source-oracle", originalRows, originalExpected, 2048, 3, 27)
  registerCorpus(
    "additional source-oracle (including 2021 repeated rechecks)",
    additionalRows,
    additionalExpected,
    2338,
    3,
    0
  )
  for (op, count) <- Vector("d" -> 612, "u" -> 519, "e" -> 240, "t" -> 2055) do
    test(s"archived instrumented native helper $op: $count comparisons") {
      val all = lines("helpers.tsv")
      val expected = lines("helper-results.tsv")
      assertEquals(all.size, 3426)
      assertEquals(expected.size, all.size)
      val rows = all.zip(expected).filter(_._1.split("\t", -1)(1) == op)
      assertEquals(rows.size, count)
      rows.foreach { case (row, exp) =>
        assertEquals(row.takeWhile(_ != '\t') + "\t" + Helpers.evaluate(row), exp)
      }
    }
  test("inputs and output are immutable and arrays crossing Bytes are owned") {
    val pk = vector(0).toArray
    val key = Bytes.fromArray(pk)
    val result = StrictDraft03.verify(key, vector(1), vector(2))
    pk(0) = (pk(0) ^ 1).toByte
    assertEquals(key, vector(0))
    val output = result match
      case Verified(out) => out
      case other         => fail(other.toString)
    val arr = output.toArray
    arr(0) = (arr(0) ^ 1).toByte
    assert(!java.util.Arrays.equals(arr, output.toArray))
    assertEquals(StrictDraft03.verify(key, vector(1), vector(2)), result)
  }
  test("null and wrong size inputs are malformed, alpha is explicitly bounded") {
    for key <- Vector(null, Bytes.empty, Bytes(Vector.fill(33)(0.toByte))) do
      assert(StrictDraft03.verify(key, vector(1), vector(2)).isInstanceOf[MalformedEnvelope])
    for proof <- Vector(null, Bytes.empty, Bytes(Vector.fill(81)(0.toByte))) do
      assert(StrictDraft03.verify(vector(0), proof, vector(2)).isInstanceOf[MalformedEnvelope])
    assert(StrictDraft03.verify(vector(0), vector(1), null).isInstanceOf[MalformedEnvelope])
    assert(
      StrictDraft03
        .verify(vector(0), vector(1), Bytes(Vector.fill(StrictDraft03.MaxAlphaBytes + 1)(0.toByte)))
        .isInstanceOf[MalformedEnvelope]
    )
    assert(
      StrictDraft03
        .verify(vector(0), vector(1), Bytes(Vector.fill(StrictDraft03.MaxAlphaBytes)(0.toByte)))
        .isInstanceOf[Rejected]
    )
  }
  test("canonical scalar boundary L is rejected, L-1 reaches challenge") {
    for (s, reason) <- Vector(
        Draft03Math.L -> "noncanonical scalar",
        Draft03Math.L.subtract(java.math.BigInteger.ONE) -> "challenge mismatch"
      )
    do
      val proof = Bytes(vector(1).value.take(48) ++ Draft03Math.scalar(s).toVector)
      assertEquals(StrictDraft03.verify(vector(0), proof, vector(2)), Rejected(reason))
  }
  test("negative-zero Gamma decodes and original bytes change challenge transcript") {
    val raw = bytes("01" + "00" * 30 + "80").toArray
    val gamma = Ed25519Point.decode(raw)
    assert(gamma != null)
    assertEquals(gamma, Ed25519Point.NEUTRAL)
    val b = Ed25519Point.BASE_POINT
    assert(
      !java.util.Arrays
        .equals(Draft03Math.challenge(b, raw, b, b), Draft03Math.challenge(b, gamma.encode(), b, b))
    )
    val proof = Bytes(raw.toVector ++ vector(1).value.drop(32))
    assertEquals(StrictDraft03.verify(vector(0), proof, vector(2)), Rejected("challenge mismatch"))
  }
  test("mixed-order scalar-negation multiplication differs from naive point subtraction") {
    val p = Ed25519Point.decode(bytes("95" + "99" * 31).toArray)
    val one = Draft03Math.scalar(java.math.BigInteger.ONE)
    val zero = new Array[Byte](32)
    val cn = Draft03Math.equation(p, Ed25519Point.BASE_POINT, one, zero)
    assert(!java.util.Arrays.equals(cn.encode(), p.negate().encode()))
  }
  test("unexpected nonfatal provider error stays InternalFailure") {
    assertEquals(
      StrictDraft03.verifyWith(
        vector(0),
        vector(1),
        vector(2),
        (_, _) => throw new IllegalStateException("injected")
      ),
      InternalFailure("java.lang.IllegalStateException", "injected")
    )
  }
  test("fatal provider errors are never disguised as rejection") {
    var propagated = false
    try {
      StrictDraft03.verifyWith(
        vector(0),
        vector(1),
        vector(2),
        (_, _) => throw new LinkageError("injected")
      )
    } catch case _: LinkageError => propagated = true
    assert(propagated)
  }
  test("malformed Bytes construction is an internal programming failure") {
    assert(StrictDraft03.verify(Bytes(null), vector(1), vector(2)).isInstanceOf[InternalFailure])
  }
  test("104 separately implemented affine arithmetic and decoder checks") {
    val rows = lines("affine-inputs.tsv")
    val expected = lines("affine-results.tsv")
    assertEquals(rows.size, 104)
    assertEquals(expected.size, rows.size)
    rows.zip(expected).foreach { case (row, exp) =>
      assertEquals(row.takeWhile(_ != '\t') + "\t" + Helpers.evaluate(row), exp)
    }
  }
  test("parallel public verification has no shared mutable digest state") {
    val pool = Executors.newFixedThreadPool(4)
    try
      val tasks = Vector.fill(16)(
        pool.submit(
          new Callable[StrictDraft03.Result]:
            def call(): StrictDraft03.Result = StrictDraft03.verify(vector(0), vector(1), vector(2))
        )
      )
      val expected = StrictDraft03.verify(vector(0), vector(1), vector(2))
      tasks.foreach(t => assertEquals(t.get(10, TimeUnit.SECONDS), expected))
    finally pool.shutdownNow()
  }
