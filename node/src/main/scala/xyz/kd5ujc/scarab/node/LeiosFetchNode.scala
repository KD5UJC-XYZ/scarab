// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Resource}
import cats.effect.std.Supervisor
import cats.mtl.Raise
import cats.syntax.all.*
import fs2.Stream
import xyz.kd5ujc.scarab.consensus.leios.{BytesSize, IsBigLedgerPeer, LeiosFetchStaticEnv, StdGen}
import xyz.kd5ujc.scarab.network.{
  LeiosFetchInitiator,
  LeiosFetchPipelined,
  LeiosFetchRequestHandler,
  MuxChannel,
  NetworkError
}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64
import xyz.kd5ujc.scarab.storage.{LeiosStore, LeiosStoreError}

/** The Leios build's endorser-block fetch of a node: the fetch logic's state shared by its peers,
  * what is done with their answers, and its store, which the LeiosFetch server answers from. The
  * peers are keyed by their connection's identity, as the reference keys them by `ConnectionId`: a
  * peer's next connection is another peer.
  */
final class LeiosFetchNode[F[_]] private (
    val shared: LeiosFetchShared[F, Long],
    arrival: LeiosFetchArrival[F, Long],
    store: LeiosStore[F]
)(using F: Async[F]):
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

  /** The LeiosFetch server's answers (`hLeiosFetchServer`, `NodeToNode.hs` 957-961 at
    * ouroboros-consensus 2416493b).
    */
  def responder: LeiosFetchRequestHandler[F] = LeiosFetchStoreHandler(store)

  /** The decision loop and its pruning for the life of the resource ([[LeiosFetchKernel.run]]). */
  def kernel[E](
      currentSlot: F[Option[Word64]],
      forecast: F[Word64 => Either[E, BytesSize]],
      immutableSlots: Stream[F, BigInt],
      defect: Throwable => F[Unit]
  ): Resource[F, Unit] =
    LeiosFetchKernel.run(
      shared,
      LeiosFetchStaticEnv.demo,
      currentSlot,
      forecast,
      immutableSlots,
      defect
    )

object LeiosFetchNode:
  /** Why the closure's bound of an offer that brought none cannot be forecast: the forecast of
    * `plvMaxEbTxsSize` from the immutable tip's ledger view is not wired yet (#54), so such an
    * offer's body is not asked for.
    */
  enum Unforecast derives CanEqual:
    case NotWired

  /** The node's fetch over `store`, the notifying store its writes go through, from the store's
    * complete closures at or after `immutableTip` ([[LeiosFetchKernel.initialized]]). The writes of
    * answers and their claims run under `supervisor`, the node's; the store's failure goes to
    * `fatal`, and an exception in one of them to `defect`, each of which stops the node.
    */
  def create[F[_]: Async](
      store: LeiosStore[F],
      prng: StdGen,
      immutableTip: Word64,
      supervisor: Supervisor[F],
      fatal: LeiosStoreError => F[Unit],
      defect: Throwable => F[Unit]
  )(using Raise[F, LeiosStoreError]): F[LeiosFetchNode[F]] =
    LeiosFetchKernel
      .initialized[F, Long](store, prng, immutableTip)
      .flatMap(LeiosFetchShared.create[F, Long])
      .map(shared =>
        new LeiosFetchNode(
          shared,
          LeiosFetchArrival(
            shared,
            store,
            LeiosFetchStaticEnv.demo,
            fatal,
            settling => supervisor.supervise(settling.handleErrorWith(defect)).void
          ),
          store
        )
      )
