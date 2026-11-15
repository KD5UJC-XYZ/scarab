// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Address, Datum}
import xyz.kd5ujc.scarab.ledger.state.{Allegra, Babbage, Conway, Dijkstra, EraTag, Mary}

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

  /** `dataTxOutL`: the inline datum's bytes. */
  def data(out: TxOut[E]): Option[Bytes]

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
