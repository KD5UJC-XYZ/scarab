// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

import cats.Monad
import cats.data.EitherT
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.consensus.leios.LeiosOrders.given
import xyz.kd5ujc.scarab.ledger.{
  AnnouncementError,
  AnnouncementInvalidity,
  InvalidOffer,
  OfferedPart
}
import xyz.kd5ujc.scarab.protocol.EbPoint
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}

/** The verdict of an announcement's validation (`AnnouncementVerdict`,
  * `LeiosDemoLogic/Announcements.hs` 180-199 at ouroboros-consensus 2416493b): too old, invalid,
  * accepted from the peer but neither processed nor relayed, or processed with what the validation
  * found, `V`.
  */
enum AnnouncementVerdict[+V]:
  /** `VerdictTooOld`: raised as [[AnnouncementError.TooOld]]. */
  case TooOld

  /** `VerdictInvalid`: raised as [[AnnouncementError.Invalid]]. */
  case Invalid(invalidity: AnnouncementInvalidity)

  /** `VerdictIgnore`: a stale operational-certificate counter. */
  case Ignore

  /** `VerdictProcess`. */
  case Process(validated: V)

/** What one LeiosNotify client remembers of its upstream peer (`LeiosNotifyPeerState`,
  * `LeiosDemoLogic.hs` 2140-2164 at ouroboros-consensus 2416493b): the slot its announcements and
  * offers were pruned to, what it announced, for the dedup and the equivocation count, and which
  * parts it offered of each point, so that repeating either costs it the connection. The offers are
  * kept here, not read back from the fetch logic's offerings, which it evicts from as it acts.
  */
final case class LeiosNotifyPeerState(
    pruneSlot: Word64,
    announcements: LeiosAnnouncements[AnnouncingHeader],
    offers: SortedMap[EbPoint, PeerOffer]
):
  /** `pruneLeiosNotifyPeerStateToImmTip` (`LeiosDemoLogic.hs` 2166-2187): to the immutable tip's
    * slot, where it is above the slot pruned to; at the origin, or a tip not above it, unchanged.
    */
  def prunedToImmTip(immTip: Option[Word64]): LeiosNotifyPeerState =
    immTip match
      case Some(tip) if pruneSlot.toBigInt < tip.toBigInt =>
        LeiosNotifyPeerState(
          tip,
          announcements.prunedToImmTip(tip),
          offers.dropWhile((point, _) => point.slot.toBigInt < tip.toBigInt)
        )
      case Some(_) | None => this

  /** `announcedIt` (`LeiosDemoLogic.hs` 2244-2250): whether one of this peer's announcements in the
    * point's slot (`announcementsInSlot`, `Announcements.hs` 93-108) is of the point's endorser
    * block.
    */
  def announcedIt(point: EbPoint): Boolean =
    announcements.live
      .rangeFrom(ElId(point.slot, xyz.kd5ujc.scarab.codec.Bytes.empty))
      .takeWhile((el, _) => el.slot.toBigInt == point.slot.toBigInt)
      .valuesIterator
      .flatMap {
        case ElState.OneAnnouncement(a)     => Iterator(a)
        case ElState.TwoAnnouncements(a, b) => Iterator(a, b)
      }
      .exists(_.fields.ebHash == point.hash)

  /** `checkLeiosBlockOffer` (`LeiosDemoLogic.hs` 2207-2241): an offer below the slot pruned to is
    * too old; a second offer of the body a repeat; a size of no byte or above
    * [[LeiosNotifyPeerState.MaxEbBytesSize]] too big; an endorser block the peer never announced
    * refused; otherwise the body offer is recorded at the size claimed, which is free.
    */
  def blockOffer(point: EbPoint, claimed: BytesSize): Either[InvalidOffer, LeiosNotifyPeerState] =
    val seen = offers.getOrElse(point, PeerOffer.empty)
    if point.slot.toBigInt < pruneSlot.toBigInt then
      Left(InvalidOffer.OfferTooOld(point, pruneSlot))
    else if seen.offeredBody.isDefined then
      Left(InvalidOffer.RepeatedOffer(point, OfferedPart.Body))
    else if claimed == BytesSize.zero || claimed > LeiosNotifyPeerState.MaxEbBytesSize then
      Left(
        InvalidOffer.BlockOfferTooBig(
          point,
          Word32.wrap(claimed.toLong),
          Word32.wrap(LeiosNotifyPeerState.MaxEbBytesSize.toLong)
        )
      )
    else if !announcedIt(point) then
      Left(InvalidOffer.BlockOfferWithoutAnnouncement(point, Word32.wrap(claimed.toLong)))
    else Right(copy(offers = offers.updated(point, seen.copy(offeredBody = Some(claimed)))))

  /** `checkLeiosClosureOffer` (`LeiosDemoLogic.hs` 2256-2278): held to the same announcement as the
    * body offer and otherwise independent of it.
    */
  def closureOffer(point: EbPoint): Either[InvalidOffer, LeiosNotifyPeerState] =
    val seen = offers.getOrElse(point, PeerOffer.empty)
    if point.slot.toBigInt < pruneSlot.toBigInt then
      Left(InvalidOffer.OfferTooOld(point, pruneSlot))
    else if seen.closure == WhetherTxsClosureOffered.Offered then
      Left(InvalidOffer.RepeatedOffer(point, OfferedPart.Closure))
    else if !announcedIt(point) then Left(InvalidOffer.ClosureOfferWithoutAnnouncement(point))
    else
      Right(
        copy(offers = offers.updated(point, seen.copy(closure = WhetherTxsClosureOffered.Offered)))
      )

