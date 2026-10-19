// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.Functor
import cats.effect.{Async, Ref}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.Path
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.storage.scls.SclsRecord.{Entry, Namespace, Summary}

/** Bounded binary export from one resource-owned complete base. The caller writes `emit` bytes to
  * unpublished output and owns its eventual durable publication. The returned proof is produced
  * after the complete generated container has passed the independent incremental validator.
  */
object SclsExport:
  final case class Config(streaming: SclsStreaming.Config, sorter: SclsEntrySorter.Config)

  enum Error extends DomainError:
    case Storage(cause: StorageError)
    case Base(cause: SclsBase.Error)
    case Mapping(cause: SclsStateMapper.Error)
    case Key(cause: SclsKey.Error)
    case Value(cause: SclsValue.Error)
    case Sorter(cause: SclsEntrySorter.Error)
    case Streaming(cause: SclsStreaming.Error)
    case Framing(cause: SclsFraming.Error)
    case Validation(cause: SclsValidation.Error)
    case UtxoCount(expected: Long, found: BigInt)
    case UtxoCoin(expected: BigInt, found: BigInt)

  def write[F[_]: Async](
      base: SclsBase.Source[F],
      scratch: Path,
      summary: Summary,
      config: Config
  )(emit: Bytes => F[Unit])(using outer: Raise[F, Error]): F[SclsValidation.VerifiedContainer] =
    val F = Async[F]
    def mapped[E](wrap: E => Error): Raise[F, E] = new Raise[F, E]:
      def functor: Functor[F] = F
      def raise[E2 <: E, A](error: E2): F[A] = outer.raise(wrap(error))
    given baseErrors: Raise[F, SclsBase.Error] = mapped(Error.Base(_))
    given storageErrors: Raise[F, StorageError] = mapped(Error.Storage(_))
    given sorterErrors: Raise[F, SclsEntrySorter.Error] = mapped(Error.Sorter(_))
    given streamingErrors: Raise[F, SclsStreaming.Error] = mapped(Error.Streaming(_))
    def lift[A](value: Either[Error, A]): F[A] = value.fold(_.raise[F, A], F.pure)
    def encode(key: SclsKey.Key, value: SclsValue.Entry): F[(Namespace, Entry)] =
      lift(for
        packed <- SclsKey.encode(key).leftMap(Error.Key(_))
        bytes <- SclsValue.encode(value, config.streaming.valueLimits).leftMap(Error.Value(_))
      yield key.namespace -> Entry(packed, bytes))

    for
      safeScratch <- SclsBase.externalScratch[F](base.database, scratch)
      result <- SclsEntrySorter.resource[F](safeScratch, config.sorter).use { sorter =>
        for
          metadata <- lift(SclsStateMapper.project(base.ledger).leftMap(Error.Mapping(_)))
          _ <- Stream
            .fromIterator[F](metadata, 128)
            .evalMap { (key, value) =>
              encode(key, value).flatMap((namespace, entry) => sorter.add(namespace, entry))
            }
            .compile
            .drain
          observed <- base.entries
            .evalMap { (key, output) =>
              encode(SclsKey.Key.Utxo(key), SclsValue.Entry.Utxo(output))
                .flatMap((namespace, entry) => sorter.add(namespace, entry))
                .as(output.value.coin)
            }
            .compile
            .fold((BigInt(0), BigInt(0))) { case ((count, coin), amount) =>
              (count + 1, coin + amount)
            }
          (count, coin) = observed
          _ <- lift(
            Either.cond(count == base.utxo.count, (), Error.UtxoCount(base.utxo.count, count))
          )
          _ <- lift(Either.cond(coin == base.utxo.coin, (), Error.UtxoCoin(base.utxo.coin, coin)))
          validator <- Ref.of[F, SclsValidation.Validator](SclsValidation.validator)
          _ <- SclsStreaming.write[F](
            sorter.entries,
            base.context.anchor.slot,
            summary,
            config.streaming
          ) { bytes =>
            for
              frames <- lift(
                SclsFraming.decode(bytes, config.streaming.frameLimit).leftMap(Error.Framing(_))
              )
              current <- validator.get
              next <- lift(
                frames
                  .foldLeftM(current)((before, frame) => before.feed(frame))
                  .leftMap(Error.Validation(_))
              )
              _ <- emit(bytes)
              _ <- validator.set(next)
            yield ()
          }
          finalState <- validator.get
          verified <- lift(finalState.finish.leftMap(Error.Validation(_)))
        yield verified
      }
    yield result
