// SPDX-License-Identifier: Apache-2.0
package lab.node.ntc

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.network.ChainSync.Point
import lab.network.LocalStateQuery.{Failure, Target}
import lab.network.runtime.LocalStateQueryServer
import lab.node.{NodeState, VolatileChain}
import lab.node.ledger.{TxIn, TxOut}
import lab.node.storage.UtxoStore

/** The LocalStateQuery interpreter over the node's volatile chain: acquiring takes the ledger state
  * retained for a point (the tip, the immutable tip or one of the last `k` blocks) and each query
  * is answered against that state alone.
  *
  * UTxO queries read the entries the retained state does not hold from the backing store; the store
  * follows the immutable tip, so a client that keeps a state acquired while the chain moves `k`
  * blocks on can see entries from a newer set (the reference holds a read-only fork for the
  * duration instead; roadmap F1).
  */
object StateQuerySource:
  def apply[F[_]: Sync](
      chain: Ref[F, VolatileChain[NodeState]],
      env: F[QueryEnv],
      store: UtxoStore[F]
  ): LocalStateQueryServer.Source[F] = new LocalStateQueryServer.Source[F]:
    def acquire(target: Target): F[Either[Failure, LocalStateQueryServer.Session[F]]] =
      chain.get.map(select(_, target)).map(_.map(session))

    private def select(c: VolatileChain[NodeState], target: Target): Either[Failure, QueryView] =
      def tipView = QueryView(c.tip, c.tipRef.map(_.blockNo), c.state)
      target match
        case Target.VolatileTip => Right(tipView)
        case Target.ImmutableTip =>
          Right(QueryView(c.anchor.point, c.anchor.blockNo, c.anchor.state))
        case Target.Specific(point) =>
          if point == c.anchor.point then Right(QueryView(point, c.anchor.blockNo, c.anchor.state))
          else
            c.blocks
              .collectFirst {
                case (ref, state) if ref.point == point =>
                  QueryView(point, Some(ref.blockNo), state)
              }
              .toRight {
                (point, c.anchor.point) match
                  case (Point.Block(slot, _), Point.Block(anchorSlot, _))
                      if slot.value < anchorSlot.value =>
                    Failure.PointTooOld
                  case (Point.Origin, _) => Failure.PointTooOld
                  case _                 => Failure.PointNotOnChain
              }

    private def session(view: QueryView): LocalStateQueryServer.Session[F] =
      new LocalStateQueryServer.Session[F]:
        def answer(raw: Bytes): F[Either[String, Bytes]] =
          NtcQuery.decode(raw) match
            case Left(error) => Sync[F].pure(Left(s"cannot decode ($error): ${raw.hex.take(80)}"))
            case Right(query) =>
              for
                e <- env
                entries <- fetch(view, QueryAnswers.utxoNeeds(query))
              yield QueryAnswers
                .answer(e, view, entries, query)
                .toRight(s"unsupported query $query")

    private def fetch(view: QueryView, needs: QueryAnswers.UtxoNeeds): F[Map[TxIn, TxOut]] =
      needs match
        case QueryAnswers.UtxoNeeds.Nothing => Sync[F].pure(Map.empty)
        case QueryAnswers.UtxoNeeds.Whole =>
          UtxoStore.materialize(store, view.ledger.utxo)
        case QueryAnswers.UtxoNeeds.Inputs(inputs) =>
          UtxoStore.complete(Some(store), view.ledger.utxo, inputs).map { u =>
            inputs.iterator.flatMap(i => u.get(i).map(i -> _)).toMap
          }
