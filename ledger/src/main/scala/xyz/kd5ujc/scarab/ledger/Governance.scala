// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import cats.Order
import scala.collection.immutable.{SortedSet, TreeMap}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value as CborValue}

/** `[transaction_id, index]` naming a governance action. */
final case class GovActionId(txId: Bytes, index: BigInt)

object GovActionId:
  /** The derived `Ord GovActionId` (`Conway/Governance/Procedures.hs:175-179`, cardano-ledger
    * f649f975): the transaction id, then the index.
    */
  given ordering: Ordering[GovActionId] = (a, b) =>
    val ids = a.txId.compareTo(b.txId)
    if ids != 0 then ids else a.index.compare(b.index)

  /** `GovActionId` (`Conway/Governance/Procedures.hs:182-190`, cardano-ledger f649f975): `RecD`
    * before 12 and `decodeRecordNamed` from 12, of a transaction id and a `GovActionIx`, a `Word16`
    * (`Procedures.hs:162`).
    */
  def decode(n: Node)(using ByteStrings): Either[LedgerDecodeError, GovActionId] =
    decode(n, DecoderVersion.of(xyz.kd5ujc.scarab.protocol.Era.Conway))

  def decode(n: Node, version: DecoderVersion)(using
      ByteStrings
  ): Either[LedgerDecodeError, GovActionId] =
    CborRead
      .record(n, 2, DecodeField.GovernanceActionId, version)
      .flatMap(xs =>
        (
          CborRead.bytesN(xs(0), 32, DecodeField.GovernanceActionTxId),
          Unsigned.read[Unsigned.Width.W16](xs(1), DecodeField.ActionIndex).map(_.value)
        )
          .mapN(GovActionId(_, _))
      )

  private[ledger] def optional(n: Node, version: DecoderVersion)(using
      ByteStrings
  ): Either[LedgerDecodeError, Option[GovActionId]] =
    n.value match
      case CborValue.Null => Right(None)
      case _              => decode(n, version).map(Some(_))

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

  /** The derived `Ord (Credential r)`: the companion's `Ordering[Credential]`. */
  private def credential(x: Credential, y: Credential): Int =
    summon[Ordering[Credential]].compare(x, y)

  /** `[0, keyhash] / [1, scripthash]` (committee), `[2, ..] / [3, ..]` (DRep), `[4, keyhash]`. */
  def decode(n: Node)(using ByteStrings): Either[LedgerDecodeError, Voter] =
    CborRead.arrayN(n, 2, DecodeField.Voter).flatMap { xs =>
      // `decodeRecordSum "Voter"` (`Conway/Governance/Procedures.hs:355-363`,
      // cardano-ledger f649f975): a full-width tag
      (
        CborRead.uint(xs(0), DecodeField.VoterTag),
        CborRead.bytesN(xs(1), 28, DecodeField.VoterHash)
      ).tupled
        .flatMap { (t, h) =>
          CborRead.small(t) match
            case Some(0) => Right(CommitteeHot(Credential.Key(h)))
            case Some(1) => Right(CommitteeHot(Credential.Script(h)))
            case Some(2) => Right(DRepVoter(Credential.Key(h)))
            case Some(3) => Right(DRepVoter(Credential.Script(h)))
            case Some(4) => Right(Pool(h))
            case _       => Left(LedgerDecodeError.UnknownTag(DecodeField.Voter, t))
        }
    }

enum Vote:
  case No, Yes, Abstain

/** The update a parameter change holds ([[GovAction.ParameterChange]]), the `PParamsUpdate era` of
  * its era (`libs/cardano-ledger-core/src/Cardano/Ledger/Core/PParams.hs:230`, cardano-ledger
  * 9df9f98c): Conway's ([[ConwayParamUpdate]]), or, in a Dijkstra state of the Leios build's
  * layout, Dijkstra's when it sets a parameter Dijkstra adds ([[DijkstraKeysUpdate]]). An update of
  * a Dijkstra state that sets Conway's parameters alone, keys 0 to 33, which Dijkstra's
  * `eraPParams` shares (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/PParams.hs:741-776`), is
  * held as Conway's, as it was before Dijkstra's keys were read.
  */
