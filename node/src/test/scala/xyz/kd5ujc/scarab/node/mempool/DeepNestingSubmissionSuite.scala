// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.effect.{IO, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import java.time.Instant
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.protocol.{Handshake, LocalTxSubmission, TxSubmission2}
import xyz.kd5ujc.scarab.protocol.ChainSync.Role
import xyz.kd5ujc.scarab.node.forge.{BlockBuilder, MempoolStateFixture}

import PreprodDeepTx.{bytes as tx, onSmallStack}
import Peers.typed

/** Preprod transaction f90dce57 carries a native script 5,383 levels deep. The reference accepts it
  * wherever it is offered, having no nesting limit but the size of the transaction (16,383 bytes of
  * the 16,384 allowed): in a block, through the node-to-node TxSubmission2, through the
  * node-to-client LocalTxSubmission, and into a block the node forges. These tests put it through
  * each path, against the ledger of the epoch it was valid in, built around the one output it
  * spends.
  */
class DeepNestingSubmissionSuite extends CatsEffectSuite:
  private val magic = 42L

  private val globals = LedgerGlobals(TestLedger.Supply, 432000, Rational(1, 20), network = 0)
  private val geometry =
    EpochGeometry(
      Instant.EPOCH,
      1000,
      epochLength = 432000,
      securityParam = 5,
      activeSlotsCoeff = 0.05
    )
  private val env = MempoolEnv(globals, geometry)
  private val tipSlot = PreprodDeepTx.Slot - 1

  private def pool: IO[Mempool[IO]] =
    Mempool.create[IO](
      Right(TxKit.conway(PreprodDeepTx.ledger)),
      tipSlot,
      env,
      1 << 20,
      MempoolEvents.none[IO]
    )

  private def held(mempool: Mempool[IO]): IO[Vector[String]] =
    mempool.snapshot.map(_.entries.map(_.id.hex))

  test("the mempool admits it, parsing and validating on a stack too small to recurse over it") {
    val admitted =
      onSmallStack(
        MempoolState.empty(Right(TxKit.conway(PreprodDeepTx.ledger)), tipSlot, 1 << 20).add(tx, env)
      )
    assertEquals(admitted.map(_._2.id.hex), Right(PreprodDeepTx.Id))
  }

  test("the mempool still refuses a transaction that is malformed however deep it is") {
    // the same bytes with the tail cut off
    val cut = tx.slice(0, tx.size - 40)
    assert(
      MempoolState
        .empty(Right(TxKit.conway(PreprodDeepTx.ledger)), tipSlot, 1 << 20)
        .add(cut, env)
        .left
        .exists(_.isInstanceOf[Rejection.Malformed])
    )
  }

  test("LocalTxSubmission accepts it into the mempool") {
    val wrapped =
      Bytes(Vector(0x82, 0x06, 0xd8, 0x18).map(_.toByte) ++ TxKit.bstr(tx)) // [6, 24(tx)]
    Resource
      .eval((pool, Bearer.inMemory[IO]()).tupled)
      .use { case (mempool, (clientEnd, serverEnd)) =>
        val server = typed(
          LocalTxSubmissionServer
            .accept(serverEnd, magic, LocalSubmission.handler(mempool))
            .map(_.message)
        ).start
        server.flatMap { running =>
          typed {
            Mux.resource(clientEnd, Set(0, 6), Mux.Config(role = Mux.Role.Initiator)).use { mux =>
              for
                hs <- mux.channel(0)
                _ <- HandshakeClient.run(
                  hs,
                  Vector(16, 17, 23).map(v => v -> Handshake.Data(magic)),
                  suite = Handshake.Suite.NodeToClient
                )
                ch <- mux.channel(6)
                bytes <- IO.fromEither(
                  LocalTxSubmission
                    .encode(
                      LocalTxSubmission.State.Idle,
                      Role.Client,
                      LocalTxSubmission.Message.SubmitTx(wrapped)
                    )
                    .leftMap(RuntimeException(_))
                )
                _ <- ch.send(bytes)
                reply <- ch.recv(LocalTxSubmission.MaxMessageBytes)(
                  LocalTxSubmission.decodePrefix(LocalTxSubmission.State.Busy, Role.Server, _)
                )
              yield reply
            }
          }.flatMap { reply =>
            running.cancel *> held(mempool).map { ids =>
              assertEquals(reply, Right(LocalTxSubmission.Message.AcceptTx))
              assertEquals(ids, Vector(PreprodDeepTx.Id))
            }
          }
        }
      }
  }

  test("TxSubmission2 offers it to a peer, which fetches it and the mempool admits it") {
    val id = TxSubmission2.bodyId(tx).fold(e => fail(e), identity)
    assertEquals(id.hex, PreprodDeepTx.Id)
    val offered = new TxSubmissionClient.Source[IO]:
      private val one = TxSubmissionClient.Held(id, tx.size, 1L)
      def after(ticket: Long, max: Int) = IO.pure(Vector(one).filter(_.ticket > ticket))
      def awaitAfter(ticket: Long) = if ticket < 1 then IO.unit else IO.never
      def body(wanted: Bytes) = IO.pure(Option.when(wanted == id)(tx))
    pool.flatMap { mempool =>
      typed {
        Peers.linked(protocols = Set(4)).use { (client, server) =>
          for
            c <- client.channel(4)
            s <- server.channel(4, Mux.Role.Responder)
            _ <- typed(TxSubmissionClient.run(c, offered)).start
            _ <- typed(TxSubmissionServer.run(s, mempool.sink)).start
            _ <- held(mempool).iterateUntil(_.nonEmpty).timeout(10.seconds)
          yield ()
        }
      }.flatMap(r => held(mempool).map(ids => (r, ids))).map { (r, ids) =>
        assertEquals(r, Right(()))
        assertEquals(ids, Vector(PreprodDeepTx.Id))
      }
    }
  }

  test("the forger includes it in a block, which decodes back to the same transaction") {
    val entry = MempoolStateFixture.entry(tx)
    val limit = BlockBuilder.Limits(65536, xyz.kd5ujc.scarab.plutus.ExUnits(14000000, 10000000000L))
    val chosen = onSmallStack(
      BlockBuilder.select(
        TxKit.conway(PreprodDeepTx.ledger),
        PreprodDeepTx.Slot,
        PreprodDeepTx.Epoch,
        geometry,
        globals,
        Vector(entry),
        limit
      )
    )
    assertEquals(chosen.map(_._1.id.hex), Vector(PreprodDeepTx.Id))
    val body = BlockBuilder.body(chosen.map(_._2))
    assertEquals(BlockBuilder.bodySize(chosen.map(_._2)), body.size)
    val block = BlockBuilder.block(
      BlockBuilder.headerBody(
        1,
        PreprodDeepTx.Slot,
        None,
        Bytes.fromArray(new Array[Byte](32)),
        Bytes.fromArray(new Array[Byte](32)),
        VrfCert(Bytes.fromArray(new Array[Byte](64)), Bytes.fromArray(new Array[Byte](80))),
        body.size,
        body.hash,
        Header.OpCert(
          Bytes.fromArray(new Array[Byte](32)),
          0,
          0,
          Bytes.fromArray(new Array[Byte](64))
        ),
        (10, 3)
      ),
      Bytes.fromArray(new Array[Byte](448)),
      body
    )
    val decoded = onSmallStack(Block.decodeShelley(block))
    decoded match
      case Right(b: Block.Shelley) =>
        assertEquals(b.transactions.map(_.id.hex), Vector(PreprodDeepTx.Id))
        assertEquals(b.bodyHash, body.hash)
      case other => fail(other.toString)
  }
