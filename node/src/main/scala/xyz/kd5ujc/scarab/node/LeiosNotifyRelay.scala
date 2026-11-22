// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Resource, Unique}
import cats.effect.std.{AtomicCell, Queue}
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import scala.collection.immutable.{Queue as Fifo, SortedMap}
import xyz.kd5ujc.scarab.consensus.leios.{AnnouncingHeader, ElId, ElState, LeiosAnnouncements}
import xyz.kd5ujc.scarab.consensus.leios.LeiosOrders.given
import xyz.kd5ujc.scarab.crypto.bls.Vote
import xyz.kd5ujc.scarab.ledger.OfferedPart
import xyz.kd5ujc.scarab.network.LeiosNotifyServer
import xyz.kd5ujc.scarab.network.LeiosNotifyServer.{Reply, Requested}
import xyz.kd5ujc.scarab.protocol.{EbPoint, HfcHeader}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64
import xyz.kd5ujc.scarab.storage.LeiosEbNotification

/** Whether an announcement or an offer is relayed downstream (`ShouldRelay`,
  * `LeiosDemoLogic/Announcements.hs` 320-328 at ouroboros-consensus 2416493b).
  */
enum ShouldRelay derives CanEqual:
  case DoRelay, DoNotRelay

/** How many slots past the immutable tip an endorser block must be to be offered
  * (`LeiosMinOfferLead`, `LeiosDemoLogic.hs` 2365-2385 at ouroboros-consensus 2416493b): not a
  * protocol parameter, each node configures its own (`LeiosMinOfferLead` of the node's
  * configuration, cardano-node b004fe0c `Configuration/POM.hs:444`).
  */
final case class LeiosMinOfferLead(slots: Word64):
  /** `leiosOfferRelayDecision` (`LeiosDemoLogic.hs` 2353-2363): an endorser block of `slot` is
    * offered unless `slot < immTip + lead`, the origin's slot 0, the sum taken in `Word64` and so
    * modulo 2^64 as the reference adds.
    */
  def decision(immTip: Option[Word64], slot: Word64): ShouldRelay =
    val bound = Word64.fromUnsigned((immTip.fold(BigInt(0))(_.toBigInt) + slots.toBigInt).toLong)
    if slot.toBigInt < bound.toBigInt then ShouldRelay.DoNotRelay else ShouldRelay.DoRelay

object LeiosMinOfferLead:
  /** `defaultLeiosMinOfferLead` (`LeiosDemoLogic.hs` 2384-2385): an hour of one-second slots. */
  val Default: LeiosMinOfferLead = LeiosMinOfferLead(Word64.fromUnsigned(3600L))

/** One downstream peer's LeiosNotify queue, its credits and what it has been announced
  * (`OutgoingLeiosNotify` and the credit counter, `NodeToNode.hs` 408-428 and 769-785 at
  * ouroboros-consensus 2416493b): `credits` the requests no reply is queued for yet, `messages` the
  * replies queued, served first in first out, and `announced` the points whose announcement was
  * queued to this peer with the parts since offered, kept to the immutable tip.
  */
final case class LeiosNotifyOutgoing(
    credits: Int,
    messages: Fifo[Reply],
    announced: SortedMap[EbPoint, Set[OfferedPart]]
):
  /** `incr`: one credit more, none where the peer already holds `depth`. */
  def requested(depth: Int): Option[LeiosNotifyOutgoing] =
    Option.unless(credits == depth)(copy(credits = credits + 1))

  /** `tryEnqueue` through the peer's `QueueAnnouncementView` (`Announcements.hs` 404-414 and
    * `NodeToNode.hs` 797-813): the announcement queued and its point recorded, keeping what was
    * offered for it; none where the peer has no credit, which drops it and records nothing.
    */
  def announcing(anc: AnnouncingHeader): Option[LeiosNotifyOutgoing] =
    Option.when(credits > 0)(
      LeiosNotifyOutgoing(
        credits - 1,
        messages.enqueue(Reply.Announcement(HfcHeader.Dijkstra(anc.header.raw))),
        announced.updatedWith(anc.fields.point)(_.orElse(Some(Set.empty)))
      )
    )

  /** `offer` and the pump's enqueue (`NodeToNode.hs` 885-933): the offer of `part` of `point`,
    * `reply`, is queued and recorded where it is to be relayed, this peer was announced the point,
    * the part was not offered to it, and it has a credit; otherwise it is dropped.
    */
  def offering(
      point: EbPoint,
      part: OfferedPart,
      reply: Reply,
      relay: ShouldRelay
  ): LeiosNotifyOutgoing =
    relay match
      case ShouldRelay.DoNotRelay => this
      case ShouldRelay.DoRelay =>
        announced.get(point) match
          case Some(offered) if !offered.contains(part) && credits > 0 =>
            LeiosNotifyOutgoing(
              credits - 1,
              messages.enqueue(reply),
              announced.updated(point, offered + part)
            )
          case Some(_) | None => this

  /** The pump's enqueue of a vote of the vote state's subscription (`pumpNext`'s `getNextVote`,
    * `NodeToNode.hs` 859 and 915-933): `MsgLeiosVotes` of the one vote, queued where the peer has a
    * credit, dropped otherwise.
    */
  def voting(vote: Vote): LeiosNotifyOutgoing =
    if credits > 0 then
      copy(credits = credits - 1, messages = messages.enqueue(Reply.Votes(Vector(vote))))
    else this

  /** `next`: the first queued reply, and the queue without it. */
  def next: Option[(Reply, LeiosNotifyOutgoing)] =
    messages.dequeueOption.map((reply, rest) => (reply, copy(messages = rest)))

  /** `pruneAnnounced` (`NodeToNode.hs` 857-882): without the points below the immutable tip's slot,
    * the origin's 0.
    */
  def prunedTo(immTip: Option[Word64]): LeiosNotifyOutgoing =
    val tip = immTip.fold(BigInt(0))(_.toBigInt)
    copy(announced = announced.dropWhile((point, _) => point.slot.toBigInt < tip))

