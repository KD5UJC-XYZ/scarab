// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import xyz.kd5ujc.scarab.ledger.LedgerDecodeError

import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.CborRead

/** An update proposal: a protocol version with parameter changes, a software version, metadata. */
final case class ByronProposal(
    raw: Bytes,
    id: Bytes,
    version: ByronVersion,
    update: ByronParamsUpdate,
    softwareName: String,
    softwareNumber: BigInt,
    signedBody: Bytes,
    issuer: Bytes,
    signature: Bytes
)

/** A vote for a proposal (the decision bit was removed from the format; every vote is a yes). */
final case class ByronVote(voter: Bytes, proposalId: Bytes, rawProposalId: Bytes, signature: Bytes)

/** The update payload of a block: an optional proposal and votes. `raw` is what the header's proof
  * covers.
  */
final case class UpdatePayload(
    raw: Bytes,
    proposal: Option[ByronProposal],
    votes: Vector[ByronVote]
)

object UpdatePayload:
  val empty: UpdatePayload = UpdatePayload(Bytes.empty, None, Vector.empty)

  private def proposal(n: Node): Either[LedgerDecodeError, ByronProposal] =
    CborRead.arrayN(n, 7, "update proposal").flatMap { xs =>
      for
        version <- ByronVersion.decode(xs(0)).leftMap(LedgerDecodeError.Version(_))
        update <- ByronParamsUpdate.decode(xs(1))
        sv <- CborRead.arrayN(xs(2), 2, "software version")
        name <- CborRead.text(sv(0), "software name")
        number <- CborRead.uint(sv(1), "software number")
        _ <- CborRead.map(xs(3), "proposal metadata")
        _ <- CborRead.map(xs(4), "proposal attributes")
        issuer <- CborRead.bytesN(xs(5), 64, "proposal issuer")
        signature <- CborRead.bytesN(xs(6), 64, "proposal signature")
      yield ByronProposal(
        n.original,
        Blake2b.hash256.hash(n.original),
        version,
        update,
        name,
        number,
        ByronCbor.of(0x85) ++ xs.take(5).foldLeft(Bytes.empty)(_ ++ _.original),
        issuer,
        signature
      )
    }

  private def vote(n: Node): Either[LedgerDecodeError, ByronVote] =
    CborRead.arrayN(n, 4, "update vote").flatMap { xs =>
      (
        CborRead.bytesN(xs(0), 64, "voter"),
        CborRead.bytesN(xs(1), 32, "voted proposal"),
        CborRead.bool(xs(2), "vote decision"),
        CborRead.bytesN(xs(3), 64, "vote signature")
      ).mapN((voter, id, _, sig) => ByronVote(voter, id, xs(1).original, sig))
    }

  def decode(n: Node): Either[LedgerDecodeError, UpdatePayload] =
    CborRead.arrayN(n, 2, "update payload").flatMap { xs =>
      for
        maybe <- CborRead.array(xs(0), "update proposal")
        p <- maybe match
          case Vector()  => Right(None)
          case Vector(x) => proposal(x).map(Some(_))
          case _         => Left(LedgerDecodeError.AtMostOne("update proposals", maybe.size))
        votes <- CborRead.array(xs(1), "update votes").flatMap(_.traverse(vote))
      yield UpdatePayload(n.original, p, votes)
    }

