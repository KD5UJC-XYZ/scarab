// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.{Eq, Show}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{
  EraIndexed,
  EraLedgerState,
  ExtLedgerState,
  HeaderState,
  Telescope,
  TelescopeError
}
import xyz.kd5ujc.scarab.models.runtime.Decoded
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** The ledger state of one era of the telescope as a `state` file holds it: a Shelley-based era's,
  * which the mirror models, or Byron's as the original item. The Byron ledger state (the
  * `ChainValidationState` of `cardano-ledger-byron`: the UTxO, delegation and update state) is not
  * modelled; only a file taken while the chain is still in Byron has it as the current state, and
  * the item is written back as it was.
  */
enum LedgerFileEra:
  case ByronItem(item: Bytes)
  case Mirrored(state: EraLedgerState)

object LedgerFileEra:
  /** The era the state is in. */
  extension (s: LedgerFileEra)
    def era: Era = s match
      case ByronItem(_) => Era.Byron
      case Mirrored(m)  => m.era

  given EraIndexed[LedgerFileEra] = EraIndexed.instance((era, state) => state.era == era)

  given Eq[LedgerFileEra] = Eq.instance {
    case (ByronItem(a), ByronItem(b)) => a == b
    case (Mirrored(a), Mirrored(b))   => a === b
    case _                            => false
  }

  given Show[LedgerFileEra] = Show.show {
    case ByronItem(b) => s"ByronItem(${b.size} bytes)"
    case Mirrored(m)  => s"Mirrored(${m.show})"
  }

/** The `state` file of a ledger snapshot of the Haskell node (`ledger/<slot>/state`): the extended
  * ledger state without its tables (the UTxO is in the `tables` file), written `[1, [ledger state
  * telescope, header state]]` in the 11.1 layout and with the Peras state third in the Leios layout
  * (`layout`, [[ExtLedgerState.Layout]]). The `meta` file beside it is [[SnapshotMeta]].
  */
final case class HaskellLedgerFile(
    ledger: Telescope[LedgerFileEra],
    header: HeaderState,
    layout: ExtLedgerState.Layout
):
  /** The era the chain is in. */
  def era: Era = ledger.era

  /** The extended ledger state of the mirror, unless the chain is still in Byron (whose ledger
    * state the mirror does not hold: [[LedgerFileEra.ByronItem]]). The tables are not in the file.
    */
  def ext: Option[ExtLedgerState] =
    ledger.state match
      case LedgerFileEra.ByronItem(_) => None
      case LedgerFileEra.Mirrored(state) =>
        Telescope
          .make(ledger.past, ledger.current.copy(state = state))
          .toOption
          .map(ExtLedgerState(_, header, layout))

object HaskellLedgerFile:
  /** The file of an extended ledger state of the mirror (the Shelley-based eras: the Byron ledger
    * state is not modelled, so it is never a [[LedgerFileEra.ByronItem]] here).
    */
  def of(ext: ExtLedgerState): Either[TelescopeError, HaskellLedgerFile] =
    Telescope
      .make(
        ext.ledgerState.past,
        ext.ledgerState.current.copy(state = LedgerFileEra.Mirrored(ext.ledgerState.state))
      )
      .map(HaskellLedgerFile(_, ext.headerState, ext.layout))

  given Eq[HaskellLedgerFile] =
    Eq.instance((a, b) => a.ledger === b.ledger && a.header === b.header && a.layout === b.layout)

  given Show[HaskellLedgerFile] =
    Show.show(f =>
      s"HaskellLedgerFile(ledger = ${f.ledger.show}, header = ${f.header.show}, " +
        s"layout = ${f.layout.show})"
    )

  /** The codecs of `layout`. */
  def codecs(layout: StateLayout): LedgerFileCodecs = layout match
    case StateLayout.Node11_1 => HaskellStateCodecs
    case StateLayout.Leios    => LeiosStateCodecs

  /** The file in the 11.1 layout, or the first thing wrong with the bytes and where it is. */
  def decode(bytes: Bytes): Decoded[HaskellLedgerFile] = decode(bytes, StateLayout.Node11_1)

  /** The bytes of the file in the 11.1 layout, or why it cannot be written. */
  def encode(file: HaskellLedgerFile): Either[StateNotWritable, Bytes] =
    encode(file, StateLayout.Node11_1)

  /** The file in `layout`, or the first thing wrong with the bytes and where it is. */
  def decode(bytes: Bytes, layout: StateLayout): Decoded[HaskellLedgerFile] =
    codecs(layout).haskellLedgerFile.decodeBytes(bytes, HaskellState.limits)

  /** The bytes of the file in `layout`, or why it cannot be written: a value with a part of another
    * layout is not.
    */
  def encode(file: HaskellLedgerFile, layout: StateLayout): Either[StateNotWritable, Bytes] =
    codecs(layout).haskellLedgerFile.write(file).left.map(StateNotWritable(_))
