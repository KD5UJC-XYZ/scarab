// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.{Fragment, LatestSlot, PeerState}
import xyz.kd5ujc.scarab.network.selection.Gsm

/** The node-owned Genesis candidate registry. ChainSync publishes validated candidates before block
  * fetching; GDD observes them independently of block application. Reference: consensus 82ecba329,
  * Genesis/Governor.hs:110-200. Candidate publication, connection removal and GSM transitions wake
  * the watcher. Density evaluation is rate limited, selection wakeups are not.
  */
final class GenesisGovernor[F[_]: Async] private (
    state: Ref[F, GenesisGovernor.State[F]],
    phaseLock: Mutex[F],
    view: F[GenesisGovernor.View],
    config: GenesisGovernor.Config,
    observe: Option[GenesisGovernor.Observation => F[Unit]]
):
  import GenesisGovernor.*
  private val F = Async[F]

  private def fingerprint(
      s: State[F]
  ): InputFingerprint =
    (
      s.phase,
      Option.when(s.phase == Gsm.Phase.Syncing)(
        // Each established TCP connection has one hot activation. Keep grouped registrations
        // rather than silently overwriting metadata if a model deliberately violates that rule.
        SortedMap.from(
          s.peers.values.toVector.groupMap(_.orderKey)(peer => peer.latestSlot -> peer.idling)
        )
      )
    )

  private def headHash(limit: Option[Fragment]): Option[xyz.kd5ujc.scarab.codec.Bytes] =
    limit.flatMap(_.tip match
      case xyz.kd5ujc.scarab.protocol.ChainSync.Point.Origin         => None
      case xyz.kd5ujc.scarab.protocol.ChainSync.Point.Block(_, hash) => Some(hash))

  private def change[A](f: State[F] => (State[F], A)): F[A] =
    F.uncancelable { _ =>
      (Deferred[F, Unit], Deferred[F, Unit]).tupled.flatMap { (inputSignal, selectionSignal) =>
        state
          .modify { before =>
            val (after, result) = f(before)
            val inputChanged = fingerprint(before) != fingerprint(after)
            val selectionChanged =
              before.phase != after.phase || headHash(before.limit) != headHash(after.limit)
            val next = after.copy(
              version = before.version + 1,
              revision = before.revision + (if inputChanged then 1 else 0),
              changed = if inputChanged then inputSignal else before.changed,
              selectionRevision = before.selectionRevision + (if selectionChanged then 1 else 0),
              selectionChanged =
                if selectionChanged then selectionSignal else before.selectionChanged
            )
            (
              next,
              (
                Option.when(inputChanged)(before.changed),
                Option.when(selectionChanged)(before.selectionChanged),
                result
              )
            )
          }
          .flatMap { (input, selection, result) =>
            input.traverse_(_.complete(())) *> selection.traverse_(_.complete(())).as(result)
          }
      }
    }

  private[node] def inputFingerprint: F[InputFingerprint] = state.get.map(fingerprint)

  def phase: F[Gsm.Phase] = state.get.map(_.phase)

  /** A phase transition configures every live bucket before subsequent registrations can see that
    * phase. In particular, entering Syncing refills rather than resumes an old balance.
    */
  def setPhase(value: Gsm.Phase): F[Unit] = phaseLock.lock.surround {
    F.uncancelable { _ =>
      state.get.flatMap { before =>
        if before.phase == value then F.unit
        else
          before.peers.values.toVector.traverse_(_.configure(value)) *>
            change(s => (s.copy(phase = value), ()))
      }
    }
  }

  def session(
      configure: Gsm.Phase => F[Unit],
      disconnect: F[Unit],
      orderKey: Option[PeerOrder] = None
  ): Resource[F, Session[F]] =
    Resource
      .make(
        phaseLock.lock.surround {
          state.get.flatMap { before =>
            configure(before.phase) *> change { s =>
              val id = s.next
              val peer = Peer[F](
                None,
                LatestSlot.Unknown,
                false,
                configure,
                disconnect,
                orderKey.getOrElse(PeerOrder(id))
              )
              (s.copy(next = id + 1, peers = s.peers.updated(id, peer)), id)
            }
          }
        }
      )(id => change(s => (s.copy(peers = s.peers - id), ())))
      .map(id => new Session(this, id))

  private def publish(
      id: Long,
      candidate: Option[Fragment],
      latest: LatestSlot,
      idling: Boolean
  ): F[Unit] =
    change(s =>
      (
        s.copy(peers =
          s.peers.updatedWith(id)(
            _.map(
              _.copy(
                candidate = candidate,
                latestSlot = latest,
                idling = idling
              )
            )
          )
        ),
        ()
      )
    )

  /** Read the current cap. PreSyncing always uses the current immutable point. A newly entered
    * Syncing phase reads the stored fragment, as Node/Genesis.hs:274 does; its previous value
    * remains until the watcher establishes the next shared prefix.
    */
  def limit: F[Option[Fragment]] =
    view.flatMap(current => limitAt(current.current))

  /** Selection already holds its lock and supplies that snapshot, avoiding a second lock read. */
  def limitAt(current: Fragment): F[Option[Fragment]] = state.get.map { s =>
    if !config.enabled || s.phase == Gsm.Phase.CaughtUp then None
    else if s.phase == Gsm.Phase.PreSyncing then Some(Fragment(current.anchor, Vector.empty))
    else Some(s.limit.getOrElse(Fragment(current.anchor, Vector.empty)))
  }

  def revision: F[Long] = state.get.map(_.revision)

  /** Versioned waiting avoids losing a selection wakeup between observing and waiting. */
  def changed(after: Long): F[Long] = F.tailRecM(()) { _ =>
    state.get.flatMap { s =>
      if s.revision != after then F.pure(Right(s.revision))
      else s.changed.get.as(Left(()))
    }
  }

  /** Chain selection wakes only for a phase or LoE head change, not each raw header observation. */
  def selectionRevision: F[Long] = state.get.map(_.selectionRevision)

  def selectionChanged(after: Long): F[Long] = F.tailRecM(()) { _ =>
    state.get.flatMap { s =>
      if s.selectionRevision != after then F.pure(Right(s.selectionRevision))
      else s.selectionChanged.get.as(Left(()))
    }
  }

  private def coherentView: F[(State[F], Option[View])] = F.tailRecM(()) { _ =>
    for
      before <- state.get
      current <-
        if config.enabled && before.phase == Gsm.Phase.Syncing then view.map(Some(_))
        else F.pure(None)
      after <- state.get
    yield if before.version == after.version then Right(before -> current) else Left(())
  }

  def candidates: F[Vector[(Long, PeerState)]] =
    state.get.map(_.peers.toVector.flatMap { (id, peer) =>
      peer.candidate.map(fragment => id -> PeerState(fragment, peer.latestSlot, peer.idling))
    })

  private def evaluate: F[Boolean] =
    F.tailRecM(()) { _ =>
      coherentView.flatMap { (snapshot, available) =>
        available match
          case None          => F.pure(Right(false))
          case Some(current) =>
            // A newly registered connection contributes an empty candidate until FindIntersect.
            val ordered = snapshot.peers.toVector.sortBy { case (id, peer) =>
              (peer.orderKey, id)
            }
            val peers = ordered.map { (id, peer) =>
              id -> PeerState(
                peer.candidate.getOrElse(
                  Fragment(xyz.kd5ujc.scarab.protocol.ChainSync.Point.Origin, Vector.empty)
                ),
                peer.latestSlot,
                peer.idling
              )
            }.toMap
            val shared = GenesisDensity.sharedCandidatePrefix(
              current.current,
              ordered.map((id, _) => id -> peers(id).candidate)
            )
            val window = current.windowAt(GenesisDensity.firstSlotAfter(shared.prefix))
            val result = window.map { window =>
              GenesisDensity.densityDisconnect(
                window,
                current.k,
                peers,
                shared.suffixes,
                shared.prefix
              )
            }
            change { now =>
              if now.phase != Gsm.Phase.Syncing || now.version != snapshot.version then (now, None)
              else
                (
                  now.copy(limit = Some(shared.prefix)),
                  Some(
                    (
                      Observation(
                        current.current,
                        current.k,
                        window,
                        ordered.map((id, peer) => Entry(id, peer.orderKey, peers(id))),
                        shared,
                        result
                      ),
                      result.toVector
                        .flatMap(_.losingPeers)
                        .flatMap(snapshot.peers.get)
                        .map(_.disconnect)
                    )
                  )
                )
            }.flatMap {
              case None => F.pure(Left(()))
              case Some((observation, disconnects)) =>
                observe.traverse_(_(observation)) *> disconnects.sequence_.as(Right(true))
            }
      }
    }

  private[node] def run: F[Nothing] =
    F.tailRecM[Long, Nothing](-1L) { previous =>
      changed(previous).flatMap { seen =>
        for
          began <- F.monotonic
          evaluated <- evaluate
          ended <- F.monotonic
          _ <- F
            .sleep((config.interval - (ended - began)).max(Duration.Zero))
            .whenA(evaluated)
        yield Left(seen)
      }
    }

