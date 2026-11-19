// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.node.PrefixTokens
import xyz.kd5ujc.scarab.protocol.LocalStateQuery

/** TEST-ONLY ORACLE: [[NtcQueryPrefix]] as it was at origin/main 897310d97, the base of the change
  * that made the judge of a query prefix resume, verbatim but for its name; its levels and refusals
  * are the main ones, which did not change.
  */
private[ntc] object BaseNtcQueryPrefix:
  import NtcQueryPrefix.{Level, Refusal}

  /** What the reference's decoder of queries makes of `input`, the part of a query that has
    * arrived.
    */
  def prefix(input: Bytes)(using QueryTable): LocalStateQuery.QueryPrefix =
    val tokens = PrefixTokens(input, LocalStateQuery.QueryPrefix.Short)
    sized(tokens, 0, Level.Query).flatMap {
      case (2, 0, next) => block(tokens, next)
      // a top-level query without arguments is whole once its tag has arrived
      case (1, 1 | 2 | 3 | 4, _) => waits
      case (size, tag, _)        => Left(refused(Refusal.Constructor(Level.Query, size, tag)))
    }.merge

  private type Verdict = LocalStateQuery.QueryPrefix
  private type Step[A] = Either[Verdict, A]
  private type Tokens = PrefixTokens[Verdict]

  private val waits: Step[Verdict] = Right(LocalStateQuery.QueryPrefix.Short)

  private def refused(refusal: Refusal): Verdict = LocalStateQuery.QueryPrefix.Refused(refusal)

  /** `decodeListLen` and `decodeWord8`: a size and a tag, which the caller judges together, and
    * where the next token starts.
    */
  private def sized(tokens: Tokens, at: Int, level: Level): Step[(Long, Int, Int)] =
    tokens
      .listLength(at, refused(Refusal.Envelope(level)))
      .flatMap((size, next) =>
        tokens.word8(next, refused(Refusal.Word8(level))).map((tag, after) => (size, tag, after))
      )

  /** `enforceSize _ n`: where the list's first item starts. */
  private def ofSize(tokens: Tokens, n: Long, at: Int, level: Level): Step[Int] =
    tokens
      .listLength(at, refused(Refusal.Envelope(level)))
      .flatMap((size, next) => Either.cond(size == n, next, refused(Refusal.Size(level, size))))

  /** A tag read with `decodeWord8` that the level's decoder takes when `known`. */
  private def tagIn(tokens: Tokens, at: Int, level: Level)(known: Int => Boolean): Step[Verdict] =
    tokens
      .word8(at, refused(Refusal.Word8(level)))
      .flatMap((tag, _) =>
        Either.cond(
          known(tag),
          LocalStateQuery.QueryPrefix.Short,
          refused(Refusal.UnknownTag(level, tag))
        )
      )

  private def era(tokens: Tokens, at: Int, level: Level): Step[(QueryEra, Int)] =
    tokens
      .word8(at, refused(Refusal.Word8(level)))
      .flatMap((index, next) =>
        QueryEra.fromIndex(index).toRight(refused(Refusal.UnknownEra(level, index))).map((_, next))
      )

  private def block(tokens: Tokens, at: Int)(using QueryTable): Step[Verdict] =
    sized(tokens, at, Level.BlockQuery).flatMap {
      case (2, 0, next) => inEra(tokens, next)
      case (3, 1, next) =>
        ofSize(tokens, 1, next, Level.Anytime)
          .flatMap(tokens.word8(_, refused(Refusal.Word8(Level.Anytime))))
          .flatMap((tag, after) =>
            if tag == 0 then era(tokens, after, Level.AnytimeEra).flatMap(_ => waits)
            else Left(refused(Refusal.UnknownTag(Level.Anytime, tag)))
          )
      case (2, 2, next) =>
        ofSize(tokens, 1, next, Level.HardFork).flatMap(tagIn(tokens, _, Level.HardFork)(_ <= 1))
      case (size, tag, _) => Left(refused(Refusal.Constructor(Level.BlockQuery, size, tag)))
    }

  private def inEra(tokens: Tokens, at: Int)(using QueryTable): Step[Verdict] =
    ofSize(tokens, 2, at, Level.EraQuery)
      .flatMap(era(tokens, _, Level.EraQuery))
      .flatMap {
        case (QueryEra.Byron, next) => tagIn(tokens, next, Level.Byron)(_ == 0)
        case (
              e @ (QueryEra.Shelley | QueryEra.Allegra | QueryEra.Mary | QueryEra.Alonzo |
              QueryEra.Babbage | QueryEra.Conway | QueryEra.Dijkstra),
              next
            ) =>
          // Conway's governance from Conway on (`getConwayEraGovDict`, `Shelley/Eras.hs:215`,
          // ouroboros-consensus 82ecba32)
          ledger(tokens, next, e.index >= QueryEra.Conway.index)
      }

  /** `decodeShelleyQuery`'s size and tag, through any number of `GetCBOR` (`(2, 9)`). */
  @tailrec private def ledger(tokens: Tokens, at: Int, governance: Boolean)(using
      QueryTable
  ): Step[Verdict] =
    sized(tokens, at, Level.Ledger) match
      case Right((2, 9, next)) => ledger(tokens, next, governance)
      case Right((size, tag, next)) =>
        if !NtcQuery.ledgerQueryTakes(size, tag) then
          Left(refused(Refusal.Constructor(Level.Ledger, size, tag)))
        else if NtcQuery.governanceQueries(tag) && !governance then
          Left(refused(Refusal.ConwayOnly(tag)))
        else if size == 2 && tag == 34 then tagIn(tokens, next, Level.PeerKind)(_ <= 1)
        else waits
      case Left(stop) => Left(stop)