type ParamChangeUpdate = ConwayParamUpdate | DijkstraKeysUpdate

/** A Dijkstra `PParamsUpdate` that sets at least one of the parameters Dijkstra adds, update keys
  * 34 to 56 (cardano-ledger 9df9f98c `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/PParams.hs:
  * 798-1123`), with whichever of Conway's keys it sets beside them: one update, as the reference
  * holds it. Made only by [[DijkstraKeysUpdate.from]]. The state holds it in a proposal of a
  * Dijkstra state of the Leios build's layout; the rules of those keys (GOV's well-formedness,
  * RATIFY's groups, ENACT) are not run yet, so a transaction does not propose one
  * (`DijkstraPending.ParameterChangeProposal`), a pool's vote on one halts, and the epoch boundary
  * halts before it ratifies a state that holds one.
  */
final case class DijkstraKeysUpdate private (update: dijkstra.DijkstraParamUpdate, firstAdded: Int)

object DijkstraKeysUpdate:
  /** The first update key of the parameters Dijkstra adds. */
  val FirstKey: Int = 34

  /** `update`, when it sets a parameter Dijkstra adds, with the least key it sets of those. */
  def from(update: dijkstra.DijkstraParamUpdate): Option[DijkstraKeysUpdate] =
    update.fields.keySet.filter(_ >= FirstKey).minOption.map(DijkstraKeysUpdate(update, _))

/** Governance action of a proposal procedure. A parameter change holds the update of its era
  * ([[ParamChangeUpdate]]): a typed Conway update that keeps the original encoding of each value
  * ([[ConwayParamUpdate]]), or a Dijkstra one that sets a parameter Dijkstra adds.
  */
