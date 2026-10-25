// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.effect.{IO, Ref}
import cats.mtl.{Ask, Handle, Raise}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.witness.VerificationError
import xyz.kd5ujc.scarab.ledger.{
  LedgerFailure,
  ProviderDefect,
  StorageError,
  Verdicts,
  VerificationDefect
}
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
    val held = MempoolState
      .empty(Right(ledger(2)), 1000, None)
      .add(t1, env, Source.Local)
      .fold(r => fail(r.toString), _._1)
    val stopped = held.sync(Right(ledger(2)), 1001, defective).left.toOption
    assertEquals(stopped, Some(noVerdict(id)))
    assertEquals(
      stopped.map(Mempool.unverifiable(_, point)),
      Some(VerificationDefect(point, VerificationDefect.Site.Witness(id), defect))
    )
    assertEquals(held.remove(Set.empty, defective).left.toOption, Some(noVerdict(id)))
  }

  private def noVerdict(id: Bytes): MempoolState.NoVerdict = LedgerFailure.Unverifiable(id, defect)

  private given Raise[IO, StorageError] with
    def functor = cats.Functor[IO]
    def raise[E <: StorageError, A](error: E): IO[A] = IO(fail(error.message))

  /** A pool holding two transactions under the real verifiers, whose environment then turns
    * `defective`; what `revalidate` raised, and the state and events before and after it.
    */
  private def revalidated(
      revalidate: Mempool[IO] => Raise[IO, MempoolState.NoVerdict] ?=> IO[Unit]
  ): IO[(Option[MempoolState.NoVerdict], MempoolState, MempoolState, Vector[MempoolEvent])] =
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
      raised <- Handle
        .allow[MempoolState.NoVerdict](revalidate(mempool).as(Option.empty[MempoolState.NoVerdict]))
        .rescue(e => IO.pure(Some(e)))
      after <- mempool.snapshot
      events <- emitted.get
    yield (raised, before, after, events.drop(seen.size))

  test("a defective sync changes nothing: the state stays, no Dropped or Synced event") {
    val (_, id1) = pay(1)
    revalidated(m => m.sync(Right(ledger(3)), 1001).void).map { (raised, before, after, events) =>
      assertEquals(raised, Some(noVerdict(id1)))
      assertEquals(before.entries.size, 2)
      assertEquals(after, before)
      assertEquals(events, Vector.empty)
    }
  }

  test(
    "a defective removeTxsEvenIfValid changes nothing: the state stays, no Removed or Dropped event"
  ) {
    val (_, id1) = pay(1)
    val (_, id2) = pay(2)
    revalidated(m => m.removeTxsEvenIfValid(Set(id1)).void).map { (raised, before, after, events) =>
      assertEquals(raised, Some(noVerdict(id2)))
      assertEquals(after, before)
      assertEquals(events, Vector.empty)
    }
  }
