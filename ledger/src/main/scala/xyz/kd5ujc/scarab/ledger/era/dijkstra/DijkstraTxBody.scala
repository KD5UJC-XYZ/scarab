// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import scala.annotation.unused
import scala.collection.immutable.SortedSet
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  CborRead,
  Credential as FlatCredential,
  DecodeField,
  LedgerDecodeError,
  RewardAccount,
  TxIn
}
import xyz.kd5ujc.scarab.ledger.dijkstra.{
  DijkstraBalanceInterval,
  DijkstraLevel,
  DijkstraTxBody as FlatDijkstraTxBody,
  DijkstraTxOut as FlatDijkstraTxOut,
  V12
}
import xyz.kd5ujc.scarab.ledger.era.{
  AsIx,
  AsIxItem,
  Exclusive,
  Inclusive,
  BabbageEraTxOut,
  BothLevels,
  ConwayEraTxCert,
  Data,
  DijkstraEraScript,
  DijkstraEraTxBody,
  EraTxError,
  Indexable,
  Network,
  OMap,
  OSet,
  PParams,
  ProposalProcedure,
  Sized,
  TxBody,
  TxLevel,
  ValidityInterval,
  VotingProcedures
}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  Credential,
  Dijkstra,
  Guard,
  KeyHash,
  ScriptHash,
  StakePool
}

/** An account's balance interval (`data AccountBalanceInterval era`,
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Scripts.hs:670-675`, cardano-ledger 9df9f98c):
  * an inclusive lower bound, an exclusive upper bound, both, or an exact balance.
  */
enum AccountBalanceInterval:
  case AccountBalanceLowerBound(lower: Inclusive[Coin])
  case AccountBalanceUpperBound(upper: Exclusive[Coin])
  case AccountBalanceBothBounds(lower: Inclusive[Coin], upper: Exclusive[Coin])
  case AccountBalanceExact(coin: Coin)

object AccountBalanceInterval:
  given CanEqual[AccountBalanceInterval, AccountBalanceInterval] = CanEqual.derived

  /** The interval the flat decoder read, which refused both bounds null (`Scripts.hs:684-696`,
    * cardano-ledger 9df9f98c).
    */
  private[dijkstra] def of(i: DijkstraBalanceInterval): AccountBalanceInterval = i match
    case DijkstraBalanceInterval.Lower(l) => AccountBalanceLowerBound(Inclusive(Coin(l)))
    case DijkstraBalanceInterval.Upper(u) => AccountBalanceUpperBound(Exclusive(Coin(u)))
    case DijkstraBalanceInterval.Both(l, u) =>
      AccountBalanceBothBounds(Inclusive(Coin(l)), Exclusive(Coin(u)))
    case DijkstraBalanceInterval.Exact(c) => AccountBalanceExact(Coin(c))

/** The fields a Dijkstra body has at both levels: those `DijkstraTxBodyRaw` 's two constructors
  * share (`TxBody.hs:172-220`, cardano-ledger 9df9f98c), each at the era's type.
  */
private[dijkstra] final case class DijkstraBodyFields(
    raw: Bytes,
    spendInputs: SortedSet[TxIn],
    referenceInputs: SortedSet[TxIn],
    outputs: Vector[Sized[DijkstraTxOut]],
    certs: OSet[DijkstraTxCert],
    withdrawals: Map[RewardAccount, Coin],
    validity: ValidityInterval,
    guards: OSet[Credential[Guard]],
    mint: Map[Bytes, Map[Bytes, BigInt]],
    scriptIntegrityHash: Option[Bytes],
    auxDataHash: Option[Bytes],
    networkId: Option[Network],
    votingProcedures: VotingProcedures[Dijkstra],
    proposalProcedures: OSet[ProposalProcedure[Dijkstra]],
    currentTreasuryValue: Option[Coin],
    treasuryDonation: Coin,
    requiredTopLevelGuards: Map[Credential[Guard], Option[Data]],
    directDeposits: Map[RewardAccount, Coin],
    accountBalanceIntervals: Map[RewardAccount, AccountBalanceInterval]
)

