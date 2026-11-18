// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import xyz.kd5ujc.scarab.ledger.{TransactionDecodeProblem, MempoolProblem, MempoolLedgerProblem}
import cats.effect.Async
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, DomainError, Node, Value}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.{LedgerFailure, PredicateFailure}
import xyz.kd5ujc.scarab.node.ntc.{PredicateFailureCbor, RefusalPresentationError}
import xyz.kd5ujc.scarab.node.{Fatal, StorageBoundary}
import xyz.kd5ujc.scarab.network.LocalTxSubmissionServer

/** Transactions submitted by local clients (`cardano-cli transaction submit` over the
  * node-to-client socket). The client sends the hard-fork-combinator wrapped transaction
  * `[era, tx]`, where `era` is the index of the transaction's era (Conway is 6) and `tx` is the
  * transaction in CBOR-in-CBOR (tag 24 around its bytes), as on node-to-node ([[unwrap]]).
  */
object LocalSubmission:
  /** What has no rejection bytes to send: an implementation limit, a crypto provider that failed on
    * a witness, a decoder or the rules that threw on the transaction, or a refusal this build has
    * no encoding for.
    */
  type NoReply = LedgerFailure.Unavailable | LedgerFailure.Unverifiable | Rejection.InputDefect |
    Rejection.RulesDefect | RefusalPresentationError | NoEncoding

  /** A refusal by the rules of `era` that this build cannot write: one of `failures` has no
    * encoding in that era's ledger ([[PreConwayFailureCbor]]). There is no free-text failure before
    * Conway to send instead, so there is no reply.
    */
  final case class NoEncoding(era: Era, failures: Vector[PredicateFailure]) extends DomainError:
    def message: String =
      s"no $era encoding of the refusal: ${failures.map(_.detail).mkString("; ")}"

  extension (reason: NoReply)
    def message: String = reason match
      case f: LedgerFailure.Unavailable  => f.message
      case f: LedgerFailure.Unverifiable => f.message
      case d: Rejection.InputDefect      => d.message
      case d: Rejection.RulesDefect      => d.message
      case d: RefusalPresentationError   => d.message
      case d: NoEncoding                 => d.message

  val ConwayEra = 6

  /** The transaction inside a wrapped one, with the era it names, read as the reference's decoder
    * of a `GenTx` reads it whole (`dispatchDecoder`,
    * `HardFork/Combinator/Serialisation/SerialiseNodeToClient.hs:258-276, 362-367`,
    * ouroboros-consensus 82ecba32): `decodeNS` 's `enforceSize "decodeNS" 2` and `decodeWord8`
    * (`Common.hs:420-425`), each at any width of its head; and in a Shelley-based era, Dijkstra
    * included, `unwrapCBORinCBOR` (`Shelley/Ledger/Mempool.hs:261-266`), tag 24 around a definite
    * byte string (`decodeTag`, `decodeBytes`,
    * `ouroboros-network/api/lib/Ouroboros/Network/Block.hs:501-504`, ouroboros-network c45735a5). A
    * Byron transaction and one of an index past the last era are not read: they get the era
    * mismatch, unless tag 24 follows the index around something other than a byte string, which is
    * refused as malformed ([[foreign]]).
    */
  def unwrap(item: Bytes): Either[Rejection, Submission] =
    def malformed(problem: TransactionDecodeProblem): Rejection = Rejection.Malformed(problem)
    for
      root <- Cbor
        .decode(item, Cbor.Limits.Unbounded)
        .leftMap(e => malformed(TransactionDecodeProblem.Syntax(e)))
      pair <- root
        .enforceSize(2)
        .flatMap {
          case Vector(era, payload) => Right((era, payload))
          case items                => Left(Node.NotSized.Length(items.size))
        }
        .leftMap(e => malformed(TransactionDecodeProblem.EraEnvelope(e)))
      (era, payload) = pair
      index <- era.value match
        case Value.UInt(e) if e <= 0xff => Right(e.toInt)
        case Value.UInt(e) => Left(malformed(TransactionDecodeProblem.EraOutOfRange(e)))
        case other         => Left(malformed(TransactionDecodeProblem.EraExpected(other)))
      raw <- Era.fromIndex(BigInt(index)) match
        case Some(
              Era.Shelley | Era.Allegra | Era.Mary | Era.Alonzo | Era.Babbage | Era.Conway |
              Era.Dijkstra
            ) =>
          cborInCbor(payload).leftMap(malformed)
        case Some(Era.Byron) | None => foreign(payload).leftMap(malformed)
    yield Submission(index, raw)

  /** `unwrapCBORinCBOR`'s tag 24 around a definite byte string: the bytes. */
  private def cborInCbor(payload: Node): Either[TransactionDecodeProblem, Bytes] =
    payload.value match
      case Value.Tag(t, inner) if t == 24 =>
        inner.value match
          case Value.ByteString(b) if !inner.isIndefinite => Right(b)
          case Value.ByteString(_) => Left(TransactionDecodeProblem.ChunkedPayload)
          case other               => Left(TransactionDecodeProblem.TaggedPayload(other))
      case other => Left(TransactionDecodeProblem.NotCborInCbor(other))

  /** The payload of an era whose transactions the pool does not read, which gets the era mismatch:
    * the bytes under tag 24, or the payload itself.
    */
  private def foreign(payload: Node): Either[TransactionDecodeProblem, Bytes] =
    payload.value match
      case Value.Tag(t, inner) if t == 24 =>
        inner.value match
          case Value.ByteString(b) => Right(b)
          case other               => Left(TransactionDecodeProblem.TaggedPayload(other))
      case _ => Right(payload.original)

  /** The rejection as sent to the client, the pool's ledger being of `era` (none while it has no
    * ledger): the ledger's `ApplyTxErr` of that era for a refused transaction (the predicate
    * failures `cardano-cli` renders), the hard-fork combinator's era mismatch for a transaction of
    * another era that does not reach the ledger's. What the ledger does not report as a predicate
    * failure (a malformed transaction, a full mempool) is sent as Conway's free-text
    * `ConwayMempoolFailure`. An implementation limit, a crypto provider that failed on a witness,
    * and a refusal of an earlier era with no encoding here remain typed values and have no
    * rejection bytes: there is no verdict to send.
    */
  def reasonItem(
      rejection: Rejection,
      era: Option[Era]
  ): Either[NoReply, Bytes] =
    rejection match
      case Rejection.Ledger(error: LedgerFailure.Unavailable)  => Left(error)
      case Rejection.Ledger(error: LedgerFailure.Unverifiable) => Left(error)
      case defect: Rejection.InputDefect                       => Left(defect)
      case defect: Rejection.RulesDefect                       => Left(defect)
      case Rejection.WrongEra(transaction, ledger) =>
        Right(
          PredicateFailureCbor.eraMismatch(
            transaction,
            Era.fromIndex(BigInt(transaction)).fold("Unknown")(_.toString),
            ledger
          )
        )
      case Rejection.Ledger(LedgerFailure.Rejected(_, failures)) =>
        inEra(era, failures.toChain.toVector)
      case Rejection.Ledger(error) => text(MempoolProblem.Ledger(error))
      case Rejection.AlreadyHeld(_) =>
        text(MempoolProblem.AllInputsSpent)
      case Rejection.Malformed(cause) => text(MempoolProblem.Malformed(cause))
      case Rejection.Limits(_, failures) =>
        inEra(era, failures.toList.toVector)
      case full: Rejection.Full => text(full.problem)
      case Rejection.NoLedger(why) =>
        val cause = why match
          case xyz.kd5ujc.scarab.node.read.ConwayView.Unavailable.InByron =>
            MempoolLedgerProblem.InByron
          case xyz.kd5ujc.scarab.node.read.ConwayView.Unavailable.InEarlier(era) =>
            MempoolLedgerProblem.Earlier(era)
          case xyz.kd5ujc.scarab.node.read.ConwayView.Unavailable.Refused(error) =>
            MempoolLedgerProblem.Refused(error)
        text(MempoolProblem.NoLedger(cause))

  /** The `ApplyTxErr` of `failures` in the ledger's era: Conway's, with its free text for a failure
    * of a rule Conway does not have ([[PredicateFailureCbor.applyTxError]]), when the pool has a
    * Conway ledger or none; the era's own before Conway ([[PreConwayFailureCbor]]).
    */
  private def inEra(era: Option[Era], failures: Vector[PredicateFailure]): Either[NoReply, Bytes] =
    era match
      case Some(e @ (Era.Shelley | Era.Allegra | Era.Mary | Era.Alonzo | Era.Babbage)) =>
        PredicateFailureCbor
          .applyTxError(e, failures)
          .leftMap[NoReply](identity)
          .flatMap(_.toRight[NoReply](NoEncoding(e, failures)))
      case Some(Era.Byron | Era.Conway | Era.Dijkstra) | None =>
        PredicateFailureCbor.applyTxError(failures)

  private def text(message: MempoolProblem): Either[RefusalPresentationError, Bytes] =
    PredicateFailureCbor.applyTxError(Vector(PredicateFailure.Mempool(message)))

  /** Service unavailability has no ledger rejection bytes. The transport closes this connection;
    * admitting an external transaction does not require stopping the node.
    */
  private[mempool] def response(refused: Refused): LocalTxSubmissionServer.Response =
    reasonItem(refused.rejection, refused.era).fold(
      {
        case error: LedgerFailure.Unavailable =>
          LocalTxSubmissionServer.Response.ServiceUnavailable(
            LocalTxSubmissionServer.Unavailable.ScriptEvaluation(error)
          )
        case error: LedgerFailure.Unverifiable =>
          LocalTxSubmissionServer.Response.ServiceUnavailable(
            LocalTxSubmissionServer.Unavailable.Verification(error)
          )
        case error: RefusalPresentationError =>
          LocalTxSubmissionServer.Response.ServiceUnavailable(
            LocalTxSubmissionServer.Unavailable.Presentation(error)
          )
        case error: NoEncoding =>
          LocalTxSubmissionServer.Response.ServiceUnavailable(
            LocalTxSubmissionServer.Unavailable.Presentation(error)
          )
        // the decoder threw on the client's bytes: no reply, the client's connection is closed
        case Rejection.InputDefect(cause) => LocalTxSubmissionServer.Response.InputDefect(cause)
        // the rules threw on the transaction: no reply, the client's connection is closed and the
        // node goes on (issue 564)
        case Rejection.RulesDefect(cause) => LocalTxSubmissionServer.Response.ServingDefect(cause)
      },
      LocalTxSubmissionServer.Response.Rejected(_)
    )

  def handler[F[_]: Async](
      mempool: Mempool[F]
  )(fatal: Fatal => F[Unit]): Bytes => F[LocalTxSubmissionServer.Response] = item =>
    unwrap(item) match
      case Left(rejection) => Async[F].pure(response(Refused(rejection, None)))
      case Right(submission) =>
        StorageBoundary.run(fatal) {
          mempool
            .submit(submission, Source.Local)
            .map(_.fold(response, _ => LocalTxSubmissionServer.Response.Accepted))
        }
