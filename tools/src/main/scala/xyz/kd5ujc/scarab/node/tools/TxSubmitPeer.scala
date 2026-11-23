// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.data.NonEmptyVector
import cats.effect.{Deferred, ExitCode, IO, IOApp, Ref}
import cats.mtl.Handle
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port, SocketAddress}
import io.circe.parser
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.network.{
  Bearer,
  HandshakeClient,
  KeepAliveClient,
  Mux,
  NetworkError,
  Protocols,
  TxSubmissionClient
}
import xyz.kd5ujc.scarab.node.mempool.RelayedTransactions
import xyz.kd5ujc.scarab.protocol.{Era, Handshake, ProtocolError, ReferenceBuild, TxSubmission2}

/** Hands signed transactions to one peer over node-to-node, as a relay passes transactions on: the
  * handshake as an initiator-only peer, KeepAlive, and TxSubmission2 as the side that owns the
  * transactions, offering them in the order given until the body of every one has been sent, the
  * connection ends, or the time runs out.
  *
  * It sends transactions into the network through a pool other than the node under test, so they
  * reach that node only by diffusion, as other people's transactions do (#79): a test load
  * submitted to the node's own relay sits in that node's mempool before its endorser block, and its
  * closure check reapplies it.
  *
  * The transactions are the text envelopes `cardano-cli ... transaction sign` writes, read from the
  * files given, or from the `.tx` files of a directory in name order; a chain of transactions
  * spending each other's outputs is offered in that order, so the peer's mempool takes each after
  * the one it spends. An envelope's `type` must name the era given, and no two transactions may
  * share a body. Each id is the hash of the body as the node's own reader of the build's relayed
  * transactions reads it ([[RelayedTransactions.of]]). Whether the peer admits a transaction it
  * asked for is its mempool's verdict, which TxSubmission2 does not report: confirm on the chain.
  *
  * Run, inside the build container: `tools/runMain xyz.kd5ujc.scarab.node.tools.TxSubmitPeer <host>
  * <port> <magic> <build> <era> <seconds> <tx file or directory>...`, for example
  * `... TxSubmitPeer relay.example 3001 164 leios-b004fe0c dijkstra 120 /work/txs`. Hand-run, not
  * part of the node, not run by `check`.
  */
