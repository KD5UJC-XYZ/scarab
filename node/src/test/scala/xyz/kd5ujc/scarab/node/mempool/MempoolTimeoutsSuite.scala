// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import xyz.kd5ujc.scarab.ledger.era.rules.ConwayRuleDefect
import cats.effect.{IO, Ref}
import cats.effect.std.Queue
import cats.mtl.Handle
import cats.syntax.all.*
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value as CborValue}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.rules.{NativeRules, PreConway}
import xyz.kd5ujc.scarab.ledger.state.{Babbage, Build, Conway, ShelleyBasedEra}
import xyz.kd5ujc.scarab.network.{DiffTime, LocalTxSubmissionServer, NetworkError}
import xyz.kd5ujc.scarab.node.assertions.Assertions
import xyz.kd5ujc.scarab.node.ntc.PredicateFailureCbor
import xyz.kd5ujc.scarab.plutus.{
  BackendFailure,
  EvalFailure,
  EvalRequest,
  EvalResult,
  ExUnits,
  ScarabEvaluator,
  ScriptEvaluator
}
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild, TxSubmission2}

import TestLedger.{out, txIn}
import TxKit.*

/** The mempool's time limits on the validation of a new transaction, as the reference's `doAddTx`
  * applies them (ouroboros-consensus 82ecba32 `Mempool/Update.hs` 205-319): a validation that took
  * longer than the soft limit is refused with Conway's error, from a client or a peer; one the hard
  * limit cuts off is refused so for a client and ends a peer's connection; before Conway, where the
  * era has no error, a late validation is judged as any other and a cut-off ends the client's
  * connection too; the pool waits while its transactions took longer than its capacity of time. The
  * durations are scripted ([[Scripted]]); nothing is decided by elapsed time, except in the last
  * test, where the node's own timing stops a script that would never end.
  */
class MempoolTimeoutsSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  private given Assertions[IO] = Assertions.noop[IO]
  private given cats.mtl.Raise[IO, StorageError] with
    def functor = cats.Functor[IO]
    def raise[E <: StorageError, A](error: E): IO[A] = IO(fail(error.message))

  private val failsafe = 20.seconds

  /** How a scripted validation ends: it ran and took `took`, or the hard limit cut it off after. */
  private enum Step:
    case Took(took: FiniteDuration)
    case Cut(took: FiniteDuration)

  /** A timing that runs each validation and reports the next scripted outcome (a validation it says
    * was cut off is not run), and records the hard limits it is given.
    */
  private final class Scripted(
      steps: Ref[IO, List[Step]],
      val limits: Ref[IO, Vector[MempoolTimeouts.HardLimit]]
  ) extends Timing[IO]:
    def within[A](limit: MempoolTimeouts.HardLimit)(validate: => A): IO[Timed[A]] =
      limits.update(_ :+ limit) *>
        steps
          .modify {
            case next :: rest => (rest, next)
            case Nil          => (Nil, Step.Took(Duration.Zero))
          }
          .flatMap {
            case Step.Took(took) => IO(Timed.Done(validate, took))
            case Step.Cut(took)  => IO.pure(Timed.Cut(took))
          }

  private def scripted(steps: Step*): IO[Scripted] =
    (
      Ref.of[IO, List[Step]](steps.toList),
      Ref.of[IO, Vector[MempoolTimeouts.HardLimit]](Vector.empty)
    )
      .mapN(Scripted(_, _))

  private def mempool(
      ledger: PoolLedger,
      timing: Timing[IO],
      events: MempoolEvents[IO] = MempoolEvents.none[IO],
      timeouts: MempoolTimeouts = MempoolTimeouts.default,
      env: MempoolEnv = TxKit.env
  ): IO[Mempool[IO]] =
    Mempool.create[IO](
      Right(ledger),
      1000,
      env,
      None,
      events,
      timeouts = Some(timeouts),
      timing = Some(timing)
    )

  private val soft = 1.second

  test(
    "a validation that took the soft limit is kept; a nanosecond longer is refused, either source"
  ) {
    val (t1, id1) = pay(1)
    val (t2, id2) = pay(2)
    val (t3, id3) = pay(3)
    for
      timing <- scripted(Step.Took(soft), Step.Took(soft + 1.nano), Step.Took(soft + 1.nano))
      pool <- mempool(ledger(3).toPool, timing)
      kept <- pool.add(t1, Source.Local)
      local <- pool.add(t2, Source.Local)
      remote <- pool.add(t3, Source.Remote)
      held <- pool.snapshot
      limits <- timing.limits.get
    yield
      assertEquals(kept, Right(id1))
      assertEquals(local, Left(Rejection.TooSlow(id2, Era.Conway, soft + 1.nano)))
      assertEquals(remote, Left(Rejection.TooSlow(id3, Era.Conway, soft + 1.nano)))
      assertEquals(held.entries.map(e => (e.id, e.validation)), Vector(id1 -> soft))
      assertEquals(limits, Vector.fill(3)(MempoolTimeouts.HardLimit.After(1500.millis)))
  }

  test("a late validation is refused as too slow whatever its result, a refusal of the rules too") {
    val (t1, id1) = pay(1, fee = 0)
    for
      timing <- scripted(Step.Took(2.seconds))
      pool <- mempool(ledger(1).toPool, timing)
      refused <- pool.add(t1, Source.Remote)
      held <- pool.snapshot
    yield
      assertEquals(refused, Left(Rejection.TooSlow(id1, Era.Conway, 2.seconds)))
      assertEquals(held.entries, Vector.empty)
  }

  test("Conway's refusal is its ConwayMempoolFailure with the reference's text") {
    val id = Blake2b.hash256.hash(Bytes.fromArray(Array[Byte](1, 2, 3)))
    val took = 1234567891.nanos
    val text =
      "MempoolTxTooSlow (1.234567891s) HardForkGenTxId {getHardForkGenTxId = " +
        "S (S (S (S (S (S (Z (WrapGenTxId {unwrapGenTxId = txid: TxId {unTxId = SafeHash \"" +
        id.hex + "\"}})))))))}"
    assertEquals(MempoolProblem.TooSlow(took, Era.Conway, id).message, text)
    // the id is the one of the era the transaction was submitted in, Babbage's here
    assert(
      MempoolProblem
        .TooSlow(took, Era.Babbage, id)
        .message
        .contains("= S (S (S (S (S (Z (WrapGenTxId {"),
      "five eras before Babbage"
    )
    // `show` of a `DiffTime`: no trailing zeros, and no point when no fraction is left
    assertEquals(
      Vector(1500.millis, 2.seconds, 1.nano, Duration.Zero, 1001.millis).map(MempoolProblem.shown),
      Vector("1.5s", "2s", "0.000000001s", "0s", "1.001s")
    )
    // the reply: `[[6, [[7, text]]]]`, Conway's `ApplyTxErr` with its one `ConwayMempoolFailure`
    val reply = LocalSubmission
      .reasonItem(Rejection.TooSlow(id, Era.Conway, took), Some(Era.Conway))
      .fold(e => fail(e.message), identity)
    Cbor.decode(reply).fold(e => fail(e.message), _.value) match
      case CborValue.Arr(Vector(wrapper)) =>
        wrapper.value match
          case CborValue.Arr(Vector(era, failures)) =>
            assertEquals(era.value, CborValue.UInt(6))
            failures.value match
              case CborValue.Arr(Vector(one)) =>
                one.value match
                  case CborValue.Arr(Vector(tag, sent)) =>
                    assertEquals(tag.value, CborValue.UInt(7))
                    assertEquals(sent.value, CborValue.Text(text))
                  case other => fail(s"$other")
              case other => fail(s"$other")
          case other => fail(s"$other")
      case other => fail(s"$other")
    assertEquals(
      Right(reply),
      PredicateFailureCbor.applyTxError(
        Vector(PredicateFailure.Mempool(MempoolProblem.TooSlow(took, Era.Conway, id)))
      )
    )
  }

  test("cut off: a client's transaction is refused as too slow, a peer's ends its connection") {
    val (t1, id1) = pay(1)
    val (t2, id2) = pay(2)
    val (t3, id3) = pay(3)
    val cut = 1500.millis
    for
      timing <- scripted(Step.Cut(cut), Step.Cut(cut), Step.Cut(cut), Step.Took(Duration.Zero))
      pool <- mempool(ledger(4).toPool, timing)
      local <- pool.add(t1, Source.Local)
      remote <- pool.add(t2, Source.Remote)
      sink = pool.sink(f => IO(fail(s"fatal: $f")))
      ended <- Handle
        .allow[NetworkError](
          sink.submit(Vector(TxSubmission2.EraTx(Era.Conway, t3))).as(Option.empty[NetworkError])
        )
        .rescue(e => IO.pure(Some(e)))
      next <- pool.add(pay(4)._1, Source.Local)
      held <- pool.snapshot
      response = LocalSubmission.response(Refused(Rejection.TooSlow(id1, Era.Conway, cut), None))
    yield
      assertEquals(local, Left(Rejection.TooSlow(id1, Era.Conway, cut)))
      assertEquals(remote, Left(Rejection.TimedOut(MempoolTimeout(Some(id2), cut))))
      assertEquals(
        ended,
        Some(
          NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(MempoolTimeout(Some(id3), cut)))
        )
      )
      assert(response.isInstanceOf[LocalTxSubmissionServer.Response.Rejected], s"$response")
      // nothing was held, and the next transaction is taken
      assertEquals(next, Right(pay(4)._2))
      assertEquals(held.entries.map(_.id), Vector(pay(4)._2))
  }

  /** A ledger of Babbage with two outputs of 100 ada for alice, at txIn(1) and txIn(2). */
  private def babbage: PoolLedger =
    val pre = summon[PreConway[Babbage]]
    given ShelleyBasedEra[Babbage] = pre.era
    val nes =
      NativeRules
        .stateIn[Babbage](7, (1 to 2).toVector.map(i => txIn(i) -> out(alice.address(), 100 * ada)))
    EarlierLedger(pre, nes.es.ls, nes.es.chainAccount, nes.epoch.toBigInt)

  /** Alice pays bob from `txIn(i)` in Babbage: `{0: inputs, 1: outputs, 2: fee, 3: ttl}`. */
  private def babbagePayment(i: Int): (Bytes, Bytes) =
    val body = Bytes(
      head(5, 4) ++
        Vector(0x00.toByte) ++ head(4, 1) ++ head(4, 2) ++ bstr(txIn(i).id) ++ head(0, 0) ++
        Vector(0x01.toByte) ++ head(4, 1) ++ head(4, 2) ++ bstr(bob.address().raw) ++
        head(0, 99 * ada) ++ Vector(0x02.toByte) ++ head(0, ada) ++
        Vector(0x03.toByte) ++ head(0, 100000)
    )
    val id = Blake2b.hash256.hash(body)
    val witnesses = Bytes(
      head(5, 1) ++ Vector(0x00.toByte) ++ head(4, 1) ++ head(4, 2) ++ bstr(alice.vkey) ++
        bstr(alice.sign(id))
    )
    (EraInjection.envelope(Block.Transaction(body, witnesses, None, true), Era.Babbage), id)

  test(
    "before Conway there is no error: a late validation is held, a cut-off ends a client's too"
  ) {
    val (t1, id1) = babbagePayment(1)
    val (t2, id2) = babbagePayment(2)
    val cut = 1500.millis
    for
      timing <- scripted(Step.Took(5.seconds), Step.Cut(cut))
      pool <- mempool(babbage, timing)
      late <- pool.add(t1, Source.Local)
      cutOff <- pool.add(t2, Source.Local)
      held <- pool.snapshot
    yield
      assertEquals(late, Right(id1))
      assertEquals(cutOff, Left(Rejection.TimedOut(MempoolTimeout(Some(id2), cut))))
      assertEquals(held.entries.map(e => (e.id, e.validation)), Vector(id1 -> 5.seconds))
      // no reply: the client's connection ends, as `ExnMempoolTimeout` ends the reference's
      assertEquals(
        LocalSubmission.response(
          Refused(Rejection.TimedOut(MempoolTimeout(Some(id2), cut)), Some(Era.Babbage))
        ),
        LocalTxSubmissionServer.Response.Aborted(MempoolTimeout(Some(id2), cut))
      )
  }

  test(
    "only Conway's ledger has the error: none from Shelley to Babbage, nor in Byron or Dijkstra"
  ) {
    val measured = MempoolState
      .empty(Right(ledger(1).toPool), 1000, None)
      .measure(
        MempoolState
          .read(Submission(Era.Conway.index, pay(1)._1), ReferenceBuild.Release11_2)
          .toOption
          .get,
        env
      )
      .fold(r => fail(r.message), identity)
    Era.values.foreach { era =>
      assertEquals(
        Mempool.tooSlow(era, measured, soft).isDefined,
        era == Era.Conway,
        s"$era"
      )
    }
  }

  /** The first event that satisfies `wanted`. */
  private def next(events: Queue[IO, MempoolEvent])(wanted: MempoolEvent => Boolean) =
    events.take.iterateUntil(wanted).timeout(failsafe)

  private val waitingForTime: MempoolEvent => Boolean =
    case MempoolEvent.WaitingForTime(_, _, _) => true
    case _                                    => false

  test("past the capacity of time the pool waits for a change; one transaction can take it past") {
    val (t1, id1) = pay(1)
    val (t2, id2) = pay(2)
    val (t3, id3) = pay(3)
    val capacity = MempoolTimeouts.default.copy(soft = DiffTime.of(10.seconds))
    for
      events <- Queue.unbounded[IO, MempoolEvent]
      timing <- scripted(Step.Took(5.seconds), Step.Took(1.nano), Step.Took(Duration.Zero))
      pool <- mempool(ledger(3).toPool, timing, (e: MempoolEvent) => events.offer(e), capacity)
      // 0 taken: the first is validated, and takes the pool to its capacity, 5 s
      first <- pool.add(t1, Source.Local)
      // 5 s is not past 5 s: the second is validated, and takes the pool past it
      second <- pool.add(t2, Source.Remote)
      // the third waits, cancelled whatever happens next; the wait holds no lock: the pool
      // changes, and the third is validated then
      waited <- pool.add(t3, Source.Local).background.use { third =>
        for
          waiting <- next(events)(waitingForTime)
          _ <- pool.removeTxsEvenIfValid(Set(id1))
          added <- third.flatMap(_.embedNever).timeout(failsafe)
        yield (waiting, added)
      }
      held <- pool.snapshot
    yield
      assertEquals((first, second), (Right(id1), Right(id2)))
      val (waiting, added) = waited
      assertEquals(
        waiting,
        MempoolEvent.WaitingForTime(
          Some(id3),
          DiffTime.of(5.seconds + 1.nano),
          DiffTime.of(5.seconds)
        )
      )
      assertEquals(added, Right(id3))
      assertEquals(
        held.entries.map(e => (e.id, e.validation)),
        Vector(id2 -> 1.nano, id3 -> Duration.Zero)
      )
      assertEquals(held.validationTime, DiffTime.of(1.nano))
  }

  test("the time the held transactions took holds a new one back under 11.1 and 11.2, not Leios") {
    val (t1, id1) = pay(1)
    val (t2, id2) = pay(2)
    val limits = MempoolTimeouts.default.copy(soft = DiffTime.of(10.seconds))
    Vector(ReferenceBuild.Release11_1, ReferenceBuild.Release11_2, ReferenceBuild.Leios)
      .traverse { build =>
        for
          events <- Queue.unbounded[IO, MempoolEvent]
          timing <- scripted(Step.Took(6.seconds), Step.Took(Duration.Zero))
          pool <- mempool(
            ledger(2).toPool,
            timing,
            (e: MempoolEvent) => events.offer(e),
            limits,
            built(build)
          )
          first <- pool.add(t1, Source.Local)
          // past the capacity of 5 s: a wait, cancelled once seen, or the transaction taken at once
          second <- build match
            case ReferenceBuild.Leios =>
              pool.add(t2, Source.Local).timeout(failsafe).map(Right(_))
            case ReferenceBuild.Release11_1 | ReferenceBuild.Release11_2 =>
              pool
                .add(t2, Source.Local)
                .background
                .use(_ => next(events)(waitingForTime))
                .map(Left(_))
          held <- pool.snapshot
        yield (build, first, second, held.entries.map(_.id))
      }
      .map(_.foreach { (build, first, second, held) =>
        assertEquals(first, Right(id1), s"$build")
        build match
          case ReferenceBuild.Leios =>
            assertEquals(second, Right(Right(id2)), s"$build")
            assertEquals(held, Vector(id1, id2), s"$build")
          case ReferenceBuild.Release11_1 | ReferenceBuild.Release11_2 =>
            assertEquals(
              second,
              Left(
                MempoolEvent
                  .WaitingForTime(Some(id2), DiffTime.of(6.seconds), DiffTime.of(5.seconds))
              ),
              s"$build"
            )
            assertEquals(held, Vector(id1), s"$build")
      })
  }

  test(
    "a duplicate waits for room and for time, as the reference's MEMPOOL rule comes after them"
  ) {
    val (t1, id1) = pay(1)
    val start = MempoolState.empty(Right(ledger(2).toPool), 1000, None)
    val measured = (s: MempoolState) =>
      s.measure(
        MempoolState
          .read(Submission(Era.Conway.index, t1), ReferenceBuild.Release11_2)
          .toOption
          .get,
        env
      )
    val (held, _) = start.add(t1, env, Source.Local).fold(r => fail(r.message), identity)
    // with room and time, it is refused as the duplicate it is
    assertEquals(held.add(t1, env, Source.Local), Left(Rejection.AlreadyHeld(id1)))
    // past the capacity of time, it waits before its refusal
    val slow = held.holding(held.entries.map(_.copy(validation = 6.seconds)))
    measured(slow) match
      case Right(m) =>
        assertEquals(
          slow.tryAdd(m, env, Source.Local, Some(DiffTime.of(5.seconds))),
          MempoolState.Tried.NoTime(DiffTime.of(6.seconds), DiffTime.of(5.seconds))
        )
      case other => fail(s"not measured: $other")
    // in a pool over its capacity in bytes, it waits for room
    val narrow = held
      .sync(Right(ledger(2).withParams(_.copy(maxBlockBodySize = 1024 + 50)).toPool), 1000, env)
      ._1
    assert(narrow.free.bytes < 0)
    assert(measured(narrow).left.exists(_.isInstanceOf[Rejection.Full]), s"${measured(narrow)}")
  }

  /** `[{}, 5]`: a Shelley update proposal with no proposals, which no Conway body reads. */
  private val update: Vector[Byte] = head(4, 2) ++ head(5, 0) ++ head(0, 5)

  /** Alice pays bob from `txIn(i)` in Alonzo, with an update proposal: the whole transaction and
    * its id.
    */
  private def alonzoWithUpdate(i: Int): (Bytes, Bytes) =
    val body = Bytes(
      head(5, 5) ++
        Vector(0x00.toByte) ++ head(4, 1) ++ head(4, 2) ++ bstr(txIn(i).id) ++ head(0, 0) ++
        Vector(0x01.toByte) ++ head(4, 1) ++ head(4, 2) ++ bstr(bob.address().raw) ++
        head(0, 99 * ada) ++ Vector(0x02.toByte) ++ head(0, ada) ++
        Vector(0x03.toByte) ++ head(0, 100000) ++ Vector(0x06.toByte) ++ update
    )
    val id = Blake2b.hash256.hash(body)
    val witnesses = Bytes(
      head(5, 1) ++ Vector(0x00.toByte) ++ head(4, 1) ++ head(4, 2) ++ bstr(alice.vkey) ++
        bstr(alice.sign(id))
    )
    (EraInjection.envelope(Block.Transaction(body, witnesses, None, true), Era.Alonzo), id)

  private def built(build: ReferenceBuild): MempoolEnv =
    env.copy(globals = env.globals.copy(referenceBuild = build))

  test(
    "11.1: a transaction no later era reads is refused by the rules, after the waits and timed"
  ) {
    val (raw, id) = alonzoWithUpdate(1)
    val submitted =
      MempoolState.read(Submission(Era.Alonzo.index, raw), ReferenceBuild.Release11_2).toOption.get
    val mismatch = Rejection.WrongEra(Era.Babbage.index, Era.Conway)
    val eleven = built(ReferenceBuild.Release11_1)
    // measured as nothing: a pool over its capacity makes it wait, and so does one past its time
    val start = MempoolState.empty(Right(ledger(2).toPool), 1000, None)
    val (held, _) = start.add(pay(2)._1, env, Source.Local).fold(r => fail(r.message), identity)
    val narrow = held
      .sync(Right(ledger(2).withParams(_.copy(maxBlockBodySize = 1024 + 50)).toPool), 1000, env)
      ._1
    assert(narrow.measure(submitted, eleven).left.exists(_.isInstanceOf[Rejection.Full]))
    val slow = held.holding(held.entries.map(_.copy(validation = 6.seconds)))
    slow.measure(submitted, eleven) match
      case Right(m) =>
        assertEquals(
          slow.tryAdd(m, eleven, Source.Local, Some(DiffTime.of(5.seconds))),
          MempoolState.Tried.NoTime(DiffTime.of(6.seconds), DiffTime.of(5.seconds))
        )
        assertEquals(
          slow.tryAdd(m, eleven, Source.Local, None),
          MempoolState.Tried.Done(Left(mismatch))
        )
      case other => fail(s"not measured: $other")
    // in time it is refused with the mismatch; late, as too slow, naming the era it came in
    for
      timing <- scripted(Step.Took(Duration.Zero), Step.Took(2.seconds), Step.Cut(1500.millis))
      pool <- mempool(ledger(2).toPool, timing, env = eleven)
      inTime <- pool.submit(Submission(Era.Alonzo.index, raw), Source.Local)
      late <- pool.submit(Submission(Era.Alonzo.index, raw), Source.Remote)
      cut <- pool.submit(Submission(Era.Alonzo.index, raw), Source.Local)
      limits <- timing.limits.get
    yield
      assertEquals(inTime.left.map(_.rejection), Left(mismatch))
      assertEquals(late.left.map(_.rejection), Left(Rejection.TooSlow(id, Era.Alonzo, 2.seconds)))
      assertEquals(cut.left.map(_.rejection), Left(Rejection.TooSlow(id, Era.Alonzo, 1500.millis)))
      assertEquals(limits.size, 3)
      assert(
        MempoolProblem
          .TooSlow(2.seconds, Era.Alonzo, id)
          .message
          .contains("= S (S (S (S (Z (WrapGenTxId {")
      )
  }

  test("11.2 and the Leios build: a transaction no later era reads is refused at its measure") {
    val (raw, _) = alonzoWithUpdate(1)
    val submitted =
      MempoolState.read(Submission(Era.Alonzo.index, raw), ReferenceBuild.Release11_2).toOption.get
    Vector(ReferenceBuild.Release11_2, ReferenceBuild.Leios).traverse_ { build =>
      // redecoded, the transaction does not read in Conway: the mismatch names Alonzo
      val mismatch = Rejection.WrongEra(Era.Alonzo.index, Era.Conway)
      val start = MempoolState.empty(Right(ledger(2).toPool), 1000, None)
      val (held, _) = start.add(pay(2)._1, env, Source.Local).fold(r => fail(r.message), identity)
      val narrow = held
        .sync(Right(ledger(2).withParams(_.copy(maxBlockBodySize = 1024 + 50)).toPool), 1000, env)
        ._1
      // refused at once, even by a pool over its capacity, and never timed
      assertEquals(narrow.measure(submitted, built(build)), Left(mismatch), s"$build")
      for
        timing <- scripted(Step.Took(2.seconds))
        pool <- mempool(ledger(2).toPool, timing, env = built(build))
        refused <- pool.submit(Submission(Era.Alonzo.index, raw), Source.Local)
        limits <- timing.limits.get
      yield
        assertEquals(refused.left.map(_.rejection), Left(mismatch), s"$build")
        assertEquals(limits, Vector.empty, s"$build: timed")
    }
  }

  test("a held transaction keeps the time its admission took when the pool is validated again") {
    val (t1, id1) = pay(1)
    val start = MempoolState.empty(Right(ledger(2).toPool), 1000, None)
    val measured = start
      .measure(
        MempoolState
          .read(Submission(Era.Conway.index, t1), ReferenceBuild.Release11_2)
          .toOption
          .get,
        env
      )
      .fold(r => fail(r.message), identity)
    start.tryAdd(measured, env, Source.Local, None) match
      case MempoolState.Tried.Done(Right(admitted)) =>
        val (held, _) = start.hold(admitted, 3.seconds)
        val (synced, dropped) = held.sync(Right(ledger(2).toPool), 1001, env)
        assertEquals(dropped, Vector.empty)
        assertEquals(synced.entries.map(e => (e.id, e.validation)), Vector(id1 -> 3.seconds))
        assertEquals(synced.validationTime, DiffTime.of(3.seconds))
      case other => fail(s"not admitted: $other")
  }

  /** The boundary case whose V3 script passes, and the ledger that funds it. */
  private lazy val (plutusLedger, plutusTx, plutusSlot) =
    val all = parse(
      Files.readString(
        Path.of("fixtures/reference/plutus-boundaries/transactions/conway-pv10.json")
      )
    ).fold(e => fail(e.message), identity).asArray.getOrElse(fail("not an array"))
    val j = all
      .find(_.hcursor.get[String]("name").contains("raw-dst-pass-valid-true"))
      .getOrElse(fail("no case raw-dst-pass-valid-true"))
    def hex(field: String): Bytes =
      j.hcursor
        .get[String](field)
        .flatMap(h => Bytes.fromHex(h).left.map(e => io.circe.DecodingFailure(e.message, Nil)))
        .fold(e => fail(e.getMessage), identity)
    val c = PlutusCase.fromJson(j)
    val ledger = MempoolLedger.of(
      Build.funded(Build.nes[Conway](5, c.params), c.spent ++ c.references ++ c.collateral)
    )
    (
      ledger,
      Tx.envelope(Block.Transaction(hex("body"), hex("witnesses"), None, c.tx.isValid)),
      c.slot
    )

  test(
    "past the soft limit a validation with no verdict keeps its cause, and a peer's connection ends"
  ) {
    // the reference's evaluation throws, and the exception escapes its `timeout`: no soft check
    val unavailable: ScriptEvaluator[cats.Id] = _ =>
      EvalResult.Failure(
        EvalFailure.Unsupported(BackendFailure.StackExhausted),
        ExUnits.zero,
        Vector.empty
      )
    val throwing: ScriptEvaluator[cats.Id] = _ => throw RuntimeException("an evaluator defect")
    def pool(evaluator: ScriptEvaluator[cats.Id], timing: Timing[IO]) =
      Mempool.create[IO](
        Right(plutusLedger.toPool),
        plutusSlot - 1,
        TxKit.env.copy(evaluator = evaluator),
        None,
        MempoolEvents.none[IO],
        timeouts = Some(MempoolTimeouts.default),
        timing = Some(timing)
      )
    for
      timing <- scripted(Step.Took(2.seconds), Step.Took(2.seconds), Step.Took(2.seconds))
      none <- pool(unavailable, timing)
      local <- none.add(plutusTx, Source.Local)
      ended <- Handle
        .allow[NetworkError](
          none
            .sink(f => IO(fail(s"fatal: $f")))
            .submit(Vector(TxSubmission2.EraTx(Era.Conway, plutusTx)))
            .as(Option.empty[NetworkError])
        )
        .rescue(e => IO.pure(Some(e)))
      defective <- pool(throwing, timing)
      threw <- defective.add(plutusTx, Source.Local)
      held <- (none.snapshot, defective.snapshot).tupled
    yield
      local match
        // no verdict on a script of Conway's rules, answered as the flat rules' was
        case Left(
              r @ Rejection.ConwayUnjudged(id, cause @ ConwayUnjudged.Unavailable(unavailable))
            ) =>
          // Conway's rules raised it unnamed, and the pool placed it by its kind
          assertEquals(
            id.map(ConwayUnjudged.rejection(_, ConwayRuleDefect.Unavailable(unavailable))),
            Some(r)
          )
          assertEquals(
            LocalSubmission.response(Refused(r, Some(Era.Conway))),
            xyz.kd5ujc.scarab.network.LocalTxSubmissionServer.Response.ServiceUnavailable(
              xyz.kd5ujc.scarab.network.LocalTxSubmissionServer.Unavailable.ScriptEvaluation(cause)
            )
          )
        case other => fail(s"no verdict refused as too slow, or otherwise: $other")
      ended match
        case Some(
              NetworkError.BearerClosed(
                NetworkError.Closure.AbortedBy(ConwayUnjudged.Unavailable(_))
              )
            ) =>
          ()
        case other => fail(s"the peer's connection did not end with the cause: $other")
      threw match
        case Left(Rejection.RulesDefect(_)) => ()
        case other => fail(s"a defect refused as too slow, or otherwise: $other")
      assertEquals((held._1.entries, held._2.entries), (Vector.empty, Vector.empty))
  }

  test("the node's timing stops a real heavy script at the hard limit, and the pool goes on") {
    // the transaction's own script replaced by the audit's loop of G2 hashToGroup with a budget
    // it never spends, evaluated by the evaluator the node's mempool runs
    val heavy = xyz.kd5ujc.scarab.plutus.UplcScripts.compile(
      "(program 1.0.0 [(lam self [self self]) (lam self [(lam x [self self]) " +
        "[(builtin bls12_381_G2_hashToGroup) (con bytestring #00) (con bytestring #00)]])])"
    )
    val evaluator: ScriptEvaluator[cats.Id] = (request: EvalRequest) =>
      ScarabEvaluator.interruptible.evaluate(
        request.copy(
          script = heavy,
          args = Vector.empty,
          budget = ExUnits(BigInt(Long.MaxValue), BigInt(Long.MaxValue))
        )
      )
    val limits = MempoolTimeouts(
      DiffTime.of(50.millis),
      DiffTime.of(200.millis),
      DiffTime.of(5.seconds)
    )
    val id = MempoolState.parse(plutusTx).fold(r => fail(r.message), _._1)
    for
      pool <- Mempool.create[IO](
        Right(plutusLedger.toPool),
        plutusSlot - 1,
        TxKit.env.copy(evaluator = evaluator),
        None,
        MempoolEvents.none[IO],
        timeouts = Some(limits),
        timing = Some(Timing.interruptible[IO])
      )
      local <- pool.add(plutusTx, Source.Local).timeout(failsafe)
      remote <- pool.add(plutusTx, Source.Remote).timeout(failsafe)
      held <- pool.snapshot
    yield
      local match
        case Left(Rejection.TooSlow(`id`, Era.Conway, took)) =>
          assert(took >= 200.millis, s"cut off after $took")
        case other => fail(s"a client's transaction cut off: $other")
      remote match
        case Left(Rejection.TimedOut(MempoolTimeout(Some(`id`), took))) =>
          assert(took >= 200.millis, s"cut off after $took")
        case other => fail(s"a peer's transaction cut off: $other")
      assertEquals(held.entries, Vector.empty)
  }
