// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{Address, CborRead, DecodeField, Datum, LedgerDecodeError}
import xyz.kd5ujc.scarab.ledger.dijkstra.V12
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraScript,
  BabbageEraTxOut,
  BabbageTxOutRep,
  EraTxError,
  MultiAssetValue,
  Val
}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxOut
import xyz.kd5ujc.scarab.ledger.state.Dijkstra

/** A Dijkstra output: `BabbageTxOut DijkstraEra`, the representation Babbage's and Conway's have
  * with Dijkstra's script (`instance EraTxOut DijkstraEra`, `type TxOut DijkstraEra = BabbageTxOut
  * DijkstraEra`, `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxOut.hs:29-30`, cardano-ledger
  * 9df9f98c), a type of its own. Made only by [[DijkstraTxOut.decode]] and
  * [[DijkstraTxOut.translate]].
  *
  * The store's form of a Dijkstra output, and so `fromStored` and `toStored`, waits for the store's
  * Dijkstra form (#54, tracked on #85): the flat output keeps a native reference script as a tree
  * of the time-lock language, which has no guard.
  */
opaque type DijkstraTxOut = BabbageTxOutRep[DijkstraScript]

object DijkstraTxOut:
  /** A Dijkstra output read as the Leios build's block decoder reads it
    * ([[xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxOut.decode]]: `decodeBabbageTxOut` at decoder
    * version 12, `eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs:555-676`, cardano-ledger
    * 9df9f98c), which decides what is refused; its value's size and its reference script, in
    * Dijkstra's script type, are then read from the same item.
    */
  def decode(n: Node): Either[EraTxError, DijkstraTxOut] = read(n).leftMap(EraTxError.Decode(_))

  private[dijkstra] def read(n: Node): Either[LedgerDecodeError, DijkstraTxOut] =
    for
      flat <- xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxOut.decode(n)
      parts <- items(n)
      (value, scriptItem) = parts
      script <- scriptItem.traverse(s =>
        V12.nested(s, DecodeField.ReferenceScript).flatMap((_, item) => DijkstraScript.read(item))
      )
    yield BabbageTxOutRep(
      flat.raw,
      flat.address,
      MultiAssetValue.of(xyz.kd5ujc.scarab.ledger.Value(flat.value.coin, flat.value.assets)),
      value.original.size,
      datum(flat.datum),
      script
    )

  /** The value and the reference script of an output the block decoder has read: keys 1 and 3 of
    * the map form, the second item of the list form, which has no script.
    */
  private def items(n: Node): Either[LedgerDecodeError, (Node, Option[Node])] =
    def key(k: Int)(entry: (Node, Node)) = entry._1.value == CborValue.UInt(BigInt(k))
    n.value match
      case CborValue.Map(_) =>
        CborRead
          .map(n, DecodeField.Output)
          .flatMap(entries =>
            entries
              .find(key(1))
              .map(_._2)
              .toRight(LedgerDecodeError.MissingField(DecodeField.Output, DecodeField.OutputValue))
              .map(v => (v, entries.find(key(3)).map(_._2)))
          )
      case _ =>
        V12
          .list(n, DecodeField.Output)
          .flatMap(
            _.lift(1)
              .toRight(LedgerDecodeError.MissingField(DecodeField.Output, DecodeField.OutputValue))
              .map((_, None))
          )

  private def datum(d: xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraDatum): Datum = d match
    case xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraDatum.NoDatum   => Datum.NoDatum
    case xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraDatum.Hash(h)   => Datum.Hash(h)
    case xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraDatum.Inline(r) => Datum.Inline(r)

  /** A Conway output carried into Dijkstra (`upgradeTxOut = upgradeBabbageTxOut`, `TxOut.hs:33`,
    * cardano-ledger 9df9f98c; applied to the UTxO by `Dijkstra/Translation.hs:225-227` and to the
    * ledger tables by ouroboros-consensus 2416493b `Cardano/CanHardFork.hs:757-765`): the same
    * address, value and datum, its reference script carried into Dijkstra (`upgradeScript`). A
    * native reference script is encoded anew ([[DijkstraNativeScript.translate]]), so the output's
    * bytes are those of the upgraded output, written as the translation of the ledger state writes
    * them (`Translate.upgradeTxOut`: the script's new encoding,
    * [[xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraScript.upgradeTimelock]], in the output's place of
    * it, [[xyz.kd5ujc.scarab.ledger.mempack.TxOutMemPack.withNativeReference]]), never the Conway
    * output's. They are read back at Dijkstra's version; Dijkstra reads every Conway output, so a
    * refusal is a defect of the encoder.
    */
  def translate(out: ConwayTxOut): Either[EraTxError, DijkstraTxOut] =
    val stored = ConwayTxOut.toStored(out)
    val upgraded = stored.scriptRef.flatMap(_.native).fold(stored) { script =>
      xyz.kd5ujc.scarab.ledger.mempack.TxOutMemPack.withNativeReference(
        stored,
        xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraScript.upgradeTimelock(script),
        script
      )
    }
    for
      read <- CborRead
        .decode(upgraded.raw, V12.nestedLimits)
        .flatMap(read)
        .leftMap(EraTxError.TranslationReadBack(_))
      // the script is `upgradeScript`'s, as `upgradeBabbageTxOut` keeps it (cardano-ledger
      // 9df9f98c `eras/conway/impl/src/Cardano/Ledger/Conway/TxOut.hs:68-81`): a native one's
      // sub-scripts memoize their encodings, which a script read from the bytes would not
      script <- out.rep.scriptRef.traverse(DijkstraScript.translate)
    yield read.copy(scriptRef = script)

  extension (out: DijkstraTxOut)
    /** The shared representation, read only. */
    def rep: BabbageTxOutRep[DijkstraScript] = out

  given CanEqual[DijkstraTxOut, DijkstraTxOut] = CanEqual.derived

  /** `EraTxOut`, `AlonzoEraTxOut` and `BabbageEraTxOut DijkstraEra` (`TxOut.hs:29-58`,
    * cardano-ledger 9df9f98c): Babbage's readers.
    */
  val instance: BabbageEraTxOut[Dijkstra] = new BabbageEraTxOut[Dijkstra]:
    override def scripts: AlonzoEraScript[Dijkstra] = DijkstraScript.instance
    override def valueOps: Val[MultiAssetValue] = summon[Val[MultiAssetValue]]
    override def address(out: DijkstraTxOut): Address = out.address
    override def value(out: DijkstraTxOut): MultiAssetValue = out.value
    override def raw(out: DijkstraTxOut): Bytes = out.raw
    override def dataHash(out: DijkstraTxOut): Option[Bytes] = out.datum match
      case Datum.Hash(h)                   => Some(h)
      case Datum.NoDatum | Datum.Inline(_) => None
    override def datum(out: DijkstraTxOut): Datum = out.datum
    override def data(out: DijkstraTxOut): Option[Bytes] = out.datum match
      case Datum.Inline(d)               => Some(d)
      case Datum.NoDatum | Datum.Hash(_) => None
    override def referenceScript(out: DijkstraTxOut): Option[DijkstraScript] = out.scriptRef
