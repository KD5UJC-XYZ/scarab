// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import cats.data.NonEmptyVector
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{Address, Credential, DRep, RewardAccount}
import xyz.kd5ujc.scarab.plutus.PlutusData
import xyz.kd5ujc.scarab.plutus.PlutusData.{B, I}

/** Where an output in a script context comes from (`TxOutSource`). */
enum DijkstraTxOutSource:
  case FromInput(input: DijkstraTxIn)
  case FromOutput(index: Int)

/** Why the PlutusV4 script context of a Dijkstra transaction cannot be built: the cases of
  * `DijkstraContextError` and of the earlier eras' errors it wraps that the PlutusV4 translation
  * raises (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs:155-178`, cardano-ledger
  * 9df9f98c), and [[Undecodable]], the node's own: the reference holds its data decoded.
  */
enum DijkstraContextError:
  /** `transLookupTxOut`: an input whose output is not in the UTxO. */
  case UnresolvedInput(input: DijkstraTxIn)

  /** A Byron address in an output of the context (`ByronTxOutInContext`). */
  case ByronTxOutInContext(source: DijkstraTxOutSource)

  /** A pointer address in an output of the context (`PointerPresentInOutput`). */
  case PointerPresentInOutput(source: DijkstraTxOutSource)

  /** `checkReferenceInputsNotDisjointFromInputs`: inputs that are also reference inputs. */
  case ReferenceInputsNotDisjointFromInputs(inputs: NonEmptyVector[DijkstraTxIn])

  /** `TimeTranslationPastHorizon`: a slot the epoch information cannot turn into time. */
  case TimeTranslationPastHorizon(slot: BigInt)

  /** `RedeemerPointerPointsToNothing`: a redeemer key that names no purpose of the body. */
  case RedeemerPointerPointsToNothing(tag: Int, index: Long)

  /** `ScriptHashNotFoundForPurpose`: a redeemer of a purpose no Plutus script is used for. */
  case ScriptHashNotFoundForPurpose(tag: Int, index: Long)

  /** `SubTxContextError`: the context of a sub-transaction could not be built. */
  case SubTxContextError(txId: Bytes, cause: DijkstraContextError)

  /** Plutus data of the transaction (a datum or a redeemer) that does not decode. */
  case Undecodable(raw: Bytes)

/** Why the scripts of a Dijkstra transaction cannot be collected with their contexts
  * (`CollectError`, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Plutus/Evaluate.hs`, the cases
  * `scriptsWithContextFromLedgerTxInfoWithResult` raises once the cost model is found).
  */
enum DijkstraCollectError:
  /** A purpose a Plutus script runs for has no redeemer. */
  case NoRedeemer(purpose: DijkstraPurpose)

  /** The script context could not be built. */
  case BadTranslation(cause: DijkstraContextError)

/** The PlutusV4 script contexts of Dijkstra transactions (`EraPlutusTxInfo 'PlutusV4 DijkstraEra`,
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs:574-1058`, cardano-ledger 9df9f98c),
  * in the encodings of plutus-ledger-api 1.71.0.0 (`261766ad`, `PlutusLedgerApi/V4/Contexts.hs`,
  * `Tx.hs`, `Address.hs`, `Time.hs`, `Ratio.hs`): a record is the list of its fields
  * (`makeIsDataSchemaAsList`, `ListEncoded`), a sum the constructor of its index, a `Maybe` `Just`
  * as constructor 0 and `Nothing` as 1, and a map in the order of the ledger map it translates.
  *
  * The contexts of PlutusV1 to PlutusV3 scripts in a Dijkstra transaction are not built here.
  */
