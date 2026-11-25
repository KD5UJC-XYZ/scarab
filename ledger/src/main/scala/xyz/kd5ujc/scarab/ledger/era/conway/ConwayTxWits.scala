// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import scala.collection.immutable.{SortedMap, SortedSet}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node}
import xyz.kd5ujc.scarab.ledger.{
  ByteStrings,
  CborRead,
  DecodeField,
  DecoderVersion,
  DetachedRecords,
  LedgerDecodeError,
  Unsigned,
  Witnesses
}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraTxWits,
  AlonzoTxWitsRep,
  AsIx,
  Data,
  EraTxError,
  Plutus,
  Redeemers,
  TxDats,
  WitVKey
}
import xyz.kd5ujc.scarab.ledger.state.Conway
import xyz.kd5ujc.scarab.protocol.Era

/** Conway's witness set: `AlonzoTxWits ConwayEra` (`type TxWits ConwayEra = AlonzoTxWits
  * ConwayEra`, `eras/conway/impl/src/Cardano/Ledger/Conway/TxWits.hs:25`, cardano-ledger f649f975),
  * the representation Alonzo's witness set has ([[AlonzoTxWitsRep]]) with Conway's scripts, by
  * hash, and its redeemers keyed by Conway's purposes, a type of its own. Made only by
  * [[ConwayTxWits.decode]].
  */
opaque type ConwayTxWits = AlonzoTxWitsRep[ConwayScript, ConwayPlutusPurpose[AsIx]]

object ConwayTxWits
    extends AlonzoTxWitsRep.Fields[ConwayTxWits, ConwayScript, ConwayPlutusPurpose[AsIx]]:
  private val Word32Max = (BigInt(1) << 32) - 1

  given CanEqual[ConwayTxWits, ConwayTxWits] = CanEqual.derived

  /** A Conway witness set from its bytes. Conway's decoder (the flat decoder at Conway) reads it
    * and refuses what Conway refuses; its scripts are then read from the same bytes into Conway's
    * script types, each level of a native script with its own bytes (the flat witness set keeps a
    * native script's bytes whole, not by level), and must be the scripts the flat decoder read, by
    * hash.
    */
  def decode(raw: Bytes): Either[EraTxError, ConwayTxWits] =
    // a datum and a redeemer's data are read as the flat decoder read them, which refuses data
    // that does not decode with this error; kept decoded here
    def data(bytes: Bytes, site: DecodeField) =
      Data.decode(bytes).leftMap(e => EraTxError.Decode(LedgerDecodeError.PlutusData(site, e)))
    for
      flat <- Witnesses.decode(raw, Era.Conway).leftMap(EraTxError.Decode(_))
      scripts <- DetachedRecords
        .read(raw, Cbor.Limits.Unbounded, DetachedRecords.Shapes.witnesses(Era.Conway))(
          LedgerDecodeError.Syntax(_)
        )(scriptsOf)
        .leftMap(EraTxError.Decode(_))
      _ <- Either.cond(
        scripts._1.keySet == flat.scriptsByHash.keySet,
        (),
        EraTxError.ScriptsReadAgain
      )
      // the flat decoder read the tag as one of Conway's and the index as a `Word32`
      // (`Redeemer.purpose`), and refuses either otherwise with these errors
      redeemers <- flat.redeemers.traverse(r =>
        for
          index <- Unsigned
            .from[Unsigned.Width.W32](r.index)
            .toRight(
              EraTxError.Decode(
                LedgerDecodeError.NumberOutOfRange(DecodeField.RedeemerIndex, r.index, 0, Word32Max)
              )
            )
          purpose <- ConwayPlutusPurpose
            .asIx(r.tag, index)
            .toRight(
              EraTxError.Decode(LedgerDecodeError.UnknownTag(DecodeField.RedeemerTag, r.tag))
            )
          redeemer <- data(r.data, DecodeField.Redeemer)
        yield purpose -> (redeemer, r.exUnits)
      )
      datums <- flat.datums.traverse(data(_, DecodeField.Datums))
    yield AlonzoTxWitsRep(
      raw,
      SortedSet.from(flat.vkeys.map(WitVKey(_, _))),
      flat.bootstrap,
      scripts._1,
      scripts._2,
      TxDats(flat.datumsRaw, SortedMap.from(datums.map(d => d.hashData -> d))),
      Redeemers(flat.redeemersRaw, SortedMap.from(redeemers))
    )

  // The scripts of the witness set (keys 1, 3, 6 and 7), each read with its own bytes. Each list
  // was read by the flat decoder before, so its form is one it read.
  private def scriptsOf(
      root: Node
  ): Either[LedgerDecodeError, (SortedMap[Bytes, ConwayScript], Vector[Bytes])] =
    val version = DecoderVersion.of(Era.Conway)
    given ByteStrings = ByteStrings.of(version)
    def items(key: Int, site: DecodeField)(fields: Map[Int, Node]) =
      fields.get(key).traverse(CborRead.list(_, site, version)).map(_.orEmpty)
    def plutus(key: Int, fields: Map[Int, Node])(make: Bytes => ConwayPlutusScript) =
      items(key, DecodeField.PlutusScripts)(fields)
        .flatMap(_.traverse(CborRead.bytes(_, DecodeField.PlutusScript)))
        .map(_.map(b => ConwayScript.plutus(make(b))))
    for
      fields <- CborRead.fields(root, DecodeField.WitnessSet)
      native <- items(1, DecodeField.NativeScripts)(fields)
        .flatMap(_.traverse(ConwayTimelock.read(_)).map(_.map(ConwayScript.native)))
      v1 <- plutus(3, fields)(b => ConwayPlutusScript.V1(Plutus(b)))
      v2 <- plutus(6, fields)(b => ConwayPlutusScript.V2(Plutus(b)))
      v3 <- plutus(7, fields)(b => ConwayPlutusScript.V3(Plutus(b)))
    // of the scripts with one hash, which are one script, the first read, as the flat decoder keeps
    yield
      val arrived = (native ++ v1 ++ v2 ++ v3)
        .map(s => ConwayScript.instance.hashScript(s) -> s)
        .distinctBy(_._1)
      (SortedMap.from(arrived), arrived.map(_._1))

  /** `EraTxWits ConwayEra` and `AlonzoEraTxWits ConwayEra` (`Conway/TxWits.hs:24-43`,
    * cardano-ledger f649f975): Alonzo's lenses.
    */
  val instance: AlonzoEraTxWits[Conway] = AlonzoTxWitsRep.Instance[Conway](ConwayScript.instance)
