// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import scala.collection.immutable.SortedMap
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  CborRead,
  Constitution,
  DecoderVersion,
  Credential as FlatCredential,
  DecodeField,
  GovActionId,
  LedgerDecodeError,
  Unsigned,
  Voter,
  VotingProcedure
}
import xyz.kd5ujc.scarab.ledger.dijkstra.{
  DijkstraAnchor,
  DijkstraCert,
  DijkstraDelegatee,
  DijkstraGovAction,
  DijkstraGovActionId,
  DijkstraPoolParams,
  DijkstraProposal,
  DijkstraVoter,
  DijkstraVotingProcedure
}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayGovCert,
  Delegatee,
  EraTxError,
  GovAction,
  GovPurposeId,
  PParamsError,
  PParamsUpdate,
  PoolCert,
  ProposalProcedure,
  ProtVer,
  UnitInterval,
  Version,
  VotingProcedures
}
import xyz.kd5ujc.scarab.protocol.StateLayout
import xyz.kd5ujc.scarab.ledger.state.{
  BlsKey,
  ColdCommittee,
  Coin,
  Credential,
  DRepRole,
  Dijkstra,
  EpochNo,
  HotCommittee,
  KeyHash,
  KeyRole,
  PoolMetadata,
  PoolParamsBlsKey,
  ScriptHash,
  Sorted,
  StakePool,
  StakePoolParams,
  Staking,
  VRFVerKeyHash,
  VocabError
}

// Dijkstra's certificates, proposals and votes, read from what the Leios build's block decoder
// read: only this object makes them from a body. At cardano-ledger 9df9f98c.

/** Dijkstra's certificates, proposals and votes from the flat decoder's (`DijkstraTxCert era`,
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxCert.hs:91-96,185-189`; `GovAction era`,
  * `ProposalProcedure era`, `VotingProcedures era`,
  * `eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Procedures.hs`, cardano-ledger 9df9f98c).
  */
