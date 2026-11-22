// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Ref}
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import java.time.Instant
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.consensus.HeaderRules
import xyz.kd5ujc.scarab.consensus.Praos2
import xyz.kd5ujc.scarab.consensus.leios.{
  AddVoteResult,
  AnnouncementVerdict,
  AnnouncingHeader,
  BytesSize,
  LeiosNotifyPeerState,
  LeiosRollForward,
  PeerOffer,
  VoteTally,
  WhetherTxsClosureOffered
}
import xyz.kd5ujc.scarab.crypto.bls.{RbHash, Vote}
import xyz.kd5ujc.scarab.ledger.{
  InvalidOffer,
  LeiosHeader,
  LeiosNotifyFault,
  PeerError,
  VerificationDefect
}
import xyz.kd5ujc.scarab.network.{LeiosNotification, NetworkError}
import xyz.kd5ujc.scarab.protocol.{EbPoint, HfcHeader}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64
import xyz.kd5ujc.scarab.storage.{LeiosStore, LeiosStoreError}

/** What a LeiosNotify client of the Leios build reads of the node: the immutable tip, read once for
  * each reply, as the reference's handler reads the immutable ledger once
  * (`ChainDB.getImmutableLedger`, `NodeToNode.hs` 640 and 670 at ouroboros-consensus 2416493b), and
  * the wait until the immutable tip can forecast the current slot (`awaitImmTipCanForecastNow`,
  * `LeiosDemoLogic.hs` 2012-2045).
  */
trait LeiosNotifyView[F[_]]:
  def immutable: F[LeiosImmutableTip]
  def ready: F[Unit]

/** The immutable tip as one read of it: its slot, the onset of an announced slot as its era history
  * places it (`judgeHeaderArrival`), and the out-of-context check of an announcing header against
  * it (`validateAnnouncementHeader`, [[NodeHeaderRules.announcementRules]]).
  */
trait LeiosImmutableTip:
  def slot: Option[Word64]
  def onset(header: LeiosHeader): HeaderRules.TimeForecast
  def validate(header: LeiosHeader): AnnouncementCheck

/** What a LeiosNotify client tells of what it received, as the reference traces it: a peer's
  * announcement that the node took as new, and each offer (`TraceLeiosPeer`, `NodeToNode.hs`
  * 683-713 at ouroboros-consensus 2416493b), and each vote the vote state counted, with the
  * certificate it completed (`TraceLeiosVoteAcquired`, `TraceLeiosCertified`, `:730-765`).
  */
enum LeiosNotifyEvent:
  case Announced(connection: Long, point: EbPoint, relay: ShouldRelay, age: FiniteDuration)
  case BlockOffered(connection: Long, point: EbPoint, ebSize: BytesSize)
  case BlockTxsOffered(connection: Long, point: EbPoint)
  case VoteAcquired(connection: Long, vote: Vote, tally: VoteTally)
  case Certified(rbHash: RbHash)

/** The handling of one connection's LeiosNotify replies (`hLeiosNotifyClient`,
  * `Ouroboros/Consensus/Network/NodeToNode.hs` 615-768 at ouroboros-consensus 2416493b), over the
  * fetch logic's `shared` state, the peer's `vars`, the node's `central` announcements, its store
  * (whose failure goes to `fatal`), its vote state, and `peerState`, what the client remembers of
  * the peer ([[LeiosNotifyPeerState]]).
  */
