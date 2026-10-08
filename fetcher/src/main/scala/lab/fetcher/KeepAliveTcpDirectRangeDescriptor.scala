// SPDX-License-Identifier: Apache-2.0
package lab.fetcher

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import java.nio.file.Path
import lab.network.{AsyncTcpTransport, ByteTransport, KeepAliveBlockFetchSession}

/** Explicit v2 identity; strict v1 validation is reused, never automatically upgraded. */
final class KeepAliveTcpDirectRangeDescriptor private (
    private val base: TcpDirectRangeDescriptor,
    val policy: KeepAliveBlockFetchSession.Policy,
    val canonical: String,
    val identity: SourceIdentity
):
  def peer = base.peer
  def networkLabel = base.networkLabel
  def batch = base.batch
  def data = base.data
  def provenanceSha256 = base.provenanceSha256
  def attribution = base.attribution
  def bytePins = base.bytePins
  def limits = base.limits
  def spec = base.spec
  private[fetcher] def core = new DirectRangeDescriptor(
    batch,
    data,
    identity,
    new ByteExpectationPolicy.OptionalKnownBytePins(bytePins),
    Some(limits.acquisition),
    Some(policy)
  )

object KeepAliveTcpDirectRangeDescriptor:
  val Profile = KeepAliveBlockFetchSession.Profile
  def parse(
      text: String,
      actualProvenanceSha256: String,
      limits: TcpFetchLimits,
      policy: KeepAliveBlockFetchSession.Policy = KeepAliveBlockFetchSession.Policy()
  ): Either[String, KeepAliveTcpDirectRangeDescriptor] =
    for
      lines <- Either.catchOnly[FetchError](LocalFiles.lines(text)).left.map(_.getMessage)
      _ <- Either.cond(
        lines.count(_ == "format\ttcp-direct-range-v2") == 1 &&
          lines.count(_ == s"profile\t$Profile") == 1 && policy.valid &&
          policy.maxOutgoingBytes == limits.maxOutgoingBytes,
        (),
        "explicit v2 KeepAlive profile or limits required"
      )
      // Only the explicitly required schema/profile is normalized for shared selector validation.
      normalized = lines
        .filterNot(_ == s"profile\t$Profile")
        .map {
          case "format\ttcp-direct-range-v2" => "format\ttcp-direct-range-v1"
          case line                          => line
        }
        .mkString("", "\n", "\n")
      base <- TcpDirectRangeDescriptor.parse(normalized, actualProvenanceSha256, limits)
      canonical = base.canonical.linesIterator
        .map {
          case "tcp-direct-range-v1"                => "tcp-direct-range-v2"
          case line if line.startsWith("profile\t") => s"profile\t$Profile"
          case "keepAlive\tnone"                    => "keepAlive\tinitiator-codec2"
          case "routing\tstrict-protocol3"          => "routing\tclosed-responder3-responder8"
          case line                                 => line
        }
        .mkString("", "\n", "\n") + Vector(
        "ownerRevision\trouted-single-engine-v1",
        "completion\tgraceful-required-v1",
        "cookieStrategy\tuniform-uint16-threadlocal-v1",
        "keepAliveStart\timmediate-before-blockfetch-request",
        "keepAliveAgency\tone-outstanding-matched-response-v1",
        "keepAliveResponseStart\tbefore-request-write",
        "keepAliveCodec\t2",
        "keepAliveMessageBytes\t65535",
        "keepAliveUpstreamClientNanos\t97000000000",
        "keepAliveUpstreamServerNanos\t60000000000",
        s"keepAliveIntervalNanos\t${policy.interval.toNanos}",
        s"keepAliveResponseNanos\t${policy.response.toNanos}",
        s"keepAliveFinishNanos\t${policy.finish.toNanos}",
        s"keepAliveQueueBytes\t${policy.keepAliveQueueBytes}",
        s"keepAliveQueueFrames\t${policy.keepAliveQueueFrames}",
        s"keepAlivePendingBytes\t${policy.keepAliveQueueBytes}",
        s"keepAliveHandoffBytes\t${policy.keepAliveQueueBytes}",
        s"blockFetchQueueBytes\t${policy.bfQueueBytes(limits.session)}",
        s"virtualBlockFetchRemainderBytes\t${policy.virtualRemainderBytes(limits.session)}",
        s"routedIngressBytes\t${policy.routedIngressBytes(limits.session)}",
        s"globalIngressBytes\t${policy.globalIngressBytes(limits.session)}",
        "outboundMailboxMessagesPerProtocol\t1",
        "writerFairness\talternating-ready-sdus-v1",
        s"maxOutgoingFrames\t${policy.maxOutgoingFrames}",
        "doneOrdering\tkeepalive-then-blockfetch",
        "handshakePhase\tstage-protocol3-preserve-protocol0-reject-unsolicited8"
      ).mkString("", "\n", "\n")
      identity <- SourceIdentity.checked(
        Digests.text(canonical),
        s"tcp-blockfetch-keepalive-${base.networkLabel}",
        base.batch.anchor
      )
    yield new KeepAliveTcpDirectRangeDescriptor(base, policy, canonical, identity)

  def load(
      descriptor: Path,
      provenance: Path,
      limits: TcpFetchLimits
  ): KeepAliveTcpDirectRangeDescriptor =
    parse(
      LocalFiles.utf8(LocalFiles.read(descriptor, 65536)),
      Digests.sha256(LocalFiles.read(provenance, 262144)),
      limits
    ).fold(FetchError.config, identity)

object KeepAliveTcpDirectRangeSource:
  def resource[F[_]: Async](
      descriptor: KeepAliveTcpDirectRangeDescriptor
  ): Resource[F, DirectRangeSource[F]] =
    fromConnection(descriptor, AsyncTcpTransport.resource(descriptor.peer, descriptor.limits.tcp))
  def fromConnection[F[_]: Async](
      descriptor: KeepAliveTcpDirectRangeDescriptor,
      connection: Resource[F, ByteTransport[F]]
  ): Resource[F, DirectRangeSource[F]] =
    // The routed owner charges the actual handshake and both protocols exactly once.
    DirectRangeSource.resource(
      descriptor.core,
      descriptor.spec,
      connection,
      descriptor.limits.session
    )
