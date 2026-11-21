// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Order
import cats.effect.{Async, Ref, Resource}
import cats.effect.std.{AtomicCell, Queue}
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.leios.{
  ElId,
  IsBigLedgerPeer,
  LeiosFetchLogic,
  LeiosFetchRequest,
  LeiosOrders,
  LeiosOutstanding,
  PeerOffer
}
import xyz.kd5ujc.scarab.network.{
  LeiosFetchJob,
  LeiosFetchPipelined,
  LeiosFetchRequestHandler,
  NetworkError,
  Protocols
}
import xyz.kd5ujc.scarab.protocol.{
  EbHash,
  EbPoint,
  LeiosFetch,
  LeiosFetchRefusal,
  LeiosTxHash,
  LeiosWireError,
  ProtocolError,
  TxBitmaps
}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word16, Word32, Word64}
import xyz.kd5ujc.scarab.storage.{LeiosEb, LeiosStore, LeiosStoreError}

import LeiosOrders.given

/** What the fetch logic knows of one peer for the life of its connection (`LeiosPeerVars`,
  * `LeiosDemoTypes.hs` 450-515 of ouroboros-consensus 2416493b): whether it is a big-ledger peer,
  * its offers by point, the elections it claimed a certificate for, the newest slot it accepted a
  * ChainSync jump through, and the requests the decision assigned it that its LeiosFetch client has
  * not sent yet.
  */
final class LeiosPeerVars[F[_]] private (
    val whetherBigLedgerPeer: IsBigLedgerPeer,
    val offerings: AtomicCell[F, SortedMap[EbPoint, PeerOffer]],
    val certificationClaims: AtomicCell[F, SortedMap[ElId, EbHash]],
    val maxAcceptedJumpSlot: Ref[F, Option[Word64]],
    val requestsToSend: Queue[F, LeiosFetchRequest]
)

object LeiosPeerVars:
  /** `newLeiosPeerVars`. */
  def create[F[_]: Async](isBig: IsBigLedgerPeer): F[LeiosPeerVars[F]] =
    (
      AtomicCell[F].of(SortedMap.empty[EbPoint, PeerOffer]),
      AtomicCell[F].of(SortedMap.empty[ElId, EbHash]),
      Ref.of[F, Option[Word64]](None),
      Queue.unbounded[F, LeiosFetchRequest]
    ).mapN(new LeiosPeerVars(isBig, _, _, _, _))

/** The fetch logic's state shared by the node and its peers' mini-protocols: the decision's state
  * (`getLeiosOutstanding`, an `MVar` there, held here so a step may run effects while it holds it),
  * the signal that wakes the decision loop (`getLeiosReady`, an `MVar ()` that is put with
  * `tryPutMVar`), and every live peer's vars (`getLeiosPeersVars`).
  */
final class LeiosFetchShared[F[_], P] private (
    val outstanding: AtomicCell[F, LeiosOutstanding[P]],
    val ready: Queue[F, Unit],
    val peers: Ref[F, SortedMap[P, LeiosPeerVars[F]]]
)(using F: Async[F]):
  /** `tryPutMVar readyVar ()`: wake the decision loop, unless it is woken already. */
  def wake: F[Unit] = ready.tryOffer(()).void

  /** `bracketLeiosPeer` (`Ouroboros/Consensus/Network/NodeToNode.hs` 1812-1870 at 2416493b): the
    * peer's vars for the life of `use`, created by whichever of its mini-protocols comes first and
    * shared by the others; on the way out the peer is no longer live and every request it had in
    * flight is released (`removePeerFromOutstanding`). There is no count of users: a hot peer's
    * mini-protocols end together, so whichever ends first cleans up and the others find nothing.
    * The peer is gone from the live peers before its requests are released, so a decision that
    * reads the live peers while it holds the state never assigns to it again.
    */
  def bracketPeer[A](peer: P, isBig: IsBigLedgerPeer)(use: LeiosPeerVars[F] => F[A]): F[A] =
    Resource
      .make(
        LeiosPeerVars
          .create[F](isBig)
          .flatMap(fresh =>
            peers.modify(live =>
              live.get(peer).fold((live.updated(peer, fresh), fresh))(existing => (live, existing))
            )
          )
      )(_ => peers.update(_ - peer) *> outstanding.update(LeiosFetchLogic.removePeer(peer, _)))
      .use(use)

object LeiosFetchShared:
  def create[F[_]: Async, P: Order](initial: LeiosOutstanding[P]): F[LeiosFetchShared[F, P]] =
    given Ordering[P] = Order[P].toOrdering
    (
      AtomicCell[F].of(initial),
      Queue.bounded[F, Unit](1),
      Ref.of[F, SortedMap[P, LeiosPeerVars[F]]](SortedMap.empty)
    ).mapN(new LeiosFetchShared(_, _, _))

/** The boundary between the decision and the protocol: the request the decision made as the job the
  * LeiosFetch client sends (`nextLeiosFetchClientCommand`'s `g`, `LeiosDemoLogic.hs` 795-841 at
  * 2416493b). A transactions request goes on the wire as the union of its jobs' offsets, as
  * `offsetsToBitmap` writes it, each window a `Word16` and each mask a `Word64`; the jobs and their
  * commitments stay with the answer's continuation.
  */
