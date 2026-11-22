// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import xyz.kd5ujc.scarab.ledger.{TransactionDecodeProblem, MempoolProblem, MempoolLedgerProblem}
import cats.effect.Async
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, DomainError, Node, Value}
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}
import cats.data.NonEmptyChain
import xyz.kd5ujc.scarab.ledger.era.rules.DijkstraMempoolPredFailure
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
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
  /** What has no rejection bytes to send: a transaction its decoder refuses (the reference's codec
    * ends the connection), an implementation limit, a crypto provider that failed on a witness, a
    * decoder or the rules that threw on the transaction, a validation the hard time limit cut off
    * in an era with no error for it, or a refusal this build has no encoding for.
    */
  type NoReply = Rejection.Malformed | LedgerFailure.Unavailable | LedgerFailure.Unverifiable |
    Rejection.InputDefect | Rejection.RulesDefect | Rejection.TimedOut | RefusalPresentationError |
    NoEncoding | DijkstraNoEncoding | Rejection.DijkstraUnjudged

  /** A refusal under a Dijkstra ledger that this build cannot write: Dijkstra's `ApplyTxError` of
    * MEMPOOL's failures (`DijkstraApplyTxError`, cardano-ledger 9df9f98c
    * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra.hs:88-90`), which has no encoder of the era's
    * types here yet; or a refusal of the node's own that the reference sends as free text in
    * Conway, which Dijkstra has none of (`mkEraMkMempoolApplyTxError _prx = Nothing`,
    * ouroboros-consensus 2416493b `Shelley/Eras.hs:273-281`). There is no reply.
    */
  enum DijkstraNoEncoding extends DomainError:
    case Refused(failures: NonEmptyChain[DijkstraMempoolPredFailure[Dijkstra]])
    case Text(problem: MempoolProblem)

    def message: String = this match
      case Refused(failures) =>
        s"no Dijkstra encoding of the refusal: ${failures.toChain.toVector.mkString("; ")}"
      case Text(problem) => s"no Dijkstra text of the refusal: ${problem.message}"

  /** A refusal by the rules of `era` that this build cannot write: one of `failures` has no
    * encoding in that era's ledger ([[PreConwayFailureCbor]]). There is no free-text failure before
    * Conway to send instead, so there is no reply.
    */
  final case class NoEncoding(era: Era, failures: Vector[PredicateFailure]) extends DomainError:
    def message: String =
      s"no $era encoding of the refusal: ${failures.map(_.detail).mkString("; ")}"

  extension (reason: NoReply)
    def message: String = reason match
      case m: Rejection.Malformed        => m.message
      case f: LedgerFailure.Unavailable  => f.message
      case f: LedgerFailure.Unverifiable => f.message
      case d: Rejection.InputDefect      => d.message
      case d: Rejection.RulesDefect      => d.message
      case d: Rejection.TimedOut         => d.message
      case d: RefusalPresentationError   => d.message
      case d: NoEncoding                 => d.message
      case d: DijkstraNoEncoding         => d.message
      case d: Rejection.DijkstraUnjudged => d.message

  val ConwayEra = 6

  /** The transaction inside a wrapped one, with the era it names, read as far as the reference's
    * decoder of a `GenTx` decides before the transaction of an era (`dispatchDecoder`,
    * `HardFork/Combinator/Serialisation/SerialiseNodeToClient.hs:258-276, 362-367`,
    * ouroboros-consensus 82ecba32): `decodeNS` 's `enforceSize "decodeNS" 2`, `decodeWord8` and an
    * index of one of the eight eras (`Common.hs:420-425`), each at any width of its head; in a
    * Shelley-based era, Dijkstra included, `unwrapCBORinCBOR`
    * (`Shelley/Ledger/Mempool.hs:261-266`), tag 24 around a definite byte string (`decodeTag`,
    * `decodeBytes`, `ouroboros-network/api/lib/Ouroboros/Network/Block.hs:501-504`,
    * ouroboros-network c45735a5); in Byron, `MempoolPayload` 's `enforceSize "MempoolPayload" 2`
    * and a constructor from 0 to 3
    * (`eras/byron/ledger/impl/src/Cardano/Chain/MempoolPayload.hs:85-93`, cardano-ledger f649f975).
    * These are the steps [[GenTx.prefix]] takes. A transaction of an era from Shelley to Conway is
    * then read in its era, and Byron's payload whole by the decoder of its kind
    * ([[MempoolState.read]]), and one of Dijkstra in the Leios build; in the release builds one of
    * Dijkstra is not read further, and gets the era mismatch.
    */
  def unwrap(item: Bytes): Either[Rejection, Submission] =
    def malformed(problem: TransactionDecodeProblem): Rejection = Rejection.Malformed(problem)
    for
      root <- Cbor
        .decode(item, Cbor.Limits.Unbounded)
        .leftMap(e => malformed(TransactionDecodeProblem.Syntax(e)))
      pair <- pairOf(root).leftMap(e => malformed(TransactionDecodeProblem.EraEnvelope(e)))
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
        case Some(Era.Byron) => byron(payload).leftMap(malformed)
        case None            => Left(malformed(TransactionDecodeProblem.UnknownEra(index)))
    yield Submission(index, raw)

  /** The two items of a definite list of two (`enforceSize _ 2`). */
  private def pairOf(n: Node): Either[Node.NotSized, (Node, Node)] =
    n.enforceSize(2).flatMap {
      case Vector(first, second) => Right((first, second))
      case items                 => Left(Node.NotSized.Length(items.size))
    }

  /** `unwrapCBORinCBOR`'s tag 24 around a definite byte string: the bytes. */
  private def cborInCbor(payload: Node): Either[TransactionDecodeProblem, Bytes] =
    payload.value match
      case Value.Tag(t, inner) if t == 24 =>
        inner.value match
          case Value.ByteString(b) if !inner.isIndefinite => Right(b)
          case Value.ByteString(_) => Left(TransactionDecodeProblem.ChunkedPayload)
          case other               => Left(TransactionDecodeProblem.TaggedPayload(other))
      case other => Left(TransactionDecodeProblem.NotCborInCbor(other))

  /** Byron's `MempoolPayload` as far as its constructor: the payload itself, which the decoder of
    * its kind reads ([[MempoolState.read]]).
    */
  private def byron(payload: Node): Either[TransactionDecodeProblem, Bytes] =
    pairOf(payload)
      .leftMap(TransactionDecodeProblem.ByronEnvelope(_))
      .flatMap((constructor, _) =>
        constructor.value match
          case Value.UInt(c) if c <= 3 => Right(payload.original)
          case other                   => Left(TransactionDecodeProblem.ByronConstructor(other))
      )

  /** The rejection as sent to the client, the pool's ledger being of `era` (none while it has no
    * ledger): the ledger's `ApplyTxErr` of that era for a refused transaction (the predicate
    * failures `cardano-cli` renders), the hard-fork combinator's era mismatch for a transaction of
    * another era that does not reach the ledger's. What the ledger does not report as a predicate
    * failure (a full mempool) is sent as Conway's free-text `ConwayMempoolFailure`. A transaction
    * its decoder refuses gets no reply: the reference's codec decodes the transaction
    * (`decodeWithByteSpan decodeTx`, ouroboros-network c45735a5
    * `ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/LocalTxSubmission/Codec.hs:148-154`)
    * and ends the connection where it fails. An implementation limit, a crypto provider that failed
    * on a witness, and a refusal of an earlier era with no encoding here remain typed values and
    * have no rejection bytes either: there is no verdict to send.
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
      case cutOff: Rejection.TimedOut                          => Left(cutOff)
      case unjudged: Rejection.DijkstraUnjudged                => Left(unjudged)
      case Rejection.DijkstraRefused(_, failures) => Left(DijkstraNoEncoding.Refused(failures))
      // Conway's `ConwayMempoolFailure` with the reference's text (`Mempool/Update.hs` 278-283)
      case Rejection.TooSlow(id, submitted, took) =>
        text(era, MempoolProblem.TooSlow(took, submitted, id))
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
      case Rejection.Ledger(error) => text(era, MempoolProblem.Ledger(error))
      case Rejection.AlreadyHeld(_) =>
        text(era, MempoolProblem.AllInputsSpent)
      case malformed: Rejection.Malformed => Left(malformed)
      case Rejection.Limits(_, failures) =>
        inEra(era, failures.toList.toVector)
      case full: Rejection.Full => text(era, full.problem)
      case Rejection.NoLedger(why) =>
        val cause = why match
          case xyz.kd5ujc.scarab.node.read.ConwayView.Unavailable.InByron =>
            MempoolLedgerProblem.InByron
          case xyz.kd5ujc.scarab.node.read.ConwayView.Unavailable.InEarlier(era) =>
            MempoolLedgerProblem.Earlier(era)
          case xyz.kd5ujc.scarab.node.read.ConwayView.Unavailable.Refused(error) =>
            MempoolLedgerProblem.Refused(error)
        text(era, MempoolProblem.NoLedger(cause))

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

  /** Conway's free-text `ConwayMempoolFailure` of `message`; under a Dijkstra ledger, none
    * ([[DijkstraNoEncoding.Text]]).
    */
  private def text(era: Option[Era], message: MempoolProblem): Either[NoReply, Bytes] =
    era match
      case Some(Era.Dijkstra) => Left(DijkstraNoEncoding.Text(message))
      case Some(
            Era.Byron | Era.Shelley | Era.Allegra | Era.Mary | Era.Alonzo | Era.Babbage | Era.Conway
          ) | None =>
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
        case error: DijkstraNoEncoding =>
          LocalTxSubmissionServer.Response.ServiceUnavailable(
            LocalTxSubmissionServer.Unavailable.Presentation(error)
          )
        // no verdict on a Dijkstra transaction: no reply, the client's connection ends, as a
        // provider's failure ends it for the other eras
        case unjudged: Rejection.DijkstraUnjudged =>
          LocalTxSubmissionServer.Response.Aborted(unjudged.cause)
        // the transaction's decoder refuses it: no reply, the client's connection ends as the
        // reference's codec ends it, and as a prefix of it ends it ([[GenTx.prefix]])
        case Rejection.Malformed(cause) => LocalTxSubmissionServer.Response.Undecodable(cause)
        // the decoder threw on the client's bytes: no reply, the client's connection is closed
        case Rejection.InputDefect(cause) => LocalTxSubmissionServer.Response.InputDefect(cause)
        // the rules threw on the transaction: no reply, the client's connection is closed and the
        // node goes on (issue 564)
        case Rejection.RulesDefect(cause) => LocalTxSubmissionServer.Response.ServingDefect(cause)
        // the hard time limit cut the validation off where the era has no error for it: no reply,
        // the client's connection ends, as `ExnMempoolTimeout` ends the reference's
        // (`Mempool/Update.hs` 285-291)
        case Rejection.TimedOut(timeout) => LocalTxSubmissionServer.Response.Aborted(timeout)
      },
      LocalTxSubmissionServer.Response.Rejected(_)
    )

  /** The node's answer to a submission: the transaction is read in its era first ([[unwrap]],
    * [[MempoolState.read]]), as the reference's codec reads it before the mempool sees it, so a
    * transaction its decoder refuses, or that a decoder throws on, ends the connection at once,
    * never behind a submission that waits for room; one that reads takes its place in line.
    */
  def handler[F[_]: Async](
      mempool: Mempool[F],
      build: ReferenceBuild
  )(fatal: Fatal => F[Unit]): Bytes => F[LocalTxSubmissionServer.Response] = item =>
    Async[F].delay(unwrap(item).flatMap(MempoolState.read(_, build))).flatMap {
      case Left(rejection) => Async[F].pure(response(Refused(rejection, None)))
      case Right(read) =>
        StorageBoundary.run(fatal) {
          mempool
            .submitRead(read, Source.Local)
            .map(_.fold(response, _ => LocalTxSubmissionServer.Response.Accepted))
        }
    }