enum GovAction:
  case ParameterChange(
      previous: Option[GovActionId],
      update: ParamChangeUpdate,
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

private[ledger] object ProposalIdentity:
  /** The order the ordered set of a body's proposals finds its repeats with ([[IdentityOrder]]). */
  given Ordering[ProposalIdentity] = IdentityOrder.proposalIdentity

/** A governance action by meaning (`Governance/Procedures.hs:815-848` (cardano-ledger f649f975),
  * `deriving Ord`): a parameter update by the typed value of each field (`PParamsUpdate`), and
  * treasury withdrawals as the `Map AccountAddress Coin` they are, whatever the order of the
  * entries; any other action is its decoded value.
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
        case GovAction.ParameterChange(previous, update: ConwayParamUpdate, policy) =>
          ActionIdentity.Update(previous, update.byKey, policy)
        case GovAction.TreasuryWithdrawals(ws, policy) =>
          ActionIdentity.Withdrawals(TreeMap.from(ws), policy)
        case other => ActionIdentity.Other(other)
      ,
      p.anchor
    )

  /** The largest major version a hard fork action may name: the action reads its version with
    * `decodeProtVer @ConwayEra`
    * (`eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Procedures.hs:886-889`, cardano-ledger
    * f649f975), whose bound is `succVersion` of `ProtVerHigh ConwayEra`, 11
    * (`libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:253-263`,
    * `libs/cardano-ledger-core/internal/Cardano/Ledger/Internal/Definition/Era.hs:133`).
    */
  private val HardForkMaxMajor: BigInt = 12

  private def optionalHash(n: Node)(using ByteStrings): Either[LedgerDecodeError, Option[Bytes]] =
    n.value match
      case CborValue.Null => Right(None)
      case _              => CborRead.bytesN(n, 28, DecodeField.PolicyHash).map(Some(_))

  /** `ProposalProcedure` (`Conway/Governance/Procedures.hs:522-534`, cardano-ledger f649f975):
    * `RecD` before 12 and `decodeRecordNamed` from 12.
    */
  def decode(n: Node, version: DecoderVersion): Either[LedgerDecodeError, Proposal] =
    given ByteStrings = ByteStrings.of(version)
    for
      xs <- CborRead.record(n, 4, DecodeField.ProposalProcedure, version)
      deposit <- CborRead.uint(xs(0), DecodeField.ProposalDeposit)
      account <- CborRead
        .bytes(xs(1), DecodeField.ProposalRewardAccount)
        .flatMap(RewardAccount.decode)
      action <- action(xs(2), version)
      anchor <- Anchor.decode(xs(3), version)
    yield Proposal(deposit, account, action, anchor)

  /** A map whose repeated key fails from decoder version 9 (`decodeMap`, Decoder.hs). */
  private def keysUnique[K: Ordering, V](
      kvs: Vector[(K, V)],
      what: DecodeSite,
      version: DecoderVersion
  ): Either[LedgerDecodeError, Vector[(K, V)]] =
    if version.atLeast(9) then CborRead.unique(kvs.map(_._1), what).as(kvs) else Right(kvs)

  private def action(n: Node, version: DecoderVersion)(using
      ByteStrings
  ): Either[LedgerDecodeError, GovAction] =
    CborRead.array(n, DecodeField.GovernanceAction).flatMap { xs =>
      for
        tag <- xs.headOption
          .toRight(LedgerDecodeError.Empty(DecodeField.GovernanceAction))
          .flatMap(CborRead.uint(_, DecodeField.ActionTag))
        // `Summands "GovAction"` (`Conway/Governance/Procedures.hs:876-895`,
        // cardano-ledger f649f975): a full-width tag
        a <- (CborRead.small(tag), xs.size) match
          case (Some(0), 4) =>
            (
              GovActionId.optional(xs(1), version),
              ConwayParamUpdate.decode(xs(2), version),
              optionalHash(xs(3))
            ).mapN(GovAction.ParameterChange(_, _, _))
          case (Some(1), 3) =>
            (
              GovActionId.optional(xs(1), version),
              ParamUpdate.protocolVersion(xs(2), HardForkMaxMajor)
            ).mapN((p, v) => GovAction.HardFork(p, v._1, v._2))
          case (Some(2), 3) =>
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
          case (Some(3), 2) => GovActionId.optional(xs(1), version).map(GovAction.NoConfidence(_))
          case (Some(4), 5) =>
            (
              GovActionId.optional(xs(1), version),
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
              CborRead.rational(xs(4), DecodeField.Quorum, CborRead.Bound.Unit, version)
            ).mapN((p, r, a, q) =>
              GovAction.UpdateCommittee(p, SortedSet.from(r), TreeMap.from(a), q)
            )
          case (Some(5), 3) =>
            (
              GovActionId.optional(xs(1), version),
              // `Constitution`: RecD before 12, decodeRecordNamed from 12 (Procedures.hs:985-995, f649f975)
              CborRead
                .record(xs(2), 2, DecodeField.Constitution, version)
                .flatMap(c => (optionalHash(c(1)), Anchor.decode(c(0), version)).tupled)
            ).mapN((p, c) => GovAction.NewConstitution(p, c._1, c._2))
          case (Some(6), 1) => Right(GovAction.Info)
          case (_, k) => Left(LedgerDecodeError.TaggedArity(DecodeField.GovernanceAction, tag, k))
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
      n: Node,
      version: DecoderVersion
  )(using
      ByteStrings
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
                actionId <- GovActionId.decode(id, version).leftMap(DecodeError.InvalidStructure(_))
                // `VotingProcedure`: RecD before 12, decodeRecordNamed from 12 (Procedures.hs:456-466, f649f975)
                p <- CborRead
                  .record(procedure, 2, DecodeField.VotingProcedure, version)
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
                    Anchor
                      .decode(p(1), version)
                      .leftMap(DecodeError.InvalidStructure(_))
                      .map(Some(_))
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
