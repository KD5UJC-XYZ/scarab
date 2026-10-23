// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import cats.syntax.all.*
import io.circe.{ACursor, Decoder}
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.ConfigError

/** `[url, data_hash]` attached to certificates, proposals and the constitution. */
final case class Anchor(url: String, hash: Bytes)

object Anchor:
  def decode(n: Node): Either[LedgerDecodeError, Anchor] =
    CborRead
      .arrayN(n, 2, DecodeField.Anchor)
      .flatMap(xs =>
        (
          CborRead.text(xs(0), DecodeField.AnchorUrl),
          CborRead.bytesN(xs(1), 32, DecodeField.AnchorHash)
        )
          .mapN(Anchor(_, _))
      )

  def optional(n: Node): Either[LedgerDecodeError, Option[Anchor]] = n.value match
    case xyz.kd5ujc.scarab.codec.Value.Null => Right(None)
    case _                                  => decode(n).map(Some(_))

/** The constitution: an anchor and the optional guardrails script. */
final case class Constitution(anchor: Anchor, script: Option[Bytes])

/** The constitutional committee: cold credential -> last epoch of its term, and the quorum. */
final case class Committee(members: Map[Credential, BigInt], threshold: Rational)

/** What the committee state records about a cold credential. */
enum CommitteeAuth:
  case Hot(credential: Credential)
  case Resigned(anchor: Option[Anchor])

/** The four governance lineages ("purposes"). */
enum Purposeful:
  case ParamUpdate, HardFork, Committee, Constitution

/** The last enacted action of each lineage (`PrevGovActionIds`). */
final case class PrevIds(
    paramUpdate: Option[GovActionId] = None,
    hardFork: Option[GovActionId] = None,
    committee: Option[GovActionId] = None,
    constitution: Option[GovActionId] = None
):
  def get(p: Purposeful): Option[GovActionId] = p match
    case Purposeful.ParamUpdate  => paramUpdate
    case Purposeful.HardFork     => hardFork
    case Purposeful.Committee    => committee
    case Purposeful.Constitution => constitution
  def set(p: Purposeful, id: Option[GovActionId]): PrevIds = p match
    case Purposeful.ParamUpdate  => copy(paramUpdate = id)
    case Purposeful.HardFork     => copy(hardFork = id)
    case Purposeful.Committee    => copy(committee = id)
    case Purposeful.Constitution => copy(constitution = id)

object GovActions:
  /** The lineage an action belongs to and the previous action it names, if it has a lineage. */
  def lineage(a: GovAction): Option[(Purposeful, Option[GovActionId])] = a match
    case GovAction.ParameterChange(p, _, _)    => Some(Purposeful.ParamUpdate -> p)
    case GovAction.HardFork(p, _, _)           => Some(Purposeful.HardFork -> p)
    case GovAction.NoConfidence(p)             => Some(Purposeful.Committee -> p)
    case GovAction.UpdateCommittee(p, _, _, _) => Some(Purposeful.Committee -> p)
    case GovAction.NewConstitution(p, _, _)    => Some(Purposeful.Constitution -> p)
    case _                                     => None

/** A proposal in the store with the votes cast on it. */
final case class GovActionState(
    id: GovActionId,
    committeeVotes: Map[Credential, Vote],
    drepVotes: Map[Credential, Vote],
    poolVotes: Map[Bytes, Vote],
    proposal: Proposal,
    proposedIn: BigInt,
    expiresAfter: BigInt
):
  def action: GovAction = proposal.action

  /** The vote of `voter` on this action (`proposalsAddVote`). */
  def cast(voter: Voter, vote: Vote): GovActionState = voter match
    case Voter.DRepVoter(c)    => copy(drepVotes = drepVotes.updated(c, vote))
    case Voter.Pool(h)         => copy(poolVotes = poolVotes.updated(h, vote))
    case Voter.CommitteeHot(c) => copy(committeeVotes = committeeVotes.updated(c, vote))

/** The proposal store: proposals in submission order and the roots of the four lineage trees. The
  * parent of a proposal is the action id it names; a proposal can be added when that is the root of
  * its lineage or another stored proposal of the same lineage.
  */
