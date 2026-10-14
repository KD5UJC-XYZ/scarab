// SPDX-License-Identifier: Apache-2.0
package lab.fuzz

import lab.cbor.Bytes
import munit.FunSuite

/** Deterministic guards that do not depend on the seed: the seeds are valid, and no decoder
  * allocates for a length that the input does not contain.
  */
class GuardSuite extends FunSuite:
  /** Targets whose seeds are valid items of the decoder: at least one must be accepted. */
  private val mustAccept = Targets.all.map(_.name).toSet -- Set(
    // no committed or sampled valid item reaches these
    "tx-whole-shelley",
    "tx-whole-allegra",
    "tx-whole-mary",
    "tx-body-dijkstra",
    "tx-witnesses-dijkstra",
    "tx-whole-dijkstra",
    "block-header-dijkstra",
    "tx-body-unused"
  )

  test("the seeds of every target are valid for it (otherwise the fuzzing stays at the surface)") {
    val cold = scala.collection.mutable.Buffer.empty[String]
    Targets.all.foreach { t =>
      t.corpus().foreach(c => Probe.run(t.name, new Input(c))(t.check(new Input(c))))
      val n = Option(Targets.accepted.get(t.name)).fold(0L)(_.sum())
      if n == 0 && mustAccept(t.name) then cold += t.name
    }
    assertEquals(cold.toList, Nil, "targets that accepted none of their seeds")
  }

  private def hex(s: String): Input = new Input(Bytes.fromHex(s).fold(e => fail(e), _.toArray))

  /** Short inputs whose heads declare 2^60 (or 2^63, 2^32) elements or bytes, alone and nested. */
  private val declaring: Vector[Input] =
    val counts = Vector(
      "1000000000000000",
      "ffffffffffffffff",
      "0000000100000000",
      "7fffffffffffffff",
      "00000000ffffffff"
    )
    for
      c <- counts
      major <- 2 to 6
      prefix <- Vector(
        "",
        "82",
        "8200",
        "83010a",
        "8200a1",
        "d818",
        "d9010280",
        "9f",
        "c2",
        "8280",
        "bf00"
      )
    yield hex(prefix + "%02x".format((major << 5) | 27) + c)

  test(
    "a short input declaring 2^60 elements or bytes fails fast, without allocating, in every decoder"
  ) {
    val limit = 4L * 1024 * 1024
    // two rounds: the first loads classes and builds caches
    (1 to 2).foreach { round =>
      for t <- Targets.all; in <- declaring do
        if round == 1 then Probe.run(t.name, in, Long.MaxValue)(t.check(in))
        else Probe.run(t.name, in, limit)(t.check(in))
    }
  }

  test(
    "every head in the seeds, rewritten to declare 2^60, 2^32 or 2^31 items, is refused without allocating"
  ) {
    Targets.all.foreach { t =>
      val seeds = t.corpus().sortBy(_.length).take(6)
      seeds.foreach { s =>
        for v <- Seq(1L << 60, 1L << 32, 1L << 31, 0x7fffffffL); m <- Mutate.inflated(s, v, 25) do
          val in = new Input(m)
          Probe.run(t.name, in)(t.check(in))
      }
    }
  }
