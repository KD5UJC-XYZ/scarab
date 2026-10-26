// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.injection

import cats.syntax.all.*
import io.circe.Json
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.ledger.GenesisState.InjectionData
import xyz.kd5ujc.scarab.ledger.state.Hash32

/** Pure demanded-chunk/hash/domain-fold transitions from `foldInjectionData`
  * (`Shelley/Genesis.hs:874-937`, cardano-ledger f649f975). This does not open or close a file,
  * implement cancellation, or wire a genesis consumer. Those effect boundaries remain #499 work.
  */
object InjectionFold:
  enum Error[+KeyE <: DomainError, +ValueE <: DomainError, +FoldE <: DomainError]
      extends DomainError:
    case Syntax(segments: Vector[String], cause: InjectionJson.Error)
    case Key(segments: Vector[String], text: String, cause: KeyE)
    case Value(segments: Vector[String], key: String, cause: ValueE)
    case Fold(cause: FoldE)
    case HashMismatch(segments: Vector[String], expected: Hash32, actual: Bytes)

  enum Step[+A, +E <: DomainError]:
    case NeedChunk(input: Input[A, E])
    case NeedEof(input: Eof[A, E])
    case Done(value: A)
    case Failed(cause: E)

  /** One demanded hGetSome request; an empty response permanently exhausts this stream branch. */
  final class Input[+A, +E <: DomainError] private[InjectionFold] (
      continue: Bytes => Step[A, E]
  ):
    val requestedBytes: Int = 65536
    def feed(bytes: Bytes): Step[A, E] = continue(bytes)

  /** Native ParseNeedData after EOF feeds empty bytes without another filesystem read. It may
    * continue requesting such feeds indefinitely; a pending step is not completion or refusal.
    */
  final class Eof[+A, +E <: DomainError] private[InjectionFold] (
      continue: () => Step[A, E]
  ):
    def resume: Step[A, E] = continue()

  def start[K, V, A, KeyE <: DomainError, ValueE <: DomainError, FoldE <: DomainError](
      source: InjectionData[(K, V)],
      initial: A
  )(
      key: String => Either[KeyE, K],
      value: Json => Either[ValueE, V],
      fold: (A, (K, V)) => Either[FoldE, A]
  ): Step[A, Error[KeyE, ValueE, FoldE]] =
    source match
      case InjectionData.NoInjection => Step.Done(initial)
      case InjectionData.Embedded(entries) =>
        entries
          .foldLeftM(initial)((acc, pair) => fold(acc, pair).leftMap(Error.Fold(_)))
          .fold(Step.Failed(_), Step.Done(_))
      case InjectionData.FromFile(segments, expected) =>
        def drive(
            state: InjectionJson.State,
            accumulator: A,
            digest: InjectionDigest,
            exhausted: Boolean
        ): Step[A, Error[KeyE, ValueE, FoldE]] =
          @tailrec def loop(
              parser: InjectionJson.State,
              acc: A
          ): Step[A, Error[KeyE, ValueE, FoldE]] =
            parser.pull match
              case InjectionJson.Step.Entry(text, json, next) =>
                val decoded = for
                  k <- key(text).leftMap(Error.Key(segments, text, _))
                  v <- value(json).leftMap(Error.Value(segments, text, _))
                  a <- fold(acc, (k, v)).leftMap(Error.Fold(_))
                yield a
                decoded match
                  case Left(cause) => Step.Failed(cause)
                  case Right(a)    => loop(next, a)
              case InjectionJson.Step.NeedData(input) =>
                if exhausted then
                  Step.NeedEof(new Eof(() => drive(input.feed(Bytes.empty), acc, digest, true)))
                else
                  Step.NeedChunk(new Input(bytes =>
                    val nextHash = if bytes.isEmpty then digest else digest.add(bytes)
                    drive(input.feed(bytes), acc, nextHash, bytes.isEmpty)
                  ))
              case InjectionJson.Step.Failed(cause) => Step.Failed(Error.Syntax(segments, cause))
              case InjectionJson.Step.Done(_) =>
                val actual = digest.finish
                if actual == expected.bytes then Step.Done(acc)
                else Step.Failed(Error.HashMismatch(segments, expected, actual))
          loop(state, accumulator)
        drive(InjectionJson.initial, initial, InjectionDigest.empty, false)
