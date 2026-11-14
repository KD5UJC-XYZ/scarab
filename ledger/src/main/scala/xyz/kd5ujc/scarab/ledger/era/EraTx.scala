// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Address, Datum, LedgerDecodeError, Script, TxIn, Update, Value}
import xyz.kd5ujc.scarab.ledger.state.{Babbage, Conway, EraTag}

/** The transaction output of era `E`, as the reference's `TxOut era` type family (cardano-ledger
  * f649f975 `libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs:297-318`): one nominal type per
  * era, an opaque type over the representation the reference shares.
  */
type TxOut[E <: EraTag] = E match
  case Babbage => babbage.BabbageTxOut
  case Conway  => conway.ConwayTxOut

/** The transaction body of era `E` (`TxBody era`, `Core.hs:223-241`). */
type TxBody[E <: EraTag] = E match
  case Babbage => babbage.BabbageTxBody
  case Conway  => conway.ConwayTxBody

/** The fields every era's output has (`EraTxOut`, `Core.hs:297-318`). */
trait EraTxOut[E <: EraTag]:
  def address(out: TxOut[E]): Address
  def value(out: TxOut[E]): Value

  /** The output's bytes as they arrived. */
  def raw(out: TxOut[E]): Bytes

/** The datum and reference script of an output, from Babbage on (`BabbageEraTxOut`,
  * `eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs:126`).
  */
trait BabbageEraTxOut[E <: EraTag] extends EraTxOut[E]:
  def datum(out: TxOut[E]): Datum
  def referenceScript(out: TxOut[E]): Option[Script]

/** The fields every era's body has (`EraTxBody`, `Core.hs:223-241`). */
trait EraTxBody[E <: EraTag]:
  def id(body: TxBody[E]): Bytes
  def inputs(body: TxBody[E]): Vector[TxIn]
  def outputs(body: TxBody[E]): Vector[TxOut[E]]
  def fee(body: TxBody[E]): BigInt

/** The protocol-parameter update of a body, up to Babbage (`ShelleyEraTxBody`, whose instances end
  * at Babbage: `AtMostEra "Babbage"`, `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxBody.hs:97`).
  */
trait ShelleyEraTxBody[E <: EraTag] extends EraTxBody[E]:
  def update(body: TxBody[E]): Option[Update]

/** The treasury a Conway body states (`ConwayEraTxBody`,
  * `eras/conway/impl/src/Cardano/Ledger/Conway/TxBody.hs:626-629`).
  */
trait ConwayEraTxBody[E <: EraTag] extends EraTxBody[E]:
  def currentTreasury(body: TxBody[E]): Option[BigInt]

/** Why bytes or a stored value are not a value of an era. */
enum EraTxError:
  case Decode(cause: LedgerDecodeError)

  /** A field the era does not have. */
  case NotInEra(field: EraField)

enum EraField:
  case Update, CurrentTreasury

/** The instances of each era, one place per class that lists the eras having it. */
object EraTxOut:
  given babbage: BabbageEraTxOut[Babbage] =
    xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTxOut.instance
  given conway: BabbageEraTxOut[Conway] = xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxOut.instance

object EraTxBody:
  given babbage: ShelleyEraTxBody[Babbage] =
    xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTxBody.instance
  given conway: ConwayEraTxBody[Conway] = xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxBody.instance
