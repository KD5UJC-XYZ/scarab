// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Rational}
import xyz.kd5ujc.scarab.ledger.{Credential, DecodeField, LedgerDecodeError, RewardAccount}

import V12.{Result, given}

/** A governance action's id: the transaction that proposed it and its index there. */
final case class DijkstraGovActionId(txId: Bytes, index: Int)

object DijkstraGovActionId:
  /** `GovActionId` from version 12 (`eras/conway/impl/src/Cardano/Ledger/Conway/Governance/
    * Procedures.hs:181-189`, cardano-ledger 9df9f98c): `decodeRecordNamed` of a 32-byte `TxId` and
    * a `GovActionIx`, a `Word16`.
    */
  def decode(n: Node): Result[DijkstraGovActionId] =
    V12
      .record(n, 2, DecodeField.GovernanceActionId)
      .flatMap(xs =>
        (
          V12.bytesN(xs(0), 32, DecodeField.GovernanceActionTxId),
          V12.word16(xs(1), DecodeField.ActionIndex)
        ).mapN(DijkstraGovActionId(_, _))
      )

/** Who votes. */
enum DijkstraVoter:
  case Committee(credential: Credential)
  case DRep(credential: Credential)
  case StakePool(pool: Bytes)

/** A vote and its anchor. */
final case class DijkstraVotingProcedure(vote: Int, anchor: Option[DijkstraAnchor])

object DijkstraVoting:
  /** `Voter` (`Procedures.hs:384-392`): `decodeRecordSum` of a committee member's key or script
    * hash (tags 0 and 1), a DRep's (2 and 3) or a pool's key hash (4); 5, a pool's script, is
    * refused.
    */
  def voter(n: Node): Result[DijkstraVoter] =
    def hash(xs: Vector[Node]) = V12.bytesN(xs(1), 28, DecodeField.VoterHash)
    V12.sum(n, DecodeField.Voter) {
      case t if t == 0 => (2, xs => hash(xs).map(h => DijkstraVoter.Committee(Credential.Key(h))))
      case t if t == 1 =>
        (2, xs => hash(xs).map(h => DijkstraVoter.Committee(Credential.Script(h))))
      case t if t == 2 => (2, xs => hash(xs).map(h => DijkstraVoter.DRep(Credential.Key(h))))
      case t if t == 3 => (2, xs => hash(xs).map(h => DijkstraVoter.DRep(Credential.Script(h))))
      case t if t == 4 => (2, xs => hash(xs).map(DijkstraVoter.StakePool(_)))
    }

  /** `Vote` (`decodeEnumBounded`, `Procedures.hs:425-426`): `decodeInt` of 0, 1 or 2. */
  private def vote(n: Node): Result[Int] =
    V12
      .int64(n, DecodeField.Vote)
      .flatMap(v =>
        Either.cond(
          v >= 0 && v <= 2,
          v.toInt,
          LedgerDecodeError.NumberOutOfRange(DecodeField.Vote, v, 0, 2)
        )
      )

  /** `VotingProcedure` from version 12 (`Procedures.hs:495-505`): `decodeRecordNamed` of the vote
    * and an anchor or null.
    */
  private def procedure(n: Node): Result[DijkstraVotingProcedure] =
    V12
      .record(n, 2, DecodeField.VotingProcedure)
      .flatMap(xs =>
        (vote(xs(0)), DijkstraAnchor.optional(xs(1))).mapN(DijkstraVotingProcedure(_, _))
      )

  /** `VotingProcedures` (`Procedures.hs:447-455`): a map of voters, none twice, each to a non-empty
    * map of governance action ids, none twice, to procedures (`decodeMapByKey`, `decodeMap`).
    */
  def procedures(
      n: Node
  ): Result[Vector[(DijkstraVoter, Vector[(DijkstraGovActionId, DijkstraVotingProcedure)])]] =
    V12.map(n, DecodeField.VotingProcedures)(voter)((_, votes) =>
      V12.nonEmptyMap(votes, DecodeField.VotesOfAVoter)(DijkstraGovActionId.decode)((_, p) =>
        procedure(p)
      )
    )

/** A governance action of the Dijkstra era, decoded, so that two proposals are one exactly when the
  * reference's derived `Ord (ProposalProcedure era)` finds them equal (`OSet`).
  */
enum DijkstraGovAction:
  case ParameterChange(
      previous: Option[DijkstraGovActionId],
      update: DijkstraParamUpdate,
      policy: Option[Bytes]
  )
  case HardForkInitiation(previous: Option[DijkstraGovActionId], major: Long, minor: Long)
  case TreasuryWithdrawals(withdrawals: Map[RewardAccount, BigInt], policy: Option[Bytes])
  case NoConfidence(previous: Option[DijkstraGovActionId])
  case UpdateCommittee(
      previous: Option[DijkstraGovActionId],
      removed: Set[Credential],
      added: Map[Credential, BigInt],
      threshold: Rational
  )
  case NewConstitution(
      previous: Option[DijkstraGovActionId],
      anchor: DijkstraAnchor,
      policy: Option[Bytes]
  )
  case InfoAction

