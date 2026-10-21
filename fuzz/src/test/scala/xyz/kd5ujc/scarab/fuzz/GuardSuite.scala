// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.fuzz

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.ledger.{Address, Certificate, Tx, TxBody}
import xyz.kd5ujc.scarab.protocol.Era
import munit.FunSuite

/** Deterministic guards check seed acceptance and allocation bounds for representative malformed
  * length heads, independently of the random mutation seed.
  */
class GuardSuite extends FunSuite:
  test("early-era whole transactions have nonempty inputs and outputs decoded in their era") {
    for era <- Vector(Era.Shelley, Era.Allegra, Era.Mary) do
      val seeds = Corpus.transactionsOf(era)
      assert(seeds.nonEmpty, s"$era needs valid whole-transaction seeds")
      seeds.foreach { seed =>
        val bytes = Bytes.fromArray(seed)
        Cbor.decode(bytes).fold(e => fail(e.toString), _.value) match
          case Value.Arr(fields) => assertEquals(fields.size, 3, s"$era original envelope")
          case other             => fail(s"$era transaction is not an array: $other")
        assertEquals(
          Corpus.asTransaction(bytes, Era.Alonzo),
          None,
          s"$era is not a four-field transaction"
        )
        val parts = Corpus
          .asTransaction(bytes, era)
          .getOrElse(fail(s"$era seed is not a whole transaction"))
        val tx = Tx.fromBlock(parts, era).fold(e => fail(s"$era seed: $e"), identity)
        assert(tx.body.inputs.nonEmpty, s"$era seed has no inputs")
        assert(tx.body.outputs.nonEmpty, s"$era seed has no outputs")
      }
  }

  test("every era's transaction-body corpus includes both sides of the pool metadata hash limit") {
    for era <- Vector(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway) do
      val sizes = Corpus
        .txBodies(era)
        .flatMap { raw =>
          TxBody.decode(Bytes.fromArray(raw), Address.Mode.of(era)).toOption.toVector.flatMap {
            _.certificates.collect { case Certificate.RegisterPool(p) => p }.flatMap { p =>
              p.metadata.toVector.flatMap { encoded =>
                Cbor
                  .decode(encoded)
                  .toOption
                  .toVector
                  .flatMap(_.value match
                    case Value.Arr(Vector(_, hash)) =>
                      hash.value match
                        case Value.ByteString(bytes) => Vector(bytes.size)
                        case _                       => Vector.empty
                    case _ => Vector.empty)
              }
            }
          }
        }
        .toSet
      assert(sizes.contains(32) && sizes.contains(33), s"$era pool metadata fuzz seeds: $sizes")
  }

  test("every target accepts a seed from its corpus in this run") {
    val cold = Targets.all
      .filter { t =>
        val before = Option(Targets.accepted.get(t.name)).fold(0L)(_.sum())
        t.corpus().foreach(c => Probe.run(t.name, new Input(c))(t.check(new Input(c))))
        val after = Option(Targets.accepted.get(t.name)).fold(0L)(_.sum())
        after == before
      }
      .map(_.name)
    assertEquals(cold, Vector.empty, "targets that accepted none of their seeds in this run")
  }

  test("generic engine targets accept exact upstream ChainSync and BlockFetch mock-domain seeds") {
    for name <- Vector("chainsync-engine-opaque", "blockfetch-engine-opaque") do
      val target = Targets.all.find(_.name == name).getOrElse(fail(s"missing $name target"))
      val before = Option(Targets.accepted.get(name)).fold(0L)(_.sum())
      target.corpus().foreach { bytes =>
        val input = new Input(bytes)
        Probe.run(name, input)(target.check(input))
      }
      val after = Option(Targets.accepted.get(name)).fold(0L)(_.sum())
      assert(after > before, s"pinned upstream fixture seeds were not accepted by $name")
  }

  test("previously missing imported CDDL modules supply every constructor without dropped seeds") {
    val rules = Vector(
      "chainsync-header:header" -> (0 to 7).toSet,
      "leios-fetch:leiosBlockFetchMessage" -> (0 to 4).toSet,
      "leios-notify:leiosNotifyMessage" -> (0 to 7).toSet
    )
    rules.foreach { (rule, expected) =>
      val samples = Corpus.sampled(rule, count = 120)
      assertEquals(samples.size, 120, rule)
      val tags = samples.map { bytes =>
        Cbor.decode(Bytes.fromArray(bytes)).fold(e => fail(e.toString), identity).value match
          case Value.Arr(fields) =>
            fields.headOption.map(_.value) match
              case Some(Value.UInt(tag)) => tag.toInt
              case other                 => fail(s"$rule has no constructor tag: $other")
          case other => fail(s"$rule is not an array: $other")
      }.toSet
      assertEquals(tags, expected, rule)
    }
  }

  test("unknown modules, missing rules and unsupported standalone groups fail the seed gate") {
    Vector("missing:rule", "conway:missing_rule", "conway:pool_params").foreach { rule =>
      intercept[munit.FailException](Corpus.sampled(rule, count = 4))
    }
    assertEquals(Corpus.sampled("conway:transaction_input", count = 4).size, 4)
  }

  private def hex(s: String): Input = new Input(
    Bytes.fromHex(s).fold(e => fail(e.toString), _.toArray)
  )

  test(
    "wide snapshot tables keep the fuzz round-trip property within its linear allocation budget"
  ) {
    val target =
      Targets.all.find(_.name == "snapshot-tables").getOrElse(fail("snapshot-tables target"))
    target.corpus()
    val entry = Array[Byte](0x58, 0x22) ++ Array.fill[Byte](34)(0) ++ Array[Byte](0x41, 0)
    val bytes =
      Array[Byte](0x81.toByte, 0xbf.toByte) ++ Array.fill(4096)(entry).flatten ++ Array[Byte](
        0xff.toByte
      )
    target.check(new Input(bytes))
  }

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

  test(
    "auxiliary fuzz seeds reach the public transaction decoder with accepted and refused envelopes"
  ) {
    for era <- Vector(Era.Alonzo, Era.Babbage, Era.Conway) do
      val seeds = Corpus.auxiliaryTransactions(era)
      assert(seeds.nonEmpty)
      val parts = seeds.map(bytes =>
        Corpus
          .asTransaction(Bytes.fromArray(bytes), era)
          .getOrElse(fail(s"auxiliary seed lost its transaction envelope: $era"))
      )
      assert(parts.forall(_.auxiliaryData.nonEmpty))
      val verdicts = parts.map(Tx.fromBlock(_, era))
      assert(verdicts.exists(_.isRight), s"$era has no accepted auxiliary seeds")
      assert(verdicts.exists(_.isLeft), s"$era has no refused auxiliary seeds")
  }
