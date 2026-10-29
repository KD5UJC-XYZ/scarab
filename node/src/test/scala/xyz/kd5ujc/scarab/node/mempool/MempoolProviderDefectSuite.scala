// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.effect.{IO, Ref}
import cats.mtl.{Ask, Handle, Raise}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.witness.VerificationError
import xyz.kd5ujc.scarab.ledger.{LedgerFailure, ProviderDefect, StorageError, Verdicts}
import xyz.kd5ujc.scarab.network.{LocalTxSubmissionServer, NetworkError}

import TxKit.*

/** Issue 393: a crypto provider that fails on a transaction witness outside a block (and, as the
  * evaluator that fails on its scripts, the same). The reference lets the exception end the thread
  * that met it (consensus 82ecba32 `Mempool/Update.hs` `doAddTx` evaluates `pureTryAddTx` there): a
  * local client's connection closes and a peer's connection closes. Nothing is a rejection, so the
  * pool is not changed by the transaction. Revalidation applies a held transaction again without
  * its static checks (`reapplyTx`), so it verifies no witness and the provider is not asked.
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

  test("a revalidation does not ask a provider that now fails: it verifies no witness again") {
    val (t1, id) = pay(1)
    val held = MempoolState
      .empty(Right(ledger(2)), 1000, None)
      .add(t1, env, Source.Local)
      .fold(r => fail(r.toString), _._1)
    assertEquals(held.sync(Right(ledger(2)), 1001, defective)._1.entries.map(_.id), Vector(id))
    assertEquals(held.remove(Set.empty, defective)._1.entries.map(_.id), Vector(id))
  }

  private given Raise[IO, StorageError] with
    def functor = cats.Functor[IO]
    def raise[E <: StorageError, A](error: E): IO[A] = IO(fail(error.message))

  /** A pool holding two transactions under the real verifiers, whose environment then turns
    * `defective`; the state and events before and after `revalidate`.
    */
  private def revalidated(
      revalidate: Mempool[IO] => IO[Unit]
  ): IO[(MempoolState, MempoolState, Vector[MempoolEvent])] =
    val (t1, _) = pay(1)
    val (t2, _) = pay(2)
    for
      current <- Ref.of[IO, MempoolEnv](env)
      emitted <- Ref.of[IO, Vector[MempoolEvent]](Vector.empty)
      environment = new Ask[IO, MempoolEnv]:
        def applicative = cats.effect.IO.asyncForIO
        def ask[E2 >: MempoolEnv] = current.get
      mempool <- Mempool.create[IO](
        Right(ledger(3)),
        1000,
        env,
        None,
        event => emitted.update(_ :+ event),
        None,
        Some(environment)
      )
      _ <- mempool.add(t1, Source.Local)
      _ <- mempool.add(t2, Source.Local)
      before <- mempool.snapshot
      seen <- emitted.get
      _ <- current.set(defective)
      _ <- revalidate(mempool)
      after <- mempool.snapshot
      events <- emitted.get
    yield (before, after, events.drop(seen.size))

  private def dropped(events: Vector[MempoolEvent]) =
    events.collect { case d: MempoolEvent.Dropped => d }

  test("a sync under a provider that now fails asks it nothing: the pool keeps its transactions") {
    val (_, id1) = pay(1)
    val (_, id2) = pay(2)
    revalidated(m => m.sync(Right(ledger(3)), 1001).void).map { (before, after, events) =>
      assertEquals(before.entries.map(_.id), Vector(id1, id2))
      assertEquals(after.entries.map(_.id), Vector(id1, id2))
      assertEquals(dropped(events), Vector.empty)
    }
  }

  test("a removeTxsEvenIfValid under a provider that now fails asks it nothing either") {
    val (_, id1) = pay(1)
    val (_, id2) = pay(2)
    revalidated(m => m.removeTxsEvenIfValid(Set(id1)).void).map { (_, after, events) =>
      assertEquals(after.entries.map(_.id), Vector(id2))
      assertEquals(dropped(events), Vector.empty)
    }
  }
