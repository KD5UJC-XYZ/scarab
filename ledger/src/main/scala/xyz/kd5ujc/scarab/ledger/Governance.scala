// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value as CborValue}

/** `[transaction_id, index]` naming a governance action. */
final case class GovActionId(txId: Bytes, index: BigInt)

object GovActionId:
  def decode(n: Node): Either[LedgerDecodeError, GovActionId] =
    CborRead
      .arrayN(n, 2, "governance action id")
      .flatMap(xs =>
        (
          CborRead.bytesN(xs(0), 32, "governance action tx id"),
          CborRead.uint(xs(1), "action index")
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
  /** The ledger's `Ord Voter`: constructor, then the credential (script before key), then hash. */
  given ordering: Ordering[Voter] = Ordering.by {
    case CommitteeHot(c) => (0, c.isInstanceOf[Credential.Key], c.hash.hex)
    case DRepVoter(c)    => (1, c.isInstanceOf[Credential.Key], c.hash.hex)
    case Pool(h)         => (2, false, h.hex)
  }

  /** `[0, keyhash] / [1, scripthash]` (committee), `[2, ..] / [3, ..]` (DRep), `[4, keyhash]`. */
  def decode(n: Node): Either[LedgerDecodeError, Voter] =
    CborRead.arrayN(n, 2, "voter").flatMap { xs =>
      (
        CborRead.uint(xs(0), "voter tag").map(_.toInt),
        CborRead.bytesN(xs(1), 28, "voter hash")
      ).tupled
        .flatMap {
          case (0, h) => Right(CommitteeHot(Credential.Key(h)))
          case (1, h) => Right(CommitteeHot(Credential.Script(h)))
          case (2, h) => Right(DRepVoter(Credential.Key(h)))
          case (3, h) => Right(DRepVoter(Credential.Script(h)))
          case (4, h) => Right(Pool(h))
          case (t, _) => Left(LedgerDecodeError.UnknownTag("voter", t))
        }
    }

enum Vote:
  case No, Yes, Abstain

/** Governance action of a proposal procedure. A parameter change keeps the update's fields as their
  * original encodings.
  */
enum GovAction:
  case ParameterChange(previous: Option[GovActionId], update: ParamUpdate, policy: Option[Bytes])
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

object Proposal:
  private def optionalHash(n: Node): Either[LedgerDecodeError, Option[Bytes]] = n.value match
    case CborValue.Null => Right(None)
    case _              => CborRead.bytesN(n, 28, "policy hash").map(Some(_))

  def decode(n: Node, version: DecoderVersion): Either[LedgerDecodeError, Proposal] =
    for
      xs <- CborRead.arrayN(n, 4, "proposal procedure")
      deposit <- CborRead.uint(xs(0), "proposal deposit")
      account <- CborRead.bytes(xs(1), "proposal reward account").flatMap(RewardAccount.decode)
      action <- action(xs(2), version)
      anchor <- Anchor.decode(xs(3))
    yield Proposal(deposit, account, action, anchor)

  /** A map whose repeated key fails from decoder version 9 (`decodeMap`, Decoder.hs). */
  private def keysUnique[K, V](
      kvs: Vector[(K, V)],
      what: String,
      version: DecoderVersion
  ): Either[LedgerDecodeError, Vector[(K, V)]] =
    if version.atLeast(9) then CborRead.unique(kvs.map(_._1), what).as(kvs) else Right(kvs)

  private def action(n: Node, version: DecoderVersion): Either[LedgerDecodeError, GovAction] =
    CborRead.array(n, "governance action").flatMap { xs =>
      for
        tag <- xs.headOption
          .toRight(LedgerDecodeError.Empty("governance action"))
          .flatMap(CborRead.uint(_, "action tag"))
        a <- (tag.toInt, xs.size) match
          case (0, 4) =>
            (
              GovActionId.optional(xs(1)),
              CborRead
                .map(xs(2), "parameter update")
                .flatMap(ParamUpdate.read(_, CostModelDecode.Format.Open)),
              optionalHash(xs(3))
            ).mapN(GovAction.ParameterChange(_, _, _))
          case (1, 3) =>
            (
              GovActionId.optional(xs(1)),
              CborRead
                .arrayN(xs(2), 2, "protocol version")
                .flatMap(v => (CborRead.uint(v(0), "major"), CborRead.uint(v(1), "minor")).tupled)
            ).mapN((p, v) => GovAction.HardFork(p, v._1, v._2))
          case (2, 3) =>
            (
              CborRead
                .map(xs(1), "treasury withdrawals")
                .flatMap(_.traverse { case (k, v) =>
                  (
                    CborRead.bytes(k, "withdrawal account").flatMap(RewardAccount.decode),
                    CborRead.uint(v, "withdrawal amount")
                  ).tupled
                })
                .flatMap(ws => keysUnique(ws, "treasury withdrawals", version)),
              optionalHash(xs(2))
            ).mapN(GovAction.TreasuryWithdrawals(_, _))
          case (3, 2) => GovActionId.optional(xs(1)).map(GovAction.NoConfidence(_))
          case (4, 5) =>
            (
              GovActionId.optional(xs(1)),
              CborRead.set(xs(2), "removed members", version)(
                Credential.decode(_, "committee member")
              ),
              CborRead
                .map(xs(3), "added members")
                .flatMap(_.traverse { case (k, v) =>
                  (Credential.decode(k, "committee member"), CborRead.uint(v, "member term")).tupled
                })
                .flatMap(ms => keysUnique(ms, "added members", version)),
              CborRead.rational(xs(4), "quorum")
            ).mapN((p, r, a, q) => GovAction.UpdateCommittee(p, r.toSet, a.toMap, q))
          case (5, 3) =>
            (
              GovActionId.optional(xs(1)),
              CborRead
                .arrayN(xs(2), 2, "constitution")
                .flatMap(c => (optionalHash(c(1)), Anchor.decode(c(0))).tupled)
            ).mapN((p, c) => GovAction.NewConstitution(p, c._1, c._2))
          case (6, 1) => Right(GovAction.Info)
          case (t, k) => Left(LedgerDecodeError.TaggedArity("governance action", t, k))
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
      .map(n, "voting procedures")
      .leftMap(DecodeError.InvalidStructure(_))
      .flatMap(_.traverse { case (voter, inner) =>
        for
          v <- Voter.decode(voter).leftMap(DecodeError.InvalidStructure(_))
          votes <- CborRead
            .map(inner, "votes")
            .leftMap(DecodeError.InvalidStructure(_))
            .flatMap(_.traverse { case (id, procedure) =>
              for
                actionId <- GovActionId.decode(id).leftMap(DecodeError.InvalidStructure(_))
                p <- CborRead
                  .arrayN(procedure, 2, "voting procedure")
                  .leftMap(DecodeError.InvalidStructure(_))
                vote <- CborRead
                  .uint(p(0), "vote")
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
      _ <- CborRead.unique(all.map(_._1), "voters")
      _ <- all.traverse { case (_, votes) =>
        CborRead
          .nonEmpty(votes, "votes of a voter", required = true)
          .flatMap(vs => CborRead.unique(vs.map(_._1), "governance action ids"))
      }
    yield all
    structure.leftMap(DecodeError.InvalidStructure(_))