object LeiosNotifyOutgoing:
  /** `emptyOutgoingLeiosNotify`, with no credit. */
  val empty: LeiosNotifyOutgoing = LeiosNotifyOutgoing(0, Fifo.empty, SortedMap.empty)

  /** The reply a store notification is offered as, and the part it offers (`pumpNext`,
    * `NodeToNode.hs` 838-856).
    */
  def offer(notification: LeiosEbNotification): (EbPoint, OfferedPart, Reply) =
    notification match
      case LeiosEbNotification.AcquiredEb(point, size) =>
        (point, OfferedPart.Body, Reply.BlockOffer(point, size))
      case LeiosEbNotification.AcquiredEbTxs(point) =>
        (point, OfferedPart.Closure, Reply.BlockTxsOffer(point))

/** The node's central announcement state (`CentralState`, `LeiosDemoLogic/Announcements.hs` 238-268
  * at ouroboros-consensus 2416493b): the announcements it counted as itself (`selfPeer`), each
  * running LeiosNotify server's queue (`queues`), and for each election the peers its first
  * announcement went to (`gate`), the only ones a second is sent to.
  */
final case class LeiosCentral[F[_]](
    selfPeer: LeiosAnnouncements[AnnouncingHeader],
    queues: Map[Unique.Token, SignallingRef[F, LeiosNotifyOutgoing]],
    gate: SortedMap[ElId, Set[Unique.Token]]
):
  /** `pruneCentralState` (`Announcements.hs` 284-293): the elections below the immutable tip go. */
  def prunedToImmTip(immTip: Word64): LeiosCentral[F] =
    copy(
      selfPeer = selfPeer.prunedToImmTip(immTip),
      gate = gate.dropWhile((el, _) => el.slot.toBigInt < immTip.toBigInt)
    )

  /** `insertPeerCentral` (`Announcements.hs` 295-306). */
  def inserted(peer: Unique.Token, queue: SignallingRef[F, LeiosNotifyOutgoing]): LeiosCentral[F] =
    copy(queues = queues.updated(peer, queue))

  /** `deletePeerCentral` (`Announcements.hs` 308-318): its queue, and the peer from the gate. */
  def deleted(peer: Unique.Token): LeiosCentral[F] =
    copy(
      queues = queues - peer,
      gate = gate.flatMap((el, peers) => Option.when(peers != Set(peer))(el -> (peers - peer)))
    )

