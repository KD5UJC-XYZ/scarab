// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

import cats.Eq
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.Praos2
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.LeiosHeader
import xyz.kd5ujc.scarab.ledger.state.WithOrigin
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64

/** A header that announces an endorser block, with the announcement read from it once
  * (`AnnouncingHeader`, `LeiosDemoLogic.hs` 1739-1753 at ouroboros-consensus 2416493b): two are the
  * same announcement when their headers' hashes are, the one identity the dedup counts by.
  */
final case class AnnouncingHeader(headerHash: Bytes, fields: AnnouncementFields)

object AnnouncingHeader:
  given Eq[AnnouncingHeader] = Eq.instance((a, b) => a.headerHash == b.headerHash)

  /** `mkAnnouncingHeader` (`LeiosDemoLogic.hs` 1755-1761): the header's announcement, its election
    * the header's slot and the hash of its issuer's key (`headerElId`, `headerLeiosAnnouncement`,
    * `Shelley/Ledger/Leios.hs` 268-286); none where the header announces nothing.
    */
  def of(header: LeiosHeader): Option[AnnouncingHeader] =
    header.leios.announcement.map(a =>
      AnnouncingHeader(
        header.hash,
        AnnouncementFields(
          ElId(Word64.clamped(header.slot), Blake2b.hash224.hash(header.issuerVkey)),
          a.hash,
          BytesSize.wrap(a.size)
        )
      )
    )

/** The announcements of one election that the node has counted (`ElState`,
  * `LeiosDemoLogic/Announcements.hs` 66-78 at 2416493b): one, or two that differ, the first first.
  */
enum ElState[A]:
  case OneAnnouncement(first: A)
  case TwoAnnouncements(first: A, second: A)

/** Why an announcement is not counted (`ErrAnnouncement`'s counting cases, `Announcements.hs`
  * 126-143): the same one again, or a third for an election that has two.
  */
enum AnnouncementRefused derives CanEqual:
  case Repeat, Third

/** The announcements counted per election (`PeerState`, `Announcements.hs` 57-63 at 2416493b). The
  * node keeps one of its own as itself (`CentralState`'s `selfPeer`, 238-262), so that only a new
  * announcement is processed; the relay queues and the record of what was sent to whom belong to
  * the LeiosNotify server, which this node does not run (#54).
  */
final case class LeiosAnnouncements[A](live: SortedMap[ElId, ElState[A]]):
  /** `extendLive` (`Announcements.hs` 145-169): the election's first announcement, or a second that
    * differs from the first; neither a repeat nor a third.
    */
  def extendLive(el: ElId, anc: A)(using
      Eq[A]
  ): Either[AnnouncementRefused, (ElState[A], LeiosAnnouncements[A])] =
    val next: Either[AnnouncementRefused, ElState[A]] = live.get(el) match
      case None => Right(ElState.OneAnnouncement(anc))
      case Some(ElState.OneAnnouncement(x)) =>
        if x === anc then Left(AnnouncementRefused.Repeat)
        else Right(ElState.TwoAnnouncements(x, anc))
      case Some(ElState.TwoAnnouncements(_, _)) => Left(AnnouncementRefused.Third)
    next.map(st => (st, LeiosAnnouncements(live.updated(el, st))))

  /** `prunePeerState` (`Announcements.hs` 116-126): without the elections below the immutable tip's
    * slot; the tip's own is kept.
    */
  def prunedToImmTip(immTip: Word64): LeiosAnnouncements[A] =
    LeiosAnnouncements(live.dropWhile((el, _) => el.slot.toBigInt < immTip.toBigInt))

object LeiosAnnouncements:
  /** `emptyPeerState`. */
  def empty[A]: LeiosAnnouncements[A] = LeiosAnnouncements(SortedMap.empty)

/** A peer's roll-forward claimed two endorser blocks certified for one election
  * (`ExnLeiosTwoCertificationClaims`, `LeiosDemoLogic.hs` 1725-1731 at 2416493b): the election, the
  * endorser block claimed first and the one claimed now.
  */
final case class TwoCertificationClaims(election: ElId, alreadyClaimed: EbHash, claimed: EbHash)

/** The pure parts of a ChainSync roll-forward's Leios handling (`leiosMsgRollForwardCallback`,
  * `Ouroboros/Consensus/Network/NodeToNode.hs` 484-510 at ouroboros-consensus 2416493b).
  */
object LeiosRollForward:
  /** `protocolStateLeiosAnnouncement` (`Shelley/Ledger/Leios.hs` 327-340 at 2416493b): the
    * announcement the chain-dependent state retains from its last header, its election that
    * header's slot (`praosStateLastSlot`, slot 0 at the origin) and the hash of its issuer's key.
    */
  def stateAnnouncement(state: Praos2.State): Option[AnnouncementFields] =
    state.announcement.map(by =>
      AnnouncementFields(
        ElId(
          Word64.clamped(state.praos.lastSlot match
            case WithOrigin.At(slot) => slot.toBigInt
            case WithOrigin.Origin   => BigInt(0)),
          by.issuer.bytes
        ),
        by.announcement.hash,
        BytesSize.wrap(by.announcement.size)
      )
    )

  /** The offer side of `checkMsgRollForwardForLeiosOffers` (`LeiosDemoLogic.hs` 1671-1696): a
    * header whose body carries a certificate (`headerContainsLeiosCert`) offers the endorser block
    * its predecessor announced, read from the predecessor's chain-dependent state `predecessor`,
    * which the certifying header's own transition would overwrite; nothing for any other header.
    */
  def certifiedAnnouncement(
      header: LeiosHeader,
      predecessor: Praos2.State
  ): Option[AnnouncementFields] =
    Option
      .when(header.leios.blockBodyContainsLeiosCert)(())
      .flatMap(_ => stateAnnouncement(predecessor))

  /** `noteCertificationClaim` (`LeiosDemoLogic.hs` 1696-1723): the peer's claims with this one
    * counted; a claim of another endorser block for an election it already claimed is refused.
    */
  def noteCertificationClaim(
      claimed: SortedMap[ElId, EbHash],
      el: ElId,
      eb: EbHash
  ): Either[TwoCertificationClaims, SortedMap[ElId, EbHash]] =
    claimed.get(el) match
      case Some(already) if already != eb => Left(TwoCertificationClaims(el, already, eb))
      case Some(_) | None                 => Right(claimed.updated(el, eb))

  /** `recordCertRbOffer` 's offer (`LeiosDemoLogic.hs` 1621-1643): the body at the size the
    * announcement gave, the closure, and the bound on the closure of the predecessor's ledger view.
    */
  def certRbOffer(fields: AnnouncementFields, maxEbTxsSize: BytesSize): PeerOffer =
    PeerOffer(Some(maxEbTxsSize), Some(fields.ebBodySize), WhetherTxsClosureOffered.Offered)

  /** `recordOffer` 's update (`LeiosDemoLogic.hs` 1645-1650): `Map.insertWith (<>)`, the new offer
    * on the left.
    */
  def recordOffer(
      offers: SortedMap[EbPoint, PeerOffer],
      point: EbPoint,
      offer: PeerOffer
  ): SortedMap[EbPoint, PeerOffer] =
    offers.updated(point, offers.get(point).fold(offer)(offer.combine))
