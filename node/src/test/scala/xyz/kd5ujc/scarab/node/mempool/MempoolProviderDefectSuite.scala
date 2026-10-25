// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.effect.IO
import cats.mtl.Handle
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.witness.VerificationError
import xyz.kd5ujc.scarab.ledger.{LedgerFailure, ProviderDefect, Verdicts, VerificationDefect}
import xyz.kd5ujc.scarab.network.{LocalTxSubmissionServer, NetworkError}
import xyz.kd5ujc.scarab.protocol.ChainSync

import TxKit.*

/** Issue 393: a crypto provider that fails on a transaction witness outside a block. The reference
  * lets the exception end the thread that met it (consensus 82ecba32 `Mempool/Update.hs` `doAddTx`
  * evaluates `pureTryAddTx` there): a local client's connection closes, a peer's connection closes,
  * and the revalidation watcher, which is linked to the node, stops the node. Nothing is a
  * rejection, so the pool is not changed by the transaction.
  */
class MempoolProviderDefectSuite extends CatsEffectSuite:
  private val boom = new IllegalStateException("provider broke")
  private val defect = ProviderDefect.Ed25519(VerificationError.ImplementationFailure(boom))

  private val failing: Verdicts = new Verdicts:
    export Verdicts.Direct.{opCert, praosVrf, tpraosVrf, keyHash, takePrepared}
    def witness(key: Bytes, signature: Bytes, message: Bytes) = Left(defect)

  private val defective = env.copy(verdicts = failing)

  private def wrap(tx: Bytes): Bytes =
    Bytes(Vector(0x82, 0x06, 0xd8, 0x18).map(_.toByte) ++ bstr(tx))

  test("a local submission met by a provider defect closes the connection, the pool unchanged") {
    val (t1, id) = pay(1)
    for
      mempool <- Mempool.create[IO](Right(ledger(2)), 1000, defective, None, MempoolEvents.none[IO])
      submit = LocalSubmission.handler[IO](mempool)(f => IO(fail(s"fatal: $f")))
      response <- submit(wrap(t1))
      held <- mempool.snapshot
    yield
      response match
        case LocalTxSubmissionServer.Response.ServiceUnavailable(
              _: LocalTxSubmissionServer.Unavailable.Verification[?]
            ) =>
          ()
        case other => fail(s"unexpected $other")
      assertEquals(held.entries, Vector.empty)
      assert(!held.contains(id))
  }

  test("a peer's transaction met by a provider defect closes that peer's connection") {
    val (t1, id) = pay(1)
    val (t2, id2) = pay(2)
    for
      mempool <- Mempool.create[IO](Right(ledger(3)), 1000, defective, None, MempoolEvents.none[IO])
      sink = mempool.sink(f => IO(fail(s"fatal: $f")))
      ended <- Handle
        .allow[NetworkError](sink.submit(Vector(t1, t2)).as(Option.empty[NetworkError]))
        .rescue(e => IO.pure(Some(e)))
      held <- mempool.snapshot
    yield
      ended match
        case Some(
              NetworkError.BearerClosed(
                NetworkError.Closure.AbortedBy(LedgerFailure.Unverifiable(`id`, `defect`))
              )
            ) =>
          ()
        case other => fail(s"unexpected $other")
      assertEquals(held.entries, Vector.empty)
      assert(!held.contains(id2))
  }

  test("a revalidation met by a provider defect is the node's defect at the new tip") {
    val (t1, id) = pay(1)
    val point = ChainSync.Point.Origin
    val state = MempoolState.empty(Right(ledger(2)), 1000, None)
    val held = state.add(t1, env, Source.Local).map(_._1)
    held.fold(r => fail(r.toString), _ => ())
    val (_, dropped) = held.toOption.get.sync(Right(ledger(2)), 1001, defective)
    assertEquals(
      Mempool.unverifiable(dropped, point),
      Some(VerificationDefect(point, VerificationDefect.Site.Witness(id), defect))
    )
    assertEquals(Mempool.unverifiable(Vector.empty, point), None)
  }
