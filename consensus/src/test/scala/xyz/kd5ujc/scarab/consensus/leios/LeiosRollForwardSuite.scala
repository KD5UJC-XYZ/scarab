// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.Praos2
import xyz.kd5ujc.scarab.consensus.state.PraosState
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  EbAnnouncement,
  Header,
  HeaderVersionInfo,
  LeiosHeader,
  LeiosHeaderFields,
  Nonce,
  VrfCert
}
import xyz.kd5ujc.scarab.ledger.state.{BlockIssuer, KeyHash, SlotNo, Sorted, WithOrigin}
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64

import LeiosOrders.given

/** The pure parts of a ChainSync roll-forward's Leios handling ([[LeiosRollForward]]) and the
  * central dedup of announcements ([[LeiosAnnouncements]]), against `LeiosDemoLogic.hs` 1671-1756
  * and `LeiosDemoLogic/Announcements.hs` 116-169 at ouroboros-consensus 2416493b.
  */
class LeiosRollForwardSuite extends munit.FunSuite:
  private def bytes(n: Int, b: Int): Bytes = Bytes(Vector.fill(n)(b.toByte))
  private def eb(b: Int): EbHash =
    EbHash.fromBytes(bytes(32, b))
  private val vkey = bytes(32, 7)

  private def header(
      slot: Long,
      cert: Boolean,
      announced: Option[EbAnnouncement],
      hash: Int = 1
  ): LeiosHeader =
    LeiosHeader(
      bytes(8, hash),
      bytes(8, hash),
      bytes(32, hash),
      BigInt(1),
      BigInt(slot),
      None,
      vkey,
      bytes(32, 3),
      VrfCert(bytes(64, 4), bytes(80, 5)),
      BigInt(0),
      bytes(32, 6),
      Header.OpCert(bytes(32, 8), BigInt(0), BigInt(0), bytes(64, 9)),
      LeiosHeaderFields(HeaderVersionInfo(BigInt(12), BigInt(0)), cert, announced),
      bytes(448, 10)
    )

  private val praos = PraosState(
    WithOrigin.Origin,
    Sorted.empty,
    Nonce.Neutral,
    Nonce.Neutral,
    Nonce.Neutral,
    Nonce.Neutral,
    Nonce.Neutral,
    Nonce.Neutral
  )

  /** The state after the announcing header at `slot`, as `Praos2.reupdate` leaves it: its slot the
    * last, and its issuer the hash of the header's key (`Praos.issuerKey`).
    */
  private def after(slot: Long, announcement: Option[EbAnnouncement]): Praos2.State =
    val issuer = KeyHash
      .fromBytes[BlockIssuer](Blake2b.hash224.hash(vkey))
      .fold(e => fail(e.toString), identity)
    Praos2.State(
      praos.copy(lastSlot = WithOrigin.At(SlotNo.fromUnsigned(slot))),
      announcement.map(Praos2.AnnouncedBy(issuer, _))
    )

  private val announcement = EbAnnouncement(eb(1), 1234L)

  test("an announcing header and the state after it name the same election and endorser block") {
    val fromHeader = AnnouncingHeader.of(header(40, cert = false, Some(announcement))).map(_.fields)
    val fromState = LeiosRollForward.stateAnnouncement(after(40, Some(announcement)))
    assertEquals(fromHeader, fromState)
    assertEquals(fromHeader.map(_.point), Some(EbPoint(Word64.clamped(40), eb(1))))
    assertEquals(fromHeader.map(_.ebBodySize), Some(BytesSize.wrap(1234L)))
  }

  test("a header without an announcement announces nothing") {
    assertEquals(AnnouncingHeader.of(header(40, cert = false, None)), None)
  }

  test("only a certifying header offers, the endorser block its predecessor announced") {
    val predecessor = after(40, Some(announcement))
    assertEquals(
      LeiosRollForward.certifiedAnnouncement(header(45, cert = true, None), predecessor),
      LeiosRollForward.stateAnnouncement(predecessor)
    )
    assertEquals(
      LeiosRollForward.certifiedAnnouncement(header(45, cert = false, None), predecessor),
      None
    )
    assertEquals(
      LeiosRollForward.certifiedAnnouncement(header(45, cert = true, None), after(40, None)),
      None
    )
  }

  test("a peer may claim one endorser block per election, as often as it likes, and no other") {
    val el = ElId(Word64.clamped(40), bytes(28, 2))
    val once = LeiosRollForward.noteCertificationClaim(SortedMap.empty, el, eb(1))
    assertEquals(once, Right(SortedMap(el -> eb(1))))
    assertEquals(once.flatMap(LeiosRollForward.noteCertificationClaim(_, el, eb(1))), once)
    assertEquals(
      once.flatMap(LeiosRollForward.noteCertificationClaim(_, el, eb(2))),
      Left(TwoCertificationClaims(el, eb(1), eb(2)))
    )
    val other = ElId(Word64.clamped(41), bytes(28, 2))
    assertEquals(
      once.flatMap(LeiosRollForward.noteCertificationClaim(_, other, eb(2))).map(_.size),
      Right(2)
    )
  }

  test(
    "a certifying roll-forward offers the body at its announced size, the closure and the bound"
  ) {
    val fields =
      AnnouncementFields(ElId(Word64.clamped(40), bytes(28, 2)), eb(1), BytesSize.wrap(9))
    val offer = LeiosRollForward.certRbOffer(fields, BytesSize.wrap(260000))
    assertEquals(
      offer,
      PeerOffer(
        Some(BytesSize.wrap(260000)),
        Some(BytesSize.wrap(9)),
        WhetherTxsClosureOffered.Offered
      )
    )
    // an offer of the body alone first: the new one's sizes win, the closure is offered by either
    val point = fields.point
    val older = PeerOffer(None, Some(BytesSize.wrap(5)), WhetherTxsClosureOffered.NotOffered)
    val recorded = LeiosRollForward.recordOffer(SortedMap(point -> older), point, offer)
    assertEquals(recorded, SortedMap(point -> offer))
    val later = PeerOffer(None, None, WhetherTxsClosureOffered.NotOffered)
    assertEquals(
      LeiosRollForward.recordOffer(recorded, point, later),
      SortedMap(point -> offer)
    )
  }

  test("an election's first announcement and a different second count; a repeat or a third not") {
    import cats.Eq
    given Eq[Int] = Eq.fromUniversalEquals
    val el = ElId(Word64.clamped(40), bytes(28, 2))
    val empty = LeiosAnnouncements.empty[Int]
    val one = empty.extendLive(el, 1)
    assertEquals(one.map(_._1), Right(ElState.OneAnnouncement(1)))
    val counted = one.fold(e => fail(e.toString), _._2)
    assertEquals(counted.extendLive(el, 1).map(_._1), Left(AnnouncementRefused.Repeat))
    val two = counted.extendLive(el, 2).fold(e => fail(e.toString), identity)
    assertEquals(two._1, ElState.TwoAnnouncements(1, 2))
    assertEquals(two._2.extendLive(el, 3).map(_._1), Left(AnnouncementRefused.Third))
  }

  test("the announcements are pruned below the immutable tip's slot, the tip's own kept") {
    import cats.Eq
    given Eq[Int] = Eq.fromUniversalEquals
    val pool = bytes(28, 2)
    val counted = (39 to 41).foldLeft(LeiosAnnouncements.empty[Int])((st, s) =>
      st.extendLive(ElId(Word64.clamped(s), pool), s).fold(e => fail(e.toString), _._2)
    )
    assertEquals(
      counted.prunedToImmTip(Word64.clamped(40)).live.keySet.map(_.slot.toBigInt),
      Set(BigInt(40), BigInt(41))
    )
  }