object GenesisGovernor:
  private[node] type InputFingerprint =
    (Gsm.Phase, Option[SortedMap[PeerOrder, Vector[(LatestSlot, Boolean)]]])
  final case class Entry(session: Long, orderKey: PeerOrder, state: PeerState)
  final case class Observation(
      current: Fragment,
      k: Int,
      genesisWindow: Option[BigInt],
      peers: Vector[Entry],
      shared: GenesisDensity.Shared[Long],
      result: Option[GenesisDensity.Result[Long]]
  )
  final case class Config(enabled: Boolean = true, interval: FiniteDuration = 1.second)

  /** A coherent ChainDB selection and immutable-ledger history query. Unknown history leaves
    * density undecided; it never supplies a guessed Genesis window.
    */
  final case class View(current: Fragment, k: Int, windowAt: BigInt => Option[BigInt])

  private final case class Peer[F[_]](
      candidate: Option[Fragment],
      latestSlot: LatestSlot,
      idling: Boolean,
      configure: Gsm.Phase => F[Unit],
      disconnect: F[Unit],
      orderKey: PeerOrder
  )

  private final case class State[F[_]](
      phase: Gsm.Phase,
      next: Long,
      peers: SortedMap[Long, Peer[F]],
      limit: Option[Fragment],
      version: Long,
      revision: Long,
      changed: Deferred[F, Unit],
      selectionRevision: Long,
      selectionChanged: Deferred[F, Unit]
  )

  final class Session[F[_]] private[GenesisGovernor] (owner: GenesisGovernor[F], val id: Long):
    def publish(candidate: Option[Fragment], latest: LatestSlot, idling: Boolean): F[Unit] =
      owner.publish(id, candidate, latest, idling)

  def create[F[_]: Async](
      view: F[View],
      config: Config = Config(),
      observe: Option[Observation => F[Unit]] = None
  ): F[GenesisGovernor[F]] =
    for
      signal <- Deferred[F, Unit]
      selection <- Deferred[F, Unit]
      state <- Ref.of[F, State[F]](
        State(Gsm.Phase.PreSyncing, 0L, SortedMap.empty, None, 0L, 0L, signal, 0L, selection)
      )
      lock <- Mutex[F]
    yield new GenesisGovernor(state, lock, view, config, observe)
