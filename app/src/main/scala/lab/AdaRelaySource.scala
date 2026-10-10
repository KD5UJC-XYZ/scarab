// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Fiber, Ref, Resource}
import cats.effect.std.Supervisor
import cats.effect.syntax.all.*
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.network.{RelayLease, RelayLimits, RelayOffer, RelaySource}
import lab.submission.SignedTransaction
import scala.concurrent.duration.*

/** One service-owned source. The single registry is both the non-queuing two-slot limit and the
  * sole owner of leased originals. Lease objects and expiry fibers retain only opaque tokens and
  * offer metadata, so clearing a registry entry releases its originals even if its caller forgets
  * the Resource finalizer. An in-flight original read linearizes at the registry operation.
  */
private[lab] final class AdaRelaySource[F[_]] private (
    selection: AdaRelaySource.Selection[F],
    registry: Ref[F, AdaRelaySource.Registry],
    timers: Supervisor[F]
)(using F: Async[F])
    extends RelaySource[F]:
  import AdaRelaySource.*

  private def release(token: Token): F[Unit] =
    registry.update(s => s.copy(entries = s.entries - token))

  private def reserve(token: Token, limits: RelayLimits): F[FiniteDuration] =
    F.monotonic.flatMap { now =>
      registry
        .modify[Either[Reason, FiniteDuration]] { old =>
          val current = old.expire(now)
          if current.closed then (current, Left(Reason.Closed))
          else if current.entries.size >= 2 then (current, Left(Reason.Capacity))
          else
            val deadline = now + limits.maxLifetime
            (
              current.copy(entries = current.entries.updated(token, Entry(deadline, Map.empty))),
              Right(deadline)
            )
        }
        .flatMap(result => F.fromEither(result.leftMap(new Unavailable(_))))
    }

  private def install(token: Token, limits: RelayLimits): F[Vector[RelayOffer]] =
    // The callback reads eligibility and installs exact originals while the service holds the
    // same owner gate. No snapshot-to-commit gap can accept an entry removed at the same pin.
    // Expected close/expiry failures are values until AFTER leaving that gate: they must not
    // poison the owner's trusted callback boundary.
    selection
      .withEligible[Either[InstallFailure, Vector[RelayOffer]]] { eligible =>
        RelayBatch.select(eligible, limits) match
          case Left(error) => F.pure(Left(InstallFailure.Selection(error)))
          case Right(batch) =>
            F.monotonic.flatMap { now =>
              registry.modify[Either[InstallFailure, Vector[RelayOffer]]] { old =>
                val current = old.expire(now)
                current.entries.get(token) match
                  case _ if current.closed =>
                    (current, Left(InstallFailure.Unavailable(Reason.Closed)))
                  case None => (current, Left(InstallFailure.Unavailable(Reason.Expired)))
                  case Some(entry) =>
                    (
                      current.copy(entries =
                        current.entries.updated(token, entry.copy(originals = batch.originals))
                      ),
                      Right(batch.offers)
                    )
              }
            }
      }
      .flatMap(result => F.fromEither(result.leftMap(InstallFailure.toThrowable)))

  private final class Lease(token: Token, val offers: Vector[RelayOffer]) extends RelayLease[F]:
    def original(transactionId: Bytes): F[Option[Bytes]] = F.monotonic.flatMap { now =>
      registry.modify { old =>
        val current = old.expire(now)
        val original = current.entries.get(token).flatMap(_.originals.get(transactionId))
        (current, original)
      }
    }

  private final case class Handle(
      token: Token,
      lease: RelayLease[F],
      timer: Fiber[F, Throwable, Unit]
  )

  def acquireBatch(limits: RelayLimits): Resource[F, RelayLease[F]] =
    Resource.eval(
      F.raiseUnless(limits != null && limits.valid)(
        new IllegalArgumentException("bounded relay limits required")
      )
    ) *> Resource
      .makeFull[F, Handle] { poll =>
        F.delay(new Token).flatMap { token =>
          reserve(token, limits).flatMap { deadline =>
            (poll(install(token, limits).timeout(limits.maxLifetime))
              .flatMap { offers =>
                F.monotonic.flatMap { now =>
                  val remaining = (deadline - now).max(Duration.Zero)
                  timers.supervise(F.sleep(remaining) *> release(token)).map { timer =>
                    Handle(token, new Lease(token, offers), timer)
                  }
                }
              })
              .onCancel(release(token))
              .onError { case _ => release(token) }
          }
        }
      }(handle => handle.timer.cancel *> release(handle.token))
      .map(_.lease)

  private def close: F[Unit] = registry.set(Registry(closed = true, Map.empty))

private[lab] object AdaRelaySource:
  /** The service invokes take with current-generation eligibility under its mutation gate. The
    * callback must be bounded memory-only work and must not reenter the service or owner.
    */
  trait Selection[F[_]]:
    def withEligible[A](take: Vector[SignedTransaction] => F[A]): F[A]

  enum Reason:
    case Capacity, Closed, Expired
  final class Unavailable(val reason: Reason)
      extends RuntimeException("relay lease unavailable: " + reason.toString)

  private final class Token
  private final case class Entry(deadline: FiniteDuration, originals: Map[Bytes, Bytes])
  private final case class Registry(closed: Boolean, entries: Map[Token, Entry]):
    def expire(now: FiniteDuration): Registry =
      copy(entries = entries.filter { case (_, entry) => now < entry.deadline })

  private enum InstallFailure:
    case Selection(error: RelayBatch.Failure)
    case Unavailable(reason: Reason)

  private object InstallFailure:
    def toThrowable(error: InstallFailure): Throwable = error match
      case InstallFailure.Unavailable(reason) => new AdaRelaySource.Unavailable(reason)
      case InstallFailure.Selection(RelayBatch.Failure.InvalidLimits) =>
        new IllegalArgumentException("bounded relay limits required")
      case InstallFailure.Selection(RelayBatch.Failure.InvalidEligibleDomain) =>
        new IllegalStateException("bounded eligible pool required")
      case InstallFailure.Selection(RelayBatch.Failure.DuplicateIdentity) =>
        new IllegalStateException("duplicate eligible transaction identity")
      case InstallFailure.Selection(RelayBatch.Failure.InvalidOriginalSize(detail)) =>
        new IllegalStateException(detail)

  /** Called exactly once by AdaSubmissionService.resource; callers use that service's relaySource.
    * This is internal composition, not an API for making independent wrappers of one pool.
    */
  def resource[F[_]: Async](selection: Selection[F]): Resource[F, RelaySource[F]] =
    val F = Async[F]
    for
      _ <- Resource.eval(
        F.raiseWhen(selection == null)(new IllegalArgumentException("eligibility source required"))
      )
      registry <- Resource.eval(Ref.of[F, Registry](Registry(closed = false, Map.empty)))
      timers <- Supervisor[F]
      source <- Resource.make(F.pure(new AdaRelaySource(selection, registry, timers)))(_.close)
    yield source