final case class Proposals(actions: Vector[GovActionState], roots: PrevIds):
  lazy val byId: Map[GovActionId, GovActionState] = actions.map(a => a.id -> a).toMap

  /** Add a proposal; `None` if its previous action is neither the root nor a stored proposal. */
  def add(gas: GovActionState): Option[Proposals] =
    val ok = GovActions.lineage(gas.action) match
      case None => true
      case Some((purpose, parent)) =>
        parent == roots.get(purpose) ||
        parent.exists(p =>
          byId.get(p).exists(s => GovActions.lineage(s.action).exists(_._1 == purpose))
        )
    Option.when(ok)(copy(actions = actions :+ gas))

  def vote(voter: Voter, vote: Vote, id: GovActionId): Proposals =
    withVotes(Vector((voter, vote, id)))

  /** The votes of a transaction cast (`proposalsAddVote`), the actions walked once however many
    * votes there are: the vote of a voter on an action replaces the one it had, and of two votes of
    * a voter on one action the later counts. The store is rebuilt with one pass over its actions,
    * where casting the votes one by one maps over every action for every vote (the preprod epochs
    * 187 to 193 carry 648,752 committee votes on five proposals).
    */
  def withVotes(votes: Vector[(Voter, Vote, GovActionId)]): Proposals =
    if votes.isEmpty then this
    else
      val byAction = votes.groupMap(_._3)(v => (v._1, v._2))
      copy(actions = actions.map { g =>
        byAction.get(g.id).fold(g)(_.foldLeft(g) { case (a, (voter, v)) => a.cast(voter, v) })
      })

  private def children(id: GovActionId, purpose: Purposeful): Vector[GovActionId] =
    actions.collect {
      case g if GovActions.lineage(g.action).contains((purpose, Some(id))) => g.id
    }

  /** The ids with every descendant. */
  private def withDescendants(ids: Set[GovActionId]): Set[GovActionId] =
    @annotation.tailrec
    def loop(frontier: Vector[GovActionId], acc: Set[GovActionId]): Set[GovActionId] =
      if frontier.isEmpty then acc
      else
        val next = frontier
          .flatMap(i =>
            byId
              .get(i)
              .flatMap(g => GovActions.lineage(g.action))
              .toVector
              .flatMap { case (purpose, _) => children(i, purpose) }
          )
          .filterNot(acc)
        loop(next.distinct, acc ++ next)
    loop(ids.toVector, ids)

  /** Remove the ids and their descendants; returns what was removed. */
  def removeWithDescendants(ids: Set[GovActionId]): (Proposals, Vector[GovActionState]) =
    val all = withDescendants(ids.filter(byId.contains))
    val (gone, kept) = actions.partition(g => all(g.id))
    (copy(actions = kept), gone)

  /** `proposalsApplyEnactment`: drop the expired actions with their descendants, then promote each
    * enacted action to the root of its lineage, dropping its competitors and their descendants.
    * Returns the new store and the removed (expired, competing) actions.
    */
  def applyEnactment(
      enacted: Vector[GovActionState],
      expired: Set[GovActionId]
  ): (Proposals, Vector[GovActionState], Vector[GovActionState]) =
    val (afterExpiry, expiredGone) = removeWithDescendants(expired)
    val (finalStore, competing) = enacted.foldLeft((afterExpiry, Vector.empty[GovActionState])) {
      case ((ps, removed), gas) =>
        GovActions.lineage(gas.action) match
          case None => (ps.copy(actions = ps.actions.filterNot(_.id == gas.id)), removed)
          case Some((purpose, _)) =>
            val siblings = ps.children0(ps.roots.get(purpose), purpose).filterNot(_ == gas.id)
            val (withoutSiblings, gone) = ps.removeWithDescendants(siblings.toSet)
            (
              withoutSiblings.copy(
                actions = withoutSiblings.actions.filterNot(_.id == gas.id),
                roots = withoutSiblings.roots.set(purpose, Some(gas.id))
              ),
              removed ++ gone
            )
    }
    (finalStore, competing, expiredGone)

  /** The children of a lineage root (the proposals naming it as previous action). */
  private def children0(root: Option[GovActionId], purpose: Purposeful): Vector[GovActionId] =
    actions.collect {
      case g if GovActions.lineage(g.action).contains((purpose, root)) => g.id
    }

  def totalDeposits: BigInt = actions.foldLeft(BigInt(0))(_ + _.proposal.deposit)

  /** Proposal deposits summed by return-account credential. */
  def depositsByCredential: Map[Credential, BigInt] =
    actions.groupMapReduce(_.proposal.returnAccount.credential)(_.proposal.deposit)(_ + _)

object Proposals:
  val empty: Proposals = Proposals(Vector.empty, PrevIds())

/** What enacting the ratified actions changes (`EnactState`). */
final case class EnactState(
    committee: Option[Committee],
    constitution: Constitution,
    params: ProtocolParams,
    prevParams: ProtocolParams,
    treasury: BigInt,
    withdrawals: Map[Credential, BigInt],
    prevIds: PrevIds
)

/** The outcome of ratification: the enact state it leads to, the actions to enact in order, the
  * expired ones and whether a delaying action was enacted.
  */
final case class RatifyState(
    enact: EnactState,
    enacted: Vector[GovActionState],
    expired: Set[GovActionId],
    delayed: Boolean
)

/** The snapshot the DRep pulser works on. */
final case class PulsingSnapshot(
    proposals: Vector[GovActionState],
    drepDistr: Map[DRep, BigInt],
    drepState: Map[Credential, DRepState],
    poolDistr: Map[Bytes, BigInt]
)

object PulsingSnapshot:
  val empty: PulsingSnapshot = PulsingSnapshot(Vector.empty, Map.empty, Map.empty, Map.empty)

