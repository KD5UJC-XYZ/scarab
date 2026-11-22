// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

import cats.Monad
import cats.data.NonEmptyVector
import cats.mtl.Raise
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.consensus.state.ChainDepState
import xyz.kd5ujc.scarab.crypto.bls.{
  BlsError,
  Certificate,
  RbHash,
  SeatId,
  SecretKey,
  VerificationKey,
  Vote
}
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64

/** A voting key of the node: a `LeiosSigningKey` of `--shelley-bls-key` with the verification key
  * that finds its seat (`deriveVerKeyDSIGN`, `LeiosVoting.hs:342-347`, ouroboros-consensus
  * 2416493b), derived once.
  */
final case class VotingKey private (secret: SecretKey, verification: VerificationKey)

object VotingKey:
  def of(secret: SecretKey): Either[BlsError, VotingKey] =
    VerificationKey.derive(secret).map(VotingKey(secret, _))

/** `LeiosVoteTiming` (`LeiosDemoTypes.hs:2806-2829`, ouroboros-consensus 2416493b): when the voters
  * of an endorser block may vote, measured from its announcing slot's onset: the window opens after
  * the equivocation wait `3 * L_hdr` and stays open `L_vote`.
  */
final case class LeiosVoteTiming(opens: FiniteDuration, window: FiniteDuration):
  /** The moment the window opens and the moment after which a vote is too late (`newVoteTimers`,
    * `LeiosVoting.hs:191-197`, ouroboros-consensus 2416493b), for an announcing slot that began at
    * `onset`.
    */
  def windowFrom(onset: FiniteDuration): (FiniteDuration, FiniteDuration) =
    val opensAt = onset + opens
    (opensAt, opensAt + window)

object LeiosVoteTiming:
  /** `defaultLeiosVoteTiming`: the timing of eras without the Leios parameters. */
  val default: LeiosVoteTiming = LeiosVoteTiming(3.seconds, 4.seconds)

  /** `leiosVoteTiming`: three announcement periods, then one vote period, each in milliseconds. */
  def of(announcementMillis: Long, voteMillis: Long): LeiosVoteTiming =
    LeiosVoteTiming((3 * announcementMillis).millis, voteMillis.millis)

/** `LeiosNotVotedReason` (`LeiosDemoTypes.hs:1839-1860`, ouroboros-consensus 2416493b): why no vote
  * was cast for an endorser block whose voting window opened. The reference renders the ledger's
  * rejection and the vote state's answers as text; here they stay typed: `I` is the reason a
  * closure does not apply, `U` why it cannot be read back.
  */
enum LeiosNotVotedReason[+I, +U]:
  /** The selection's tip is not the block that announced this endorser block. */
  case ChainTipDoesNotAnnounce

  /** The window had shut when the vote was due or when the closure was validated. */
  case TooLate

  /** The selection's era has no committee, or no key of ours holds a seat in it. */
  case NotOnCommittee

  /** The closure does not apply to the announcing block's ledger state. */
  case EbTxsInvalid(cause: I)

  /** The vote state accepted none of the votes cast, one answer per seat. */
  case VoteRejected(answers: NonEmptyVector[(Vote, AddVoteResult)])

  /** The closure could not be read back, so nothing was validated: no verdict on the block. */
  case ClosureUnavailable(cause: U)

/** `EbClosureVerdict` (`LeiosVoting.hs:432-444`, ouroboros-consensus 2416493b): the outcome of
  * applying an endorser block's transactions to the announcing block's ledger state.
  */
enum EbClosureVerdict[+I, +U]:
  case Valid
  case Invalid(cause: I)
  case Unreadable(cause: U)

/** One consistent view of the node's selection, as the forker at its volatile tip gives one
  * (`LeiosVoting.hs:330-341`, ouroboros-consensus 2416493b): the chain-dependent state of the tip,
  * the tip's hash (none at genesis), the committee of the tip's ledger state
  * ([[LeiosCertification.leiosCommitteeOfTip]]), and the ledger state the closure applies to.
  */
final case class SelectionView[L](
    chainDep: ChainDepState,
    tip: Option[RbHash],
    committee: Option[TipCommittee],
    ledger: L
)

/** What the voting of one endorser block needs of the node: its clock (time since the system
  * start), its selection, the validation of the closure against the selection's ledger state
  * (`validateEbClosure`, `LeiosVoting.hs:457-565`, ouroboros-consensus 2416493b), and the vote
  * state.
  */
trait LeiosVotingEnv[F[_], L, I, U]:
  def now: F[FiniteDuration]
  def selection: F[Option[SelectionView[L]]]
  def validateClosure(point: EbPoint, ledger: L): F[EbClosureVerdict[I, U]]
  def addVote(vote: Vote): F[AddVoteResult]

