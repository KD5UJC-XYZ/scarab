// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

import cats.data.Writer
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  AnnouncementError,
  AnnouncementInvalidity,
  EbAnnouncement,
  Header,
  HeaderVersionInfo,
  InvalidOffer,
  LeiosHeader,
  LeiosHeaderFields,
  OfferedPart,
  VrfCert
}
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}

/** What a LeiosNotify client remembers of its upstream peer ([[LeiosNotifyPeerState]]) against
  * `LeiosNotifyPeerState`, `checkLeiosBlockOffer`, `checkLeiosClosureOffer`, `announcedIt` and
  * `pruneLeiosNotifyPeerStateToImmTip` (`LeiosDemoLogic.hs` 2140-2278 at ouroboros-consensus
  * 2416493b), and the per-peer `onAnnouncement` (`LeiosDemoLogic/Announcements.hs` 201-232).
  */
class LeiosNotifyPeerStateSuite extends munit.FunSuite:
  private def bytes(n: Int, b: Int): Bytes = Bytes(Vector.fill(n)(b.toByte))
  private def eb(b: Int): EbHash = EbHash.fromBytes(bytes(32, b))
  private def w64(n: Long): Word64 = Word64.fromUnsigned(n)

  /** A header of `pool` at `slot` announcing `ebHash`, told apart by `hash`. */
  private def announcing(slot: Long, ebHash: Int, hash: Int = 1, pool: Int = 7): AnnouncingHeader =
    val header = LeiosHeader(
      bytes(8, hash),
      bytes(32, hash),
      bytes(32, hash),
      BigInt(1),
      BigInt(slot),
      None,
      bytes(32, pool),
      bytes(32, 3),
      VrfCert(bytes(64, 4), bytes(80, 5)),
      BigInt(0),
      bytes(32, 6),
      Header.OpCert(bytes(32, 8), BigInt(0), BigInt(0), bytes(64, 9)),
      LeiosHeaderFields(
        HeaderVersionInfo(BigInt(12), BigInt(0)),
        false,
        Some(EbAnnouncement(eb(ebHash), 1234L))
      ),
      bytes(448, 10)
    )
    AnnouncingHeader.of(header).getOrElse(fail("announces nothing"))

  private val a = announcing(100, ebHash = 1)
  private val point = a.fields.point
  private val size = BytesSize.wrap(1234L)

  /** The state with `anc` counted as the peer's. */
  private def withAnnounced(anc: AnnouncingHeader*): LeiosNotifyPeerState =
    anc.foldLeft(LeiosNotifyPeerState.empty) { (st, x) =>
      st.copy(announcements =
        st.announcements.extendLive(x.fields.election, x).fold(e => fail(e.toString), _._2)
      )
    }

  test("a peer announced a point when one of its announcements in the point's slot names it") {
    val st = withAnnounced(a, announcing(101, ebHash = 2, hash = 2))
    assert(st.announcedIt(point))
    assert(!st.announcedIt(EbPoint(point.slot, eb(2))))
    assert(st.announcedIt(EbPoint(w64(101), eb(2))))
    // an equivocation's second announcement counts too
    val second = announcing(100, ebHash = 3, hash = 3)
    assert(withAnnounced(a, second).announcedIt(EbPoint(point.slot, eb(3))))
    // another pool's announcement in the slot counts
    val other = announcing(100, ebHash = 4, hash = 4, pool = 9)
    assert(withAnnounced(other).announcedIt(EbPoint(point.slot, eb(4))))
  }

  test("a body offer is held to the announcement, offered once, and of 1 to 600000 bytes") {
    val st = withAnnounced(a)
    val offered = st.blockOffer(point, size).fold(e => fail(e.toString), identity)
    assertEquals(
      offered.offers(point),
      PeerOffer(None, Some(size), WhetherTxsClosureOffered.NotOffered)
    )
    assertEquals(
      offered.blockOffer(point, size),
      Left(InvalidOffer.RepeatedOffer(point, OfferedPart.Body))
    )
    assertEquals(
      st.blockOffer(point, BytesSize.zero),
      Left(InvalidOffer.BlockOfferTooBig(point, Word32.wrap(0), Word32.wrap(600000)))
    )
    assert(st.blockOffer(point, BytesSize.wrap(600000)).isRight)
    assertEquals(
      st.blockOffer(point, BytesSize.wrap(600001)),
      Left(InvalidOffer.BlockOfferTooBig(point, Word32.wrap(600001), Word32.wrap(600000)))
    )
    // the size need not be the announced one
    assert(st.blockOffer(point, BytesSize.wrap(99)).isRight)
    assertEquals(
      LeiosNotifyPeerState.empty.blockOffer(point, size),
      Left(InvalidOffer.BlockOfferWithoutAnnouncement(point, Word32.wrap(1234)))
    )
  }

  test("a body offer's guards run in the reference's order") {
    // too old before repeat before size before the announcement
    val pruned = withAnnounced(a).copy(pruneSlot = w64(101))
    assertEquals(
      pruned.blockOffer(point, BytesSize.zero),
      Left(InvalidOffer.OfferTooOld(point, w64(101)))
    )
    val offered = withAnnounced(a).blockOffer(point, size).fold(e => fail(e.toString), identity)
    assertEquals(
      offered.blockOffer(point, BytesSize.zero),
      Left(InvalidOffer.RepeatedOffer(point, OfferedPart.Body))
    )
    assertEquals(
      LeiosNotifyPeerState.empty.blockOffer(point, BytesSize.zero),
      Left(InvalidOffer.BlockOfferTooBig(point, Word32.wrap(0), Word32.wrap(600000)))
    )
  }

  test("a closure offer is held to the announcement and offered once, the body's aside") {
    val st = withAnnounced(a)
    val closure = st.closureOffer(point).fold(e => fail(e.toString), identity)
    assertEquals(closure.offers(point).closure, WhetherTxsClosureOffered.Offered)
    assertEquals(
      closure.closureOffer(point),
      Left(InvalidOffer.RepeatedOffer(point, OfferedPart.Closure))
    )
    // either may come first
    assert(closure.blockOffer(point, size).isRight)
    assertEquals(
      LeiosNotifyPeerState.empty.closureOffer(point),
      Left(InvalidOffer.ClosureOfferWithoutAnnouncement(point))
    )
    assertEquals(
      st.copy(pruneSlot = w64(101)).closureOffer(point),
      Left(InvalidOffer.OfferTooOld(point, w64(101)))
    )
  }

  test("the state is pruned to an immutable tip above its slot, the tip's own slot kept") {
    val st = withAnnounced(a, announcing(101, ebHash = 2, hash = 2))
      .blockOffer(point, size)
      .fold(e => fail(e.toString), identity)
    assertEquals(st.prunedToImmTip(None), st)
    val atTip = st.prunedToImmTip(Some(w64(100)))
    assertEquals(atTip.pruneSlot, w64(100))
    assert(atTip.announcedIt(point))
    val past = st.prunedToImmTip(Some(w64(101)))
    assertEquals(past.pruneSlot, w64(101))
    assert(!past.announcedIt(point))
    assertEquals(past.offers.get(point), None)
    // a tip not above the slot pruned to changes nothing
    assertEquals(past.prunedToImmTip(Some(w64(50))), past)
  }

  // --- onAnnouncement ---

  private def on(
      verdict: AnnouncementVerdict[Int],
      st: LeiosAnnouncements[AnnouncingHeader],
      anc: AnnouncingHeader
  ): (Either[AnnouncementError, LeiosAnnouncements[AnnouncingHeader]], Vector[Int]) =
    // what `process` ran, written as it runs
    type Ran[A] = Writer[Vector[Int], A]
    val (processed, result) = LeiosNotifyPeerState
      .onAnnouncement[Ran, Int](
        _ => Writer.value(verdict),
        (_, v) => Writer.tell(Vector(v)),
        st,
        anc
      )
      .value
      .run
    (result, processed)

  test("a repeat or a third announcement is refused before it is validated") {
    val one = withAnnounced(a).announcements
    val (repeat, _) = on(AnnouncementVerdict.Process(1), one, a)
    assertEquals(repeat, Left(AnnouncementError.Repeat))
    val two = withAnnounced(a, announcing(100, ebHash = 3, hash = 3)).announcements
    val (third, processed) = on(AnnouncementVerdict.Process(1), two, announcing(100, 4, hash = 4))
    assertEquals(third, Left(AnnouncementError.Third))
    assertEquals(processed, Vector.empty)
  }

  test("a verdict that rejects refuses; one that ignores counts; one that processes runs it") {
    val empty = LeiosAnnouncements.empty[AnnouncingHeader]
    assertEquals(on(AnnouncementVerdict.TooOld, empty, a)._1, Left(AnnouncementError.TooOld))
    assertEquals(
      on(AnnouncementVerdict.Invalid(AnnouncementInvalidity.OutsideHorizon), empty, a)._1,
      Left(AnnouncementError.Invalid(AnnouncementInvalidity.OutsideHorizon))
    )
    val (ignored, none) = on(AnnouncementVerdict.Ignore, empty, a)
    assertEquals(none, Vector.empty)
    assert(ignored.exists(_.live.contains(a.fields.election)))
    val (processed, ran) = on(AnnouncementVerdict.Process(5), empty, a)
    assertEquals(ran, Vector(5))
    assert(processed.exists(_.live.contains(a.fields.election)))
  }
