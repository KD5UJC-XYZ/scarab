// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{LedgerDecodeError, TxIn, Update}
import xyz.kd5ujc.scarab.ledger.state.{Babbage, Conway, EraTag}

/** The fields every era's body has (`EraTxBody`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs:223-295`): the ones of a body of any level
  * take the era's evidence of the level ([[Level]]), as the reference's lenses are polymorphic in
  * `l` (`Core.hs:245-265`); the fee is of a top-level body only (`feeTxBodyL`, `Core.hs:249`).
  */
trait EraTxBody[E <: EraTag]:
  /** The levels the era's bodies have: [[TopOnly]] or [[BothLevels]]. */
  type Level[L <: TxLevel]

  /** The top level, which every era has. */
  def top: Level[TxLevel.Top]

  /** The outputs' class (the superclass `EraTxOut era`). */
  def outputsOf: EraTxOut[E]

  def id[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Bytes
  def inputs[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Vector[TxIn]
  def outputs[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Vector[TxOut[E]]
  def fee(body: TxBody[TxLevel.Top, E]): BigInt

/** The protocol-parameter update of a body, up to Babbage (`ShelleyEraTxBody`, whose instances end
  * at Babbage: `AtMostEra "Babbage"`, `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxBody.hs:97`).
  */
trait ShelleyEraTxBody[E <: EraTag] extends EraTxBody[E]:
  def update(body: TxBody[TxLevel.Top, E]): Option[Update]

/** The treasury a Conway body states (`ConwayEraTxBody`,
  * `eras/conway/impl/src/Cardano/Ledger/Conway/TxBody.hs:626-629`).
  */
trait ConwayEraTxBody[E <: EraTag] extends EraTxBody[E]:
  def currentTreasury[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Option[BigInt]

/** Why bytes or a stored value are not a value of an era. */
enum EraTxError:
  case Decode(cause: LedgerDecodeError)

  /** A field the era does not have. */
  case NotInEra(field: EraField)

enum EraField:
  case Update, CurrentTreasury, MultiAsset, Datum, ReferenceScript, NegativeValue

/** The instances of each era, the one place that lists the eras having each body class. */
object EraTxBody:
  given babbage: ShelleyEraTxBody[Babbage] =
    xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTxBody.instance
  given conway: ConwayEraTxBody[Conway] = xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxBody.instance
