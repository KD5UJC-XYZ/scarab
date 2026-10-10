// SPDX-License-Identifier: Apache-2.0
package lab

import java.security.{KeyPairGenerator, Signature}
import lab.ledger.*
import lab.submission.*
import lab.header.PraosCertificateState.Point
import cats.effect.{IO, Ref, Deferred}
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import scala.concurrent.duration.*
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.submission.SignedTransaction

class AdaSubmissionServiceSuite extends munit.FunSuite:
  private val R = ClusterTransition
  private def n(v: V) = Node(v, Bytes.empty)
  private def u(i: BigInt) = V.UInt(i)
  private def arr(v: V*) = V.Arr(v.toVector.map(n))
  private def dict(v: (V, V)*) = V.Map(v.toVector.map((k, x) => n(k) -> n(x)))
  private def raw(v: V) = Cbor.encode(v).toOption.get
  private def bs(b: Bytes) = V.ByteString(b)
  private def hex(s: String) = Bytes.fromHex(s).toOption.get
  private def cat(xs: Bytes*) = Bytes(xs.toVector.flatMap(_.value))
  private def array(xs: Vector[Bytes]) = cat(hex("9f"), Bytes(xs.flatMap(_.value)), hex("ff"))
  private val pairs = Vector.fill(3)(KeyPairGenerator.getInstance("Ed25519").generateKeyPair())
  private val publics = pairs.map(k => Bytes.fromArray(k.getPublic.getEncoded.takeRight(32)))
  private val hashes = publics.map(Blake2b.hash224.hash)
  private val digest = Bytes(Vector.fill(32)(1.toByte))
  private val input = arr(bs(Bytes(Vector.fill(32)(2.toByte))), u(0))
  private val otherInput = arr(bs(Bytes(Vector.fill(32)(3.toByte))), u(1))
  private val native = raw(
    arr(u(1), arr(arr(u(0), bs(hashes(0))), arr(u(4), u(20)), arr(u(5), u(30))))
  )
  private def scriptHash = NativeScript.decode(native).toOption.get.hash
  private def keyAddress(k: Int) = Bytes(Vector(0x60.toByte) ++ hashes(k).value)
  private val scriptAddress = Bytes(Vector(0x70.toByte) ++ scriptHash.value)
  private def output(address: Bytes, coin: BigInt) = arr(bs(address), u(coin))
  private def env(
      a: BigInt = 44,
      b: BigInt = 155381,
      max: BigInt = 16384,
      cost: BigInt = 4310,
      magic: Long = 1082026,
      epoch: BigInt = 0
  ) =
    R.environment(digest, digest, magic, epoch, 9, 0, a, b, max, cost).toOption.get
  private def initial(
      environment: R.Environment = env(),
      fees: BigInt = 700000,
      nativeInput: Boolean = false,
      nonminimal: Boolean = false
  ): R.State =
    val before = raw(
      dict(
        input -> output(if nativeInput then scriptAddress else keyAddress(0), 4000000),
        otherInput -> output(keyAddress(1), 9000000)
      )
    )
    val encoded =
      if nonminimal then hex(before.hex.replace("1a003d0900", "1b00000000003d0900")) else before
    R.checkpoint(environment, encoded, fees, 20, digest).toOption.get
  private def body(
      refs: Vector[V] = Vector(input),
      coin: BigInt = 3800000,
      fee: BigInt = 200000,
      lower: BigInt = 20,
      upper: BigInt = 30,
      extra: Vector[(V, V)] = Vector.empty
  ): Bytes =
    raw(
      dict(
        (Vector(
          u(0) -> arr(refs*),
          u(1) -> arr(output(keyAddress(0), coin)),
          u(2) -> u(fee),
          u(8) -> u(lower),
          u(3) -> u(upper)
        ) ++ extra)*
      )
    )
  private def tx(
      b: Bytes = body(),
      scripts: Vector[Bytes] = Vector.empty,
      signers: Vector[Int] = Vector(0)
  ): Bytes =
    val witnesses = signers.map { k =>
      val signer = Signature.getInstance("Ed25519")
      signer.initSign(pairs(k).getPrivate)
      signer.update(Blake2b.hash256.hash(b).toArray)
      raw(arr(bs(publics(k)), bs(Bytes.fromArray(signer.sign()))))
    }
    val fields =
      (if witnesses.nonEmpty then Vector(hex("00"), array(witnesses)) else Vector.empty) ++
        (if scripts.nonEmpty then Vector(hex("01"), array(scripts)) else Vector.empty)
    cat(hex("84"), b, cat(hex("bf"), Bytes(fields.flatMap(_.value)), hex("ff")), hex("f5f6"))

  private def view(
      generation: Int = 0,
      profile: AdmissionProfile = AdmissionProfile.AdaVkey,
      slot: BigInt = 20
  ): AdmissionView =
    val seed = initial(nativeInput = profile == AdmissionProfile.NativeScript)
    val ledger =
      R.checkpoint(seed.environment, seed.outputMap, seed.fees, slot, digest).toOption.get
    val pin = StatePin
      .checked(
        digest,
        generation,
        Point(digest, slot, generation),
        digest,
        ledger.id,
        ledger.environment.id,
        ledger.slot,
        profile.id
      )
      .toOption
      .get
    AdmissionView.checked(pin, ledger).toOption.get

  private final class Owner(
      val cell: Ref[IO, AdmissionView],
      gate: Semaphore[IO],
      beforeCommit: IO[Unit]
  ) extends AdmissionState[IO]:
    def current = gate.permit.use(_ => cell.get)
    def withCurrent[A](expected: StatePin)(commit: IO[A]): IO[Either[StatePin, A]] =
      beforeCommit *> gate.permit.use(_ =>
        IO.uncancelable(_ =>
          cell.get.flatMap(v =>
            if v.pin == expected then commit.map(Right(_)) else IO.pure(Left(v.pin))
          )
        )
      )
    def move(
        service: AdaSubmissionService[IO],
        generation: Int,
        included: Set[Bytes] = Set.empty,
        kind: StateChangeKind = StateChangeKind.Published,
        slot: BigInt = 20
    ): IO[Unit] =
      gate.permit.use(_ =>
        IO.uncancelable(_ =>
          cell.get.flatMap { previous =>
            val next = view(generation, AdmissionProfile.fromId(previous.pin.profileId).get, slot)
            cell.set(next) *> service.changed(
              AdmissionStateChange(
                next,
                kind,
                included.toVector.map(id => IncludedTransaction(id, None, None))
              )
            )
          }
        )
      )
    def close(service: AdaSubmissionService[IO]): IO[Unit] = gate.permit.use(_ => service.closed)
  private def owner(
      before: IO[Unit] = IO.unit,
      profile: AdmissionProfile = AdmissionProfile.AdaVkey
  ): IO[Owner] =
    (Ref.of[IO, AdmissionView](view(profile = profile)), Semaphore[IO](1))
      .mapN(new Owner(_, _, before))
  private def submit(s: AdaSubmissionService[IO], bytes: Bytes): IO[AdaSubmissionService.Result] =
    s.request.use(_.get.submit(bytes))
  private def ready(s: AdaSubmissionService[IO]): IO[Unit] =
    s.snapshot
      .flatMap(x => if x.rebuilding then IO.cede *> ready(s) else IO.unit)
      .timeout(5.seconds)

  test("preparation suspends validation once and retains genuine candidate and view references") {
    Vector(AdmissionProfile.AdaVkey, AdmissionProfile.NativeScript)
      .traverse_ { profile =>
        val v = view(profile = profile)
        val original = tx(scripts =
          if profile == AdmissionProfile.NativeScript then Vector(native) else Vector.empty
        )
        val candidate = AdmissionValidation.prepare(profile, v.pin, v.ledger, original).toOption.get
        val calls = new java.util.concurrent.atomic.AtomicInteger(0)
        val prepared = AdmissionPreparation.evaluate[IO](profile, v) {
          calls.incrementAndGet()
          Right(candidate)
        }
        assertEquals(calls.get(), 0)
        prepared.map { result =>
          val value = result.toOption.get
          assertEquals(calls.get(), 1)
          assert(value.view eq v)
          assert(value.candidate eq candidate)
          assert(value.candidate.transaction.original eq candidate.transaction.original)
        }
      }
      .unsafeToFuture()
  }

  test("validation rejection bypasses binding and preserves the exact domain cause") {
    val error = ScopedAdmission.Failure.Unsupported("test domain cause")
    AdmissionPreparation
      .evaluate[IO](AdmissionProfile.AdaVkey, null)(Left(error))
      .map {
        case Left(AdmissionPreparation.Failure.Rejected(actual)) => assert(actual eq error)
        case other                                               => fail(other.toString)
      }
      .unsafeToFuture()
  }

  test("all profile identity rejections agree with the existing pure validator") {
    val original = Bytes(Vector(0x80.toByte))
    AdmissionProfile.values.toVector
      .traverse_ { profile =>
        val v = view(profile = profile)
        val old = AdmissionValidation.prepare(profile, v.pin, v.ledger, original)
        assert(old.isLeft)
        AdmissionPreparation
          .evaluate[IO](profile, v) {
            AdmissionValidation.prepare(profile, v.pin, v.ledger, original)
          }
          .map(result =>
            assertEquals(
              result.left.toOption,
              old.left.toOption.map(AdmissionPreparation.Failure.Rejected.apply)
            )
          )
      }
      .unsafeToFuture()
  }

  test("checked preparation preserves legacy binding decisions over full pin and view drift") {
    val v = view()
    val original = tx()
    val p = v.pin
    val changed = Bytes(Vector.fill(32)(9.toByte))
    def pin(
        owner: Bytes = p.ownerId,
        generation: BigInt = p.generation,
        point: Point = p.point,
        coherent: Bytes = p.coherentStateId,
        ledger: Bytes = p.ledgerStateId,
        environment: Bytes = p.environmentId,
        slot: BigInt = p.validationSlot,
        profile: String = p.profileId
    ): StatePin =
      StatePin
        .checked(owner, generation, point, coherent, ledger, environment, slot, profile)
        .toOption
        .get
    val variants = Vector(
      p,
      pin(owner = changed),
      pin(generation = p.generation + 1),
      pin(point = Point(changed, p.point.slot, p.point.blockNo)),
      pin(point = Point(p.point.hash, p.point.slot + 1, p.point.blockNo)),
      pin(point = Point(p.point.hash, p.point.slot, p.point.blockNo + 1)),
      pin(coherent = changed),
      pin(ledger = changed),
      pin(environment = changed),
      pin(slot = p.validationSlot + 1),
      pin(profile = AdmissionProfile.NativeScript.id)
    )
    def candidate(pin: StatePin, source: R.State = v.ledger) =
      AdmissionValidation.prepare(AdmissionProfile.AdaVkey, pin, source, original).toOption.get
    val base = candidate(p)
    val wrongProfile =
      AdmissionView.checked(pin(profile = AdmissionProfile.NativeScript.id), v.ledger).toOption.get
    val changedStates = Vector(
      initial(fees = 700001),
      initial(environment = env(a = 45)),
      R.checkpoint(v.ledger.environment, v.ledger.outputMap, v.ledger.fees, 21, digest).toOption.get
    )
    val drift = changedStates.map { ledger =>
      val q = pin(ledger = ledger.id, environment = ledger.environment.id, slot = ledger.slot)
      (AdmissionProfile.AdaVkey, AdmissionView.checked(q, ledger).toOption.get, candidate(q))
    }
    val rows = variants.map(q => (AdmissionProfile.AdaVkey, v, candidate(q))) ++ drift ++ Vector(
      (AdmissionProfile.NativeScript, v, base),
      (AdmissionProfile.AdaVkey, wrongProfile, base)
    )
    rows.foreach { (profile, current, checked) =>
      val legacy = checked.profile == profile && current.pin.profileId == profile.id &&
        checked.ledgerStateId == current.pin.ledgerStateId && checked.environmentId == current.pin.environmentId &&
        checked.validationSlot == current.pin.validationSlot && checked.pin == current.pin
      val modern = AdmissionPreparation.checked(profile, current, checked)
      assertEquals(modern.isRight, legacy)
      if !legacy then
        assertEquals(modern.left.toOption, Some(AdmissionPreparation.Failure.BindingMismatch))
    }
    assertEquals(
      rows.count { (profile, current, checked) =>
        AdmissionPreparation.checked(profile, current, checked).isRight
      },
      1
    )
  }

  test("unexpected preparation exceptions remain the original effect failure") {
    val sentinel = new IllegalStateException("preparation sentinel")
    AdmissionPreparation
      .evaluate[IO](AdmissionProfile.AdaVkey, view())(throw sentinel)
      .attempt
      .map { result =>
        assert(result.swap.toOption.exists(_ eq sentinel))
      }
      .unsafeToFuture()
  }

  test("cancellation before suspended preparation does not evaluate or become domain rejection") {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    val prepared = AdmissionPreparation.evaluate[IO](AdmissionProfile.AdaVkey, view()) {
      calls.incrementAndGet()
      Left(ScopedAdmission.Failure.Unsupported("not evaluated"))
    }
    (for
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      fiber <- (entered.complete(()).void *> release.get *> prepared).start
      _ <- entered.get
      _ <- fiber.cancel
      outcome <- fiber.join
      _ = assert(outcome.isCanceled)
      _ = assertEquals(calls.get(), 0)
    yield ()).unsafeToFuture()
  }

  test("preparation requires only Sync but checked data cannot forge owner authority") {
    import scala.compiletime.testing.{typeCheckErrors, typeChecks}
    assert(
      typeChecks(
        """import lab.*; import lab.submission.*; import lab.ledger.ScopedAdmission; def prepare[F[_]: cats.effect.Sync](v: AdmissionView, r: Either[ScopedAdmission.Failure, ScopedAdmission.Candidate[StatePin]]) = AdmissionPreparation.evaluate[F](AdmissionProfile.AdaVkey,v)(r)"""
      )
    )
    assert(
      typeCheckErrors(
        """import lab.*; import lab.submission.*; import lab.ledger.ScopedAdmission; def prepare[F[_]: cats.Applicative](v: AdmissionView, r: Either[ScopedAdmission.Failure, ScopedAdmission.Candidate[StatePin]]) = AdmissionPreparation.evaluate[F](AdmissionProfile.AdaVkey,v)(r)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import lab.*; import lab.submission.*; import lab.ledger.ScopedAdmission; def forge(v: AdmissionView,c: ScopedAdmission.Candidate[StatePin]) = new AdmissionPreparation.Prepared(v,c)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import lab.*; def fence[F[_]](p: AdmissionPreparation.Prepared): AdmissionPrograms.Fence[F] = p"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors("""val error: lab.AdmissionPreparation.Failure = "unavailable"""").nonEmpty
    )
  }

  test("concurrent conflicting spends reserve one input; duplicate survives request release") {
    (for
      o <- owner()
      _ <- AdaSubmissionService.resource[IO](o).use { s =>
        (submit(s, tx()), submit(s, tx(body(coin = 3700000, fee = 300000)))).parTupled.flatMap {
          (a, b) =>
            val results = Vector(a, b)
            IO(
              assertEquals(results.count(_.isInstanceOf[AdaSubmissionService.Result.Accepted]), 1)
            ) *>
              IO(
                assertEquals(
                  results.count(_.isInstanceOf[AdaSubmissionService.Result.PoolRejected]),
                  1
                )
              ) *>
              s.snapshot
                .flatMap(snapshot => submit(s, snapshot.eligible.head.original))
                .flatMap(result =>
                  IO(assert(result.isInstanceOf[AdaSubmissionService.Result.AlreadyPresent]))
                )
        }
      }
    yield ()).unsafeToFuture()
  }
  test("request permits bound allocation and cannot validate twice or after release") {
    (for
      o <- owner()
      _ <- AdaSubmissionService.resource[IO](o).use { s =>
        List.fill(8)(s.request).sequence.use { requests =>
          s.request.use(extra => IO(assertEquals(extra, None))) *>
            requests.head.get.submit(tx()) *>
            requests.head.get.submit(tx()).attempt.flatMap(r => IO(assert(r.isLeft)))
        } *> s.request.allocated.flatMap { (held, release) =>
          release *> held.get.submit(tx()).attempt.flatMap(r => IO(assert(r.isLeft)))
        }
      }
    yield ()).unsafeToFuture()
  }
  test("state movement during admission fences old validation without resurrection") {
    (for
      calls <- Ref.of[IO, Int](0)
      entered <- Deferred[IO, Unit]
      resume <- Deferred[IO, Unit]
      o <- owner(
        calls
          .getAndUpdate(_ + 1)
          .flatMap(n => if n == 1 then entered.complete(()).void *> resume.get else IO.unit)
      )
      _ <- AdaSubmissionService.resource[IO](o).use { s =>
        for
          fiber <- submit(s, tx()).start
          _ <- entered.get
          _ <- o.move(s, 1)
          _ <- resume.complete(())
          result <- fiber.joinWithNever
          _ <- IO(assert(result.isInstanceOf[AdaSubmissionService.Result.Retry]))
          _ <- ready(s)
          snapshot <- s.snapshot
          _ <- IO(assertEquals(snapshot.size, 0))
        yield ()
      }
    yield ()).unsafeToFuture()
  }
  test("inclusion is follower-only and rollback removes orphan history without restoring bytes") {
    (for
      o <- owner()
      _ <- AdaSubmissionService.resource[IO](o).use { s =>
        val id = SignedTransaction.checked(tx()).toOption.get.transactionId
        for
          _ <- submit(s, tx())
          _ <- o.move(s, 2, Set(id))
          _ <- ready(s)
          included <- s.status(id)
          _ <- IO(assert(included.get.isInstanceOf[AdaPool.Status.Included[?]]))
          _ <- o.move(s, 1, kind = StateChangeKind.RolledBack)
          _ <- ready(s)
          absent <- s.status(id)
          snapshot <- s.snapshot
          _ <- IO(assertEquals(absent, None))
          _ <- IO(assertEquals(snapshot.size, 0))
        yield ()
      }
    yield ()).unsafeToFuture()
  }
  test("closed pool rejects admission and removes relay eligibility") {
    (for
      o <- owner()
      _ <- AdaSubmissionService.resource[IO](o).use { s =>
        submit(s, tx()) *> o.close(s) *> submit(s, tx())
          .flatMap(r => IO(assertEquals(r, AdaSubmissionService.Result.Unavailable))) *>
          s.snapshot.flatMap(x => IO(assert(x.closed && x.eligible.isEmpty && x.size == 0)))
      }
    yield ()).unsafeToFuture()
  }

  test("cancellation before commit leaves no entry; after commit preserves acceptance") {
    (for
      calls <- Ref.of[IO, Int](0)
      entered <- Deferred[IO, Unit]
      never <- Deferred[IO, Unit]
      o <- owner(
        calls
          .getAndUpdate(_ + 1)
          .flatMap(n => if n == 1 then entered.complete(()).void *> never.get else IO.unit)
      )
      _ <- AdaSubmissionService.resource[IO](o).use { s =>
        for
          fiber <- submit(s, tx()).start
          _ <- entered.get
          _ <- fiber.cancel
          empty <- s.snapshot
          _ <- IO(assertEquals(empty.size, 0))
          accepted <- Deferred[IO, Unit]
          after <- (submit(s, tx()) *> accepted.complete(()) *> IO.never[Unit]).start
          _ <- accepted.get
          _ <- after.cancel
          retained <- s.snapshot
          _ <- IO(assertEquals(retained.size, 1))
        yield ()
      }
    yield ()).unsafeToFuture()
  }

  test("concurrent rebuild completion and rapid movement drain the latest pending generation") {
    (for
      o <- owner()
      _ <- AdaSubmissionService.resource[IO](o).use { s =>
        (1 to 100).toList.traverse_ { generation =>
          o.move(s, generation) *> IO.cede
        } *> ready(s) *> s.snapshot.flatMap { snapshot =>
          IO(assertEquals(snapshot.pin.generation, BigInt(100))) *>
            IO(assert(!snapshot.rebuilding))
        }
      }
    yield ()).unsafeToFuture()
  }

  test("native admission and generation rebuild use the same opt-in profile") {
    val original = tx(scripts = Vector(native))
    val id = SignedTransaction.checked(original).toOption.get.transactionId
    (for
      o <- owner(profile = AdmissionProfile.NativeScript)
      _ <- AdaSubmissionService.resource[IO](o).use { s =>
        for
          accepted <- submit(s, original)
          _ = accepted match
            case AdaSubmissionService.Result.Accepted(receipt) =>
              assertEquals(receipt.profileId, AdmissionProfile.NativeScript.id)
              assertEquals(receipt.pin.profileId, receipt.profileId)
            case other => fail(other.toString)
          _ <- o.move(s, 1)
          _ <- ready(s)
          snapshot <- s.snapshot
          _ = assertEquals(snapshot.pin.generation, BigInt(1))
          _ = assertEquals(snapshot.eligible.map(_.original), Vector(original))
          duplicate <- submit(s, original)
          _ = assert(duplicate.isInstanceOf[AdaSubmissionService.Result.AlreadyPresent])
          _ <- o.move(s, 2, slot = 30)
          _ <- ready(s)
          expired <- s.snapshot
          status <- s.status(id)
          _ = assertEquals(expired.size, 0)
          _ = status match
            case Some(AdaPool.Status.Dropped(AdaPool.Drop.Validation(_))) => ()
            case other                                                    => fail(other.toString)
        yield ()
      }
      ada <- owner()
      _ <- AdaSubmissionService.resource[IO](ada).use { s =>
        submit(s, original).map { result =>
          assertEquals(s.profile, AdmissionProfile.AdaVkey)
          assert(result.isInstanceOf[AdaSubmissionService.Result.Rejected])
        }
      }
    yield ()).timeout(10.seconds).unsafeToFuture()
  }
