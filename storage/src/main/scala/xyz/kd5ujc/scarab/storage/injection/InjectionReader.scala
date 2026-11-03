// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.injection

import cats.effect.Sync
import cats.mtl.Raise
import cats.syntax.all.*
import io.circe.Json
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.ledger.GenesisState.InjectionData
import xyz.kd5ujc.scarab.ledger.injection.InjectionJson
import xyz.kd5ujc.scarab.ledger.state.Hash32

/** Generic `foldInjectionData` (`Shelley/Genesis.hs:874-937`, cardano-ledger f649f975). File bytes
  * are hashed only when the parser demands them. A completed entry's key, value and accumulator
  * step precede parsing the next entry; digest comparison follows successful parsing. This
  * mechanism is unused by genesis initialization and does not implement an OS filesystem.
  */
object InjectionReader:
  enum Content[+KeyE <: DomainError, +ValueE <: DomainError, +FoldE <: DomainError]
      extends DomainError:
    case Syntax(segments: Vector[String], cause: InjectionJson.Error)
    case Key(segments: Vector[String], text: String, cause: KeyE)
    case Value(segments: Vector[String], key: String, cause: ValueE)
    case Fold(cause: FoldE)
    case HashMismatch(segments: Vector[String], expected: Hash32, actual: Bytes)

  private final case class Loop[A](
      parser: InjectionJson.State,
      accumulator: A,
      digest: InjectionDigest,
      eof: Boolean
  )

  def fold[
      F[_]: Sync,
      FileE <: DomainError,
      H,
      KeyE <: DomainError,
      ValueE <: DomainError,
      FoldE <: DomainError,
      K,
      V,
      A
  ](
      files: InjectionFiles[F, FileE, H],
      source: InjectionData[(K, V)],
      initial: A
  )(
      key: String => Either[KeyE, K],
      value: Json => Either[ValueE, V],
      step: (A, (K, V)) => Either[FoldE, A]
  )(using Raise[F, InjectionFiles.Failure[FileE, Content[KeyE, ValueE, FoldE]]]): F[A] =
    type Error = InjectionFiles.Failure[FileE, Content[KeyE, ValueE, FoldE]]
    val F = Sync[F]
    def lift[B](result: Either[Content[KeyE, ValueE, FoldE], B])(using
        errors: Raise[F, Error]
    ): F[B] =
      result.fold(error => errors.raise(InjectionFiles.Failure.Body(error)), F.pure)
    files.scoped[Content[KeyE, ValueE, FoldE], A] {
      source match
        case InjectionData.NoInjection => F.pure(initial)
        case InjectionData.Embedded(entries) =>
          entries.foldLeftM(initial)((acc, pair) =>
            F.delay(step(acc, pair)).flatMap(r => lift(r.leftMap(Content.Fold(_))))
          )
        case InjectionData.FromFile(segments, expected) =>
          files.use[Content[KeyE, ValueE, FoldE], A](segments) { reader =>
            F.tailRecM(Loop(InjectionJson.initial, initial, InjectionDigest.empty, false)) {
              current =>
                F.delay(current.parser.pull).flatMap {
                  case InjectionJson.Step.Entry(text, json, next) =>
                    val decoded = for
                      k <- key(text).leftMap(Content.Key(segments, text, _))
                      v <- value(json).leftMap(Content.Value(segments, text, _))
                      a <- step(current.accumulator, (k, v)).leftMap(Content.Fold(_))
                    yield a
                    lift(decoded).map(a => Left(current.copy(parser = next, accumulator = a)))
                  case InjectionJson.Step.NeedData(input) =>
                    if current.eof then F.pure(Left(current.copy(parser = input.feed(Bytes.empty))))
                    else
                      reader.readSome.map { bytes =>
                        val nextHash =
                          if bytes.isEmpty then current.digest else current.digest.add(bytes)
                        Left(
                          current.copy(
                            parser = input.feed(bytes),
                            digest = nextHash,
                            eof = bytes.isEmpty
                          )
                        )
                      }
                  case InjectionJson.Step.Failed(cause) =>
                    lift(Left(Content.Syntax(segments, cause)))
                  case InjectionJson.Step.Done(_) =>
                    val actual = current.digest.finish
                    lift(
                      Either.cond(
                        actual == expected.bytes,
                        current.accumulator,
                        Content.HashMismatch(segments, expected, actual)
                      )
                    ).map(Right(_))
                }
            }
          }
    }
