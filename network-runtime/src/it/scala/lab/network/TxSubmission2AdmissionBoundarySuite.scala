// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.submission.SignedTransaction

/** Optional cross-commit identity-boundary regression. See docs/txsubmission2.md for the command.
  * This structurally checked fixture does not claim ADA ledger admission or reference acceptance.
  */
class TxSubmission2AdmissionBoundarySuite extends munit.FunSuite:
  private def hex(value: String) = Bytes.fromHex(value).toOption.get
  private def frame(payload: String, remote: Boolean = true): Bytes =
    val size = payload.length / 2
    hex("00000000" + (if remote then "8004" else "0004") + f"$size%04x" + payload)

  test(
    "shared SignedTransaction indefinite original traverses the complete relay session unchanged"
  ) {
    val raw = hex("9fbf001800ffa0f5f6ff")
    val signed = SignedTransaction.checked(raw).fold(e => fail(e.toString), identity)
    val expectedId = "285a6693407997d9c23fc1e7b826ec3df317010c8a39e734d7d0001446fa78f3"
    assertEquals(signed.original, raw)
    assertEquals(signed.originalBody, hex("bf001800ff"))
    assertEquals(signed.transactionId, hex(expectedId))
    assertEquals(TxSubmission2.bodyId(signed.original), Right(signed.transactionId))
    val size = TxSubmission2.advertisedSize(signed.byteSize).toOption.get
    assertEquals(size, 15L)
    import ScriptedByteTransport.Step.*
    val script = Vector(
      Expect(hex("00000000000000098200a10e8402f500f4")),
      Receive(hex("000000008000000883010e8402f500f4")),
      Expect(frame("8106", false)),
      Receive(frame("8400f5000a")),
      Expect(frame("82019f8282065820" + expectedId + "0fff", false)),
      Receive(frame("82029f82065820" + expectedId + "ff")),
      Expect(frame("82039f8206d8184a9fbf001800ffa0f5f6ffff", false)),
      Receive(frame("8400f5010a")),
      Expect(frame("8104", false))
    )
    TestControl
      .executeEmbed(for
        releases <- Ref.of[IO, Int](0)
        source = new RelaySource[IO]:
          def acquireBatch(limits: RelayLimits): Resource[IO, RelayLease[IO]] =
            Resource.make(IO.pure(new RelayLease[IO]:
              def offers = Vector(RelayOffer(signed.transactionId, size))
              def original(id: Bytes) = IO.pure(
                Option.when(id == signed.transactionId)(signed.original)
              )))(_ => releases.update(_ + 1))
        result <- ScriptedByteTransport
          .resource[IO](script)
          .use(t => TxSubmission2Session.resource[IO](t).use(_.run(Handshake.Data(2), source)))
        released <- releases.get
        _ = assertEquals(released, 1)
        _ = assert(
          result.events
            .contains(TxSubmission2Session.Event.BodiesWritten(Vector(signed.transactionId)))
        )
      yield ())
      .unsafeToFuture()
  }
