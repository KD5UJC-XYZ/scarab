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
  DijkstraCert,
  DijkstraLevel,
  DijkstraProposal,
  DijkstraTx as FlatDijkstraTx,
  DijkstraTxBody as FlatDijkstraTxBody,
  DijkstraTxIn as FlatTxIn,
  DijkstraTxOut as FlatDijkstraTxOut,
  DijkstraVoting as FlatVoting,
  MultiAsset,
  Sparse,
  V12
}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraOrder.given
import xyz.kd5ujc.scarab.ledger.dijkstra.V12.strings
import xyz.kd5ujc.scarab.ledger.era.{
  LedgerLevelTxInfo,
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
  StakePool,
  Withdrawals
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
    withdrawals: Withdrawals,
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
  def withdrawals: Withdrawals = fields.withdrawals
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
      // the reference's `Withdrawals` (`dtbrWithdrawals` and `dstbrWithdrawals`, `TxBody.hs:181,
      // 205`, cardano-ledger 9df9f98c), each account the `AccountAddress` the decoder read
      withdrawals <- Convert
        .withdrawals(b.withdrawals.map((a, c) => a -> Coin(c)))
        .leftMap(EraTxError.Vocab(_))
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
      withdrawals,
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

  // The keys of a body of each level and those it requires (`decoderByKey`, `requiredFields`,
  // `TxBody.hs:347-479`, cardano-ledger 9df9f98c), as the flat decoder takes them, made once.
  private val topKeys: Set[Int] = Set(0, 1, 2, 3, 4, 5, 7, 8, 9, 11, 13, 14, 15, 16, 17, 18) ++
    (19 to 27)
  private val topRequired: Set[Int] = Set(0, 1, 2)
  private val subKeys: Set[Int] =
    Set(0, 1, 3, 4, 5, 7, 8, 9, 11, 14, 15, 18, 19, 20, 21, 22, 24, 25, 26)
  private val subRequired: Set[Int] = Set(0, 1)

  /** What a body's map holds at either level, read into Dijkstra's types: the fields of both
    * levels, and those only a top-level body may hold, which the keys of a sub-transaction's body
    * leave out.
    */
  private final case class Read(
      fields: DijkstraBodyFields,
      fee: Option[Coin],
      collateralInputs: SortedSet[TxIn],
      collateralReturn: Option[Sized[DijkstraTxOut]],
      totalCollateral: Option[Coin],
      subTransactions: Vector[DijkstraTx[TxLevel.Sub]],
      startingAccountBalanceIntervals: Map[RewardAccount, AccountBalanceInterval]
  )

  /** `DecCBOR (Annotator (DijkstraTxBodyRaw l era))` (`decodeSparseKeyed`, `TxBody.hs:340-479`,
    * cardano-ledger 9df9f98c), read from the item straight into Dijkstra's types: the keys of the
    * level, each field and each refusal, and their order, the Leios build's block decoder's
    * ([[xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTx.body]]), which this reads as that decoder
    * followed by [[topOf]] and [[subOf]] did. Each output is read once, by Dijkstra's reader
    * ([[DijkstraTxOut.decode]]), not from its bytes again, a certificate from its own item, and
    * each required guard's datum decoded once ([[Data.read]]). `subTx` reads a sub-transaction,
    * which only a top-level body has (key 23).
    */
  private def read(
      n: Node,
      level: DijkstraLevel,
      subTx: Node => Either[EraTxError, DijkstraTx[TxLevel.Sub]]
  ): Either[EraTxError, Read] =
    def d[A](r: Either[LedgerDecodeError, A]): Either[EraTxError, A] =
      r.leftMap(EraTxError.Decode(_))
    val (known, required) = level match
      case DijkstraLevel.Top => (topKeys, topRequired)
      case DijkstraLevel.Sub => (subKeys, subRequired)
    def coin(site: DecodeField)(x: Node) = V12.word64(x, site)
    def hash32(site: DecodeField)(x: Node) = V12.bytesN(x, 32, site)
    def nonEmpty[A](site: DecodeField)(r: Either[LedgerDecodeError, Vector[A]]) =
      r.flatMap(V12.nonEmpty(_, site))
    for
      f <- d(Sparse.read(n, DecodeField.TransactionBody, required)(known))
      spent <- d(f.get(0).traverse(FlatTxIn.set(_, DecodeField.Inputs)))
      outputs <- f.get(1).traverse(outputList)
      fee <- d(f.get(2).traverse(coin(DecodeField.Fee)))
      ttl <- d(f.get(3).traverse(coin(DecodeField.Ttl)))
      certs <- f.get(4).traverse(certSet)
      withdrawals <- d(
        f.get(5)
          .traverse(
            FlatDijkstraTx.accountMap(_, DecodeField.Withdrawals)(
              coin(DecodeField.WithdrawalAmount)
            )
          )
      )
      // the reference's `Withdrawals` (`dtbrWithdrawals` and `dstbrWithdrawals`, `TxBody.hs:181,
      // 205`, cardano-ledger 9df9f98c), each account the `AccountAddress` the decoder read
      withdrawn <- Convert
        .withdrawals(withdrawals.orEmpty.map((a, c) => a -> Coin(c)))
        .leftMap(EraTxError.Vocab(_))
      auxHash <- d(f.get(7).traverse(hash32(DecodeField.AuxiliaryDataHash)))
      start <- d(f.get(8).traverse(coin(DecodeField.ValidityStart)))
      mint <- d(f.get(9).traverse(MultiAsset.decode(_, DecodeField.Mint)(MultiAsset.minted)))
      integrity <- d(f.get(11).traverse(hash32(DecodeField.ScriptDataHash)))
      collateral <- d(f.get(13).traverse(FlatTxIn.nonEmptySet(_, DecodeField.Collateral)))
      guardSet <- d(f.get(14).traverse(FlatDijkstraTx.guards)).flatMap(gs =>
        gs.orEmpty.traverse(guard)
      )
      network <- d(f.get(15).traverse(FlatDijkstraTx.networkId)).flatMap(
        _.traverse(v =>
          // a `Word8` of 0 or 1, as read above
          Network
            .of(BigInt(v))
            .toRight(
              EraTxError.Decode(
                LedgerDecodeError.NumberOutOfRange(DecodeField.NetworkId, BigInt(v), 0, 1)
              )
            )
        )
      )
      collateralReturn <- f.get(16).traverse(output)
      totalCollateral <- d(f.get(17).traverse(coin(DecodeField.TotalCollateral)))
      references <- d(f.get(18).traverse(FlatTxIn.nonEmptySet(_, DecodeField.ReferenceInputs)))
      votes <- d(
        f.get(19)
          .traverse(v => nonEmpty(DecodeField.VotingProcedures)(FlatVoting.procedures(v)))
      )
      proposals <- d(
        f.get(20)
          .traverse(p => nonEmpty(DecodeField.ProposalProcedures)(DijkstraProposal.set(p)))
      ).flatMap(ps => ps.orEmpty.traverse(DijkstraProcedures.proposal))
      treasury <- d(f.get(21).traverse(coin(DecodeField.CurrentTreasuryValue)))
      donation <- d(f.get(22).traverse(FlatDijkstraTx.positive))
      subs <- f.get(23).traverse(subTransactions(_, subTx))
      requiredGuards <- f.get(24).traverse(requiredTopLevelGuards)
      deposits <- d(
        f.get(25)
          .traverse(
            FlatDijkstraTx.accountMap(_, DecodeField.DirectDeposits)(
              coin(DecodeField.DirectDeposits)
            )
          )
      )
      balances <- d(
        f.get(26)
          .traverse(
            FlatDijkstraTx.accountMap(_, DecodeField.AccountBalanceIntervals)(
              FlatDijkstraTx.balanceInterval
            )
          )
      )
      starting <- d(
        f.get(27)
          .traverse(
            FlatDijkstraTx.accountMap(_, DecodeField.AccountBalanceIntervals)(
              FlatDijkstraTx.balanceInterval
            )
          )
      )
    // the decoder refused a repeated certificate, proposal or guard (`decodeOSet`), a repeated
    // voter or action (`decodeMapByKey`) and a repeated key of each map, so the ordered sets and the
    // maps below hold what it read
    yield Read(
      DijkstraBodyFields(
        n.original,
        inputs(spent.orEmpty),
        inputs(references.orEmpty),
        outputs.orEmpty,
        OSet.decoded(certs.orEmpty),
        withdrawn,
        ValidityInterval(start, ttl),
        OSet.decoded(guardSet),
        mint.getOrElse(Map.empty),
        integrity,
        auxHash,
        network,
        DijkstraProcedures.votes(votes.orEmpty),
        OSet.decoded(proposals),
        treasury.map(Coin(_)),
        Coin(donation.getOrElse(BigInt(0))),
        requiredGuards.getOrElse(Map.empty),
        deposits.orEmpty.map((a, c) => a -> Coin(c)).toMap,
        intervals(balances.orEmpty)
      ),
      fee.map(Coin(_)),
      inputs(collateral.orEmpty),
      collateralReturn,
      totalCollateral.map(Coin(_)),
      subs.orEmpty,
      intervals(starting.orEmpty)
    )

  // An output read by Dijkstra's reader, with the size of its encoding (`Sized`).
  private def output(n: Node): Either[EraTxError, Sized[DijkstraTxOut]] =
    DijkstraTxOut.decode(n).map(Sized(_, n.original.size.toLong))

  // `Seq (Sized TxOut)` (`decodeStrictSeq`): a list, no tag.
  private def outputList(n: Node): Either[EraTxError, Vector[Sized[DijkstraTxOut]]] =
    V12.list(n, DecodeField.Outputs).leftMap(EraTxError.Decode(_)).flatMap(_.traverse(output))

  /** Key 4: `OSet (TxCert era)` (`decodeOSet`, the tag 258 allowed, no certificate twice by value),
    * not empty (`failOnNull`), as the flat decoder reads it; each certificate is then made in the
    * era's types from what it read and from its own item ([[DijkstraProcedures.cert]]).
    */
  private def certSet(n: Node): Either[EraTxError, Vector[DijkstraTxCert]] =
    (for
      items <- V12.setItems(n, DecodeField.Certificates)
      read <- items.traverse(DijkstraCert.decode)
      _ <- V12.unique(read, DecodeField.Certificates)(identity)
      _ <- V12.nonEmpty(read, DecodeField.Certificates)
    yield read.zip(items))
      .leftMap(EraTxError.Decode(_))
      .flatMap(_.traverse((c, item) => DijkstraProcedures.cert(c, Right(item))))

  /** Key 23: `decodeSubTransactions` (`TxBody.hs:452-456`,
    * `decodeNonEmptySetLikeEnforceNoDuplicatesAnn`, cardano-ledger 9df9f98c): the tag 258 allowed,
    * a non-empty list of sub-transactions, no transaction id twice, as the flat decoder reads it.
    */
  private def subTransactions(
      n: Node,
      subTx: Node => Either[EraTxError, DijkstraTx[TxLevel.Sub]]
  ): Either[EraTxError, Vector[DijkstraTx[TxLevel.Sub]]] =
    for
      items <- V12.setItems(n, DecodeField.SubTransactions).leftMap(EraTxError.Decode(_))
      subs <- items.traverse(subTx)
      _ <- V12
        .unique(subs, DecodeField.SubTransactions)(_.id)
        .flatMap(V12.nonEmpty(_, DecodeField.SubTransactions))
        .leftMap(EraTxError.Decode(_))
    yield subs

  /** Key 24: `decodeRequiredTopLevelGuards` (`TxBody.hs:458-461`, cardano-ledger 9df9f98c), a
    * non-empty map of credentials, none twice, to Plutus `Data` or null, as the flat decoder reads
    * it, each datum decoded once ([[Data.read]]).
    */
  private def requiredTopLevelGuards(
      n: Node
  ): Either[EraTxError, Map[Credential[Guard], Option[Data]]] =
    V12
      .nonEmptyMap(n, DecodeField.RequiredTopLevelGuards)(
        FlatCredential.decode(_, DecodeField.RequiredTopLevelGuards)
      )((_, v) =>
        V12.nullable(v)(
          Data.read(_).leftMap(LedgerDecodeError.PlutusData(DecodeField.RequiredTopLevelGuards, _))
        )
      )
      .leftMap(EraTxError.Decode(_))
      .flatMap(_.traverse((c, d) => guard(c).tupleRight(d)))
      .map(_.toMap)

  /** A top-level body read from its item ([[read]]), with its sub-transactions read by `subTx`. */
  private[dijkstra] def readTop(
      n: Node,
      subTx: Node => Either[EraTxError, DijkstraTx[TxLevel.Sub]]
  )(using @unused proof: DijkstraTx.FromBytes): Either[EraTxError, DijkstraTxBody[TxLevel.Top]] =
    for
      r <- read(n, DijkstraLevel.Top, subTx)
      // the decoder refused a sub-transaction id twice and kept the order read
      // (`decodeNonEmptySetLikeEnforceNoDuplicatesAnn (flip OMap.|>)`), which the ordered map
      // refuses again (`TxBody.hs:467-471`, cardano-ledger 9df9f98c)
      subs <- OMap
        .decoded(r.subTransactions.map(s => s.id -> s))
        .toRight(EraTxError.Decode(LedgerDecodeError.DuplicateElement(DecodeField.SubTransactions)))
      // a top-level body requires a fee (`requiredFields`, `TxBody.hs:476-479`, cardano-ledger 9df9f98c)
      fee <- r.fee.toRight(
        EraTxError.Decode(
          LedgerDecodeError.MissingField(DecodeField.TransactionBody, DecodeField.Fee)
        )
      )
    yield TopTxBody(
      r.fields,
      r.collateralInputs,
      r.collateralReturn,
      r.totalCollateral,
      fee,
      subs,
      r.startingAccountBalanceIntervals
    )

  /** A sub-transaction's body read from its item ([[read]]), whose keys leave out the top level's.
    */
  private[dijkstra] def readSub(n: Node)(using
      @unused proof: DijkstraTx.FromBytes
  ): Either[EraTxError, DijkstraTxBody[TxLevel.Sub]] =
    // a sub-transaction's body has no key 23, so it reads no sub-transaction
    read(n, DijkstraLevel.Sub, _ => Left(EraTxError.OtherLevel)).map(r => SubTxBody(r.fields))

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
        Indexable
          .fromIndexMap(idx, txBody.withdrawals.unWithdrawals)
          .map(DijkstraWithdrawing(_))
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
      override def levelOf[L <: TxLevel](level: LedgerLevelTxInfo[L, Dijkstra]): BothLevels[L] =
        level match
          case LedgerLevelTxInfo.LedgerTopTxInfo(_) => BothLevels.Top
          case LedgerLevelTxInfo.LedgerSubTxInfo(_) => BothLevels.Sub
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
      ): Withdrawals = body.withdrawals
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
