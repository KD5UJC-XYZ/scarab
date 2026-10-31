// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.{Header, HeaderVrf, VrfCert, PeerError}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era, Mux as WireMux}
import xyz.kd5ujc.scarab.consensus.ChainSelection
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

  test("large read-ahead settings fit the retained current and queued candidate histories") {
    assertEquals(Peer.requestDepth(100000, 10000), 2048)
    assertEquals(Peer.requestDepth(100000, 128), 128)
    assertEquals(Peer.requestDepth(0, 128), 1)
  }

  private def header(i: Int, bodySize: Long): Header =
    Header.Shelley(
      Era.Babbage,
      Bytes.wrap(Array.fill(900)(1.toByte)),
      Bytes.empty,
      Bytes.wrap(Array.fill(32)(i.toByte)),
      BigInt(i),
      BigInt(i),
      Some(Bytes.wrap(Array.fill(32)((i - 1).toByte))),
      Bytes.empty,
      Bytes.empty,
      HeaderVrf.Praos(VrfCert(Bytes.empty, Bytes.empty)),
      BigInt(bodySize),
      Bytes.empty,
      Header.OpCert(Bytes.empty, BigInt(0), BigInt(0), Bytes.empty),
      (BigInt(8), BigInt(0)),
      Bytes.empty
    )

  property("owned runs are the maximal stretches of owned headers, in order") {
    forAll(Gen.listOf(Gen.oneOf(true, false)).map(_.take(300))) { flags =>
      val headers = flags.indices.map(i => header(i, 1000L)).toVector
      val runs = Peer.ownedRuns(headers.zip(flags)).map(_.toVector)
      assertEquals(runs.flatten, headers.zip(flags).collect { case (h, true) => h })
      runs.foreach { run =>
        val ix = run.map(h => h.blockNo.toInt)
        assertEquals(ix, (ix.head to ix.last).toVector)
      }
      // two runs are never adjacent: something not owned lies between them
      runs.zip(runs.drop(1)).foreach((a, b) => assert(b.head.blockNo - a.last.blockNo > 1))
    }
  }

  test("selected fragments never merge across an excluded parent") {
    val first = header(1, 1000L)
    val third = header(3, 1000L)
    assertEquals(
      Peer.ownedRuns(Vector(first -> true, third -> true)).map(_.toVector),
      Vector(Vector(first), Vector(third))
    )
  }

  test("what the network layer raises is a peer error, and a peer that broke a rule is forgotten") {
    import xyz.kd5ujc.scarab.network.NetworkError
    import xyz.kd5ujc.scarab.network.selection.FailureClass
    def classified(error: NetworkError) = Peer.networkFault(error) match
      case Fault.Failed(failure) => FailurePolicy.classify(failure)
      case Fault.Defect(cause)   => fail(s"unexpected defect $cause")
    // a lost or slow connection is tried again
    assertEquals(
      classified(NetworkError.Connect("peer", new java.io.IOException("refused"))),
      FailureClass.Transient
    )
    assertEquals(classified(NetworkError.Timeout(2, "StNext")), FailureClass.Transient)
    List(NetworkError.SduOperation.Read, NetworkError.SduOperation.Write).foreach(operation =>
      assertEquals(classified(NetworkError.SduTimeout(operation)), FailureClass.Transient)
    )
    assertEquals(
      classified(NetworkError.BearerClosed(NetworkError.Closure.PeerClosed)),
      FailureClass.Transient
    )
    assertEquals(
      classified(NetworkError.VersionMismatch(Vector(14), Vector(13))),
      FailureClass.Transient
    )
    // a peer that sends what the protocol does not allow is forgotten: more unread bytes than the
    // limit of the protocol, a message that does not decode, a message in the wrong state, a frame
    // the multiplexer cannot read
    assertEquals(classified(NetworkError.IngressLimit(2, 462100L)), FailureClass.Misbehaved)
    assertEquals(
      classified(
        NetworkError.TxSubmission(
          xyz.kd5ujc.scarab.network.TxSubmissionPolicy.Violation.Outstanding(10, 1)
        )
      ),
      FailureClass.Misbehaved
    )
    assertEquals(
      classified(
        NetworkError
          .Decode(3, xyz.kd5ujc.scarab.protocol.ProtocolError.ExpectedSerializedBlockTag24)
      ),
      FailureClass.Misbehaved
    )
    assertEquals(
      classified(
        NetworkError.IllegalTransition(
          2,
          NetworkError.RuntimeViolation.PipelinedRequest(
            xyz.kd5ujc.scarab.protocol.ChainSync.State.Idle
          )
        )
      ),
      FailureClass.Misbehaved
    )
    assertEquals(
      classified(NetworkError.Framing(WireMux.Error.TruncatedPayload(9, 1))),
      FailureClass.Misbehaved
    )
    assert(Peer.networkFault(NetworkError.IngressLimit(2, 1L)).isInstanceOf[Fault.Failed])
  }

  // issue 567: the connection's own exception ends that connection, uncharged, with its cause
  test("network defects keep their original cause and end only the connection") {
    import xyz.kd5ujc.scarab.ledger.PeerError
    val cause = new IllegalStateException("injected")
    Peer.networkFault(xyz.kd5ujc.scarab.network.NetworkError.Defect(cause)) match
      case Fault.Failed(error @ PeerError.ConnectionDefect(thrown)) =>
        assert(thrown eq cause)
        assertEquals(
          FailurePolicy.classify(error),
          xyz.kd5ujc.scarab.network.selection.FailureClass.NotThePeers
        )
      case other => fail(s"unexpected $other")
  }

  test("mux causes reach node policy unchanged, with local failures separate from peer framing") {
    import xyz.kd5ujc.scarab.ledger.PeerError
    import xyz.kd5ujc.scarab.network.NetworkError
    import xyz.kd5ujc.scarab.network.selection.FailureClass

    val truncated = WireMux.Error.TruncatedPayload(9, 1)
    val setup = WireMux.Error.InvalidPayloadLimit(0)
    val write = WireMux.Error.InvalidMessageLimit(2500001, 2500000)
    val cases = Vector(
      (NetworkError.Framing(truncated), PeerError.Framing(truncated), FailureClass.Misbehaved),
      (NetworkError.MuxSetup(setup), PeerError.MuxSetup(setup), FailureClass.Transient),
      (NetworkError.MuxWrite(write), PeerError.MuxWrite(write), FailureClass.Transient)
    )
    cases.foreach { case (network, peer, classification) =>
      assertEquals(Peer.networkFault(network), Fault.Failed(peer))
      assertEquals(FailurePolicy.classify(peer), classification)
    }
    assertEquals(
      PeerError.Framing(truncated).message,
      "protocol: multiplexer framing: EOF in mux payload"
    )
  }

  test("network-to-peer classification retains the original typed cause") {
    val malformed = xyz.kd5ujc.scarab.protocol.ProtocolError.ExpectedSerializedBlockTag24
    val network: xyz.kd5ujc.scarab.network.NetworkError.Decode =
      xyz.kd5ujc.scarab.network.NetworkError.Decode(3, malformed)
    assertEquals(Peer.networkFault(network), Fault.Failed(PeerError.Protocol(network)))
    val cause = new java.io.IOException("connection refused")
    val connect: xyz.kd5ujc.scarab.network.NetworkError.Connect =
      xyz.kd5ujc.scarab.network.NetworkError.Connect("peer", cause)
    assertEquals(
      Peer.networkFault(connect),
      Fault.Failed(PeerError.Unavailable(PeerError.Unavailability.Network(connect)))
    )
  }

  test("peer address validation retains the rejected input") {
    assertEquals(Peer.parseAddress("invalid"), Left(Peer.AddressError.Invalid("invalid")))
  }

  test("candidate publication coalesces an unpublished fork into one rollback and new prefix") {
    val first = header(1, 1)
    val old = header(2, 1)
    val replacement = header(3, 1)
    val before = ChainSelection.Candidate(ChainSync.Point.Origin, Vector(first, old))
    val latest = ChainSelection.Candidate(ChainSync.Point.Origin, Vector(first, replacement))
    val tip = ChainSync.Tip(replacement.point, ChainSync.UInt64.saturating(3))
    val result = Peer.publication(before, latest, tip, 128)
    assertEquals(
      result,
      Right(
        latest -> Vector(
          SyncEvent.RollBackward(first.point, tip),
          SyncEvent.RollForward(replacement, tip)
        )
      )
    )
  }

  test("candidate publication splits body work without dropping validated headers") {
    val headers = Vector.tabulate(10)(i => header(i + 1, 1))
    val latest = ChainSelection.Candidate(ChainSync.Point.Origin, headers)
    val tip = ChainSync.Tip(headers.last.point, ChainSync.UInt64.saturating(10))
    val initial = ChainSelection.Candidate(ChainSync.Point.Origin, Vector.empty)
    val (last, events) = (0 until 4).foldLeft(initial -> Vector.empty[SyncEvent]) {
      case ((previous, seen), _) =>
        val (after, next) =
          Peer.publication(previous, latest, tip, 3).fold(e => fail(e.message), identity)
        (after, seen ++ next)
    }
    assertEquals(last, latest)
    assertEquals(events, headers.map(h => SyncEvent.RollForward(h, tip)))
  }

  test("detached publication requests a typed reintersection instead of inventing a rollback") {
    val previous = ChainSelection.Candidate(header(1, 1).point, Vector(header(2, 1)))
    val latest = ChainSelection.Candidate(header(3, 1).point, Vector(header(4, 1)))
    val tip = ChainSync.Tip(latest.tip, ChainSync.UInt64.saturating(4))
    assertEquals(
      Peer.publication(previous, latest, tip, 128),
      Left(PeerError.Divergence.DetachedCandidate(latest.base))
    )
  }