object LeiosNotifyPeerState:
  /** `emptyLeiosNotifyPeerState` (`LeiosDemoLogic.hs` 2158-2164): pruned to slot 0. */
  val empty: LeiosNotifyPeerState =
    LeiosNotifyPeerState(Word64.fromUnsigned(0L), LeiosAnnouncements.empty, SortedMap.empty)

  /** `maxLeiosEbBytesSize` (`LeiosDemoOnlyTestFetch.hs` 213-215 at ouroboros-consensus 2416493b):
    * the most bytes any endorser block may have, the bound of the guardrails script.
    */
  val MaxEbBytesSize: BytesSize = BytesSize.wrap(600L * 1000L)

  /** `onAnnouncement` (`LeiosDemoLogic/Announcements.hs` 201-232 at ouroboros-consensus 2416493b):
    * the announcement counted against this peer's first ([[LeiosAnnouncements.extendLive]]: a
    * repeat or a third is refused), and only then validated, the costly part; a verdict that
    * accepts updates the peer's dedup state, one that processes runs `process` first, and one that
    * rejects refuses the announcement.
    */
  def onAnnouncement[F[_]: Monad, V](
      validate: AnnouncingHeader => F[AnnouncementVerdict[V]],
      process: (AnnouncingHeader, V) => F[Unit],
      st: LeiosAnnouncements[AnnouncingHeader],
      anc: AnnouncingHeader
  ): EitherT[F, AnnouncementError, LeiosAnnouncements[AnnouncingHeader]] =
    for
      counted <- EitherT.fromEither[F](
        st.extendLive(anc.fields.election, anc).leftMap {
          case AnnouncementRefused.Repeat => AnnouncementError.Repeat
          case AnnouncementRefused.Third  => AnnouncementError.Third
        }
      )
      (_, next) = counted
      verdict <- EitherT.liftF(validate(anc))
      kept <- verdict match
        case AnnouncementVerdict.TooOld =>
          EitherT.leftT[F, LeiosAnnouncements[AnnouncingHeader]](
            AnnouncementError.TooOld
          )
        case AnnouncementVerdict.Invalid(invalidity) =>
          EitherT.leftT[F, LeiosAnnouncements[AnnouncingHeader]](
            AnnouncementError.Invalid(invalidity)
          )
        case AnnouncementVerdict.Ignore => EitherT.rightT[F, AnnouncementError](next)
        case AnnouncementVerdict.Process(validated) =>
          EitherT.liftF(process(anc, validated).as(next))
    yield kept
