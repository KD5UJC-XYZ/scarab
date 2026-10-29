// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO

/** The end a hot protocol reports when the connection's first failure was another protocol's: the
  * reference's `Shutdown (Just e)` (ouroboros-network 2fb69829 network-mux/src/Network/Mux.hs
  * 800–812), not a cancellation without its cause (issue 597).
  */
class FirstFailureSuite extends munit.CatsEffectSuite:
  private val cancelled = NetworkError.BearerClosed(NetworkError.Closure.Aborted)
  private val fetchFault = NetworkError.Timeout(Protocols.BlockFetch, "block")
  private val syncFault = NetworkError.Timeout(Protocols.ChainSync, "next")

  test("a protocol cancelled after another failed reports that failure") {
    for
      first <- NodeToNode.FirstFailure.create[IO]
      fetch <- first.ended(fetchFault)
      sync <- first.ended(cancelled)
    yield
      assertEquals(fetch, fetchFault)
      assertEquals(sync, fetchFault)
  }

  test("a cancellation before any failure stays a cancellation and is not the connection's") {
    for
      first <- NodeToNode.FirstFailure.create[IO]
      early <- first.ended(cancelled)
      fetch <- first.ended(fetchFault)
      late <- first.ended(cancelled)
    yield
      assertEquals(early, cancelled)
      assertEquals(fetch, fetchFault)
      assertEquals(late, fetchFault)
  }

  test("each protocol's own failure reports itself; the first is the connection's") {
    for
      first <- NodeToNode.FirstFailure.create[IO]
      fetch <- first.ended(fetchFault)
      sync <- first.ended(syncFault)
      cancel <- first.ended(cancelled)
    yield
      assertEquals(fetch, fetchFault)
      assertEquals(sync, syncFault)
      assertEquals(cancel, fetchFault)
  }
