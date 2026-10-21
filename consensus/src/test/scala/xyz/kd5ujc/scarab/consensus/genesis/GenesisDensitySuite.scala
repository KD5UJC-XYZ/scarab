// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.genesis

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
import GenesisDensity.*

class GenesisDensitySuite extends FunSuite:
  private def point(slot: Int, branch: Int = 0): Point.Block =
    Point.Block(UInt64.saturating(slot), Bytes(Vector(slot.toByte, branch.toByte)))
  private val empty = Fragment(Point.Origin, Vector.empty)
  private def fragment(slots: Int*): Fragment = Fragment(Point.Origin, slots.toVector.map(point(_)))

  test("shared prefix can exceed current selection, while no candidates pin to immutable anchor") {
    val candidate = fragment(1, 3, 5)
    assertEquals(sharedCandidatePrefix(empty, Vector(1 -> candidate)).prefix, candidate)
    assertEquals(sharedCandidatePrefix[Int](candidate, Vector.empty).prefix, empty)
  }

  test("disconnected and empty candidates pin agreement to immutable anchor") {
    val current = Fragment(point(1), Vector(point(2)))
    val disconnected = Fragment(point(9), Vector(point(10)))
    val result = sharedCandidatePrefix(current, Vector(1 -> fragment(1, 2, 3), 2 -> disconnected))
    assertEquals(result.prefix, Fragment(point(1), Vector.empty))
    assertEquals(result.suffixes(1)._2, result.prefix)
    assertEquals(result.suffixes.head._2.headers, Vector(point(2), point(3)))
  }

  test("density uses half-open slot window, forecast latest header and unknown state") {
    val a = fragment(1, 2, 4)
    val b = Fragment(Point.Origin, Vector(point(0, 1), point(2, 1)))
    val states = Map(
      1 -> PeerState(a, LatestSlot.At(4), false),
      2 -> PeerState(b, LatestSlot.At(5), false),
      3 -> PeerState(a, LatestSlot.Unknown, false)
    )
    val result = densityDisconnect(4, 1, states, Vector(1 -> a, 2 -> b, 3 -> a), empty)
    assertEquals(result.bounds.map(_._1), Vector(1, 2))
    assertEquals(result.bounds.head._2.lowerBound, BigInt(2))
    assertEquals(result.bounds.head._2.upperBound, BigInt(2))
    assert(result.bounds.head._2.hasBlockAfter)
    assertEquals(result.bounds(1)._2.upperBound, BigInt(2))
  }

  test("equal known densities disconnect both conflicting peers in input order") {
    val a = fragment(1, 3)
    val b = Fragment(Point.Origin, Vector(point(1, 1), point(3, 1)))
    val states =
      Map(2 -> PeerState(a, LatestSlot.At(4), false), 1 -> PeerState(b, LatestSlot.At(4), false))
    val result = densityDisconnect(4, 99, states, Vector(2 -> a, 1 -> b), empty)
    assertEquals(result.losingPeers, Vector(2, 1))
  }

  test("idle empty disagreement may lose, but an unresponsive non-idle peer waits for timeout") {
    val a = fragment(0, 1, 2)
    val states = Map(
      1 -> PeerState(a, LatestSlot.At(2), false),
      2 -> PeerState(empty, LatestSlot.Origin, false),
      3 -> PeerState(empty, LatestSlot.Origin, true)
    )
    val result = densityDisconnect(3, 1, states, Vector(1 -> a, 2 -> empty, 3 -> empty), empty)
    assertEquals(result.losingPeers, Vector(3))
  }

  test("shared first block prevents disconnection, despite matching bounds") {
    val a = fragment(0, 1, 2)
    val b = fragment(0)
    val result = densityDisconnect(
      3,
      1,
      Map(1 -> PeerState(a, LatestSlot.At(3), false), 2 -> PeerState(b, LatestSlot.At(3), false)),
      Vector(1 -> a, 2 -> b),
      empty
    )
    assertEquals(result.losingPeers, Vector.empty)
  }

  test("LoE permits k headers after its tip, including repeated slots such as Byron EBBs") {
    val candidate = Fragment(Point.Origin, Vector(point(0), point(1), point(1, 1), point(2)))
    val limit = Fragment(Point.Origin, Vector(point(0)))
    assertEquals(
      trim(2, empty, candidate, Some(limit)),
      Right(candidate.copy(headers = candidate.headers.take(3)))
    )
    assertEquals(trim(0, empty, candidate, None), Right(candidate))
  }

  test(
    "LoE excludes divergent suffix, sanitizes a stale limit, and reports disconnected candidate"
  ) {
    val candidate = fragment(0, 1, 2)
    val limit = Fragment(Point.Origin, Vector(point(0), point(1, 1)))
    assertEquals(trim(99, empty, candidate, Some(limit)), Right(fragment(0)))
    val current = Fragment(point(0), Vector(point(1)))
    assertEquals(
      sanitize(current, Fragment(point(9), Vector.empty)),
      Fragment(point(0), Vector.empty)
    )
    assertEquals(
      trim(2, current, Fragment(point(8), Vector.empty), Some(limit)),
      Left(LoEError.CandidateDoesNotIntersectLimit)
    )
  }
