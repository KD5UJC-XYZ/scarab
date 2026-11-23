// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

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
  * transactions, offering them in the order given until the peer has asked for every body, or the
  * time runs out.
  *
  * It sends transactions into the network through a pool other than the node under test, so they
  * reach that node only by diffusion, as other people's transactions do (#79): a test load
  * submitted to the node's own relay sits in that node's mempool before its endorser block, and its
  * closure check reapplies it.
  *
  * The transactions are the text envelopes `cardano-cli ... transaction sign` writes (`cborHex`),
  * read from the files given, or from the `.tx` files of a directory in name order; a chain of
  * transactions spending each other's outputs is offered in that order, so the peer's mempool takes
  * each after the one it spends. Each id is the hash of the body as the node's own reader of the
  * build's relayed transactions reads it ([[RelayedTransactions.of]]). Whether the peer admits a
  * transaction it asked for is its mempool's verdict, which TxSubmission2 does not report: confirm
  * on the chain.
  *
  * Run, inside the build container: `tools/runMain xyz.kd5ujc.scarab.node.tools.TxSubmitPeer <host>
  * <port> <magic> <build> <era> <seconds> <tx file or directory>...`, for example
  * `... TxSubmitPeer relay.example 3001 164 leios-b004fe0c dijkstra 120 /work/txs`. Hand-run, not
  * part of the node, not run by `check`.
  */
object TxSubmitPeer extends IOApp:
  /** `<era>`'s values: the Shelley-based eras whose transactions a peer relays. */
  private val eras: Map[String, Era] =
    Map("conway" -> Era.Conway, "dijkstra" -> Era.Dijkstra)

  /** A signed transaction as it is offered: its file, its bytes and its id. */
  final case class Signed(file: Path, original: Bytes, id: Bytes)

  /** Why a transaction file is not offered. */
  enum Unreadable:
    case NotEnvelope(file: Path, cause: io.circe.Error)
    case NotHex(file: Path)
    case NotTransaction(file: Path, cause: ProtocolError)

    def message: String = this match
      case NotEnvelope(file, cause) =>
        s"$file: not a text envelope with cborHex (${cause.getMessage})"
      case NotHex(file) => s"$file: cborHex is not hex"
      case NotTransaction(file, cause) =>
        s"$file: not a transaction of the era: ${cause.message}"

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

  /** The transaction of a text envelope, and its id under `reader`. */
  def signed(
      file: Path,
      text: String,
      reader: TxSubmission2.TransactionReader,
      era: Era
  ): Either[Unreadable, Signed] =
    for
      hex <- parser
        .parse(text)
        .flatMap(_.hcursor.downField("cborHex").as[String])
        .leftMap(Unreadable.NotEnvelope(file, _))
      original <- Bytes.fromHex(hex).leftMap(_ => Unreadable.NotHex(file))
      body <- reader.body(original, era).leftMap(Unreadable.NotTransaction(file, _))
    yield Signed(file, original, Blake2b.hash256.hash(body))

  /** The transactions as a source: ticket `i + 1` for the `i` -th, offered once each, oldest first;
    * `asked` is told each id whose body the peer asks for.
    */
  def source(
      txs: Vector[Signed],
      era: Era,
      txReader: TxSubmission2.TransactionReader,
      asked: Bytes => IO[Unit]
  ): TxSubmissionClient.Source[IO] =
    val held =
      txs.zipWithIndex.map((t, i) => TxSubmissionClient.Held(t.id, t.original.size, i + 1L, era))
    val byId = txs.map(t => t.id -> t).toMap
    new TxSubmissionClient.Source[IO]:
      def after(ticket: Long, max: Int) = IO.pure(held.filter(_.ticket > ticket).take(max))
      // nothing is added after the start, so a wait past the last ticket never ends
      def awaitAfter(ticket: Long) = if ticket < txs.size then IO.unit else IO.never
      def body(id: Bytes) =
        byId.get(id).traverse(t => asked(id).as(TxSubmission2.EraTx(era, t.original)))
      def reader = txReader

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
      era: Era,
      limit: FiniteDuration,
      paths: List[Path]
  ): IO[ExitCode] =
    paths
      .flatTraverse(p => files(p).map(_.toList))
      .flatMap(_.traverse(f => IO.blocking(Files.readString(f)).map(signed(f, _, reader, era))))
      .flatMap(_.sequence match
        case Left(unreadable)          => IO.println(unreadable.message).as(ExitCode.Error)
        case Right(txs) if txs.isEmpty => IO.println("no transactions").as(ExitCode.Error)
        case Right(txs)                => offer(peer, magic, reader, era, limit, txs.toVector))

  private def offer(
      peer: SocketAddress[Host],
      magic: Long,
      reader: TxSubmission2.TransactionReader,
      era: Era,
      limit: FiniteDuration,
      txs: Vector[Signed]
  ): IO[ExitCode] =
    (Ref.of[IO, Set[Bytes]](Set.empty), Deferred[IO, Unit], IO.monotonic).flatMapN {
      (asked, all, start) =>
        val tell = (id: Bytes) =>
          asked
            .updateAndGet(_ + id)
            .flatMap(s => all.complete(()).void.whenA(s.size == txs.size))
        val protocols = Set(Protocols.Handshake, Protocols.KeepAlive, Protocols.TxSubmission2)
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
                finished <- (
                  KeepAliveClient.background[IO](ka, mux),
                  TxSubmissionClient.backgroundFinishing[IO](mux, source(txs, era, reader, tell))
                ).tupled.use { (_, running) =>
                  all.get
                    .as(true)
                    .timeoutTo(limit, IO.pure(false))
                    .flatTap(_ => running.finish(30.seconds))
                }
              yield finished
            }
          }
          .rescue(e => IO.println(s"ERROR ${e.message}").as(false))
          .flatMap { finished =>
            (asked.get, IO.monotonic).flatMapN { (ids, end) =>
              val notAsked = txs.filterNot(t => ids.contains(t.id))
              IO.println(
                s"offered ${txs.size}, bodies asked ${ids.size}, in ${(end - start).toMillis} ms" +
                  (if finished then ""
                   else s"; not asked: ${notAsked.map(_.file.getFileName).take(10).mkString(", ")}")
              ).as(if finished then ExitCode.Success else ExitCode.Error)
            }
          }
    }
end TxSubmitPeer
