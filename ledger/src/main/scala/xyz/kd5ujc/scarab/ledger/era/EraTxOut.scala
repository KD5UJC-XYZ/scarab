// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Address
import xyz.kd5ujc.scarab.plutus.PlutusData
import xyz.kd5ujc.scarab.ledger.state.{Allegra, Babbage, Coin, Conway, Dijkstra, EraTag, Mary}

/** The fields every era's output has (`EraTxOut`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs:297`). An output of the previous era is
  * carried in by the era's own translation (the reference's `upgradeTxOut`), not by this class.
  */
trait EraTxOut[E <: EraTag]:
  /** The arithmetic of the era's value (`Val (Value era)`, a superclass of the reference's). */
  def valueOps: Val[Value[E]]

  /** `addrTxOutL`. */
  def address(out: TxOut[E]): Address

  /** `valueTxOutL`. */
  def value(out: TxOut[E]): Value[E]

  /** The output's bytes as they arrived. */
  def raw(out: TxOut[E]): Bytes

/** The datum hash of an output, from Alonzo on (`AlonzoEraTxOut`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxOut.hs:92-95`, cardano-ledger f649f975).
  */
trait AlonzoEraTxOut[E <: EraTag] extends EraTxOut[E]:
  /** `dataHashTxOutL`: the hash of a datum the output names by its hash. */
  def dataHash(out: TxOut[E]): Option[Bytes]

  /** `datumTxOutF`. */
  def datum(out: TxOut[E]): Datum

/** The inline datum and reference script of an output, from Babbage on (`BabbageEraTxOut`,
  * `eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs:126-131`, cardano-ledger f649f975), whose
  * class asks for the era's Plutus scripts too (`AlonzoEraScript era`): an instance names the era's
  * script instance, so a rule given the output class has the script class it asks for, and the era
  * has one instance of each.
  */
trait BabbageEraTxOut[E <: EraTag] extends AlonzoEraTxOut[E]:
  /** The era's scripts (the superclass `AlonzoEraScript era`). */
  def scripts: AlonzoEraScript[E]

  /** `dataTxOutF`: the inline datum's data (`getDataBabbageTxOut`, `Babbage/TxOut.hs:706-716`,
    * cardano-ledger f649f975, through `binaryDataToData`), read when it is asked for, as the
    * reference's strict `SJust` forces it; its failure, which only an inline datum taken unread
    * ([[BabbageTxOutRep.fromStored]]) can reach, is returned ([[BinaryData.binaryDataToData]]).
    */
  def data(out: TxOut[E]): Either[PlutusData.DecodeError, Option[Data]]

  /** `referenceScriptTxOutL`. */
  def referenceScript(out: TxOut[E]): Option[Script[E]]

/** The instances of each era, the one place that lists the eras having each output class. */
object EraTxOut:
  given allegra: EraTxOut[Allegra] = xyz.kd5ujc.scarab.ledger.era.allegra.AllegraTxOut.instance
  given mary: EraTxOut[Mary] = xyz.kd5ujc.scarab.ledger.era.mary.MaryTxOut.instance
  given babbage: BabbageEraTxOut[Babbage] =
    xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTxOut.instance
  given conway: BabbageEraTxOut[Conway] = xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxOut.instance
  given dijkstra: BabbageEraTxOut[Dijkstra] =
    xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTxOut.instance

/** The node's stored form of an output and a value of era `E`, which the flat failures carry
  * ([[xyz.kd5ujc.scarab.ledger.UtxoFailure]]): glue until each era's failures are its own types
  * (design §3.4), not a class of the reference. An era has an instance once its outputs have a
  * stored form.
  */
trait StoredTxOut[E <: EraTag]:
  def toStored(out: TxOut[E]): xyz.kd5ujc.scarab.ledger.TxOut
  def storedValue(value: Value[E]): xyz.kd5ujc.scarab.ledger.Value

  /** A stored output read as an output of era `E` (design §3.6), for the rules that read the UTxO
    * set the state keeps; it refuses what only a damaged store holds.
    */
  private[ledger] def fromStored(out: xyz.kd5ujc.scarab.ledger.TxOut): Either[EraTxError, TxOut[E]]

object StoredTxOut:
  given conway: StoredTxOut[Conway] with
    def toStored(out: TxOut[Conway]): xyz.kd5ujc.scarab.ledger.TxOut =
      xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxOut.toStored(out)
    private[ledger] def fromStored(
        out: xyz.kd5ujc.scarab.ledger.TxOut
    ): Either[EraTxError, TxOut[Conway]] =
      xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxOut.fromStored(out)
    def storedValue(value: Value[Conway]): xyz.kd5ujc.scarab.ledger.Value = value.stored

  given dijkstra: StoredTxOut[Dijkstra] with
    def toStored(out: TxOut[Dijkstra]): xyz.kd5ujc.scarab.ledger.TxOut =
      xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTxOut.toStored(out)
    private[ledger] def fromStored(
        out: xyz.kd5ujc.scarab.ledger.TxOut
    ): Either[EraTxError, TxOut[Dijkstra]] =
      xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTxOut.fromStored(out)
    def storedValue(value: Value[Dijkstra]): xyz.kd5ujc.scarab.ledger.Value = value.stored

/** The functions of Babbage's outputs (`eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs`,
  * cardano-ledger f649f975).
  */
object BabbageTxOuts:
  /** `getDataHashBabbageTxOut` (`Babbage/TxOut.hs:723-731`, cardano-ledger f649f975): the hash of a
    * datum the output names by its hash, not that of an inline datum.
    */
  def dataHash(datum: Datum): Option[Bytes] = datum match
    case Datum.DatumHash(dh)            => Some(dh)
    case Datum.NoDatum | Datum.Datum(_) => None

  /** `getDataBabbageTxOut` (`Babbage/TxOut.hs:706-716`, cardano-ledger f649f975): the inline
    * datum's data, through `binaryDataToData` ([[BinaryData.binaryDataToData]]).
    */
  def data(datum: Datum): Either[PlutusData.DecodeError, Option[Data]] = datum match
    case Datum.Datum(binaryData)            => BinaryData.binaryDataToData(binaryData).map(Some(_))
    case Datum.NoDatum | Datum.DatumHash(_) => Right(None)

  /** `babbageMinUTxOValue` (`Babbage/TxOut.hs:665-688`, cardano-ledger f649f975): the least
    * lovelace of an output of `sizedSize` bytes, `(160 + size) * coinsPerUTxOByte`. The reference
    * reaches it through `EraTxOut` 's `getMinCoinSizedTxOut` (`Core.hs:391-392`), which every era's
    * instance has; here it is a function, until the eras before Babbage have parameter records
    * their instances could read (#85).
    */
  def babbageMinUTxOValue[E <: EraTag, A](pp: PParams[E], sizedTxOut: Sized[A])(using
      BabbageEraPParams[E]
  ): Coin =
    val cpb = BabbageEraPParams.ppCoinsPerUTxOByteL[E].get(pp).unCoinPerByte.toBigInt
    Coin((ConstantOverhead + BigInt(sizedTxOut.size)) * cpb)

  /** `constantOverhead`, an approximation of the memory an entry of the UTxO set takes beside its
    * output (`Babbage/TxOut.hs:674-688`, cardano-ledger f649f975).
    */
  private val ConstantOverhead = BigInt(160)
