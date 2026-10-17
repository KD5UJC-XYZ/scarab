// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.{Header, HeaderVrf, VrfCert}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

class PeerSuite extends ScalaCheckSuite:
  property("thinned intersection candidates fit one FindIntersect and keep both ends") {
    forAll(Gen.choose(0, 5000)) { n =>
      val all = Vector.range(0, n)
      val thin = Peer.thin(all)
      assert(thin.size <= 64)
      if n > 0 then
        assertEquals(thin.head, 0)
        assertEquals(thin.last, n - 1)
      assertEquals(thin, thin.sorted)
      assertEquals(thin, thin.distinct)
      // The newest candidates are all there: a short rollback finds its intersection at once.
      assertEquals(thin.take(math.min(n, 16)), all.take(16))
    }
  }

  private def header(i: Int, bodySize: Long): Header =
    Header.Shelley(
      Era.Babbage,
      Bytes.wrap(Array.fill(900)(1.toByte)),
      Bytes.empty,
      Bytes.wrap(Array.fill(32)(i.toByte)),
      BigInt(i),
      BigInt(i),
      None,
      Bytes.empty,
      Bytes.empty,
      HeaderVrf.Praos(VrfCert(Bytes.empty, Bytes.empty)),
      BigInt(bodySize),
      Bytes.empty,
      Header.OpCert(Bytes.empty, BigInt(0), BigInt(0), Bytes.empty),
      (BigInt(8), BigInt(0)),
      Bytes.empty
    )

  property("ranges cover the headers in order within the request limits") {
    forAll(Gen.listOf(Gen.choose(0L, 90112L)).map(_.take(600))) { sizes =>
      val headers = sizes.zipWithIndex.map((s, i) => header(i, s)).toVector
      val runs = Peer.ranges(headers)
      assertEquals(runs.flatten, headers)
      runs.foreach { run =>
        assert(run.size <= 100)
        val bytes = run.map {
          case h: Header.Shelley => h.bodySize.toLong + h.raw.size + 512
          case _                 => 0L
        }
        // A run over the budget is a single oversized header, never several.
        assert(bytes.sum <= 8000000L || run.size == 1)
      }
    }
  }

  property("owned runs are the maximal stretches of owned headers, in order") {
    forAll(Gen.listOf(Gen.oneOf(true, false)).map(_.take(300))) { flags =>
      val headers = flags.indices.map(i => header(i, 1000L)).toVector
      val runs = Peer.ownedRuns(headers.zip(flags))
      assertEquals(runs.flatten, headers.zip(flags).collect { case (h, true) => h })
      runs.foreach { run =>
        val ix = run.map(h => h.blockNo.toInt)
        assertEquals(ix, (ix.head to ix.last).toVector)
      }
      // two runs are never adjacent: something not owned lies between them
      runs.zip(runs.drop(1)).foreach((a, b) => assert(b.head.blockNo - a.last.blockNo > 1))
    }
  }

  test("what the network layer raises is a peer error, and a peer that broke a rule is forgotten") {
    import xyz.kd5ujc.scarab.ledger.PeerError
    import xyz.kd5ujc.scarab.network.NetworkError
    import xyz.kd5ujc.scarab.network.selection.FailureClass
    def classified(error: NetworkError) = FailurePolicy.classify(Peer.peerError(error))
    // a lost or slow connection is tried again
    assertEquals(classified(NetworkError.Connect("peer", "refused")), FailureClass.Transient)
    assertEquals(classified(NetworkError.Timeout(2, "StNext")), FailureClass.Transient)
    assertEquals(classified(NetworkError.BearerClosed("closed by peer")), FailureClass.Transient)
    assertEquals(
      classified(NetworkError.VersionMismatch(Vector(14), Vector(13))),
      FailureClass.Transient
    )
    // a peer that sends what the protocol does not allow is forgotten: more unread bytes than the
    // limit of the protocol, a message that does not decode, a message in the wrong state, a frame
    // the multiplexer cannot read
    assertEquals(classified(NetworkError.IngressLimit(2, 462100L)), FailureClass.Misbehaved)
    assertEquals(classified(NetworkError.Decode(3, "not a block")), FailureClass.Misbehaved)
    assertEquals(
      classified(NetworkError.IllegalTransition(2, "pipelined request")),
      FailureClass.Misbehaved
    )
    assertEquals(classified(NetworkError.Framing("EOF in mux payload")), FailureClass.Misbehaved)
    assert(Peer.peerError(NetworkError.IngressLimit(2, 1L)).isInstanceOf[PeerError.Protocol])
  }