/** A failure of this node, not a verdict on any block or vote. */
enum LeiosVotingDefect:
  /** Signing a vote failed in the native library. */
  case Signing(seat: SeatId, cause: BlsError)

  /** A block that certifies would extend genesis: `decideLeiosCertify` 's `error`
    * (`NodeKernel/Forge.hs:358`, ouroboros-consensus 2416493b), unreachable, since a block that
    * announced is the parent.
    */
  case CertifyOnGenesis

/** `decideLeiosCertify` 's outcome (`NodeKernel/Forge.hs:324-397`, ouroboros-consensus 2416493b):
  * the certificate this block carries, or why it carries none. The reference returns `Nothing` for
  * every case but the last and traces two of them.
  */
enum CertifyDecision derives CanEqual:
  /** The parent announced nothing this block may still certify, or the era has no Leios. */
  case NothingAnnounced

  /** Fewer than `gap` slots have passed since the announcement. */
  case TooEarly(announced: EbPoint, gap: BigInt)

  /** The closure of the announced endorser block is not in the store (traced there). */
  case ClosureNotDownloaded(announced: EbPoint)

  /** No certificate has been assembled for the announcing block (traced there). */
  case NoCertificate(announced: EbPoint)

  /** This block carries `certificate` and certifies `endorserBlock`. */
  case Certify(certificate: Certificate, endorserBlock: EbHash)

/** The decisions of Leios voting and certifying (`LeiosVoting.hs`, `NodeKernel/Forge.hs`,
  * ouroboros-consensus 2416493b), over the node's capabilities.
  */
