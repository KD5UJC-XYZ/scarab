// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models.gen

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.concurrent.{FutureTask, TimeUnit}
import scala.util.Random

class SamplerTerminationSuite extends munit.FunSuite:
  private def sampler(cddl: String, maxDepth: Int = 7): CddlSampler =
    val file = CddlParser.parse(cddl).fold(e => fail(e.message), identity)
    new CddlSampler(Schema.build(Vector("t" -> Vector(file))), maxDepth)

  private def boundedSmallStack[A](body: => A): A =
    val task = new FutureTask[A](() => body)
    val thread = new Thread(null, task, "sampler-cycle-small-stack", 256 * 1024)
    thread.setDaemon(true)
    thread.start()
    try task.get(5, TimeUnit.SECONDS)
    finally task.cancel(true)

  test("direct, indirect and group cycles return a typed refusal in bounded time") {
    for cddl <- Vector("r = r\n", "r = s\ns = r\n", "r = [g]\ng = (g, uint)\n") do
      val result = boundedSmallStack(sampler(cddl).sample(QName("t", "r"), new Random(1)))
      result match
        case Left(GenError.SamplingCycle(q)) => assert(Set("r", "s", "g")(q.name))
        case other => fail(s"expected a sampling cycle for $cddl, got $other")
  }

  test("a productive choice with a recursive first alternative refuses its cutoff cycle") {
    val result = boundedSmallStack {
      sampler("r = r / uint\n", maxDepth = 0).sample(QName("t", "r"), new Random(1))
    }
    assertEquals(result, Left(GenError.SamplingCycle(QName("t", "r"))))
    val terminating = boundedSmallStack {
      sampler("r = uint / [r]\n", maxDepth = 0).sample(QName("t", "r"), new Random(1))
    }
    assert(terminating.isRight)
  }

  test("greedy optional samples retain required suffixes through enclosing groups") {
    val cases = Vector(
      "r = [? before: uint, after: uint]\n" -> 2,
      "r = [g, tail: uint]\ng = (? before: uint, after: uint)\n" -> 3,
      "r = [g, tail: uint]\ng = (? before: uint)\n" -> 2
    )
    cases.foreach { (cddl, size) =>
      val s = sampler(cddl)
      (1L to 64L).foreach { seed =>
        val result =
          s.sample(QName("t", "r"), new Random(seed)).fold(e => fail(e.message), identity)
        assertEquals(result.headOption.map(_ & 0xff), Some(0x80 + size), s"schema=$cddl seed=$seed")
      }
    }
  }

  test("fixed-seed recursive and compound samples preserve their original bytes") {
    val samplers = Vector(
      sampler("r = uint / [r]\n"),
      sampler("r = [0, bytes .size 4, ? text, * uint]\n"),
      sampler("r = { * uint => bytes }\n")
    )
    val samples = samplers.zipWithIndex.flatMap { (s, i) =>
      val rnd = new Random(20261018L + i)
      Vector.fill(128)(s.sample(QName("t", "r"), rnd).fold(e => fail(e.message), identity))
    }
    val digest = MessageDigest.getInstance("SHA-256")
    samples.foreach { bytes =>
      digest.update(bytes.length.toString.getBytes(UTF_8))
      digest.update(0.toByte)
      digest.update(bytes)
    }
    val hex = digest.digest().map(b => f"${b & 0xff}%02x").mkString
    // Captured from fac5554c before the cycle-refusal change (384 samples, seeds20261018..20).
    assertEquals(hex, "14a0d6242eaf59048296048ccd9f751d4edcef1e2d073623370ef50a861f01c1")
  }
