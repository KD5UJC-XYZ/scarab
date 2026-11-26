// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.Applicative
import cats.effect.{Concurrent, Ref, Resource}
import cats.syntax.all.*

/** The credentials in force, one generation at a time, and the leases that the work using their
  * keys holds on them (#1418).
  *
  * Work that uses the keys takes a lease on the generation in force as it starts ([[leased]]), and
  * ends with that generation's keys whatever a reload does meanwhile; the lease ends with it, on
  * failure and on cancellation too. A reload installs the next generation at once ([[install]]) and
  * does not wait for the leases on the one it supersedes: that one is retired, and its keys are
  * erased when its last lease ends, or at once if no lease is held on it.
  *
  * So no lease is taken on a retired generation, let alone on one being erased: a lease is only
  * ever taken on the generation in force, and a retired one is never in force again. And a
  * generation is erased exactly once, by the one change of state that removes it: the install that
  * supersedes it when no lease is held on it, or else the end of its last lease. That change and
  * the erasure it calls for are one step that cancellation does not split.
  *
  * How long a lease is held is up to its holder; [[HotKeys]] lends the keys for one evaluation or
  * for one section under the key's lock, never across a wait for a slot.
  */
private[forge] final class Generations[F[_], A] private (
    state: Ref[F, Generations.State[F, A]]
)(using F: Concurrent[F]):

  /** `use` with the generation in force, under a lease on it that ends with `use`; none, without
    * running `use`, while no generation is in force.
    */
  def leased[B](use: A => F[B]): F[Option[B]] =
    Resource
      .make(state.modify(_.leased))(_.traverse_(lease => state.modify(_.released(lease)).flatten))
      .use(_.traverse(lease => use(lease.value)))

  /** Whether a generation is in force. */
  def present: F[Boolean] = state.get.map(_.inForce.isDefined)

  /** `next` in force from now (none: no generation in force), never waiting for a lease. The
    * generation it supersedes is retired, and `retire` erases its keys when its last lease ends, or
    * now if no lease is held on it: `retire` names what of the superseded generation is erased, so
    * that a key `next` takes over from it is not.
    */
  def install(next: Option[A])(retire: A => F[Unit]): F[Unit] =
    F.uncancelable(_ => state.modify(_.installed(next, retire)).flatten)

object Generations:
  /** `first` in force. */
  def of[F[_]: Concurrent, A](first: A): F[Generations[F, A]] =
    Ref
      .of[F, State[F, A]](State(1L, Some(InForce(0L, first, 0)), Map.empty))
      .map(new Generations(_))

  /** A lease on the generation numbered `id`, whose value is `value`. */
  private final case class Lease[A](id: Long, value: A)

  /** The generation in force, numbered `id`, and the leases held on it. */
  private final case class InForce[A](id: Long, value: A, leases: Int)

  /** A retired generation on which `leases` leases are still held, and its erasure. */
  private final case class Retired[F[_]](leases: Int, erasure: F[Unit])

  /** The generation in force, the retired ones still leased, and the number the next generation
    * installed takes.
    */
  private final case class State[F[_], A](
      next: Long,
      inForce: Option[InForce[A]],
      retired: Map[Long, Retired[F]]
  ):
    /** A lease on the generation in force, if there is one. */
    def leased: (State[F, A], Option[Lease[A]]) =
      inForce.fold((this, Option.empty[Lease[A]]))(g =>
        (copy(inForce = Some(g.copy(leases = g.leases + 1))), Some(Lease(g.id, g.value)))
      )

    /** The end of `lease`, and the erasure it calls for: that of its generation, if it is retired
      * and this was the last lease on it.
      */
    def released(lease: Lease[A])(using F: Applicative[F]): (State[F, A], F[Unit]) =
      inForce match
        case Some(g) if g.id == lease.id =>
          (copy(inForce = Some(g.copy(leases = g.leases - 1))), F.unit)
        case _ =>
          retired.get(lease.id) match
            case Some(r) if r.leases > 1 =>
              (copy(retired = retired.updated(lease.id, r.copy(leases = r.leases - 1))), F.unit)
            case Some(r) => (copy(retired = retired - lease.id), r.erasure)
            // a lease's generation is in force or retired until the end of the lease, which a
            // `Resource` releases once
            case None => (this, F.unit)

    /** `next` in force, and the erasure that calls for now: that of the generation it supersedes,
      * if no lease is held on it; otherwise that generation is retired with its leases.
      */
    def installed(next: Option[A], retire: A => F[Unit])(using
        F: Applicative[F]
    ): (State[F, A], F[Unit]) =
      val made = next.map(InForce(this.next, _, 0))
      inForce match
        case None                     => (State(this.next + 1, made, retired), F.unit)
        case Some(g) if g.leases == 0 => (State(this.next + 1, made, retired), retire(g.value))
        case Some(g) =>
          val kept = retired.updated(g.id, Retired(g.leases, retire(g.value)))
          (State(this.next + 1, made, kept), F.unit)