/** Why an update proposal, vote or endorsement is refused. */
enum UpdateError:
  case InvalidProposer(proposer: Bytes)
  case InvalidSignature
  case NullUpdateProposal
  case DuplicateProtocolVersion(version: ByronVersion)
  case InvalidProtocolVersion(version: ByronVersion, adopted: ByronVersion)
  case ProposalTooLarge(max: BigInt, actual: BigInt)
  case MaxBlockSizeTooLarge(max: BigInt, actual: BigInt)
  case MaxTxSizeTooLarge(max: BigInt, actual: BigInt)
  case InvalidScriptVersion(adopted: BigInt, proposed: BigInt)
  case DuplicateSoftwareVersion(name: String)
  case InvalidSoftwareVersion(name: String, number: BigInt)
  case VotingProposalNotRegistered(id: Bytes)
  case VoterNotDelegate(voter: Bytes)
  case VoteAlreadyCast(delegator: Bytes)
  case MultipleProposalsForProtocolVersion(version: ByronVersion)

  def message: String = this match
    case InvalidProposer(p)          => s"proposer ${p.hex.take(16)} is not a delegate"
    case InvalidSignature            => "update signature rejected"
    case NullUpdateProposal          => "update proposal changes nothing"
    case DuplicateProtocolVersion(v) => s"protocol version ${v.render} is already proposed"
    case InvalidProtocolVersion(v, a) =>
      s"protocol version ${v.render} cannot follow the adopted ${a.render}"
    case ProposalTooLarge(m, a)         => s"proposal of $a bytes exceeds $m"
    case MaxBlockSizeTooLarge(m, a)     => s"proposed block size $a exceeds twice $m"
    case MaxTxSizeTooLarge(m, a)        => s"proposed tx size $a is not below the block size $m"
    case InvalidScriptVersion(a, p)     => s"script version $p cannot follow $a"
    case DuplicateSoftwareVersion(n)    => s"software $n is already proposed"
    case InvalidSoftwareVersion(n, v)   => s"software $n version $v is not the next"
    case VotingProposalNotRegistered(i) => s"vote for unregistered proposal ${i.hex.take(16)}"
    case VoterNotDelegate(v)            => s"voter ${v.hex.take(16)} is not a delegate"
    case VoteAlreadyCast(d)             => s"genesis key ${d.hex.take(16)} already voted"
    case MultipleProposalsForProtocolVersion(v) =>
      s"several proposals for protocol version ${v.render}"

final case class CandidateUpdate(slot: BigInt, version: ByronVersion, params: ByronParams)
final case class ProtocolProposal(version: ByronVersion, params: ByronParams)
final case class AppVersion(number: BigInt, slot: BigInt)

/** The update-interface state (`Update.Validation.Interface.State`). */
final case class UpdateState(
    adoptedVersion: ByronVersion,
    adoptedParams: ByronParams,
    candidates: Vector[CandidateUpdate],
    appVersions: Map[String, AppVersion],
    registeredProtocol: Map[Bytes, ProtocolProposal],
    registeredSoftware: Map[Bytes, (String, BigInt)],
    confirmed: Map[Bytes, BigInt],
    votes: Map[Bytes, Set[Bytes]],
    endorsements: Set[(ByronVersion, Bytes)],
    registrationSlot: Map[Bytes, BigInt]
)