/** A proposal: its deposit, return account, action and anchor. */
final case class DijkstraProposal(
    deposit: BigInt,
    returnAccount: RewardAccount,
    action: DijkstraGovAction,
    anchor: DijkstraAnchor
)

object DijkstraProposal:
  /** `ProposalProcedure` from version 12 (`Procedures.hs:567-580`): `decodeRecordNamed` of the
    * deposit (`Coin`), the return account, the action and the anchor.
    */
  def decode(n: Node): Result[DijkstraProposal] =
    V12
      .record(n, 4, DecodeField.ProposalProcedure)
      .flatMap(xs =>
        (
          V12.word64(xs(0), DecodeField.ProposalDeposit),
          Account.decode(xs(1), DecodeField.ProposalRewardAccount),
          action(xs(2)),
          DijkstraAnchor.decode(xs(3))
        ).mapN(DijkstraProposal(_, _, _, _))
      )

  /** `OSet (ProposalProcedure era)`: the tag 258 allowed, no proposal twice by value. */
  def set(n: Node): Result[Vector[DijkstraProposal]] =
    V12.set(n, DecodeField.ProposalProcedures)(decode)(identity)

  private def previous(n: Node) = V12.nullable(n)(DijkstraGovActionId.decode)
  private def policy(n: Node) = V12.nullable(n)(V12.bytesN(_, 28, DecodeField.PolicyHash))

  /** `GovAction` from version 12 (`Procedures.hs:935-985`): `decodeRecordSum` of a parameter change
    * `[0, previous / null, update, policy / null]`, a hard fork `[1, previous / null, version]`
    * whose major may be at most 13 (`decodeProtVer @DijkstraEra`, `ProtVerHigh` 12 and its
    * successor, `BaseTypes.hs:254-265`; the version a `CBORGroup` record of a `Version` and a
    * `Word32`), treasury withdrawals `[2, map, policy / null]`, no confidence `[3, previous /
    * null]`, a committee update `[4, previous / null, set, map, quorum]`, a constitution `[5,
    * previous / null, [anchor, script hash / null]]` and an information action `[6]`.
    */
  private def action(n: Node): Result[DijkstraGovAction] =
    import DijkstraGovAction.*
    V12.sum(n, DecodeField.GovernanceAction) {
      case t if t == 0 =>
        (
          4,
          xs =>
            (previous(xs(1)), DijkstraParamUpdate.decode(xs(2)), policy(xs(3)))
              .mapN(ParameterChange(_, _, _))
        )
      case t if t == 1 =>
        (
          3,
          xs => (previous(xs(1)), version(xs(2))).mapN((p, v) => HardForkInitiation(p, v._1, v._2))
        )
      case t if t == 2 =>
        (
          3,
          xs =>
            (
              V12.map(xs(1), DecodeField.TreasuryWithdrawals)(
                Account.decode(_, DecodeField.WithdrawalAccount)
              )((_, c) => V12.word64(c, DecodeField.WithdrawalAmount)),
              policy(xs(2))
            ).mapN((w, p) => TreasuryWithdrawals(w.toMap, p))
        )
      case t if t == 3 => (2, xs => previous(xs(1)).map(NoConfidence(_)))
      case t if t == 4 =>
        (
          5,
          xs =>
            (
              previous(xs(1)),
              V12.set(xs(2), DecodeField.RemovedMembers)(
                Credential.decode(_, DecodeField.CommitteeMember)
              )(identity),
              V12.map(xs(3), DecodeField.AddedMembers)(
                Credential.decode(_, DecodeField.CommitteeMember)
              )((_, e) => V12.word64(e, DecodeField.MemberTerm)),
              V12.ratio(xs(4), DecodeField.Quorum, V12.Interval.Unit)
            ).mapN((p, r, a, q) => UpdateCommittee(p, r.toSet, a.toMap, q))
        )
      case t if t == 5 =>
        (
          3,
          xs =>
            (
              previous(xs(1)),
              V12
                .record(xs(2), 2, DecodeField.Constitution)
                .flatMap(c => (DijkstraAnchor.decode(c(0)), policy(c(1))).tupled)
            ).mapN((p, c) => NewConstitution(p, c._1, c._2))
        )
      case t if t == 6 => (1, _ => Right(InfoAction))
    }

  /** `ProtVer` through `decodeProtVer @DijkstraEra`: the major a `Version` (`decodeWord32` and
    * `mkVersion32`, at most `MaxVersion` 13, `Version.hs:59-63`) no larger than 13, the minor a
    * `Word32`.
    */
  private def version(n: Node): Result[(Long, Long)] =
    V12
      .record(n, 2, DecodeField.ProtocolVersion)
      .flatMap(xs =>
        (V12.word32(xs(0), DecodeField.Major), V12.word32(xs(1), DecodeField.Minor)).tupled
      )
      .flatMap((major, minor) =>
        Either.cond(
          major <= 13,
          (major, minor),
          LedgerDecodeError.NumberOutOfRange(DecodeField.Major, major, 0, 13)
        )
      )
end DijkstraProposal
