// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.protocol.EbPoint
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}

/** Which of a point's two independent offers a LeiosNotify message makes (`OfferedBodyOrClosure`,
  * `LeiosDemoLogic.hs` 1989-1990 at ouroboros-consensus 2416493b).
  */
enum OfferedPart derives CanEqual:
  case Body, Closure

/** Why a LeiosNotify client refuses an announcement it validated (`AnnouncementInvalidity`,
  * `LeiosDemoLogic/Announcements/Validate.hs` 61-99 at ouroboros-consensus 2416493b). The
  * reference's `NoAnnouncement` is not a case: the client reads the announcement before it
  * validates (`mkAnnouncingHeader`), so a header without one never reaches the validation
  * ([[LeiosNotifyFault.AnnouncementMissing]]).
  */
enum AnnouncementInvalidity:
  /** The announced slot is past the forecast's range from the immutable tip (`OutsideHorizon`). */
  case OutsideHorizon

  /** The announced slot is below the immutable tip's (`SlotBeforeImmutableTip`). */
  case SlotBeforeImmutableTip

  /** The relaxed, out-of-context protocol checks refused the header (`HeaderInvalid`): the
    * announcement's size, the election proof, the signature and the certificate.
    */
  case HeaderInvalid(error: Praos2Error)

  /** An envelope check of the view refused the header (`RbHeaderEnvelopeInvalid`): its era, the
    * node's major version, the header's and the body's size.
    */
  case EnvelopeInvalid(error: EnvelopeError)

  def message: String = this match
    case OutsideHorizon => "the announced slot is past the forecast range of the immutable tip"
    case SlotBeforeImmutableTip => "the announced slot is below the immutable tip"
    case HeaderInvalid(e)       => s"the announcing header is invalid: ${e.message}"
    case EnvelopeInvalid(e)     => s"the announcing header's envelope is invalid: ${e.message}"

/** Why a LeiosNotify client refuses an announcement (`ErrAnnouncement`,
  * `LeiosDemoLogic/Announcements.hs` 126-143 at ouroboros-consensus 2416493b).
  */
enum AnnouncementError:
  /** The peer sent this announcement before (`ErrRepeat`). */
  case Repeat

  /** The peer sent two announcements for this election already (`ErrThird`). */
  case Third

  /** The announcement is invalid (`ErrInvalid`). */
  case Invalid(invalidity: AnnouncementInvalidity)

  /** Its slot's onset is older than a client tolerates (`ErrTooOld`, `maxAnnouncementAgeRecv`). */
  case TooOld

  def message: String = this match
    case Repeat     => "an announcement the peer sent before"
    case Third      => "a third announcement for one election"
    case Invalid(i) => i.message
    case TooOld     => "an announcement older than the client tolerates"

/** Why a LeiosNotify client refuses an offer (`ExnLeiosInvalidOffer`, `LeiosDemoLogic.hs` 1875-1946
  * at ouroboros-consensus 2416493b). The sizes are the reference's `BytesSize`, a `Word32`.
  */
enum InvalidOffer:
  /** A body offer of an endorser block this peer never announced, with the size it claimed. */
  case BlockOfferWithoutAnnouncement(point: EbPoint, claimed: Word32)

  /** A body offer claiming no byte, or more than any endorser block may have: the size claimed and
    * the bound.
    */
  case BlockOfferTooBig(point: EbPoint, claimed: Word32, bound: Word32)

  /** A closure offer of an endorser block this peer never announced. */
  case ClosureOfferWithoutAnnouncement(point: EbPoint)

  /** A second offer of a part this peer offered already. */
  case RepeatedOffer(point: EbPoint, part: OfferedPart)

  /** An offer below the slot this peer's announcements were pruned to. */
  case OfferTooOld(point: EbPoint, prunedTo: Word64)

  def message: String =
    def at(p: EbPoint) = s"${p.hash.bytes.hex} at slot ${p.slot.toBigInt}"
    this match
      case BlockOfferWithoutAnnouncement(p, size) =>
        s"a body offer of ${at(p)} (${size.toLong} bytes) the peer never announced"
      case BlockOfferTooBig(p, size, bound) =>
        s"a body offer of ${at(p)} at ${size.toLong} bytes, outside 1 to ${bound.toLong}"
      case ClosureOfferWithoutAnnouncement(p) =>
        s"a closure offer of ${at(p)} the peer never announced"
      case RepeatedOffer(p, part) => s"a second $part offer of ${at(p)}"
      case OfferTooOld(p, slot)   => s"an offer of ${at(p)} below the pruned slot ${slot.toBigInt}"

/** What a LeiosNotify client ends the connection over: the exceptions its handler throws, which no
  * rethrow policy names (`hLeiosNotifyClient`, `NodeToNode.hs` 615-768 at ouroboros-consensus
  * 2416493b).
  */
enum LeiosNotifyFault extends DomainError:
  /** An announcement whose header announces no endorser block (`ExnLeiosBlockAnnouncementMissing`).
    */
  case AnnouncementMissing

  /** An announcement refused, by the announcing header's slot and hash
    * (`ExnInvalidLeiosAnnouncement`, `ReactToAnnouncementError`).
    */
  case Announcement(slot: BigInt, hash: xyz.kd5ujc.scarab.codec.Bytes, error: AnnouncementError)

  /** An announcement whose slot the immutable tip's era history cannot place on the clock, by the
    * announcing header's slot and hash: the `PastHorizonException` of `judgeHeaderArrival`
    * (`announcementValidity`, `LeiosDemoLogic.hs` 2063-2075).
    */
  case OnsetUnknown(slot: BigInt, hash: xyz.kd5ujc.scarab.codec.Bytes)

  /** An offer refused (`ExnLeiosInvalidOffer`). */
  case Offer(offer: InvalidOffer)

  /** `MsgCanceled` before the client quit (`LeiosNotifyUnexpectedMsgCancel`). */
  case UnexpectedCancel

  def message: String = this match
    case AnnouncementMissing => "an announcement whose header announces no endorser block"
    case Announcement(slot, hash, error) =>
      s"the announcement of ${hash.hex} at slot $slot is refused: ${error.message}"
    case OnsetUnknown(slot, hash) =>
      s"the announcement of ${hash.hex} at slot $slot is past the era history's horizon"
    case Offer(offer)     => offer.message
    case UnexpectedCancel => "a MsgCanceled before the client quit"
