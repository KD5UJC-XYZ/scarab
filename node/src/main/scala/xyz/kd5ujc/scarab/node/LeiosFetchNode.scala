// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.std.{AtomicCell, Semaphore, Supervisor}
import cats.mtl.Raise
import cats.syntax.all.*
import fs2.Stream
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.consensus.leios.{
  AnnouncingHeader,
  BytesSize,
  IsBigLedgerPeer,
  LeiosFetchStaticEnv,
  LeiosNotifyPeerState,
  StdGen
}
import xyz.kd5ujc.scarab.network.{
  LeiosFetchInitiator,
  LeiosFetchPipelined,
  LeiosFetchRequestHandler,
  LeiosNotifyInitiator,
  LeiosNotifyPipelined,
  LeiosNotifyServer,
  MuxChannel,
  NetworkError
}
import xyz.kd5ujc.scarab.protocol.{EbPoint, LeiosNotify, LeiosTxHash}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.node.forge.{ForgedLeiosEb, OnForgedLeiosEb}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}
import xyz.kd5ujc.scarab.storage.{LeiosEbNotification, LeiosStore, LeiosStoreError}
import cats.effect.std.Queue
import xyz.kd5ujc.scarab.crypto.bls.Vote

/** The Leios build's endorser-block fetch of a node: the fetch logic's state shared by its peers,
  * what is done with their answers, and its store, which the LeiosFetch server answers from. The
  * peers are keyed by their connection's identity, as the reference keys them by `ConnectionId`: a
  * peer's next connection is another peer.
  */
