// SPDX-License-Identifier: Apache-2.0
package lab.fetcher

import cats.effect.{Async, Ref, Resource}
import cats.syntax.all.*
import java.nio.file.Path
import lab.cbor.Bytes
import lab.network.{
  AsyncTcpTransport,
  BlockFetch,
  BlockFetchSession,
  ByteTransport,
  CardanoBlockFetch,
  ChainSync,
  Handshake,
  NumericPeer,
  TcpLimits
}
import scala.concurrent.duration.*

/** All useful-work limits are identity-bound. Cleanup is an additional finalization allowance. */
final class TcpFetchLimits private (
    val tcp: TcpLimits,
    val batch: EndpointBatch.Limits,
    val fetch: Limits,
    val session: BlockFetchSession.Config,
    val acquisition: FiniteDuration,
    val maxOutgoingBytes: Long
):
  def canonical: Vector[String] = Vector(
    s"connectNanos\t${tcp.connect.toNanos}",
    s"readNanos\t${tcp.read.toNanos}",
    s"writeNanos\t${tcp.write.toNanos}",
    s"cleanupNanos\t${tcp.cleanup.toNanos}",
    s"threads\t${tcp.threads}",
    s"maxChunkBytes\t${tcp.maxChunkBytes}",
    s"maxBlocks\t${batch.maxBlocks}",
    s"maxRawBytes\t${batch.maxRawBytes}",
    s"maxBlockBytes\t${batch.maxBlockBytes}",
    s"maxInputBytes\t${fetch.maxInputBytes}",
    s"maxStoredBytes\t${fetch.maxStoredBytes}",
    s"maxFiles\t${fetch.maxFiles}",
    s"jobNanos\t${fetch.maxDuration.toNanos}",
    s"acquisitionNanos\t${acquisition.toNanos}",
    s"maxOutgoingBytes\t$maxOutgoingBytes",
    s"handshakeNanos\t${session.handshake.toNanos}",
    s"busyNanos\t${session.times.busy.toNanos}",
    s"streamingNanos\t${session.times.streaming.toNanos}",
    s"idleNanos\t${session.times.idle.fold("-")(_.toNanos.toString)}",
    s"maxSduPayload\t${session.maxSduPayload}",
    s"maxFrames\t${session.maxFrames}",
    s"maxEvents\t${session.maxEvents}",
    s"maxWireBytes\t${session.maxWireBytes}",
    s"maxTotalFrames\t${session.maxTotalFrames}"
  )
object TcpFetchLimits:
  def checked(
      tcp: TcpLimits,
      maxBlocks: Int = 4,
      maxBlockBytes: Int = 1048576,
      maxRawBytes: Long = 4194304L,
      maxWireBytes: Long = 5242880L,
      acquisitionSeconds: Int = 30,
      jobSeconds: Int = 45,
      handshakeSeconds: Int = 5,
      stateSeconds: Int = 10,
      maxOutgoingBytes: Long = 16384L,
      maxStoredBytes: Long = 8388608L,
      maxFiles: Int = 256
  ): Either[String, TcpFetchLimits] =
    for
      _ <- Either.cond(
        acquisitionSeconds > 0 && acquisitionSeconds <= 30 &&
          jobSeconds > 0 && jobSeconds <= 45 && handshakeSeconds > 0 && handshakeSeconds <= 5 &&
          stateSeconds > 0 && stateSeconds <= 10 && maxWireBytes > 0 && maxWireBytes <= 5242880L &&
          maxOutgoingBytes > 0 && maxOutgoingBytes <= 16384 && maxStoredBytes > 0 &&
          maxStoredBytes <= 8388608L && maxFiles <= 256,
        (),
        "invalid TCP fetch limits"
      )
      batch <- EndpointBatch.Limits
        .checked(maxBlocks, maxRawBytes, maxBlockBytes)
        .left
        .map(_.message)
      fetch <- Limits.checked(
        maxBlocks,
        maxBlockBytes,
        maxRawBytes,
        maxStoredBytes,
        maxFiles,
        jobSeconds
      )
    yield new TcpFetchLimits(
      tcp,
      batch,
      fetch,
      BlockFetchSession.Config(
        maxRawBlockBytes = maxBlockBytes,
        maxChunkBytes = tcp.maxChunkBytes,
        maxWireBytes = maxWireBytes,
        handshake = handshakeSeconds.seconds,
        times = BlockFetch.TimeLimits(
          busy = stateSeconds.seconds,
          streaming = stateSeconds.seconds,
          wholeRequest = acquisitionSeconds.seconds
        )
      ),
      acquisitionSeconds.seconds,
      maxOutgoingBytes
    )