object ByronUpdate:
  /** What the rules read: the protocol magic bytes, `k`, the slot and the delegation map. */
  final case class Env(
      magicId: BigInt,
      magic: Bytes,
      k: BigInt,
      slot: BigInt,
      genesisKeys: Int,
      delegation: Map[Bytes, Bytes]
  ):
    def delegatorOf(delegate: Bytes): Option[Bytes] =
      delegation.collectFirst { case (g, d) if d == delegate => g }
    def isDelegate(h: Bytes): Boolean = delegation.valuesIterator.contains(h)

  def initial(params: ByronParams): UpdateState =
    UpdateState(
      ByronVersion.zero,
      params,
      Vector.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Set.empty,
      Map.empty
    )

  private val MainnetStagingMagic = BigInt(633343913)

  // ---------------------------------------------------------------- proposals

  def registerProposal(
      env: Env,
      st: UpdateState,
      p: ByronProposal
  ): Either[UpdateError, UpdateState] =
    val proposer = ByronCbor.keyHash(p.issuer)
    val adopted = st.adoptedParams
    val protocolChanged = !(p.version == st.adoptedVersion && p.update(adopted) == adopted)
    val softwareChanged =
      st.appVersions.get(p.softwareName).forall(_.number != p.softwareNumber)
    val exempt = env.magicId == MainnetStagingMagic &&
      (env.slot == BigInt(969188) || env.slot == BigInt(1915231))
    for
      _ <- Either.cond(env.isDelegate(proposer), (), UpdateError.InvalidProposer(proposer))
      _ <- Either.cond(
        ByronSig.verify(
          p.issuer,
          p.signature,
          ByronSig.tag(ByronSig.SignUpdateProposal, env.magic) ++ p.signedBody
        ),
        (),
        UpdateError.InvalidSignature
      )
      _ <- Either.cond(
        protocolChanged || softwareChanged || exempt,
        (),
        UpdateError.NullUpdateProposal
      )
      protocol <-
        if protocolChanged then registerProtocol(st, p) else Right(st.registeredProtocol)
      software <-
        if softwareChanged then registerSoftware(st, p) else Right(st.registeredSoftware)
    yield st.copy(
      registeredProtocol = protocol,
      registeredSoftware = software,
      registrationSlot = st.registrationSlot.updated(p.id, env.slot)
    )

  private def registerProtocol(
      st: UpdateState,
      p: ByronProposal
  ): Either[UpdateError, Map[Bytes, ProtocolProposal]] =
    val adopted = st.adoptedParams
    val proposed = p.update(adopted)
    // pvCanFollow, Validation/Registration.hs:489-499 (ledger f649f97). Components are bounded.
    val canFollow =
      val a = st.adoptedVersion
      val v = p.version
      Ordering[ByronVersion].lt(a, v) && ((v.major - a.major) match
        case 0 => v.minor == a.minor + 1
        case 1 => v.minor == 0
        case _ => false)
    for
      _ <- Either.cond(
        !st.registeredProtocol.valuesIterator.exists(_.version == p.version),
        (),
        UpdateError.DuplicateProtocolVersion(p.version)
      )
      _ <- Either.cond(
        canFollow,
        (),
        UpdateError.InvalidProtocolVersion(p.version, st.adoptedVersion)
      )
      _ <- Either.cond(
        BigInt(p.raw.size) <= adopted.maxProposalSize,
        (),
        UpdateError.ProposalTooLarge(adopted.maxProposalSize, p.raw.size)
      )
      _ <- Either.cond(
        proposed.maxBlockSize <= 2 * adopted.maxBlockSize,
        (),
        UpdateError.MaxBlockSizeTooLarge(adopted.maxBlockSize, proposed.maxBlockSize)
      )
      _ <- Either.cond(
        proposed.maxTxSize < proposed.maxBlockSize,
        (),
        UpdateError.MaxTxSizeTooLarge(proposed.maxBlockSize, proposed.maxTxSize)
      )
      diff = proposed.scriptVersion - adopted.scriptVersion
      _ <- Either.cond(
        diff >= 0 && diff <= 1,
        (),
        UpdateError.InvalidScriptVersion(adopted.scriptVersion, proposed.scriptVersion)
      )
    yield st.registeredProtocol.updated(p.id, ProtocolProposal(p.version, proposed))

  private def registerSoftware(
      st: UpdateState,
      p: ByronProposal
  ): Either[UpdateError, Map[Bytes, (String, BigInt)]] =
    val follows = st.appVersions.get(p.softwareName) match
      case None    => p.softwareNumber == 0 || p.softwareNumber == 1
      case Some(a) => p.softwareNumber == a.number + 1
    for
      _ <- Either.cond(
        !st.registeredSoftware.valuesIterator.exists(_._1 == p.softwareName),
        (),
        UpdateError.DuplicateSoftwareVersion(p.softwareName)
      )
      _ <- Either.cond(
        follows,
        (),
        UpdateError.InvalidSoftwareVersion(p.softwareName, p.softwareNumber)
      )
    yield st.registeredSoftware.updated(p.id, (p.softwareName, p.softwareNumber))

  // ---------------------------------------------------------------- votes

  private def registerVote(
      env: Env,
      st: UpdateState,
      v: ByronVote
  ): Either[UpdateError, UpdateState] =
    val voter = ByronCbor.keyHash(v.voter)
    val signed = ByronCbor.of(0x82) ++ v.rawProposalId ++ ByronCbor.of(0xf5)
    for
      _ <- Either.cond(
        st.registrationSlot.contains(v.proposalId),
        (),
        UpdateError.VotingProposalNotRegistered(v.proposalId)
      )
      delegator <- env.delegatorOf(voter).toRight(UpdateError.VoterNotDelegate(voter))
      _ <- Either.cond(
        !st.votes.getOrElse(v.proposalId, Set.empty).contains(delegator),
        (),
        UpdateError.VoteAlreadyCast(delegator)
      )
      _ <- Either.cond(
        ByronSig.verify(
          v.voter,
          v.signature,
          ByronSig.tag(ByronSig.SignUpdateVote, env.magic) ++ signed
        ),
        (),
        UpdateError.InvalidSignature
      )
    yield
      val votes = st.votes.updated(
        v.proposalId,
        st.votes.getOrElse(v.proposalId, Set.empty) + delegator
      )
      val threshold = st.adoptedParams.adoptionThreshold(env.genesisKeys)
      val confirmed =
        if votes(v.proposalId).size >= threshold && !st.confirmed.contains(v.proposalId) then
          st.confirmed.updated(v.proposalId, env.slot)
        else st.confirmed
      st.copy(votes = votes, confirmed = confirmed)

  def registerVotes(
      env: Env,
      st: UpdateState,
      votes: Vector[ByronVote]
  ): Either[UpdateError, UpdateState] =
    votes.foldLeftM(st)(registerVote(env, _, _)).map { after =>
      val confirmedSoftware = after.registeredSoftware.view.filterKeys(after.confirmed.contains)
      val appVersions = confirmedSoftware.values.map { (name, number) =>
        name -> AppVersion(number, env.slot)
      }.toMap
      after.copy(
        appVersions = after.appVersions ++ appVersions,
        registeredSoftware = after.registeredSoftware.removedAll(after.confirmed.keys)
      )
    }

  // ---------------------------------------------------------------- endorsements

  def registerEndorsement(
      env: Env,
      st: UpdateState,
      version: ByronVersion,
      endorser: Bytes
  ): Either[UpdateError, UpdateState] =
    val matching = st.registeredProtocol.filter(_._2.version == version).toList
    val endorsements =
      env.delegatorOf(endorser).fold(st.endorsements)(g => st.endorsements + ((version, g)))
    def stable(id: Bytes) = st.confirmed.get(id).exists(s => s + 2 * env.k <= env.slot)
    val afterEndorsement: Either[UpdateError, UpdateState] = matching match
      case Nil => Right(st)
      case (id, proposal) :: Nil =>
        if !stable(id) then Right(st)
        else if endorsements.count(_._1 == version) >= st.adoptedParams.adoptionThreshold(
            env.genesisKeys
          )
        then
          val candidate = CandidateUpdate(env.slot, version, proposal.params)
          val candidates = st.candidates match
            case head +: _ if Ordering[ByronVersion].lt(head.version, version) =>
              candidate +: st.candidates
            case Vector() => Vector(candidate)
            case other    => other
          Right(st.copy(candidates = candidates, endorsements = endorsements))
        else Right(st.copy(endorsements = endorsements))
      case _ => Left(UpdateError.MultipleProposalsForProtocolVersion(version))
    afterEndorsement.map { after =>
      val ttl = st.adoptedParams.updateProposalTtl
      val keep = after.registrationSlot.filter((_, s) => env.slot <= s + ttl).keySet ++
        after.confirmed.keySet
      val protocol = after.registeredProtocol.view.filterKeys(keep.contains).toMap
      val versions = protocol.values.map(_.version).toSet
      after.copy(
        registeredProtocol = protocol,
        registeredSoftware = after.registeredSoftware.view.filterKeys(keep.contains).toMap,
        votes = after.votes.view.filterKeys(keep.contains).toMap,
        endorsements = after.endorsements.filter(e => versions.contains(e._1)),
        registrationSlot = after.registrationSlot.view.filterKeys(keep.contains).toMap
      )
    }

  /** `registerUpdate`: the proposal of the block, its votes, then the block's own endorsement. */
  def register(
      env: Env,
      st: UpdateState,
      payload: UpdatePayload,
      version: ByronVersion,
      issuer: Bytes
  ): Either[UpdateError, UpdateState] =
    for
      withProposal <- payload.proposal.fold(Right(st))(registerProposal(env, st, _))
      withVotes <- registerVotes(env, withProposal, payload.votes)
      done <- registerEndorsement(env, withVotes, version, issuer)
    yield done

  // ---------------------------------------------------------------- epochs

  /** `registerEpoch` / `tryBumpVersion`: entering `epoch`, the newest candidate that has been
    * stable for `4k` slots becomes the adopted protocol; adopting resets the proposal state.
    */
  def registerEpoch(st: UpdateState, k: BigInt, epochSlots: BigInt, epoch: BigInt): UpdateState =
    val firstSlot = epoch * epochSlots
    st.candidates.find(c => c.slot + 4 * k <= firstSlot) match
      case Some(c) if c.version != st.adoptedVersion =>
        st.copy(
          adoptedVersion = c.version,
          adoptedParams = c.params,
          candidates = Vector.empty,
          registeredProtocol = Map.empty,
          registeredSoftware = Map.empty,
          confirmed = Map.empty,
          votes = Map.empty,
          endorsements = Set.empty,
          registrationSlot = Map.empty
        )
      case _ => st
