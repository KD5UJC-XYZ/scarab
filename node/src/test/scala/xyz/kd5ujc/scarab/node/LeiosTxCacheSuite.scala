// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyVector
import cats.effect.{Deferred, IO}
import cats.mtl.Raise
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.leios.{EbClosureVerdict, ProtVerParity}
import xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.rbHash
import xyz.kd5ujc.scarab.ledger.{StorageError, TxIn, TxOut, WitnessCheck}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  DijkstraClosure,
  DijkstraLedgerPredFailure,
  DijkstraMempoolPredFailure,
  DijkstraUtxowPredFailure,
  LeiosClosureDefect
}
import xyz.kd5ujc.scarab.ledger.state.SlotNo
import xyz.kd5ujc.scarab.node.mempool.{DijkstraState, Rejection}
import xyz.kd5ujc.scarab.protocol.LeiosTxHash
import xyz.kd5ujc.scarab.storage.{LeiosEb, MemoryUtxoStore, UtxoReadView}

/** The closure check with the node's LeiosTxCache (`validateEbClosure`, `LeiosVoting.hs:446-565` at
  * ouroboros-consensus 2416493b), on the testnet's recorded closure of 323 transactions
  * ([[RecordedLeiosClosure]]): a transaction the cache holds validated under the state's parity is
  * reapplied, without the static checks, and any other applied in full, as the reference's `decide`
  * takes them; what it validates in full is recorded, also the valid prefix of a closure that does
  * not apply; an evicted transaction is a miss again; and with every transaction cached the check
  * takes a fraction of the cold one. Its cases of the cold and warm checks, of the other parity and
  * of a closure that does not apply are the reference's own properties of `validateEbClosure`
  * (`prop_freshClosureFullyApplied`, `prop_secondLookReapplies`, `prop_otherProtVerRevalidates`,
  * `prop_recordsValidatedBeforeFailure`, `prop_stopsAtFirstFailure`,
  * `ouroboros-consensus/test/consensus-test/Test/LeiosVoting.hs:86-145` at 2416493b), here on the
  * chain's own transactions.
  */
class LeiosTxCacheSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  override def munitIOTimeout: Duration = 5.minutes

  private given Raise[IO, StorageError] with
    def functor = cats.Functor[IO]
    def raise[E <: StorageError, A](error: E): IO[A] = IO(fail(error.message))

  private val r = RecordedLeiosClosure

  private lazy val state: DijkstraState =
    LeiosClosureCheck
      .ticked(
        r.env.rules,
        r.seeded,
        SlotNo.fromBigInt(r.slot).fold(e => fail(e.toString), identity)
      )
      .fold(e => fail(e.toString), identity)

  private lazy val parity: ProtVerParity = LeiosClosureCheck.parityOf(state)

  private def other(p: ProtVerParity): ProtVerParity = p match
    case ProtVerParity.Even => ProtVerParity.Odd
    case ProtVerParity.Odd  => ProtVerParity.Even

  /** Transaction 6 of the closure (no auxiliary data, so its bytes end with its one key witness's
    * signature, then `null` and `true`) with the signature's last byte flipped: its body, and so
    * its balance and inputs, unchanged, only its witness wrong, which is a static check.
    */
  private lazy val forged: (LeiosTxHash, Bytes) =
    val raw = r.stored(6)._2
    assertEquals(raw.drop(raw.size - 2), Bytes.fromArray(Array(0xf6.toByte, 0xf5.toByte)))
    val at = raw.size - 3
    val flipped = raw.lift(at).fold(fail("no signature byte"))(b => (b ^ 1).toByte)
    val altered = raw.take(at) ++ Bytes.fromArray(Array(flipped)) ++ raw.drop(at + 1)
    LeiosEb.hashTx(altered) -> altered

  private def withForged: Vector[(LeiosTxHash, Bytes)] = r.stored.updated(6, forged)

  /** A cache that holds an announcement and the body of `closure`, so its transactions are tracked
    * (`insertAnnouncement`, `insertBody`), and the transactions `applied` validated under `under`.
    */
  private def cacheOf(
      closure: Vector[(LeiosTxHash, Bytes)],
      applied: Vector[LeiosTxHash],
      under: ProtVerParity
  ): IO[LeiosTxCache[IO]] =
    val body = LeiosEb.of(NonEmptyVector.fromVectorUnsafe(closure.map(_._2)))
    val eb = LeiosEb.hashOf(body.transactions.toVector)
    for
      cache <- LeiosTxCache.create[IO]
      _ <- cache.insertAnnouncement(r.point.slot.toBigInt, rbHash(1), eb)
      _ <- cache.insertBody(eb, body)
      _ <- cache.insertApplied(applied, under)
    yield cache

  /** The check of `closure` with `cache`; with `witnesses`, its witness signatures verified in
    * parallel first and handed to the rules by that cache, as the node runs it.
    */
  private def check(
      closure: Vector[(LeiosTxHash, Bytes)],
      cache: LeiosTxCache[IO],
      witnesses: Option[VerificationCache] = None
  ): IO[EbClosureVerdict[Rejection, LeiosClosureDefect]] =
    LeiosClosureCheck.validateEbClosure[IO](
      _ => IO.pure(Some(closure)),
      None,
      cache,
      witnesses.fold((_: Vector[WitnessCheck]) => IO.unit)(_.warmWitnesses[IO]),
      r.point,
      state,
      witnesses.fold(r.env)(w => r.env.copy(verdicts = w))
    )

  private def validated(cache: LeiosTxCache[IO], closure: Vector[(LeiosTxHash, Bytes)]) =
    cache.lookup.map(look => closure.map(e => look(e._1)))

  private def refusedWitness(v: EbClosureVerdict[Rejection, ?]): Unit = v match
    case EbClosureVerdict.Invalid(Rejection.DijkstraRefused(_, failures)) =>
      assert(
        failures.exists {
          case DijkstraMempoolPredFailure.LedgerFailure(
                DijkstraLedgerPredFailure.DijkstraUtxowFailure(
                  DijkstraUtxowPredFailure.InvalidWitnessesUTXOW(_)
                )
              ) =>
            true
          case _ => false
        },
        s"$failures"
      )
    case other => fail(s"not a refused witness: $other")

  test(
    "a cold check validates every transaction in full and records them; the next reapplies all"
  ) {
    for
      cache <- cacheOf(r.stored, Vector.empty, parity)
      cold <- check(r.stored, cache)
      held <- validated(cache, r.stored)
      warm <- check(r.stored, cache)
    yield
      assertEquals(cold, EbClosureVerdict.Valid(0, 323))
      assertEquals(held, Vector.fill(323)(Some(Right(parity))))
      assertEquals(warm, EbClosureVerdict.Valid(323, 0))
  }

  test("tags under the other parity send every transaction back through the full check") {
    cacheOf(r.stored, r.stored.map(_._1), other(parity))
      .flatMap(check(r.stored, _))
      .map(v => assertEquals(v, EbClosureVerdict.Valid(0, 323)))
  }

  test(
    "a hit is reapplied without the static checks; a miss, or a hit of the other parity, is not"
  ) {
    val all = withForged.map(_._1)
    for
      hit <- cacheOf(withForged, all, parity).flatMap(check(withForged, _))
      // the forged one untagged, every other one a hit
      miss <- cacheOf(withForged, all.filterNot(_ == forged._1), parity)
        .flatMap(check(withForged, _))
      otherParity <- cacheOf(withForged, all, other(parity)).flatMap(check(withForged, _))
    yield
      // reapplyTx checks no witness: the reference's verdict on a closure its cache holds whole
      assertEquals(hit, EbClosureVerdict.Valid(323, 0))
      refusedWitness(miss)
      refusedWitness(otherParity)
  }

  test("a closure that does not apply records the valid prefix it validated, and no more") {
    for
      cache <- cacheOf(withForged, Vector.empty, parity)
      verdict <- check(withForged, cache)
      held <- validated(cache, withForged)
    yield
      refusedWitness(verdict)
      assertEquals(held.take(6), Vector.fill(6)(Some(Right(parity))))
      assertEquals(held.drop(6), Vector.fill(317)(None))
  }

  test(
    "witnesses verified in parallel ahead of the application: the same verdicts, each one taken"
  ) {
    VerificationCache.resource[IO](4).use { witnesses =>
      for
        parallel <- cacheOf(r.stored, Vector.empty, parity).flatMap(
          check(r.stored, _, Some(witnesses))
        )
        taken = (witnesses.witnessStats.hits.sum, witnesses.witnessStats.misses.sum)
        forgedParallel <- cacheOf(withForged, Vector.empty, parity).flatMap(
          check(withForged, _, Some(witnesses))
        )
        forgedSerial <- cacheOf(withForged, Vector.empty, parity).flatMap(check(withForged, _))
        // a hit is reapplied and checks no witness: none of its signatures is verified ahead
        before = witnesses.warmedStats.misses.sum
        _ <- cacheOf(r.stored, r.stored.map(_._1), parity).flatMap(
          check(r.stored, _, Some(witnesses))
        )
        after = witnesses.warmedStats.misses.sum
      yield
        assertEquals(parallel, EbClosureVerdict.Valid(0, 323))
        // every one of the 323 key witnesses the rules asked for was there, none computed again
        assertEquals(taken, (323L, 0L))
        refusedWitness(forgedParallel)
        assertEquals(forgedParallel, forgedSerial)
        assertEquals(after, before)
    }
  }

  /** The recorded state without the outputs of it the closure spends, and those outputs. */
  private lazy val split: (DijkstraState, Map[TxIn, TxOut]) =
    val txs = DijkstraClosure
      .resolveLeiosClosure(r.point.hash, Some(r.stored))
      .fold(e => fail(e.toString), _.map(_._2))
    val inputs = MirrorDijkstra.inputs(txs)
    val held = state.utxo
    // the closure also spends outputs earlier transactions of it make, which no state holds
    val spent = inputs.flatMap(i => held.get(i).map(i -> _)).toMap
    assert(spent.nonEmpty, "the state holds outputs the closure spends")
    (
      state.withUtxo(
        held.copy(diff = held.diff -- spent.keySet, resident = held.resident -- spent.keySet)
      ),
      spent
    )

  /** A storage error raised as an exception, so that a test can tell the check's own failure. */
  private final case class Stopped(error: StorageError) extends Exception

  /** `under`, whose reads signal `reading` and then wait for `release`. */
  private final class Gated(
      under: UtxoReadView[IO],
      reading: Deferred[IO, Unit],
      release: Deferred[IO, Unit]
  ) extends UtxoReadView[IO]:
    def read(keys: Iterable[TxIn])(using Raise[IO, StorageError]): IO[Map[TxIn, TxOut]] =
      reading.complete(()) *> release.get *> under.read(keys)
    def stamp(using Raise[IO, StorageError]): IO[Long] = under.stamp
    def all(using Raise[IO, StorageError]): IO[Map[TxIn, TxOut]] = under.all
    def select(keep: TxOut => Boolean)(using Raise[IO, StorageError]): IO[Map[TxIn, TxOut]] =
      under.select(keep)
    def selectAddresses(addresses: Set[Bytes])(using
        Raise[IO, StorageError]
    ): IO[Map[TxIn, TxOut]] = under.selectAddresses(addresses)

  test(
    "the steps are decided on the cache as it is once the outputs are read, as the reference reads it"
  ) {
    val (stripped, spent) = split
    VerificationCache.resource[IO](4).use { witnesses =>
      for
        store <- MemoryUtxoStore[IO]
        _ <- store.write(spent.view.mapValues(Some(_)).toMap, 0L)
        reading <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        cache <- cacheOf(r.stored, Vector.empty, parity)
        running <- LeiosClosureCheck
          .validateEbClosure[IO](
            _ => IO.pure(Some(r.stored)),
            Some(Gated(store, reading, release)),
            cache,
            witnesses.warmWitnesses[IO],
            r.point,
            stripped,
            r.env.copy(verdicts = witnesses)
          )
          .start
        _ <- reading.get
        // tagged while the outputs are read, after the warming chose what to verify ahead
        _ <- cache.insertApplied(Vector(r.stored(0)._1), parity)
        _ <- release.complete(())
        verdict <- running.joinWithNever
      yield assertEquals(verdict, EbClosureVerdict.Valid(1, 322))
    }
  }

  test("a failed read of the outputs ends the check while other warming holds every permit") {
    given Raise[IO, StorageError] with
      def functor = cats.Functor[IO]
      def raise[E <: StorageError, A](error: E): IO[A] = IO.raiseError(Stopped(error))
    val (stripped, _) = split
    val failing = new UtxoReadView[IO]:
      def read(keys: Iterable[TxIn])(using
          raise: Raise[IO, StorageError]
      ): IO[Map[TxIn, TxOut]] = raise.raise(StorageError.DatabaseInUse("the test's store"))
      def stamp(using Raise[IO, StorageError]): IO[Long] = IO.pure(0L)
      def all(using Raise[IO, StorageError]): IO[Map[TxIn, TxOut]] = IO.pure(Map.empty)
      def select(keep: TxOut => Boolean)(using Raise[IO, StorageError]): IO[Map[TxIn, TxOut]] =
        IO.pure(Map.empty)
      def selectAddresses(addresses: Set[Bytes])(using
          Raise[IO, StorageError]
      ): IO[Map[TxIn, TxOut]] = IO.pure(Map.empty)
    VerificationCache.resource[IO](1).use { witnesses =>
      IO.blocking(witnesses.running.acquire())
        .bracket { _ =>
          for
            cache <- cacheOf(r.stored, Vector.empty, parity)
            ended <- LeiosClosureCheck
              .validateEbClosure[IO](
                _ => IO.pure(Some(r.stored)),
                Some(failing),
                cache,
                witnesses.warmWitnesses[IO],
                r.point,
                stripped,
                r.env.copy(verdicts = witnesses)
              )
              .attempt
          yield ended match
            case Left(Stopped(StorageError.DatabaseInUse(_))) => ()
            case other => fail(s"not the store's error: $other")
        }(_ => IO.delay(witnesses.running.release()))
    }
  }

  test("a transaction only the mempool held and a fetched one: applied and unapplied") {
    for
      cache <- cacheOf(r.stored, Vector(r.stored(0)._1), parity)
      _ <- cache.insertUnapplied(Vector(r.stored(1)._1))
      look <- cache.lookup
    yield
      assertEquals(look(r.stored(0)._1), Some(Right(parity)))
      assertEquals(look(r.stored(1)._1), Some(Left(())))
      assertEquals(look(r.stored(2)._1), None)
      assertEquals(
        LeiosClosureCheck.Step.of(look(r.stored(1)._1), parity),
        LeiosClosureCheck.Step.Apply
      )
  }

  test("evicting the announcement prunes the cache: its transactions are misses again") {
    for
      cache <- cacheOf(r.stored, r.stored.map(_._1), parity)
      _ <- cache.evictOlderThan(r.point.slot.toBigInt + 1)
      held <- validated(cache, r.stored)
      index <- cache.snapshot
      verdict <- check(r.stored, cache)
    yield
      assertEquals(held, Vector.fill(323)(None))
      assertEquals(index.announcementCount, 0)
      // evicted past the endorser block's slot: the check records nothing it cannot track
      assertEquals(verdict, EbClosureVerdict.Valid(0, 323))
  }

  test("benchmark: the recorded closure's check with a warm cache takes well under a second") {
    val runs = 5
    def timed[A](io: IO[A]): IO[(FiniteDuration, A)] =
      IO.monotonic.flatMap(t0 => io.flatMap(a => IO.monotonic.map(t1 => (t1 - t0, a))))
    def best(io: IO[EbClosureVerdict[Rejection, LeiosClosureDefect]]) =
      (1 to runs).toVector.traverse(_ => timed(io)).map(rs => (rs.map(_._1).min, rs.map(_._2)))
    for
      (cold, coldVerdicts) <- best(
        cacheOf(r.stored, Vector.empty, parity).flatMap(check(r.stored, _))
      )
      (warm, warmVerdicts) <- best(
        cacheOf(r.stored, r.stored.map(_._1), parity).flatMap(check(r.stored, _))
      )
      (parallel, parallelVerdicts) <- VerificationCache
        .resource[IO](4)
        .use(w =>
          best(cacheOf(r.stored, Vector.empty, parity).flatMap(check(r.stored, _, Some(w))))
        )
      _ <- IO.println(
        f"LeiosTxCacheSuite: 323 transactions, cold ${cold.toMicros / 1000.0}%.1f ms, " +
          f"cold with witnesses on 4 threads ${parallel.toMicros / 1000.0}%.1f ms, " +
          f"warm ${warm.toMicros / 1000.0}%.1f ms, ${cold.toNanos.toDouble / warm.toNanos}%.1fx"
      )
    yield
      assertEquals(coldVerdicts.distinct, Vector(EbClosureVerdict.Valid(0, 323)))
      assertEquals(parallelVerdicts.distinct, Vector(EbClosureVerdict.Valid(0, 323)))
      assertEquals(warmVerdicts.distinct, Vector(EbClosureVerdict.Valid(323, 0)))
      assert(warm < 1.second, s"warm $warm")
      assert(warm < cold, s"warm $warm, cold $cold")
  }