object DijkstraProcedures:
  private def vocab[A](r: Either[xyz.kd5ujc.scarab.ledger.state.VocabError, A]) =
    r.leftMap(EraTxError.Vocab(_))

  private def credential[R <: KeyRole](c: FlatCredential): Either[EraTxError, Credential[R]] =
    vocab(Convert.credential[R](c))

  private def poolKey(pool: Bytes): Either[EraTxError, KeyHash[StakePool]] =
    vocab(KeyHash.fromBytes[StakePool](pool))

  private def anchor(a: DijkstraAnchor): Anchor = Anchor(a.url, a.hash)

  private def actionId(id: DijkstraGovActionId): GovActionId =
    GovActionId(id.txId, BigInt(id.index))

  private def delegatee(d: DijkstraDelegatee): Either[EraTxError, Delegatee] = d match
    case DijkstraDelegatee.Stake(pool) => poolKey(pool).map(Delegatee.DelegStake(_))
    case DijkstraDelegatee.Vote(drep)  => vocab(Convert.drep(drep)).map(Delegatee.DelegVote(_))
    case DijkstraDelegatee.StakeVote(pool, drep) =>
      (poolKey(pool), vocab(Convert.drep(drep))).mapN(Delegatee.DelegStakeVote(_, _))

  /** The parameters of a pool registration the flat decoder read (`decodeStakePoolParamsFlat`,
    * cardano-ledger 9df9f98c `State/StakePool.hs:746-772`), at the state's types, with the voting
    * key the Leios build's `StakePoolParams` holds (`sppBlsKey`, `:479`). The flat decoder keeps
    * the relays and the metadata decoded; the state keeps the bytes they were read from, as it does
    * for Conway's, so they are taken from `item`, the certificate as the body's bytes hold it,
    * whose items the flat decoder read: the tag, then nine items, or ten with the key's place
    * third. No relays at all is `Bytes.empty`, as Conway's decoder keeps them.
    */
  private def poolParams(
      p: DijkstraPoolParams,
      item: Node
  ): Either[EraTxError, StakePoolParams] =
    for
      xs <- CborRead.array(item, DecodeField.Certificate).leftMap(EraTxError.Decode(_))
      extra = if xs.sizeIs == 11 then 1 else 0
      relays <- xs
        .lift(8 + extra)
        .toRight(
          EraTxError.Decode(LedgerDecodeError.ArrayLength(DecodeField.Certificate, 10, xs.size))
        )
      metadataItem <- xs
        .lift(9 + extra)
        .toRight(
          EraTxError.Decode(LedgerDecodeError.ArrayLength(DecodeField.Certificate, 10, xs.size))
        )
      metadata <- metadataItem.value match
        case Value.Null => Right(None)
        case _ =>
          PoolMetadata
            .read(metadataItem.original, DecoderVersion.Dijkstra, StateLayout.Leios)
            .bimap(EraTxError.Decode(_), Some(_))
      params <- vocab(
        (
          KeyHash.fromBytes[StakePool](p.operator),
          VRFVerKeyHash.fromBytes(p.vrf),
          UnitInterval.boundRational(p.margin).toRight(VocabError.NotUnitInterval(p.margin)),
          Convert.accountAddress(p.account),
          p.owners.toList.traverse(KeyHash.fromBytes[Staking]).map(Sorted.set(_))
        ).mapN((id, vrf, margin, account, owners) =>
          StakePoolParams(
            id,
            vrf,
            PoolParamsBlsKey.Leios(p.blsKey.map(k => BlsKey(k.key, k.proofOfPossession))),
            Coin(p.pledge),
            Coin(p.cost),
            margin,
            account,
            owners,
            if relays.original == Bytes(Vector(0x80.toByte)) then Bytes.empty else relays.original,
            metadata
          )
        )
      )
    yield params

  /** The certificate the flat decoder read, at the reference's types; `item` is the certificate as
    * the body's bytes hold it, which a pool registration's parameters are read from
    * ([[poolParams]]). The decoder bounded each field as the reference's type does.
    */
  private[dijkstra] def cert(
      c: DijkstraCert,
      item: => Either[EraTxError, Node]
  ): Either[EraTxError, DijkstraTxCert] =
    import DijkstraTxCert.*
    c match
      case DijkstraCert.Register(cred, deposit) =>
        credential[Staking](cred).map(c =>
          DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraRegCert(c, Coin(deposit)))
        )
      case DijkstraCert.Unregister(cred, refund) =>
        credential[Staking](cred).map(c =>
          DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraUnRegCert(c, Coin(refund)))
        )
      case DijkstraCert.Delegate(cred, to) =>
        (credential[Staking](cred), delegatee(to)).mapN((c, d) =>
          DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraDelegCert(c, d))
        )
      case DijkstraCert.RegisterDelegate(cred, to, deposit) =>
        (credential[Staking](cred), delegatee(to)).mapN((c, d) =>
          DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraRegDelegCert(c, d, Coin(deposit)))
        )
      case DijkstraCert.RegisterPool(p) =>
        item.flatMap(poolParams(p, _)).map(params => DijkstraTxCertPool(PoolCert.RegPool(params)))
      case DijkstraCert.RetirePool(pool, epoch) =>
        (poolKey(pool), vocab(EpochNo.fromBigInt(epoch)))
          .mapN((p, e) => DijkstraTxCertPool(PoolCert.RetirePool(p, e)))
      case DijkstraCert.AuthorizeCommitteeHotKey(cold, hot) =>
        (credential[ColdCommittee](cold), credential[HotCommittee](hot)).mapN((c, h) =>
          DijkstraTxCertGov(ConwayGovCert.ConwayAuthCommitteeHotKey(c, h))
        )
      case DijkstraCert.ResignCommitteeColdKey(cold, a) =>
        credential[ColdCommittee](cold).map(c =>
          DijkstraTxCertGov(ConwayGovCert.ConwayResignCommitteeColdKey(c, a.map(anchor)))
        )
      case DijkstraCert.RegisterDRep(cred, deposit, a) =>
        credential[DRepRole](cred).map(c =>
          DijkstraTxCertGov(ConwayGovCert.ConwayRegDRep(c, Coin(deposit), a.map(anchor)))
        )
      case DijkstraCert.UnregisterDRep(cred, refund) =>
        credential[DRepRole](cred).map(c =>
          DijkstraTxCertGov(ConwayGovCert.ConwayUnRegDRep(c, Coin(refund)))
        )
      case DijkstraCert.UpdateDRep(cred, a) =>
        credential[DRepRole](cred).map(c =>
          DijkstraTxCertGov(ConwayGovCert.ConwayUpdateDRep(c, a.map(anchor)))
        )

  private def guardrails(policy: Option[Bytes]): Either[EraTxError, Option[ScriptHash]] =
    policy.traverse(p => vocab(ScriptHash.fromBytes(p)))

  /** The action the flat decoder read, at the reference's types (`GovAction DijkstraEra`,
    * `Procedures.hs:935-985`, cardano-ledger 9df9f98c). The decoder bounded each field as the
    * reference's type does, so none of these refusals is reached from bytes it accepted: an update
    * outside its fields' types ([[DijkstraPParams.fromUpdate]]), a version outside `Version` and
    * `Word32`, a script hash or committee credential of the wrong length, an epoch outside `Word64`
    * and a threshold outside `[0, 1]`.
    */
  private def govAction(a: DijkstraGovAction): Either[EraTxError, GovAction[Dijkstra]] = a match
    case DijkstraGovAction.ParameterChange(previous, update, policy) =>
      (
        DijkstraPParams.fromUpdate(update).leftMap(EraTxError.PParams(_)),
        guardrails(policy)
      ).mapN((u, g) =>
        GovAction.ParameterChange(
          previous.map(p => GovPurposeId(actionId(p))),
          PParamsUpdate[Dijkstra](u),
          g
        )
      )
    case DijkstraGovAction.HardForkInitiation(previous, major, minor) =>
      (
        Version
          .mkVersion(BigInt(major))
          .toRight(PParamsError.OutOfRange(DecodeField.Major, BigInt(major))),
        Unsigned
          .from[Unsigned.Width.W32](BigInt(minor))
          .toRight(PParamsError.OutOfRange(DecodeField.Minor, BigInt(minor)))
      ).mapN(ProtVer(_, _))
        .leftMap(EraTxError.PParams(_))
        .map(v => GovAction.HardForkInitiation(previous.map(p => GovPurposeId(actionId(p))), v))
    case DijkstraGovAction.TreasuryWithdrawals(withdrawals, policy) =>
      // the decoder refused a repeated account (`decodeMap`), so the map holds every withdrawal
      guardrails(policy).map(g =>
        GovAction.TreasuryWithdrawals(withdrawals.map((a, c) => a -> Coin(c)), g)
      )
    case DijkstraGovAction.NoConfidence(previous) =>
      Right(GovAction.NoConfidence(previous.map(p => GovPurposeId(actionId(p)))))
    case DijkstraGovAction.UpdateCommittee(previous, removed, added, threshold) =>
      (
        removed.toVector.traverse(credential[ColdCommittee](_)).map(_.toSet),
        added.toVector
          .traverse((c, e) => (credential[ColdCommittee](c), vocab(EpochNo.fromBigInt(e))).tupled)
          .map(_.toMap),
        UnitInterval
          .boundRational(threshold)
          .toRight(
            EraTxError.PParams(PParamsError.RatioOutOfBounds(DecodeField.Quorum, threshold))
          )
      ).mapN((r, m, q) =>
        GovAction.UpdateCommittee(previous.map(p => GovPurposeId(actionId(p))), r, m, q)
      )
    case DijkstraGovAction.NewConstitution(previous, a, policy) =>
      Right(
        GovAction.NewConstitution(
          previous.map(p => GovPurposeId(actionId(p))),
          Constitution(anchor(a), policy)
        )
      )
    case DijkstraGovAction.InfoAction => Right(GovAction.InfoAction())

  /** The proposal the flat decoder read, its action at the reference's types ([[govAction]]), with
    * the flat proposal it was read as.
    */
  private[dijkstra] def proposal(
      p: DijkstraProposal
  ): Either[EraTxError, ProposalProcedure[Dijkstra]] =
    govAction(p.action).map(a =>
      ProposalProcedure[Dijkstra](Coin(p.deposit), p.returnAccount, a, anchor(p.anchor), p)
    )

  private def voter(v: DijkstraVoter): Voter = v match
    case DijkstraVoter.Committee(c) => Voter.CommitteeHot(c)
    case DijkstraVoter.DRep(c)      => Voter.DRepVoter(c)
    case DijkstraVoter.StakePool(p) => Voter.Pool(p)

  /** The votes the flat decoder read, which refused a repeated voter or action (`decodeMapByKey`)
    * and a voter without votes (`DecCBOR (VotingProcedures era)`, `Procedures.hs:447-455`,
    * cardano-ledger 9df9f98c).
    */
  private[dijkstra] def votes(
      all: Vector[(DijkstraVoter, Vector[(DijkstraGovActionId, DijkstraVotingProcedure)])]
  ): VotingProcedures[Dijkstra] =
    VotingProcedures[Dijkstra](
      SortedMap.from(
        all.map((v, vs) =>
          voter(v) -> SortedMap.from(
            vs.map((id, p) => actionId(id) -> VotingProcedure(p.vote, p.anchor.map(anchor)))
          )
        )
      )
    )
