// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import cats.effect.IO
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes

import BlockFetchPolicy.*

/** A Genesis BlockFetch decision costs time linear in the candidates' lengths when the dynamo
  * serves a candidate far shorter than the best one (issue 810). Trimming the blocks to fetch to
  * the dynamo's candidate looked for each of them by a scan of that candidate, so each block the
  * dynamo does not have cost a scan of the whole of it.
  *
  * The bound is elapsed time read from `Clock[IO]`, as in the node's `SharedHashCodeSuite`. With
  * 240,000 blocks in the best candidate and 120,000 in the dynamo's, each test took under 0.4 s in
  * a JDK container of 6 CPUs. Where the decision was quadratic, neither had ended when munit's
  * 30-second timeout failed it, and at half these lengths the first took 12 s. A 5-second bound
  * leaves a margin of over 12 times for the decision as it is.
  */
class GenesisBlockFetchScaleSuite extends CatsEffectSuite:
  private val bound = 5.seconds

  /** A 32-byte hash of block `n`, a new instance each call, as each peer's headers are. */
  private def hash(n: Int): Bytes =
    Bytes.wrap(Array.tabulate(32)(i => ((n >>> ((i % 4) * 8)) ^ (i * 37)).toByte))

  private def chain(length: Int): Fragment =
    Fragment(Some(hash(0)), (1 to length).toVector.map(n => Header(hash(n), n, 1000)))

  /** The dynamo with the first 120,000 blocks, first in the order, and a peer with 240,000. */
  private val candidates = Vector(
    Candidate(1, 1, hash(120000), Vector(chain(120000))),
    Candidate(2, 2, hash(240000), Vector(chain(240000)))
  )

  /** The decision on `candidates` with `fetched`, within the bound; the first block it requests. */
  private def decided(fetched: Fetched): IO[Vector[(Int, Option[BigInt])]] =
    IO(
      GenesisBlockFetch.decide(
        Config(),
        GenesisBlockFetch.State(Vector(1, 2)),
        0,
        GenesisBlockFetch.Starvation.EndedAt(-1),
        candidates,
        fetched = fetched
      )
    ).timed.map { (took, result) =>
      assert(took < bound, s"took ${took.toMillis} ms, more than ${bound.toMillis} ms")
      result.decisions.collect { case Decision.Request(peer, fragments) =>
        peer -> fragments.flatMap(_.headers).headOption.map(_.slot)
      }
    }

  test("a dynamo half the best candidate's length is served in time linear in both") {
    decided(Fetched.none).map(assertEquals(_, Vector(1 -> Some(BigInt(1)))))
  }

  test("so it is when every other block is fetched, which leaves a fragment for each block") {
    val even = (2 to 240000 by 2).map(hash).toSet
    decided(Fetched.of(even)).map(assertEquals(_, Vector(1 -> Some(BigInt(1)))))
  }
