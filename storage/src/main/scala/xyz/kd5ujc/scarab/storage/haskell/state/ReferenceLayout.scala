// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.models.runtime.Decoded
import xyz.kd5ujc.scarab.protocol.{ReferenceBuild, StateLayout}

/** A reference build with the codec of the `state` layout it writes ([[StateLayout.of]]): what a
  * snapshot format, an import or an export reads and writes the reference's bytes with.
  */
final class ReferenceLayout private (val build: ReferenceBuild):
  /** The layout of the bytes this codec reads and writes. */
  def layout: StateLayout = StateLayout.of(build)

  /** Whether state bytes written under `recorded` are in this layout, and so read by this codec:
    * there is no conversion between layouts.
    */
  def reads(recorded: ReferenceBuild): Boolean = StateLayout.of(recorded) == layout

  /** The `state` file, or the first thing wrong with the bytes and where it is. */
  def decode(bytes: Bytes): Decoded[HaskellLedgerFile] = HaskellLedgerFile.decode(bytes, layout)

  /** The bytes of the `state` file, or why it cannot be written (a part of another layout among
    * them).
    */
  def encode(file: HaskellLedgerFile): Either[StateNotWritable, Bytes] =
    HaskellLedgerFile.encode(file, layout)

object ReferenceLayout:
  /** The codec of the cardano-node 11.1 line, the default build's: what the tools without the flag,
    * the SCLS conversions and the tests use.
    */
  val Release11_1: ReferenceLayout = new ReferenceLayout(ReferenceBuild.Release11_1)

  /** The codec of `build`'s layout. Every build's layout has one. */
  def of(build: ReferenceBuild): ReferenceLayout = new ReferenceLayout(build)
