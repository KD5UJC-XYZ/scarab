// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import scala.concurrent.duration.*
import lab.cbor.Bytes
import lab.submission.{AdmissionProfile, SignedTransaction, StatePin}
import SequentialDevnetRunner.*

/** Test-only client boundary. Endpoint descriptions are supervisor assertions, not proof of Docker
  * ownership or independent nodes. Resource implementations must own/cancel their requests and may
  * not leave detached work behind. No existing live runner advertises this capability.
  */
object PlutusMultiEndpointScenarioAdapter:
  final case class Endpoint(id: String, loopbackPort: Int, ownerId: Bytes)
  final case class Snapshot(pin: StatePin, closed: Boolean)
  final case class Accepted(transactionId: Bytes, envelopeSHA256: Bytes, pin: StatePin)
  final case class Publication(
      pin: StatePin,
      transactionId: Bytes,
      bodySHA256: Bytes,
      witnessesSHA256: Bytes
  )
  final case class Included(transactionId: Bytes, pin: StatePin, publication: Publication)

  /** Implementations decode only successful scoped HTTP responses/publications, reject unsupported
    * profiles and bound input bytes. awaitIncluded must bind the actual publication to this
    * endpoint. The boundary still independently checks all transaction identities and owner pins
    * below.
    */
  trait Client:
    def endpoint: Endpoint
    def state: IO[Snapshot]
    def submit(original: Bytes): IO[Accepted]
    def awaitIncluded(transactionId: Bytes): IO[Included]

  private val profile = AdmissionProfile.PlutusV3.id
  private def hash(bytes: Bytes): Bytes = ClusterHeaderObservation.sha256(bytes)
  private def validHash(bytes: Bytes): Boolean =
    bytes != null && bytes.value != null && bytes.size == 32
  private def requireIO(ok: Boolean, reason: String): IO[Unit] =
    IO.raiseUnless(ok)(new IllegalArgumentException(reason))
  private def scoped(pin: StatePin, endpoint: Endpoint): Boolean =
    pin != null && pin.ownerId == endpoint.ownerId && pin.profileId == profile &&
      pin.validationSlot == pin.point.slot
  private def follows(later: StatePin, earlier: StatePin): Boolean =
    later.generation >= earlier.generation && later.point.slot >= earlier.point.slot &&
      later.point.blockNo >= earlier.point.blockNo &&
      ((later.point.slot != earlier.point.slot && later.point.blockNo != earlier.point.blockNo) || later.point == earlier.point) &&
      (later.generation != earlier.generation || later == earlier)

  /** One attempt per lease, including after a failed/cancelled submission. No retry authority. The
    * enclosing runner also bounds resource acquisition and the complete scenario lifetime.
    */
  def owned(
      endpoints: Vector[Endpoint],
      originals: Vector[Bytes],
      actionLimit: FiniteDuration,
      connect: Endpoint => Resource[IO, Client]
  ): Resource[IO, Adapter] =
    val checked = for
      _ <- requireIO(
        endpoints.size == 2 && originals.size == 2,
        "exactly two endpoints/transactions required"
      )
      _ <- requireIO(
        actionLimit > Duration.Zero && actionLimit <= 60.seconds,
        "bounded action required"
      )
      _ <- requireIO(
        endpoints.forall(e =>
          e != null && e.id != null && e.id.matches(
            "[a-z0-9-]{1,48}"
          ) && e.loopbackPort > 0 && e.loopbackPort <= 65535 && validHash(e.ownerId)
        ),
        "invalid endpoint"
      )
      _ <- requireIO(
        endpoints.map(_.id).distinct.size == 2 && endpoints
          .map(_.loopbackPort)
          .distinct
          .size == 2 && endpoints.map(_.ownerId).distinct.size == 2,
        "distinct endpoint IDs, ports and owners required"
      )
      txs <- originals.traverse(raw =>
        IO.fromEither(
          SignedTransaction.checked(raw).left.map(e => new IllegalArgumentException(e.toString))
        )
      )
      _ <- requireIO(
        txs.forall(_.isValid) && txs.map(_.transactionId).distinct.size == 2,
        "distinct valid-flag transactions required"
      )
    yield txs
    for
      txs <- Resource.eval(checked)
      clients <- endpoints.traverse(e => connect(e))
      initial <- Resource.eval(clients.zip(endpoints).traverse { case (client, endpoint) =>
        for
          _ <- requireIO(client.endpoint == endpoint, "resource endpoint identity mismatch")
          snapshot <- client.state.timeout(actionLimit)
          _ <- requireIO(
            !snapshot.closed && scoped(snapshot.pin, endpoint),
            "unsupported endpoint state"
          )
        yield snapshot.pin
      })
      attempted <- Resource.eval(Ref.of[IO, Boolean](false))
      evidence <- Resource.eval(Ref.of[IO, Option[Bytes]](None))
    yield new Adapter:
      val capabilities = Set(Requirement.MultipleIngressNodes)
      def execute(scenario: Scenario): IO[Either[Failure, Unit]] =
        if scenario != Scenario.MultipleNodes then IO.pure(Left(Failure.ActionRejected))
        else
          attempted.getAndSet(true).flatMap { already =>
            if already then IO.pure(Left(Failure.ActionRejected))
            else
              clients
                .zip(endpoints)
                .zip(txs.zip(initial))
                .traverse { case ((client, endpoint), (tx, start)) =>
                  for
                    current <- client.state
                    _ <- requireIO(
                      !current.closed && scoped(current.pin, endpoint) && follows(
                        current.pin,
                        start
                      ),
                      "endpoint changed before submit"
                    )
                    accepted <- client.submit(tx.original)
                    _ <- requireIO(
                      accepted.transactionId == tx.transactionId && accepted.envelopeSHA256 == tx.envelopeSHA256 && scoped(
                        accepted.pin,
                        endpoint
                      ) && follows(accepted.pin, current.pin),
                      "accepted original/owner mismatch"
                    )
                    included <- client.awaitIncluded(tx.transactionId)
                    p = included.publication
                    _ <- requireIO(
                      included.transactionId == tx.transactionId && scoped(
                        included.pin,
                        endpoint
                      ) && follows(
                        included.pin,
                        accepted.pin
                      ) && included.pin.generation > accepted.pin.generation && included.pin.point.slot > accepted.pin.point.slot && included.pin.point.blockNo > accepted.pin.point.blockNo && p.pin == included.pin && p.transactionId == tx.transactionId && p.bodySHA256 == hash(
                        tx.originalBody
                      ) && p.witnessesSHA256 == hash(tx.originalWitnesses),
                      "original inclusion publication mismatch"
                    )
                    after <- client.state
                    _ <- requireIO(
                      !after.closed && scoped(after.pin, endpoint) && follows(
                        after.pin,
                        included.pin
                      ),
                      "endpoint changed after inclusion"
                    )
                  yield s"${endpoint.id}:${endpoint.loopbackPort}:${endpoint.ownerId.hex}:${tx.transactionId.hex}:${tx.envelopeSHA256.hex}:${p.bodySHA256.hex}:${p.witnessesSHA256.hex}:${included.pin.generation}:${included.pin.point.slot}:${included.pin.point.blockNo}:${included.pin.point.hash.hex}:${included.pin.coherentStateId.hex}:${included.pin.ledgerStateId.hex}:${included.pin.environmentId.hex}"
                }
                .flatMap(rows =>
                  evidence.set(
                    Some(
                      Bytes.fromArray(
                        ("multi-ingress-observation-v1\nsubmittedEnvelopeByteEqualityVerified=false\nrestoreAuthority=false\nfullLedgerValidated=false\n" + rows
                          .mkString("\n")).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                      )
                    )
                  )
                )
                .as(Right(()): Either[Failure, Unit])
                .handleError(_ => Left(Failure.AdapterError))
                .timeoutTo(actionLimit, IO.pure(Left(Failure.ActionDeadline)))
          }
      def observe(scenario: Scenario): IO[Bytes] =
        if scenario != Scenario.MultipleNodes then
          IO.raiseError(new IllegalArgumentException("unsupported observation"))
        else
          evidence.get.flatMap(value =>
            IO.fromOption(value)(new IllegalStateException("both inclusions required"))
          )
