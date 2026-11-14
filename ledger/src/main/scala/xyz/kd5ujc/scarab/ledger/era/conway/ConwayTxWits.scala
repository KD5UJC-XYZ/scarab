// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import scala.collection.immutable.{SortedMap, SortedSet}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  BootstrapWitness,
  ByteStrings,
  CborRead,
  DecodeField,
  DecoderVersion,
  DetachedRecords,
  LedgerDecodeError,
  Unsigned,
  Witnesses
}
import xyz.kd5ujc.scarab.ledger.era.{AsIx, EraTxError, Plutus, WitVKey}
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.Era

/** Conway's witness set: `AlonzoTxWits ConwayEra` (`data AlonzoTxWitsRaw`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs:249-255`, cardano-ledger f649f975), with
  * the bytes it arrived as and those of its datums and redeemers, which the script integrity hash
  * is over. Its scripts are Conway scripts, by hash, and its redeemers are keyed by Conway's
  * purposes. Made only by [[ConwayTxWits.decode]].
  *
  * @param bootstrap
  *   the bootstrap witnesses, one per address root ([[BootstrapWitness.set]])
  * @param datums
  *   the datums by hash, each the bytes of its data as they arrived
  * @param redeemers
  *   per purpose, the redeemer's data as it arrived and its units
  */
final case class ConwayTxWits private (
    raw: Bytes,
    vkeys: SortedSet[WitVKey],
    bootstrap: Vector[BootstrapWitness],
    scripts: SortedMap[Bytes, ConwayScript],
    datums: SortedMap[Bytes, Bytes],
    redeemers: SortedMap[ConwayPlutusPurpose[AsIx], (Bytes, ExUnits)],
    datumsRaw: Option[Bytes],
    redeemersRaw: Option[Bytes]
)

object ConwayTxWits:
  private val Word32Max = (BigInt(1) << 32) - 1

  given CanEqual[ConwayTxWits, ConwayTxWits] = CanEqual.derived

  /** A Conway witness set from its bytes. Conway's decoder (the flat decoder at Conway) reads it
    * and refuses what Conway refuses; its scripts are then read from the same bytes into Conway's
    * script types, each with its own bytes (the flat witness set keeps no bytes of a native
    * script), and must be the scripts the flat decoder read, by hash.
    */
  def decode(raw: Bytes): Either[EraTxError, ConwayTxWits] =
    for
      flat <- Witnesses.decode(raw, Era.Conway).leftMap(EraTxError.Decode(_))
      scripts <- DetachedRecords
        .read(raw, Cbor.Limits.Unbounded, DetachedRecords.Shapes.witnesses(Era.Conway))(
          LedgerDecodeError.Syntax(_)
        )(scriptsOf)
        .leftMap(EraTxError.Decode(_))
      _ <- Either.cond(
        scripts.keySet == flat.scriptsByHash.keySet,
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
        yield purpose -> (r.data, r.exUnits)
      )
    yield ConwayTxWits(
      raw,
      SortedSet.from(flat.vkeys.map(WitVKey(_, _))),
      flat.bootstrap,
      scripts,
      SortedMap.from(flat.datums.map(d => Blake2b.hash256.hash(d) -> d)),
      SortedMap.from(redeemers),
      flat.datumsRaw,
      flat.redeemersRaw
    )

  // The scripts of the witness set (keys 1, 3, 6 and 7), each read with its own bytes. Each list
  // was read by the flat decoder before, so its form is one it read.
  private def scriptsOf(root: Node): Either[LedgerDecodeError, SortedMap[Bytes, ConwayScript]] =
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
    yield SortedMap.from(
      (native ++ v1 ++ v2 ++ v3).map(s => ConwayScript.instance.hashScript(s) -> s)
    )

  val instance
      : xyz.kd5ujc.scarab.ledger.era.AlonzoEraTxWits[xyz.kd5ujc.scarab.ledger.state.Conway] =
    new xyz.kd5ujc.scarab.ledger.era.AlonzoEraTxWits[xyz.kd5ujc.scarab.ledger.state.Conway]:
      override def scriptsOf
          : xyz.kd5ujc.scarab.ledger.era.AlonzoEraScript[xyz.kd5ujc.scarab.ledger.state.Conway] =
        ConwayScript.instance
      override def addrTxWits(wits: ConwayTxWits): SortedSet[WitVKey] = wits.vkeys
      override def bootAddrTxWits(wits: ConwayTxWits): Vector[BootstrapWitness] = wits.bootstrap
      override def scriptTxWits(wits: ConwayTxWits): SortedMap[Bytes, ConwayScript] = wits.scripts
      override def datsTxWits(wits: ConwayTxWits): SortedMap[Bytes, Bytes] = wits.datums
      override def rdmrsTxWits(
          wits: ConwayTxWits
      ): SortedMap[ConwayPlutusPurpose[AsIx], (Bytes, ExUnits)] = wits.redeemers
