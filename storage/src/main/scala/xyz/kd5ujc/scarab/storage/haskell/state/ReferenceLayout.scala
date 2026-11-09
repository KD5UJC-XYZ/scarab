// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.models.runtime.Decoded
import xyz.kd5ujc.scarab.protocol.{ReferenceBuild, ReferenceBuildNotYet}

/** The layout of the reference's `state` file that a reference build writes (#826). Several builds
  * can share one ([[StateLayout.of]]): a build names what the operator runs, a layout what the
  * bytes are.
  */
enum StateLayout derives CanEqual:
  /** The cardano-node 11.1 line: `[1, [ledger state telescope, header state]]`, an `ExtLedgerState`
    * of two parts (ouroboros-consensus 82ecba32 `Ledger/Extended.hs:233-248`), what
    * [[HaskellLedgerFile]] reads and writes.
    */
  case Node11_1

  /** The Leios build: its `ExtLedgerState` has a third part, the Peras state (ouroboros-consensus
    * 2416493b `Ledger/Extended.hs:420-442`), and its era states differ from 11.1's in every era.
    * Not implemented yet (#826 step b).
    */
  case Leios

object StateLayout:
  /** The layout `build` writes. */
  def of(build: ReferenceBuild): StateLayout = build match
    case ReferenceBuild.Release11_1   => Node11_1
    case ReferenceBuild.LeiosB004fe0c => Leios

/** A reference build whose `state` layout this build reads and writes, with that layout's codec. It
  * is the only way to a snapshot format, an import or an export, so a build whose layout has no
  * codec reaches none of them: [[ReferenceLayout.of]] refuses it at startup.
  */
final class ReferenceLayout private (val build: ReferenceBuild):
  /** The layout of the bytes this codec reads and writes. */
  def layout: StateLayout = StateLayout.of(build)

  /** Whether state bytes written under `recorded` are in this layout, and so read by this codec:
    * there is no conversion between layouts.
    */
  def reads(recorded: ReferenceBuild): Boolean = StateLayout.of(recorded) == layout

  /** The `state` file, or the first thing wrong with the bytes and where it is. */
  def decode(bytes: Bytes): Decoded[HaskellLedgerFile] = HaskellLedgerFile.decode(bytes)

  /** The bytes of the `state` file, or why it cannot be written. */
  def encode(file: HaskellLedgerFile): Either[StateNotWritable, Bytes] =
    HaskellLedgerFile.encode(file)

object ReferenceLayout:
  /** The codec of the cardano-node 11.1 line, the default build's: what the tools without the flag,
    * the SCLS conversions and the tests use.
    */
  val Release11_1: ReferenceLayout = new ReferenceLayout(ReferenceBuild.Release11_1)

  /** The codec of `build`, or the refusal of a build whose layout is not implemented. */
  def of(build: ReferenceBuild): Either[ReferenceBuildNotYet, ReferenceLayout] =
    StateLayout.of(build) match
      case StateLayout.Node11_1 => Right(new ReferenceLayout(build))
      case StateLayout.Leios    => Left(ReferenceBuildNotYet.StateLayout(build))