private[node] final class LeiosNotifyHandling[F[_]](
    connection: Long,
    shared: LeiosFetchShared[F, Long],
    vars: LeiosPeerVars[F],
    central: LeiosChainSync.Central[F],
    store: LeiosStore[F],
    fatal: LeiosStoreError => F[Unit],
    votes: LeiosVotes[F],
    view: LeiosNotifyView[F],
    clock: SlotClock[F],
    onset: BigInt => F[Option[FiniteDuration]],
    events: LeiosNotifyEvent => F[Unit],
    peerState: Ref[F, LeiosNotifyPeerState],
    skew: FiniteDuration
)(using F: Async[F]):
  import LeiosNotifyHandling.{Validated, refused}

  /** One reply, refused through `Raise[F, PeerError]`; the caller ends the connection with it. */
  def handle(notification: LeiosNotification)(using Raise[F, PeerError]): F[Unit] =
    notification match
      case LeiosNotification.Announcement(bytes) =>
        // a header relayed as an announcement must carry one; another era's header carries none
        val announcing = HfcHeader.decode(bytes).toOption.flatMap {
          case HfcHeader.Dijkstra(raw) =>
            LeiosHeader.decode(raw).toOption.flatMap(AnnouncingHeader.of)
          case _: HfcHeader.Supported | _: HfcHeader.Unrecognised => None
        }
        announcing.fold(refused[F, Unit](LeiosNotifyFault.AnnouncementMissing))(announced)
      case LeiosNotification.BlockOffer(point, size) =>
        val claimed = BytesSize.wrap(size.toLong)
        checkOffer(_.blockOffer(point, claimed)) *>
          recordOffer(point, PeerOffer(None, Some(claimed), WhetherTxsClosureOffered.NotOffered)) *>
          events(LeiosNotifyEvent.BlockOffered(connection, point, claimed))
      case LeiosNotification.BlockTxsOffer(point) =>
        checkOffer(_.closureOffer(point)) *>
          recordOffer(point, PeerOffer(None, None, WhetherTxsClosureOffered.Offered)) *>
          events(LeiosNotifyEvent.BlockTxsOffered(connection, point))
      case LeiosNotification.Votes(vs) => vs.traverse_(vote)
      case LeiosNotification.Canceled  => refused(LeiosNotifyFault.UnexpectedCancel)

  /** `MsgLeiosBlockAnnouncement` (`NodeToNode.hs` 661-706): counted against this peer's
    * announcements and validated ([[LeiosNotifyPeerState.onAnnouncement]], [[validity]]); a new one
    * processed centrally ([[LeiosChainSync.processCentrally]], `ReceivedViaLeiosNotify`); the
    * peer's state then pruned to the immutable tip read before, as there.
    */
  private def announced(anc: AnnouncingHeader)(using Raise[F, PeerError]): F[Unit] =
    (view.immutable, peerState.get).flatMapN { (imm, st) =>
      LeiosNotifyPeerState
        .onAnnouncement[F, Validated](
          validity(imm, _),
          (a, v) =>
            LeiosChainSync.processCentrally(shared, central, store, fatal)(v.relay, a, v.onset) *>
              events(LeiosNotifyEvent.Announced(connection, a.fields.point, v.relay, v.age)),
          st.announcements,
          anc
        )
        .value
        .flatMap {
          case Left(error) =>
            refused[F, Unit](
              LeiosNotifyFault.Announcement(anc.header.slot, anc.headerHash, error)
            )
          case Right(announcements) =>
            peerState.set(st.copy(announcements = announcements).prunedToImmTip(imm.slot))
        }
    }

  /** `announcementValidity` (`LeiosDemoLogic.hs` 2048-2093): the arrival recorded, the slot's onset
    * placed by the immutable tip's history; a slot beyond the clock's skew refuses the header, one
    * within it is waited for once; then the age, which past `maxAnnouncementAgeRecv` is too old and
    * up to `maxAnnouncementAgeSend` relayed ([[LeiosNotifyHandling.ageVerdict]]); then the header's
    * check against the immutable tip, a stale counter's announcement ignored.
    */
  private def validity(imm: LeiosImmutableTip, anc: AnnouncingHeader)(using
      Raise[F, PeerError]
  ): F[AnnouncementVerdict[Validated]] =
    val header = anc.header
    clock.now.flatMap { arrival =>
      F.pure(imm.onset(header)).flatMap {
        case HeaderRules.TimeForecast.Invalid(error) =>
          PeerError.InvalidHeader(header.point, error).raise
        case HeaderRules.TimeForecast.Unknown(_) =>
          refused(LeiosNotifyFault.OnsetUnknown(header.slot, anc.headerHash))
        case HeaderRules.TimeForecast.Known(onsetPicos) =>
          val eligible = HeaderEligibility.forecast(header.slot, onsetPicos, arrival, skew) match
            case HeaderEligibility.Eligible => F.unit
            case HeaderEligibility.FarFuture(error) =>
              PeerError.InvalidHeader(header.point, error).raise[F, Unit]
            case HeaderEligibility.WaitUntil(until) => HeaderClock.delayOnce(clock, until)
          (eligible *> clock.now, onset(header.slot)).flatMapN { (now, at) =>
            val age = LeiosNotifyHandling.age(onsetPicos, now)
            LeiosNotifyHandling.ageVerdict(age) match
              case None => F.pure(AnnouncementVerdict.TooOld)
              case Some(relay) =>
                F.pure(imm.validate(header)).flatMap {
                  case AnnouncementCheck.Checked(Left(invalidity)) =>
                    F.pure(AnnouncementVerdict.Invalid(invalidity))
                  case AnnouncementCheck.Checked(Right(Praos2.OcinStaleness.Stale)) =>
                    F.pure(AnnouncementVerdict.Ignore)
                  case AnnouncementCheck.Checked(Right(Praos2.OcinStaleness.Fresh)) =>
                    F.pure(AnnouncementVerdict.Process(Validated(relay, at, age)))
                  case AnnouncementCheck.Refused(error) =>
                    PeerError.InvalidHeader(header.point, error).raise
                  case AnnouncementCheck.Undecided(defect) =>
                    PeerError.HeaderUndecided(header.point, defect).raise
                  case AnnouncementCheck.Unverifiable(defect) =>
                    PeerError
                      .Unverifiable(
                        VerificationDefect(header.point, VerificationDefect.Site.Header, defect)
                      )
                      .raise
                }
          }
      }
    }

  /** `checkOffer` (`NodeToNode.hs` 624-643): the peer's state pruned to the immutable tip first,
    * which keeps the too-old bound on the tip itself, then the check, committed or refused.
    */
  private def checkOffer(
      check: LeiosNotifyPeerState => Either[InvalidOffer, LeiosNotifyPeerState]
  )(using Raise[F, PeerError]): F[Unit] =
    (view.immutable, peerState.get).flatMapN { (imm, st) =>
      check(st.prunedToImmTip(imm.slot)).fold(
        offer => refused[F, Unit](LeiosNotifyFault.Offer(offer)),
        peerState.set
      )
    }

  /** `recordEbBodyOffer` and `recordEbClosureOffer` (`LeiosDemoLogic.hs` 1598-1619), through
    * `recordOffer` (`:1645-1650`): the offer joined into the peer's offerings and the decision
    * woken. An offer seeds nothing to fetch on its own: what is pursued comes from announcements.
    */
  private def recordOffer(point: EbPoint, offer: PeerOffer): F[Unit] =
    vars.offerings.update(LeiosRollForward.recordOffer(_, point, offer)) *> shared.wake

  /** One vote of `MsgLeiosVotes` (`NodeToNode.hs` 717-766): added to the vote state, where a vote
    * that does not count is dropped, as there; a vote counted is told, and the certificate it
    * completed. The vote state's defect is the node's, never a verdict on the vote.
    */
  private def vote(v: Vote)(using Raise[F, PeerError]): F[Unit] =
    Handle
      .allow[LeiosVotesDefect](votes.add(v).map(_.asRight[LeiosVotesDefect]))
      .rescue(d => F.pure(d.asLeft[AddVoteResult]))
      .flatMap {
        case Left(LeiosVotesDefect.Verification(native)) =>
          PeerError.LeiosVoteUndecided(v.announcingRbHash, native).raise
        case Left(LeiosVotesDefect.Aggregation(failed)) =>
          PeerError.LeiosVoteUndecided(failed.rbHash, failed.cause).raise
        case Left(LeiosVotesDefect.Committee(failed)) =>
          PeerError.LeiosVoteUndecided(v.announcingRbHash, failed).raise
        case Right(AddVoteResult.Added(tally, certificate)) =>
          events(LeiosNotifyEvent.VoteAcquired(connection, v, tally)) *>
            certificate.traverse_(_ => events(LeiosNotifyEvent.Certified(v.announcingRbHash)))
        case Right(
              AddVoteResult.NoCommittee | AddVoteResult.AlreadyKnown | AddVoteResult.Invalid(_)
            ) =>
          F.unit
      }

