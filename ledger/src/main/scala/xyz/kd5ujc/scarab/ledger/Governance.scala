// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import cats.Order
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value as CborValue}

/** `[transaction_id, index]` naming a governance action. */
final case class GovActionId(txId: Bytes, index: BigInt)

object GovActionId:
  def decode(n: Node): Either[LedgerDecodeError, GovActionId] =
    CborRead
      .arrayN(n, 2, DecodeField.GovernanceActionId)
      .flatMap(xs =>
        (
          CborRead.bytesN(xs(0), 32, DecodeField.GovernanceActionTxId),
          CborRead.uint(xs(1), DecodeField.ActionIndex)
        )
          .mapN(GovActionId(_, _))
      )

  private[ledger] def optional(n: Node): Either[LedgerDecodeError, Option[GovActionId]] =
    n.value match
      case CborValue.Null => Right(None)
      case _              => decode(n).map(Some(_))

/** Who casts a vote. */
enum Voter:
  case CommitteeHot(credential: Credential)
  case DRepVoter(credential: Credential)
  case Pool(keyHash: Bytes)

object Voter:
  /** The ledger's `Ord Voter`, which is derived (`Conway/Governance/Procedures.hs` 338-342,
    * f649f975): the constructor in the order of the declaration (committee, DRep, pool), then the
    * credential's derived `Ord` (`Credential.hs` 98-101: `ScriptHashObj` before `KeyHashObj`), then
    * the hash. A `KeyHash` or `ScriptHash` orders as its `Hash` (`Hashes.hs` 166-168, 203-205),
    * whose `Ord` is the one of `PackedBytes` (cardano-crypto-class 2.5.1.0, `Hash/Class.hs` 137-138
    * and `PackedBytes/Internal.hs` 91-98; the same in 2.4.0.0): big-endian words, or the
    * `ShortByteString`. Either is the unsigned lexicographic order of the bytes, which is the order
    * of [[Bytes]].
    */
  given order: Order[Voter] = (a, b) =>
    a match
      case CommitteeHot(x) =>
        b match
          case CommitteeHot(y) => credential(x, y)
          case _               => -1
      case DRepVoter(x) =>
        b match
          case CommitteeHot(_) => 1
          case DRepVoter(y)    => credential(x, y)
          case Pool(_)         => -1
      case Pool(x) =>
        b match
          case Pool(y) => x.compareTo(y)
          case _       => 1

  /** [[order]] for the standard library's sorts. */
  given ordering: Ordering[Voter] = order.toOrdering

  /** The derived `Ord (Credential r)`: a script before a key, then the hash. Not the legacy
    * `Ordering[Credential]`, which puts the key first.
    */
  private def credential(x: Credential, y: Credential): Int = x match
    case Credential.Script(a) =>
      y match
        case Credential.Script(b) => a.compareTo(b)
        case Credential.Key(_)    => -1
    case Credential.Key(a) =>
      y match
        case Credential.Key(b)    => a.compareTo(b)
        case Credential.Script(_) => 1

  /** `[0, keyhash] / [1, scripthash]` (committee), `[2, ..] / [3, ..]` (DRep), `[4, keyhash]`. */
  def decode(n: Node): Either[LedgerDecodeError, Voter] =
    CborRead.arrayN(n, 2, DecodeField.Voter).flatMap { xs =>
      (
        CborRead.uint(xs(0), DecodeField.VoterTag).map(_.toInt),
        CborRead.bytesN(xs(1), 28, DecodeField.VoterHash)
      ).tupled
        .flatMap {
          case (0, h) => Right(CommitteeHot(Credential.Key(h)))
          case (1, h) => Right(CommitteeHot(Credential.Script(h)))
          case (2, h) => Right(DRepVoter(Credential.Key(h)))
          case (3, h) => Right(DRepVoter(Credential.Script(h)))
          case (4, h) => Right(Pool(h))
          case (t, _) => Left(LedgerDecodeError.UnknownTag(DecodeField.Voter, t))
        }
    }

enum Vote:
  case No, Yes, Abstain

/** Governance action of a proposal procedure. A parameter change holds a typed Conway update that
  * keeps the original encoding of each value ([[ConwayParamUpdate]]).
  */
enum GovAction:
  case ParameterChange(
      previous: Option[GovActionId],
      update: ConwayParamUpdate,
      policy: Option[Bytes]
  )
  case HardFork(previous: Option[GovActionId], major: BigInt, minor: BigInt)
  case TreasuryWithdrawals(withdrawals: Vector[(RewardAccount, BigInt)], policy: Option[Bytes])
  case NoConfidence(previous: Option[GovActionId])
  case UpdateCommittee(
      previous: Option[GovActionId],
      removed: Set[Credential],
      added: Map[Credential, BigInt],
      quorum: Rational
  )
  case NewConstitution(
      previous: Option[GovActionId],
      script: Option[Bytes],
      anchor: Anchor = ConwayGenesis.noAnchor
  )
  case Info