final class LeiosFetchNode[F[_]] private (
    val shared: LeiosFetchShared[F, Long],
    arrival: LeiosFetchArrival[F, Long],
    store: LeiosStore[F],
    central: LeiosChainSync.Central[F],
    onset: BigInt => F[Option[FiniteDuration]],
    fatal: LeiosStoreError => F[Unit],
    notifications: Resource[F, Queue[F, LeiosEbNotification]],
    immutableTip: F[Option[Word64]],
    lead: LeiosMinOfferLead
)(using F: Async[F]):
  /** The ChainSync client's Leios handling of the connection whose vars are `vars`
    * ([[LeiosChainSync]]): the client runs inside the peer's bracket, as the reference's does
    * (`bracketLeiosPeer`, `NodeToNode.hs` 1473 at ouroboros-consensus 2416493b).
    */
  def chainSync(vars: LeiosPeerVars[F]): LeiosChainSync[F] =
    LeiosChainSync(shared, vars, central, store, onset, fatal)

  /** `use` inside `connection`'s bracket, with the ChainSync client's Leios handling of it. */
  def chainSyncOf[A](connection: Long)(use: LeiosChainSync[F] => F[A]): F[A] =
    shared.bracketPeer(connection, IsBigLedgerPeer.NotBigLedgerPeer)(vars => use(chainSync(vars)))

  /** The LeiosFetch client of `connection` 's hot activation (`aLeiosFetchClient` and
    * `hLeiosFetchClient`, `NodeToNode.hs` 937-956 and 1911-1935 at ouroboros-consensus 2416493b):
    * the pipelined client over the requests the decision queues for the peer, each answer handed to
    * the arrival, inside the peer's bracket. Every peer is taken for one that is not a big-ledger
    * peer: the hot connection is not told (#54).
    */
  def initiator(connection: Long): LeiosFetchInitiator[F] = new LeiosFetchInitiator[F]:
    def run(channel: MuxChannel[F], onStop: F[Unit] => Resource[F, Unit])(using
        Raise[F, NetworkError]
    ): F[Unit] =
      shared.bracketPeer(connection, IsBigLedgerPeer.NotBigLedgerPeer) { vars =>
        // the stop is the peer's queue's, written before the governor's Terminate anywhere else
        onStop(vars.requestsToSend.terminate).surround(
          LeiosFetchPipelined.run[F, Unit](
            channel,
            LeiosFetchJobs.next(
              vars.requestsToSend,
              LeiosFetchJobs.of(
                _,
                arrival.block(connection, _, _),
                arrival.blockTxs(connection, _, _)
              )
            )
          )
        )
      }

  /** The LeiosNotify client of `connection` 's hot activation (`aLeiosNotifyClient` and
    * `hLeiosNotifyClient`, `NodeToNode.hs` 615-768 and 1866-1889 at ouroboros-consensus 2416493b):
    * the pipelined client ([[LeiosNotifyPipelined]]) gated on `client` 's view, each reply handled
    * as the reference handles it ([[LeiosNotifyHandling]]) with a state of the peer's of its own,
    * inside the peer's bracket, which it shares with the connection's ChainSync and LeiosFetch
    * clients. It stops when the governor stops the hot protocols, through the stop it registers.
    */
  def notifyInitiator(connection: Long, client: LeiosNotifyContext[F]): LeiosNotifyInitiator[F] =
    new LeiosNotifyInitiator[F]:
      def run(channel: MuxChannel[F], onStop: F[Unit] => Resource[F, Unit])(using
          Raise[F, NetworkError]
      ): F[Unit] =
        shared.bracketPeer(connection, IsBigLedgerPeer.NotBigLedgerPeer) { vars =>
          (Deferred[F, Unit], Ref.of[F, LeiosNotifyPeerState](LeiosNotifyPeerState.empty))
            .flatMapN { (stop, peerState) =>
              val handling = notifyHandling(connection, vars, client, peerState)
              given LeiosNotify.AnnouncementReader = client.reader
              onStop(stop.complete(()).void).surround(
                LeiosNotifyPipelined.run[F](
                  channel,
                  stop,
                  client.view.ready,
                  notification => LeiosNotifyHandling.ending(handling.handle(notification))
                )
              )
            }
        }

  /** The handling of `connection` 's LeiosNotify replies ([[LeiosNotifyHandling]]) over the peer's
    * `vars` and its client's state of the peer, `peerState`.
    */
  private[node] def notifyHandling(
      connection: Long,
      vars: LeiosPeerVars[F],
      client: LeiosNotifyContext[F],
      peerState: Ref[F, LeiosNotifyPeerState]
  ): LeiosNotifyHandling[F] =
    LeiosNotifyHandling(
      connection,
      shared,
      vars,
      central,
      store,
      fatal,
      client.votes,
      client.view,
      client.clock,
      onset,
      client.events,
      peerState,
      client.skew
    )

  /** `onForgedLeiosEb` (`LeiosDemoLogic.hs` 2389-2460 at ouroboros-consensus 2416493b): an endorser
    * block the node forged, handed to the handlers a peer's announcement, body and closure go
    * through, with no peer, so that the fetch logic's state is what receiving it would make it.
    * First the announcement, relayed to the peers the node's LeiosNotify servers run for
    * ([[announcedLocally]]), then the body ([[LeiosFetchArrival.forgedBlock]]) and the closure
    * ([[LeiosFetchArrival.forgedTxs]]), each written before the next, whose writes the servers
    * offer. Not mirrored (#54): the forged closure's transactions are not recorded in the
    * transaction cache as applied (`ForgedTxs`' parity); no egress credit; no traces of the forged
    * and stored endorser block.
    */
  val onForged: OnForgedLeiosEb[F] = new OnForgedLeiosEb[F]:
    def forged(announcing: AnnouncingHeader, eb: ForgedLeiosEb)(using
        Raise[F, LeiosStoreError]
    ): F[Unit] =
      announcedLocally(announcing) *> arrival.forgedBlock(eb.point, eb.body) *>
        arrival.forgedTxs(eb.point, eb.closure)

  /** `processAnnouncementCentrally` of an announcement the node forged (`ForgedLocally`,
    * `Announcements.DoRelay`, `LeiosDemoLogic.hs` 1784-1853 and 2425-2436): counted once per
    * election as a peer's is, and relayed to the peers with a credit (`onAnnouncementCentral`,
    * [[LeiosCentral.announced]]); a new one then marks its endorser block as imminent in the fetch
    * logic's state (`markForged`, `markBodyImminent`), so that it is never fetched, even when a
    * peer relays the announcement back, records it in the transaction cache
    * (`recordAnnouncementInTxCache`) and its point in the store (`writeEbPoint`). It records no
    * onset, as the age of a forged block is not diffusion's.
    */
  private def announcedLocally(anc: AnnouncingHeader)(using Raise[F, LeiosStoreError]): F[Unit] =
    val fields = anc.fields
    announcedCentrally(
      anc,
      ShouldRelay.DoRelay,
      shared.outstanding.update(_.markBodyImminent(fields.ebHash, fields.election.slot)) *>
        shared.txCache.announced(anc) *>
        store.writeEbPoint(fields.point, Word32.wrap(fields.ebBodySize.toLong)).void
    )

  /** The LeiosFetch server's answers (`hLeiosFetchServer`, `NodeToNode.hs` 957-961 at
    * ouroboros-consensus 2416493b).
    */
  def responder: LeiosFetchRequestHandler[F] = LeiosFetchStoreHandler(store)

  /** The replies of one run of the LeiosNotify server (`hLeiosNotifyServer`, `NodeToNode.hs`
    * 769-936 at ouroboros-consensus 2416493b; [[LeiosNotifyRelay.server]]): the announcements the
    * node relays, the offers of what its store acquires, each held to the offer lead from the
    * immutable tip, and the votes of `votes`, the vote state's subscription
    * ([[LeiosVotes.subscribe]]).
    */
  def notifier(votes: Resource[F, Queue[F, Vote]]): Resource[F, LeiosNotifyServer.Replies[F]] =
    LeiosNotifyRelay.server(central, notifications, votes, immutableTip, lead)

  /** `processAnnouncementCentrally` of an announcement that is not a peer's ChainSync roll-forward,
    * such as one the node forged (`ForgedLocally`, `LeiosDemoLogic.hs` 1784-1853 and 2399-2425):
    * counted once per election, relayed where `relay` says so, then `publishLocally`
    * ([[LeiosCentral.announced]]).
    */
  def announcedCentrally(
      anc: AnnouncingHeader,
      relay: ShouldRelay,
      publishLocally: F[Unit]
  ): F[Unit] =
    LeiosCentral.announced(central, relay, anc, publishLocally)

  /** The decision loop and its pruning for the life of the resource ([[LeiosFetchKernel.run]]). */
  def kernel[E](
      currentSlot: F[Option[Word64]],
      forecast: F[Word64 => Either[E, BytesSize]],
      immutableSlots: Stream[F, BigInt],
      defect: Throwable => F[Unit]
  ): Resource[F, Unit] =
    LeiosFetchKernel.run(
      shared,
      central,
      LeiosFetchStaticEnv.demo,
      currentSlot,
      forecast,
      immutableSlots,
      defect
    )

