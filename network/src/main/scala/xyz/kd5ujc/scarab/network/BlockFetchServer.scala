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
    /** What the source answers for the inclusive range `from` to `to`, two blocks: the reference's
      * ChainDB `stream` (`Storage/ChainDB/Impl/Iterator.hs` `newIterator`). The length of a range
      * is not bounded: the reference server streams any range it holds.
      */
    def range(from: Point, to: Point): F[Answer[F]]

  /** A source's answer for a range. */
  enum Answer[F[_]]:
    /** The range is not one the node holds (`UnknownRange`): the client gets `NoBlocks`. */
    case NoBlocks()

    /** The blocks of the range, oldest first, each read when it is pulled. A refusal ends the batch
      * where it stands, before `BatchDone` (`IteratorBlockGCed`, `Server.hs` 144-146).
      */
    case Batch(blocks: Stream[F, Either[NetworkError.RangeRefusal, Bytes]])

    /** The bounds are refused before anything is sent (`InvalidIteratorRange`). */
    case Refused(refusal: NetworkError.RangeRefusal)

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
    val genericBlocks = new BlockFetchRuntime.Blocks[F, Point, CardanoBlockFetch.RawNtNBlock]:
      // an Origin end ends the connection before the source is asked (`NoGenesisBlock`,
      // `Server.hs` 107-112); a refusal of the source ends it before `StartBatch`, or in the
      // batch before `BatchDone`, and `UnknownRange` is `NoBlocks` (118-146)
      def range(
          from: Point,
          to: Point
      ): F[Option[Stream[F, CardanoBlockFetch.RawNtNBlock]]] =
        (from, to) match
          case (_: Point.Block, _: Point.Block) =>
            blocks.range(from, to).flatMap {
              case Answer.NoBlocks()       => F.pure(None)
              case Answer.Refused(refusal) => refused(refusal)
              case Answer.Batch(stream) =>
                F.pure(
                  Some(
                    stream.evalMap(
                      _.fold(
                        refused,
                        raw =>
                          CardanoBlockFetch.RawNtNBlock
                            .from(raw)
                            .fold(
                              error =>
                                NetworkError
                                  .IllegalTransition(id, error)
                                  .raise[F, CardanoBlockFetch.RawNtNBlock],
                              F.pure
                            )
                      )
                    )
                  )
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
