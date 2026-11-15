// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  GovActionId,
  LedgerDecodeError,
  RewardAccount,
  TxIn,
  Update,
  Voter,
  VotingProcedure
}
import xyz.kd5ujc.scarab.ledger.state.{Coin, Conway, EraTag, VocabError}

/** The fields every era's body has (`EraTxBody`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs:223-295`): the ones of a body of any level
  * take the era's evidence of the level ([[Level]]), as the reference's lenses are polymorphic in
  * `l` (`Core.hs:245-265`, cardano-ledger f649f975); the fee is of a top-level body only
  * (`feeTxBodyL`, `Core.hs:249`, cardano-ledger f649f975). The class's superclasses of other
  * families (`EraTxOut era`, `EraTxCert era`) are members ([[outputsOf]], [[certsOf]]), so an era
  * has one instance of each class.
  */
trait EraTxBody[E <: EraTag]:
  /** The levels the era's bodies have: [[TopOnly]] or [[BothLevels]]. */
  type Level[L <: TxLevel]

  /** The top level, which every era has. */
  def top: Level[TxLevel.Top]

  /** The outputs' class (the superclass `EraTxOut era`). */
  def outputsOf: EraTxOut[E]

  /** The certificates' class (the superclass `EraTxCert era`). */
  def certsOf: EraTxCert[E]

  def id[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Bytes
  def inputs[L <: TxLevel](body: TxBody[L, E])(using Level[L]): SortedSet[TxIn]
  def outputs[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Vector[TxOut[E]]
  def fee(body: TxBody[TxLevel.Top, E]): Coin
  def withdrawals[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Map[RewardAccount, Coin]
  def auxDataHash[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Option[Bytes]

  /** `certsTxBodyL`: the certificates in order, a sequence in the class (`Core.hs:265`,
    * cardano-ledger f649f975) whatever an era's body keeps them as (Conway's an `OSet`,
    * `Conway/TxBody.hs:351-353`, cardano-ledger f649f975).
    */
  def certs[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Vector[TxCert[E]]

  /** `allInputsTxBodyF` (`Core.hs:260-263`, cardano-ledger f649f975): every input of a top-level
    * body the UTxO set must hold, whether or not the transaction spends it.
    */
  def allInputs(body: TxBody[TxLevel.Top, E]): SortedSet[TxIn]

/** The protocol-parameter update of a body, up to Babbage (`ShelleyEraTxBody`, whose instances end
  * at Babbage: `AtMostEra "Babbage"`, `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxBody.hs:97`,
  * cardano-ledger f649f975).
  */
trait ShelleyEraTxBody[E <: EraTag] extends EraTxBody[E]:
  /** The certificates' class (the superclass `ShelleyEraTxCert era`, `Shelley/TxBody.hs:97`,
    * cardano-ledger f649f975).
    */
  override def certsOf: ShelleyEraTxCert[E]

  def update(body: TxBody[TxLevel.Top, E]): Option[Update]

/** The slots a transaction is valid in, from Allegra (`data ValidityInterval`,
  * `eras/allegra/impl/src/Cardano/Ledger/Allegra/Scripts.hs:130-134`, cardano-ledger f649f975):
  * from `invalidBefore`, and before `invalidHereafter`.
  */
final case class ValidityInterval(invalidBefore: Option[BigInt], invalidHereafter: Option[BigInt])

/** The validity interval of a body, from Allegra (`AllegraEraTxBody`,
  * `eras/allegra/impl/src/Cardano/Ledger/Allegra/TxBody.hs:89-90`, cardano-ledger f649f975).
  */
trait AllegraEraTxBody[E <: EraTag] extends EraTxBody[E]:
  def vldt[L <: TxLevel](body: TxBody[L, E])(using Level[L]): ValidityInterval

/** What a body mints, from Mary (`MaryEraTxBody`,
  * `eras/mary/impl/src/Cardano/Ledger/Mary/TxBody.hs:70-81`, cardano-ledger f649f975).
  */
trait MaryEraTxBody[E <: EraTag] extends AllegraEraTxBody[E]:
  /** `mintTxBodyL`: per policy, per asset name, the quantity minted (negative when burnt). */
  def mint[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Map[Bytes, Map[Bytes, BigInt]]

  /** `mintedTxBodyF`: the policies a body mints or burns under. */
  final def minted[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Set[Bytes] =
    mint(body).keySet

/** The script fields of a body, from Alonzo (`AlonzoEraTxBody`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxBody.hs:127-154`, cardano-ledger f649f975). Its
  * redeemer pointers come with the era's purposes.
  */
trait AlonzoEraTxBody[E <: EraTag] extends MaryEraTxBody[E]:
  override def outputsOf: AlonzoEraTxOut[E]

  /** `collateralInputsTxBodyL`, of a top-level body only. */
  def collateralInputs(body: TxBody[TxLevel.Top, E]): SortedSet[TxIn]

  /** `reqSignerHashesTxBodyL` (`AtMostEra "Conway"`). */
  def reqSignerHashes[L <: TxLevel](body: TxBody[L, E])(using Level[L]): SortedSet[Bytes]
  def scriptIntegrityHash[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Option[Bytes]
  def networkId[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Option[Network]

/** The reference inputs and the collateral return, from Babbage (`BabbageEraTxBody`,
  * `eras/babbage/impl/src/Cardano/Ledger/Babbage/TxBody.hs:110-121`, cardano-ledger f649f975).
  */
trait BabbageEraTxBody[E <: EraTag] extends AlonzoEraTxBody[E]:
  override def outputsOf: BabbageEraTxOut[E]

  def referenceInputs[L <: TxLevel](body: TxBody[L, E])(using Level[L]): SortedSet[TxIn]

  /** `totalCollateralTxBodyL`, of a top-level body only. */
  def totalCollateral(body: TxBody[TxLevel.Top, E]): Option[Coin]

  /** `sizedOutputsTxBodyL`: the outputs, each with the size of the encoding it was read from. */
  def sizedOutputs[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Vector[Sized[TxOut[E]]]

  /** `sizedCollateralReturnTxBodyL`, of a top-level body only. */
  def sizedCollateralReturn(body: TxBody[TxLevel.Top, E]): Option[Sized[TxOut[E]]]

  /** `collateralReturnTxBodyL`, of a top-level body only. */
  final def collateralReturn(body: TxBody[TxLevel.Top, E]): Option[TxOut[E]] =
    sizedCollateralReturn(body).map(_.value)

  /** `allSizedOutputsTxBodyF`, of a body of any level (`Babbage/TxBody.hs:121`, cardano-ledger
    * f649f975): what each era's instance says for each of its levels.
    */
  def allSizedOutputs[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Vector[Sized[TxOut[E]]]

  /** `allSizedOutputsBabbageTxBodyF` (`Babbage/TxBody.hs:241-249`, cardano-ledger f649f975): the
    * outputs and then the collateral return of a top-level body, which an era's instance gives at
    * the top level.
    */
  /** `babbageAllInputsTxBodyF` (`Babbage/TxBody.hs:231-239`, cardano-ledger f649f975): the spent,
    * collateral and reference inputs, which an era's instance gives from Babbage.
    */
  protected final def allInputsBabbage(body: TxBody[TxLevel.Top, E]): SortedSet[TxIn] =
    inputs(body)(using top) ++ collateralInputs(body) ++ referenceInputs(body)(using top)

  protected final def allSizedOutputsBabbage(
      body: TxBody[TxLevel.Top, E]
  ): Vector[Sized[TxOut[E]]] =
    sizedOutputs(body)(using top) ++ sizedCollateralReturn(body)

/** The governance fields of a body, from Conway (`ConwayEraTxBody`,
  * `eras/conway/impl/src/Cardano/Ledger/Conway/TxBody.hs:626-641`, cardano-ledger f649f975), whose
  * certificates are Conway's (the superclass `ConwayEraTxCert era`) and whose scripts have Conway's
  * purposes (`ConwayEraScript era`). Its superclass `ConwayEraPParams era` comes with the era's
  * protocol parameters.
  */
trait ConwayEraTxBody[E <: EraTag] extends BabbageEraTxBody[E]:
  override def certsOf: ConwayEraTxCert[E]

  /** The scripts' class (the superclass `ConwayEraScript era`). */
  def scriptsOf: ConwayEraScript[E]

  def currentTreasuryValue[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Option[Coin]
  def votingProcedures[L <: TxLevel](body: TxBody[L, E])(using
      Level[L]
  ): SortedMap[Voter, SortedMap[GovActionId, VotingProcedure]]
  def proposalProcedures[L <: TxLevel](body: TxBody[L, E])(using Level[L]): OSet[ProposalProcedure]
  def treasuryDonation[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Coin

/** Why bytes or a stored value are not a value of an era. */
enum EraTxError:
  case Decode(cause: LedgerDecodeError)

  /** A field the era does not have. */
  case NotInEra(field: EraField)

  /** A delegation to neither a pool nor a DRep, which no decoder reads. */
  case NoDelegatee

  /** A field the decoder read that is not a value of its reference type (a key hash of the wrong
    * length, an epoch out of `Word64`), which the decoder's own bounds rule out.
    */
  case Vocab(cause: VocabError)

  /** The scripts of a witness set read again from its bytes are not the ones its decoder read. */
  case ScriptsReadAgain

  /** A stored output whose kept fields (its reference script's hash and tree) are not what its
    * bytes read as: the store holds what no decoder wrote.
    */
  case StoredOutputDiffers

  /** The encoding a translation into the next era wrote is refused when read again at that era's
    * version, though the next era reads every value of the previous one: a defect of the encoder,
    * not a refusal of the value translated.
    */
  case TranslationReadBack(cause: LedgerDecodeError)

enum EraField:
  case Update, CurrentTreasury, MultiAsset, Datum, ReferenceScript, NegativeValue,
    GenesisDelegation, InstantaneousRewards

/** The instances of each era, the one place that lists the eras having each body class. */
object EraTxBody:
  given conway: (ConwayEraTxBody[Conway] { type Level[L <: TxLevel] = TopOnly[L] }) =
    xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxBody.instance
