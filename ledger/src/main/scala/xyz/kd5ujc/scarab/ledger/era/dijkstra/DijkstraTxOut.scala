// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  CborRead,
  DecodeField,
  Datum,
  LedgerDecodeError,
  ScriptKind
}
import xyz.kd5ujc.scarab.ledger.dijkstra.V12
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoScriptRep,
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
  * 9df9f98c), a type of its own. Made only by [[DijkstraTxOut.decode]], [[DijkstraTxOut.translate]]
  * and [[DijkstraTxOut.fromStored]].
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
    * ledger tables by ouroboros-consensus 2416493b `Cardano/CanHardFork.hs:757-765`): the decoded
    * address, value and datum carried as they are, and the reference script carried into Dijkstra
    * (`upgradeScript`, [[DijkstraScript.translate]]), as `upgradeBabbageTxOut` carries each field
    * of the compact output (`eras/conway/impl/src/Cardano/Ledger/Conway/TxOut.hs:75-81`). Nothing
    * is read again, so an output an earlier era read leniently (a pointer out of range, normalised
    * to `Ptr 0 0 0`) is carried as it was read.
    *
    * A native reference script is encoded anew, so the output's bytes are those of the upgraded
    * output, written as the translation of the ledger state writes them (`Translate.upgradeTxOut`:
    * the script's new encoding,
    * [[xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraScript.upgradeTimelock]], in the output's place of
    * it, [[xyz.kd5ujc.scarab.ledger.mempack.TxOutMemPack.withNativeReference]]), never the Conway
    * output's. An output with no native reference script keeps the bytes the store keeps for it, as
    * the translation of the ledger state does and as
    * [[xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxOut.translate]] keeps Babbage's: the reference
    * holds no bytes of an output it has upgraded, and writes it in its own form where it writes one
    * (#977).
    */
  def translate(out: ConwayTxOut): Either[EraTxError, DijkstraTxOut] =
    val r = out.rep
    val stored = ConwayTxOut.toStored(out)
    val raw = stored.scriptRef.flatMap(_.native).fold(r.raw) { native =>
      xyz.kd5ujc.scarab.ledger.mempack.TxOutMemPack
        .withNativeReference(
          stored,
          xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraScript.upgradeTimelock(native),
          native
        )
        .raw
    }
    r.scriptRef
      .traverse(DijkstraScript.translate)
      .map(script => BabbageTxOutRep(raw, r.address, r.value, r.valueSize, r.datum, script))

  /** The stored output read as a Dijkstra output: its reference script read again from the output's
    * bytes as a Dijkstra script ([[DijkstraScript.read]]), as Conway's and Babbage's are read
    * (`BabbageTxOutRep.fromStored`), so a native script with a guard, which the flat output keeps
    * without a tree, is read whole.
    */
  private[ledger] def fromStored(
      out: xyz.kd5ujc.scarab.ledger.TxOut
  ): Either[EraTxError, DijkstraTxOut] =
    BabbageTxOutRep
      .fromStored(out)(n => DijkstraScript.read(n))
      .leftMap(EraTxError.Decode(_))
      // the output written back is the one read, so `toStored` is its inverse
      .flatMap(o => Either.cond(toStored(o) == out, o, EraTxError.StoredOutputDiffers))

  /** The output in the form the store keeps, the inverse of [[fromStored]] (which refuses a stored
    * output this does not give back): its bytes, fields and the value's size as they arrived, and
    * its reference script as the flat output keeps one, by its kind and hash, with the time-lock
    * tree of a native script that holds no guard ([[DijkstraNativeScript.timelock]]) and none for
    * one that does, and without a Plutus script's bytes; the store reads the script again from the
    * output's bytes. The flat output keeps a script so, rather than in a new stored form, by the
    * owner's decision of 2026-11-16 (#54): the reference keeps a Dijkstra native script as its
    * bytes (`MemoBytes`), which the output's bytes hold.
    */
  private[ledger] def toStored(out: DijkstraTxOut): xyz.kd5ujc.scarab.ledger.TxOut =
    xyz.kd5ujc.scarab.ledger.TxOut(
      out.raw,
      out.address,
      out.value.stored,
      out.datum,
      out.scriptRef.map(storedScript),
      out.valueSize
    )

  private def storedScript(s: DijkstraScript): xyz.kd5ujc.scarab.ledger.Script =
    val hash = DijkstraScript.instance.hashScript(s)
    s.rep match
      case AlonzoScriptRep.NativeScript(n) =>
        xyz.kd5ujc.scarab.ledger.Script(ScriptKind.Native, hash, n.timelock)
      case AlonzoScriptRep.PlutusScript(p) =>
        xyz.kd5ujc.scarab.ledger.Script(storedKind(p), hash, None)

  /** The stored kind of a Plutus script of Dijkstra's languages, PlutusV1 to PlutusV4. */
  private def storedKind(p: DijkstraPlutusScript): ScriptKind = p match
    case DijkstraPlutusScript.V1(_) => ScriptKind.PlutusV1
    case DijkstraPlutusScript.V2(_) => ScriptKind.PlutusV2
    case DijkstraPlutusScript.V3(_) => ScriptKind.PlutusV3
    case DijkstraPlutusScript.V4(_) => ScriptKind.PlutusV4

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
