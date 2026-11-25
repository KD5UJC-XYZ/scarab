// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import java.lang.management.ManagementFactory
import munit.FunSuite
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes

import BlockFetchPolicy.*

/** A Genesis BlockFetch decision costs time linear in the candidates' lengths when the dynamo
  * serves a candidate far shorter than the best one (issue 810). Trimming the blocks to fetch to
  * the dynamo's candidate looked for each of them by a scan of that candidate, so each block the
  * dynamo does not have cost a scan of the whole of it.
  *
  * The bound is on the CPU time of the test's own thread, which other work on the machine does not
  * inflate as it does elapsed time (the bound of the node's `SharedHashCodeSuite` is elapsed time).
  * With 240,000 blocks in the best candidate and 120,000 in the dynamo's, the whole test took about
  * 0.12 s in a JDK container of 6 CPUs; where the decision was quadratic it had not ended when
  * munit's 30-second timeout failed the test, and at half these lengths it took 12 s. A 5-second
  * bound leaves a margin of about 40 times for the decision as it is.
  */
class GenesisBlockFetchScaleSuite extends FunSuite:
  private val bound = 5.seconds

  /** The result of `body` and the CPU time the calling thread spent on it, which does not depend on
    * the load of the machine as elapsed time does.
    */
  private def cpu[A](body: => A): (FiniteDuration, A) =
    val bean = ManagementFactory.getThreadMXBean
    val start = bean.getCurrentThreadCpuTime
    val result = body
    ((bean.getCurrentThreadCpuTime - start).nanos, result)

  /** A 32-byte hash of block `n`, a new instance each call, as each peer's headers are. */
  private def hash(n: Int): Bytes =
    Bytes.wrap(Array.tabulate(32)(i => ((n >>> ((i % 4) * 8)) ^ (i * 37)).toByte))

  private def chain(length: Int): Fragment =
    Fragment(Some(hash(0)), (1 to length).toVector.map(n => Header(hash(n), n, 1000)))

  test("a dynamo half the best candidate's length is served in time linear in both") {
    val candidates = Vector(
      Candidate(1, 1, hash(120000), Vector(chain(120000))),
      Candidate(2, 2, hash(240000), Vector(chain(240000)))
    )
    val (took, result) = cpu(
      GenesisBlockFetch.decide(
        Config(),
        GenesisBlockFetch.State(Vector(1, 2), Some(1), BigInt(0)),
        0,
        GenesisBlockFetch.Starvation.EndedAt(-1),
        candidates
      )
    )
    assert(took < bound, s"took ${took.toMillis} ms of CPU, more than ${bound.toMillis} ms")
    assertEquals(
      result.decisions.collect { case Decision.Request(peer, fragments) =>
        peer -> fragments.flatMap(_.headers).headOption.map(_.slot)
      },
      Vector(1 -> Some(BigInt(1)))
    )
  }