object LeiosCentral:
  def empty[F[_]]: LeiosCentral[F] =
    LeiosCentral(LeiosAnnouncements.empty[AnnouncingHeader], Map.empty, SortedMap.empty)

  /** `onAnnouncementCentral` (`Announcements.hs` 338-414 at 2416493b): an announcement the node had
    * not counted is counted, relayed at once where `relay` says so, then published locally
    * (`publishLocally`); a repeat, or a third for its election, changes nothing. An election's
    * first announcement goes to every peer whose server runs and has a credit, which the gate
    * records; its second, the equivocation, only to the peers the first went to.
    */
  def announced[F[_]](
      central: AtomicCell[F, LeiosCentral[F]],
      relay: ShouldRelay,
      anc: AnnouncingHeader,
      publishLocally: F[Unit]
  )(using F: Async[F]): F[Unit] =
    val el = anc.fields.election
    def tryEnqueue(queue: SignallingRef[F, LeiosNotifyOutgoing]): F[Boolean] =
      queue.modify(o => o.announcing(anc).fold((o, false))((_, true)))
    def send(st: LeiosCentral[F], elSt: ElState[AnnouncingHeader]): F[Set[Unique.Token]] =
      elSt match
        case ElState.OneAnnouncement(_) =>
          st.queues.toVector
            .traverseFilter((peer, queue) => tryEnqueue(queue).map(Option.when(_)(peer)))
            .map(_.toSet)
        case ElState.TwoAnnouncements(_, _) =>
          st.gate
            .getOrElse(el, Set.empty)
            .toVector
            .flatMap(st.queues.get)
            .traverse_(tryEnqueue)
            .as(Set.empty)
    central.evalUpdate(st =>
      st.selfPeer.extendLive(el, anc) match
        case Left(_) => F.pure(st)
        case Right((elSt, self)) =>
          (relay match
            case ShouldRelay.DoNotRelay => F.pure(Set.empty[Unique.Token])
            case ShouldRelay.DoRelay    => send(st, elSt)
          ).flatMap { sent =>
            // `insertElBimapLs`: no entry for an empty set
            val gate =
              if sent.isEmpty then st.gate
              else st.gate.updated(el, st.gate.getOrElse(el, Set.empty) ++ sent)
            publishLocally.as(st.copy(selfPeer = self, gate = gate))
          }
    )

/** The node's LeiosNotify servers (`hLeiosNotifyServer`, `NodeToNode.hs` 769-936 at
  * ouroboros-consensus 2416493b) over the central announcement state, the store's notifications,
  * the immutable tip's slot and the node's offer lead.
  */
private[node] object LeiosNotifyRelay:
  /** One run of a peer's server: the store's notifications and the vote state's votes subscribed to
    * (`subscribeEbNotifications`, `subscribeVotes`) and an empty queue made, which is registered
    * with the central state for the run's life (`insertPeerCentral`, and `deletePeerCentral` in the
    * pump's `finally`). Its replies count requests into credits (`incr`) and take the queue's next
    * reply (`next`); its pump takes whichever of a notification and a vote comes first
    * (`pumpNext`), pruning first what the peer was announced to the immutable tip: a notification
    * becomes an offer, dropped where it is not to be relayed, was not announced to the peer, was
    * offered to it already, or finds it with no credit; a vote becomes `MsgLeiosVotes` of it,
    * dropped where the peer has no credit.
    */
  def server[F[_]](
      central: AtomicCell[F, LeiosCentral[F]],
      notifications: Resource[F, Queue[F, LeiosEbNotification]],
      votes: Resource[F, Queue[F, Vote]],
      immutableTip: F[Option[Word64]],
      lead: LeiosMinOfferLead,
      depth: Int = LeiosNotifyServer.PipelineDepth
  )(using F: Async[F]): Resource[F, LeiosNotifyServer.Replies[F]] =
    for
      subscription <- notifications
      voted <- votes
      queue <- Resource.eval(SignallingRef.of[F, LeiosNotifyOutgoing](LeiosNotifyOutgoing.empty))
      peer <- Resource.eval(F.unique)
      _ <- Resource.make(central.update(_.inserted(peer, queue)))(_ =>
        central.update(_.deleted(peer))
      )
    yield new LeiosNotifyServer.Replies[F]:
      def requested: F[Requested] =
        queue.modify(o =>
          o.requested(depth)
            .fold((o, Requested.Excessive(depth): Requested))((_, Requested.NotExcessive))
        )

      def next: F[Reply] =
        queue
          .modify(o => o.next.fold((o, Option.empty[Reply]))((reply, rest) => (rest, Some(reply))))
          .flatMap(_.fold(queue.waitUntil(_.messages.nonEmpty) *> next)(F.pure))

      def pump: F[Unit] =
        F.race(subscription.take, voted.take)
          .flatMap { next =>
            immutableTip.flatMap(tip =>
              queue.update { outgoing =>
                val pruned = outgoing.prunedTo(tip)
                next match
                  case Left(notification) =>
                    val (point, part, reply) = LeiosNotifyOutgoing.offer(notification)
                    pruned.offering(point, part, reply, lead.decision(tip, point.slot))
                  case Right(vote) => pruned.voting(vote)
              }
            )
          }
          .foreverM[Unit]
