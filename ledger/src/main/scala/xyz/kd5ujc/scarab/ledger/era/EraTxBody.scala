// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{LedgerDecodeError, RewardAccount, TxIn}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.AccountBalanceInterval
import xyz.kd5ujc.scarab.ledger.state.{
  Alonzo,
  Babbage,
  Coin,
  Conway,
  Credential,
  Dijkstra,
  EraTag,
  Guard,
  KeyHash,
  StakePool,
  Staking,
  VocabError,
  Withdrawals
}

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

  /** The level of a transaction's information for its script contexts (`toSTxLevel` of `instance
    * HasEraTxLevel LedgerTxInfo era`,
    * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Plutus/Context.hs:130-131`, cardano-ledger
    * 9df9f98c): an era with the top level only has no sub-transaction's information.
    */
  def levelOf[L <: TxLevel](level: LedgerLevelTxInfo[L, E]): Level[L]

  /** The outputs' class (the superclass `EraTxOut era`). */
  def outputsOf: EraTxOut[E]

  /** The certificates' class (the superclass `EraTxCert era`). */
  def certsOf: EraTxCert[E]

  def id[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Bytes
  def inputs[L <: TxLevel](body: TxBody[L, E])(using Level[L]): SortedSet[TxIn]
  def outputs[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Vector[TxOut[E]]
  def fee(body: TxBody[TxLevel.Top, E]): Coin

  /** `withdrawalsTxBodyL` (`Core.hs:251`, cardano-ledger f649f975): the reference's
    * [[Withdrawals]], each account an `AccountAddress`.
    */
  def withdrawals[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Withdrawals
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

  /** `spendableInputsTxBodyF` (`Core.hs:255-258`, cardano-ledger f649f975): the inputs whose
    * outputs the transaction may spend, which ones depending on its validity: from Alonzo the
    * collateral inputs too.
    */
  def spendableInputs[L <: TxLevel](body: TxBody[L, E])(using Level[L]): SortedSet[TxIn]

  /** `getTotalDepositsTxBody` (`Core.hs:267-277`, cardano-ledger f649f975): the deposits a
    * top-level body pays, its contribution to what the transaction produces.
    */
  def getTotalDepositsTxBody(
      pp: PParams[E],
      isPoolRegistered: KeyHash[StakePool] => Boolean,
      body: TxBody[TxLevel.Top, E]
  ): Coin =
    certsOf.getTotalDepositsTxCerts(pp, isPoolRegistered, certs[TxLevel.Top](body)(using top))

  /** `getTotalRefundsTxBody` (`Core.hs:279-289`, cardano-ledger f649f975): the refunds a body
    * collects, its contribution to what the transaction consumes.
    */
  def getTotalRefundsTxBody[L <: TxLevel](
      pp: PParams[E],
      lookupStakingDeposit: Credential[Staking] => Option[Coin],
      body: TxBody[L, E]
  )(using Level[L]): Coin = certsOf.getTotalRefundsTxCerts(pp, lookupStakingDeposit, certs(body))

/** The protocol-parameter update of a body, up to Babbage (`ShelleyEraTxBody`, whose instances end
  * at Babbage: `AtMostEra "Babbage"`, `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxBody.hs:97`,
  * cardano-ledger f649f975).
  */
trait ShelleyEraTxBody[E <: EraTag] extends EraTxBody[E]:
  /** The certificates' class (the superclass `ShelleyEraTxCert era`, `Shelley/TxBody.hs:97`,
    * cardano-ledger f649f975).
    */
  override def certsOf: ShelleyEraTxCert[E]

  /** `updateTxBodyL` (`Shelley/TxBody.hs:97-100`, cardano-ledger f649f975): the typed update
    * proposal ([[Update]]), read with the bytes the node keeps it by ([[KeptUpdate]]), the form
    * PPUP records its proposals in.
    */
  def keptUpdate(body: TxBody[TxLevel.Top, E]): Option[KeptUpdate[E]]

  /** `updateTxBodyL`: the typed update proposal alone ([[keptUpdate]]). */
  final def update(body: TxBody[TxLevel.Top, E]): Option[Update[E]] =
    keptUpdate(body).map(_.update)

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
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxBody.hs:127-154`, cardano-ledger f649f975), with
  * its redeemer pointers in the era's purposes.
  */
trait AlonzoEraTxBody[E <: EraTag] extends MaryEraTxBody[E]:
  override def outputsOf: AlonzoEraTxOut[E]

  /** `collateralInputsTxBodyL`, of a top-level body only. */
  def collateralInputs(body: TxBody[TxLevel.Top, E]): SortedSet[TxIn]

  /** `reqSignerHashesTxBodyG` (`Alonzo/TxBody.hs:132-136`, cardano-ledger f649f975): the keys a
    * body requires to sign it, in every era from Alonzo. Up to Conway it is the body's field
    * ([[AlonzoEraTxBodyToConway]]); Dijkstra's, in the reference, is its guards' keys
    * (`Dijkstra/TxBody.hs:1419`, cardano-ledger 9df9f98c).
    */
  def reqSignerHashes[L <: TxLevel](body: TxBody[L, E])(using Level[L]): SortedSet[Bytes]
  def scriptIntegrityHash[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Option[Bytes]
  def networkId[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Option[Network]

  /** `redeemerPointerInverse` (`Alonzo/TxBody.hs:151-154`, cardano-ledger f649f975): the purpose,
    * with its item, that a redeemer's pointer names in the body, none if it names nothing.
    */
  def redeemerPointerInverse[L <: TxLevel](body: TxBody[L, E], ptr: PlutusPurpose[AsIx, E])(using
      Level[L]
  ): Option[PlutusPurpose[AsIxItem, E]]

/** The body's own field of required signers, of the eras up to Conway (`reqSignerHashesTxBodyL`,
  * `AtMostEra "Conway"`, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxBody.hs:130`, cardano-ledger
  * f649f975, and `:134`, 9df9f98c): a member the reference confines to a range of eras is a class
  * of its own with instances at exactly those eras (#85, 2026-11-15), so Dijkstra, whose body has
  * no such field (`notSupportedInThisEraL`, `Dijkstra/TxBody.hs:1416`, cardano-ledger 9df9f98c),
  * has no instance. The getter of every era ([[AlonzoEraTxBody.reqSignerHashes]]) is this field
  * here, as the reference's default of `reqSignerHashesTxBodyG` is (`Alonzo/TxBody.hs:134-136`,
  * cardano-ledger f649f975).
  */
trait AlonzoEraTxBodyToConway[E <: EraTag] extends AlonzoEraTxBody[E]:
  /** `reqSignerHashesTxBodyL`. */
  def reqSignerHashesField[L <: TxLevel](body: TxBody[L, E])(using Level[L]): SortedSet[Bytes]

  final override def reqSignerHashes[L <: TxLevel](body: TxBody[L, E])(using
      Level[L]
  ): SortedSet[Bytes] = reqSignerHashesField(body)

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

  /** `babbageSpendableInputsTxBodyF` (`Babbage/TxBody.hs:223-229`, cardano-ledger f649f975): the
    * spent and the collateral inputs, which an era's instance gives at the top level from Babbage.
    */
  protected final def spendableInputsBabbage(body: TxBody[TxLevel.Top, E]): SortedSet[TxIn] =
    inputs(body)(using top) ++ collateralInputs(body)

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
  def votingProcedures[L <: TxLevel](body: TxBody[L, E])(using Level[L]): VotingProcedures[E]
  def proposalProcedures[L <: TxLevel](body: TxBody[L, E])(using
      Level[L]
  ): OSet[ProposalProcedure[E]]
  def treasuryDonation[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Coin

  /** `conwayProposalsDeposits` (`Conway/TxBody.hs:382-391`, cardano-ledger f649f975): the
    * governance-action deposit of the parameters for each proposal.
    */
  final def conwayProposalsDeposits[L <: TxLevel](pp: PParams[E], body: TxBody[L, E])(using
      Level[L],
      ConwayEraPParams[E]
  ): Coin =
    Coin(
      BigInt(proposalProcedures(body).size) *
        ConwayEraPParams.ppGovActionDepositCompactL[E].get(pp).toBigInt
    )

/** The fields of a body from Dijkstra (`DijkstraEraTxBody`,
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxBody.hs:1581-1599`, cardano-ledger 9df9f98c).
  */
trait DijkstraEraTxBody[E <: EraTag] extends ConwayEraTxBody[E]:
  /** `guardsTxBodyL`: the credentials whose guards the body requires, in the order read. */
  def guards[L <: TxLevel](body: TxBody[L, E])(using Level[L]): OSet[Credential[Guard]]

  /** `subTransactionsTxBodyL`, of a top-level body only: the sub-transactions by id, in the order
    * read.
    */
  def subTransactions(body: TxBody[TxLevel.Top, E]): OMap[Bytes, Tx[TxLevel.Sub, E]]

  /** `requiredTopLevelGuardsTxBodyL`: the guards the body requires to be among the top-level body's
    * guards, each with its data or none. The reference reads them from the top-level body and from
    * each sub-transaction's (`Rules/Utxow.hs:313-318`, cardano-ledger 9df9f98c).
    */
  def requiredTopLevelGuards[L <: TxLevel](body: TxBody[L, E])(using
      Level[L]
  ): Map[Credential[Guard], Option[Data]]

  /** `directDepositsTxBodyL`: the coin deposited into each account. */
  def directDeposits[L <: TxLevel](body: TxBody[L, E])(using Level[L]): Map[RewardAccount, Coin]

  /** `accountBalanceIntervalsTxBodyL`: the interval each account's balance must be in. */
  def accountBalanceIntervals[L <: TxLevel](body: TxBody[L, E])(using
      Level[L]
  ): Map[RewardAccount, AccountBalanceInterval]

  /** `startingAccountBalanceIntervalsTxBodyL`, of a top-level body only. */
  def startingAccountBalanceIntervals(
      body: TxBody[TxLevel.Top, E]
  ): Map[RewardAccount, AccountBalanceInterval]

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

  /** A parameter the decoder read (in an update or a hard fork's version) that is not a value of
    * its reference type, which the decoder's own bounds rule out.
    */
  case PParams(cause: PParamsError)

  /** The scripts of a witness set read again from its bytes are not the ones its decoder read. */
  case ScriptsReadAgain

  /** A Dijkstra body read at another level than the one asked for, which the decoders never give.
    */
  case OtherLevel

  /** The datums or redeemers of a witness set read again from its bytes (keys 4 and 5) are not the
    * ones its decoder read.
    */
  case WitnessDataReadAgain

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

  /** A certificate Conway brought (a deposit, a vote delegation, a DRep's or a committee member's),
    * which no decoder before Conway reads.
    */
  case ConwayCertificate

/** The instances of each era, the one place that lists the eras having each body class. */
object EraTxBody:
  given alonzo: ((ShelleyEraTxBody[Alonzo] & AlonzoEraTxBodyToConway[Alonzo]) {
    type Level[L <: TxLevel] = TopOnly[L]
  }) =
    xyz.kd5ujc.scarab.ledger.era.alonzo.AlonzoTxBody.instance

  given babbage: ((ShelleyEraTxBody[Babbage] & BabbageEraTxBody[Babbage] &
    AlonzoEraTxBodyToConway[Babbage]) { type Level[L <: TxLevel] = TopOnly[L] }) =
    xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTxBody.instance

  given conway: ((ConwayEraTxBody[Conway] & AlonzoEraTxBodyToConway[Conway]) {
    type Level[L <: TxLevel] = TopOnly[L]
  }) =
    xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxBody.instance

  given dijkstra: (DijkstraEraTxBody[Dijkstra] { type Level[L <: TxLevel] = BothLevels[L] }) =
    xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTxBody.instance
