// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.codec.BinaryError
import xyz.kd5ujc.scarab.consensus.state.{PBftState, PraosState, TPraosState}
import xyz.kd5ujc.scarab.consensus.state.ChainDepState as LiveChainDepState

/** The chain-dependent states as the sidecars of the snapshot versions before
  * [[xyz.kd5ujc.scarab.ledger.FormatVersion.V10]] name them, frozen: PBFT, TPraos and Praos. The
  * live [[LiveChainDepState]] grew Praos2, the protocol of Dijkstra in the Leios build
  * (ouroboros-consensus 2416493b `Protocol/Praos2.hs:117`), when V10 was changed in place before
  * any deployment (#826). That changes the shape of the state's codec and with it the schema
  * fingerprint of every sidecar that holds one; the readers of the previous versions take this
  * codec instead, whose shape and bytes are those the files were written with (the `CodecSuite`
  * goldens of the previous sidecar schemas guard it), as [[FrozenScriptKind]] does for the kinds of
  * script.
  */
private[storage] object FrozenChainDepState:
  import NodeCodecs.given

  /** Named as the live type was, so the derived shape is
    * `ChainDepState{PBft(...)|TPraos(...)|Praos(...)}`.
    */
  enum ChainDepState:
    case PBft(state: PBftState)
    case TPraos(state: TPraosState)
    case Praos(state: PraosState)

  private val frozen: Codec[ChainDepState] = Codec.auto

  /** Every state a previous-version file can hold is a live state. */
  def live(state: ChainDepState): LiveChainDepState = state match
    case ChainDepState.PBft(s)   => LiveChainDepState.PBft(s)
    case ChainDepState.TPraos(s) => LiveChainDepState.TPraos(s)
    case ChainDepState.Praos(s)  => LiveChainDepState.Praos(s)

  /** The live state in the frozen schema: read totally into the live type. The writer is the
    * previous-version tests' and migrations' (the node writes only the current version); it writes
    * the live encoding, which for the protocols the schema has is the derived encoding (the same
    * variant index and fields), and for one it lacks (Praos2) a variant index that the reader
    * refuses ([[BinaryError.InvalidVariant]]), so no file of a previous version can be read as
    * holding a state it could not name.
    */
  val codec: Codec[LiveChainDepState] = new Codec[LiveChainDepState]:
    def write(out: Out, a: LiveChainDepState): Unit = NodeCodecs.chainDepState.write(out, a)
    def read(in: In): Either[BinaryError, LiveChainDepState] = frozen.read(in).map(live)
    def shape: String = frozen.shape
