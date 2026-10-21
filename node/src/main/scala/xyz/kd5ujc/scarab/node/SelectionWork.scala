// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Ref, Resource, Temporal}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.selection.GenesisBlockFetch.Starvation
import xyz.kd5ujc.scarab.network.genesis.PicosClock

/** Ready block-consideration work, shared by all following sessions. Remote fetches and LoE
  * reprocessing are not AddBlock messages. Reference: ChainDB/Impl/Types.hs:691–718 at 82ecba329.
  * Keep processing work live until the selection lock's owner completes it, so a slow ledger check
  * is never mistaken for a starved selection worker.
  */
final class SelectionWork[F[_]: Temporal] private (
    state: Ref[F, SelectionWork.State],
    changed: F[Unit],
    clock: PicosClock[F]
):
  import SelectionWork.*
  private val F = Temporal[F]

  def snapshot: F[Starvation] = state.get.map(_.starvation)

  private def alter(f: (State, BigInt) => State): F[Unit] = F.uncancelable { _ =>
    clock.now.flatMap { now =>
      state
        .modify { before =>
          val after = f(before, now)
          (after, before.starvation != after.starvation)
        }
        .flatMap(notify => changed.whenA(notify))
    }
  }

  def session: Resource[F, Session[F]] = Resource
    .make(
      state.modify(s =>
        (s.copy(next = s.next + 1, owners = s.owners.updated(s.next, Work(Map.empty, 0))), s.next)
      )
    )(id => alter((s, _) => settle(s.copy(owners = s.owners - id))))
    .map(id => new Session(this, id))

  private def ready(id: Long, hashes: Vector[Bytes]): F[Unit] = state.update { s =>
    s.copy(owners = s.owners.updatedWith(id)(_.map { work =>
      work.copy(queued =
        hashes.foldLeft(work.queued)((counts, hash) =>
          counts.updated(hash, counts.getOrElse(hash, 0) + 1)
        )
      )
    }))
  }

  /** Acquired under the global application lock. A cached read or LoE-only reconsideration has no
    * new queued AddBlock token and therefore cannot end starvation.
    */
  private def processing(id: Long, hash: Bytes): Resource[F, Unit] = Resource
    .make(
      F.uncancelable { _ =>
        clock.now.flatMap { now =>
          state
            .modify { s =>
              val source =
                s.owners.toVector.sortBy(_._1).find((_, work) => work.queued.getOrElse(hash, 0) > 0)
              (s.owners.get(id), source) match
                case (Some(caller), Some((owner, work))) =>
                  val count = work.queued(hash)
                  val queued =
                    if count == 1 then work.queued - hash
                    else work.queued.updated(hash, count - 1)
                  val removed = s.owners.updated(owner, work.copy(queued = queued))
                  val processing =
                    removed.updatedWith(id)(_.map(w => w.copy(processing = caller.processing + 1)))
                  val starvation = s.starvation match
                    case Starvation.Ongoing => Starvation.EndedAt(now)
                    case value              => value
                  (
                    s.copy(owners = processing, starvation = starvation),
                    (true, starvation != s.starvation)
                  )
                case _ => (s, (false, false))
            }
            .flatMap((acquired, notify) => changed.whenA(notify).as(acquired))
        }
      }
    )(acquired =>
      if !acquired then F.unit
      else
        alter { (s, _) =>
          settle(
            s.copy(owners =
              s.owners.updatedWith(id)(_.map(w => w.copy(processing = w.processing - 1)))
            )
          )
        }
    )
    .void

  private def discardPending(id: Long): F[Unit] = alter { (s, _) =>
    settle(s.copy(owners = s.owners.updatedWith(id)(_.map(_.copy(queued = Map.empty)))))
  }

object SelectionWork:
  final class Session[F[_]] private[SelectionWork] (owner: SelectionWork[F], id: Long):
    def ready(hashes: Vector[Bytes]): F[Unit] = owner.ready(id, hashes)
    def processing(hash: Bytes): Resource[F, Unit] = owner.processing(id, hash)
    def discardPending: F[Unit] = owner.discardPending(id)

  private final case class Work(queued: Map[Bytes, Int], processing: Int)
  private final case class State(next: Long, owners: Map[Long, Work], starvation: Starvation)
  private def settle(state: State): State =
    if state.owners.valuesIterator.forall(w => w.queued.isEmpty && w.processing == 0) then
      state.copy(starvation = Starvation.Ongoing)
    else state
  def create[F[_]: Temporal](
      changed: F[Unit],
      clock: Option[PicosClock[F]] = None
  ): F[SelectionWork[F]] =
    Ref
      .of[F, State](State(0L, Map.empty, Starvation.Ongoing))
      .map(new SelectionWork(_, changed, clock.getOrElse(PicosClock.system[F])))
