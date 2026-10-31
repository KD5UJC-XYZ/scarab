// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import Handshake.*

/** Negotiation over every pair of offered version sets and every pair of flag settings, against the
  * reference's rules (`Version.hs` 129 to 148 of cardano-diffusion, `Handshake/Type.hs`
  * `handshakeServerPeer`): the highest version both offer, the same magic or a refusal, and the
  * agreed data the minimum of the diffusion modes (initiator only wins), the conjunction of peer
  * sharing and of Peras, the disjunction of query. Exhaustive rather than random: 7 x 7 version
  * sets, 16 x 16 flag settings, both roles.
  */
class HandshakeNegotiationSuite extends munit.FunSuite:
  private val ntn = Suite.NodeToNode
  private val all = NtnVersion.All

  /** Every non-empty subset of the versions. */
  private val sets: Vector[Vector[NtnVersion]] =
    (1 until (1 << all.size)).toVector.map(mask =>
      all.indices.toVector.filter(i => (mask & (1 << i)) != 0).map(all(_))
    )

  private final case class Flags(
      initiatorOnly: Boolean,
      peerSharing: Boolean,
      query: Boolean,
      peras: Boolean
  )
  private val flags: Vector[Flags] =
    (for
      a <- Vector(false, true); b <- Vector(false, true); c <- Vector(false, true);
      d <- Vector(false, true)
    yield Flags(a, b, c, d))

  private def offers(vs: Vector[NtnVersion], f: Flags, magic: Long = 164): Vector[(Int, Data)] =
    // the Peras flag exists from version 16 only: an offer of 14 or 15 cannot set it
    vs.map(v =>
      v.number -> Data(magic, f.initiatorOnly, f.peerSharing, f.query, f.peras && v.carriesPeras)
    )

  private def right[E, A](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)

  private def expected(client: Vector[NtnVersion], server: Vector[NtnVersion], c: Flags, s: Flags) =
    client.intersect(server).lastOption.map { v =>
      val peras = c.peras && s.peras && v.carriesPeras
      v -> Data(
        164,
        c.initiatorOnly || s.initiatorOnly,
        c.peerSharing && s.peerSharing,
        c.query || s.query,
        peras
      )
    }

  test("the server picks the highest common version and the reference's data, for all pairs") {
    var cases = 0
    for cs <- sets; ss <- sets; cf <- flags; sf <- flags
    do
      cases += 1
      val proposal = right(clientStart(ntn, offers(cs, cf)))._2
      // over the wire: the proposal is encoded and read back before the server sees it
      val wire = right(encode(ntn, proposal))
      val seen = decode(ntn, State.Propose, wire).fold(e => fail(e.message), identity)
      val (answer, result) = right(responder(ntn, offers(ss, sf), seen))
      expected(cs, ss, cf, sf) match
        case None =>
          assertEquals(result, Result.Rejected(Reason.VersionMismatch(ss.map(_.number))))
          assertEquals(answer, Message.Refuse(Reason.VersionMismatch(ss.map(_.number))))
        case Some((v, data)) =>
          val sideQuery = data.query
          result match
            case Result.Negotiated(rv, rd) =>
              assert(!sideQuery, s"a query was answered as a negotiation: $cs $ss $cf $sf")
              assertEquals((rv, rd), (v.number, data))
              // the client reads the server's answer and arrives at the same agreement
              val replyBytes = right(encode(ntn, answer))
              val reply =
                decode(ntn, State.Confirm, replyBytes).fold(e => fail(e.message), identity)
              val outcome = right(right(clientStart(ntn, offers(cs, cf)))._1.receive(reply))._2
              assertEquals(outcome, Result.Negotiated(v.number, data))
            case Result.QueryResult(_) =>
              assert(sideQuery, s"a negotiation was answered as a query: $cs $ss $cf $sf")
              answer match
                case Message.QueryReply(vs) => assertEquals(vs.map(_._1), ss.map(_.number))
                case other                  => fail(other.toString)
            case Result.Rejected(r) => fail(s"$cs $ss $cf $sf refused: $r")
    assertEquals(cases, 7 * 7 * 16 * 16)
  }

  test("a different magic is refused on the highest common version, never another") {
    for cs <- sets; ss <- sets do
      val proposal =
        right(clientStart(ntn, offers(cs, Flags(true, false, false, false), magic = 7)))._2
      val (_, result) =
        right(responder(ntn, offers(ss, Flags(true, false, false, false)), proposal))
      cs.intersect(ss).lastOption match
        case None => assert(result.isInstanceOf[Result.Rejected])
        case Some(v) =>
          assertEquals(result, Result.Rejected(Reason.Refused(v.number, "network magic mismatch")))
  }

  test("the Peras flag exists from version 16 only, in the data and in the offer") {
    val on = Data(164, peras = true)
    all.foreach { v =>
      val term = dataTerm(ntn, v.number, on)
      if v.carriesPeras then
        assertEquals(decodeData(ntn, v.number, right(term)), Right(on))
        assertEquals(right(term), right(dataTerm(ntn, v.number, on)))
      else assert(term.isLeft, s"v${v.number} accepted a Peras flag")
    }
    // a peer's version-16 data without the fifth element, and version-14 data with it, are refused
    assert(decodeData(ntn, 16, right(dataTerm(ntn, 14, Data(164)))).isLeft)
    assert(decodeData(ntn, 15, right(dataTerm(ntn, 16, Data(164)))).isLeft)
  }

  test("a server that does not offer the highest version of the client picks the next one down") {
    val c = offers(
      Vector(NtnVersion.V14, NtnVersion.V15, NtnVersion.V16),
      Flags(true, false, false, false)
    )
    val s = offers(NtnVersion.upTo(NtnVersion.LatestReleased), Flags(false, true, false, false))
    val proposal = right(clientStart(ntn, c))._2
    assertEquals(
      right(responder(ntn, s, proposal))._2,
      Result.Negotiated(15, Data(164, initiatorOnly = true, peerSharing = false))
    )
  }

  test("the typed versions: numbers, order, the release and the experimental maximum") {
    assertEquals(all.map(_.number), Vector(14, 15, 16))
    assertEquals(all.flatMap(v => NtnVersion.fromNumber(v.number)), all)
    assertEquals(NtnVersion.fromNumber(13), None)
    assertEquals(NtnVersion.fromNumber(17), None)
    assertEquals(NtnVersion.maximum(experimentalProtocols = false), NtnVersion.V15)
    assertEquals(NtnVersion.maximum(experimentalProtocols = true), NtnVersion.V16)
    assertEquals(NtnVersion.Latest, NtnVersion.values.last)
    assertEquals(NtnVersion.upTo(NtnVersion.V14), Vector(NtnVersion.V14))
    assertEquals(NtcVersion.All.map(_.number), (16 to 23).toVector)
    assertEquals(NtcVersion.LatestReleased.number, 23)
    assertEquals(NtcVersion.fromNumber(15), None)
    assertEquals(NtcVersion.fromNumber(24), None)
    // the handshake recognises exactly the typed versions
    assertEquals((0 to 40).filter(recognized(ntn, _)), all.map(_.number))
    assertEquals((0 to 40).filter(recognized(Suite.NodeToClient, _)), NtcVersion.All.map(_.number))
  }
end HandshakeNegotiationSuite
