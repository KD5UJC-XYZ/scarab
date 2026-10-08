// SPDX-License-Identifier: Apache-2.0
package lab.fetcher

import cats.effect.{Async, Resource}
import lab.network.{BlockFetchSession, ByteTransport, Handshake}

/** Pinned, canonical fixture recipe, not captured traffic or authenticated network evidence. */
final class PinnedDirectRangeDescriptor private (
    val batch: EndpointBatch.Spec,
    val data: Handshake.Data,
    val originals: Vector[PinnedDirectRangeDescriptor.Original],
    val canonical: String,
    val identity: SourceIdentity
)
object PinnedDirectRangeDescriptor:
  final case class Original(size: Int, sha256: String)
  def checked(
      batch: EndpointBatch.Spec,
      data: Handshake.Data,
      originalManifestSha256: String,
      originalProvenanceSha256: String,
      originals: Vector[Original],
      scriptSha256: String,
      upstreamPins: Vector[String],
      networkLabel: String = "mainnet-labelled-unauthenticated"
  ): Either[String, PinnedDirectRangeDescriptor] =
    def point(p: lab.network.CardanoBlockFetch.SpecificPoint): String =
      s"${p.slot.value}:${p.hash.hex}"
    if data.query || Handshake
        .dataTerm(Handshake.Suite.NodeToNode, 14, data)
        .isLeft || !networkLabel.matches("[A-Za-z0-9._-]{1,60}") ||
      !Vector(originalManifestSha256, originalProvenanceSha256, scriptSha256).forall(
        Digests.valid
      ) ||
      originals.isEmpty || originals.size > batch.limits.maxBlocks ||
      originals.exists(o =>
        o.size <= 0 || o.size > batch.limits.maxBlockBytes || !Digests.valid(o.sha256)
      ) ||
      originals.map(_.size.toLong).sum > batch.limits.maxRawBytes ||
      batch.expectedCount.exists(_ != originals.size) ||
      batch.expectedPoints.exists(_.size != originals.size) ||
      upstreamPins.isEmpty || upstreamPins.size > 16 ||
      upstreamPins.exists(p => !p.matches("[A-Za-z0-9._:/-]{1,240}"))
    then Left("invalid pinned direct-range descriptor")
    else
      val canonical = Vector(
        "fixture-direct-range-v1",
        "profile\tstrict-single-active-NtN14-protocol3-cardano-codec2",
        s"networkLabel\t$networkLabel",
        s"networkMagic\t${data.magic}",
        s"diffusion\t${data.initiatorOnly}",
        s"peerSharing\t${data.peerSharing}",
        "query\tfalse",
        s"originalManifestSha256\t$originalManifestSha256",
        s"originalProvenanceSha256\t$originalProvenanceSha256",
        s"scriptSha256\t$scriptSha256",
        s"anchor\t${batch.anchor.encoded}",
        s"first\t${point(batch.first)}",
        s"last\t${point(batch.last)}",
        s"expectedCount\t${batch.expectedCount.fold("-")(_.toString)}",
        s"expectedPoints\t${batch.expectedPoints.fold("-")(_.map(point).mkString(","))}",
        s"maxBlocks\t${batch.limits.maxBlocks}",
        s"maxRawBytes\t${batch.limits.maxRawBytes}",
        s"maxBlockBytes\t${batch.limits.maxBlockBytes}"
      ).concat(originals.map(o => s"original\t${o.size}\t${o.sha256}"))
        .concat(upstreamPins.map(p => s"upstream\t$p"))
        .mkString("", "\n", "\n")
      SourceIdentity
        .checked(Digests.text(canonical), s"fixture-blockfetch-$networkLabel", batch.anchor)
        .map(new PinnedDirectRangeDescriptor(batch, data, originals, canonical, _))

/** Compatibility facade preserving fixture descriptor bytes and exact-original policy. */
final class FixtureDirectRangeSource[F[_]: Async] private (core: DirectRangeSource[F]):
  def identity: SourceIdentity = core.identity
  def inputBytes: F[Long] = core.inputBytes
  def run(store: SegmentStore[F]): F[FetchResult] = core.run(store)
  def runOwned(store: Resource[F, SegmentStore[F]]): F[FetchResult] = core.runOwned(store)

object FixtureDirectRangeSource:
  def resource[F[_]: Async](
      descriptor: PinnedDirectRangeDescriptor,
      spec: FetchSpec,
      fixtureConnection: Resource[F, ByteTransport[F]],
      config: BlockFetchSession.Config = BlockFetchSession.Config()
  ): Resource[F, FixtureDirectRangeSource[F]] =
    DirectRangeSource
      .resource(
        new DirectRangeDescriptor(
          descriptor.batch,
          descriptor.data,
          descriptor.identity,
          ByteExpectationPolicy.ExactOrderedOriginals(descriptor.originals),
          None
        ),
        spec,
        fixtureConnection,
        config
      )
      .map(new FixtureDirectRangeSource(_))