/** A Dijkstra transaction body of level `L`: `TxBody l DijkstraEra` (`data DijkstraTxBodyRaw l
  * era`, `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxBody.hs:172-220`, cardano-ledger
  * 9df9f98c), with the bytes it arrived as, whose hash is the transaction's id. A top-level body
  * ([[DijkstraTxBody.TopTxBody]], `DijkstraTxBodyRaw`) has, beyond the fields of both levels, the
  * fee, the collateral, the sub-transactions and the starting balance intervals; a
  * sub-transaction's ([[DijkstraTxBody.SubTxBody]], `DijkstraSubTxBodyRaw`) has none of them. Made
  * only by [[DijkstraTx]]'s decoders.
  */
sealed abstract class DijkstraTxBody[L <: TxLevel] private[dijkstra] (
    private[dijkstra] val fields: DijkstraBodyFields
):
  def raw: Bytes = fields.raw

  /** The inputs (`dtbrSpendInputs`), in the ledger's `TxIn` order. */
  def spendInputs: SortedSet[TxIn] = fields.spendInputs
  def referenceInputs: SortedSet[TxIn] = fields.referenceInputs

  /** The outputs, each with the size of the encoding it was read from (`Sized`). */
  def outputs: Vector[Sized[DijkstraTxOut]] = fields.outputs
  def certs: OSet[DijkstraTxCert] = fields.certs
  def withdrawals: Map[RewardAccount, Coin] = fields.withdrawals
  def validity: ValidityInterval = fields.validity
  def guards: OSet[Credential[Guard]] = fields.guards
  def mint: Map[Bytes, Map[Bytes, BigInt]] = fields.mint
  def scriptIntegrityHash: Option[Bytes] = fields.scriptIntegrityHash
  def auxDataHash: Option[Bytes] = fields.auxDataHash
  def networkId: Option[Network] = fields.networkId
  def votingProcedures: VotingProcedures[Dijkstra] = fields.votingProcedures
  def proposalProcedures: OSet[ProposalProcedure[Dijkstra]] = fields.proposalProcedures
  def currentTreasuryValue: Option[Coin] = fields.currentTreasuryValue
  def treasuryDonation: Coin = fields.treasuryDonation
  def requiredTopLevelGuards: Map[Credential[Guard], Option[Data]] = fields.requiredTopLevelGuards
  def directDeposits: Map[RewardAccount, Coin] = fields.directDeposits
  def accountBalanceIntervals: Map[RewardAccount, AccountBalanceInterval] =
    fields.accountBalanceIntervals

  lazy val id: Bytes = xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(fields.raw)

