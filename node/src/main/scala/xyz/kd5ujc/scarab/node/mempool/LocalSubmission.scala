// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import xyz.kd5ujc.scarab.ledger.{TransactionDecodeProblem, MempoolProblem, MempoolLedgerProblem}
import cats.Applicative
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.{LedgerFailure, PredicateFailure}
import xyz.kd5ujc.scarab.node.ntc.PredicateFailureCbor
import xyz.kd5ujc.scarab.network.LocalTxSubmissionServer

/** Transactions submitted by local clients (`cardano-cli transaction submit` over the
  * node-to-client socket). The client sends the hard-fork-combinator wrapped transaction
  * `[era, tx]` where the Conway era is 6 and `tx` is the transaction itself or, as on node-to-node,
  * CBOR-in-CBOR (tag 24).
  */
object LocalSubmission:
  val ConwayEra = 6

  /** The original transaction bytes inside a wrapped one. */
  def unwrap(item: Bytes): Either[Rejection, Bytes] =
    Cbor.decode(item).left.map(e => Rejection.Malformed(TransactionDecodeProblem.Syntax(e))).flatMap { root =>
      root.value match
        case Value.Arr(Vector(era, payload)) =>
          era.value match
            case Value.UInt(e) if e == ConwayEra =>
              payload.value match
                case Value.Tag(t, inner) if t == 24 =>
                  inner.value match
                    case Value.ByteString(b) => Right(b)
                    case _ => Left(Rejection.Malformed(TransactionDecodeProblem.TaggedPayload(inner.value)))
                case _ => Right(payload.original)
            case Value.UInt(e) if e.isValidInt => Left(Rejection.WrongEra(e.toInt))
            case Value.UInt(e) =>
              Left(Rejection.Malformed(TransactionDecodeProblem.EraOutOfRange(e)))
            case _ => Left(Rejection.Malformed(TransactionDecodeProblem.EraExpected(era.value)))
        case _ => Left(Rejection.Malformed(TransactionDecodeProblem.EraEnvelope(root.value)))
    }

  /** The rejection as sent to the client: the ledger's `ApplyTxErr` for a refused transaction (the
    * predicate failures `cardano-cli` renders), the hard-fork combinator's era mismatch for a
    * transaction of another era. What the ledger does not report as a predicate failure (a
    * malformed transaction, a full mempool) is sent as its free-text `ConwayMempoolFailure`. An
    * implementation limit remains a typed value and has no rejection bytes.
    */
  def reasonItem(rejection: Rejection): Either[LedgerFailure.Unavailable, Bytes] = rejection match
    case Rejection.Ledger(error: LedgerFailure.Unavailable) => Left(error)
    case Rejection.WrongEra(era) =>
      Right(
        PredicateFailureCbor.eraMismatch(
          era,
          Era.fromIndex(BigInt(era)).fold("Unknown")(_.toString)
        )
      )
    case Rejection.Ledger(LedgerFailure.Rejected(_, failure)) =>
      Right(PredicateFailureCbor.applyTxError(Vector(failure)))
    case Rejection.Ledger(error) => Right(text(MempoolProblem.Ledger(error)))
    case Rejection.AlreadyHeld(_) =>
      Right(text(MempoolProblem.AllInputsSpent))
    case Rejection.Malformed(cause) => Right(text(MempoolProblem.Malformed(cause)))
    case Rejection.Full(size, free) => Right(text(MempoolProblem.Capacity(size, free)))
    case Rejection.NoLedger(why) =>
      val cause = why match
        case xyz.kd5ujc.scarab.node.read.ConwayView.Unavailable.InByron => MempoolLedgerProblem.InByron
        case xyz.kd5ujc.scarab.node.read.ConwayView.Unavailable.InEarlier(era) => MempoolLedgerProblem.Earlier(era)
        case xyz.kd5ujc.scarab.node.read.ConwayView.Unavailable.Refused(error) => MempoolLedgerProblem.Refused(error)
      Right(text(MempoolProblem.NoLedger(cause)))

  private def text(message: MempoolProblem): Bytes =
    PredicateFailureCbor.applyTxError(Vector(PredicateFailure.Mempool(message)))

  /** Service unavailability has no ledger rejection bytes. The transport closes this connection;
    * admitting an external transaction does not require stopping the node.
    */
  private def response(rejection: Rejection): LocalTxSubmissionServer.Response =
    reasonItem(rejection).fold(
      error =>
        LocalTxSubmissionServer.Response.ServiceUnavailable(
          LocalTxSubmissionServer.Unavailable.ScriptEvaluation(error)
        ),
      LocalTxSubmissionServer.Response.Rejected(_)
    )

  def handler[F[_]: Applicative](
      mempool: Mempool[F]
  ): Bytes => F[LocalTxSubmissionServer.Response] = item =>
    unwrap(item) match
      case Left(rejection) => Applicative[F].pure(response(rejection))
      case Right(tx) =>
        mempool.add(tx).map(_.fold(response, _ => LocalTxSubmissionServer.Response.Accepted))
