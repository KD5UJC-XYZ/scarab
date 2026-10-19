// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.storage.scls.SclsRecord.{Namespace, Record}
import xyz.kd5ujc.scarab.storage.scls.SclsCommitment.Digest

/** Incremental container validation for `ledger-f649-conway-v0`: one header, raw ordered chunks,
  * and one terminal manifest. This verifies framing commitments, not namespace value schemas or
  * chain authenticity. Reference: tweag/cardano-cls@8e2bba9442cb388089f53d708cc4aedcb47726ea,
  * scls-format/src/Cardano/SCLS/Internal/Serializer/Dump.hs:105-153, and
  * Internal/Serializer/Builder/InMemory.hs:152-165 (chunk digest and namespace Merkle state).
  */
object SclsValidation:
  enum Expected:
    case Header, ChunkOrManifest, End

  enum CountField:
    case Entries, Chunks
    case NamespaceEntries(namespace: Namespace)
    case NamespaceChunks(namespace: Namespace)

  enum Error extends DomainError:
    case Record(cause: SclsRecord.Error)
    case UnexpectedRecord(expected: Expected, found: SclsFraming.Kind)
    case MissingHeader, MissingManifest
    case NamespaceOrder(previous: Namespace, found: Namespace)
    case Sequence(namespace: Namespace, expected: BigInt, found: BigInt)
    case ChunkHash(namespace: Namespace, expected: Digest, found: Digest)
    case NamespaceRoot(namespace: Namespace, expected: Digest, found: Digest)
    case GlobalRoot(expected: Digest, found: Digest)
    case Count(field: CountField, expected: BigInt, found: BigInt)
    case NamespaceSet(expected: Vector[Namespace], found: Vector[Namespace])
    case PreviousManifestOffset(found: BigInt)

  final case class VerifiedContainer private[SclsValidation] (manifest: Record.Manifest)

  private final case class Observed(
      entries: BigInt,
      chunks: BigInt,
      tree: SclsCommitment.Merkle,
      lastKey: Option[Bytes]
  )
  private val empty = Observed(0, 0, SclsCommitment.empty, None)

  private enum Phase:
    case Start
    case Chunks(observed: Map[Namespace, Observed], last: Option[Namespace])
    case Complete(manifest: Record.Manifest)

  final class Validator private[SclsValidation] (private val phase: Phase):
    def feed(frame: SclsFraming.Frame): Either[Error, Validator] =
      SclsRecord.decode(frame).leftMap(Error.Record(_)).flatMap { record =>
        (phase, record) match
          case (Phase.Start, Record.Header) =>
            Right(new Validator(Phase.Chunks(Map.empty, None)))
          case (Phase.Start, _)       => Left(Error.UnexpectedRecord(Expected.Header, frame.kind))
          case (Phase.Complete(_), _) => Left(Error.UnexpectedRecord(Expected.End, frame.kind))
          case (Phase.Chunks(observed, last), chunk: Record.Chunk) =>
            val prior = observed.getOrElse(chunk.namespace, empty)
            for
              _ <- last match
                case Some(previous) if previous.bytes.compareTo(chunk.namespace.bytes) > 0 =>
                  Left(Error.NamespaceOrder(previous, chunk.namespace))
                case _ => Right(())
              _ <- Either.cond(
                chunk.sequence == prior.chunks + 1,
                (),
                Error.Sequence(chunk.namespace, prior.chunks + 1, chunk.sequence)
              )
              folded <- SclsRecord
                .foldEntries(chunk, (prior.tree, SclsCommitment.emptyChunk), prior.lastKey) {
                  case ((tree, hash), entry) =>
                    (
                      tree.addEntry(chunk.namespace.bytes, entry.key, entry.value),
                      hash.addEntry(chunk.namespace.bytes, entry.key, entry.value)
                    )
                }
                .leftMap(Error.Record(_))
              ((tree, hash), lastKey) = folded
              _ <- Either.cond(
                hash.digest == chunk.hash,
                (),
                Error.ChunkHash(chunk.namespace, hash.digest, chunk.hash)
              )
              next = Observed(prior.entries + chunk.count, prior.chunks + 1, tree, lastKey)
            yield new Validator(
              Phase.Chunks(observed.updated(chunk.namespace, next), Some(chunk.namespace))
            )
          case (Phase.Chunks(observed, _), manifest: Record.Manifest) =>
            verify(manifest, observed).as(new Validator(Phase.Complete(manifest)))
          case (Phase.Chunks(_, _), _) =>
            Left(Error.UnexpectedRecord(Expected.ChunkOrManifest, frame.kind))
      }

    def finish: Either[Error, VerifiedContainer] = phase match
      case Phase.Start              => Left(Error.MissingHeader)
      case Phase.Chunks(_, _)       => Left(Error.MissingManifest)
      case Phase.Complete(manifest) => Right(VerifiedContainer(manifest))

  private def count(field: CountField, expected: BigInt, found: BigInt): Either[Error, Unit] =
    Either.cond(expected == found, (), Error.Count(field, expected, found))

  private def verify(
      manifest: Record.Manifest,
      observed: Map[Namespace, Observed]
  ): Either[Error, Unit] =
    val names = manifest.namespaces.map(_.namespace)
    val required = Namespace.values.toVector
    for
      _ <- Either.cond(names == required, (), Error.NamespaceSet(required, names))
      _ <- Either.cond(manifest.previous == 0, (), Error.PreviousManifestOffset(manifest.previous))
      _ <- manifest.namespaces.traverse_ { info =>
        val actual = observed.getOrElse(info.namespace, empty)
        for
          _ <- count(CountField.NamespaceEntries(info.namespace), actual.entries, info.entries)
          _ <- count(CountField.NamespaceChunks(info.namespace), actual.chunks, info.chunks)
          _ <- Either.cond(
            actual.tree.root == info.root,
            (),
            Error.NamespaceRoot(info.namespace, actual.tree.root, info.root)
          )
        yield ()
      }
      _ <- count(
        CountField.Entries,
        observed.values.foldLeft(BigInt(0))(_ + _.entries),
        manifest.entries
      )
      _ <- count(
        CountField.Chunks,
        observed.values.foldLeft(BigInt(0))(_ + _.chunks),
        manifest.chunks
      )
      root = manifest.namespaces
        .foldLeft(SclsCommitment.empty)((tree, info) => tree.addNamespace(info.root))
        .root
      _ <- Either.cond(root == manifest.root, (), Error.GlobalRoot(root, manifest.root))
    yield ()

  def validator: Validator = new Validator(Phase.Start)

  /** Small fixture convenience. Services validate and consume records incrementally. */
  def validate(frames: Vector[SclsFraming.Frame]): Either[Error, VerifiedContainer] =
    frames.foldLeftM(validator)((current, frame) => current.feed(frame)).flatMap(_.finish)