object TxSubmitPeer extends IOApp:
  /** An era a peer relays transactions of, with the era name its `cardano-cli` envelopes carry. */
  final case class TxEra(era: Era, envelopeName: String):
    /** The envelope types `cardano-cli` writes for a signed transaction of this era. */
    def envelopeTypes: Set[String] = Set(s"Tx $envelopeName", s"Witnessed Tx $envelopeName")

  /** `<era>`'s values. */
  val eras: Map[String, TxEra] = Map(
    "conway" -> TxEra(Era.Conway, "ConwayEra"),
    "dijkstra" -> TxEra(Era.Dijkstra, "DijkstraEra")
  )

  /** A signed transaction as it is offered: its file, its bytes and its id. */
  final case class Signed(file: Path, original: Bytes, id: Bytes)

  /** Why the transactions are not offered. */
  enum Unreadable:
    case NotEnvelope(file: Path, cause: io.circe.Error)
    case OtherEra(file: Path, envelopeType: String)
    case NotHex(file: Path)
    case NotTransaction(file: Path, cause: ProtocolError)
    case SameBody(first: Path, second: Path)
    case Empty

    def message: String = this match
      case NotEnvelope(file, cause) =>
        s"$file: not a text envelope with type and cborHex (${cause.getMessage})"
      case OtherEra(file, envelopeType) => s"$file: a $envelopeType, not of the era given"
      case NotHex(file)                 => s"$file: cborHex is not hex"
      case NotTransaction(file, cause) =>
        s"$file: not a transaction of the era: ${cause.message}"
      case SameBody(first, second) => s"$first and $second have the same body"
      case Empty                   => "no transactions"

  /** The transactions to offer, at least one, no two with the same id, in offering order. */
  final class Batch private (val txs: NonEmptyVector[Signed]):
    /** The bytes of every body, which the replies send in all. */
    val bytes: Long = txs.foldLeft(0L)(_ + _.original.size)

  object Batch:
    def of(txs: Vector[Signed]): Either[Unreadable, Batch] =
      NonEmptyVector.fromVector(txs).toRight(Unreadable.Empty).flatMap { all =>
        txs
          .foldLeft(Map.empty[Bytes, Path].asRight[Unreadable]) { (seen, t) =>
            seen.flatMap(m =>
              m.get(t.id)
                .fold(m.updated(t.id, t.file).asRight)(f => Unreadable.SameBody(f, t.file).asLeft)
            )
          }
          .as(Batch(all))
      }

  /** The `.tx` files of `path` in name order, or `path` itself when it is a file. */
  private def files(path: Path): IO[Vector[Path]] =
    IO.blocking(
      if Files.isDirectory(path) then
        Files
          .list(path)
          .iterator()
          .asScala
          .filter(_.getFileName.toString.endsWith(".tx"))
          .toVector
          .sortBy(_.getFileName.toString)
      else Vector(path)
    )

  /** The transaction of a text envelope of `era`, and its id under `reader`. */
  def signed(
      file: Path,
      text: String,
      reader: TxSubmission2.TransactionReader,
      era: TxEra
  ): Either[Unreadable, Signed] =
    for
      json <- parser.parse(text).leftMap(Unreadable.NotEnvelope(file, _))
      envelopeType <- json.hcursor
        .downField("type")
        .as[String]
        .leftMap(Unreadable.NotEnvelope(file, _))
      _ <- Either.cond(era.envelopeTypes(envelopeType), (), Unreadable.OtherEra(file, envelopeType))
      hex <- json.hcursor.downField("cborHex").as[String].leftMap(Unreadable.NotEnvelope(file, _))
      original <- Bytes.fromHex(hex).leftMap(_ => Unreadable.NotHex(file))
      body <- reader.body(original, era.era).leftMap(Unreadable.NotTransaction(file, _))
    yield Signed(file, original, Blake2b.hash256.hash(body))

  /** The batch as a source: ticket `i + 1` for the `i` -th transaction, offered once each, oldest
    * first.
    */
  def source(
      batch: Batch,
      era: Era,
      txReader: TxSubmission2.TransactionReader
  ): TxSubmissionClient.Source[IO] =
    val held = batch.txs.toVector.zipWithIndex.map((t, i) =>
      TxSubmissionClient.Held(t.id, t.original.size, i + 1L, era)
    )
    val byId = batch.txs.toVector.map(t => t.id -> t).toMap
    new TxSubmissionClient.Source[IO]:
      // tickets are 1..n in order, so those after `ticket` start at index `ticket`
      def after(ticket: Long, max: Int) =
        IO.pure(
          if ticket < 0L || ticket >= held.size then Vector.empty
          else held.slice(ticket.toInt, ticket.toInt + max.max(0))
        )
      // nothing is added after the start, so a wait past the last ticket never ends
      def awaitAfter(ticket: Long) = if ticket < held.size then IO.unit else IO.never
      def body(id: Bytes) = IO.pure(byId.get(id).map(t => TxSubmission2.EraTx(era, t.original)))
      def reader = txReader

  /** How an offering ended. */
  enum Outcome:
    /** Every body was sent and the protocol then ended with `MsgDone`, which the peer allows only
      * from a blocking request: it read every reply before it.
      */
    case Sent

    /** Every body was queued, but the protocol's end, which would confirm the peer read them, did
      * not come in time.
      */
    case Unconfirmed

    /** The protocol or the connection ended first. */
    case Ended(result: Either[NetworkError, Unit])

    /** The time ran out with `sent` bytes of the batch's bodies sent. */
    case TimedOut(sent: Long)

    def exitCode: ExitCode = this match
      case Sent                                 => ExitCode.Success
      case Unconfirmed | Ended(_) | TimedOut(_) => ExitCode.Error

  def run(args: List[String]): IO[ExitCode] = args match
    case host :: port :: magic :: build :: era :: seconds :: paths if paths.nonEmpty =>
      (
        Host.fromString(host),
        port.toIntOption.flatMap(Port.fromInt),
        magic.toLongOption,
        ReferenceBuild.parse(build).toOption,
        eras.get(era),
        seconds.toIntOption.filter(_ > 0)
      ).tupled match
        case Some((h, p, m, b, e, s)) =>
          submit(
            SocketAddress(h, p),
            m,
            RelayedTransactions.of(b),
            e,
            s.seconds,
            paths.map(Path.of(_))
          )
        case None => usage
    case _ => usage

  private val usage: IO[ExitCode] =
    IO.println(
      "usage: TxSubmitPeer <host> <port> <magic> <reference build> <conway|dijkstra> <seconds> " +
        "<tx file or directory>..."
    ).as(ExitCode.Error)

  private def submit(
      peer: SocketAddress[Host],
      magic: Long,
      reader: TxSubmission2.TransactionReader,
      era: TxEra,
      limit: FiniteDuration,
      paths: List[Path]
  ): IO[ExitCode] =
    paths
      .flatTraverse(p => files(p).map(_.toList))
      .flatMap(_.traverse(f => IO.blocking(Files.readString(f)).map(signed(f, _, reader, era))))
      .map(_.sequence.flatMap(txs => Batch.of(txs.toVector)))
      .flatMap {
        case Left(unreadable) => IO.println(unreadable.message).as(ExitCode.Error)
        case Right(batch) =>
          IO.monotonic.flatMap(start =>
            offer(peer, magic, reader, era.era, limit, batch).flatMap(outcome =>
              IO.monotonic.flatMap(end => report(batch, outcome, end - start)).as(outcome.exitCode)
            )
          )
      }

  private def report(batch: Batch, outcome: Outcome, took: FiniteDuration): IO[Unit] =
    IO.println(
      s"offered ${batch.txs.length} transactions (${batch.bytes} bytes) in ${took.toMillis} ms: " +
        (outcome match
          case Outcome.Sent        => "every body sent, protocol ended"
          case Outcome.Unconfirmed => "every body queued; the protocol's end did not come in time"
          case Outcome.Ended(Right(())) => "the peer ended the protocol before every body was asked"
          case Outcome.Ended(Left(error)) => s"the connection failed: ${error.message}"
          case Outcome.TimedOut(sent) =>
            s"time ran out with $sent of ${batch.bytes} body bytes sent")
    )

  /** One deadline, `limit`, for the whole offering: the connection, the handshake, the replies and
    * the protocol's end. The connection's closure is watched throughout.
    */
  private def offer(
      peer: SocketAddress[Host],
      magic: Long,
      reader: TxSubmission2.TransactionReader,
      era: Era,
      limit: FiniteDuration,
      batch: Batch
  ): IO[Outcome] =
    (
      Ref.of[IO, Long](0L),
      Deferred[IO, Unit],
      Deferred[IO, Either[NetworkError, Unit]],
      IO.monotonic
    )
      .flatMapN { (sent, all, ended, start) =>
        // told after each reply is sent, so the last body counts only once it has gone
        val served = (bytes: Long) =>
          sent.updateAndGet(_ + bytes).flatMap(s => all.complete(()).void.whenA(s >= batch.bytes))
        val protocols = Set(Protocols.Handshake, Protocols.KeepAlive, Protocols.TxSubmission2)
        val remaining = IO.monotonic.map(now => (limit - (now - start)).max(Duration.Zero))
        Handle
          .allow[NetworkError] {
            (for
              bearer <- Bearer.tcp[IO](peer, 10.seconds)
              mux <- Mux.resource[IO](bearer, protocols)
            yield mux).use { mux =>
              for
                hs <- mux.channel(Protocols.Handshake)
                agreed <- HandshakeClient.run(
                  hs,
                  Vector(14, 15, 16).map(_ -> Handshake.Data(magic, initiatorOnly = true))
                )
                _ <- IO.println(s"handshake with $peer: version ${agreed.version}")
                ka <- mux.channel(Protocols.KeepAlive)
                outcome <- (
                  KeepAliveClient.background[IO](ka, mux),
                  TxSubmissionClient.backgroundFinishing[IO](
                    mux,
                    source(batch, era, reader),
                    completed = Some(result => ended.complete(result).void),
                    served = Some(served)
                  )
                ).tupled.use { (_, running) =>
                  val stopped: IO[Either[NetworkError, Unit]] =
                    IO.race(ended.get, mux.closed).map(_.fold(identity, Left(_)))
                  IO.race(all.get, stopped).flatMap {
                    case Left(()) =>
                      remaining
                        .flatMap(running.finish)
                        .map(if _ then Outcome.Sent else Outcome.Unconfirmed)
                    case Right(result) => IO.pure(Outcome.Ended(result))
                  }
                }
              yield outcome
            }
          }
          .rescue(e => IO.pure(Outcome.Ended(Left(e))))
          .timeoutTo(limit, sent.get.map(Outcome.TimedOut(_)))
      }
end TxSubmitPeer
