// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.syntax.all.*
import scodec.{Attempt, Err}
import scodec.codecs.uint32
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.storage.scls.SclsRecord.{Entry, Namespace, NamespaceInfo, Record, Summary}

/** Incremental raw-chunk output for `ledger-f649-conway-v0`. Entries must arrive in packed-key
  * order; their value schemas are the caller's responsibility. This builder retains one bounded
  * chunk and logarithmic Merkle state per namespace. Reference:
  * tweag/cardano-cls@8e2bba9442cb388089f53d708cc4aedcb47726ea,
  * scls-format/src/Cardano/SCLS/Internal/Serializer/Builder/InMemory.hs:80-165;
  * Serializer/Reference/Impl.hs:49-62 sorts by the packed key before feeding the builder.
  */
object SclsOutput:
  enum Error extends DomainError:
    case Framing(cause: SclsFraming.Error)
    case Record(cause: SclsRecord.Error)
    case Key(cause: SclsKey.Error)
    case Binary(cause: Err)
    case InvalidChunkEntries(found: Int)
    case NamespaceOrder(previous: Namespace, found: Namespace)
    case EntryOrder(namespace: Namespace, previous: Bytes, found: Bytes)
    case EntryTooLarge(namespace: Namespace, bytes: Long, budget: Long)

  final class Config private[SclsOutput] (
      val recordBytes: Int,
      val chunkEntries: Int,
      private[SclsOutput] val limit: SclsFraming.Limit
  )

  object Config:
    /** Bounds exclude the four-byte frame length and include its tag, as in [[SclsFraming]]. */
    def fromInts(recordBytes: Int, chunkEntries: Int): Either[Error, Config] =
      for
        limit <- SclsFraming.Limit.fromInt(recordBytes).leftMap(Error.Framing(_))
        _ <- Either.cond(chunkEntries > 0, (), Error.InvalidChunkEntries(chunkEntries))
      yield new Config(recordBytes, chunkEntries, limit)

  private final case class Observed(
      entries: BigInt,
      chunks: BigInt,
      tree: SclsCommitment.Merkle,
      lastKey: Option[Bytes]
  )
  private val empty = Observed(0, 0, SclsCommitment.empty, None)

  private final case class Pending(
      namespace: Namespace,
      count: Int,
      bytes: Int,
      reversed: List[Bytes],
      hash: SclsCommitment.ChunkHash
  )

  private def concat(parts: List[Bytes]): Bytes =
    Bytes.wrap(
      fs2.Chunk
        .concat(
          parts.map(bytes => fs2.Chunk.array(bytes.unsafeArray, bytes.unsafeOffset, bytes.size))
        )
        .toArray
    )

  private def encodedEntry(entry: Entry): Either[Error, Bytes] =
    uint32.encode(entry.key.size.toLong + entry.value.size.toLong) match
      case Attempt.Failure(cause) => Left(Error.Binary(cause))
      case Attempt.Successful(bits) =>
        Right(concat(List(Bytes.wrap(bits.toByteArray), entry.key, entry.value)))

  private def encoded(record: Record, config: Config): Either[Error, Bytes] =
    SclsRecord
      .encode(record)
      .leftMap(Error.Record(_))
      .flatMap(SclsFraming.encode(_, config.limit).leftMap(Error.Framing(_)))

  final class Builder private[SclsOutput] (
      private val config: Config,
      private val observed: Map[Namespace, Observed],
      private val pending: Option[Pending],
      private val last: Option[Namespace]
  ):
    private def budget(namespace: Namespace): Long =
      // Tag, sequence, raw compression, sized namespace, key width, count and Blake2b-224.
      config.recordBytes.toLong - 50L - namespace.bytes.size.toLong

    private def flush: Either[Error, (Builder, Vector[Bytes])] = pending match
      case None => Right(this -> Vector.empty)
      case Some(chunk) =>
        val prior = observed.getOrElse(chunk.namespace, empty)
        val record = Record.Chunk(
          prior.chunks + 1,
          chunk.namespace,
          concat(chunk.reversed.reverse),
          chunk.count.toLong,
          chunk.hash.digest
        )
        encoded(record, config).map { bytes =>
          val next = observed.updated(chunk.namespace, prior.copy(chunks = prior.chunks + 1))
          new Builder(config, next, None, last) -> Vector(bytes)
        }

    /** A successful push emits at most one completed chunk. Failure leaves this value reusable. */
    def push(namespace: Namespace, entry: Entry): Either[Error, (Builder, Vector[Bytes])] =
      val prior = observed.getOrElse(namespace, empty)
      val size = 4L + entry.key.size.toLong + entry.value.size.toLong
      for
        _ <- SclsKey.decode(namespace, entry.key).leftMap(Error.Key(_))
        _ <- last match
          case Some(previous) if previous.bytes.compareTo(namespace.bytes) > 0 =>
            Left(Error.NamespaceOrder(previous, namespace))
          case _ => Right(())
        _ <- prior.lastKey match
          case Some(previous) if previous.compareTo(entry.key) >= 0 =>
            Left(Error.EntryOrder(namespace, previous, entry.key))
          case _ => Right(())
        _ <- Either.cond(
          size <= budget(namespace),
          (),
          Error.EntryTooLarge(namespace, size, budget(namespace))
        )
        data <- encodedEntry(entry)
        ready <- pending match
          case Some(chunk)
              if chunk.namespace != namespace || chunk.count == config.chunkEntries ||
                chunk.bytes.toLong + size > budget(namespace) =>
            flush
          case _ => Right(this -> Vector.empty)
        (builder, emitted) = ready
        chunk = builder.pending.getOrElse(
          Pending(namespace, 0, 0, Nil, SclsCommitment.emptyChunk)
        )
        next = chunk.copy(
          count = chunk.count + 1,
          bytes = chunk.bytes + data.size,
          reversed = data :: chunk.reversed,
          hash = chunk.hash.addEntry(namespace.bytes, entry.key, entry.value)
        )
        // A flush advances the chunk count, while this entry advances its namespace tree.
        actual = builder.observed.getOrElse(namespace, empty)
        stats = actual.copy(
          entries = actual.entries + 1,
          tree = actual.tree.addEntry(namespace.bytes, entry.key, entry.value),
          lastKey = Some(entry.key.compact)
        )
      yield new Builder(
        config,
        builder.observed.updated(namespace, stats),
        Some(next),
        Some(namespace)
      ) -> emitted

    /** Empty namespaces are included in the terminal manifest, with no empty chunks. A caller
      * publishes the destination only after these final records and its durable close succeed.
      */
    def finish(slot: BigInt, summary: Summary): Either[Error, Vector[Bytes]] =
      flush.flatMap { (builder, chunks) =>
        val infos = Namespace.values.toVector.map { namespace =>
          val actual = builder.observed.getOrElse(namespace, empty)
          NamespaceInfo(namespace, actual.entries, actual.chunks, actual.tree.root)
        }
        val root = infos
          .foldLeft(SclsCommitment.empty)((tree, info) => tree.addNamespace(info.root))
          .root
        val manifest = Record.Manifest(
          slot,
          infos.foldLeft(BigInt(0))(_ + _.entries),
          infos.foldLeft(BigInt(0))(_ + _.chunks),
          summary,
          infos,
          0,
          root
        )
        encoded(manifest, config).map(chunks :+ _)
      }

  def begin(config: Config): Either[Error, (Builder, Bytes)] =
    encoded(Record.Header, config).map(bytes => new Builder(config, Map.empty, None, None) -> bytes)