/** Endpoint claims and optional independent byte assertions; unknown fetched bytes are absent. */
final class TcpDirectRangeDescriptor private (
    val peer: NumericPeer,
    val networkLabel: String,
    val batch: EndpointBatch.Spec,
    val data: Handshake.Data,
    val provenanceSha256: String,
    val attribution: String,
    val bytePins: Vector[ByteExpectationPolicy.KnownBytePin],
    val limits: TcpFetchLimits,
    val canonical: String,
    val identity: SourceIdentity,
    val spec: FetchSpec
):
  private[fetcher] def core: DirectRangeDescriptor = new DirectRangeDescriptor(
    batch,
    data,
    identity,
    new ByteExpectationPolicy.OptionalKnownBytePins(bytePins),
    Some(limits.acquisition)
  )

object TcpDirectRangeDescriptor:
  val Profile = "ntn14-blockfetch-short-strict-v1"
  private val fields = Set(
    "format",
    "peer",
    "port",
    "networkLabel",
    "networkMagic",
    "anchor",
    "first",
    "last",
    "provenanceSha256",
    "attribution",
    "expectedCount",
    "expectedPoints",
    "bytePins"
  )
  private def point(p: CardanoBlockFetch.SpecificPoint): String = s"${p.slot.value}:${p.hash.hex}"
  def specific(p: Point): Either[String, CardanoBlockFetch.SpecificPoint] =
    for
      slot <- ChainSync.UInt64.from(p.slot)
      hash <- Bytes.fromHex(p.hash)
      result <- CardanoBlockFetch.SpecificPoint.from(ChainSync.Point.Block(slot, hash))
    yield result

  def checked(
      peer: NumericPeer,
      networkLabel: String,
      networkMagic: Long,
      batch: EndpointBatch.Spec,
      provenanceSha256: String,
      attribution: String,
      bytePins: Vector[ByteExpectationPolicy.KnownBytePin],
      limits: TcpFetchLimits
  ): Either[String, TcpDirectRangeDescriptor] =
    val pins = bytePins.sortBy(p => (p.point.slot, p.point.hash))
    val data =
      Handshake.Data(networkMagic, initiatorOnly = true, peerSharing = false, query = false)
    for
      _ <- Either.cond(
        networkMagic >= 0 && networkMagic <= 4294967295L &&
          networkLabel.matches("[A-Za-z0-9._-]{1,60}") && Digests.valid(provenanceSha256) &&
          attribution.matches("[A-Za-z0-9][A-Za-z0-9 ._:/@+-]{0,239}") &&
          batch.limits.maxBlocks == limits.batch.maxBlocks &&
          batch.limits.maxRawBytes == limits.batch.maxRawBytes &&
          batch.limits.maxBlockBytes == limits.batch.maxBlockBytes,
        (),
        "invalid TCP descriptor profile, attribution or limits"
      )
      _ <- Either.cond(
        pins.size <= batch.limits.maxBlocks && batch.expectedCount.forall(pins.size <= _) &&
          pins.map(_.rawSize.toLong).sum <= batch.limits.maxRawBytes &&
          pins.map(_.point).distinct.size == pins.size && pins.forall(p =>
            p.rawSize > 0 && p.rawSize <= batch.limits.maxBlockBytes && Digests.valid(
              p.rawSha256
            ) &&
              p.point.slot >= batch.first.slot.value && p.point.slot <= batch.last.slot.value &&
              (p.point.slot != batch.first.slot.value || p.point.encoded == point(batch.first)) &&
              (p.point.slot != batch.last.slot.value || p.point.encoded == point(batch.last)) &&
              batch.expectedPoints.forall(_.exists(q => point(q) == p.point.encoded))
          ),
        (),
        "invalid, duplicate or out-of-range independent byte pins"
      )
      last <- Point.parse(point(batch.last))
      spec <- FetchSpec.checked(batch.anchor, None, Some(last), limits.fetch)
      canonical = (Vector(
        "tcp-direct-range-v1",
        s"profile\t$Profile",
        "keepAlive\tnone",
        "routing\tstrict-protocol3",
        "indexer\tpost-byron-shelley-conway-v1",
        "version\t14",
        "codec\t2",
        s"endpoint\t${peer.identity}",
        s"networkLabel\t$networkLabel",
        s"networkMagic\t$networkMagic",
        "initiatorOnly\ttrue",
        "peerSharing\tfalse",
        "query\tfalse",
        s"provenanceSha256\t$provenanceSha256",
        s"attribution\t$attribution",
        s"anchor\t${batch.anchor.encoded}",
        s"first\t${point(batch.first)}",
        s"last\t${point(batch.last)}",
        s"expectedCount\t${batch.expectedCount.fold("-")(_.toString)}",
        s"expectedPoints\t${batch.expectedPoints.fold("-")(_.map(point).mkString(","))}",
        s"bytePins\t${
            if pins.isEmpty then "-"
            else pins.map(p => s"${p.point.encoded},${p.rawSize},${p.rawSha256}").mkString(";")
          }"
      ) ++
        limits.canonical).mkString("", "\n", "\n")
      identity <- SourceIdentity.checked(
        Digests.text(canonical),
        s"tcp-blockfetch-$networkLabel",
        batch.anchor
      )
    yield new TcpDirectRangeDescriptor(
      peer,
      networkLabel,
      batch,
      data,
      provenanceSha256,
      attribution,
      pins,
      limits,
      canonical,
      identity,
      spec
    )

  /** Bounded local files only. File names/paths never enter canonical source identity. */
  def load(descriptor: Path, provenance: Path, limits: TcpFetchLimits): TcpDirectRangeDescriptor =
    val text = LocalFiles.utf8(LocalFiles.read(descriptor, 65536))
    val provenanceBytes = LocalFiles.read(provenance, 262144)
    parse(text, Digests.sha256(provenanceBytes), limits).fold(FetchError.config, identity)

  def parse(
      text: String,
      actualProvenanceSha256: String,
      limits: TcpFetchLimits
  ): Either[String, TcpDirectRangeDescriptor] =
    Either.catchOnly[FetchError](LocalFiles.lines(text)).left.map(_.getMessage).flatMap { lines =>
      lines
        .traverse { line =>
          line.split("\t", -1).toList match
            case k :: v :: Nil if fields(k) => Right(k -> v)
            case _                          => Left("unknown TCP descriptor field or invalid line")
        }
        .flatMap { pairs =>
          val m = pairs.toMap
          if pairs.size != fields.size || m.keySet != fields then
            Left("missing or duplicate TCP descriptor fields")
          else if m("format") != "tcp-direct-range-v1" || m(
              "provenanceSha256"
            ) != actualProvenanceSha256
          then Left("TCP descriptor schema or actual provenance digest mismatch")
          else
            def int(s: String): Either[String, Int] = Either
              .cond(s.matches("[1-9][0-9]{0,9}"), s, "invalid positive integer")
              .flatMap(_.toIntOption.toRight("integer overflow"))
            def parsePin(s: String): Either[String, ByteExpectationPolicy.KnownBytePin] =
              s.split(",", -1).toList match
                case p :: size :: hash :: Nil =>
                  (Point.parse(p), int(size)).mapN(ByteExpectationPolicy.KnownBytePin(_, _, hash))
                case _ => Left("invalid independent byte pin")
            for
              port <- int(m("port"))
              peer <- NumericPeer.checked(m("peer"), port)
              magic <- Either
                .cond(
                  m("networkMagic").matches("0|[1-9][0-9]{0,9}"),
                  m("networkMagic"),
                  "invalid network magic"
                )
                .flatMap(_.toLongOption.toRight("invalid network magic"))
              anchor <- Point.parse(m("anchor"))
              first <- Point.parse(m("first")).flatMap(specific)
              last <- Point.parse(m("last")).flatMap(specific)
              count <-
                if m("expectedCount") == "-" then Right(None)
                else int(m("expectedCount")).map(Some(_))
              points <-
                if m("expectedPoints") == "-" then Right(None)
                else
                  m("expectedPoints")
                    .split(",", -1)
                    .toVector
                    .traverse(s => Point.parse(s).flatMap(specific))
                    .map(Some(_))
              batch <- EndpointBatch.Spec
                .checked(
                  anchor,
                  CardanoBlockFetch.InclusiveRange(first, last),
                  count,
                  points,
                  limits.batch
                )
                .left
                .map(_.message)
              pins <-
                if m("bytePins") == "-" then Right(Vector.empty)
                else m("bytePins").split(";", -1).toVector.traverse(parsePin)
              descriptor <- checked(
                peer,
                m("networkLabel"),
                magic,
                batch,
                actualProvenanceSha256,
                m("attribution"),
                pins,
                limits
              )
            yield descriptor
        }
    }

