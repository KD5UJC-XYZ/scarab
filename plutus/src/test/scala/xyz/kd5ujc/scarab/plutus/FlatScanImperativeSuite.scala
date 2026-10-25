// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import munit.FunSuite
import scala.util.Random

/** [[FlatScan.decode]] gives the result of the imperative walker it replaced
  * ([[FlatScanImperative]]), program and scan facts or failure, for every fixture script, every
  * prefix of one, and seeded single-bit changes of each, and keeps its stack on the heap (ADR
  * 0011).
  */
class FlatScanImperativeSuite extends FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private def onStack[A](size: Long)(body: => A): Either[Throwable, A] =
    val result = new java.util.concurrent.atomic.AtomicReference[Either[Throwable, A]]()
    val worker = new Thread(
      null,
      () =>
        result.set(
          try Right(body)
          catch case error: Throwable => Left(error)
        ),
      "flat-functional",
      size
    )
    worker.start()
    worker.join()
    result.get()

  /** Equality of a deep program recurses, so the comparisons run on a large stack. */
  private def onBigStack[A](body: => A): Either[Throwable, A] = onStack(1024L * 1024 * 1024)(body)

  private enum Outcome:
    case Accepted, Refused, Differs

  private def outcome(flat: Array[Byte]): Outcome =
    val imperative = FlatScanImperative.decode(flat)
    if imperative != FlatScan.decode(flat) then Outcome.Differs
    else if imperative.isRight then Outcome.Accepted
    else Outcome.Refused

  private def outcomes(cases: Vector[(String, Array[Byte])]): Vector[(String, Outcome)] =
    onBigStack(cases.map((id, flat) => id -> outcome(flat))).fold(e => fail(e.toString), identity)

  private def differing(results: Vector[(String, Outcome)]): Vector[String] =
    results.collect { case (id, Outcome.Differs) => id }

  private lazy val corpus: Vector[(String, Array[Byte])] =
    FlatFixtureScripts.conformance ++ FlatFixtureScripts.fixtures ++ FlatFixtureScripts.probes

  /** Every prefix of a script up to 512 bytes long, and 512 evenly spaced ones of a longer one. */
  private def prefixes(id: String, flat: Array[Byte]): Vector[(String, Array[Byte])] =
    val lengths =
      if flat.length <= 512 then (0 until flat.length).toVector
      else (0 until 512).map(i => (i.toLong * flat.length / 512).toInt).distinct.toVector
    lengths.map(n => s"$id[..$n]" -> flat.take(n))

  /** Sixteen copies of a script, each with one bit changed at a position the seed picks. */
  private def flips(index: Int, id: String, flat: Array[Byte]): Vector[(String, Array[Byte])] =
    val random = Random(index.toLong)
    Vector.fill(16)(random.nextInt(flat.length * 8)).map { bit =>
      s"$id^$bit" -> flat.updated(bit / 8, (flat(bit / 8) ^ (0x80 >>> (bit % 8))).toByte)
    }

  test("the same result for every fixture script and the deep programs") {
    val results = outcomes(corpus ++ DeepFlatPrograms.all)
    assertEquals(differing(results), Vector.empty)
    val accepted = results.count(_._2 == Outcome.Accepted)
    val refused = results.count(_._2 == Outcome.Refused)
    // A shrinking count is a lost fixture; the probes include refusals of every kind they pin.
    assert(accepted > 2900, s"only $accepted programs compared")
    assert(refused > 20, s"only $refused refusals compared")
  }

  test("the same result for every prefix of every fixture script") {
    val results = outcomes(corpus.flatMap(prefixes))
    assertEquals(differing(results), Vector.empty)
    assert(results.count(_._2 == Outcome.Refused) > 100000, "too few refused prefixes")
  }

  test("the same result for seeded single-bit changes of every fixture script") {
    val results = outcomes(corpus.zipWithIndex.collect {
      case ((id, flat), index) if flat.nonEmpty => flips(index, id, flat)
    }.flatten)
    assertEquals(differing(results), Vector.empty)
    assert(results.count(_._2 == Outcome.Accepted) > 1000, "too few accepted changes")
    assert(results.count(_._2 == Outcome.Refused) > 1000, "too few refused changes")
  }

  test("the deep programs decode on a small stack") {
    DeepFlatPrograms.all.foreach { (id, flat) =>
      val decoded = onStack(256L * 1024)(FlatScan.decode(flat).isRight)
      assertEquals(decoded.left.map(_.getClass.getName), Right(true), id)
    }
  }
