// SPDX-License-Identifier: Apache-2.0
package lab.fetcher

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import lab.network.{
  BlockFetchSession,
  ByteTransport,
  Handshake,
  KeepAliveBlockFetchSession,
  BlockFetch,
  CardanoBlockFetch
}
import lab.network.CardanoBlockFetch.RawNtNBlock

import scala.concurrent.duration.*

/** Checked identity and byte expectations; identities are always derived by EndpointBatch. */
sealed trait ByteExpectationPolicy:
  private[fetcher] def verify(blocks: Vector[RawNtNBlock]): Either[String, Unit]
object ByteExpectationPolicy:
  final case class ExactOrderedOriginals(originals: Vector[PinnedDirectRangeDescriptor.Original])
      extends ByteExpectationPolicy:
    private[fetcher] def verify(blocks: Vector[RawNtNBlock]) =
      Either.cond(
        blocks.map(b =>
          PinnedDirectRangeDescriptor.Original(b.bytes.size, Digests.sha256(b.bytes.value.toArray))
        ) == originals,
        (),
        "original source length/digest/order mismatch"
      )
  final case class KnownBytePin(point: Point, rawSize: Int, rawSha256: String)
  final class OptionalKnownBytePins private[fetcher] (val pins: Vector[KnownBytePin])
      extends ByteExpectationPolicy:
    private[fetcher] def verify(blocks: Vector[RawNtNBlock]): Either[String, Unit] =
      blocks.traverse(b => lab.chain.CardanoBlockIndex.inspect(b.bytes, 1048576)).flatMap {
        indexed =>
          Either.cond(
            pins.forall(pin =>
              indexed.count(b =>
                Point.of(b) == pin.point && b.rawBytes.size == pin.rawSize &&
                  b.rawSha256.hex == pin.rawSha256
              ) == 1
            ),
            (),
            "independent byte pin mismatch or unmatched pin"
          )
      }

final class DirectRangeDescriptor private[fetcher] (
    val batch: EndpointBatch.Spec,
    val data: Handshake.Data,
    val identity: SourceIdentity,
    val bytePolicy: ByteExpectationPolicy,
    val acquisitionTimeout: Option[FiniteDuration],
    private[fetcher] val keepAlive: Option[KeepAliveBlockFetchSession.Policy] = None
)

/** A checked end-only source/store composition. The underlying BlockSource is private so a caller
  * cannot accidentally substitute Fetch.run's early-stop count for the exact inclusive range. Each
  * run opens a fresh owned connection and replays a fully verified batch, including overlap.
  */
final class DirectRangeSource[F[_]: Async] private (
    val identity: SourceIdentity,
    spec: FetchSpec,
    source: BlockSource[F]
):
  def inputBytes: F[Long] = source.inputBytes
  def run(store: SegmentStore[F]): F[FetchResult] = Fetch.run(spec, source, store)

  /** Also bounds store allocation/recovery, before a snapshot necessarily exists. */
  def runOwned(store: Resource[F, SegmentStore[F]]): F[FetchResult] =
    Async[F].timeoutTo(
      store.use(run),
      spec.limits.maxDuration,
      Async[F].raiseError(new FetchError(3, "timeBudget during store recovery/acquisition"))
    )