/** What the node's LeiosNotify clients need beside the fetch: the vote state peers' votes go to,
  * the view of the node they read ([[LeiosNotifyView]]), the clock and the skew the in-future check
  * of an announcement takes, where what they receive is told, and the reader of an announced
  * header.
  */
final case class LeiosNotifyContext[F[_]](
    votes: LeiosVotes[F],
    view: LeiosNotifyView[F],
    clock: SlotClock[F],
    events: LeiosNotifyEvent => F[Unit],
    reader: LeiosNotify.AnnouncementReader,
    skew: FiniteDuration = HeaderEligibility.DefaultSkew
)

object LeiosFetchNode:
  /** Why the closure's bound of an offer that brought none cannot be forecast from the immutable
    * tip for its slot, so that such an offer's body is not asked for this pass (`forecastFor`
    * failing, `NodeKernel.hs:586-593` at ouroboros-consensus 2416493b).
    */
  enum Unforecast:
    /** The view of the slot is not forecast from the immutable tip ([[Praos2Headers.Unforecast]]).
      */
    case View(cause: Praos2Headers.Unforecast)

    /** The build runs no Praos2, and so has no Leios view ([[Praos2Headers.of]]). */
    case NoPraos2

  /** `forecastMaxEbTxsSize` (`NodeKernel.hs:586-593` at 2416493b): the bound on the closure of an
    * endorser block of `slot`, read from the ledger view forecast from `immutable`, the immutable
    * tip's state.
    */
  def forecast[S](
      rules: NodeHeaderRules[S],
      immutable: xyz.kd5ujc.scarab.consensus.Anchor[S],
      slot: Word64
  ): Either[Unforecast, BytesSize] =
    rules
      .leiosMaxEbTxsSize(immutable.state, immutable.block, slot.toBigInt)
      .toRight(Unforecast.NoPraos2)
      .flatMap(_.leftMap(Unforecast.View(_)).map(bound => BytesSize.wrap(bound.toLong)))

  /** The node's fetch over `store`, the notifying store its writes go through, from the store's
    * complete closures at or after `immutableTip` ([[LeiosFetchKernel.initialized]]). The writes of
    * answers and their claims run under `supervisor`, the node's, each on a slot of one writer's
    * queue its peers share ([[LeiosFetchArrival.WriterQueueDepth]]); the store's failure goes to
    * `fatal`, and an exception in one of them to `defect`, each of which stops the node. `onset`
    * places a slot on the wall clock since the system start. Its LeiosNotify servers ([[notifier]])
    * offer from `notifications`, the store's, held to `lead` from the slot of the immutable tip
    * `immutableTipNow` reads. A body's transactions are pulled from the node's mempool through
    * `pull` ([[LeiosMempoolPull]]), as the reference's fetch clients pull them
    * (`Ouroboros/Consensus/Network/NodeToNode.hs` 950-952 at 2416493b), and its handlers keep the
    * node's transaction cache `txCache` ([[LeiosTxCache]]).
    */
  def create[F[_]: Async](
      store: LeiosStore[F],
      prng: StdGen,
      immutableTip: Word64,
      supervisor: Supervisor[F],
      fatal: LeiosStoreError => F[Unit],
      defect: Throwable => F[Unit],
      onset: BigInt => F[Option[FiniteDuration]],
      notifications: Resource[F, Queue[F, LeiosEbNotification]],
      immutableTipNow: F[Option[Word64]],
      lead: LeiosMinOfferLead,
      pull: LeiosMempoolPull[F],
      txCache: LeiosTxCache[F],
      prevalidate: Option[(EbPoint, Vector[(LeiosTxHash, Bytes)]) => F[Unit]] = None
  )(using Raise[F, LeiosStoreError]): F[LeiosFetchNode[F]] =
    (
      LeiosFetchKernel
        .initialized[F, Long](store, prng, immutableTip)
        .flatMap(LeiosFetchShared.create[F, Long](_, txCache)),
      Semaphore[F](LeiosFetchArrival.WriterQueueDepth),
      AtomicCell[F].of(LeiosCentral.empty[F])
    ).mapN((shared, slots, central) =>
      new LeiosFetchNode(
        shared,
        LeiosFetchArrival(
          shared,
          store,
          LeiosFetchStaticEnv.demo,
          fatal,
          settling => supervisor.supervise(settling.handleErrorWith(defect)).void,
          slots,
          pull,
          prevalidate
        ),
        store,
        central,
        onset,
        fatal,
        notifications,
        immutableTipNow,
        lead
      )
    )
