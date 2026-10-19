// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import cats.effect.kernel.Concurrent
import fs2.Stream
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor as CborCodec, DomainError}
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.storage.scls.SclsRecord.{Entry, Namespace, Record, Summary}

/** Bounded effectful bridge between ordered raw entries and the incremental SCLS primitives.
  * Callers must keep the supplied entry sink unpublished until `read` returns its verified value.
  */
object SclsStreaming:
  enum Error extends DomainError:
    case Framing(cause: SclsFraming.Error)
    case Record(cause: SclsRecord.Error)
    case Validation(cause: SclsValidation.Error)
    case Output(cause: SclsOutput.Error)
    case Key(cause: SclsKey.Error)
    case Value(cause: SclsValue.Error)
    case ValueNamespace(expected: Namespace, found: Namespace)
    case Stage(cause: StorageError)
    case InvalidFeedBytes(found: Int)

  final class Config private (
      private[scls] val output: SclsOutput.Config,
      private[scls] val frameLimit: SclsFraming.Limit,
      private[scls] val feedBytes: Int,
      private[scls] val valueLimits: CborCodec.Limits
  )

  object Config:
    def fromInts(
        recordBytes: Int,
        entriesPerChunk: Int,
        feedBytes: Int,
        valueLimits: CborCodec.Limits = CborCodec.Limits()
    ): Either[Error, Config] =
      for
        output <- SclsOutput.Config
          .fromInts(recordBytes, entriesPerChunk)
          .leftMap(Error.Output(_))
        limit <- SclsFraming.Limit.fromInt(recordBytes).leftMap(Error.Framing(_))
        _ <- Either.cond(feedBytes > 0, (), Error.InvalidFeedBytes(feedBytes))
      yield new Config(output, limit, feedBytes, valueLimits)

  private final case class ReadState(
      decoder: SclsFraming.Decoder,
      validator: SclsValidation.Validator
  )

  private def lift[F[_]: Concurrent, A](result: Either[Error, A])(using
      Raise[F, Error]
  ): F[A] = result.fold(_.raise[F, A], Concurrent[F].pure)

  private def validateValue[F[_]: Concurrent](
      namespace: Namespace,
      entry: Entry,
      limits: CborCodec.Limits
  )(using Raise[F, Error]): F[Unit] =
    for
      value <- lift(SclsValue.decode(namespace, entry.value, limits).leftMap(Error.Value(_)))
      _ <- lift(
        Either.cond(
          value.namespace == namespace,
          (),
          Error.ValueNamespace(namespace, value.namespace)
        )
      )
    yield ()

  /** Write framed bytes incrementally. `emit` receives at most one bounded frame per call. */
  def write[F[_]: Concurrent](
      entries: Stream[F, (Namespace, Entry)],
      slot: BigInt,
      summary: Summary,
      config: Config
  )(emit: Bytes => F[Unit])(using Raise[F, Error]): F[Unit] =
    for
      opened <- lift(
        SclsOutput.begin(config.output).leftMap(Error.Output(_))
      )
      (initial, header) = opened
      _ <- emit(header)
      finished <- entries
        .evalMapAccumulate(initial) { case (builder, (namespace, entry)) =>
          for
            _ <- validateValue(namespace, entry, config.valueLimits)
            pushed <- lift(
              builder.push(namespace, entry).leftMap(Error.Output(_))
            )
            (next, frames) = pushed
            _ <- frames.traverse_(emit)
          yield next -> ()
        }
        .compile
        .fold(initial) { case (_, (builder, _)) => builder }
      footer <- lift(finished.finish(slot, summary).leftMap(Error.Output(_)))
      _ <- footer.traverse_(emit)
    yield ()

  private def consume[F[_]: Concurrent](
      state: ReadState,
      frame: SclsFraming.Frame,
      config: Config,
      stage: (Namespace, Entry) => F[Unit]
  )(using Raise[F, Error]): F[ReadState] =
    for
      record <- lift(SclsRecord.decode(frame).leftMap(Error.Record(_)))
      validator <- lift(state.validator.feed(frame).leftMap(Error.Validation(_)))
      _ <- record match
        case chunk: Record.Chunk =>
          SclsRecord
            .foldEntriesM[F, Unit](chunk, ()) { (_, entry) =>
              for
                _ <- lift(
                  SclsKey
                    .decode(chunk.namespace, entry.key)
                    .leftMap(Error.Key(_))
                    .void
                )
                _ <- validateValue(chunk.namespace, entry, config.valueLimits)
                _ <- stage(chunk.namespace, entry)
              yield ()
            }
            .flatMap(result => lift(result.leftMap(Error.Record(_))).void)
        case _ => Concurrent[F].unit
    yield ReadState(state.decoder, validator)

  /** Consume bounded byte feeds, stage validated entries, and return proof only after manifest and
    * exact EOF. The append-only callback must write to unpublished staging; this service has no
    * publication capability.
    */
  def read[F[_]: Concurrent](
      input: Stream[F, Byte],
      config: Config
  )(stage: (Namespace, Entry) => F[Unit])(using
      Raise[F, Error]
  ): F[SclsValidation.VerifiedContainer] =
    val initial = ReadState(SclsFraming.decoder(config.frameLimit), SclsValidation.validator)
    for
      finalState <- input
        .chunkN(config.feedBytes, allowFewer = true)
        .evalMapAccumulate(initial) { case (state, chunk) =>
          val bytes = Bytes.wrap(chunk.toArray)
          for
            decoded <- lift(state.decoder.feed(bytes).leftMap(Error.Framing(_)))
            (decoder, frames) = decoded
            withDecoder = state.copy(decoder = decoder)
            next <- frames.foldLeftM(withDecoder) { (current, frame) =>
              consume(current, frame, config, stage)
            }
          yield next -> ()
        }
        .compile
        .fold(initial) { case (_, (state, _)) => state }
      _ <- lift(finalState.decoder.finish.leftMap(Error.Framing(_)))
      verified <- lift(finalState.validator.finish.leftMap(Error.Validation(_)))
    yield verified