object TcpDirectRangeSource:
  /** An outgoing cap is separate from the connection owner's incoming wire accounting. */
  def resource[F[_]: Async](
      descriptor: TcpDirectRangeDescriptor
  ): Resource[F, DirectRangeSource[F]] =
    fromConnection(descriptor, AsyncTcpTransport.resource(descriptor.peer, descriptor.limits.tcp))

  def fromConnection[F[_]: Async](
      descriptor: TcpDirectRangeDescriptor,
      connection: Resource[F, ByteTransport[F]]
  ): Resource[F, DirectRangeSource[F]] =
    val bounded = connection.flatMap { transport =>
      Resource.eval(Ref.of[F, Long](0L)).map { sent =>
        new ByteTransport[F]:
          def read = transport.read
          def close = transport.close
          def isClosed = transport.isClosed
          def write(bytes: Bytes): F[Unit] = sent
            .modify { used =>
              if bytes.size.toLong > descriptor.limits.maxOutgoingBytes - used then (used, false)
              else (used + bytes.size, true)
            }
            .flatMap(ok =>
              if ok then transport.write(bytes)
              else transport.close *> Async[F].raiseError(new FetchError(3, "outgoingWireBudget"))
            )
      }
    }
    DirectRangeSource.resource(descriptor.core, descriptor.spec, bounded, descriptor.limits.session)
