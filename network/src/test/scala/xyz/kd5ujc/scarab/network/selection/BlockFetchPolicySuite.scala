// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes

import BlockFetchPolicy.*
import FetchGsv.*

class BlockFetchPolicySuite extends FunSuite:
  private def hash(n: Int): Bytes = Bytes(Vector(n.toByte))
  private def header(n: Int, bytes: Long = 100): Header = Header(hash(n), BigInt(n), bytes)
  private def candidate(peer: Int, headers: Header*): Candidate[Int] =
    Candidate(peer, 0, hash(peer), Vector(Fragment(None, headers.toVector)), tieBreak = peer.toLong)

  test("default model and strict deadline follow degenerate upstream DeltaQ") {
    assertEquals(default.geographic, PicosPerSecond)
    assertEquals(default.window, Window(510000, 1020000))
    assertEquals(default.duration(0, 100), PicosPerSecond + BigInt(400000000))
    val exactDeadline = PeerGsv(0, Gsv(PicosPerSecond, 0), Gsv(PicosPerSecond, 1))
    assertEquals(exactDeadline.probability(0, 0), 0.0)
    assertEquals(probabilityBand(0.98), ProbabilityBand.Moderate)
    assertEquals(probabilityBand(0.75), ProbabilityBand.Low)
  }

  test("KeepAlive samples smooth geographical delay and retain serialisation") {
    val sampled = fromSample(1000 * PicosPerSecond, 1000 * PicosPerSecond + PicosPerSecond / 5)
    val updated = sampled.update(default)
    assertEquals(updated.outbound.gPicos, PicosPerSecond / 10)
    assertEquals(updated.inbound.sPicosPerByte, BigInt(2000000))
    val short = sampled.copy(sampleTimePicos = 100 * PicosPerSecond).update(default)
    assertEquals(short.outbound.gPicos, BigInt(460000000000L))
  }

  test("bulk removes duplicates and splits holes with original anchors") {
    val first = candidate(1, header(1), header(2), header(3))
      .copy(inFlight = InFlight(1, 100, Set(hash(2))))
    val decisions = decide(Config(), FetchMode.BulkSync, Vector(first))
    assertEquals(
      decisions,
      Vector(
        Decision.Request(
          1,
          Vector(
            Fragment(None, Vector(header(1))),
            Fragment(Some(hash(2)), Vector(header(3)))
          )
        )
      )
    )
  }

  test("global target counts active peers without pending work") {
    val idle = candidate(2, header(1))
    val active = candidate(1).copy(fragments = Vector.empty, inFlight = InFlight(1, 10))
    assert(
      decide(Config(), FetchMode.BulkSync, Vector(active, idle)).contains(
        Decision.Declined(2, Refusal.Concurrency(FetchMode.BulkSync, 1))
      )
    )
  }

  test("a faster preferred peer can replace a slow active peer above the target") {
    val slow = candidate(1).copy(fragments = Vector.empty, inFlight = InFlight(1, 10))
    val quick =
      candidate(2, header(1)).copy(performance = PeerGsv(0, Gsv(1, 2000000), Gsv(1, 2000000)))
    assert(decide(Config(), FetchMode.BulkSync, Vector(slow, quick)).exists {
      case Decision.Request(2, _) => true
      case _                      => false
    })
  }

  test("last whole block crosses byte limit, additional fragments respect request limit") {
    val large = candidate(1, header(1, 2000000), header(2))
    assertEquals(
      decide(Config(), FetchMode.BulkSync, Vector(large)),
      Vector(Decision.Request(1, Vector(Fragment(None, Vector(header(1, 2000000))))))
    )
    val split = candidate(1).copy(fragments =
      Vector(
        Fragment(None, Vector(header(1))),
        Fragment(Some(hash(2)), Vector(header(3)))
      )
    )
    assertEquals(
      decide(Config(maxRequestsPerPeer = 1), FetchMode.BulkSync, Vector(split)),
      Vector(Decision.Request(1, Vector(Fragment(None, Vector(header(1))))))
    )
  }

  test("deadline permits duplicates, bulk assigns each block once per decision round") {
    val peers = Vector(candidate(1, header(1)), candidate(2, header(1)))
    val bulk = decide(Config(bulkConcurrency = 2), FetchMode.BulkSync, peers)
    assertEquals(bulk.count { case Decision.Request(_, _) => true; case _ => false }, 1)
    val deadline = decide(Config(deadlineConcurrency = 2), FetchMode.Deadline, peers)
    assertEquals(deadline.count { case Decision.Request(_, _) => true; case _ => false }, 2)
  }

  test("shutdown, startup, aberrance and busy states preserve typed refusals") {
    val states = Vector(
      Status.Shutdown -> Refusal.PeerShutdown,
      Status.Starting -> Refusal.PeerStarting,
      Status.Aberrant -> Refusal.PeerSlow,
      Status.Busy -> Refusal.PeerBusy(0, 510000, 1020000)
    )
    states.foreach { (status, refusal) =>
      assertEquals(
        decide(Config(), FetchMode.BulkSync, Vector(candidate(1, header(1)).copy(status = status))),
        Vector(Decision.Declined(1, refusal))
      )
    }
  }

  test("chain comparator direction follows each pinned Praos branch") {
    val lower = candidate(1, header(1)).copy(chainPreference = 1)
    val higher = candidate(2, header(2)).copy(chainPreference = 2)
    assertEquals(rank(FetchMode.BulkSync, Vector(higher, lower)).map(_.peer), Vector(1, 2))
    assertEquals(rank(FetchMode.Deadline, Vector(lower, higher)).map(_.peer), Vector(2, 1))
  }

  test("deadline interleaves equally ranked chain heads instead of exhausting one fork") {
    val peers = Vector(
      candidate(1, header(1)),
      candidate(2, header(1)),
      candidate(3, header(2)),
      candidate(4, header(2))
    ).map(c => c.copy(chainHead = c.fragments.head.headers.head.hash))
    assertEquals(rank(FetchMode.Deadline, peers).map(_.peer), Vector(1, 3, 2, 4))
  }

  test("earlier candidate refusals survive and sort on the correct side of requests") {
    val refused = candidate(1, header(1)).copy(refusal = Some(Refusal.AlreadyFetched))
    val usable = candidate(2, header(2))
    assertEquals(rank(FetchMode.BulkSync, Vector(usable, refused)).map(_.peer), Vector(1, 2))
    assertEquals(rank(FetchMode.Deadline, Vector(refused, usable)).map(_.peer), Vector(2, 1))
    assert(
      decide(Config(), FetchMode.Deadline, Vector(refused)).contains(
        Decision.Declined(1, Refusal.AlreadyFetched)
      )
    )
  }

  test("own outstanding blocks are filtered before ranking with their own refusal") {
    val owned = candidate(1, header(1)).copy(inFlight = InFlight(1, 100, Set(hash(1))))
    FetchMode.values.foreach { mode =>
      assertEquals(
        decide(Config(), mode, Vector(owned)),
        Vector(Decision.Declined(1, Refusal.InFlightThisPeer))
      )
    }
  }

  test("zero request allowance returns the native request-limit refusal") {
    assertEquals(
      decide(Config(maxRequestsPerPeer = 0), FetchMode.BulkSync, Vector(candidate(1, header(1)))),
      Vector(Decision.Declined(1, Refusal.RequestsInFlight(0)))
    )
  }

  test("EWMA uses native Pico floor rounding for intermediate and signed products") {
    val sample = fromSample(BigInt(999999999), BigInt(1000000002))
    assertEquals(sample.outbound.gPicos, BigInt(1))
    assertEquals(sample.update(default).outbound.gPicos, BigInt(499999500000L))
  }
