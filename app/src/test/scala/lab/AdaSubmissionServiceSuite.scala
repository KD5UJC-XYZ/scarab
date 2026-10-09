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

  private def view(generation: Int = 0): AdmissionView =
    val ledger = initial()
    val pin = StatePin
      .checked(
        digest,
        generation,
        Point(digest, 20, generation),
        digest,
        ledger.id,
        ledger.environment.id,
        ledger.slot,
        StatePin.Profile
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
        kind: StateChangeKind = StateChangeKind.Published
    ): IO[Unit] =
      gate.permit.use(_ =>
        IO.uncancelable(_ =>
          cell.set(view(generation)) *> service.changed(
            AdmissionStateChange(
              view(generation),
              kind,
              included.toVector.map(id => IncludedTransaction(id, None, None))
            )
          )
        )
      )
    def close(service: AdaSubmissionService[IO]): IO[Unit] = gate.permit.use(_ => service.closed)
  private def owner(before: IO[Unit] = IO.unit): IO[Owner] =
    (Ref.of[IO, AdmissionView](view()), Semaphore[IO](1)).mapN(new Owner(_, _, before))
  private def submit(s: AdaSubmissionService[IO], bytes: Bytes): IO[AdaSubmissionService.Result] =
    s.request.use(_.get.submit(bytes))
  private def ready(s: AdaSubmissionService[IO]): IO[Unit] =
    s.snapshot
      .flatMap(x => if x.rebuilding then IO.cede *> ready(s) else IO.unit)
      .timeout(5.seconds)

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
