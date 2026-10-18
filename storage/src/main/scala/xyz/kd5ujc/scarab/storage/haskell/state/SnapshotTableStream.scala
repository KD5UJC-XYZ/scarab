// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.effect.Sync
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.{Chunk, Pull, Stream}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{TxIn, TxOut}
import xyz.kd5ujc.scarab.ledger.mempack.TxOutMemPack
import xyz.kd5ujc.scarab.ledger.state.{EraTag, ShelleyBasedEra}
import xyz.kd5ujc.scarab.storage.UtxoStore

/** Effectful streaming around the pure framing machine. Input/output resources belong to the
  * caller, so file errors and an import's atomicity remain at the storage boundary.
  */
object SnapshotTableStream:
  import SnapshotTables.{Entry, Error, Summary}

  enum Event:
    case Item(entry: Entry)
    case Complete(summary: Summary)

  /** Semantic insertion, distinct from framing. Implementations must use the era's map semantics:
    * duplicate refusal from protocol 9, last value wins before it. An importing sink can check
    * uniqueness on disk; requiring a sorted input would reject maps the reference accepts
    * (`cardano-ledger` f649f97 `Binary/Decoding/Decoder.hs:809-856`). A sink must stage its writes
    * until the caller has also verified the completed file CRC and snapshot metadata.
    */
  trait SemanticSink[F[_], E <: EraTag]:
    def insert(key: TxIn, value: TxOut): F[Unit]

  private def lift[F[_]: Sync, A](result: Either[Error, A])(using Raise[F, Error]): F[A] =
    result.fold(_.raise[F, A], Sync[F].pure)

  /** The final event appears only after the entire input, including trailing-byte checks, is read.
    * `Pull.loop` supplies stack safety even when every input chunk is one byte.
    */
  def events[F[_]: Sync](input: Stream[F, Byte])(using Raise[F, Error]): Stream[F, Event] =
    Pull
      .loop[F, Event, (SnapshotTables.Decoder, Stream[F, Byte])] { (decoder, rest) =>
        rest.pull.uncons.flatMap {
          case None =>
            Pull
              .eval(lift(decoder.finish))
              .flatMap(summary => Pull.output1(Event.Complete(summary)).as(None))
          case Some((chunk, tail)) =>
            Pull.eval(lift(decoder.feed(Bytes.wrap(chunk.toArray)))).flatMap { (next, entries) =>
              Pull.output(Chunk.from(entries.map(Event.Item(_)))).as(Some((next, tail)))
            }
        }
      }((SnapshotTables.decoder, input))
      .stream

  /** Framing-only entries. A successful drain does not check MemPack or duplicate keys. */
  def entries[F[_]: Sync](input: Stream[F, Byte])(using Raise[F, Error]): Stream[F, Entry] =
    events(input).collect { case Event.Item(entry) => entry }

  def fold[F[_]: Sync, A](input: Stream[F, Byte], initial: A)(
      consume: (A, Entry) => F[A]
  )(using Raise[F, Error]): F[(A, Summary)] =
    events(input)
      .evalMapAccumulate((initial, Option.empty[Summary])) {
        case ((state, summary), Event.Item(entry)) =>
          consume(state, entry).map(next => ((next, summary), ()))
        case ((state, _), Event.Complete(summary)) =>
          Sync[F].pure(((state, Some(summary)), ()))
      }
      .compile
      .last
      .flatMap { last =>
        val (state, summary) = last.fold((initial, Option.empty[Summary]))(_._1)
        lift(summary.toRight(Error.Incomplete(SnapshotTables.Field.Envelope, 0L))).map(state -> _)
      }

  /** Era-checked values and whole-table semantics supplied explicitly by the sink. */
  def readInto[F[_]: Sync, E <: EraTag: ShelleyBasedEra](
      input: Stream[F, Byte],
      sink: SemanticSink[F, E]
  )(using Raise[F, Error]): F[Summary] =
    fold(input, ()) { (_, entry) =>
      lift(TxOutMemPack.decode[E](entry.value).leftMap(Error.InvalidValue(entry.key, _)))
        .flatMap(out => sink.insert(UtxoStore.Coding.decodeKey(entry.key.toArray), out))
    }.map(_._2)

  private final case class Written(count: Long, previous: Option[Bytes], crc: SnapshotCrc)

  /** Constant-memory writer from an ascending stream. The count decides the reference framing; a
    * wrong count, duplicate key or descending key fails before a completed file is reported. `emit`
    * must write all supplied bytes, or fail; it must not retain them.
    */
  def write[F[_]: Sync](count: Long, entries: Stream[F, Entry])(
      emit: Bytes => F[Unit]
  )(using Raise[F, Error]): F[Summary] =
    for
      prefix <- lift(SnapshotTables.prefix(count))
      _ <- emit(prefix)
      initial = Written(0L, None, SnapshotCrc.of(prefix))
      last <- entries
        .evalMapAccumulate(initial) { (state, entry) =>
          for
            _ <- lift(
              Either.cond(state.count < count, (), Error.CountMismatch(count, state.count + 1))
            )
            _ <- state.previous.traverse_(previous =>
              lift(
                Either.cond(
                  previous.compareTo(entry.key) < 0,
                  (),
                  Error.KeysNotAscending(previous, entry.key)
                )
              )
            )
            encoded <- lift(SnapshotTables.encodeEntry(entry))
            _ <- emit(encoded)
          yield (Written(state.count + 1, Some(entry.key), state.crc.update(encoded)), ())
        }
        .compile
        .last
      written = last.fold(initial)(_._1)
      _ <- lift(Either.cond(written.count == count, (), Error.CountMismatch(count, written.count)))
      suffix = SnapshotTables.suffix(count)
      _ <- emit(suffix)
    yield Summary(written.count, written.crc.update(suffix))

  /** Convert the existing stored CBOR values at the interchange boundary; V5 and the stored values
    * do not change. The store is held offline while its count and sorted stream are read.
    */
  def writeStore[F[_]: Sync, E <: EraTag: ShelleyBasedEra](store: UtxoStore[F])(
      emit: Bytes => F[Unit]
  )(using Raise[F, Error]): F[Summary] =
    store.size.flatMap(count => writeOutputs[F, E](count, store.entries)(emit))

  /** A read-only checkpoint supplies its snapshot's exact count without a second scan. */
  def writeOutputs[F[_]: Sync, E <: EraTag: ShelleyBasedEra](
      count: Long,
      entries: Stream[F, (TxIn, TxOut)]
  )(emit: Bytes => F[Unit])(using Raise[F, Error]): F[Summary] =
    val packed = entries.evalMap { (key, out) =>
      for
        encodedKey <- lift(
          UtxoStore.Coding
            .encodeKey(key)
            .map(Bytes.wrap)
            .toRight(Error.InvalidKey(key))
        )
        bytes <- lift(TxOutMemPack.encode[E](out).leftMap(Error.InvalidValue(encodedKey, _)))
      yield Entry(encodedKey, bytes)
    }
    write(count, packed)(emit)