object DirectRangeSource:
  private trait Session[F[_]]:
    def negotiate(data: Handshake.Data): F[Unit]
    def request(range: CardanoBlockFetch.InclusiveRange): F[Unit]
    def receive: F[BlockFetch.Message[RawNtNBlock]]
    def freeze: F[Unit]
    def finish: F[Unit]
    def bounded[A](work: F[A]): F[A]
  private def session[F[_]: Async](
      transport: ByteTransport[F],
      config: BlockFetchSession.Config,
      policy: Option[KeepAliveBlockFetchSession.Policy]
  ): Resource[F, Session[F]] = policy match
    case None =>
      BlockFetchSession.resource(transport, config).map { s =>
        new Session[F]:
          def negotiate(data: Handshake.Data) = s.negotiate(data)
          def request(range: CardanoBlockFetch.InclusiveRange) = s.request(range)
          def receive = s.receive
          def freeze = Async[F].unit
          def finish = s.finish
          def bounded[A](work: F[A]) = s.bounded(work)
      }
    case Some(p) =>
      KeepAliveBlockFetchSession.resource(transport, config, p).map { s =>
        new Session[F]:
          def negotiate(data: Handshake.Data) = s.negotiate(data)
          def request(range: CardanoBlockFetch.InclusiveRange) = s.request(range)
          def receive = s.receive
          def freeze = s.freeze
          def finish = s.finish
          def bounded[A](work: F[A]) = s.bounded(work)
      }
  def resource[F[_]: Async](
      descriptor: DirectRangeDescriptor,
      spec: FetchSpec,
      connection: Resource[F, ByteTransport[F]],
      config: BlockFetchSession.Config = BlockFetchSession.Config()
  ): Resource[F, DirectRangeSource[F]] =
    val F = Async[F]
    val batchSpec = descriptor.batch
    val last = Point
      .parse(s"${batchSpec.last.slot.value}:${batchSpec.last.hash.hex}")
      .fold(FetchError.config, identity)
    val checked = F.raiseUnless(
      spec.after == batchSpec.anchor && spec.end.contains(last) && spec.count.isEmpty &&
        spec.limits.maxBlocks >= batchSpec.limits.maxBlocks &&
        spec.limits.maxBlockBytes >= batchSpec.limits.maxBlockBytes &&
        config.maxRawBlockBytes == batchSpec.limits.maxBlockBytes
    )(
      new FetchError(
        2,
        "direct range requires matching exclusive anchor and end-only inclusive selection/limits"
      )
    )
    Resource.eval(checked *> (Ref.of[F, Long](0L), Semaphore[F](1)).tupled).map {
      (admitted, gate) =>
        def account(n: Int): F[Unit] = admitted
          .modify { used =>
            if n.toLong > spec.limits.maxInputBytes - used then (used, false)
            else (used + n, true)
          }
          .flatMap(ok => F.raiseUnless(ok)(new FetchError(3, "inputBudget")))
        def verifyOriginals(blocks: Vector[RawNtNBlock]): F[Unit] =
          F.fromEither(descriptor.bytePolicy.verify(blocks).leftMap(new FetchError(5, _)))
        def withDeadline[A](work: F[A]): F[A] = descriptor.acquisitionTimeout.fold(work)(duration =>
          F.timeoutTo(
            work,
            duration,
            F.raiseError(new FetchError(3, "timeBudget during connection/acquisition"))
          )
        )
        val acquire = withDeadline(
          connection
            .flatMap(t => session(t, config, descriptor.keepAlive))
            .use { session =>
              def loop(batch: EndpointBatch.Batch): F[Vector[RawNtNBlock]] =
                session.receive.flatMap { message =>
                  val charge = message match
                    case lab.network.BlockFetch.Message.Block(raw) => account(raw.bytes.size)
                    case _                                         => F.unit
                  charge *> F
                    .fromEither(
                      batch.accept(message).leftMap(e => new FetchError(e.code, e.message))
                    )
                    .flatMap { next =>
                      next.result match
                        case EndpointBatch.Result.Pending => loop(next)
                        case EndpointBatch.Result.Unavailable =>
                          F.raiseError(new FetchError(3, "rangeUnavailable"))
                        case EndpointBatch.Result.Complete(blocks) =>
                          session.freeze *> verifyOriginals(blocks) *> session.finish.as(blocks)
                    }
                }
              session.bounded(
                session.negotiate(descriptor.data) *> session.request(batchSpec.range) *>
                  loop(EndpointBatch.begin(batchSpec))
              )
            }
        )
          .handleErrorWith {
            case e: FetchError => F.raiseError(e)
            case e: KeepAliveBlockFetchSession.OutgoingBudgetExceeded =>
              F.raiseError(new FetchError(3, e.getMessage))
            case _: lab.network.SingleProtocolConnection.WholeDeadlineExceeded
                if descriptor.acquisitionTimeout.nonEmpty =>
              F.raiseError(new FetchError(3, "timeBudget during connection/acquisition"))
            case e =>
              F.raiseError(
                new FetchError(4, Option(e.getMessage).getOrElse("direct-range transport failure"))
              )
          }
        val source = new BlockSource[F]:
          def identity = descriptor.identity
          // Cumulative raw bytes admitted during all acquisitions, including failed opens/replay.
          // Excludes descriptor, mux/CBOR overhead, outgoing bytes; not exact wire bandwidth.
          def inputBytes = admitted.get
          def open: Resource[F, BlockCursor[F]] =
            gate.permit *> Resource.eval(acquire).flatMap { blocks =>
              Resource.eval(Ref.of[F, Int](0)).map { position =>
                new BlockCursor[F]:
                  def next: F[SourceEvent] = position.modify { index =>
                    if index < blocks.size then (index + 1, SourceEvent.Raw(blocks(index).bytes))
                    else (index, SourceEvent.End)
                  }
              }
            }
        new DirectRangeSource(descriptor.identity, spec, source)
    }