/** `[deposit, reward_account, gov_action, anchor]`. */
final case class Proposal(
    deposit: BigInt,
    returnAccount: RewardAccount,
    action: GovAction,
    anchor: Anchor = ConwayGenesis.noAnchor
)

/** The comparison key of a proposal procedure: what `Ord (ProposalProcedure era)` compares
  * (`Governance/Procedures.hs:498-504`, f649f975), with the action as [[ActionIdentity]].
  */
private[ledger] final case class ProposalIdentity(
    deposit: BigInt,
    returnAccount: RewardAccount,
    action: ActionIdentity,
    anchor: Anchor
)

/** A governance action by meaning (`Governance/Procedures.hs:815-848`, `deriving Ord`): a parameter
  * update by the typed value of each field (`PParamsUpdate`), and treasury withdrawals as the `Map
  * AccountAddress Coin` they are, whatever the order of the entries; any other action is its
  * decoded value.
  */
private[ledger] enum ActionIdentity:
  case Update(
      previous: Option[GovActionId],
      fields: Map[Int, ConwayParam],
      policy: Option[Bytes]
  )
  case Withdrawals(withdrawals: Map[RewardAccount, BigInt], policy: Option[Bytes])
  case Other(action: GovAction)

object Proposal:
  private[ledger] def identity(p: Proposal): ProposalIdentity =
    ProposalIdentity(
      p.deposit,
      p.returnAccount,
      p.action match
        case GovAction.ParameterChange(previous, update, policy) =>
          ActionIdentity.Update(previous, update.byKey, policy)
        case GovAction.TreasuryWithdrawals(ws, policy) =>
          ActionIdentity.Withdrawals(ws.toMap, policy)
        case other => ActionIdentity.Other(other)
      ,
      p.anchor
    )

  private def optionalHash(n: Node): Either[LedgerDecodeError, Option[Bytes]] = n.value match
    case CborValue.Null => Right(None)
    case _              => CborRead.bytesN(n, 28, DecodeField.PolicyHash).map(Some(_))

  def decode(n: Node, version: DecoderVersion): Either[LedgerDecodeError, Proposal] =
    for
      xs <- CborRead.arrayN(n, 4, DecodeField.ProposalProcedure)
      deposit <- CborRead.uint(xs(0), DecodeField.ProposalDeposit)
      account <- CborRead
        .bytes(xs(1), DecodeField.ProposalRewardAccount)
        .flatMap(RewardAccount.decode)
      action <- action(xs(2), version)
      anchor <- Anchor.decode(xs(3))
    yield Proposal(deposit, account, action, anchor)

  /** A map whose repeated key fails from decoder version 9 (`decodeMap`, Decoder.hs). */
  private def keysUnique[K, V](
      kvs: Vector[(K, V)],
      what: DecodeSite,
      version: DecoderVersion
  ): Either[LedgerDecodeError, Vector[(K, V)]] =
    if version.atLeast(9) then CborRead.unique(kvs.map(_._1), what).as(kvs) else Right(kvs)

  private def action(n: Node, version: DecoderVersion): Either[LedgerDecodeError, GovAction] =
    CborRead.array(n, DecodeField.GovernanceAction).flatMap { xs =>
      for
        tag <- xs.headOption
          .toRight(LedgerDecodeError.Empty(DecodeField.GovernanceAction))
          .flatMap(CborRead.uint(_, DecodeField.ActionTag))
        a <- (tag.toInt, xs.size) match
          case (0, 4) =>
            (
              GovActionId.optional(xs(1)),
              ConwayParamUpdate.decode(xs(2)),
              optionalHash(xs(3))
            ).mapN(GovAction.ParameterChange(_, _, _))
          case (1, 3) =>
            (
              GovActionId.optional(xs(1)),
              CborRead
                .arrayN(xs(2), 2, DecodeField.ProtocolVersion)
                .flatMap(v =>
                  (
                    CborRead.uint(v(0), DecodeField.Major),
                    CborRead.uint(v(1), DecodeField.Minor)
                  ).tupled
                )
            ).mapN((p, v) => GovAction.HardFork(p, v._1, v._2))
          case (2, 3) =>
            (
              CborRead
                .map(xs(1), DecodeField.TreasuryWithdrawals)
                .flatMap(_.traverse { case (k, v) =>
                  (
                    CborRead.bytes(k, DecodeField.WithdrawalAccount).flatMap(RewardAccount.decode),
                    CborRead.uint(v, DecodeField.WithdrawalAmount)
                  ).tupled
                })
                .flatMap(ws => keysUnique(ws, DecodeField.TreasuryWithdrawals, version)),
              optionalHash(xs(2))
            ).mapN(GovAction.TreasuryWithdrawals(_, _))
          case (3, 2) => GovActionId.optional(xs(1)).map(GovAction.NoConfidence(_))
          case (4, 5) =>
            (
              GovActionId.optional(xs(1)),
              CborRead.set(xs(2), DecodeField.RemovedMembers, version)(
                Credential.decode(_, DecodeField.CommitteeMember)
              ),
              CborRead
                .map(xs(3), DecodeField.AddedMembers)
                .flatMap(_.traverse { case (k, v) =>
                  (
                    Credential.decode(k, DecodeField.CommitteeMember),
                    CborRead.uint(v, DecodeField.MemberTerm)
                  ).tupled
                })
                .flatMap(ms => keysUnique(ms, DecodeField.AddedMembers, version)),
              CborRead.rational(xs(4), DecodeField.Quorum, CborRead.Bound.Unit)
            ).mapN((p, r, a, q) => GovAction.UpdateCommittee(p, r.toSet, a.toMap, q))
          case (5, 3) =>
            (
              GovActionId.optional(xs(1)),
              CborRead
                .arrayN(xs(2), 2, DecodeField.Constitution)
                .flatMap(c => (optionalHash(c(1)), Anchor.decode(c(0))).tupled)
            ).mapN((p, c) => GovAction.NewConstitution(p, c._1, c._2))
          case (6, 1) => Right(GovAction.Info)
          case (t, k) => Left(LedgerDecodeError.TaggedArity(DecodeField.GovernanceAction, t, k))
      yield a
    }

