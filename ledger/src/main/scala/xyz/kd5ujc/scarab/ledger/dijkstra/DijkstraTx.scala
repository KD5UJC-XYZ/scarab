// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{Credential, DecodeField, LedgerDecodeError, RewardAccount}

import V12.{Result, given}

/** An account balance interval of a body (`AccountBalanceInterval`,
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Scripts.hs:670-675`, cardano-ledger 9df9f98c):
  * an exact balance, or an inclusive lower bound, an exclusive upper bound, or both.
  */
enum DijkstraBalanceInterval:
  case Exact(coin: BigInt)
  case Lower(inclusive: BigInt)
  case Upper(exclusive: BigInt)
  case Both(inclusive: BigInt, exclusive: BigInt)

/** The level of a transaction: one of a block, or one of the sub-transactions of a body. */
enum DijkstraLevel:
  case Top
  case Sub

/** The body of a Dijkstra transaction or sub-transaction, decoded, its original bytes kept: the
  * transaction id is their Blake2b-256 digest. A field the reference keeps as an empty value when
  * it is absent is empty here; `fee` is absent only from a sub-transaction's body.
  */
final case class DijkstraTxBody(
    raw: Bytes,
    level: DijkstraLevel,
    inputs: Vector[DijkstraTxIn],
    outputs: Vector[DijkstraTxOut],
    fee: Option[BigInt],
    ttl: Option[BigInt],
    certificates: Vector[DijkstraCert],
    withdrawals: Vector[(RewardAccount, BigInt)],
    auxDataHash: Option[Bytes],
    validityStart: Option[BigInt],
    mint: MultiAsset,
    scriptIntegrityHash: Option[Bytes],
    collateral: Vector[DijkstraTxIn],
    guards: Vector[Credential],
    networkId: Option[Int],
    collateralReturn: Option[DijkstraTxOut],
    totalCollateral: Option[BigInt],
    referenceInputs: Vector[DijkstraTxIn],
    votingProcedures: Vector[
      (DijkstraVoter, Vector[(DijkstraGovActionId, DijkstraVotingProcedure)])
    ],
    proposals: Vector[DijkstraProposal],
    currentTreasuryValue: Option[BigInt],
    donation: BigInt,
    subTransactions: Vector[DijkstraSubTx],
    requiredTopLevelGuards: Vector[(Credential, Option[Bytes])],
    directDeposits: Vector[(RewardAccount, BigInt)],
    accountBalanceIntervals: Vector[(RewardAccount, DijkstraBalanceInterval)],
    startingAccountBalanceIntervals: Vector[(RewardAccount, DijkstraBalanceInterval)]
):
  lazy val id: Bytes = Blake2b.hash256.hash(raw)

/** A sub-transaction of a body: its body, witnesses and auxiliary data, its original bytes kept. */
final case class DijkstraSubTx(
    raw: Bytes,
    body: DijkstraTxBody,
    witnesses: DijkstraWitnesses,
    auxiliary: Option[DijkstraAuxData]
):
  def id: Bytes = body.id

/** A transaction of a Dijkstra block, decoded as the build of the public Leios testnet decodes it,
  * every part with its original bytes.
  */
final case class DijkstraTx(
    raw: Bytes,
    body: DijkstraTxBody,
    witnesses: DijkstraWitnesses,
    auxiliary: Option[DijkstraAuxData],
    isValid: Boolean
):
  def id: Bytes = body.id