object DijkstraScriptContexts:
  type Result[A] = Either[DijkstraContextError, A]

  /** What a context depends on besides the transaction: the outputs its inputs and reference inputs
    * spend, and slot to POSIX time in milliseconds (`transSlotToPOSIXTime`).
    */
  final case class Environment(
      utxo: Map[DijkstraTxIn, DijkstraTxOut],
      posix: BigInt => Result[BigInt]
  )

  /** A transaction or sub-transaction: a body and its witnesses. */
  final case class Transaction(body: DijkstraTxBody, witnesses: DijkstraWitnesses)

  object Transaction:
    def of(tx: DijkstraTx): Transaction = Transaction(tx.body, tx.witnesses)
    def of(tx: DijkstraSubTx): Transaction = Transaction(tx.body, tx.witnesses)

  /** The level of a transaction (`LedgerLevelTxInfo`): the top level, with the transaction
    * information of each of its sub-transactions (in body order, as `transGuardingTopTxInfo` reads
    * them), or a sub-transaction at its index.
    */
  enum Level:
    case Top(subTransactions: Vector[(Transaction, Map[(Int, Int), Bytes])])
    case Sub(index: Int)

  /** One PlutusV4 script context: the redeemer's purpose, the script's hash and the argument. */
  final case class Context(purpose: DijkstraPurpose, scriptHash: Bytes, argument: PlutusData)

  // -------------------------------------------------------------------------------------------
  // Encodings

  private def constr(tag: Int, fields: PlutusData*): PlutusData = PlutusData.constr(tag, fields*)
  private def list(fields: PlutusData*): PlutusData = PlutusData.List(fields.toVector)
  private def maybe(x: Option[PlutusData]): PlutusData = PlutusData.maybe(x)

  /** `Credential`: `PubKeyCredential` (0) or `ScriptCredential` (1). An `AccountId`, a DRep's, a
    * committee member's and a guard's credential encode as the credential.
    */
  private def credential(c: Credential): PlutusData = c match
    case Credential.Key(h)    => constr(0, B(h))
    case Credential.Script(h) => constr(1, B(h))

  /** `TxOutRef` (`ListEncoded`): the transaction id and the index. */
  private def outRef(in: DijkstraTxIn): PlutusData = list(B(in.id), I(in.index))

  private def rational(r: Rational): PlutusData = list(I(r.numerator), I(r.denominator))

  /** `transMultiAsset`: policies, then names, each in byte order. */
  private def multiAsset(m: MultiAsset): Vector[(PlutusData, PlutusData)] =
    m.toVector.sortBy(_._1).map { (policy, names) =>
      (B(policy): PlutusData) -> PlutusData.Map(
        names.toVector.sortBy(_._1).map((n, q) => (B(n): PlutusData) -> (I(q): PlutusData))
      )
    }

  /** `transValue`: the ada entry first, then the assets. */
  private def value(v: DijkstraValue): PlutusData =
    val ada = (B(Bytes.empty): PlutusData) -> PlutusData.Map(Vector(B(Bytes.empty) -> I(v.coin)))
    PlutusData.Map(ada +: multiAsset(v.assets))

  /** `transMintValue`: the assets only. */
  private def mintValue(m: MultiAsset): PlutusData = PlutusData.Map(multiAsset(m))

  private def data(raw: Bytes): Result[PlutusData] =
    PlutusData.decode(raw).leftMap(_ => DijkstraContextError.Undecodable(raw))

  /** `transTxOutV4` (`TxInfo.hs:650-690`): an `Address` of the payment credential and the staking
    * account of a base address; a pointer address and a Byron address are refused.
    */
  private def output(source: DijkstraTxOutSource, out: DijkstraTxOut): Result[PlutusData] =
    val address: Result[PlutusData] = out.address.kind match
      case Address.Kind.Shelley(_, payment, stake, pointer) =>
        if pointer.isDefined then Left(DijkstraContextError.PointerPresentInOutput(source))
        else Right(list(credential(payment), maybe(stake.map(credential))))
      case _ => Left(DijkstraContextError.ByronTxOutInContext(source))
    val datum: Result[PlutusData] = out.datum match
      case DijkstraDatum.NoDatum     => Right(constr(0))
      case DijkstraDatum.Hash(h)     => Right(constr(1, B(h)))
      case DijkstraDatum.Inline(raw) => data(raw).map(constr(2, _))
    (address, datum).mapN((a, d) =>
      list(a, value(out.value), d, maybe(out.referenceScript.map(s => B(s.hash))))
    )

  /** `transTxInInfoV4`: the input and the output it spends. */
  private def inputInfo(env: Environment)(in: DijkstraTxIn): Result[PlutusData] =
    env.utxo
      .get(in)
      .toRight(DijkstraContextError.UnresolvedInput(in))
      .flatMap(output(DijkstraTxOutSource.FromInput(in), _))
      .map(list(outRef(in), _))

  private def drep(d: DRep): PlutusData = d match
    case DRep.Registered(c)      => constr(0, credential(c))
    case DRep.AlwaysAbstain      => constr(1)
    case DRep.AlwaysNoConfidence => constr(2)

  private def delegatee(d: DijkstraDelegatee): PlutusData = d match
    case DijkstraDelegatee.Stake(pool)        => constr(0, B(pool))
    case DijkstraDelegatee.Vote(r)            => constr(1, drep(r))
    case DijkstraDelegatee.StakeVote(pool, r) => constr(2, B(pool), drep(r))

  /** `transTxCertV4` (`TxInfo.hs:701-730`). */
  private def txCert(c: DijkstraCert): PlutusData = c match
    case DijkstraCert.Register(cred, deposit)  => constr(0, credential(cred), I(deposit))
    case DijkstraCert.Unregister(cred, refund) => constr(1, credential(cred), I(refund))
    case DijkstraCert.Delegate(cred, d)        => constr(2, credential(cred), delegatee(d))
    case DijkstraCert.RegisterDelegate(cred, d, deposit) =>
      constr(3, credential(cred), delegatee(d), I(deposit))
    case DijkstraCert.RegisterDRep(cred, deposit, _) => constr(4, credential(cred), I(deposit))
    case DijkstraCert.UpdateDRep(cred, _)            => constr(5, credential(cred))
    case DijkstraCert.UnregisterDRep(cred, refund)   => constr(6, credential(cred), I(refund))
    case DijkstraCert.RegisterPool(params)           => constr(7, B(params.operator), B(params.vrf))
    case DijkstraCert.RetirePool(pool, epoch)        => constr(8, B(pool), I(epoch))
    case DijkstraCert.AuthorizeCommitteeHotKey(cold, hot) =>
      constr(9, credential(cold), credential(hot))
    case DijkstraCert.ResignCommitteeColdKey(cold, _) => constr(10, credential(cold))

  private def voter(v: DijkstraVoter): PlutusData = v match
    case DijkstraVoter.Committee(c) => constr(0, credential(c))
    case DijkstraVoter.DRep(c)      => constr(1, credential(c))
    case DijkstraVoter.StakePool(h) => constr(2, B(h))

  /** `GovernanceActionId` (`ListEncoded`). */
  private def govActionId(id: DijkstraGovActionId): PlutusData = list(B(id.txId), I(id.index))

  /** `ToPlutusData (PParamsUpdate DijkstraEra)` (`Conway/PParams.hs:200-206`): the fields of the
    * update by tag, in the order of `eraPParams` (`Dijkstra/PParams.hs:741-796`), which is the
    * order of the tags.
    */
  def changedParameters(update: DijkstraParamUpdate): PlutusData =
    def encoded(v: DijkstraParamValue): PlutusData = v match
      case DijkstraParamValue.Number(n)         => I(n)
      case DijkstraParamValue.Ratio(r)          => rational(r)
      case DijkstraParamValue.OptionalRatio(r)  => maybe(r.map(rational))
      case DijkstraParamValue.OptionalNumber(n) => maybe(n.map(x => I(BigInt(x))))
      case DijkstraParamValue.CostModels(models) =>
        PlutusData.Map(models.toVector.sortBy(_._1).map { (language, values) =>
          (I(BigInt(language)): PlutusData) -> PlutusData.List(values.map(x => I(BigInt(x))))
        })
      case DijkstraParamValue.Pair(a, b)       => list(rational(a), rational(b))
      case DijkstraParamValue.ExUnits(mem, st) => list(I(mem), I(st))
      case DijkstraParamValue.Thresholds(rs)   => PlutusData.List(rs.map(rational))
    PlutusData.Map(update.fields.toVector.sortBy(_._1).map { (tag, v) =>
      (I(BigInt(tag)): PlutusData) -> encoded(v)
    })

  /** `transGovAction` (`TxInfo.hs:1016-1051`). */
  private def govAction(a: DijkstraGovAction): PlutusData =
    def previous(id: Option[DijkstraGovActionId]) = maybe(id.map(govActionId))
    def policy(h: Option[Bytes]) = maybe(h.map(B(_)))
    a match
      case DijkstraGovAction.ParameterChange(prev, update, p) =>
        constr(0, previous(prev), changedParameters(update), policy(p))
      case DijkstraGovAction.HardForkInitiation(prev, major, minor) =>
        constr(1, previous(prev), list(I(major), I(minor)))
      case DijkstraGovAction.TreasuryWithdrawals(withdrawals, p) =>
        val sorted = withdrawals.toVector.sortBy(_._1)(using DijkstraPurposes.accountOrder)
        constr(
          2,
          PlutusData.Map(sorted.map((a, c) => credential(a.credential) -> (I(c): PlutusData))),
          policy(p)
        )
      case DijkstraGovAction.NoConfidence(prev) => constr(3, previous(prev))
      case DijkstraGovAction.UpdateCommittee(prev, removed, added, threshold) =>
        constr(
          4,
          previous(prev),
          PlutusData.List(
            removed.toVector.sorted(using DijkstraPurposes.credentialOrder).map(credential)
          ),
          PlutusData.Map(
            added.toVector
              .sortBy(_._1)(using DijkstraPurposes.credentialOrder)
              .map((c, e) => credential(c) -> (I(e): PlutusData))
          ),
          rational(threshold)
        )
      case DijkstraGovAction.NewConstitution(prev, _, p) =>
        constr(5, previous(prev), list(policy(p)))
      case DijkstraGovAction.InfoAction => constr(6)

  /** `transProposal` (`TxInfo.hs:997-1007`, `ListEncoded`): the deposit, the return account's
    * credential and the action.
    */
  private def proposal(p: DijkstraProposal): PlutusData =
    list(I(p.deposit), credential(p.returnAccount.credential), govAction(p.action))

  /** `transVotingProcedures`: voters, then actions, in the ledger's orders. */
  private def votes(body: DijkstraTxBody): PlutusData =
    votesOf(body.votingProcedures.map((v, ps) => v -> ps.map((id, p) => id -> p.vote).toMap).toMap)

  private def votesOf(procedures: Map[DijkstraVoter, Map[DijkstraGovActionId, Int]]): PlutusData =
    PlutusData.Map(
      procedures.toVector.sortBy(_._1)(using DijkstraPurposes.voterOrder).map { (v, ps) =>
        voter(v) -> PlutusData.Map(
          ps.toVector
            .sortBy(_._1)(using DijkstraPurposes.govActionOrder)
            .map((id, vote) => govActionId(id) -> constr(vote))
        )
      }
    )

  /** `transMap transAccountAddressToCredential transCoinToLovelace`. */
  private def accountCoins(entries: Vector[(RewardAccount, BigInt)]): PlutusData =
    PlutusData.Map(
      entries
        .sortBy(_._1)(using DijkstraPurposes.accountOrder)
        .map((a, c) => credential(a.credential) -> (I(c): PlutusData))
    )

  /** `transAccountBalanceIntervals` (`TxInfo.hs:758-768`). */
  private def balanceIntervals(
      entries: Vector[(RewardAccount, DijkstraBalanceInterval)]
  ): PlutusData =
    def interval(i: DijkstraBalanceInterval): PlutusData = i match
      case DijkstraBalanceInterval.Lower(l)   => constr(0, I(l))
      case DijkstraBalanceInterval.Upper(u)   => constr(1, I(u))
      case DijkstraBalanceInterval.Both(l, u) => constr(2, I(l), I(u))
      case DijkstraBalanceInterval.Exact(c)   => constr(3, I(c))
    PlutusData.Map(
      entries
        .sortBy(_._1)(using DijkstraPurposes.accountOrder)
        .map((a, i) => credential(a.credential) -> interval(i))
    )

  /** `transValidityInterval` (`TxInfo.hs:745-756`): the inclusive start and the exclusive end. */
  private def validRange(
      start: Option[BigInt],
      end: Option[BigInt],
      env: Environment
  ): Result[PlutusData] =
    (start.traverse(env.posix), end.traverse(env.posix)).mapN((s, e) =>
      list(maybe(s.map(I(_))), maybe(e.map(I(_))))
    )

  /** `transPlutusPurposeV4` (`TxInfo.hs:958-990`). */
  private def scriptPurpose(p: DijkstraPurpose, hash: Bytes): PlutusData =
    val sh = B(hash)
    p match
      case DijkstraPurpose.Minting(_, policy)  => constr(0, sh, B(policy))
      case DijkstraPurpose.Spending(_, in)     => constr(1, sh, outRef(in))
      case DijkstraPurpose.Withdrawing(_, a)   => constr(2, sh, credential(a.credential))
      case DijkstraPurpose.Certifying(ix, c)   => constr(3, sh, I(ix), txCert(c))
      case DijkstraPurpose.Voting(_, v)        => constr(4, sh, voter(v))
      case DijkstraPurpose.Proposing(ix, prop) => constr(5, sh, I(ix), proposal(prop))
      case DijkstraPurpose.Guarding(ix, _)     => constr(6, sh, I(ix))

  /** The redeemers of a transaction with their purposes and scripts (`transTxRedeemers`,
    * `Babbage/TxInfo.hs:215-229`, and `transRedeemerPointerV2V3`, `:195-212`), in the order of the
    * ledger's redeemer map: by tag, then index.
    */
  private def redeemers(
      tx: Transaction,
      used: Map[(Int, Int), Bytes]
  ): Result[Vector[(DijkstraPurpose, Bytes, DijkstraRedeemer)]] =
    // the ledger's redeemer map keeps the last of two redeemers of one key
    tx.witnesses.redeemers
      .groupMapReduce(r => (r.tag, r.index))(identity)((_, last) => last)
      .values
      .toVector
      .sortBy(r => (r.tag, r.index))
      .traverse { r =>
        for
          purpose <- DijkstraPurposes
            .inverse(tx.body, r.tag, r.index)
            .toRight(DijkstraContextError.RedeemerPointerPointsToNothing(r.tag, r.index))
          hash <- used
            .get(purpose.key)
            .toRight(DijkstraContextError.ScriptHashNotFoundForPurpose(r.tag, r.index))
        yield (purpose, hash, r)
      }

  private def datumMap(tx: Transaction): Result[Vector[(Bytes, PlutusData)]] =
    tx.witnesses.datums
      .traverse(raw => data(raw).map(DijkstraDatum.hash(raw) -> _))
      .map(_.toMap.toVector.sortBy(_._1))

  /** The translation of one transaction that the top level's aggregate reuses. */
  private final case class Info(
      encoded: PlutusData,
      id: Bytes,
      inputs: Vector[PlutusData],
      referenceInputs: Vector[PlutusData],
      outputs: Vector[PlutusData],
      certificates: Vector[PlutusData],
      guards: Vector[PlutusData],
      redeemerScripts: Vector[Bytes],
      proposals: Vector[PlutusData],
      currentTreasury: Option[BigInt]
  )

  /** `toPlutusTxInfo` of PlutusV4 (`TxInfo.hs:577-626`). `used` is the script hash of each purpose
    * the transaction runs a Plutus script for (`ltiScriptHashesUsed`), by redeemer key.
    */
  private def info(
      tx: Transaction,
      subIndex: Option[Int],
      used: Map[(Int, Int), Bytes],
      env: Environment
  ): Result[Info] =
    val body = tx.body
    for
      range <- validRange(body.validityStart, body.ttl, env)
      ins <- DijkstraPurposes.sortedInputs(body).traverse(inputInfo(env))
      refs <- body.referenceInputs
        .sorted(using DijkstraPurposes.inputOrder)
        .traverse(inputInfo(env))
      overlap = NonEmptyVector.fromVector(
        body.inputs.toSet
          .intersect(body.referenceInputs.toSet)
          .toVector
          .sorted(using DijkstraPurposes.inputOrder)
      )
      _ <- overlap.fold(Right(()))(o =>
        Left(DijkstraContextError.ReferenceInputsNotDisjointFromInputs(o))
      )
      outs <- body.outputs.zipWithIndex.traverse((o, ix) =>
        output(DijkstraTxOutSource.FromOutput(ix), o)
      )
      rdmrs <- redeemers(tx, used)
      rdmrData <- rdmrs.traverse((p, h, r) => data(r.data).map(d => scriptPurpose(p, h) -> d))
      datums <- datumMap(tx)
      guardDatums <- body.requiredTopLevelGuards
        .sortBy(_._1)(using DijkstraPurposes.credentialOrder)
        .traverse((c, d) => d.traverse(data).map(x => credential(c) -> maybe(x)))
    yield
      val certs = body.certificates.map(txCert)
      val guards = body.guards.map(credential)
      val proposals = body.proposals.map(proposal)
      val encoded = list(
        B(body.id),
        maybe(subIndex.map(ix => I(ix))),
        PlutusData.List(ins),
        PlutusData.List(refs),
        PlutusData.List(outs),
        mintValue(body.mint),
        PlutusData.List(certs),
        accountCoins(body.withdrawals),
        accountCoins(body.directDeposits),
        balanceIntervals(body.accountBalanceIntervals),
        range,
        PlutusData.List(guards),
        PlutusData.Map(guardDatums),
        PlutusData.Map(rdmrData),
        PlutusData.Map(datums.map((h, d) => (B(h): PlutusData) -> d)),
        votes(body),
        PlutusData.List(proposals),
        maybe(body.currentTreasuryValue.map(I(_))),
        I(body.donation)
      )
      Info(
        encoded,
        body.id,
        ins,
        refs,
        outs,
        certs,
        guards,
        rdmrs.map(_._2),
        proposals,
        body.currentTreasuryValue
      )

  /** `transGuardingTopTxInfo` (`TxInfo.hs:819-925`): the sub-transactions' information and an
    * aggregate of the whole transaction, sub-transactions first and the top level last.
    */
  private def topTxInfo(
      top: Transaction,
      topInfo: Info,
      guardScript: Bytes,
      subs: Vector[(Transaction, Map[(Int, Int), Bytes])],
      env: Environment
  ): Result[PlutusData] =
    val batch = subs.map(_._1) :+ top
    def guardDatum(tx: Transaction): Result[Option[(Bytes, PlutusData)]] =
      tx.body.requiredTopLevelGuards
        .collectFirst { case (Credential.Script(h), Some(raw)) if h == guardScript => raw }
        .traverse(raw => data(raw).map(tx.body.id -> _))
    def sumByAccount(entries: Vector[(RewardAccount, BigInt)]): Vector[(RewardAccount, BigInt)] =
      entries.groupMapReduce(_._1)(_._2)(_ + _).toVector
    def mintsWith(keep: BigInt => Boolean): MultiAsset =
      batch
        .flatMap(_.body.mint.toVector)
        .flatMap((p, names) => names.toVector.map((n, q) => (p, n, q)))
        .filter((_, _, q) => keep(q))
        .groupMapReduce((p, n, _) => (p, n))(_._3)(_ + _)
        .toVector
        .groupMap(_._1._1)((k, q) => k._2 -> q)
        .map((p, ns) => p -> ns.toMap)
    val start = batch.flatMap(_.body.validityStart).maxOption
    val end = batch.flatMap(_.body.ttl).minOption
    for
      subInfos <- subs.zipWithIndex.traverse { case ((sub, used), ix) =>
        info(sub, Some(ix), used, env).leftMap(
          DijkstraContextError.SubTxContextError(sub.body.id, _)
        )
      }
      batchRange <- validRange(start, end, env)
      guardDatums <- batch.traverse(guardDatum).map(_.flatten)
      batchDatums <- batch.traverse(datumMap).map(_.flatten.toMap.toVector.sortBy(_._1))
    yield
      val infos = subInfos :+ topInfo
      val votesByVoter = batch
        .map(tx =>
          tx.body.votingProcedures.map((v, ps) => v -> ps.map((id, p) => id -> p.vote).toMap).toMap
        )
        .foldLeft(Map.empty[DijkstraVoter, Map[DijkstraGovActionId, Int]]) { (acc, next) =>
          next.foldLeft(acc) { case (m, (v, ps)) => m.updated(v, m.getOrElse(v, Map.empty) ++ ps) }
        }
      val requiredGuards = batch
        .flatMap(_.body.requiredTopLevelGuards.map(_._1))
        .distinct
        .sorted(using DijkstraPurposes.credentialOrder)
      val simplified = list(
        PlutusData.List(infos.map(i => B(i.id))),
        PlutusData.List(infos.flatMap(_.inputs)),
        PlutusData.List(infos.flatMap(_.referenceInputs)),
        PlutusData.List(infos.flatMap(_.outputs)),
        mintValue(mintsWith(_ > 0)),
        mintValue(mintsWith(_ < 0)),
        PlutusData.List(infos.flatMap(_.certificates)),
        accountCoins(sumByAccount(batch.flatMap(_.body.withdrawals))),
        accountCoins(sumByAccount(batch.flatMap(_.body.directDeposits))),
        batchRange,
        PlutusData.List(infos.flatMap(_.guards)),
        PlutusData.List(requiredGuards.map(credential)),
        PlutusData.List(infos.flatMap(_.redeemerScripts).distinct.sorted.map(B(_))),
        PlutusData.Map(batchDatums.map((h, d) => (B(h): PlutusData) -> d)),
        votesOf(votesByVoter),
        PlutusData.List(infos.flatMap(_.proposals)),
        maybe(infos.flatMap(_.currentTreasury).headOption.map(I(_))),
        I(batch.map(_.body.donation).sum)
      )
      list(
        PlutusData.List(subInfos.map(_.encoded)),
        PlutusData.Map(
          guardDatums.toMap.toVector.sortBy(_._1).map((id, d) => (B(id): PlutusData) -> d)
        ),
        balanceIntervals(top.body.startingAccountBalanceIntervals),
        simplified
      )

  /** The datum of a spent output (`getBabbageSpendingDatum`): inline, or by hash among the
    * transaction's datums.
    */
  private def spendingDatum(
      tx: Transaction,
      in: DijkstraTxIn,
      env: Environment
  ): Result[Option[PlutusData]] =
    env.utxo.get(in).map(_.datum) match
      case Some(DijkstraDatum.Inline(raw)) => data(raw).map(Some(_))
      case Some(DijkstraDatum.Hash(h)) =>
        tx.witnesses.datums.find(d => DijkstraDatum.hash(d) == h).traverse(data)
      case _ => Right(None)

  /** A script the transaction runs, collected with its argument: a PlutusV4 script's context, or a
    * PlutusV1 to PlutusV3 script, whose context in a Dijkstra transaction is not built here.
    */
  enum Collected:
    case V4(context: Context)
    case Untranslated(purpose: DijkstraPurpose, scriptHash: Bytes, language: DijkstraLanguage)

  type Collection = Either[NonEmptyVector[DijkstraCollectError], Vector[Collected]]

  /** The scripts of a transaction with their arguments, as `scriptsWithContextFromLedgerTxInfo
    * WithResult` collects them (`Evaluate.hs:131-176`): for each script in `scripts`, its redeemer,
    * then its context (`toPlutusV4Args`, `TxInfo.hs:927-956`): the list of the transaction
    * information, the redeemer, the `ScriptInfo` and the script's hash. The transaction information
    * is built once, for the first script that needs it. As in the reference, the result lists the
    * scripts, or every failure, in the reverse of their order in `scripts` (each is consed onto the
    * list).
    *
    * @param used
    *   the script hash of every purpose the transaction runs a Plutus script for, by redeemer key
    *   (`ltiScriptHashesUsed`)
    * @param scripts
    *   the purposes the transaction runs a Plutus script for, with the script's hash and language,
    *   in the order of the scripts needed (`ltiScriptsUsed`)
    */
  def contexts(
      tx: Transaction,
      level: Level,
      used: Map[(Int, Int), Bytes],
      scripts: Vector[(DijkstraPurpose, Bytes, DijkstraLanguage)],
      env: Environment
  ): Collection =
    val subIndex = level match
      case Level.Top(_)  => None
      case Level.Sub(ix) => Some(ix)
    lazy val txInfo = info(tx, subIndex, used, env)
    def v4(purpose: DijkstraPurpose, hash: Bytes, redeemer: DijkstraRedeemer): Result[Context] =
      for
        ti <- txInfo
        rdmr <- data(redeemer.data)
        scriptInfo <- purpose match
          case DijkstraPurpose.Spending(_, in) =>
            spendingDatum(tx, in, env).map(d => constr(1, outRef(in), maybe(d)))
          case DijkstraPurpose.Minting(_, policy) => Right(constr(0, B(policy)))
          case DijkstraPurpose.Withdrawing(_, a)  => Right(constr(2, credential(a.credential)))
          case DijkstraPurpose.Certifying(ix, c)  => Right(constr(3, I(ix), txCert(c)))
          case DijkstraPurpose.Voting(_, v)       => Right(constr(4, voter(v)))
          case DijkstraPurpose.Proposing(ix, p)   => Right(constr(5, I(ix), proposal(p)))
          case DijkstraPurpose.Guarding(ix, _) =>
            level match
              case Level.Top(subs) =>
                topTxInfo(tx, ti, hash, subs, env).map(t => constr(6, I(ix), maybe(Some(t))))
              case Level.Sub(_) => Right(constr(6, I(ix), maybe(None)))
      yield Context(purpose, hash, list(ti.encoded, rdmr, scriptInfo, B(hash)))
    val each: Vector[Either[DijkstraCollectError, Collected]] = scripts.map {
      (purpose, hash, language) =>
        tx.witnesses.redeemers
          .findLast(r => r.tag == purpose.tag && r.index == purpose.index.toLong)
          .toRight(DijkstraCollectError.NoRedeemer(purpose))
          .flatMap { redeemer =>
            language match
              case DijkstraLanguage.PlutusV4 =>
                v4(purpose, hash, redeemer)
                  .bimap(DijkstraCollectError.BadTranslation(_), Collected.V4(_))
              case other => Right(Collected.Untranslated(purpose, hash, other))
          }
    }
    NonEmptyVector
      .fromVector(each.collect { case Left(e) => e }.reverse)
      .toLeft(each.collect { case Right(c) => c }.reverse)

  /** The Plutus scripts a transaction provides, by hash (`getDijkstraScriptsProvided`,
    * `Dijkstra/UTxO.hs:167-186`, and `getBabbageScriptsProvided`, `Babbage/UTxO.hs:136-147`): the
    * reference scripts of the outputs its inputs and reference inputs spend, and its witness
    * scripts; for a top-level transaction, those of each of its sub-transactions too.
    */
  def plutusScriptsProvided(
      tx: DijkstraTx,
      utxo: Map[DijkstraTxIn, DijkstraTxOut]
  ): Map[Bytes, DijkstraLanguage] =
    def of(body: DijkstraTxBody, witnesses: DijkstraWitnesses): Vector[DijkstraScript] =
      (body.referenceInputs ++ body.inputs).flatMap(utxo.get).flatMap(_.referenceScript) ++
        witnesses.scripts
    (of(tx.body, tx.witnesses) ++ tx.body.subTransactions.flatMap(s =>
      of(s.body, s.witnesses)
    )).collect { case script @ DijkstraScript.Plutus(language, _) => script.hash -> language }.toMap

  /** The scripts of a top-level transaction and of each of its sub-transactions with their
    * arguments, as `mkDijkstraStAnnTopTx` and `mkDijkstraStAnnSubTx` collect them
    * (`Dijkstra.hs:113-215`): the purposes each one needs a script for (`getDijkstraScriptsNeeded`)
    * whose script it provides (`resolveNeededPlutusScriptsWithPurpose`), the sub-transactions
    * resolved against the scripts the whole transaction provides ([[contexts]]).
    */
  def transaction(tx: DijkstraTx, env: Environment): (Collection, Vector[Collection]) =
    val provided = plutusScriptsProvided(tx, env.utxo)
    def resolved(body: DijkstraTxBody): Vector[(DijkstraPurpose, Bytes, DijkstraLanguage)] =
      DijkstraPurposes
        .scriptsNeeded(env.utxo, body)
        .flatMap((p, h) => provided.get(h).map((p, h, _)))
    def used(rs: Vector[(DijkstraPurpose, Bytes, DijkstraLanguage)]): Map[(Int, Int), Bytes] =
      rs.map((p, h, _) => p.key -> h).toMap
    val subs = tx.body.subTransactions.map(s => (Transaction.of(s), resolved(s.body)))
    val top = resolved(tx.body)
    val topCollection = contexts(
      Transaction.of(tx),
      Level.Top(subs.map((t, rs) => t -> used(rs))),
      used(top),
      top,
      env
    )
    val subCollections = subs.zipWithIndex.map { case ((t, rs), ix) =>
      contexts(t, Level.Sub(ix), used(rs), rs, env)
    }
    (topCollection, subCollections)
