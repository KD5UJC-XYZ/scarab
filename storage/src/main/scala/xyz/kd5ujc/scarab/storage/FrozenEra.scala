// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.codec.BinaryError
import xyz.kd5ujc.scarab.protocol.Era as LiveEra

/** The eras as the sidecars of the snapshot versions before
  * [[xyz.kd5ujc.scarab.ledger.FormatVersion.V9]] name them, frozen: Byron to Conway. The live
  * [[LiveEra]] grew Dijkstra at V9, which changes the shape of its derived codec and with it the
  * schema fingerprint of every sidecar that holds an era; the readers of the previous versions take
  * this codec instead, whose shape and bytes are those the files were written with (the
  * `CodecSuite` goldens of the previous sidecar schemas guard it).
  */
private[storage] object FrozenEra:
  /** Named as the live type was, so the derived shape is `Era{Byron|...|Conway}`. */
  enum Era derives CanEqual:
    case Byron, Shelley, Allegra, Mary, Alonzo, Babbage, Conway

  private val frozen: Codec[Era] = Codec.auto

  /** Every era a previous-version file can name is a live era. */
  def live(era: Era): LiveEra = era match
    case Era.Byron   => LiveEra.Byron
    case Era.Shelley => LiveEra.Shelley
    case Era.Allegra => LiveEra.Allegra
    case Era.Mary    => LiveEra.Mary
    case Era.Alonzo  => LiveEra.Alonzo
    case Era.Babbage => LiveEra.Babbage
    case Era.Conway  => LiveEra.Conway

  /** The live era in the frozen schema: read totally into the live type. The writer is the
    * previous-version tests' (the node writes only the current version); it writes the variant
    * index, which for the eras the schema has is the derived encoding, and for one it lacks
    * (Dijkstra) an index that the reader refuses ([[BinaryError.InvalidVariant]]), so no file of a
    * previous version can be read as holding an era it could not name.
    */
  val codec: Codec[LiveEra] = new Codec[LiveEra]:
    def write(out: Out, a: LiveEra): Unit = out.varint(a.ordinal.toLong)
    def read(in: In): Either[BinaryError, LiveEra] = frozen.read(in).map(live)
    def shape: String = frozen.shape