object DijkstraTx:
  /** `decodeDijkstraTopTxInBlock` (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Tx.hs:175-182`,
    * cardano-ledger 9df9f98c): `decodeRecordNamed "DijkstraTx" (const 4)` of the body, the witness
    * set, the auxiliary data or null, and the validity flag, a boolean; the body, witnesses and
    * auxiliary data each memoize their original bytes.
    */
  def inBlock(n: Node): Result[DijkstraTx] =
    V12
      .record(n, 4, DecodeField.Transaction)
      .flatMap(xs =>
        (
          body(xs(0), DijkstraLevel.Top),
          DijkstraWitnesses.decode(xs(1)),
          V12.nullable(xs(2))(DijkstraAuxData.decode),
          V12.bool(xs(3), DecodeField.IsValid)
        ).mapN(DijkstraTx(n.original, _, _, _, _))
      )

  /** A transaction as a client or a peer submits it, the CBOR of `raw` read whole with no budget of
    * items, string bytes or nesting, as [[xyz.kd5ujc.scarab.ledger.Tx.fromEnvelope]] reads one of
    * an earlier era ([[submitted]]).
    */
  def fromSubmission(raw: Bytes): Result[DijkstraTx] =
    Cbor
      .decode(raw, Cbor.Limits.Unbounded)
      .leftMap(LedgerDecodeError.Syntax(_))
      .flatMap(submitted)

  /** `decodeDijkstraTopTx` (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Tx.hs:152-169`,
    * cardano-ledger 9df9f98c), the mempool form: `decodeRecordNamed "DijkstraTx"` of the body, the
    * witness set and the auxiliary data or null, three items, or with a boolean third item, which
    * the reference peeks at, four, that boolean then the validity flag before the auxiliary data. A
    * flag of `false` is refused ([[LedgerDecodeError.InvalidFlagSubmitted]]); a transaction read
    * here is valid. The order differs from the block form ([[inBlock]]), whose flag comes last.
    */
  def submitted(n: Node): Result[DijkstraTx] =
    V12.list(n, DecodeField.Transaction).flatMap { xs =>
      val flagged = xs
        .lift(2)
        .exists(_.value match
          case Value.Bool(_) => true
          case _             => false)
      val size = if flagged then 4 else 3
      for
        _ <- Either.cond(
          xs.size == size,
          (),
          LedgerDecodeError.ArrayLength(DecodeField.Transaction, size, xs.size)
        )
        _ <- Either.cond(
          !flagged || xs(2).value == Value.Bool(true),
          (),
          LedgerDecodeError.InvalidFlagSubmitted
        )
        tx <- (
          body(xs(0), DijkstraLevel.Top),
          DijkstraWitnesses.decode(xs(1)),
          V12.nullable(xs(size - 1))(DijkstraAuxData.decode)
        ).mapN(DijkstraTx(n.original, _, _, _, true))
      yield tx
    }

  /** `DijkstraSubTx` (`Tx.hs:184-193`): `decodeRecordNamed "DijkstraSubTx" (const 3)` of a body of
    * the sub-transaction level, a witness set and auxiliary data or null.
    */
  private def subTx(n: Node): Result[DijkstraSubTx] =
    V12
      .record(n, 3, DecodeField.SubTransaction)
      .flatMap(xs =>
        (
          body(xs(0), DijkstraLevel.Sub),
          DijkstraWitnesses.decode(xs(1)),
          V12.nullable(xs(2))(DijkstraAuxData.decode)
        ).mapN(DijkstraSubTx(n.original, _, _, _))
      )

  /** The keys of a body of each level and those it requires (`decoderByKey`, `requiredFields`,
    * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxBody.hs:347-479`): the fee (2), collateral
    * (13, 16, 17), sub-transactions (23) and starting balance intervals (27) are a top-level
    * transaction's only, and so is the requirement of a fee.
    */
  private def keys(level: DijkstraLevel): (Set[Int], Set[Int]) = level match
    case DijkstraLevel.Top =>
      (Set(0, 1, 2, 3, 4, 5, 7, 8, 9, 11, 13, 14, 15, 16, 17, 18) ++ (19 to 27), Set(0, 1, 2))
    case DijkstraLevel.Sub =>
      (Set(0, 1, 3, 4, 5, 7, 8, 9, 11, 14, 15, 18, 19, 20, 21, 22, 24, 25, 26), Set(0, 1))

  /** `DijkstraTxBodyRaw` (`TxBody.hs:340-479`): `decodeSparseKeyed` over the keys of the level. A
    * collection that is present must not be empty where the reference says `failOnNull` or
    * `failOnMempty` (certificates, withdrawals, mint, collateral, reference inputs, votes,
    * proposals, direct deposits and balance intervals) or uses a non-empty decoder
    * (sub-transactions, required guards); the guards (14) may be empty. The donation is a positive
    * coin (`decodePositiveCoin`).
    */
  def body(n: Node, level: DijkstraLevel): Result[DijkstraTxBody] =
    val (known, required) = keys(level)
    def coin(site: DecodeField)(x: Node) = V12.word64(x, site)
    def hash32(site: DecodeField)(x: Node) = V12.bytesN(x, 32, site)
    def nonEmpty[A](site: DecodeField)(r: Result[Vector[A]]) = r.flatMap(V12.nonEmpty(_, site))
    for
      f <- Sparse.read(n, DecodeField.TransactionBody, required)(known)
      inputs <- f.get(0).traverse(DijkstraTxIn.set(_, DecodeField.Inputs))
      outputs <- f.get(1).traverse(DijkstraTxOut.list)
      fee <- f.get(2).traverse(coin(DecodeField.Fee))
      ttl <- f.get(3).traverse(coin(DecodeField.Ttl))
      certs <- f.get(4).traverse(c => nonEmpty(DecodeField.Certificates)(DijkstraCert.set(c)))
      withdrawals <- f
        .get(5)
        .traverse(accountMap(_, DecodeField.Withdrawals)(coin(DecodeField.WithdrawalAmount)))
      auxHash <- f.get(7).traverse(hash32(DecodeField.AuxiliaryDataHash))
      start <- f.get(8).traverse(coin(DecodeField.ValidityStart))
      mint <- f.get(9).traverse(MultiAsset.decode(_, DecodeField.Mint)(MultiAsset.minted))
      integrity <- f.get(11).traverse(hash32(DecodeField.ScriptDataHash))
      collateral <- f.get(13).traverse(DijkstraTxIn.nonEmptySet(_, DecodeField.Collateral))
      guardSet <- f.get(14).traverse(guards)
      network <- f.get(15).traverse(networkId)
      collateralReturn <- f.get(16).traverse(DijkstraTxOut.decode)
      totalCollateral <- f.get(17).traverse(coin(DecodeField.TotalCollateral))
      references <- f.get(18).traverse(DijkstraTxIn.nonEmptySet(_, DecodeField.ReferenceInputs))
      votes <- f
        .get(19)
        .traverse(v => nonEmpty(DecodeField.VotingProcedures)(DijkstraVoting.procedures(v)))
      proposals <- f
        .get(20)
        .traverse(p => nonEmpty(DecodeField.ProposalProcedures)(DijkstraProposal.set(p)))
      treasury <- f.get(21).traverse(coin(DecodeField.CurrentTreasuryValue))
      donation <- f.get(22).traverse(positive)
      subTxs <- f.get(23).traverse(subTransactions)
      required <- f.get(24).traverse(requiredGuards)
      deposits <- f
        .get(25)
        .traverse(accountMap(_, DecodeField.DirectDeposits)(coin(DecodeField.DirectDeposits)))
      intervals <- f.get(26).traverse(accountMap(_, DecodeField.AccountBalanceIntervals)(interval))
      starting <- f.get(27).traverse(accountMap(_, DecodeField.AccountBalanceIntervals)(interval))
    yield DijkstraTxBody(
      n.original,
      level,
      inputs.orEmpty,
      outputs.orEmpty,
      fee,
      ttl,
      certs.orEmpty,
      withdrawals.orEmpty,
      auxHash,
      start,
      mint.getOrElse(Map.empty),
      integrity,
      collateral.orEmpty,
      guardSet.orEmpty,
      network,
      collateralReturn,
      totalCollateral,
      references.orEmpty,
      votes.orEmpty,
      proposals.orEmpty,
      treasury,
      donation.getOrElse(BigInt(0)),
      subTxs.orEmpty,
      required.orEmpty,
      deposits.orEmpty,
      intervals.orEmpty,
      starting.orEmpty
    )

  /** A non-empty map of account addresses, none twice (`decodeMap`, then `failOnNull`). */
  private def accountMap[A](n: Node, site: DecodeField)(
      value: Node => Result[A]
  ): Result[Vector[(RewardAccount, A)]] =
    V12.nonEmptyMap(n, site)(Account.decode(_, site))((_, v) => value(v))

  /** `decodePositiveCoin` (`libs/cardano-ledger-core/src/Cardano/Ledger/Coin.hs:226-232`): a
    * `Word64` other than zero.
    */
  private def positive(n: Node): Result[BigInt] =
    V12
      .word64(n, DecodeField.Donation)
      .flatMap(c => Either.cond(c != 0, c, LedgerDecodeError.Zero(DecodeField.Donation)))

  /** `Network` (`BaseTypes.hs:899-905`): a `Word8` that is 0 or 1. */
  private def networkId(n: Node): Result[Int] =
    V12
      .word8(n, DecodeField.NetworkId)
      .flatMap(v =>
        Either.cond(v <= 1, v, LedgerDecodeError.NumberOutOfRange(DecodeField.NetworkId, v, 0, 1))
      )

  /** `decodeGuards` (`TxBody.hs:1662-1678`): an `OSet` of credentials whose first element decides
    * the form of all: a list makes every element a credential, anything else every element a key
    * hash. No guard twice.
    */
  private def guards(n: Node): Result[Vector[Credential]] =
    V12.setItems(n, DecodeField.Guards).flatMap { xs =>
      val credentials = xs.headOption.exists(V12.isList)
      xs.traverse(x =>
        if credentials then Credential.decode(x, DecodeField.Guards)
        else V12.bytesN(x, 28, DecodeField.Guards).map(Credential.Key(_))
      ).flatMap(V12.unique(_, DecodeField.Guards)(identity))
    }

  /** `decodeRequiredTopLevelGuards` (`TxBody.hs:458-461`, `decodeNonEmptyMapNoDuplicatesAnn`,
    * `Decoding/Annotated.hs:282-293`): a non-empty map of credentials, none twice, to Plutus `Data`
    * or null, each datum kept as its bytes.
    */
  private def requiredGuards(n: Node): Result[Vector[(Credential, Option[Bytes])]] =
    V12.nonEmptyMap(n, DecodeField.RequiredTopLevelGuards)(
      Credential.decode(_, DecodeField.RequiredTopLevelGuards)
    )((_, v) =>
      V12.nullable(v)(d => DijkstraDatum.data(d, DecodeField.RequiredTopLevelGuards).as(d.original))
    )

  /** `AccountBalanceInterval`
    * (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Scripts.hs:684-696`): an unsigned integer is
    * an exact balance; anything else is `decodeRecordNamed` of a lower and an upper bound, each a
    * coin or null, not both null.
    */
  private def interval(n: Node): Result[DijkstraBalanceInterval] = n.value match
    case Value.UInt(_) =>
      V12.word64(n, DecodeField.AccountBalanceIntervals).map(DijkstraBalanceInterval.Exact(_))
    case _ =>
      V12
        .record(n, 2, DecodeField.AccountBalanceIntervals)
        .flatMap(xs =>
          (
            V12.nullable(xs(0))(V12.word64(_, DecodeField.AccountBalanceIntervals)),
            V12.nullable(xs(1))(V12.word64(_, DecodeField.AccountBalanceIntervals))
          ).tupled
        )
        .flatMap {
          case (None, None)    => Left(LedgerDecodeError.Empty(DecodeField.AccountBalanceIntervals))
          case (Some(l), None) => Right(DijkstraBalanceInterval.Lower(l))
          case (None, Some(u)) => Right(DijkstraBalanceInterval.Upper(u))
          case (Some(l), Some(u)) => Right(DijkstraBalanceInterval.Both(l, u))
        }

  /** `decodeSubTransactions` (`TxBody.hs:452-456`, `decodeNonEmptySetLikeEnforceNoDuplicatesAnn`,
    * `Decoding/Annotated.hs:251-277`): the tag 258 allowed, a non-empty list of sub-transactions,
    * no transaction id twice. A sub-transaction's body has no key 23, so they do not nest.
    */
  private def subTransactions(n: Node): Result[Vector[DijkstraSubTx]] =
    V12.nonEmptySet(n, DecodeField.SubTransactions)(subTx)(_.id)

end DijkstraTx