/** The Conway genesis: voting parameters, the initial committee and the constitution. */
object ConwayGenesis:
  val noAnchor: Anchor = Anchor("", Bytes(Vector.fill(32)(0.toByte)))
  val noConstitution: Constitution = Constitution(noAnchor, None)

  private def decimalOrRatio(c: ACursor): Either[ConfigError, Rational] =
    c.as[BigDecimal]
      .map(Rational.fromDecimal)
      .orElse(
        (c.downField("numerator").as[BigInt], c.downField("denominator").as[BigInt])
          .mapN((n, d) => Rational(n, d))
      )
      .leftMap(e => ConfigError(ConfigProblem.JsonField("genesis", "rational", e)))

  def votingParams(
      c: ACursor
  ): Either[ConfigError, (PoolVotingThresholds, DRepVotingThresholds, BigInt, BigInt)] =
    def thresholds(name: String, keys: Vector[String]) =
      val cur = c.downField(name)
      if cur.focus.isEmpty then Right(Vector.fill(keys.size)(Rational.zero))
      else keys.traverse(k => decimalOrRatio(cur.downField(k)))
    for
      pool <- thresholds(
        "poolVotingThresholds",
        Vector(
          "motionNoConfidence",
          "committeeNormal",
          "committeeNoConfidence",
          "hardForkInitiation",
          "ppSecurityGroup"
        )
      )
      drep <- thresholds(
        "dRepVotingThresholds",
        Vector(
          "motionNoConfidence",
          "committeeNormal",
          "committeeNoConfidence",
          "updateToConstitution",
          "hardForkInitiation",
          "ppNetworkGroup",
          "ppEconomicGroup",
          "ppTechnicalGroup",
          "ppGovGroup",
          "treasuryWithdrawal"
        )
      )
      p <- PoolVotingThresholds
        .fromVector(pool)
        .leftMap(e => ConfigError(ConfigProblem.LedgerValue(e)))
      d <- DRepVotingThresholds
        .fromVector(drep)
        .leftMap(e => ConfigError(ConfigProblem.LedgerValue(e)))
      minSize <- c
        .downField("committeeMinSize")
        .as[Option[BigInt]]
        .leftMap(e => ConfigError(ConfigProblem.JsonField("conway genesis", "governance", e)))
      maxTerm <- c
        .downField("committeeMaxTermLength")
        .as[Option[BigInt]]
        .leftMap(e => ConfigError(ConfigProblem.JsonField("conway genesis", "governance", e)))
    yield (p, d, minSize.getOrElse(BigInt(0)), maxTerm.getOrElse(BigInt(0)))

  private def credential(s: String): Either[ConfigError, Credential] =
    def hex(h: String) =
      Bytes
        .fromHex(h)
        .leftMap(e => ConfigError(ConfigProblem.Hex(e)))
        .filterOrElse(_.size == 28, ConfigError(ConfigProblem.ByteLength("hash", 28, h.length / 2)))
    if s.startsWith("keyHash-") then hex(s.drop(8)).map(Credential.Key(_))
    else if s.startsWith("scriptHash-") then hex(s.drop(11)).map(Credential.Script(_))
    else Left(ConfigError(ConfigProblem.Credential(s)))

  /** The genesis committee and constitution; absent entries give an empty committee and
    * constitution with an empty anchor, as the genesis defaults do.
    */
  def committeeAndConstitution(
      c: ACursor
  ): Either[ConfigError, (Option[Committee], Constitution)] =
    for
      members <- c
        .downField("committee")
        .downField("members")
        .as[Option[Map[String, BigInt]]]
        .leftMap(e =>
          ConfigError(ConfigProblem.JsonField("conway genesis", "committee members", e))
        )
      parsed <- members.getOrElse(Map.empty).toVector.traverse { case (k, v) =>
        credential(k).map(_ -> v)
      }
      threshold <-
        if c.downField("committee").downField("threshold").focus.isEmpty then Right(Rational.zero)
        else decimalOrRatio(c.downField("committee").downField("threshold"))
      url <- c
        .downField("constitution")
        .downField("anchor")
        .downField("url")
        .as[Option[String]]
        .leftMap(e => ConfigError(ConfigProblem.JsonField("conway genesis", "governance", e)))
      hash <- c
        .downField("constitution")
        .downField("anchor")
        .downField("dataHash")
        .as[Option[String]]
        .leftMap(e => ConfigError(ConfigProblem.JsonField("conway genesis", "governance", e)))
      hashBytes <- hash.traverse(h =>
        Bytes.fromHex(h).leftMap(e => ConfigError(ConfigProblem.Hex(e)))
      )
      script <- c
        .downField("constitution")
        .downField("script")
        .as[Option[String]]
        .leftMap(e => ConfigError(ConfigProblem.JsonField("conway genesis", "governance", e)))
      scriptBytes <- script.traverse(h =>
        Bytes.fromHex(h).leftMap(e => ConfigError(ConfigProblem.Hex(e)))
      )
    // The ledger always has a committee after the Conway translation, possibly with no members.
    yield (
      Some(Committee(parsed.toMap, threshold)),
      Constitution(
        Anchor(url.getOrElse(""), hashBytes.getOrElse(noAnchor.hash)),
        scriptBytes
      )
    )
