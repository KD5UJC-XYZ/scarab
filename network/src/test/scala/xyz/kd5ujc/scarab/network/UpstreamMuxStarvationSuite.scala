// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import fs2.Chunk
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult

/** Original Uneven inputs and Test.Mux.prop_mux_starvation judgments at 2fb69829. Native
  * response1/response1 wiring is retained; unequal replies are separately labelled controls.
  */
class UpstreamMuxStarvationSuite extends FailsOnUncaught:
  private enum Verdict(val wire: String):
    case Pass extends Verdict("pass")
    case Discard extends Verdict("precondition-discard")
    case Failure extends Verdict("upstream-failure")
  private enum Domain(val wire: String):
    case Applicable extends Domain("applicable")
    case Discard extends Domain("precondition-discard")
    case Oversized extends Domain("unsupported-message-limit")
  private final case class Recipe(size: Int, chunk: Bytes, digest: String):
    def bytes: Bytes =
      val result = Bytes(Vector.tabulate(size)(i => chunk(i % chunk.size)))
      assertEquals(sha(result.toArray), digest)
      result
  private final case class Sample(
      seed: Int,
      a: Recipe,
      b: Recipe,
      request: Bytes,
      prefixA: Bytes,
      prefixB: Bytes,
      wireHashA: String,
      wireHashB: String,
      nativeTrace: Vector[Int],
      verdict: Verdict,
      domain: Domain
  ):
    def responseA: Bytes =
      val result = prefixA ++ a.bytes
      assertEquals(sha(result.toArray), wireHashA)
      result
    def responseB: Bytes =
      val result = prefixB ++ b.bytes
      assertEquals(sha(result.toArray), wireHashB)
      result

  private def sha(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 255}%02x").mkString
  private def hex(text: String): Bytes = Bytes.fromHex(text).fold(e => fail(e.message), identity)

  private lazy val samples: Vector[Sample] =
    val path = "/upstream/mux-starvation/starvation.tsv"
    val stream = Option(getClass.getResourceAsStream(path)).getOrElse(fail(s"missing $path"))
    val bytes =
      try stream.readAllBytes()
      finally stream.close()
    assertEquals(sha(bytes), "c91279076b33eb513dc899d516faf8324369e656db454e002e8a01b9b97f7557")
    new String(bytes, StandardCharsets.UTF_8).linesIterator.map { line =>
      val r = line.split("\t", -1).toVector
      assertEquals(r.size, 18)
      assertEquals(r.head, "mux-starvation-v1")
      def recipe(index: Int): Recipe =
        val size = r(index).toInt
        val chunk = hex(r(index + 1))
        assert(size >= 0 && size < 3000000)
        assertEquals(chunk.size, size.min(1024))
        Recipe(size, chunk, r(index + 2))
      Sample(
        r(1).toInt,
        recipe(4),
        recipe(7),
        hex(r(10)),
        hex(r(11)),
        hex(r(12)),
        r(13),
        r(14),
        if r(15).isEmpty then Vector.empty else r(15).split(",").toVector.map(_.toInt),
        Verdict.values.find(_.wire == r(16)).getOrElse(fail(s"unknown verdict ${r(16)}")),
        Domain.values.find(_.wire == r(17)).getOrElse(fail(s"unknown domain ${r(17)}"))
      )
    }.toVector

  // Test.Mux.hs:1108–1131: take100, discard initial protocol run, collapse the next
  // initial run and final run, then require adjacent protocol numbers to differ.
  private def overlap(trace: Vector[Int]): Vector[Int] =
    val prefix = trace.take(100)
    val rest = prefix.headOption.fold(Vector.empty[Int])(first => prefix.dropWhile(_ == first))
    rest.headOption.fold(Vector.empty[Int]) { first =>
      val last = rest.last // nonempty in this branch
      val start = Vector(first) ++ rest.dropWhile(_ == first)
      start.reverse.dropWhile(_ == last).reverse :+ last
    }
  private def alternates(trace: Vector[Int]): Boolean =
    overlap(trace).zip(overlap(trace).drop(1)).forall((a, b) => a != b)

  private def exactly(size: Int)(bytes: Bytes): DecodeResult[Bytes] =
    if bytes.size >= size then DecodeResult.Decoded(bytes.take(size), size)
    else DecodeResult.NeedMore

  private def run(sample: Sample, response2: Bytes, response3: Bytes): IO[Vector[Int]] =
    TestControl
      .executeEmbed(Peers.typed {
        for
          ends <- Bearer.inMemory[IO](10) // original queue bearer capacity
          (a, b) = ends
          admitted <- Ref.of[IO, Int](0)
          release <- Deferred[IO, Unit]
          first <- Ref.of[IO, Boolean](true)
          trace <- Ref.of[IO, Vector[Int]](Vector.empty)
          observing = new Bearer[IO]:
            def reads = b.reads
            def close = b.close
            def write(bytes: Chunk[Byte]) =
              // Observe actual wire headers independently of the production framing decoder.
              val frame = bytes.toArray
              assert(frame.length >= 8)
              val number = ((frame(4) & 255) << 8) | (frame(5) & 255)
              val length = ((frame(6) & 255) << 8) | (frame(7) & 255)
              assert((number & 0x8000) != 0)
              assert(length > 0 && length <= 1280)
              assertEquals(frame.length, 8 + length)
              first.getAndSet(false).flatMap(flag => release.get.whenA(flag)) *>
                trace.update(_ :+ (number & 0x7fff)) *> b.write(bytes)
          config = Mux.Config(
            maxSduPayload = 1280,
            maxMessageBytes = 3000000,
            ingressLimit = _ => 3000000L
          )
          result <- (
            Mux.resource(a, Set(2, 3), config),
            Mux.resource(observing, Set(2, 3), config.copy(role = Mux.Role.Responder))
          ).tupled.use { (client, server) =>
            def conversation(protocol: Int, response: Bytes)(using Peers.Net): IO[Unit] =
              for
                c <- client.channel(protocol)
                s <- server.channel(protocol)
                _ <- (
                  (for
                    _ <- c.send(sample.request)
                    got <- c.recv(response.size)(exactly(response.size))
                    _ <- IO(
                      assert(got == response, s"response bytes at seed ${sample.seed}/$protocol")
                    )
                    _ <- c.send(hex("8102")) *> c.flush
                  yield ()),
                  (for
                    request <- s.recv(sample.request.size)(exactly(sample.request.size))
                    _ <- IO(assertEquals(request, sample.request))
                    _ <- s.send(response)
                    n <- admitted.updateAndGet(_ + 1)
                    _ <- release.complete(()).void.whenA(n == 2)
                    done <- s.recv(2)(exactly(2))
                    _ <- IO(assertEquals(done, hex("8102")))
                  yield ())
                ).parTupled
              yield ()
            (conversation(2, response2), conversation(3, response3)).parTupled *> trace.get
          }
        yield result
      })
      .map(_.fold(e => fail(s"seed ${sample.seed}: ${e.message}"), identity))

  test(
    "retain all 32 original generated outcomes, including discards and historical default-profile classifications"
  ):
    assertEquals(samples.size, 32)
    assertEquals(samples.map(_.seed), (512001 until 512033).toVector)
    assertEquals(samples.count(_.verdict == Verdict.Pass), 22)
    assertEquals(samples.count(_.verdict == Verdict.Discard), 10)
    assertEquals(samples.count(_.verdict == Verdict.Failure), 0)
    assertEquals(samples.count(_.domain == Domain.Applicable), 18)
    assertEquals(samples.count(_.domain == Domain.Oversized), 4)
    samples.foreach { s =>
      val eligible = s.a.size > 2560 && s.b.size > 2560
      assertEquals(s.verdict == Verdict.Discard, !eligible)
      assertEquals(s.domain == Domain.Discard, !eligible)
      if eligible then
        assertEquals(s.domain == Domain.Oversized, s.b.size + s.prefixB.size > 2500000)
        assertEquals(s.nativeTrace.size, 100)
        assert(alternates(s.nativeTrace), s"original receive-header ordering at seed ${s.seed}")
    }

  test("replay original response1/response1 bytes and the unchanged upstream ordering assertion"):
    samples.filter(_.verdict == Verdict.Pass).traverse_ { sample =>
      val response = sample.responseB
      run(sample, response, response).map { trace =>
        assert(alternates(trace), s"upstream ordering assertion at seed ${sample.seed}")
        // The controlled admission barrier makes overlap observable, so the assertion cannot
        // pass vacuously after discarding a serial writer's initial and final protocol runs.
        assertEquals(overlap(trace).toSet, Set(2, 3), s"actual overlap at seed ${sample.seed}")
        val frames = (response.size + 1279) / 1280
        assertEquals(trace.count(_ == 2), frames)
        assertEquals(trace.count(_ == 3), frames)
      }
    }

  test("supplemental unequal replies retain both generated inputs and the same ordering law"):
    val cases =
      samples.filter(s => s.domain == Domain.Applicable && s.a.size + s.prefixA.size <= 2500000)
    assertEquals(cases.size, 12)
    cases.traverse_ { sample =>
      assert(sample.a.size != sample.b.size)
      run(sample, sample.responseA, sample.responseB).map { trace =>
        assert(alternates(trace), s"supplemental unequal ordering at seed ${sample.seed}")
        assertEquals(
          overlap(trace).toSet,
          Set(2, 3),
          s"supplemental overlap at seed ${sample.seed}"
        )
      }
    }

  test("port preserves startup/end normalization, interior failure and its vacuity limitation"):
    assert(alternates(Vector(2, 2, 3, 2, 3, 3)))
    assert(!alternates(Vector(2, 2, 3, 2, 2, 3)))
    assert(alternates(Vector.fill(5)(2) ++ Vector.fill(5)(3)))
    assertEquals(overlap(Vector.fill(5)(2) ++ Vector.fill(5)(3)), Vector(3))
    // Prefix100 is the exact upstream observation window; a later trace fault is outside it.
    assert(alternates(Vector.tabulate(100)(i => 2 + i % 2) ++ Vector(2, 2, 3)))
