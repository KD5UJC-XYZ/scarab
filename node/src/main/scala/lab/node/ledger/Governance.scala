// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import cats.syntax.all.*
import lab.cbor.{Bytes, Node, Value as CborValue}

/** `[transaction_id, index]` naming a governance action. */
final case class GovActionId(txId: Bytes, index: BigInt)

object GovActionId:
  def decode(n: Node): Either[String, GovActionId] =
    CborRead
      .arrayN(n, 2, "governance action id")
      .flatMap(xs =>
        (
          CborRead.bytesN(xs(0), 32, "governance action tx id"),
          CborRead.uint(xs(1), "action index")
        )
          .mapN(GovActionId(_, _))
      )

  private[ledger] def optional(n: Node): Either[String, Option[GovActionId]] = n.value match
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
  def decode(n: Node): Either[String, Voter] =
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
          case (t, _) => Left(s"voter tag $t")
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
  case NewConstitution(previous: Option[GovActionId], script: Option[Bytes])
  case Info

/** `[deposit, reward_account, gov_action, anchor]`. */
final case class Proposal(deposit: BigInt, returnAccount: RewardAccount, action: GovAction)

object Proposal:
  private def optionalHash(n: Node): Either[String, Option[Bytes]] = n.value match
    case CborValue.Null => Right(None)
    case _              => CborRead.bytesN(n, 28, "policy hash").map(Some(_))

  def decode(n: Node): Either[String, Proposal] =
    for
      xs <- CborRead.arrayN(n, 4, "proposal procedure")
      deposit <- CborRead.uint(xs(0), "proposal deposit")
      account <- CborRead.bytes(xs(1), "proposal reward account").flatMap(RewardAccount.decode)
      action <- action(xs(2))
    yield Proposal(deposit, account, action)

  private def action(n: Node): Either[String, GovAction] =
    CborRead.array(n, "governance action").flatMap { xs =>
      for
        tag <- xs.headOption
          .toRight("empty governance action")
          .flatMap(CborRead.uint(_, "action tag"))
        a <- (tag.toInt, xs.size) match
          case (0, 4) =>
            (
              GovActionId.optional(xs(1)),
              CborRead
                .map(xs(2), "parameter update")
                .flatMap(
                  _.traverse { case (k, v) =>
                    CborRead.uint(k, "parameter index").map(_.toInt -> v.original)
                  }
                )
                .map(kvs => ParamUpdate(kvs.toMap)),
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
                }),
              optionalHash(xs(2))
            ).mapN(GovAction.TreasuryWithdrawals(_, _))
          case (3, 2) => GovActionId.optional(xs(1)).map(GovAction.NoConfidence(_))
          case (4, 5) =>
            (
              GovActionId.optional(xs(1)),
              CborRead
                .set(xs(2), "removed members")
                .flatMap(_.traverse(Credential.decode(_, "committee member"))),
              CborRead
                .map(xs(3), "added members")
                .flatMap(_.traverse { case (k, v) =>
                  (Credential.decode(k, "committee member"), CborRead.uint(v, "member term")).tupled
                }),
              CborRead.rational(xs(4), "quorum")
            ).mapN((p, r, a, q) => GovAction.UpdateCommittee(p, r.toSet, a.toMap, q))
          case (5, 3) =>
            (
              GovActionId.optional(xs(1)),
              CborRead.arrayN(xs(2), 2, "constitution").flatMap(c => optionalHash(c(1)))
            ).mapN(GovAction.NewConstitution(_, _))
          case (6, 1) => Right(GovAction.Info)
          case (t, k) => Left(s"governance action $t with $k elements")
      yield a
    }

/** `{ + voter => { + gov_action_id => [vote, anchor / null] } }`. */
object VotingProcedures:
  def decode(n: Node): Either[String, Vector[(Voter, Vector[(GovActionId, Vote)])]] =
    CborRead
      .map(n, "voting procedures")
      .flatMap(_.traverse { case (voter, inner) =>
        for
          v <- Voter.decode(voter)
          votes <- CborRead
            .map(inner, "votes")
            .flatMap(_.traverse { case (id, procedure) =>
              for
                actionId <- GovActionId.decode(id)
                p <- CborRead.arrayN(procedure, 2, "voting procedure")
                vote <- CborRead.uint(p(0), "vote").flatMap {
                  case x if x == 0 => Right(Vote.No)
                  case x if x == 1 => Right(Vote.Yes)
                  case x if x == 2 => Right(Vote.Abstain)
                  case x           => Left(s"vote $x")
                }
              yield actionId -> vote
            })
        yield v -> votes
      })
