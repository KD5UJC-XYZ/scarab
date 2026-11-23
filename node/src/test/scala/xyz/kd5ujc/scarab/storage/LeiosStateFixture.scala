// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.nio.file.{Files as JFiles, Path as JPath}
import munit.Assertions.fail
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{AnnTip, TipInfo}
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.state.{BlockNo, Hash32, ShelleyBasedEra, SlotNo, WithOrigin}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.haskell.state.{ReferenceLayout, SnapshotCrc, SnapshotMeta}

/** The Leios build's devnet state of `fixtures/leios/state` (`dijkstra-203`, the first Dijkstra
  * state of its run) with its header's tip moved to the first Dijkstra block of
  * `fixtures/dijkstra-devnet` (another run of the same build, whose blocks are kept), and its
  * `meta` with the checksum of the moved state and the captured tables: a Dijkstra state whose
  * point is a block a database can hold, followed by the Dijkstra blocks of that chain.
  */
object LeiosStateFixture:
  private val fixture = JPath.of("fixtures/leios/state")

  /** The setup of the devnet the state is of, under the Leios build. */
  val setup: NodeSetup =
    NodeSetup
      .fromDirectory(ReferenceBuild.Leios)(fixture.resolve("genesis"))
      .fold(e => fail(e.message), identity)

  val layout: ReferenceLayout =
    ReferenceLayout.of(ReferenceBuild.Leios).fold(e => fail(e.message), identity)

  def read(state: String, name: String): Bytes =
    Bytes.wrap(JFiles.readAllBytes(fixture.resolve(s"$state/$name")))

  /** A block of `fixtures/dijkstra-devnet` and its point. */
  final case class Stored(raw: Bytes, point: BlockRef)

  /** The Dijkstra blocks of `fixtures/dijkstra-devnet/blocks.tsv` (`[8, block]`), in chain order.
    */
  val dijkstraBlocks: Vector[Stored] =
    JFiles
      .readAllLines(JPath.of("fixtures/dijkstra-devnet/blocks.tsv"))
      .toArray(Array.empty[String])
      .toVector
      .drop(1)
      .map(_.split('\t'))
      .collect { case row if row(0) == "8" => row(4) }
      .map { hex =>
        val raw = Bytes.fromHex(hex).fold(e => fail(e.message), identity)
        val h = Block
          .decode(raw, setup.geometry.byron.epochSlots)
          .fold(e => fail(e.message), identity)
          .header
        Stored(raw, BlockRef(h.slot, h.hash, h.blockNo, h.isBoundary))
      }

  /** The first Dijkstra block of `fixtures/dijkstra-devnet`, the moved state's point. */
  val first: Stored = dijkstraBlocks.headOption.getOrElse(fail("no Dijkstra block"))

  /** Slot 203's `state` with its header's tip at [[first]], written in the Leios layout, and its
    * `meta` with the checksum of that state and the captured tables (`crcOfConcat`,
    * [[SnapshotCrc.combined]]).
    */
  val (movedState, movedMeta) =
    val file = layout.decode(read("dijkstra-203", "state")).fold(e => fail(e.render), identity)
    val tip = AnnTip(
      SlotNo.fromUnsigned(first.point.slot.toLong),
      BlockNo.fromUnsigned(first.point.blockNo.toLong),
      TipInfo.ShelleyBasedTip(
        ShelleyBasedEra.SDijkstra,
        Hash32.fromBytes(first.point.hash).fold(e => fail(e.toString), identity)
      )
    )
    val state = layout
      .encode(file.copy(header = file.header.copy(tip = WithOrigin.At(tip))))
      .fold(e => fail(e.violation.render), identity)
    val meta = SnapshotMeta
      .parse(new String(read("dijkstra-203", "meta").toArray, "UTF-8"))
      .flatMap(m =>
        SnapshotMeta.encode(
          m.copy(checksum =
            SnapshotCrc
              .combined(SnapshotCrc.of(state), SnapshotCrc.of(read("dijkstra-203", "tables")))
              .value
          )
        )
      )
      .fold(e => fail(e.toString), identity)
    (state, Bytes.wrap(meta.getBytes("UTF-8")))

  /** The moved state's three files, as a reference ledger directory holds them. */
  val movedFiles: Vector[(String, Bytes)] =
    Vector(
      "state" -> movedState,
      "tables" -> read("dijkstra-203", "tables"),
      "meta" -> movedMeta
    )