object LeiosFetchJobs:
  def of[F[_]](
      request: LeiosFetchRequest,
      onBlock: (LeiosFetchRequest.Block, Vector[(LeiosTxHash, Word32)]) => F[Unit],
      onTxs: (LeiosFetchRequest.BlockTxs, Vector[Bytes]) => F[Unit]
  ): Either[LeiosWireError, LeiosFetchJob[F]] = request match
    case block: LeiosFetchRequest.Block =>
      Right(LeiosFetchJob.Block(block.point, txs => onBlock(block, txs)))
    case txs: LeiosFetchRequest.BlockTxs =>
      bitmaps(txs).map(b => LeiosFetchJob.BlockTxs(txs.point, b, answer => onTxs(txs, answer)))

  /** The wire bitmaps of a transactions request's jobs. */
  def bitmaps(request: LeiosFetchRequest.BlockTxs): Either[LeiosWireError, TxBitmaps] =
    val offsets =
      request.jobs.toSortedMap.values.foldLeft(scala.collection.immutable.SortedSet.empty[Int])(
        (all, job) => all ++ job.offsets.toSortedSet
      )
    LeiosFetchLogic
      .offsetsToBitmap(offsets)
      .traverse((window, mask) =>
        (Word16.from(BigInt(window)), Word64.from(BigInt(mask) & Word64.Max)).tupled
      )
      .map(TxBitmaps(_))

  /** `nextLeiosFetchClientCommand` (`LeiosDemoLogic.hs` 716-794 at 2416493b): the client's next
    * instruction. Its result when the governor asks it to stop; else the peer's next request to
    * send as its job; and when neither is ready, the wait for whichever comes first.
    */
  def next[F[_]](
      stopping: F[Boolean],
      stopped: F[Unit],
      requests: Queue[F, LeiosFetchRequest],
      job: LeiosFetchRequest => Either[LeiosWireError, LeiosFetchJob[F]]
  )(using
      F: Async[F],
      raise: Raise[F, NetworkError]
  ): F[
    Either[F[LeiosFetchPipelined.Instruction[F, Unit]], LeiosFetchPipelined.Instruction[F, Unit]]
  ] =
    def sent(request: LeiosFetchRequest): F[LeiosFetchPipelined.Instruction[F, Unit]] =
      job(request).fold(
        e => NetworkError.IllegalTransition(Protocols.LeiosFetch, ProtocolError.Leios(e)).raise,
        j => F.pure(Right(j))
      )
    val waiting: F[LeiosFetchPipelined.Instruction[F, Unit]] =
      stopping.ifM(
        F.pure(Left(())),
        F.race(stopped, requests.take).flatMap(_.fold(_ => F.pure(Left(())), sent))
      )
    stopping.ifM(
      F.pure(Right(Left(()))),
      requests.tryTake.flatMap(_.fold(F.pure(Left(waiting)))(r => sent(r).map(Right(_))))
    )

/** The LeiosFetch server's answers, from the node's endorser-block store (`leiosFetchHandler`,
  * `msgLeiosBlockRequest` and `msgLeiosBlockTxsRequest`, `LeiosDemoLogic.hs` 246-307 at 2416493b).
  * A body is answered as the store holds it, read back and bounded by `maxTxsPerEb` as the
  * reference's decoder bounds it. Transactions are answered in the order of the request's bitmaps;
  * each offset must come back, written, or the request named what this node never offered.
  */
object LeiosFetchStoreHandler:
  def apply[F[_]](store: LeiosStore[F])(using F: Async[F]): LeiosFetchRequestHandler[F] =
    new LeiosFetchRequestHandler[F]:
      def block(point: EbPoint)(using Raise[F, NetworkError]): F[Vector[(LeiosTxHash, Word32)]] =
        stored(store.lookupEbBody(point.hash)).flatMap {
          case None => refused(LeiosFetchRefusal.UnknownBlockRequested(point))
          case Some(bytes) =>
            LeiosEb.decode(bytes) match
              case Left(cause) => refused(LeiosFetchRefusal.StoredBodyUndecodable(point, cause))
              case Right(eb) if eb.size > LeiosFetch.MaxTxsPerEb =>
                refused(LeiosFetchRefusal.StoredBodyTooLarge(point, eb.size))
              case Right(eb) => F.pure(eb.transactions.toVector)
        }

      def blockTxs(point: EbPoint, bitmaps: TxBitmaps)(using
          Raise[F, NetworkError]
      ): F[Vector[Bytes]] =
        val offsets = LeiosFetchLogic.bitmapOffsets(
          bitmaps.windows.map((w, m) => (w.toInt, m.toBigInt.longValue))
        )
        stored(store.batchRetrieveTxs(point.hash, offsets)).flatMap { results =>
          if results.sizeIs != offsets.size then
            refused(LeiosFetchRefusal.UnknownTxsRequested(point, offsets))
          else
            results.traverse((offset, tx) =>
              if LeiosEb.written(tx) then F.pure(tx)
              else refused[F, Bytes](LeiosFetchRefusal.UnknownTxsRequested(point, Vector(offset)))
            )
        }

      private def stored[A](
          read: Raise[F, LeiosStoreError] ?=> F[A]
      )(using Raise[F, NetworkError]): F[A] =
        Handle
          .allow[LeiosStoreError](read)
          .rescue(e => refused[F, A](LeiosFetchRefusal.Store(e)))

  private def refused[F[_], A](refusal: LeiosFetchRefusal)(using Raise[F, NetworkError]): F[A] =
    NetworkError.LeiosFetchRefused(refusal).raise[F, A]
