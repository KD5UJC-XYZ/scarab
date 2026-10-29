// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO

/** The end a hot protocol reports when the connection failed before it was cancelled: the
  * reference's `Shutdown (Just e)` (ouroboros-network 2fb69829 network-mux/src/Network/Mux.hs
  * 800–812), not a cancellation without its cause (issue 597).
  */
class ProtocolEndSuite extends FailsOnUncaught:
  private val cancelled = NetworkError.BearerClosed(NetworkError.Closure.Aborted)
  private val fetchFault = NetworkError.Timeout(Protocols.BlockFetch, "block")
  private val syncFault = NetworkError.Timeout(Protocols.ChainSync, "next")

  /** `body` over a real multiplexer on one end of an in-memory bearer whose other end stays open.
    */
  private def overMux[A](body: Mux[IO] => IO[A]): IO[A] =
    Bearer
      .inMemory[IO]()
      .flatMap((ours, _) => Peers.typed(Mux.resource(ours, Set(2, 3), Mux.Config()).use(body)))
      .map(_.fold(e => fail(s"the mux itself failed: ${e.message}"), identity))

  test("a protocol cancelled after the connection failed reports that failure") {
    overMux { m =>
      for
        before <- m.failure
        _ <- m.abort(fetchFault)
        after <- m.failure
        sync <- NodeToNode.protocolEnd(m, cancelled)
        fetch <- NodeToNode.protocolEnd(m, fetchFault)
      yield
        assertEquals(before, None)
        assertEquals(after, Some(fetchFault))
        assertEquals(sync, fetchFault)
        assertEquals(fetch, fetchFault)
    }
  }

  test("a cancellation of a connection that has not failed stays a cancellation") {
    overMux(m => NodeToNode.protocolEnd(m, cancelled)).map(assertEquals(_, cancelled))
  }

  test("a protocol's own failure reports itself, whatever failed the connection first") {
    overMux { m =>
      for
        _ <- m.abort(fetchFault)
        sync <- NodeToNode.protocolEnd(m, syncFault)
      yield assertEquals(sync, syncFault)
    }
  }
