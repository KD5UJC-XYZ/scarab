// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Temporal
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{BlockFetch, CardanoBlockFetch, ProtocolError}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** BlockFetch server: answers inclusive range requests from a [[BlockFetchServer.Blocks]] source,
  * one request at a time (a client that pipelines is served in order), as the reference's
  * `blockFetchServer'` (ouroboros-consensus 82ecba32, `MiniProtocol/BlockFetch/Server.hs` 87-146).
  */
object BlockFetchServer:
  trait Blocks[F[_]]:
    /** The blocks of the inclusive range `from` to `to`, two blocks, as the reference's ChainDB
      * `stream` answers its server (`Storage/ChainDB/Impl/Iterator.hs` `newIterator`, 254-451): the
      * range's `UnknownRange`, answered `NoBlocks`, or its iterator, each result read when it is
      * pulled. What the reference throws, opening the iterator or in it, is raised
      * ([[NetworkError.RangeRefused]]), which ends the connection. The length of a range is not
      * bounded: the reference server streams any range it holds.
      */
    def range(from: Point, to: Point)(using
        Raise[F, NetworkError]
    ): F[Either[UnknownRange, Stream[F, IteratorResult]]]

  /** Why a range is not one the node holds: the ChainDB's `UnknownRange` (`Storage/ChainDB/API.hs`
    * 793-799, ouroboros-consensus 82ecba32), which the server answers with `NoBlocks`.
    */
  enum UnknownRange:
    /** The block at `point` is not held (`MissingBlock`). */
    case MissingBlock(point: Point)

    /** The range does not reach the immutable chain's tip from `from` (`ForkTooOld`). */
    case ForkTooOld(from: Point)

  /** What a range's iterator gives next: the ChainDB's `IteratorResult` (`Storage/ChainDB/API.hs`
    * 744-783). The stream of a range is the results of the reference's `iteratorNext` calls, up to
    * the first `Exhausted` or `BlockGCed`.
    */
  enum IteratorResult:
    /** The range is streamed whole (`IteratorExhausted`). */
    case Exhausted

    /** The next block of the range, its original bytes (`IteratorResult`). */
    case Block(raw: Bytes)

    /** The next block of the range, at `point`, was collected and is not in the immutable chain
      * (`IteratorBlockGCed`).
      */
    case BlockGCed(point: Point)

  /** @param idle
    *   The wait for the client's next request: BFIdle is waitForever in timeLimitsBlockFetch
    *   (BlockFetch/Codec.hs:65, ouroboros-network c45735a5).
    */
  final case class Settings(
      limits: BlockFetch.Limits = BlockFetch.Limits(),
      idle: ProtocolDeadline = ProtocolDeadline.Unbounded
  )

  def run[F[_]: Temporal](
      channel: MuxChannel[F],
      blocks: Blocks[F],
      settings: Settings = Settings()
  )(using Raise[F, NetworkError]): F[Unit] =
    val F = Temporal[F]
    val id = Protocols.BlockFetch
    val codec = CardanoBlockFetch.payloadCodec()
    def refused[A](refusal: NetworkError.RangeRefusal): F[A] =
      NetworkError.RangeRefused(refusal).raise[F, A]
    def sent(raw: Bytes): F[CardanoBlockFetch.RawNtNBlock] =
      CardanoBlockFetch.RawNtNBlock
        .from(raw)
        .fold(
          error =>
            NetworkError.IllegalTransition(id, error).raise[F, CardanoBlockFetch.RawNtNBlock],
          F.pure
        )
    val genericBlocks = new BlockFetchRuntime.Blocks[F, Point, CardanoBlockFetch.RawNtNBlock]:
      // `blockFetchServer'` (`Server.hs` 104-146): an Origin end throws `NoGenesisBlock` before
      // the ChainDB is asked; `UnknownRange` is `NoBlocks`; an iterator is a batch, each result
      // sent as it comes, `IteratorExhausted` its `BatchDone` and `IteratorBlockGCed` thrown as
      // `BlockGCed`, with no `BatchDone`; what the ChainDB throws ends the connection as well
      def range(
          from: Point,
          to: Point
      ): F[Option[Stream[F, CardanoBlockFetch.RawNtNBlock]]] =
        (from, to) match
          case (_: Point.Block, _: Point.Block) =>
            blocks.range(from, to).map {
              case Left(_) => None
              case Right(results) =>
                Some(
                  results.evalMap {
                    case IteratorResult.Exhausted  => F.pure(None)
                    case IteratorResult.Block(raw) => sent(raw).map(Some(_))
                    case IteratorResult.BlockGCed(point) =>
                      refused[Option[CardanoBlockFetch.RawNtNBlock]](
                        NetworkError.RangeRefusal.BlockGCed(point)
                      )
                  }.unNoneTerminate
                )
            }
          case _ => refused(NetworkError.RangeRefusal.NoGenesisBlock)

    // a range with an Origin end is the server's to refuse, first of all (`NoGenesisBlock`)
    val validated: (Point, Point) => Either[ProtocolError, (Point, Point)] = (from, to) =>
      (from, to) match
        case (_: Point.Block, _: Point.Block) =>
          CardanoBlockFetch.InclusiveRange
            .fromPoints(from, to)
            .map(range => (range.from.point, range.to.point))
        case _ => Right((from, to))

    BlockFetchRuntime.run(
      channel,
      genericBlocks,
      CardanoBlockFetch.pointCodec,
      codec,
      validated,
      BlockFetchRuntime.ServerSettings(
        settings.limits,
        settings.idle
      )
    )
