// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import com.comcast.ip4s.UnixSocketAddress
import fs2.io.net.Network
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.TestLedger.txIn
import xyz.kd5ujc.scarab.protocol.{Handshake, LocalTxSubmission}
import xyz.kd5ujc.scarab.protocol.ChainSync.Role
import xyz.kd5ujc.scarab.network.*
import scala.concurrent.duration.*

import TxKit.*

/** `cardano-cli transaction submit` as a client: node-to-client handshake, then LocalTxSubmission,
  * over a Unix socket into a real mempool.
  */
class LocalSubmissionSuite extends FailsOnUncaught:
  private val magic = 42L

  private def wrap(tx: Bytes): Bytes =
    // [6, 24(h'tx')]
    Bytes(Vector(0x82, 0x06, 0xd8, 0x18).map(_.toByte) ++ bstr(tx))

  private def submit(
      channel: MuxChannel[IO],
      item: Bytes
  )(using Handle[IO, NetworkError]): IO[LocalTxSubmission.Message] =
    for
      bytes <- IO.fromEither(
        LocalTxSubmission
          .encode(
            LocalTxSubmission.State.Idle,
            Role.Client,
            LocalTxSubmission.Message.SubmitTx(item)
          )
          .left
          .map(e => RuntimeException(e.message))
      )
      _ <- channel.send(bytes)
      reply <- channel.recv(LocalTxSubmission.MaxMessageBytes)(
        LocalTxSubmission.decodePrefix(LocalTxSubmission.State.Busy, Role.Server, _)
      )
    yield reply

  test(
    "a local client submits through the Unix socket: accepted once, then refused with a reason"
  ) {
    val (good, id) = pay(1)
    val (clash, _) = pay(1, fee = 2 * ada)
    Resource
      .eval(
        Mempool.create[IO](Right(ledger(2)), 1000, env, 100000, MempoolEvents.none[IO])
      )
      .flatMap { mempool =>
        Resource.eval(IO(java.nio.file.Files.createTempDirectory("csl-ntc"))).flatMap { dir =>
          val path = dir.resolve("node.socket")
          BearerServer
            .listenUnix[IO, NetworkError](path)(error => IO(fail(error.toString))) { bearer =>
              Handle
                .allow[NetworkError](
                  LocalTxSubmissionServer
                    .accept(bearer, magic, LocalSubmission.handler(mempool)(failure => IO(fail(s"unexpected fatal: $failure"))))
                    .map(_.message)
                )
                .rescue(e => IO.pure(e.message))
                .void
            }
            .as((mempool, path))
        }
      }
      .use { (mempool, path) =>
        Network
          .forAsync[IO]
          .connect(UnixSocketAddress(path.toString))
          .flatMap(socket => Resource.pure[IO, Bearer[IO]](Bearer.fromSocket(socket)))
          .use { bearer =>
            Handle
              .allow[NetworkError] {
                Mux
                  .resource(bearer, Set(0, 6), Mux.Config(role = Mux.Role.Initiator))
                  .use { mux =>
                    for
                      hs <- mux.channel(0)
                      agreed <- HandshakeClient.run(
                        hs,
                        Vector(16, 17, 23).map(v => v -> Handshake.Data(magic)),
                        suite = Handshake.Suite.NodeToClient
                      )
                      ch <- mux.channel(6)
                      accepted <- submit(ch, wrap(good))
                      again <- submit(ch, wrap(good))
                      conflicting <- submit(ch, wrap(clash))
                      garbage <- submit(ch, Bytes(Vector(0x01)))
                      wrongEra <- submit(ch, Bytes(Vector(0x82, 0x05, 0x41, 0x00).map(_.toByte)))
                      held <- mempool.snapshot.map(_.entries.map(_.id))
                    yield (
                      agreed.version,
                      accepted,
                      List(again, conflicting, garbage, wrongEra),
                      held
                    )
                  }
              }
              .rescue(e => IO.raiseError(RuntimeException(e.message)))
          }
          .map { case (version, accepted, refused, held) =>
            assertEquals(version, 23)
            assertEquals(accepted, LocalTxSubmission.Message.AcceptTx)
            assert(
              refused.forall(_.isInstanceOf[LocalTxSubmission.Message.RejectTx]),
              refused.toString
            )
            assertEquals(held, Vector(id))
          }
      }
  }

  test("a client speaking a node-to-client version we do not have is refused") {
    Resource
      .eval(Bearer.inMemory[IO]())
      .use { (clientEnd, serverEnd) =>
        Mempool.create[IO](Right(ledger(1)), 1000, env, 1000, MempoolEvents.none[IO]).flatMap {
          mempool =>
            Handle
              .allow[NetworkError](
                LocalTxSubmissionServer
                  .accept(serverEnd, magic, LocalSubmission.handler(mempool)(failure => IO(fail(s"unexpected fatal: $failure"))))
                  .map(_.message)
              )
              .rescue(e => IO.pure(e.message))
              .start
              .flatMap { server =>
                Handle
                  .allow[NetworkError] {
                    Mux.resource(clientEnd, Set(0, 6), Mux.Config()).use { mux =>
                      mux
                        .channel(0)
                        .flatMap(
                          HandshakeClient.run(
                            _,
                            Vector(Handshake.Data(magic)).map(d => 16 -> d.copy(magic = magic + 1)),
                            suite = Handshake.Suite.NodeToClient
                          )
                        )
                        .void
                    }
                  }
                  .rescue(_ => IO.pure(()))
                  *> server.joinWithNever.map(r => assert(r.nonEmpty))
              }
        }
      }
  }

  test(
    "an unavailable local submission sends no rejection, closes only its connection, and permits a fresh client"
  ) {
    val fixture = xyz.kd5ujc.scarab.ledger.PlutusCase
      .load(java.nio.file.Path.of("fixtures/preprod/plutus/tx-2edd684f.json"))
    val script =
      (fixture.tx.witnesses.scripts ++ fixture.references.values.flatMap(_.referenceScript))
        .find(_.kind.isPlutus)
        .getOrElse(fail("fixture has no Plutus script"))
    val code = script.plutus.getOrElse(fail("fixture discarded script bytes"))
    val (good, id) = tx(
      Vector(txIn(1)),
      Vector(bob.address() -> (99 * ada)),
      ada,
      Vector(alice),
      referenceScript = Some(script.kind -> code)
    )
    val decoder: xyz.kd5ujc.scarab.plutus.ScriptDecoder[cats.Id] = (_, _, _) =>
      Left(
        xyz.kd5ujc.scarab.plutus.ScriptDecodeFailure
          .Unavailable(xyz.kd5ujc.scarab.plutus.BackendFailure.StackExhausted)
      )
    val failure = xyz.kd5ujc.scarab.ledger.LedgerFailure.Unavailable(
      id,
      xyz.kd5ujc.scarab.ledger.ScriptLocation.Phase1(script.hash),
      xyz.kd5ujc.scarab.plutus.EvalFailure
        .Unsupported(xyz.kd5ujc.scarab.plutus.BackendFailure.StackExhausted)
    )
    LocalSubmission
      .reasonItem(Rejection.Ledger(failure))
      .fold(
        error => assertEquals(error.message, failure.message),
        bytes => fail(s"unavailability encoded rejection bytes ${bytes.hex}")
      )
    Resource
      .eval(for
        context <- Ref.of[IO, MempoolEnv](env.copy(decoder = decoder))
        ask = new cats.mtl.Ask[IO, MempoolEnv]:
          val applicative = summon[cats.Applicative[IO]]
          def ask[E >: MempoolEnv]: IO[E] = context.get.map(value => value: E)
        mempool <- Mempool.create[IO](
          Right(ledger(2)),
          1000,
          env,
          100000,
          MempoolEvents.none[IO],
          environment = Some(ask)
        )
        ended <- Deferred[IO, LocalTxSubmissionServer.End]
        dir <- IO(java.nio.file.Files.createTempDirectory("scarab-unavailable-submission"))
      yield (mempool, context, ended, dir.resolve("node.socket")))
      .flatMap { (mempool, context, ended, path) =>
        BearerServer
          .listenUnix[IO, NetworkError](path)(error => IO(fail(error.toString))) { bearer =>
            Handle
              .allow[NetworkError](
                NodeToClientServer.accept(bearer, magic, LocalSubmission.handler(mempool)(failure => IO(fail(s"unexpected fatal: $failure"))))
              )
              .rescue(error => IO.pure(LocalTxSubmissionServer.End.ConnectionClosed(error)))
              .flatMap(end => ended.complete(end).void)
          }
          .as((mempool, context, ended, path))
      }
      .use { (mempool, context, ended, path) =>
        def client: IO[Either[NetworkError, LocalTxSubmission.Message]] =
          Network.forAsync[IO].connect(UnixSocketAddress(path.toString)).use { socket =>
            Handle
              .allow[NetworkError] {
                Mux.resource(Bearer.fromSocket(socket), Set(0, 6)).use { mux =>
                  for
                    hs <- mux.channel(0)
                    _ <- HandshakeClient.run(
                      hs,
                      Vector(23 -> Handshake.Data(magic)),
                      suite = Handshake.Suite.NodeToClient
                    )
                    ch <- mux.channel(6)
                    answer <- submit(ch, wrap(good))
                  yield answer.asRight[NetworkError]
                }
              }
              .rescue(error => IO.pure(error.asLeft[LocalTxSubmission.Message]))
          }
        for
          unavailable <- client
          endedAs <- ended.get
          heldBefore <- mempool.contains(id)
          _ <- context.set(env)
          healthy <- client
          heldAfter <- mempool.contains(id)
        yield
          unavailable match
            case Left(_: NetworkError.BearerClosed) => ()
            case other => fail(s"expected connection closure without rejection bytes, got $other")
          endedAs match
            case LocalTxSubmissionServer.End.ServiceUnavailable(
                  _: LocalTxSubmissionServer.Unavailable.ScriptEvaluation[?]
                ) =>
              ()
            case other => fail(s"unexpected service end $other")
          assert(!heldBefore)
          assertEquals(healthy, Right(LocalTxSubmission.Message.AcceptTx))
          assert(heldAfter)
      }
  }
