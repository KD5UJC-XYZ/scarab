// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.codec.BinaryError
import xyz.kd5ujc.scarab.ledger.ScriptKind as LiveScriptKind

/** The kinds of script as the sidecars of the snapshot versions before
  * [[xyz.kd5ujc.scarab.ledger.FormatVersion.V10]] name them, frozen: native and PlutusV1 to
  * PlutusV3. The live [[LiveScriptKind]] grew PlutusV4, the language of a Dijkstra reference script
  * (cardano-ledger 9df9f98c `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Scripts.hs:476-481`),
  * when V10 was changed in place before any deployment (#85, #54). That changes the shape of the
  * script's codec and with it the schema fingerprint of every sidecar that holds an output; the
  * readers of the previous versions take this codec instead, whose shape and bytes are those the
  * files were written with (the `CodecSuite` goldens of the previous sidecar schemas guard it), as
  * [[FrozenEra]] does for the eras.
  */
private[storage] object FrozenScriptKind:
  /** Named as the live type was, so the derived shape is `ScriptKind{Native|...|PlutusV3}`. */
  enum ScriptKind derives CanEqual:
    case Native, PlutusV1, PlutusV2, PlutusV3

  private val frozen: Codec[ScriptKind] = Codec.auto

  /** Every kind a previous-version file can name is a live kind. */
  def live(kind: ScriptKind): LiveScriptKind = kind match
    case ScriptKind.Native   => LiveScriptKind.Native
    case ScriptKind.PlutusV1 => LiveScriptKind.PlutusV1
    case ScriptKind.PlutusV2 => LiveScriptKind.PlutusV2
    case ScriptKind.PlutusV3 => LiveScriptKind.PlutusV3

  /** The live kind in the frozen schema: read totally into the live type. The writer is the
    * previous-version tests' and migrations' (the node writes only the current version); it writes
    * the variant index, which for the kinds the schema has is the derived encoding, and for one it
    * lacks (PlutusV4) an index that the reader refuses ([[BinaryError.InvalidVariant]]), so no file
    * of a previous version can be read as holding a kind it could not name.
    */
  val codec: Codec[LiveScriptKind] = new Codec[LiveScriptKind]:
    def write(out: Out, a: LiveScriptKind): Unit = out.varint(a.ordinal.toLong)
    def read(in: In): Either[BinaryError, LiveScriptKind] = frozen.read(in).map(live)
    def shape: String = frozen.shape