object DijkstraTxBody:
  /** A top-level body (`DijkstraTxBodyRaw`, `TxBody.hs:173-198`, cardano-ledger 9df9f98c). */
  final class TopTxBody private[DijkstraTxBody] (
      topFields: DijkstraBodyFields,
      val collateralInputs: SortedSet[TxIn],
      val collateralReturn: Option[Sized[DijkstraTxOut]],
      val totalCollateral: Option[Coin],
      val fee: Coin,
      val subTransactions: OMap[Bytes, DijkstraTx[TxLevel.Sub]],
      val startingAccountBalanceIntervals: Map[RewardAccount, AccountBalanceInterval]
  ) extends DijkstraTxBody[TxLevel.Top](topFields):
    // Not a case class: its derived `Mirror` would make one from any fields, whatever its bytes.
    private def all: Any = (
      fields,
      collateralInputs,
      collateralReturn,
      totalCollateral,
      fee,
      subTransactions,
      startingAccountBalanceIntervals
    )
    override def equals(that: Any): Boolean = that match
      case o: TopTxBody => all == o.all
      case _            => false
    override def hashCode(): Int = all.hashCode
    override def toString: String = s"TopTxBody($all)"

  /** A sub-transaction's body (`DijkstraSubTxBodyRaw`, `TxBody.hs:199-219`, cardano-ledger
    * 9df9f98c).
    */
  final class SubTxBody private[DijkstraTxBody] (subFields: DijkstraBodyFields)
      extends DijkstraTxBody[TxLevel.Sub](subFields):
    override def equals(that: Any): Boolean = that match
      case o: SubTxBody => fields == o.fields
      case _            => false
    override def hashCode(): Int = fields.hashCode
    override def toString: String = s"SubTxBody($fields)"

  given [L <: TxLevel]: CanEqual[DijkstraTxBody[L], DijkstraTxBody[L]] = CanEqual.derived

  /** `toSTxLevel` of a body (`instance HasEraTxLevel TxBody DijkstraEra`, `TxBody.hs:1079-1084`,
    * cardano-ledger 9df9f98c): the level the body is of, read from its constructor, as
    * `withBothTxLevels` reads it.
    */
  def toSTxLevel[L <: TxLevel](body: DijkstraTxBody[L]): BothLevels[L] = body match
    case _: TopTxBody => BothLevels.Top
    case _: SubTxBody => BothLevels.Sub

  /** The top-level body, which is the only body of the top level. */
  def topBody(body: DijkstraTxBody[TxLevel.Top]): TopTxBody = body match
    case t: TopTxBody => t

  /** The certificates of a body as its bytes hold them (key 4, `decodeOSet` of a set that may be
    * tagged 258), in the order the flat decoder read them: a pool registration's relays and
    * metadata are read from them ([[DijkstraProcedures.cert]]).
    */
  private def certItems(raw: Bytes): Either[EraTxError, Vector[Node]] =
    (for
      body <- CborRead.decode(raw, V12.nestedLimits)
      entries <- V12.entries(body, DecodeField.TransactionBody)
      certs = entries.collectFirst {
        case (k, v) if (k.value match
              case Value.UInt(t) => t == 4
              case _             => false
            ) =>
          v
      }
      items <- certs.fold(Right(Vector.empty[Node]))(V12.setItems(_, DecodeField.Certificates))
    yield items).leftMap(EraTxError.Decode(_))

  /** The fields of both levels of a body the flat decoder read, at the era's types. */
  private def fieldsOf(b: FlatDijkstraTxBody): Either[EraTxError, DijkstraBodyFields] =
    // read again only when a certificate needs its bytes, a pool registration
    lazy val items = certItems(b.raw)
    def item(i: Int): Either[EraTxError, Node] =
      items.flatMap(
        _.lift(i).toRight(
          EraTxError.Decode(
            LedgerDecodeError.ArrayLength(DecodeField.Certificates, i + 1, b.certificates.size)
          )
        )
      )
    for
      outputs <- b.outputs.traverse(sized)
      certs <- b.certificates.zipWithIndex.traverse((c, i) => DijkstraProcedures.cert(c, item(i)))
      proposals <- b.proposals.traverse(DijkstraProcedures.proposal)
      network <- b.networkId.traverse(v =>
        Network
          .of(BigInt(v))
          .toRight(
            EraTxError.Decode(
              LedgerDecodeError.NumberOutOfRange(DecodeField.NetworkId, BigInt(v), 0, 1)
            )
          )
      )
      // each datum the flat decoder read as data, which it refused otherwise with this error
      guards <- b.guards.traverse(guard)
      required <- b.requiredTopLevelGuards.traverse((c, d) =>
        (
          guard(c),
          d.traverse(bytes =>
            Data
              .decode(bytes)
              .leftMap(e =>
                EraTxError
                  .Decode(LedgerDecodeError.PlutusData(DecodeField.RequiredTopLevelGuards, e))
              )
          )
        ).tupled
      )
    // the decoder refused a repeated certificate, proposal or guard (`decodeOSet`), a repeated
    // voter or action (`decodeMapByKey`) and a repeated key of each map, so the ordered sets and the
    // maps below hold what it read
    yield DijkstraBodyFields(
      b.raw,
      inputs(b.inputs),
      inputs(b.referenceInputs),
      outputs,
      OSet.decoded(certs),
      b.withdrawals.map((a, c) => a -> Coin(c)).toMap,
      ValidityInterval(b.validityStart, b.ttl),
      OSet.decoded(guards),
      b.mint,
      b.scriptIntegrityHash,
      b.auxDataHash,
      network,
      DijkstraProcedures.votes(b.votingProcedures),
      OSet.decoded(proposals),
      b.currentTreasuryValue.map(Coin(_)),
      Coin(b.donation),
      required.toMap,
      b.directDeposits.map((a, c) => a -> Coin(c)).toMap,
      intervals(b.accountBalanceIntervals)
    )

  private def inputs(
      xs: Vector[xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxIn]
  ): SortedSet[TxIn] =
    SortedSet.from(xs.map(i => TxIn(i.id, BigInt(i.index))))

  private def intervals(
      xs: Vector[(RewardAccount, DijkstraBalanceInterval)]
  ): Map[RewardAccount, AccountBalanceInterval] =
    xs.map((a, i) => a -> AccountBalanceInterval.of(i)).toMap

  // An output read again from the bytes the flat decoder read it from, through Dijkstra's checked
  // read, with the size of that encoding (`Sized`).
  private def sized(out: FlatDijkstraTxOut): Either[EraTxError, Sized[DijkstraTxOut]] =
    CborRead
      .decode(out.raw, V12.nestedLimits)
      .flatMap(DijkstraTxOut.read)
      .bimap(EraTxError.Decode(_), Sized(_, out.raw.size.toLong))

  /** A top-level body the flat decoder read, with its sub-transactions in Dijkstra's types. */
  private[dijkstra] def topOf(
      b: FlatDijkstraTxBody,
      subTransactions: OMap[Bytes, DijkstraTx[TxLevel.Sub]]
  )(using @unused proof: DijkstraTx.FromBytes): Either[EraTxError, DijkstraTxBody[TxLevel.Top]] =
    for
      _ <- level(b, DijkstraLevel.Top)
      fields <- fieldsOf(b)
      collateralReturn <- b.collateralReturn.traverse(sized)
      // the flat decoder requires a fee at the top level (`requiredFields`, `TxBody.hs:476-479`, cardano-ledger 9df9f98c)
      fee <- b.fee.toRight(
        EraTxError.Decode(
          LedgerDecodeError.MissingField(DecodeField.TransactionBody, DecodeField.Fee)
        )
      )
    yield TopTxBody(
      fields,
      inputs(b.collateral),
      collateralReturn,
      b.totalCollateral.map(Coin(_)),
      Coin(fee),
      subTransactions,
      intervals(b.startingAccountBalanceIntervals)
    )

  /** A sub-transaction's body the flat decoder read, whose decoder took none of the top level's
    * keys.
    */
  private[dijkstra] def subOf(
      b: FlatDijkstraTxBody
  )(using @unused proof: DijkstraTx.FromBytes): Either[EraTxError, DijkstraTxBody[TxLevel.Sub]] =
    level(b, DijkstraLevel.Sub) *> fieldsOf(b).map(SubTxBody(_))

  // A body read at the level asked for: the flat decoder took only that level's keys.
  private def level(b: FlatDijkstraTxBody, asked: DijkstraLevel): Either[EraTxError, Unit] =
    Either.cond(b.level == asked, (), EraTxError.OtherLevel)

  /** `dijkstraRedeemerPointerInverse` (`TxBody.hs:1297-1316`, cardano-ledger 9df9f98c): the item at
    * the pointer's index among the policies minted (in their order), the inputs, the withdrawals'
    * accounts (in theirs), the certificates, the voters (in theirs), the proposals, or the guards,
    * where a guard that is a key names nothing (`GuardsScriptHashView`, `TxBody.hs:1264-1272`,
    * cardano-ledger 9df9f98c).
    */
  def dijkstraRedeemerPointerInverse[L <: TxLevel](
      txBody: DijkstraTxBody[L],
      ptr: DijkstraPlutusPurpose[AsIx]
  ): Option[DijkstraPlutusPurpose[AsIxItem]] =
    import DijkstraPlutusPurpose.*
    ptr match
      case DijkstraMinting(idx) =>
        Indexable.fromIndexSet(idx, SortedSet.from(txBody.mint.keySet)).map(DijkstraMinting(_))
      case DijkstraSpending(idx) =>
        Indexable.fromIndexSet(idx, txBody.spendInputs).map(DijkstraSpending(_))
      case DijkstraWithdrawing(idx) =>
        Indexable.fromIndexMap(idx, txBody.withdrawals).map(DijkstraWithdrawing(_))
      case DijkstraCertifying(idx) =>
        Indexable.fromIndexOSet(idx, txBody.certs).map(DijkstraCertifying(_))
      case DijkstraVoting(idx) =>
        Indexable
          .fromIndexMap(idx, txBody.votingProcedures.unVotingProcedures)
          .map(DijkstraVoting(_))
      case DijkstraProposing(idx) =>
        Indexable.fromIndexOSet(idx, txBody.proposalProcedures).map(DijkstraProposing(_))
      case DijkstraGuarding(idx) =>
        Indexable
          .fromIndexOSet(
            AsIx[
              xyz.kd5ujc.scarab.ledger.Unsigned[xyz.kd5ujc.scarab.ledger.Unsigned.Width.W32],
              Credential[Guard]
            ](idx.ix),
            txBody.guards
          )
          .flatMap(item => guardScript(item.item).map(h => DijkstraGuarding(AsIxItem(item.ix, h))))

  // The script hash of a guard, none for a key (`toScriptHash`, `TxBody.hs:1270-1272`, cardano-ledger 9df9f98c).
  private def guardScript(c: Credential[Guard]): Option[ScriptHash] = c match
    case Credential.ScriptHashObj(h) => Some(h)
    case Credential.KeyHashObj(_)    => None

  // A guard the flat decoder read, as a guard's credential (`Credential Guard`): its hash read as 28
  // bytes, which the decoder bounded.
  private def guard(c: FlatCredential): Either[EraTxError, Credential[Guard]] =
    Convert.credential[Guard](c).leftMap(EraTxError.Vocab(_))

  /** `EraTxBody`, `AllegraEraTxBody`, `MaryEraTxBody`, `AlonzoEraTxBody`, `BabbageEraTxBody`,
    * `ConwayEraTxBody` and `DijkstraEraTxBody DijkstraEra` (`TxBody.hs:1183-1655`, cardano-ledger
    * 9df9f98c), of both levels. Dijkstra's body has no required signers' field
    * (`notSupportedInThisEraL`, `TxBody.hs:1416`, cardano-ledger 9df9f98c): its required signers
    * are its guards' keys.
    */
  val instance: DijkstraEraTxBody[Dijkstra] { type Level[L <: TxLevel] = BothLevels[L] } =
    new DijkstraEraTxBody[Dijkstra]:
      type Level[L <: TxLevel] = BothLevels[L]
      override def top: BothLevels[TxLevel.Top] = BothLevels.Top
      override def outputsOf: BabbageEraTxOut[Dijkstra] = DijkstraTxOut.instance
      override def certsOf: ConwayEraTxCert[Dijkstra] = DijkstraTxCert.instance
      override def scriptsOf: DijkstraEraScript[Dijkstra] = DijkstraScript.instance
      override def id[L <: TxLevel](body: TxBody[L, Dijkstra])(using BothLevels[L]): Bytes =
        body.id
      override def inputs[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): SortedSet[TxIn] = body.spendInputs
      override def outputs[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): Vector[DijkstraTxOut] = body.outputs.map(_.value)
      override def sizedOutputs[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): Vector[Sized[DijkstraTxOut]] = body.outputs
      override def fee(body: TxBody[TxLevel.Top, Dijkstra]): Coin = topBody(body).fee
      override def withdrawals[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): Map[RewardAccount, Coin] = body.withdrawals
      override def auxDataHash[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): Option[Bytes] = body.auxDataHash
      // `certsTxBodyL`, the `OSet` as a sequence, `TxBody.hs:1219-1222`, cardano-ledger 9df9f98c
      override def certs[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): Vector[DijkstraTxCert] = body.certs.toVector
      override def vldt[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): ValidityInterval = body.validity
      override def mint[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): Map[Bytes, Map[Bytes, BigInt]] = body.mint
      override def collateralInputs(body: TxBody[TxLevel.Top, Dijkstra]): SortedSet[TxIn] =
        topBody(body).collateralInputs
      // `reqSignerHashesTxBodyG`, `TxBody.hs:1419-1423`, cardano-ledger 9df9f98c: the key hashes among the guards
      override def reqSignerHashes[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): SortedSet[Bytes] =
        SortedSet.from(body.guards.toVector.flatMap {
          case Credential.KeyHashObj(h)    => Some(h.bytes)
          case Credential.ScriptHashObj(_) => None
        })
      override def scriptIntegrityHash[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): Option[Bytes] = body.scriptIntegrityHash
      override def networkId[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): Option[Network] = body.networkId
      // `redeemerPointerInverse = dijkstraRedeemerPointerInverse`, `TxBody.hs:1433`, cardano-ledger 9df9f98c
      override def redeemerPointerInverse[L <: TxLevel](
          body: TxBody[L, Dijkstra],
          ptr: DijkstraPlutusPurpose[AsIx]
      )(using BothLevels[L]): Option[DijkstraPlutusPurpose[AsIxItem]] =
        dijkstraRedeemerPointerInverse(body, ptr)
      override def referenceInputs[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): SortedSet[TxIn] = body.referenceInputs
      override def totalCollateral(body: TxBody[TxLevel.Top, Dijkstra]): Option[Coin] =
        topBody(body).totalCollateral
      override def sizedCollateralReturn(
          body: TxBody[TxLevel.Top, Dijkstra]
      ): Option[Sized[DijkstraTxOut]] = topBody(body).collateralReturn
      // `dijkstraAllInputsTxBodyF`, `TxBody.hs:1551-1558`, cardano-ledger 9df9f98c: each sub-transaction's inputs and
      // reference inputs, and the top level's spent, collateral and reference inputs
      override def allInputs(body: TxBody[TxLevel.Top, Dijkstra]): SortedSet[TxIn] =
        topBody(body).subTransactions.toVector.foldLeft(allInputsBabbage(body))((all, sub) =>
          all ++ sub.body.spendInputs ++ sub.body.referenceInputs
        )
      // `spendableInputsTxBodyF`, `TxBody.hs:1212-1214`, cardano-ledger 9df9f98c: the spent and collateral inputs at the
      // top level, the spent inputs of a sub-transaction
      override def spendableInputs[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          l: BothLevels[L]
      ): SortedSet[TxIn] =
        l match
          case BothLevels.Top => spendableInputsBabbage(body)
          case BothLevels.Sub => body.spendInputs
      // `getTotalDepositsTxBody = dijkstraTotalDepositsTxBody`, `TxBody.hs:1225,1254-1258`, cardano-ledger 9df9f98c: the
      // certificates' deposits and the proposals'
      override def getTotalDepositsTxBody(
          pp: PParams[Dijkstra],
          isPoolRegistered: KeyHash[StakePool] => Boolean,
          body: TxBody[TxLevel.Top, Dijkstra]
      ): Coin =
        Coin(
          certsOf
            .getTotalDepositsTxCerts(pp, isPoolRegistered, certs[TxLevel.Top](body)(using top))
            .value +
            conwayProposalsDeposits[TxLevel.Top](pp, body)(using top).value
        )
      // `allSizedOutputsTxBodyF`, `TxBody.hs:1498-1500`, cardano-ledger 9df9f98c: the outputs and the collateral return at
      // the top level, the outputs of a sub-transaction
      override def allSizedOutputs[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          l: BothLevels[L]
      ): Vector[Sized[DijkstraTxOut]] =
        l match
          case BothLevels.Top => allSizedOutputsBabbage(body)
          case BothLevels.Sub => body.outputs
      override def currentTreasuryValue[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): Option[Coin] = body.currentTreasuryValue
      override def votingProcedures[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): VotingProcedures[Dijkstra] = body.votingProcedures
      override def proposalProcedures[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): OSet[ProposalProcedure[Dijkstra]] = body.proposalProcedures
      override def treasuryDonation[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): Coin = body.treasuryDonation
      override def guards[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): OSet[Credential[Guard]] = body.guards
      override def subTransactions(
          body: TxBody[TxLevel.Top, Dijkstra]
      ): OMap[Bytes, DijkstraTx[TxLevel.Sub]] = topBody(body).subTransactions
      override def requiredTopLevelGuards[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): Map[Credential[Guard], Option[Data]] = body.requiredTopLevelGuards
      override def directDeposits[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): Map[RewardAccount, Coin] = body.directDeposits
      override def accountBalanceIntervals[L <: TxLevel](body: TxBody[L, Dijkstra])(using
          BothLevels[L]
      ): Map[RewardAccount, AccountBalanceInterval] = body.accountBalanceIntervals
      override def startingAccountBalanceIntervals(
          body: TxBody[TxLevel.Top, Dijkstra]
      ): Map[RewardAccount, AccountBalanceInterval] = topBody(body).startingAccountBalanceIntervals
