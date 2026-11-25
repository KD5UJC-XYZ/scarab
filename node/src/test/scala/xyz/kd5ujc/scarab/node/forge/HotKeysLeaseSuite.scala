// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.IORuntimeConfig
import cats.mtl.Raise
import cats.syntax.all.*
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.crypto.vrf.{Draft03Prover, PraosVrfCertificate}
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
      made: Ref[IO, Vector[HotKeySource.Read[IO]]]
  ) extends HotKeySource[IO]:
    def read = HotKeySource.reading(
      files.get.map(afresh).flatTap(found => made.update(_ :+ found)).map(Right(_))
    )
    def persist(key: Sum6KesSigning.SigningKey) = IO.pure(Persisted.Unchanged)
    def hold(next: HotKeySource.Read[IO]): IO[Unit] = files.set(next)

    /** The VRF and KES keys each read made, in order. */
    def keys: IO[Vector[(Draft03Prover.SigningKey, Sum6KesSigning.SigningKey)]] =
      made.get.map(_.collect { case HotKeySource.Read.Found(hot, vrf, _, _) => vrf -> hot.kes })

  private def filesOf(first: HotKeySource.Read[IO]): IO[Files] =
    (
      Ref.of[IO, HotKeySource.Read[IO]](first),
      Ref.of[IO, Vector[HotKeySource.Read[IO]]](Vector.empty)
    )
      .mapN(Files(_, _))

  /** What the files of `p` hold with the VRF key of `seed` (`vrfOf`) in place of the pool's. */
  private def withVrf(p: Pool, kesSeed: Int, seed: Int): HotKeySource.Read[IO] =
    HotKeySource.Read.Found(
      HotCredentials(kesOf(kesSeed), p.creds.opCert, p.creds.coldVkey),
      vrfOf(seed),
      IO.unit
    )

  /** `f` run while an evaluation of the key `vrf` names, under its lease, waits in its computation;
    * then the evaluation's proof of `alpha`, once it is let go, and what `f` gave. The evaluation
    * is let go however `f` ends.
    */
  private def whileEvaluating[A](hot: HotKeys[IO], vrf: Bytes)(f: IO[A]) =
    val entered = new CountDownLatch(1)
    val go = new CountDownLatch(1)
    hot.keys
      .evaluating(vrf) { key =>
        entered.countDown()
        go.await()
        key.prove(alpha)
      }
      .start
      .flatMap { proving =>
        (IO.blocking(entered.await()) *> f)
          .guarantee(IO(go.countDown()))
          .flatMap(a => proving.joinWithNever.tupleRight(a))
      }

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
    (1 to 24).toList.traverse_ { seed =>
      val program =
        for
          // each run its own keys: the forger erases those it supersedes
          p <- IO(pool(kesStart = 50, seed = 49))
          files <- filesOf(found(p, 49))
          hot <- HotKeys.create[IO](p.creds, Some(files), config, _ => IO.unit)
          evaluations = (1 to 18).toList.parTraverse(i =>
            val evaluation = hot.inForce.get.flatMap(
              _.vrf.flatTraverse(vrf =>
                hot.keys.evaluating(vrf)(_.output(alpha)).map(_.tupleLeft(vrf))
              )
            )
            // every third cancelled wherever it is: its lease, if it took one, ends with it
            if i % 3 == 0 then evaluation.start.flatMap(f => f.cancel *> f.join).as(None)
            else evaluation
          )
          // the files alternate between the pool's VRF key and another, the KES key the same
          reloads = (1 to 4).toList.traverse(i =>
            files.hold(if i % 2 == 0 then found(p, 49) else withVrf(p, 49, 149)) *>
              Forger.scoped(hot.reload(50, Some(0)))
          )
          ran <- (evaluations, reloads).parTupled
          (results, reloaded) = ran
          read <- files.keys
        yield
          assertEquals(reloaded, List.fill(4)(Right(())), s"seed $seed")
          assert(results.flatten.nonEmpty, s"seed $seed: no evaluation ran")
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
      val seeded = java.util.Base64.getEncoder.encodeToString(seed.toString.getBytes)
      TestControl.executeEmbed(program, everyStep, Some(seeded))
    }
  }
