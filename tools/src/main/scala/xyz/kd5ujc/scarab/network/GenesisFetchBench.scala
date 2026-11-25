// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import java.lang.management.ManagementFactory
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.selection.{BlockFetchPolicy, Fetched, GenesisBlockFetch}

import BlockFetchPolicy.*

/** The cost of one Genesis BlockFetch decision (`GenesisBlockFetch.decide`) when the dynamo, the
  * first peer of the order, serves a candidate shorter than the best one, as in a Genesis sync
  * whose dynamo is behind (issue 810). Not part of `check`: run it with
  * `sbt "tools/runMain xyz.kd5ujc.scarab.network.GenesisFetchBench"`. It uses only `decide`, so the
  * same file runs against both builds. "Fresh" calls take new fixtures each time, whose candidates'
  * hashes no one has hashed yet (the fetched set hashes its own); "repeated" calls reuse one
  * fixture. It prints thread CPU milliseconds per call.
  */
object GenesisFetchBench extends IOApp.Simple:
  /** A 32-byte hash of block `n`, a new instance each call, as each peer's headers are. */
  private def hash(n: Int): Bytes =
    val bytes = new Array[Byte](32)
    new java.util.Random(n.toLong * 0x9e3779b97f4a7c15L).nextBytes(bytes)
    Bytes.wrap(bytes)

  private def chain(from: Int, until: Int): Fragment =
    Fragment(Some(hash(from - 1)), (from until until).toVector.map(n => Header(hash(n), n, 1000)))

  /** The dynamo serving `best - behind` blocks, a second peer the best `best`; the first `fetched`
    * blocks are fetched.
    */
  private def fixture(best: Int, behind: Int, fetched: Int): (Vector[Candidate[Int]], Fetched) =
    val candidates = Vector(
      Candidate(1, 1, hash(best - behind), Vector(chain(1, best - behind + 1))),
      Candidate(2, 2, hash(best), Vector(chain(1, best + 1)))
    )
    (candidates, Fetched.of((1 to fetched).map(hash).toSet))

  private def decide(candidates: Vector[Candidate[Int]], fetched: Fetched): Int =
    GenesisBlockFetch
      .decide(
        Config(),
        GenesisBlockFetch.State(Vector(1, 2), Some(1), BigInt(0)),
        0,
        GenesisBlockFetch.Starvation.EndedAt(-1),
        candidates,
        fetched = fetched
      )
      .decisions
      .collect { case Decision.Request(_, fragments) => fragments.map(_.headers.size).sum }
      .sum

  private val bean = ManagementFactory.getThreadMXBean

  /** The timed region: thread CPU time, which a reading through `Clock[IO]` would not give. */
  private def cpuMillis(body: => Int): (Double, Int) =
    val start = bean.getCurrentThreadCpuTime
    val result = body
    ((bean.getCurrentThreadCpuTime - start) / 1e6, result)

  private def median(values: Vector[Double]): Double =
    val sorted = values.sorted
    sorted(sorted.size / 2)

  private def measure(best: Int, behind: Int, fetched: Int): IO[Unit] = IO.delay {
    val fixtures = Vector.fill(40)(fixture(best, behind, fetched))
    fixtures.take(20).foreach((c, f) => decide(c, f))
    val fresh = fixtures.drop(20).map((c, f) => cpuMillis(decide(c, f)))
    val (candidates, known) = fixture(best, behind, fetched)
    (1 to 20).foreach(_ => decide(candidates, known))
    val repeated = (1 to 50).toVector.map(_ => cpuMillis(decide(candidates, known)))
    println(
      f"best=$best behind=$behind fetched=$fetched requested=${fresh.head._2} " +
        f"freshMedianMs=${median(fresh.map(_._1))}%.3f repeatedMedianMs=${median(repeated.map(_._1))}%.3f"
    )
  }

  def run: IO[Unit] =
    IO.raiseUnless(bean.isCurrentThreadCpuTimeSupported && bean.isThreadCpuTimeEnabled)(
      new UnsupportedOperationException("this JVM does not measure a thread's CPU time")
    ) *> Vector((8000, 2000, 0), (8000, 2000, 2000), (8000, 0, 0), (2000, 500, 0))
      .traverse_((best, behind, fetched) => measure(best, behind, fetched))
