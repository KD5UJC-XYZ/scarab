// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.plutus.PlutusData

// The types of the Plutus V4 script context that the ledger translates a Dijkstra transaction to, as
// plutus-ledger-api 1.71.0.0 defines them (plutus 261766ad, `plutus-ledger-api/src/PlutusLedgerApi/V4`), the
// version the Leios testnet's node links, with their `ToData` encodings: a record of
// `makeIsDataSchemaAsList` and a newtype derived `via ListEncoded` is the list of its fields
// (`V4/Internal.hs:15-21`: the fields of its constructor 0); a sum of `makeIsDataSchemaIndexed` is the
// constructor of its index with its fields; a type V4 takes from V1 to V3 is that version's, with its encoding.

/** The types of `PlutusLedgerApi.V4` (plutus 261766ad) that the ledger's Plutus V4 translation
  * builds.
  */
object PV4:
  import ToData.toData
  import PV1.given
  import PV2.given
  import PV3.given

  private def list(fields: PlutusData*): PlutusData = PlutusData.List(fields.toVector)

  /** `newtype AccountId` (`V4/Address.hs:48-60`, plutus 261766ad): a credential, encoded as it is.
    */
  final case class AccountId(credential: PV1.Credential)

  /** `data Address` of V4 (`V4/Address.hs:74-81,129`, plutus 261766ad): the payment credential and
    * the account the funds are staked to, a list.
    */
  final case class Address(
      addressCredential: PV1.Credential,
      addressStakingAccountId: Option[AccountId]
  )

  /** `newtype TxOutRef` of V4 (`V4/Tx.hs:56-61`, plutus 261766ad): V3's, encoded as a list. */
  final case class TxOutRef(txOutRefId: PV3.TxId, txOutRefIdx: BigInt)

  /** `data TxOut` of V4 (`V4/Tx.hs:77-84,125`, plutus 261766ad): V2's fields with V4's address, a
    * list.
    */
  final case class TxOut(
      txOutAddress: Address,
      txOutValue: PV1.Value,
      txOutDatum: PV2.OutputDatum,
      txOutReferenceScript: Option[PV1.ScriptHash]
  )

  /** `data POSIXTimeRange` of V4 (`V4/Time.hs:30-37,50`, plutus 261766ad): the inclusive start and
    * the exclusive end, either open, a list.
    */
  final case class POSIXTimeRange(
      fromInclusive: Option[PV1.POSIXTime],
      untilExclusive: Option[PV1.POSIXTime]
  )

  /** `newtype Rational` of V4 (`V4/Ratio.hs:35-56`, plutus 261766ad): plutus-tx's ratio
    * ([[PV3.Rational]]), encoded as the list of its numerator and denominator.
    */
  final case class Rational(unRational: PV3.Rational)

  /** `newtype GovernanceActionId` of V4 (`V4/Contexts.hs:107-112`, plutus 261766ad): V3's, encoded
    * as a list.
    */
  final case class GovernanceActionId(gaidTxId: PV3.TxId, gaidGovActionIx: BigInt)

  /** `newtype ProtocolVersion` of V4 (`V4/Contexts.hs:130-135`, plutus 261766ad): V3's, encoded as
    * a list.
    */
  final case class ProtocolVersion(pvMajor: BigInt, pvMinor: BigInt)

  /** `newtype Constitution` of V4 (`V4/Contexts.hs:152-158`, plutus 261766ad): its guardrails
    * script, encoded as a list.
    */
  final case class Constitution(constitutionScript: Option[PV1.ScriptHash])

  /** `newtype Committee` of V4 (`V4/Contexts.hs:171-179`, plutus 261766ad): the members with their
    * terms and the quorum (`CommitteeValue`, a pair), encoded as a list (`ListEncoded`); its
    * pattern's fields are [[committeeMembers]] and [[committeeQuorum]].
    */
  final case class Committee(
      committeeValue: (AssocMap[PV3.ColdCommitteeCredential, BigInt], Rational)
  ):
    def committeeMembers: AssocMap[PV3.ColdCommitteeCredential, BigInt] = committeeValue._1
    def committeeQuorum: Rational = committeeValue._2

  /** `data GovernanceAction` of V4 (`V4/Contexts.hs:200-226`, plutus 261766ad): V3's actions with
    * V4's identifiers, version, constitution and ratio.
    */
  enum GovernanceAction:
    case ParameterChange(
        parent: Option[GovernanceActionId],
        changed: PV3.ChangedParameters,
        script: Option[PV1.ScriptHash]
    )
    case HardForkInitiation(parent: Option[GovernanceActionId], version: ProtocolVersion)
    case TreasuryWithdrawals(
        withdrawals: AssocMap[PV1.Credential, PV1.Lovelace],
        script: Option[PV1.ScriptHash]
    )
    case NoConfidence(parent: Option[GovernanceActionId])
    case UpdateCommittee(
        parent: Option[GovernanceActionId],
        removed: Vector[PV3.ColdCommitteeCredential],
        added: AssocMap[PV3.ColdCommitteeCredential, BigInt],
        quorum: Rational
    )
    case NewConstitution(parent: Option[GovernanceActionId], constitution: Constitution)
    case InfoAction

  /** `newtype ProposalProcedure` of V4 (`V4/Contexts.hs:228-233`, plutus 261766ad): the deposit,
    * the return account and the action, encoded as a list.
    */
  final case class ProposalProcedure(
      ppDeposit: PV1.Lovelace,
      ppReturnAddr: PV1.Credential,
      ppGovernanceAction: GovernanceAction
  )

  /** `data AccountBalanceInterval` (`V4/Contexts.hs:261-280`, plutus 261766ad). */
  enum AccountBalanceInterval:
    case AccountBalanceLowerBound(lower: PV1.Lovelace)
    case AccountBalanceUpperBound(upper: PV1.Lovelace)
    case AccountBalanceBothBounds(lower: PV1.Lovelace, upper: PV1.Lovelace)
    case AccountBalanceExact(exact: PV1.Lovelace)

  /** `newtype AccountBalanceIntervals` (`V4/Contexts.hs:282-293`, plutus 261766ad): its map,
    * encoded as it is.
    */
  final case class AccountBalanceIntervals(intervals: AssocMap[AccountId, AccountBalanceInterval])

  /** `data TxCert` of V4 (`V4/Contexts.hs:305-319,622-636`, plutus 261766ad): an account's
    * certificates by its `AccountId`, every deposit and refund given.
    */
  enum TxCert:
    case TxCertRegAccount(account: AccountId, deposit: PV1.Lovelace)
    case TxCertUnRegAccount(account: AccountId, refund: PV1.Lovelace)
    case TxCertDelegAccount(account: AccountId, delegatee: PV3.Delegatee)
    case TxCertRegAccountDeleg(account: AccountId, delegatee: PV3.Delegatee, deposit: PV1.Lovelace)
    case TxCertRegDRep(credential: PV3.DRepCredential, deposit: PV1.Lovelace)
    case TxCertUpdateDRep(credential: PV3.DRepCredential)
    case TxCertUnRegDRep(credential: PV3.DRepCredential, refund: PV1.Lovelace)
    case TxCertPoolRegister(poolId: PV1.PubKeyHash, poolVfr: PV1.PubKeyHash)
    case TxCertPoolRetire(poolId: PV1.PubKeyHash, epoch: BigInt)
    case TxCertAuthHotCommittee(cold: PV3.ColdCommitteeCredential, hot: PV3.HotCommitteeCredential)
    case TxCertResignColdCommittee(cold: PV3.ColdCommitteeCredential)

  /** `data ScriptPurpose` of V4 (`V4/Contexts.hs:323-333,639-649`, plutus 261766ad): each purpose
    * with the hash of the script it runs, and the guarding purpose.
    */
  enum ScriptPurpose:
    case Minting(scriptHash: PV1.ScriptHash, currencySymbol: PV1.CurrencySymbol)
    case Spending(scriptHash: PV1.ScriptHash, txOutRef: TxOutRef)
    case Withdrawing(scriptHash: PV1.ScriptHash, credential: PV1.Credential)
    case Certifying(scriptHash: PV1.ScriptHash, index: BigInt, txCert: TxCert)
    case Voting(scriptHash: PV1.ScriptHash, voter: PV3.Voter)
    case Proposing(scriptHash: PV1.ScriptHash, index: BigInt, proposal: ProposalProcedure)
    case Guarding(scriptHash: PV1.ScriptHash, index: BigInt)

  /** `data TxInInfo` of V4 (`V4/Contexts.hs:336-341,652`, plutus 261766ad), a list. */
  final case class TxInInfo(txInInfoOutRef: TxOutRef, txInInfoResolved: TxOut)

  /** `data TxInfo` of V4 (`V4/Contexts.hs:349-371,655`, plutus 261766ad): a transaction or a
    * sub-transaction at its index, a list.
    */
  final case class TxInfo(
      txInfoId: PV3.TxId,
      txInfoSubTxIx: Option[BigInt],
      txInfoInputs: Vector[TxInInfo],
      txInfoReferenceInputs: Vector[TxInInfo],
      txInfoOutputs: Vector[TxOut],
      txInfoMint: PV3.MintValue,
      txInfoTxCerts: Vector[TxCert],
      txInfoWithdrawals: AssocMap[PV1.Credential, PV1.Lovelace],
      txInfoDirectDeposits: AssocMap[PV1.Credential, PV1.Lovelace],
      txInfoAccountBalanceIntervals: AccountBalanceIntervals,
      txInfoValidRange: POSIXTimeRange,
      txInfoGuards: Vector[PV1.Credential],
      txInfoRequiredTopLevelGuards: AssocMap[PV1.Credential, Option[PV1.Datum]],
      txInfoRedeemers: AssocMap[ScriptPurpose, PV1.Redeemer],
      txInfoData: AssocMap[PV1.DatumHash, PV1.Datum],
      txInfoVotes: AssocMap[PV3.Voter, AssocMap[GovernanceActionId, PV3.Vote]],
      txInfoProposalProcedures: Vector[ProposalProcedure],
      txInfoCurrentTreasuryAmount: Option[PV1.Lovelace],
      txInfoTreasuryDonation: PV1.Lovelace
  )

  /** `data TopTxInfoSimplified` (`V4/Contexts.hs:397-447,658`, plutus 261766ad): the whole
    * transaction, its sub-transactions and its top level put together, a list.
    */
  final case class TopTxInfoSimplified(
      ttisIds: Vector[PV3.TxId],
      ttisInputs: Vector[TxInInfo],
      ttisReferenceInputs: Vector[TxInInfo],
      ttisOutputs: Vector[TxOut],
      ttisMints: PV3.MintValue,
      ttisBurns: PV3.MintValue,
      ttisTxCerts: Vector[TxCert],
      ttisWithdrawals: AssocMap[PV1.Credential, PV1.Lovelace],
      ttisDirectDeposits: AssocMap[PV1.Credential, PV1.Lovelace],
      ttisValidRange: POSIXTimeRange,
      ttisGuards: Vector[PV1.Credential],
      ttisRequiredTopLevelGuards: Vector[PV1.Credential],
      ttisRedeemerHashes: Vector[PV1.ScriptHash],
      ttisData: AssocMap[PV1.DatumHash, PV1.Datum],
      ttisVotes: AssocMap[PV3.Voter, AssocMap[GovernanceActionId, PV3.Vote]],
      ttisProposalProcedures: Vector[ProposalProcedure],
      ttisCurrentTreasuryAmount: Option[PV1.Lovelace],
      ttisTreasuryDonations: PV1.Lovelace
  )

  /** `data TopTxInfo` (`V4/Contexts.hs:449-466,661`, plutus 261766ad): the sub-transactions'
    * information, the datums the required top-level guards give, the starting balance intervals and
    * the whole transaction, a list.
    */
  final case class TopTxInfo(
      topTxInfoSubTransactions: Vector[TxInfo],
      topTxInfoDatums: AssocMap[PV3.TxId, PV1.Datum],
      topTxInfoStartingAccountBalanceIntervals: AccountBalanceIntervals,
      topTxInfoSimplified: TopTxInfoSimplified
  )

  /** `data ScriptInfo` of V4 (`V4/Contexts.hs:468-490,664-674`, plutus 261766ad): the purpose, with
    * the datum of a spending script and, for a guard at the top level, the whole transaction.
    */
  enum ScriptInfo:
    case MintingScript(currencySymbol: PV1.CurrencySymbol)
    case SpendingScript(txOutRef: TxOutRef, datum: Option[PV1.Datum])
    case WithdrawingScript(account: AccountId)
    case CertifyingScript(index: BigInt, txCert: TxCert)
    case VotingScript(voter: PV3.Voter)
    case ProposingScript(index: BigInt, proposal: ProposalProcedure)
    case GuardingScript(index: BigInt, topTxInfo: Option[TopTxInfo])

  /** `data ScriptContext` of V4 (`V4/Contexts.hs:492-504,677`, plutus 261766ad): the information,
    * the redeemer, the purpose's information and the hash of the script running, a list.
    */
  final case class ScriptContext(
      scriptContextTxInfo: TxInfo,
      scriptContextRedeemer: PV1.Redeemer,
      scriptContextScriptInfo: ScriptInfo,
      scriptContextScriptHash: PV1.ScriptHash
  )

  given accountId: ToData[AccountId] = _.credential.toData

  given address: ToData[Address] = a =>
    list(a.addressCredential.toData, a.addressStakingAccountId.toData)

  given txOutRef: ToData[TxOutRef] = r => list(r.txOutRefId.toData, r.txOutRefIdx.toData)

  given txOut: ToData[TxOut] = o =>
    list(
      o.txOutAddress.toData,
      o.txOutValue.toData,
      o.txOutDatum.toData,
      o.txOutReferenceScript.toData
    )

  given posixTimeRange: ToData[POSIXTimeRange] = r =>
    list(r.fromInclusive.toData, r.untilExclusive.toData)

  given rational: ToData[Rational] = r =>
    list(r.unRational.numerator.toData, r.unRational.denominator.toData)

  given governanceActionId: ToData[GovernanceActionId] = g =>
    list(g.gaidTxId.toData, g.gaidGovActionIx.toData)

  given protocolVersion: ToData[ProtocolVersion] = v => list(v.pvMajor.toData, v.pvMinor.toData)

  given constitution: ToData[Constitution] = c => list(c.constitutionScript.toData)

  given committee: ToData[Committee] = c =>
    list(c.committeeMembers.toData, c.committeeQuorum.toData)

  given governanceAction: ToData[GovernanceAction] =
    case GovernanceAction.ParameterChange(p, c, s) =>
      PlutusData.constr(0, p.toData, c.toData, s.toData)
    case GovernanceAction.HardForkInitiation(p, v)  => PlutusData.constr(1, p.toData, v.toData)
    case GovernanceAction.TreasuryWithdrawals(w, s) => PlutusData.constr(2, w.toData, s.toData)
    case GovernanceAction.NoConfidence(p)           => PlutusData.constr(3, p.toData)
    case GovernanceAction.UpdateCommittee(p, r, a, q) =>
      PlutusData.constr(4, p.toData, r.toData, a.toData, q.toData)
    case GovernanceAction.NewConstitution(p, c) => PlutusData.constr(5, p.toData, c.toData)
    case GovernanceAction.InfoAction            => PlutusData.constr(6)

  given proposalProcedure: ToData[ProposalProcedure] = p =>
    list(p.ppDeposit.toData, p.ppReturnAddr.toData, p.ppGovernanceAction.toData)

  given accountBalanceInterval: ToData[AccountBalanceInterval] =
    case AccountBalanceInterval.AccountBalanceLowerBound(l) => PlutusData.constr(0, l.toData)
    case AccountBalanceInterval.AccountBalanceUpperBound(u) => PlutusData.constr(1, u.toData)
    case AccountBalanceInterval.AccountBalanceBothBounds(l, u) =>
      PlutusData.constr(2, l.toData, u.toData)
    case AccountBalanceInterval.AccountBalanceExact(e) => PlutusData.constr(3, e.toData)

  given accountBalanceIntervals: ToData[AccountBalanceIntervals] = _.intervals.toData

  given txCert: ToData[TxCert] =
    case TxCert.TxCertRegAccount(a, d)         => PlutusData.constr(0, a.toData, d.toData)
    case TxCert.TxCertUnRegAccount(a, r)       => PlutusData.constr(1, a.toData, r.toData)
    case TxCert.TxCertDelegAccount(a, d)       => PlutusData.constr(2, a.toData, d.toData)
    case TxCert.TxCertRegAccountDeleg(a, d, l) => PlutusData.constr(3, a.toData, d.toData, l.toData)
    case TxCert.TxCertRegDRep(c, l)            => PlutusData.constr(4, c.toData, l.toData)
    case TxCert.TxCertUpdateDRep(c)            => PlutusData.constr(5, c.toData)
    case TxCert.TxCertUnRegDRep(c, l)          => PlutusData.constr(6, c.toData, l.toData)
    case TxCert.TxCertPoolRegister(p, v)       => PlutusData.constr(7, p.toData, v.toData)
    case TxCert.TxCertPoolRetire(p, e)         => PlutusData.constr(8, p.toData, e.toData)
    case TxCert.TxCertAuthHotCommittee(c, h)   => PlutusData.constr(9, c.toData, h.toData)
    case TxCert.TxCertResignColdCommittee(c)   => PlutusData.constr(10, c.toData)

  given scriptPurpose: ToData[ScriptPurpose] =
    case ScriptPurpose.Minting(h, c)       => PlutusData.constr(0, h.toData, c.toData)
    case ScriptPurpose.Spending(h, r)      => PlutusData.constr(1, h.toData, r.toData)
    case ScriptPurpose.Withdrawing(h, c)   => PlutusData.constr(2, h.toData, c.toData)
    case ScriptPurpose.Certifying(h, i, c) => PlutusData.constr(3, h.toData, i.toData, c.toData)
    case ScriptPurpose.Voting(h, v)        => PlutusData.constr(4, h.toData, v.toData)
    case ScriptPurpose.Proposing(h, i, p)  => PlutusData.constr(5, h.toData, i.toData, p.toData)
    case ScriptPurpose.Guarding(h, i)      => PlutusData.constr(6, h.toData, i.toData)

  given txInInfo: ToData[TxInInfo] = i => list(i.txInInfoOutRef.toData, i.txInInfoResolved.toData)

  given txInfo: ToData[TxInfo] = t =>
    list(
      t.txInfoId.toData,
      t.txInfoSubTxIx.toData,
      t.txInfoInputs.toData,
      t.txInfoReferenceInputs.toData,
      t.txInfoOutputs.toData,
      t.txInfoMint.toData,
      t.txInfoTxCerts.toData,
      t.txInfoWithdrawals.toData,
      t.txInfoDirectDeposits.toData,
      t.txInfoAccountBalanceIntervals.toData,
      t.txInfoValidRange.toData,
      t.txInfoGuards.toData,
      t.txInfoRequiredTopLevelGuards.toData,
      t.txInfoRedeemers.toData,
      t.txInfoData.toData,
      t.txInfoVotes.toData,
      t.txInfoProposalProcedures.toData,
      t.txInfoCurrentTreasuryAmount.toData,
      t.txInfoTreasuryDonation.toData
    )

  given topTxInfoSimplified: ToData[TopTxInfoSimplified] = t =>
    list(
      t.ttisIds.toData,
      t.ttisInputs.toData,
      t.ttisReferenceInputs.toData,
      t.ttisOutputs.toData,
      t.ttisMints.toData,
      t.ttisBurns.toData,
      t.ttisTxCerts.toData,
      t.ttisWithdrawals.toData,
      t.ttisDirectDeposits.toData,
      t.ttisValidRange.toData,
      t.ttisGuards.toData,
      t.ttisRequiredTopLevelGuards.toData,
      t.ttisRedeemerHashes.toData,
      t.ttisData.toData,
      t.ttisVotes.toData,
      t.ttisProposalProcedures.toData,
      t.ttisCurrentTreasuryAmount.toData,
      t.ttisTreasuryDonations.toData
    )

  given topTxInfo: ToData[TopTxInfo] = t =>
    list(
      t.topTxInfoSubTransactions.toData,
      t.topTxInfoDatums.toData,
      t.topTxInfoStartingAccountBalanceIntervals.toData,
      t.topTxInfoSimplified.toData
    )

  given scriptInfo: ToData[ScriptInfo] =
    case ScriptInfo.MintingScript(c)       => PlutusData.constr(0, c.toData)
    case ScriptInfo.SpendingScript(r, d)   => PlutusData.constr(1, r.toData, d.toData)
    case ScriptInfo.WithdrawingScript(a)   => PlutusData.constr(2, a.toData)
    case ScriptInfo.CertifyingScript(i, c) => PlutusData.constr(3, i.toData, c.toData)
    case ScriptInfo.VotingScript(v)        => PlutusData.constr(4, v.toData)
    case ScriptInfo.ProposingScript(i, p)  => PlutusData.constr(5, i.toData, p.toData)
    case ScriptInfo.GuardingScript(i, t)   => PlutusData.constr(6, i.toData, t.toData)

  given scriptContext: ToData[ScriptContext] = c =>
    list(
      c.scriptContextTxInfo.toData,
      c.scriptContextRedeemer.toData,
      c.scriptContextScriptInfo.toData,
      c.scriptContextScriptHash.toData
    )
