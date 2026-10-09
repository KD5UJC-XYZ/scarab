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
import lab.network.{RelayLimits, TxSubmission2, ChainSync}

class AdaAdmissionRelaySuite extends munit.FunSuite:
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
    cat(hex("9f"), b, cat(hex("bf"), Bytes(fields.flatMap(_.value)), hex("ff")), hex("f5f6ff"))

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

  test(
    "valid indefinite ADA envelope stays exact through admission, managed lease and TxSubmission2"
  ) {
    val original = tx()
    val identity = SignedTransaction.checked(original).toOption.get
    val variant = tx(signers = Vector(0, 1))
    val variantIdentity = SignedTransaction.checked(variant).toOption.get
    assertEquals(original.value.head & 255, 0x9f)
    assertEquals(original.value.last & 255, 0xff)
    assertEquals(identity.originalBody, body())
    assertEquals(identity.originalWitnesses.value.head & 255, 0xbf)
    assertEquals(identity.transactionId, Blake2b.hash256.hash(body()))
    assertEquals(variantIdentity.transactionId, identity.transactionId)
    assertNotEquals(variantIdentity.originalWitnesses, identity.originalWitnesses)
    assert(AdaAdmission.prepare(view().pin, view().ledger, original).isRight)
    assert(AdaAdmission.prepare(view().pin, view().ledger, variant).isRight)
    val flattened = Cbor.encode(Cbor.decode(original).toOption.get.value).toOption.get
    assertNotEquals(flattened, original)

    (for
      o <- owner()
      before <- o.current
      _ <- AdaSubmissionService.resource[IO](o).use { service =>
        for
          admitted <- submit(service, original)
          _ <- IO {
            admitted match
              case AdaSubmissionService.Result.Accepted(receipt) =>
                assertEquals(receipt.transactionId, identity.transactionId)
                assertEquals(receipt.envelopeSHA256, identity.envelopeSHA256)
                assertEquals(receipt.pin, before.pin)
                assert(!receipt.fullLedgerValidated)
              case other => fail(other.toString)
          }
          _ <- service.relaySource.acquireBatch(RelayLimits()).use { lease =>
            for
              _ <- IO(assertEquals(lease.offers.size, 1))
              offered = lease.offers.head
              _ <- IO(assertEquals(offered.transactionId, identity.transactionId))
              _ <- IO(
                assertEquals(
                  offered.advertisedSize,
                  TxSubmission2.advertisedSize(original.size).toOption.get
                )
              )
              leased <- lease.original(offered.transactionId)
              _ <- IO(assertEquals(leased, Some(original)))
              _ <- IO(assertEquals(TxSubmission2.bodyId(leased.get), Right(identity.transactionId)))
              leasedIdentity = SignedTransaction.checked(leased.get).toOption.get
              _ <- IO(assertEquals(leasedIdentity.originalBody, identity.originalBody))
              _ <- IO(assertEquals(leasedIdentity.originalWitnesses, identity.originalWitnesses))
              wire = TxSubmission2
                .encode(
                  TxSubmission2.State.Txs,
                  ChainSync.Role.Client,
                  TxSubmission2.Message.ReplyTxs(Vector(leased.get))
                )
                .toOption
                .get
              encodedGenTxSize = Cbor.decode(wire).toOption.get.value match
                case V.Arr(Vector(_, list)) =>
                  list.value match
                    case V.Arr(Vector(genTx)) => genTx.original.size.toLong
                    case other                => fail(other.toString)
                case other => fail(other.toString)
              _ <- IO(assertEquals(offered.advertisedSize, encodedGenTxSize))
              decoded = TxSubmission2.decode(TxSubmission2.State.Txs, ChainSync.Role.Client, wire)
              _ <- IO(
                assertEquals(decoded, Right(TxSubmission2.Message.ReplyTxs(Vector(original))))
              )
              conflict <- submit(service, variant)
              _ <- IO(
                assertEquals(
                  conflict,
                  AdaSubmissionService.Result.PoolRejected(AdaPool.Rejection.EnvelopeConflict)
                )
              )
              retained <- lease.original(offered.transactionId)
              _ <- IO(assertEquals(retained, Some(original)))
              _ <- service.relaySource.acquireBatch(RelayLimits()).use { fresh =>
                fresh
                  .original(offered.transactionId)
                  .flatMap(bytes => IO(assertEquals(bytes, Some(original))))
              }
              competing <- submit(service, tx(body(coin = 3700000, fee = 300000)))
              _ <- IO {
                competing match
                  case AdaSubmissionService.Result.PoolRejected(
                        AdaPool.Rejection.InputsReserved(inputs)
                      ) =>
                    assertEquals(inputs.size, 1)
                  case other => fail(other.toString)
              }
            yield ()
          }
          snapshot <- service.snapshot
          after <- o.current
          _ <- IO(assertEquals(snapshot.size, 1))
          _ <- IO(assertEquals(snapshot.eligible.map(_.original), Vector(original)))
          _ <- IO(assertEquals(after.pin, before.pin))
          _ <- IO(assertEquals(after.ledger.id, before.ledger.id))
          _ <- IO(assertEquals(after.ledger.outputMap, before.ledger.outputMap))
          _ <- IO(assertEquals(after.ledger.fees, before.ledger.fees))
          _ <- IO(assertEquals(after.ledger.revision, before.ledger.revision))
        yield ()
      }
    yield ()).unsafeToFuture()
  }
