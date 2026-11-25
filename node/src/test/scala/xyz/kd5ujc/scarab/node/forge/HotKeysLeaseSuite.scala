// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.IORuntimeConfig
import cats.mtl.Raise
import cats.syntax.all.*
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.crypto.vrf.{Draft03Prover, PraosVrfCertificate}
import xyz.kd5ujc.scarab.ledger.ForgeError
import xyz.kd5ujc.scarab.node.Fatal

/** Who owns the keys a read returns, and what an evaluation in flight does at a reload (#1418). The
  * reader of a key owns it until the reload installs it, and the scope of the read erases what the
  * reload does not take. An evaluation of the VRF key holds a lease on the credentials in force
  * when it starts and ends with their key, whatever a reload does meanwhile: a reload installs the
  * next credentials at once, and the superseded ones are erased when the last evaluation under them
  * ends. Disabling forging, a refused reload and a start as non-producing erase what they should.
  */
class HotKeysLeaseSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught with ScheduleFixture:

  private val alpha = hex(Array.fill(32)(5.toByte))
  private val zeroSeed = hex(Array.fill(32)(0.toByte))

  /** Whether `key` 's seed is zero: erased. */
  private def erased(key: Draft03Prover.SigningKey): Boolean = key.toBytes.take(32) == zeroSeed

  /** `body` with the forger's terminal failures raised, so that a test sees one. */
  private def ok[A](body: Raise[IO, Fatal] ?=> IO[A]): IO[A] =
    Forger.scoped(body).flatMap(_.fold(f => IO.raiseError(new AssertionError(f.message)), IO.pure))

  /** A key source over files the test sets, each read finding keys of its own ([[afresh]]), kept in
    * `made` as the read made them; nothing is written back.
    */
  private final class Files(
      files: Ref[IO, HotKeySource.Read[IO]],
      made: Ref[IO, Vector[HotKeySource.Read[IO]]],
      reading: Ref[IO, Option[Deferred[IO, Unit]]]
  ) extends HotKeySource[IO]:
    def read = HotKeySource.reading(
      reading.getAndSet(None).flatMap(_.traverse_(_.complete(()))) *>
        files.get.map(afresh).flatTap(found => made.update(_ :+ found)).map(Right(_))
    )
    def persist(key: Sum6KesSigning.SigningKey) = IO.pure(Persisted.Unchanged)
    def hold(next: HotKeySource.Read[IO]): IO[Unit] = files.set(next)

    /** Completed when the next read begins. */
    def nextRead: IO[Deferred[IO, Unit]] =
      Deferred[IO, Unit].flatTap(began => reading.set(Some(began)))

    /** The VRF and KES keys each read made, in order. */
    def keys: IO[Vector[(Draft03Prover.SigningKey, Sum6KesSigning.SigningKey)]] =
      made.get.map(_.collect { case HotKeySource.Read.Found(hot, vrf, _, _) => vrf -> hot.kes })

  private def filesOf(first: HotKeySource.Read[IO]): IO[Files] =
    (
      Ref.of[IO, HotKeySource.Read[IO]](first),
      Ref.of[IO, Vector[HotKeySource.Read[IO]]](Vector.empty),
      Ref.of[IO, Option[Deferred[IO, Unit]]](None)
    ).mapN(Files(_, _, _))

  /** What the files of `p` hold with the VRF key of `seed` (`vrfOf`) in place of the pool's. */
  private def withVrf(p: Pool, kesSeed: Int, seed: Int): HotKeySource.Read[IO] =
    HotKeySource.Read.Found(
      HotCredentials(kesOf(kesSeed), p.creds.opCert, p.creds.coldVkey),
      vrfOf(seed),
      IO.unit
    )

  /** `f` run while an evaluation of the key `vrf` names, under its lease, waits in its computation;
    * then the evaluation's proof of `alpha`, once it is let go, and what `f` gave. The scope opens
    * once the evaluation holds its lease, and fails if the evaluation ends without; however it
    * ends, the evaluation is let go, then cancelled if it has not ended, and joined.
    */
  private def whileEvaluating[A](hot: HotKeys[IO], vrf: Bytes)(f: IO[A]) =
    val held =
      for
        entered <- Resource.eval(IO(new CountDownLatch(1)))
        go <- Resource.eval(IO(new CountDownLatch(1)))
        proving <- hot.keys
          .evaluating(vrf) { key =>
            entered.countDown()
            go.await()
            key.prove(alpha)
          }
          .background
        // released before the evaluation is cancelled, so that its computation is let go first
        release <- Resource.make(IO.pure(IO(go.countDown())))(identity)
        _ <- Resource.eval(IO.race(IO.interruptible(entered.await()), proving).flatMap {
          case Left(_)        => IO.unit
          case Right(outcome) => IO.raiseError(new AssertionError(s"no lease held: $outcome"))
        })
      yield (release, proving.flatMap(_.embedNever))
    held.use((release, proved) => f.flatTap(_ => release).flatMap(a => proved.tupleRight(a)))

  test(
    "an evaluation holding its lease across a reload ends with the key it began with and a " +
      "correct proof; that key is erased once it ends, and not before"
  ) {
    val p = pool(kesStart = 50, seed = 41)
    val start = p.creds.vrf
    val other = vrfOf(141)
    for
      files <- filesOf(withVrf(p, 41, 141))
      hot <- HotKeys.create[IO](p.creds, Some(files), config, _ => IO.unit)
      ran <- whileEvaluating(hot, start.publicKey)(
        ok(hot.reload(50, Some(0))) *> (IO(erased(start)), hot.inForce.get).tupled
      )
      (proved, (during, installed)) = ran
      after <- IO(erased(start))
    yield
      assert(!during, "the key was erased while an evaluation held its lease")
      assertEquals(installed.vrf, Some(other.publicKey), "the reload installed its key at once")
      proved match
        case Some(Right(Draft03Prover.Proved(proof, output))) =>
          assertEquals(
            PraosVrfCertificate.verifyAlpha(alpha, start.publicKey, proof, output),
            PraosVrfCertificate.Result.VerifiedCertificate(output)
          )
        case other => fail(s"the evaluation did not prove with its key: $other")
      assert(after, "the superseded key was not erased once its last evaluation ended")
  }

  test(
    "an evaluation after a reload uses the key it installed, and inputs that name the superseded " +
      "key are not evaluated"
  ) {
    val p = pool(kesStart = 50, seed = 42)
    val start = p.creds.vrf
    val other = vrfOf(142)
    for
      files <- filesOf(withVrf(p, 42, 142))
      hot <- HotKeys.create[IO](p.creds, Some(files), config, _ => IO.unit)
      before <- hot.keys.evaluating(start.publicKey)(_.output(alpha))
      _ <- ok(hot.reload(50, Some(0)))
      next <- hot.keys.evaluating(other.publicKey)(_.output(alpha))
      superseded <- hot.keys.evaluating(start.publicKey)(_.output(alpha))
      read <- files.keys
    yield
      assertEquals(before, Some(p.vrfRead.output(alpha)))
      assertEquals(next, Some(other.output(alpha)), "the new key's output")
      assertEquals(superseded, None, "a superseded key is not evaluated, nor refused")
      assert(erased(start), "the superseded key is erased")
      assertEquals(read.map((vrf, _) => erased(vrf)), Vector(false), "the key installed is kept")
  }

  test(
    "a reload that reads the same VRF key installs the read's copy: evaluations of inputs naming " +
      "it go on with it, and the superseded copy is erased"
  ) {
    val p = pool(kesStart = 50, seed = 43)
    val start = p.creds.vrf
    val expected = p.vrfRead.output(alpha)
    for
      files <- filesOf(found(p, 43))
      hot <- HotKeys.create[IO](p.creds, Some(files), config, _ => IO.unit)
      _ <- ok(hot.reload(50, Some(0)))
      again <- hot.keys.evaluating(start.publicKey)(_.output(alpha))
      read <- files.keys
      kes <- currentHot(hot).map(_.kes)
    yield
      assertEquals(again, Some(expected))
      assert(erased(start), "the superseded copy is erased")
      assertEquals(read.map((vrf, _) => erased(vrf)), Vector(false), "the read's copy is in force")
      // the files hold the KES key in use: the key in use is kept, and the file's copy erased
      assert(kes eq p.creds.kes, "the KES key in use is the one kept")
      assert(!kes.isForgotten, "the KES key kept was erased with the credentials it was taken from")
      assertEquals(read.map((_, k) => k.isForgotten), Vector(true), "the file's copy is erased")
  }

  test(
    "a refused reload erases the keys it read and leaves the credentials in force as they were"
  ) {
    val p = pool(kesStart = 50, seed = 44)
    val stranger = pool(kesStart = 50, seed = 45)
    for
      seen <- Ref.of[IO, Vector[ForgeEvent]](Vector.empty)
      // another pool's credentials
      files <- filesOf(found(stranger, 45))
      hot <- HotKeys.create[IO](p.creds, Some(files), config, e => seen.update(_ :+ e))
      _ <- ok(hot.reload(50, Some(0)))
      events <- seen.get
      read <- files.keys
      still <- hot.keys.evaluating(p.creds.vrf.publicKey)(_.prove(alpha))
      inUse <- currentHot(hot)
    yield
      assertEquals(
        events.collect { case ForgeEvent.KesReloadRefused(why) => why },
        Vector(ReloadRefusal.WrongPool(p.creds.poolId, stranger.creds.poolId))
      )
      assertEquals(read.map((vrf, kes) => (erased(vrf), kes.isForgotten)), Vector((true, true)))
      assert(still.exists(_.isRight), s"the VRF key in force: $still")
      assert(!erased(p.creds.vrf))
      assertEquals(inUse, HotKeys.Signer.of(p.creds))
      assert(!inUse.kes.isForgotten)
  }

  test("disabling forging erases the keys in force once the evaluations under them end") {
    val p = pool(kesStart = 50, seed = 46)
    for
      files <- filesOf(HotKeySource.Read.Absent(Path.of("kes.skey")))
      hot <- HotKeys.create[IO](p.creds, Some(files), config, _ => IO.unit)
      ran <- whileEvaluating(hot, p.creds.vrf.publicKey)(
        ok(hot.reload(50, Some(0))) *> IO((erased(p.creds.vrf), p.creds.kes.isForgotten))
      )
      (proved, during) = ran
      enabled <- hot.enabled
      after <- hot.keys.evaluating(p.creds.vrf.publicKey)(_.output(alpha))
    yield
      assertEquals(during, (false, false), "erased while an evaluation held their lease")
      assert(proved.exists(_.isRight), s"the evaluation in flight: $proved")
      assert(!enabled)
      assertEquals(after, None)
      assert(erased(p.creds.vrf), "the VRF key is erased")
      assert(p.creds.kes.isForgotten, "the KES key is erased")
  }

  test(
    "a node started as non-producing holds no key: the start-up keys are erased at once, and a " +
      "reload that finds the files forges with the keys it read"
  ) {
    val p = pool(kesStart = 50, seed = 47)
    for
      files <- filesOf(found(p, 47))
      hot <- HotKeys.create[IO](p.creds, Some(files), config, _ => IO.unit, enabled = false)
      before <- (hot.enabled, hot.keys.evaluating(p.creds.vrf.publicKey)(_.output(alpha))).tupled
      startUp <- IO((erased(p.creds.vrf), p.creds.kes.isForgotten))
      _ <- ok(hot.reload(50, Some(0)))
      after <- hot.keys.evaluating(p.creds.vrf.publicKey)(_.output(alpha))
      read <- files.keys
    yield
      assertEquals(before, (false, None))
      assertEquals(startUp, (true, true))
      assertEquals(after, Some(p.vrfRead.output(alpha)))
      assertEquals(read.map((vrf, kes) => (erased(vrf), kes.isForgotten)), Vector((false, false)))
  }

  // The runtime checks for cancelation at every step and yields at every second one, so the seeded
  // schedules interleave the evaluations, their leases and the reloads at any step.
  test(
    "under any interleaving of evaluations, their cancellations and reloads, no evaluation is " +
      "refused, each ends with the key it named, and every superseded key ends erased"
  ) {
    val everyStep = IORuntimeConfig(1, 2)
    val p0 = pool(kesStart = 50, seed = 49)
    val pk = p0.creds.vrf.publicKey
    val otherPk = vrfOf(149).publicKey
    val expected = Map(pk -> p0.vrfRead.output(alpha), otherPk -> vrfOf(149).output(alpha))
    (1 to 24).toList
      .traverse { seed =>
        val program =
          for
            // each run its own keys: the forger erases those it supersedes
            p <- IO(pool(kesStart = 50, seed = 49))
            files <- filesOf(found(p, 49))
            hot <- HotKeys.create[IO](p.creds, Some(files), config, _ => IO.unit)
            // the cancelled evaluations that held their lease when the cancel came
            held <- IO(new AtomicInteger(0))
            evaluations = (1 to 18).toList.parTraverse(i =>
              def evaluation(counted: Boolean) = hot.inForce.get.flatMap(
                _.vrf.flatTraverse(vrf =>
                  hot.keys
                    .evaluating(vrf) { key =>
                      if counted then held.incrementAndGet()
                      key.output(alpha)
                    }
                    .map(_.tupleLeft(vrf))
                )
              )
              // every third cancelled wherever it is: its lease, if it took one, ends with it
              if i % 3 == 0 then evaluation(true).start.flatMap(f => f.cancel *> f.join).as(None)
              else evaluation(false)
            )
            // the files alternate between the pool's VRF key and another, the KES key the same
            reloads = (1 to 4).toList.traverse(i =>
              files.hold(if i % 2 == 0 then found(p, 49) else withVrf(p, 49, 149)) *>
                Forger.scoped(hot.reload(50, Some(0)))
            )
            ran <- (evaluations, reloads).parTupled
            (results, reloaded) = ran
            read <- files.keys
            cancelledHolding <- IO(held.get)
          yield
            assertEquals(reloaded, List.fill(4)(Right(())), s"seed $seed")
            results.flatten.foreach((vrf, output) =>
              assertEquals(output, expected(vrf), s"seed $seed: an evaluation of ${vrf.hex}")
            )
            // the start-up key and every key a reload installed and the next one superseded
            val superseded = p.creds.vrf +: read.dropRight(1).map(_._1)
            assertEquals(superseded.map(erased), Vector.fill(superseded.size)(true), s"seed $seed")
            assertEquals(read.lastOption.map((vrf, _) => erased(vrf)), Some(false), s"seed $seed")
            // the KES key in use kept throughout, each file's copy erased with its read
            assert(!p.creds.kes.isForgotten, s"seed $seed: the KES key in use")
            assertEquals(read.map(_._2.isForgotten), Vector.fill(read.size)(true), s"seed $seed")
            (results.flatten.size, cancelledHolding)
        val seeded = java.util.Base64.getEncoder.encodeToString(seed.toString.getBytes)
        TestControl.executeEmbed(program, everyStep, Some(seeded))
      }
      .map { counts =>
        val (evaluated, holding) = counts.unzip
        assert(evaluated.sum > 0, "no evaluation ran")
        // the cancellations land while a lease is held too, not only before one is taken
        assert(holding.sum > 0, s"no cancelled evaluation held its lease: $holding")
      }
  }

  test(
    "under any interleaving, a reload cancelled once its read has begun, in the read's scope or " +
      "in the move of its keys into force, leaves no key unerased but the one in force"
  ) {
    val everyStep = IORuntimeConfig(1, 2)
    val other = vrfOf(150).publicKey
    (1 to 96).toList
      .traverse { seed =>
        val program =
          for
            p <- IO(pool(kesStart = 50, seed = 50))
            files <- filesOf(withVrf(p, 50, 150))
            hot <- HotKeys.create[IO](p.creds, Some(files), config, _ => IO.unit)
            began <- files.nextRead
            cancelled <- Resource
              .make(Forger.scoped(hot.reload(50, Some(0))).start)(_.cancel)
              .use(reloading =>
                // the cancel comes `seed` steps of the canceller later: the runtime may run the
                // reload at every second one, so that across the seeds the cancel lands anywhere
                IO.race(began.get, reloading.join).flatMap {
                  case Left(_) => IO.unit.replicateA_(seed) *> reloading.cancel *> reloading.join
                  case Right(outcome) => IO.pure(outcome)
                }
              )
            // a reload after it: refused at once if the cancel left the keys failed
            _ <- files.hold(found(p, 50))
            after <- Forger.scoped(hot.reload(50, Some(0)))
            read <- files.keys
            inForce <- hot.keys.lent((vrf, _) => vrf)
          yield
            val live = (p.creds.vrf +: read.map(_._1)).filterNot(erased)
            assertEquals(live.size, 1, s"seed $seed: keys not erased")
            assertEquals(live.map(_.publicKey).headOption, inForce, s"seed $seed: the key kept")
            assertEquals(read.map(_._2.isForgotten), Vector.fill(read.size)(true), s"seed $seed")
            assert(!p.creds.kes.isForgotten, s"seed $seed: the KES key in use")
            after match
              case Right(()) | Left(Fatal.Internal(ForgeError.KesUpdateInterrupted)) => ()
              case other => fail(s"seed $seed: the reload after the cancelled one: $other")
            // cancelled, and with its keys in force or not
            Option.when(cancelled.isCanceled)(after.isLeft && inForce.contains(other))
        val seeded = java.util.Base64.getEncoder.encodeToString(seed.toString.getBytes)
        TestControl.executeEmbed(program, everyStep, Some(seeded))
      }
      .map { cancels =>
        val landed = cancels.flatten
        assert(landed.contains(true), s"no cancel came once the reload had installed: $cancels")
        assert(landed.contains(false), s"no cancel came before the reload installed: $cancels")
      }
  }

  /** A slot of KES period 50 that the VRF key of `led` leads and that of `notLed` does not, with
    * all the stake under the genesis nonce.
    */
  private def ledOnlyBy(led: Int, notLed: Int): BigInt =
    (BigInt(5000) until BigInt(5090))
      .find(s => leads(led, s) && !leads(notLed, s))
      .getOrElse(fail(s"no slot led by $led and not by $notLed"))

  /** The per-slot decision of `s` of the pool of `seed`, with `at` 's changes made at the forger's
    * points: the step's result, the reloads' results, and the events.
    */
  private def stepped(seed: Int, s: BigInt)(
      at: Pool => PartialFunction[Forger.Point, HotKeySource.Read[IO]]
  ) =
    val p = pool(kesStart = 50, seed = seed)
    withHandle {
      for
        files <- filesOf(found(p, seed))
        self <- Deferred[IO, Harness]
        reloads <- Ref.of[IO, Vector[Either[Fatal, Unit]]](Vector.empty)
        hooks = at(p)
        h <- harness(
          p,
          ledgerWith(p, 1),
          source = Some(files),
          clock = Some(IO.pure(java.time.Instant.EPOCH.plusSeconds(5000))),
          pause = Some(point =>
            hooks
              .lift(point)
              .traverse_(next =>
                files.hold(next) *> self.get
                  .flatMap(_.forger.reload)
                  .flatMap(r => reloads.update(_ :+ r))
              )
          )
        )
        _ <- self.complete(h)
        result <- h.forger.step(s)
        reloaded <- reloads.get
        events <- h.seen
      yield (result, reloaded, events, p)
    }

  test(
    "a VRF key installed just before the leader check is the key checked: the check never names " +
      "a key no longer in force"
  ) {
    val s = ledOnlyBy(54, 154)
    stepped(54, s)(p => { case Forger.Point.Checking(`s`) => withVrf(p, 54, 154) }).map {
      (result, reloaded, events, _) =>
        assertEquals(result, Right(()))
        assertEquals(reloaded, Vector(Right(())))
        // the key installed, which does not lead the slot, was checked: not led, and no block
        assert(events.contains(ForgeEvent.Loop(ForgeTrace.NodeNotLeader(s))), events.toString)
        assert(!events.exists(_.isInstanceOf[ForgeEvent.Forged]), events.toString)
    }
  }

  test(
    "a VRF key replaced after the leader check and put back before the signature costs no slot"
  ) {
    val s = ledOnlyBy(57, 157)
    stepped(57, s)(p => {
      case Forger.Point.Rechecked(`s`) => withVrf(p, 57, 157)
      case Forger.Point.Signing(`s`)   => found(p, 57)
    }).map { (result, reloaded, events, _) =>
      assertEquals(result, Right(()))
      assertEquals(reloaded, Vector(Right(()), Right(())))
      assertEquals(events.collect { case f: ForgeEvent.Forged => f.slot }, Vector(s))
      assert(events.contains(ForgeEvent.Loop(ForgeTrace.NodeIsLeader(s))), events.toString)
    }
  }

  test(
    "a reload's lines are written once its lease has ended: a log that blocks keeps no " +
      "superseded key from being erased"
  ) {
    // forging disabled (both keys of the credentials in force) and a switch (their VRF key, the
    // KES key kept), each with the sink blocked on the reload's last line
    def blocked(
        seed: Int,
        files: Pool => HotKeySource.Read[IO],
        last: ForgeEvent => Boolean
    ): IO[(Boolean, Boolean)] =
      val p = pool(kesStart = 50, seed = seed)
      for
        writing <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        source <- filesOf(files(p))
        hot <- HotKeys.create[IO](
          p.creds,
          Some(source),
          config,
          e => (writing.complete(()) *> release.get).whenA(last(e))
        )
        seen <- Resource
          .make(ok(hot.reload(50, Some(0))).start)(f => release.complete(()) *> f.cancel)
          .use(reloading =>
            IO.race(writing.get, reloading.join)
              .flatMap {
                case Left(_) => IO((erased(p.creds.vrf), p.creds.kes.isForgotten))
                case Right(outcome) =>
                  IO.raiseError(new AssertionError(s"no line blocked: $outcome"))
              }
              .flatTap(_ => release.complete(()) *> reloading.joinWithNever)
          )
      yield seen
    for
      disabled <- blocked(
        58,
        _ => HotKeySource.Read.Absent(Path.of("kes.skey")),
        _ == ForgeEvent.BlockForgingUpdate(BlockForgingState.DisabledBlockForging)
      )
      replaced <- blocked(
        59,
        p => withVrf(p, 59, 159),
        _ == ForgeEvent.BlockForgingUpdate(BlockForgingState.EnabledBlockForging)
      )
    yield
      assertEquals(disabled, (true, true), "the keys of forging disabled, while its line waits")
      assertEquals(replaced, (true, false), "the VRF key replaced, while the line waits")
  }

  test(
    "every reload gives the scheduler a new value, the VRF key the same, so that a walk a reload " +
      "ended is taken up again"
  ) {
    val p = pool(kesStart = 50, seed = 56)
    TestControl.executeEmbed(
      withHandle {
        for
          values <- Ref.of[IO, Int](0)
          again <- Deferred[IO, Unit]
          files <- filesOf(found(p, 56))
          h <- harness(
            p,
            ledgerIn(5, Vector(p -> BigInt(1000000))),
            live = true,
            unbounded = true,
            source = Some(files),
            pause = Some {
              case Forger.Point.Inputs(_) =>
                values.updateAndGet(_ + 1).flatMap(n => again.complete(()).void.whenA(n == 2))
              case _ => IO.unit
            }
          )
          taken <- Resource
            .make(h.forger.run.start)(_.cancel)
            .use(_ =>
              h.settled(5002) *>
                h.forger.reload
                  .flatMap(_.fold(f => IO.raiseError(new AssertionError(f.message)), IO.pure)) *>
                IO.race(again.get, h.settled(5090))
            )
        yield assert(taken.isLeft, "the reload gave the scheduler no new value")
      }
    )
  }