object LeiosVoting:

  /** `tipAnnouncerFor` (`LeiosVoting.hs:571-590`, ouroboros-consensus 2416493b): the hash of the
    * selection's tip when the last header applied announced `point` and is itself the tip; the
    * announcement's point is that header's slot and the endorser block's hash
    * ([[LeiosCertification.announcement]]).
    */
  def tipAnnouncerFor(
      chainDep: ChainDepState,
      tip: Option[RbHash],
      point: EbPoint
  ): Option[RbHash] =
    LeiosCertification.announcement(chainDep).filter(_ == point).flatMap(_ => tip)

  /** The seats our keys hold (`LeiosVoting.hs:342-347`, ouroboros-consensus 2416493b): each key its
    * own seat ([[xyz.kd5ujc.scarab.crypto.bls.Committee.seatOf]]), a key without one none, in the
    * keys' order.
    */
  def seats(keys: Vector[VotingKey], committee: TipCommittee): Vector[(VotingKey, SeatId)] =
    keys.flatMap(k => committee.committee.seatOf(k.verification).map(k -> _))

  /** `goVote` (`LeiosVoting.hs:309-419`, ouroboros-consensus 2416493b): the vote of every seat our
    * keys hold for the endorser block at `point`, whose window shuts after `deadline`, or why none
    * was cast. The checks run in the reference's order, the cheap ones first so that a block we
    * would not vote for is never validated: the deadline, the selection's tip announcing the block,
    * the committee and our seats, then the closure, then the deadline again by the clock as it
    * reads once validation is done. Each seat's vote is signed and added; a seat's refusal is in
    * the answers, and only when every seat's vote was refused is there no vote.
    */
  def vote[F[_]: Monad, L, I, U](
      env: LeiosVotingEnv[F, L, I, U],
      keys: Vector[VotingKey],
      point: EbPoint,
      deadline: FiniteDuration
  )(using
      defect: Raise[F, LeiosVotingDefect]
  ): F[Either[LeiosNotVotedReason[I, U], NonEmptyVector[(Vote, AddVoteResult)]]] =
    type Out = Either[LeiosNotVotedReason[I, U], NonEmptyVector[(Vote, AddVoteResult)]]
    def no(reason: LeiosNotVotedReason[I, U]): F[Out] = Monad[F].pure(Left(reason))
    env.now.flatMap { started =>
      if started > deadline then no(LeiosNotVotedReason.TooLate)
      else
        env.selection.flatMap {
          case None => no(LeiosNotVotedReason.ChainTipDoesNotAnnounce)
          case Some(view) =>
            tipAnnouncerFor(view.chainDep, view.tip, point) match
              case None => no(LeiosNotVotedReason.ChainTipDoesNotAnnounce)
              case Some(rbHash) =>
                view.committee.flatMap(c => NonEmptyVector.fromVector(seats(keys, c))) match
                  case None => no(LeiosNotVotedReason.NotOnCommittee)
                  case Some(held) =>
                    env.validateClosure(point, view.ledger).flatMap {
                      case EbClosureVerdict.Unreadable(cause) =>
                        no(LeiosNotVotedReason.ClosureUnavailable(cause))
                      case EbClosureVerdict.Invalid(cause) =>
                        no(LeiosNotVotedReason.EbTxsInvalid(cause))
                      case EbClosureVerdict.Valid =>
                        env.now.flatMap { validated =>
                          if validated > deadline then no(LeiosNotVotedReason.TooLate)
                          else cast(env, held, rbHash).map(answered)
                        }
                    }
        }
    }

  private def cast[F[_]: Monad, L, I, U](
      env: LeiosVotingEnv[F, L, I, U],
      held: NonEmptyVector[(VotingKey, SeatId)],
      rbHash: RbHash
  )(using defect: Raise[F, LeiosVotingDefect]): F[NonEmptyVector[(Vote, AddVoteResult)]] =
    held.traverse { (key, seat) =>
      Vote.sign(key.secret, seat, rbHash) match
        case Left(cause) =>
          defect.raise[LeiosVotingDefect, (Vote, AddVoteResult)](
            LeiosVotingDefect.Signing(seat, cause)
          )
        case Right(vote) => env.addVote(vote).map(vote -> _)
    }

  private def answered[I, U](
      answers: NonEmptyVector[(Vote, AddVoteResult)]
  ): Either[LeiosNotVotedReason[I, U], NonEmptyVector[(Vote, AddVoteResult)]] =
    val accepted = answers.exists {
      case (_, AddVoteResult.Added(_, _)) => true
      case (_, AddVoteResult.NoCommittee | AddVoteResult.Invalid(_) | AddVoteResult.AlreadyKnown) =>
        false
    }
    Either.cond(accepted, answers, LeiosNotVotedReason.VoteRejected(answers))

  /** `decideLeiosCertify` (`NodeKernel/Forge.hs:324-397`, ouroboros-consensus 2416493b): whether
    * the block forged for `slot` on top of the selection's tip carries a certificate, in the
    * reference's order:
    *
    *   1. what the parent announced, read from its chain-dependent state ticked to `slot`
    *      (`tickedProtocolStateLeiosAnnouncement`, `Shelley/Ledger/Leios.hs:341-343`; the tick
    *      clears it across an epoch boundary), and the era's minimum certification gap
    *      (`getMinCertificationGap`, `Shelley/Ledger/Ledger.hs:969-973`, [[minCertificationGap]]);
    *      either missing, nothing;
    *   1. at least the gap since the announcement, the slots subtracted as `Word64` s;
    *   1. the closure in the store (`lookupEbClosure`);
    *   1. the certificate the vote state assembled for the parent (`queryCert`), the parent being
    *      the announcing block.
    */
  def decideLeiosCertify[F[_]: Monad](
      slot: Word64,
      announced: Option[EbPoint],
      minGap: Option[BigInt],
      tip: Option[RbHash],
      closureHeld: EbHash => F[Boolean],
      queryCert: RbHash => F[Option[Certificate]]
  )(using defect: Raise[F, LeiosVotingDefect]): F[CertifyDecision] =
    (announced, minGap).tupled match
      case None => Monad[F].pure(CertifyDecision.NothingAnnounced)
      case Some((point, gap)) =>
        val elapsed = Word64.fromUnsigned((slot.toBigInt - point.slot.toBigInt).toLong)
        if elapsed.toBigInt < gap then Monad[F].pure(CertifyDecision.TooEarly(point, gap))
        else
          closureHeld(point.hash).flatMap {
            case false => Monad[F].pure(CertifyDecision.ClosureNotDownloaded(point))
            case true =>
              tip match
                case None =>
                  defect.raise[LeiosVotingDefect, CertifyDecision](
                    LeiosVotingDefect.CertifyOnGenesis
                  )
                case Some(rbHash) =>
                  queryCert(rbHash).map(
                    _.fold(CertifyDecision.NoCertificate(point))(
                      CertifyDecision.Certify(_, point.hash)
                    )
                  )
          }

  /** `minCertificationGap` (`LeiosDemoTypes.hs:2834-2841`, ouroboros-consensus 2416493b): the
    * certification gap of the era's slot length and the parameters' three periods
    * ([[xyz.kd5ujc.scarab.consensus.Praos2.certificationGap]]).
    */
  def minCertificationGap(
      slotMillis: BigInt,
      announcementMillis: BigInt,
      voteMillis: BigInt,
      diffusionMillis: BigInt
  ): Either[xyz.kd5ujc.scarab.ledger.Praos2Defect, BigInt] =
    xyz.kd5ujc.scarab.consensus.Praos2
      .certificationGap(slotMillis, announcementMillis, voteMillis, diffusionMillis)
