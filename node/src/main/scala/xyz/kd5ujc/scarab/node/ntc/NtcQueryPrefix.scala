// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.node.PrefixTokens
import xyz.kd5ujc.scarab.protocol.LocalStateQuery

/** A query of `MsgQuery` judged while it has not all arrived. The reference's codec reads the query
  * with `queryDecodeNodeToClient` from the stream (`decodeQuery`, ouroboros-network c45735a5
  * `ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/LocalStateQuery/Codec.hs:169-170`),
  * so a prefix its decoder fails on ends the connection before the rest arrives (#924). The
  * envelopes are read as far as the ledger query's size and tag, each token once its head has
  * arrived whole ([[PrefixTokens]]); a query's arguments are judged once it has all arrived, by
  * [[NtcQuery.decode]]. Every envelope is read with a definite length there too, so a prefix
  * refused here is a prefix of queries that decode refuses whole. ouroboros-consensus 82ecba32
  * unless another pin is named.
  */
object NtcQueryPrefix:
  /** The decoders a query passes through, outermost first, at ouroboros-consensus 82ecba32: the
    * top-level query, `handleTopLevelQuery` (`Ouroboros/Consensus/Ledger/Query.hs:482-491`); the
    * hard-fork combinator's block query
    * (`HardFork/Combinator/Serialisation/SerialiseNodeToClient.hs:456-479`); `decodeNS` around a
    * `QueryIfCurrent` (`HardFork/Combinator/Serialisation/Common.hs:420-425`); `QueryAnytime` 's
    * query (`HardFork/Combinator/Ledger/Query.hs:545-550`) and its era, the `Serialise` of
    * `EraIndex` (`HardFork/Combinator/Abstract/SingleEraBlock.hs:169-175`); `decodeQueryHardFork`
    * (`SerialiseNodeToClient.hs:410-417`); `decodeByronQuery`, a bare tag
    * (`Byron/Ledger/Ledger.hs:556-561`); `decodeShelleyQuery` (`Shelley/Ledger/Query.hs:916-998`)
    * and `GetLedgerPeerSnapshot` 's peer kind (`Shelley/Ledger/Query.hs:986-992`).
    */
  enum Level:
    case Query, BlockQuery, EraQuery, Anytime, AnytimeEra, HardFork, Byron, Ledger, PeerKind

  /** Where the reference's decoder of queries fails on the part of a query that has arrived. */
  enum Refusal extends DomainError:
    /** `decodeListLen`: not a definite list. */
    case Envelope(level: Level)

    /** `enforceSize`: a definite list of another length. */
    case Size(level: Level, size: Long)

    /** `decodeWord8`: not an unsigned integer up to 255. */
    case Word8(level: Level)

    /** A size and a tag, judged together, that the level's decoder has no case for. */
    case Constructor(level: Level, size: Long, tag: Int)

    /** A tag the level's decoder has no case for. */
    case UnknownTag(level: Level, tag: Int)

    /** An era index past the last era of `CardanoEras` (`nsFromIndex`). */
    case UnknownEra(level: Level, index: Int)

    /** A query that `requireCG` guards, of an era before Conway (`Shelley/Ledger/Query.hs:936-942`,
      * ouroboros-consensus 82ecba32).
      */
    case ConwayOnly(tag: Int)

  /** What the reference's decoder of queries makes of `input`, the part of a query that has
    * arrived.
    */
  def prefix(input: Bytes): LocalStateQuery.QueryPrefix =
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

  private def block(tokens: Tokens, at: Int): Step[Verdict] =
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

  private def inEra(tokens: Tokens, at: Int): Step[Verdict] =
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
  @tailrec private def ledger(tokens: Tokens, at: Int, governance: Boolean): Step[Verdict] =
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