/** `{ + voter => { + gov_action_id => [vote, anchor / null] } }`. */
final case class VotingProcedure(vote: Vote, anchor: Option[Anchor])

object VotingProcedures:
  enum DecodeError:
    case InvalidStructure(cause: LedgerDecodeError)
    case InvalidVote(value: BigInt)

    def message: String = this match
      case InvalidStructure(cause) => cause.message
      case InvalidVote(value)      => s"vote $value"

  def decode(
      n: Node
  ): Either[DecodeError, Vector[(Voter, Vector[(GovActionId, VotingProcedure)])]] =
    CborRead
      .map(n, DecodeField.VotingProcedures)
      .leftMap(DecodeError.InvalidStructure(_))
      .flatMap(_.traverse { case (voter, inner) =>
        for
          v <- Voter.decode(voter).leftMap(DecodeError.InvalidStructure(_))
          votes <- CborRead
            .map(inner, DecodeField.Votes)
            .leftMap(DecodeError.InvalidStructure(_))
            .flatMap(_.traverse { case (id, procedure) =>
              for
                actionId <- GovActionId.decode(id).leftMap(DecodeError.InvalidStructure(_))
                p <- CborRead
                  .arrayN(procedure, 2, DecodeField.VotingProcedure)
                  .leftMap(DecodeError.InvalidStructure(_))
                vote <- CborRead
                  .uint(p(0), DecodeField.Vote)
                  .leftMap(DecodeError.InvalidStructure(_))
                  .flatMap {
                    case x if x == 0 => Right(Vote.No)
                    case x if x == 1 => Right(Vote.Yes)
                    case x if x == 2 => Right(Vote.Abstain)
                    case x           => Left(DecodeError.InvalidVote(x))
                  }
                anchor <- p(1).value match
                  case CborValue.Null => Right(None)
                  case _ =>
                    Anchor.decode(p(1)).leftMap(DecodeError.InvalidStructure(_)).map(Some(_))
              yield actionId -> VotingProcedure(vote, anchor)
            })
        yield v -> votes
      })

  /** What the transaction-body decoder of Conway (decoder version 9) refuses in the voting
    * procedures: a map with a repeated key (`decodeMapByKey`, Decoder.hs) and a voter without votes
    * (`DecCBOR VotingProcedures`, `Conway/Governance/Procedures.hs` at cardano-ledger f649f975).
    * Signals of the GOV rule are not read from a body, so [[decode]] does not apply it.
    */
  def requireBodyDecodable(
      all: Vector[(Voter, Vector[(GovActionId, VotingProcedure)])]
  ): Either[DecodeError, Vector[(Voter, Vector[(GovActionId, VotingProcedure)])]] =
    val structure = for
      _ <- CborRead.unique(all.map(_._1), DecodeField.Voters)
      _ <- all.traverse { case (_, votes) =>
        CborRead
          .nonEmpty(votes, DecodeField.VotesOfAVoter, required = true)
          .flatMap(vs => CborRead.unique(vs.map(_._1), DecodeField.GovernanceActionIds))
      }
    yield all
    structure.leftMap(DecodeError.InvalidStructure(_))