private[node] object LeiosNotifyHandling:
  /** What an announcement's validation found: whether to relay it, its slot's onset since the
    * system start, if known, and its age.
    */
  final case class Validated(relay: ShouldRelay, onset: Option[FiniteDuration], age: FiniteDuration)

  /** `maxAnnouncementAgeSend` (`LeiosDemoLogic.hs` 2318-2328 at ouroboros-consensus 2416493b): an
    * announcement older than this is not relayed.
    */
  val MaxAnnouncementAgeSend: FiniteDuration = 300.seconds

  /** `maxAnnouncementAgeRecv` (`LeiosDemoLogic.hs` 2330-2340): a peer that relays an announcement
    * older than this is disconnected (`ErrTooOld`).
    */
  val MaxAnnouncementAgeRecv: FiniteDuration = 600.seconds

  /** The age's part of `announcementValidity` (`LeiosDemoLogic.hs` 2423-2431): too old past the
    * receiving bound, none; relayed up to the sending bound, each inclusive.
    */
  def ageVerdict(age: FiniteDuration): Option[ShouldRelay] =
    if age > MaxAnnouncementAgeRecv then None
    else if age <= MaxAnnouncementAgeSend then Some(ShouldRelay.DoRelay)
    else Some(ShouldRelay.DoNotRelay)

  /** `diffRelTime now onset`: the time from the onset, in picoseconds since the epoch, to `now`, to
    * the nanosecond; never below zero, as the in-future check has waited until the onset.
    */
  def age(onsetPicos: BigInt, now: Instant): FiniteDuration =
    val nowPicos = BigInt(now.getEpochSecond) * BigInt(10).pow(12) + BigInt(now.getNano) * 1000
    ((nowPicos - onsetPicos) / 1000).max(0).toLong.nanos

  def refused[F[_], A](fault: LeiosNotifyFault)(using Raise[F, PeerError]): F[A] =
    PeerError.LeiosNotify(fault).raise[F, A]

  /** A refusal of the handler's, `PeerError`, as the connection's end: the reference's exception in
    * the client thread ends the connection (`AbortedBy`, which the peer's classification reads).
    */
  def ending[F[_]: Async, A](body: Raise[F, PeerError] ?=> F[A])(using
      Raise[F, NetworkError]
  ): F[A] =
    Handle
      .allow[PeerError](body.map(_.asRight[PeerError]))
      .rescue(e => Async[F].pure(e.asLeft[A]))
      .flatMap(
        _.fold(
          e => NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(e)).raise[F, A],
          Async[F].pure
        )
      )
