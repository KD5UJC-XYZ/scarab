// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.NtcVersion
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.protocol.LocalStateQuery.{Failure, Target}
import xyz.kd5ujc.scarab.network.LocalStateQueryServer
import xyz.kd5ujc.scarab.network.LocalStateQueryServer.Refusal
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.node.read.ChainRead
import xyz.kd5ujc.scarab.ledger.{TxIn, TxOut}
import xyz.kd5ujc.scarab.storage.UtxoStore

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
  def apply[F[_]: Sync, S: ChainRead](
      chain: Ref[F, VolatileChain[S]],
      env: F[QueryEnv],
      store: UtxoStore[F],
      version: NtcVersion = NtcVersion.LatestReleased
  ): LocalStateQueryServer.Source[F] = new LocalStateQueryServer.Source[F]:
    override def forVersion(agreed: NtcVersion): LocalStateQueryServer.Source[F] =
      StateQuerySource(chain, env, store, agreed)

    def acquire(target: Target): F[Either[Failure, LocalStateQueryServer.Session[F]]] =
      chain.get.map(select(_, target)).map(_.map(session))

    private def select(c: VolatileChain[S], target: Target): Either[Failure, QueryView[S]] =
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

    private def session(view: QueryView[S]): LocalStateQueryServer.Session[F] =
      new LocalStateQueryServer.Session[F]:
        def answer(raw: Bytes): F[Either[Refusal, Bytes]] =
          NtcQuery.decode(raw, version) match
            case Left(error) =>
              Sync[F].pure(Left(Refusal.Undecodable(error.message, raw.hex.take(80))))
            case Right(query) =>
              val shown = NtcQuery.describe(query)
              for
                e <- env
                entries <- fetch(view, QueryAnswers.utxoNeeds(query))
                // A value outside its encoding makes the writer throw; the connection ends with the
                // query named, never silently.
                answered <- Sync[F]
                  .delay(QueryAnswers.answerOrWhy(e, view, entries, query, version))
                  .attempt
              yield answered match
                case Right(Right(result))                => Right(result)
                case Right(Left(Unanswered.Unsupported)) => Left(Refusal.Unsupported(shown))
                case Right(Left(Unanswered.Refused(error))) =>
                  Left(Refusal.Unwritable(shown, s"the state cannot be read: $error"))
                case Right(Left(Unanswered.Unwritable)) =>
                  Left(Refusal.Unwritable(shown, "the answer is out of range"))
                case Left(cause) => Left(Refusal.Unwritable(shown, cause.toString))

    private def fetch(view: QueryView[S], needs: QueryAnswers.UtxoNeeds): F[Map[TxIn, TxOut]] =
      needs match
        case QueryAnswers.UtxoNeeds.Nothing => Sync[F].pure(Map.empty)
        case QueryAnswers.UtxoNeeds.Whole =>
          UtxoStore.materialize(store, view.utxo)
        case QueryAnswers.UtxoNeeds.Addresses(addresses) =>
          UtxoStore.materializeAddresses(store, view.utxo, addresses)
        case QueryAnswers.UtxoNeeds.Inputs(inputs) =>
          UtxoStore.complete(Some(store), view.utxo, inputs).map { u =>
            inputs.iterator.flatMap(i => u.get(i).map(i -> _)).toMap
          }
