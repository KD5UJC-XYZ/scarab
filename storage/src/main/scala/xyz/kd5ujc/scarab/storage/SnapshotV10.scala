// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** The snapshot of format version 10, the previous one: the V9 layout with the reference build
  * whose layout its Haskell state bytes are in recorded after the magic (#826), the layout V11 has:
  *
  * {{{
  * "SCRBSNPA" | build length u8 | build name, ASCII | binding length u32 | binding | V5 body
  * binding = schema fingerprint[8] | Codec of CheckpointBinding     (as in SnapshotV9)
  * V5 body = sidecar length u32 | sidecar | Haskell state bytes     (as in SnapshotV5)
  * }}}
  *
  * Its sidecar has the schema V9 has, frozen at V11 ([[SnapshotSidecarV10]]), with the bootstrap
  * (ledger-lite) state that V11 no longer holds. A V10 file is read only to be migrated to V11
  * ([[SnapshotV11.fromV10]]), also in memory by the export and the SCLS base of a database not yet
  * opened by this build.
  *
  * The migration from V9 ([[fromV9]]) records the 11.1 build and keeps the binding, the sidecar and
  * the Haskell bytes byte for byte: a V9 file holds only 11.1-layout bytes. Every V9 writer either
  * encodes with the 11.1 codec ([[SnapshotV5.parts]]) or keeps bytes that the 11.1 codec decoded
  * completely (the imports), and that codec refuses every other layout: a Dijkstra telescope or tip
  * (`DijkstraNotYet.LedgerState`), and the Leios build's three-part `ExtLedgerState`
  * (ouroboros-consensus 2416493b `Ledger/Extended.hs:420-442`), as the reference's 11.1 decoder
  * does (`decodeListLenOf 2`, 82ecba32 `Ledger/Extended.hs:268-280`).
  */
object SnapshotV10:
  val Magic: Array[Byte] = "SCRBSNPA".getBytes(StandardCharsets.US_ASCII)

  /** A V10 file's content, in one array of its exact size: what the previous-version tests write
    * (the node writes [[SnapshotV11]]).
    */
  private[storage] def framed(
      build: ReferenceBuild,
      binding: CheckpointBinding,
      sidecar: FrozenSidecar.SnapshotSidecar,
      haskell: Bytes
  ): Array[Byte] =
    val recorded = SnapshotV11.buildBytes(build)
    val bound = SnapshotV9.bindingBytes(binding)
    val side = SnapshotV5.v10SidecarBytes(sidecar)
    val at = Magic.length + recorded.length
    val out = new Array[Byte](at + 4 + bound.length + 4 + side.length + haskell.size)
    System.arraycopy(Magic, 0, out, 0, Magic.length)
    System.arraycopy(recorded, 0, out, Magic.length, recorded.length)
    ByteBuffer.wrap(out, at, 4).putInt(bound.length)
    System.arraycopy(bound, 0, out, at + 4, bound.length)
    SnapshotV5.writeBody(out, at + 4 + bound.length, side, haskell)
    out

  /** The V10 content of a V9 file's content: the 11.1 build recorded, and the binding, the sidecar
    * and the Haskell bytes copied as they were, after they have been read (so a file that does not
    * read does not convert). Nothing after the magic is encoded again: a V9 file whose binding or
    * sidecar is in an encoding the reader takes but the writer would not produce keeps it. Every V9
    * file converts, its Haskell bytes being in the 11.1 layout (see above).
    */
  def fromV9(content: Array[Byte]): Either[StorageError, Array[Byte]] =
    SnapshotV9
      .sections(content)
      .map(_ =>
        Magic ++ SnapshotV11.buildBytes(ReferenceBuild.Release11_1) ++
          content.drop(SnapshotV9.Magic.length)
      )

  // ---- read

  /** The name of the build recorded in the content of a V10 file, as written. */
  def recordedOf(content: Array[Byte]): Either[StorageError, String] =
    SnapshotV11.header(Magic, content).map(_._1)

  /** The binding of the content of a V10 file. */
  def bindingOf(content: Array[Byte]): Either[StorageError, CheckpointBinding] =
    SnapshotV11.header(Magic, content).map(_._2)

  /** The sidecar of the content of a V10 file, read into the current types. */
  def sidecarOf(content: Array[Byte]): Either[StorageError, SnapshotSidecar] =
    located(content).map(s => FrozenSidecar.live(s._2))

  /** The recorded build's name, the binding, the sidecar and the Haskell bytes of a V10 file, after
    * the magic, the lengths and the fingerprints have been checked. The build is not admitted: the
    * file is not decoded.
    */
  private[storage] def sections(
      content: Array[Byte]
  ): Either[StorageError, (String, CheckpointBinding, FrozenSidecar.SnapshotSidecar, Bytes)] =
    SnapshotV11.header(Magic, content).flatMap { (name, binding, at) =>
      SnapshotV5
        .previousBody(content, at, SnapshotV5.Schema.V10)
        .map((sidecar, haskell) => (name, binding, sidecar, haskell))
    }

  /** The offset of the body, the sidecar and the Haskell bytes of a V10 file, after the magic, the
    * lengths and the fingerprints have been checked.
    */
  private[storage] def located(
      content: Array[Byte]
  ): Either[StorageError, (Int, FrozenSidecar.SnapshotSidecar, Bytes)] =
    SnapshotV11.header(Magic, content).flatMap { (_, _, at) =>
      SnapshotV5
        .previousBody(content, at, SnapshotV5.Schema.V10)
        .map((sidecar, haskell) => (at, sidecar, haskell))
    }
