// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.effect.{Ref, Resource, Sync}
import cats.effect.syntax.all.*
import cats.effect.std.Mutex
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
import xyz.kd5ujc.scarab.storage.{UtxoPinnedReadView, UtxoStore}
import xyz.kd5ujc.scarab.ledger.StorageError

/** The LocalStateQuery interpreter over the node's volatile chain: acquiring takes the ledger state
  * retained for a point (the tip, the immutable tip or one of the last `k` blocks) and each query
  * is answered against that state alone. Conway UTxO queries combine the retained ledger overlay
  * with a resource-scoped read view of the backing generation captured under the chain publication
  * gate, so later advances and rollbacks cannot change an acquired answer.
  */
object StateQuerySource:
  def apply[F[_]: Sync, S: ChainRead](
      chain: Ref[F, VolatileChain[S]],
      env: F[QueryEnv],
      store: UtxoStore[F],
      chainGate: Mutex[F],
      version: NtcVersion = NtcVersion.LatestReleased
  ): LocalStateQueryServer.Source[F, StorageError] =
    new LocalStateQueryServer.Source[F, StorageError]:
      override def forVersion(agreed: NtcVersion): LocalStateQueryServer.Source[F, StorageError] =
        StateQuerySource(chain, env, store, chainGate, agreed)

      def acquire(
          target: Target
      ): Resource[
        F,
        Either[
          LocalStateQueryServer.AcquireFailure[StorageError],
          LocalStateQueryServer.Session[F, StorageError]
        ]
      ] =
        Resource
          .makeFull[
            F,
            (
                Either[
                  LocalStateQueryServer.AcquireFailure[StorageError],
                  LocalStateQueryServer.Session[F, StorageError]
                ],
                Option[F[Unit]]
            )
          ] { poll =>
            poll(chainGate.lock.allocated).flatMap { case (_, unlock) =>
              lockCapture(store, target, chain, version, env).guarantee(unlock)
            }
          } { case (_, finalizer) => finalizer.fold(Sync[F].unit)(identity) }
          .map(_._1)

      private def lockCapture(
          store: UtxoStore[F],
          target: Target,
          chain: Ref[F, VolatileChain[S]],
          version: NtcVersion,
          env: F[QueryEnv]
      ): F[
        (
            Either[
              LocalStateQueryServer.AcquireFailure[StorageError],
              LocalStateQueryServer.Session[F, StorageError]
            ],
            Option[F[Unit]]
        )
      ] =
        chain.get.flatMap { current =>
          select(current, target) match
            case Left(failure) =>
              Sync[F].pure(
                Left(LocalStateQueryServer.AcquireFailure.Rejected(failure)) -> Option
                  .empty[F[Unit]]
              )
            case Right(view) =>
              if view.era != xyz.kd5ujc.scarab.protocol.Era.Conway then
                Sync[F].pure(Right(session(view, None, version, env)) -> Option.empty[F[Unit]])
              else
                val oldest = view.utxo.flushed
                val newest = view.blockNo.getOrElse(BigInt(-1))
                store.readView.allocated
                  .map { case (captured, release) =>
                    val result = captured match
                      case Left(error) =>
                        Left(LocalStateQueryServer.AcquireFailure.Backend(error))
                      case Right(pinned) =>
                        val stamp = pinned.stamp
                        if BigInt(stamp) >= BigInt(oldest) && BigInt(stamp) <= newest then
                          Right(session(view, Some(pinned), version, env))
                        else
                          Left(
                            LocalStateQueryServer.AcquireFailure.Incoherent(
                              view.point,
                              oldest,
                              stamp,
                              newest
                            )
                          )
                    result -> Some(release)
                  }
        }

      private def select(c: VolatileChain[S], target: Target): Either[Failure, QueryView[S]] =
        def tipView = QueryView(c.tip, c.tipRef.map(_.blockNo), c.state)
        target match
          case Target.VolatileTip => Right(tipView)
          case Target.ImmutableTip =>
            Right(QueryView(c.anchor.point, c.anchor.blockNo, c.anchor.state))
          case Target.Specific(point) =>
            if point == c.anchor.point then
              Right(QueryView(point, c.anchor.blockNo, c.anchor.state))
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

      private def session(
          view: QueryView[S],
          pinned: Option[UtxoPinnedReadView[F]],
          version: NtcVersion,
          env: F[QueryEnv]
      ): LocalStateQueryServer.Session[F, StorageError] =
        new LocalStateQueryServer.Session[F, StorageError]:
          def answer(raw: Bytes): F[Either[StorageError, Either[Refusal, Bytes]]] =
            NtcQuery.decode(raw, version) match
              case Left(error) =>
                Sync[F].pure(Right(Left(Refusal.Undecodable(error, raw.hex.take(80)))))
              case Right(query) =>
                val shown = NtcQuery.describe(query)
                for
                  e <- env
                  entries <- fetch(view, pinned, query)
                  answer <- entries match
                    case Left(error) => Sync[F].pure(Left(error))
                    case Right(values) =>
                      Sync[F]
                        .delay(QueryAnswers.answerOrWhy(e, view, values, query, version))
                        .attempt
                        .map {
                          case Left(error)          => Right(Left(Refusal.Defect(shown, error)))
                          case Right(Right(result)) => Right(Right(result))
                          case Right(Left(Unanswered.Unsupported)) =>
                            Right(Left(Refusal.Unsupported(shown)))
                          case Right(Left(Unanswered.Refused(error))) =>
                            Right(
                              Left(Refusal.Unwritable(shown, error))
                            )
                          case Right(Left(Unanswered.Unwritable)) =>
                            Right(Left(Refusal.OutOfRange(shown)))
                        }
                yield answer

      private def fetch(
          view: QueryView[S],
          pinned: Option[UtxoPinnedReadView[F]],
          query: NtcQuery
      ): F[Either[StorageError, Map[TxIn, TxOut]]] =
        QueryAnswers.utxoNeeds(view, query) match
          case QueryAnswers.UtxoNeeds.Nothing => Sync[F].pure(Right(Map.empty))
          case QueryAnswers.UtxoNeeds.Whole =>
            pinned.fold(Sync[F].pure(Right(Map.empty[TxIn, TxOut])))(
              UtxoStore.materializePinned(_, view.utxo)
            )
          case QueryAnswers.UtxoNeeds.Addresses(addresses) =>
            pinned.fold(Sync[F].pure(Right(Map.empty[TxIn, TxOut])))(
              UtxoStore.materializePinnedAddresses(_, view.utxo, addresses)
            )
          case QueryAnswers.UtxoNeeds.Inputs(inputs) =>
            pinned.fold(Sync[F].pure(Right(Map.empty[TxIn, TxOut])))(backing =>
              val missing = view.utxo.missing(inputs)
              if missing.isEmpty then
                Sync[F].pure(
                  Right(inputs.iterator.flatMap(i => view.utxo.get(i).map(i -> _)).toMap)
                )
              else
                backing
                  .read(missing)
                  .map(_.map { fetched =>
                    val complete = view.utxo.withResident(fetched)
                    inputs.iterator.flatMap(i => complete.get(i).map(i -> _)).toMap
                  })
            )
