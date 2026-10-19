// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.Monad
import cats.data.StateT
import cats.syntax.all.*
import scodec.{Attempt, Codec, Err}
import scodec.bits.ByteVector
import scodec.codecs.{int64, uint8, uint32, utf8}
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.storage.scls.SclsCommitment.Digest

/** Records of the pinned `ledger-f649-conway-v0` profile. Container decoding retains original entry
  * bytes; namespace value schemas and whole-file commitments are checked separately. Reference:
  * tweag/cardano-cls@8e2bba9442cb388089f53d708cc4aedcb47726ea,
  * scls-format/src/Cardano/SCLS/Internal/Record/{Hdr.hs:28-53,Chunk.hs:97-119,
  * Manifest.hs:109-168}; key sizes: cardano-ledger@f649f9751074d2ab3de033fc3912f29c9862c1f5,
  * libs/cardano-ledger-canonical-state/src/Cardano/Ledger/CanonicalState/Namespace, each
  * namespace's V0.hs `NamespaceKeySize` instance.
  */
object SclsRecord:
  enum Namespace(val name: String, val keySize: Int):
    case Blocks extends Namespace("blocks/v0", 36)
    case Accounts extends Namespace("entities/accounts/v0", 29)
    case CommitteeEntities extends Namespace("entities/committee/v0", 1)
    case DReps extends Namespace("entities/dreps/v0", 29)
    case StakePools extends Namespace("entities/stake_pools/v0", 28)
    case StakePoolVrfHashes extends Namespace("entities/stake_pools/vrf_key_hashes/v0", 32)
    case Committee extends Namespace("gov/committee/v0", 1)
    case Constitution extends Namespace("gov/constitution/v0", 1)
    case PParams extends Namespace("gov/pparams/v0", 4)
    case ProposalRoots extends Namespace("gov/proposals/roots/v0", 1)
    case Proposals extends Namespace("gov/proposals/v0", 34)
    case Utxo extends Namespace("utxo/v0", 34)

    def bytes: Bytes = Bytes.wrap(name.getBytes(java.nio.charset.StandardCharsets.UTF_8))

  final case class Summary(createdAt: String, tool: String, comment: Option[String])
  final case class NamespaceInfo(
      namespace: Namespace,
      entries: BigInt,
      chunks: BigInt,
      root: Digest
  )
  final case class Entry(key: Bytes, value: Bytes)

  enum Record:
    case Header
    case Chunk(
        sequence: BigInt,
        namespace: Namespace,
        data: Bytes,
        count: Long,
        hash: Digest
    )
    case Manifest(
        slot: BigInt,
        entries: BigInt,
        chunks: BigInt,
        summary: Summary,
        namespaces: Vector[NamespaceInfo],
        previous: BigInt,
        root: Digest
    )

  enum Error extends DomainError:
    case Truncated(offset: Int, requested: Long, remaining: Int)
    case Binary(cause: Err)
    case Word64Range(found: BigInt)
    case InvalidMagic(found: Bytes)
    case UnsupportedVersion(found: Long)
    case UnsupportedCompression(found: Int)
    case UnsupportedNamespace(found: String)
    case UnsupportedRecord(kind: SclsFraming.Kind)
    case KeySize(namespace: Namespace, found: Long)
    case NamespaceOrder(previous: Namespace, found: Namespace)
    case EntrySize(namespace: Namespace, found: Long)
    case EntryOrder(namespace: Namespace, previous: Bytes, found: Bytes)
    case EntryCount(expected: Long, found: Int)
    case InvalidDigestSize(found: Int)
    case FooterSize(expected: Long, found: Long)
    case Trailing(offset: Int, remaining: Int)

  private final case class Cursor(bytes: Bytes, offset: Int):
    def remaining: Int = bytes.size - offset

  private type Result[A] = Either[Error, A]
  private type Read[A] = StateT[Result, Cursor, A]

  private def lift[A](result: Result[A]): Read[A] = StateT.liftF(result)

  private def take(size: Long): Read[Bytes] = StateT { cursor =>
    if size < 0 || size > cursor.remaining.toLong then
      Left(Error.Truncated(cursor.offset, size, cursor.remaining))
    else
      val end = cursor.offset + size.toInt
      Right(cursor.copy(offset = end) -> cursor.bytes.slice(cursor.offset, end))
  }

  private def fixed[A](size: Int, codec: Codec[A]): Read[A] =
    take(size).flatMap { bytes =>
      lift(codec.decode(ByteVector.view(bytes.toArray).bits) match
        case Attempt.Successful(decoded) => Right(decoded.value)
        case Attempt.Failure(cause)      => Left(Error.Binary(cause)))
    }

  private val word32: Read[Long] = fixed(4, uint32)
  private val word64: Read[BigInt] = fixed(8, int64).map { signed =>
    if signed >= 0 then BigInt(signed) else BigInt(signed) + (BigInt(1) << 64)
  }
  private val digest: Read[Digest] = take(28).flatMap { bytes =>
    lift(Digest.fromBytes(bytes).toRight(Error.InvalidDigestSize(bytes.size)))
  }

  private def text(size: Long): Read[String] = take(size).flatMap { bytes =>
    lift(utf8.decode(ByteVector.view(bytes.toArray).bits) match
      case Attempt.Successful(decoded) => Right(decoded.value)
      case Attempt.Failure(cause)      => Left(Error.Binary(cause)))
  }
  private val sizedText: Read[String] = word32.flatMap(text)
  private def namespace(size: Long): Read[Namespace] = text(size).flatMap { name =>
    lift(Namespace.values.find(_.name == name).toRight(Error.UnsupportedNamespace(name)))
  }

  private val summary: Read[Summary] =
    (sizedText, sizedText, sizedText).mapN { (created, tool, comment) =>
      Summary(created, tool, Option.when(comment.nonEmpty)(comment))
    }

  private def nextEntry(namespace: Namespace): Read[Option[Entry]] =
    StateT.inspect[Result, Cursor, Int](_.remaining).flatMap {
      case 0 => StateT.pure(None)
      case _ =>
        for
          size <- word32
          _ <- lift(
            Either.cond(
              size >= namespace.keySize.toLong,
              (),
              Error.EntrySize(namespace, size)
            )
          )
          key <- take(namespace.keySize.toLong)
          value <- take(size - namespace.keySize.toLong)
        yield Some(Entry(key, value))
    }

  private val namespaceInfos: Read[Vector[NamespaceInfo]] =
    Monad[Read].tailRecM(Vector.empty[NamespaceInfo]) { infos =>
      word32.flatMap {
        case 0 => StateT.pure(Right(infos))
        case size =>
          for
            entries <- word64
            chunks <- word64
            ns <- namespace(size)
            _ <- lift(infos.lastOption match
              case Some(previous) if previous.namespace.bytes.compareTo(ns.bytes) >= 0 =>
                Left(Error.NamespaceOrder(previous.namespace, ns))
              case _ => Right(()))
            root <- digest
          yield Left(infos :+ NamespaceInfo(ns, entries, chunks, root))
      }
    }

  private def contents(frame: SclsFraming.Frame): Read[Record] = frame.kind match
    case SclsFraming.Kind.Header =>
      for
        magic <- take(4)
        _ <- lift(
          Either.cond(
            magic == Bytes.wrap(Array[Byte](83, 67, 76, 83)),
            (),
            Error.InvalidMagic(magic)
          )
        )
        version <- word32
        _ <- lift(Either.cond(version == 1L, (), Error.UnsupportedVersion(version)))
      yield Record.Header
    case SclsFraming.Kind.Chunk =>
      for
        sequence <- word64
        compression <- fixed(1, uint8)
        _ <- lift(Either.cond(compression == 0, (), Error.UnsupportedCompression(compression)))
        ns <- word32.flatMap(namespace)
        keySize <- word32
        _ <- lift(Either.cond(keySize == ns.keySize.toLong, (), Error.KeySize(ns, keySize)))
        remaining <- StateT.inspect[Result, Cursor, Int](_.remaining)
        offset <- StateT.inspect[Result, Cursor, Int](_.offset)
        _ <- lift(Either.cond(remaining >= 32, (), Error.Truncated(offset, 32, remaining)))
        data <- take(remaining.toLong - 32L)
        count <- word32
        hash <- digest
      yield Record.Chunk(sequence, ns, data, count, hash)
    case SclsFraming.Kind.Manifest =>
      for
        slot <- word64
        entries <- word64
        chunks <- word64
        detail <- summary
        namespaces <- namespaceInfos
        previous <- word64
        root <- digest
        footer <- word32
        expected = frame.contents.size.toLong + 1L
        _ <- lift(Either.cond(footer == expected, (), Error.FooterSize(expected, footer)))
      yield Record.Manifest(slot, entries, chunks, detail, namespaces, previous, root)
    case SclsFraming.Kind.Metadata => lift(Left(Error.UnsupportedRecord(frame.kind)))

  private def run[A](read: Read[A], bytes: Bytes): Result[A] =
    read.run(Cursor(bytes, 0)).flatMap { (end, result) =>
      Either.cond(end.remaining == 0, result, Error.Trailing(end.offset, end.remaining))
    }

  def decode(frame: SclsFraming.Frame): Result[Record] = run(contents(frame), frame.contents)

  /** Consume one entry at a time, retaining only the accumulator and the previous key. */
  def foldEntries[A](chunk: Record.Chunk, initial: A, previous: Option[Bytes] = None)(
      append: (A, Entry) => A
  ): Result[(A, Option[Bytes])] =
    val read = Monad[Read].tailRecM((initial, previous, 0)) { (acc, last, count) =>
      StateT.inspect[Result, Cursor, Int](_.remaining).flatMap {
        case 0 => StateT.pure(Right((acc, last, count)))
        case _ =>
          for
            size <- word32
            _ <- lift(
              Either.cond(
                size >= chunk.namespace.keySize.toLong,
                (),
                Error.EntrySize(chunk.namespace, size)
              )
            )
            key <- take(chunk.namespace.keySize)
            value <- take(size - chunk.namespace.keySize.toLong)
            _ <- lift(last match
              case Some(previous) if previous.compareTo(key) >= 0 =>
                Left(Error.EntryOrder(chunk.namespace, previous, key))
              case _ => Right(()))
          yield Left((append(acc, Entry(key, value)), Some(key.compact), count + 1))
      }
    }
    run(read, chunk.data).flatMap { (acc, last, count) =>
      Either.cond(count.toLong == chunk.count, (acc, last), Error.EntryCount(chunk.count, count))
    }

  /** Effectful, stack-safe traversal for a service that must stage entries without collecting a
    * chunk's entries in a second vector. The frame decoder and caller bound `chunk.data`.
    */
  def foldEntriesM[F[_]: Monad, A](
      chunk: Record.Chunk,
      initial: A,
      previous: Option[Bytes] = None
  )(append: (A, Entry) => F[A]): F[Either[Error, (A, Option[Bytes])]] =
    type State = (Cursor, A, Option[Bytes], Long)
    val start: State = (Cursor(chunk.data, 0), initial, previous, 0L)
    Monad[F].tailRecM(start) { case (cursor, accumulated, last, count) =>
      nextEntry(chunk.namespace).run(cursor) match
        case Left(error) => Monad[F].pure(Right(Left(error)))
        case Right((_, None)) =>
          val result = Either.cond(
            count == chunk.count,
            (accumulated, last),
            Error.EntryCount(chunk.count, count.toInt)
          )
          Monad[F].pure(Right(result))
        case Right((next, Some(entry))) =>
          last match
            case Some(previousKey) if previousKey.compareTo(entry.key) >= 0 =>
              Monad[F].pure(
                Right(Left(Error.EntryOrder(chunk.namespace, previousKey, entry.key)))
              )
            case _ =>
              append(accumulated, entry).map { value =>
                Left((next, value, Some(entry.key.compact), count + 1))
              }
    }

  /** Convenience for small fixtures; services use [[foldEntries]] without collecting entries. */
  def entries(chunk: Record.Chunk): Result[Vector[Entry]] =
    foldEntries(chunk, Vector.empty[Entry])(_ :+ _).map(_._1)

  private val MaxWord64 = (BigInt(1) << 64) - 1

  private def encoded[A](codec: Codec[A], value: A): Result[Bytes] = codec.encode(value) match
    case Attempt.Successful(bits) => Right(Bytes.wrap(bits.toByteArray))
    case Attempt.Failure(cause)   => Left(Error.Binary(cause))

  private def encodeWord64(value: BigInt): Result[Bytes] =
    if value < 0 || value > MaxWord64 then Left(Error.Word64Range(value))
    else encoded(int64, value.longValue)

  private def concat(parts: Vector[Bytes]): Bytes =
    Bytes.wrap(
      fs2.Chunk
        .concat(
          parts.map(bytes => fs2.Chunk.array(bytes.unsafeArray, bytes.unsafeOffset, bytes.size))
        )
        .toArray
    )

  private def encodeText(value: String): Result[Bytes] =
    encoded(utf8, value).flatMap { bytes =>
      encoded(uint32, bytes.size.toLong).map(prefix => concat(Vector(prefix, bytes)))
    }

  private def encodeSummary(value: Summary): Result[Bytes] =
    Vector(value.createdAt, value.tool, value.comment.getOrElse(""))
      .traverse(encodeText)
      .map(concat)

  private def encodeNamespace(info: NamespaceInfo): Result[Bytes] =
    for
      size <- encoded(uint32, info.namespace.bytes.size.toLong)
      entries <- encodeWord64(info.entries)
      chunks <- encodeWord64(info.chunks)
    yield concat(Vector(size, entries, chunks, info.namespace.bytes, info.root.bytes))

  /** Encoding validates the same profile invariants as decoding before returning a frame. */
  def encode(record: Record): Result[SclsFraming.Frame] =
    val contents: Result[SclsFraming.Frame] = record match
      case Record.Header =>
        Right(
          SclsFraming.Frame(
            SclsFraming.Kind.Header,
            Bytes.wrap(Array[Byte](83, 67, 76, 83, 0, 0, 0, 1))
          )
        )
      case chunk: Record.Chunk =>
        for
          sequence <- encodeWord64(chunk.sequence)
          ns <- encodeText(chunk.namespace.name)
          keySize <- encoded(uint32, chunk.namespace.keySize.toLong)
          count <- encoded(uint32, chunk.count)
        yield SclsFraming.Frame(
          SclsFraming.Kind.Chunk,
          concat(
            Vector(
              sequence,
              Bytes.wrap(Array[Byte](0)),
              ns,
              keySize,
              chunk.data,
              count,
              chunk.hash.bytes
            )
          )
        )
      case manifest: Record.Manifest =>
        for
          slot <- encodeWord64(manifest.slot)
          entries <- encodeWord64(manifest.entries)
          chunks <- encodeWord64(manifest.chunks)
          summary <- encodeSummary(manifest.summary)
          namespaces <- manifest.namespaces.traverse(encodeNamespace)
          previous <- encodeWord64(manifest.previous)
          body = concat(
            Vector(slot, entries, chunks, summary) ++ namespaces ++ Vector(
              Bytes.wrap(Array.fill[Byte](4)(0)),
              previous,
              manifest.root.bytes
            )
          )
          footer <- encoded(uint32, body.size.toLong + 5L)
        yield SclsFraming.Frame(SclsFraming.Kind.Manifest, concat(Vector(body, footer)))
    contents.flatMap(frame => decode(frame).as(frame))
