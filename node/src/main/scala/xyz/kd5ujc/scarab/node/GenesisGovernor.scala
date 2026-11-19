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
  * Genesis/Governor.hs:88-188 (`gddWatcher`), run by Util/STM.hs:127-152 (`runWatcher`). Both are
  * the same at 808e40054; at 2416493bf Governor.hs is too, and `runWatcher` and `blockUntilChanged`
  * are unchanged four lines lower. A change of the watcher's fingerprint (a peer's latest slot or
  * idling, a connection added or removed, a GSM transition) wakes the watcher. Density evaluation
  * is rate limited, selection wakeups are not.
  *
  * The reference reads the GSM state, ChainDB's chain and immutable ledger and every client's state
  * in one STM transaction (Governor.hs:129-148). Here they are one cell: the node's selection is
  * handed to the governor ([[select]]) before it is published to anything else, so one read of the
  * state holds all of them.
  */
final class GenesisGovernor[F[_]: Async] private (
    state: Ref[F, GenesisGovernor.State[F]],
    phaseLock: Mutex[F],
    config: GenesisGovernor.Config,
    observe: Option[GenesisGovernor.Observation => F[Unit]]
):
  import GenesisGovernor.*
  private val F = Async[F]

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

  /** A session registers with a kill handle of its own, as the reference's ChainSync client
    * registers `cschGDDKill`, a `throwTo` to its own thread (consensus 82ecba329,
    * MiniProtocol/ChainSync/Client.hs:423 and 449; 424 and 450 at 808e40054 and 2416493bf), which
    * does nothing once that thread has ended. A session ends under its handle's guard, and a
    * disconnection is delivered under the same guard only while the session is registered
    * ([[deliver]]): once the session has ended, its handle does nothing, whatever the bearer is
    * used for afterwards.
    */
  def session(
      configure: Gsm.Phase => F[Unit],
      disconnect: F[Unit],
      orderKey: Option[PeerOrder] = None
  ): Resource[F, Session[F]] =
    Resource
      .make(
        phaseLock.lock.surround {
          Mutex[F].flatMap { guard =>
            state.get.flatMap { before =>
              configure(before.phase) *> change { s =>
                val id = s.next
                val peer = Peer[F](
                  None,
                  LatestSlot.Unknown,
                  false,
                  configure,
                  Kill(guard, disconnect),
                  orderKey.getOrElse(PeerOrder(id))
                )
                (s.copy(next = id + 1, peers = s.peers.updated(id, peer)), id -> guard)
              }
            }
          }
        }
      )((id, guard) => guard.lock.surround(change(s => (s.copy(peers = s.peers - id), ()))))
      .map((id, _) => new Session(this, id))

  /** Disconnect the session `id` through the kill handle a snapshot holds: under the handle's
    * guard, and only if the session is still registered, so a session that has ended meanwhile is
    * not told.
    */
  private def deliver(id: Long, kill: Kill[F]): F[Unit] =
    kill.guard.lock.surround(
      state.get.flatMap(now => kill.disconnect.whenA(now.peers.contains(id)))
    )

  /** ChainDB's current chain and immutable ledger, as the node's selection commits them. The
    * selection hands each one here before it publishes it to the sessions, so a snapshot never
    * holds a candidate checked against a newer selection than its own. A new selection does not
    * wake the watcher, whose fingerprint does not read the chain (Governor.hs:150-165, consensus
    * 82ecba329).
    */
  def select(current: View): F[Unit] = change(s => (s.copy(view = current), ()))

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
  def limit: F[Option[Fragment]] = state.get.map(s => limitOf(s, s.view.current))

  /** Selection already holds its lock and supplies that snapshot, avoiding a second lock read. */
  def limitAt(current: Fragment): F[Option[Fragment]] = state.get.map(limitOf(_, current))

  private def limitOf(s: State[F], current: Fragment): Option[Fragment] =
    if !config.enabled || s.phase == Gsm.Phase.CaughtUp then None
    else if s.phase == Gsm.Phase.PreSyncing then Some(Fragment(current.anchor, Vector.empty))
    else Some(s.limit.getOrElse(Fragment(current.anchor, Vector.empty)))

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

  def candidates: F[Vector[(Long, PeerState)]] =
    state.get.map(_.peers.toVector.flatMap { (id, peer) =>
      peer.candidate.map(fragment => id -> PeerState(fragment, peer.latestSlot, peer.idling))
    })

  /** `blockUntilChanged` (Util/STM.hs:47-56, consensus 82ecba329): wait until the fingerprint
    * differs from the one of the last snapshot evaluated, then take the next snapshot from that
    * same read of the state. The revision only signals that the fingerprint may have changed: a
    * fingerprint that changed and came back is the one already evaluated, and is waited past. None
    * reads at once, as `runWatcher` does with no initial fingerprint (STM.hs:132-139).
    */
  private def awaitSnapshot(last: Option[InputFingerprint]): F[Snapshot[F]] =
    F.tailRecM(()) { _ =>
      state.get.flatMap { s =>
        val read = Snapshot.read(s, config.enabled)
        if last.contains(read.fingerprint) then s.changed.get.as(Left(()))
        else F.pure(Right(read))
      }
    }

  /** The computation of `evaluateGDD` over one snapshot (Governor.hs:226-264, consensus 82ecba329).
    * A newly registered connection contributes an empty candidate until FindIntersect.
    */
  private def decide(peers: SortedMap[Long, Peer[F]], current: View): Observation =
    val ordered = peers.toVector.sortBy { case (id, peer) => (peer.orderKey, id) }
    val states = ordered.map { (id, peer) =>
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
      ordered.map((id, _) => id -> states(id).candidate)
    )
    val window = current.windowAt(GenesisDensity.firstSlotAfter(shared.prefix))
    Observation(
      current.current,
      current.k,
      window,
      ordered.map((id, peer) => Entry(id, peer.orderKey, states(id))),
      shared,
      window.map(
        GenesisDensity.densityDisconnect(_, current.k, states, shared.suffixes, shared.prefix)
      )
    )

  /** `wNotify` (Governor.hs:167-188, consensus 82ecba329). For a Syncing snapshot: evaluate it,
    * store the LoE fragment and disconnect the losers, then wait out the rate limit, measured from
    * before the evaluation. The result is committed whatever was written after the snapshot was
    * read, the GSM phase included, as the reference's `swapTVar` is.
    *
    * The reference kills each loser through its handle in the snapshot (Governor.hs:281-283); each
    * is delivered here through the session's own kill handle ([[deliver]]). The reference kills
    * before it swaps; a disconnect here completes asynchronously, and storing first is the order in
    * which the replayed upstream Genesis cases end their peers in the reference's order
    * (`UpstreamGenesisReplaySuite`).
    */
  private def evaluate(snapshot: Snapshot[F]): F[Unit] = snapshot match
    case Snapshot.Idle(_, _) => F.unit
    case Snapshot.Syncing(taken, _) =>
      for
        began <- F.monotonic
        observation = decide(taken.peers, taken.view)
        _ <- observe.traverse_(_(observation))
        _ <- change(s => (s.copy(limit = Some(observation.shared.prefix)), ()))
        _ <- observation.result.toVector
          .flatMap(_.losingPeers)
          .flatMap(id => taken.peers.get(id).map(peer => id -> peer.kill))
          .traverse_(deliver)
        ended <- F.monotonic
        _ <- F.sleep((config.interval - (ended - began)).max(Duration.Zero))
      yield ()

  /** `runWatcher` (Util/STM.hs:127-152, consensus 82ecba329): each snapshot is evaluated, and the
    * next waits for the fingerprint to change from that snapshot's.
    */
  private[node] def run: F[Nothing] =
    F.tailRecM[Option[InputFingerprint], Nothing](None) { last =>
      awaitSnapshot(last).flatMap(snapshot =>
        evaluate(snapshot).as(Left(Some(snapshot.fingerprint)))
      )
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

  /** A session's kill handle: its disconnection, and the guard its session ends under. */
  private final case class Kill[F[_]](guard: Mutex[F], disconnect: F[Unit])

  private final case class Peer[F[_]](
      candidate: Option[Fragment],
      latestSlot: LatestSlot,
      idling: Boolean,
      configure: Gsm.Phase => F[Unit],
      kill: Kill[F],
      orderKey: PeerOrder
  )

  private final case class State[F[_]](
      phase: Gsm.Phase,
      next: Long,
      peers: SortedMap[Long, Peer[F]],
      limit: Option[Fragment],
      view: View,
      revision: Long,
      changed: Deferred[F, Unit],
      selectionRevision: Long,
      selectionChanged: Deferred[F, Unit]
  )

  /** `wFingerprint` (Governor.hs:150-165, consensus 82ecba329): in Syncing, each peer's latest slot
    * and idling; otherwise the phase alone.
    */
  private def fingerprint[F[_]](s: State[F]): InputFingerprint =
    // Each established TCP connection has one hot activation. Keep grouped registrations rather
    // than silently overwriting metadata if a model deliberately violates that rule.
    (
      s.phase,
      Option.when(s.phase == Gsm.Phase.Syncing)(
        SortedMap.from(
          s.peers.values.toVector.groupMap(_.orderKey)(peer => peer.latestSlot -> peer.idling)
        )
      )
    )

  /** What one evaluation reads, as the `wReader` of `gddWatcher` (Governor.hs:129-148, consensus
    * 82ecba329) reads it in one transaction. The governor's state is one value, so the GSM phase,
    * ChainDB's view, the candidates and the kill handles all come from the same read, and so does
    * the fingerprint the next snapshot is compared with; only `read` builds a snapshot, and only
    * from one state.
    */
  private sealed trait Snapshot[F[_]]:
    def state: State[F]
    def fingerprint: InputFingerprint

  private object Snapshot:
    /** PreSyncing or CaughtUp, or LoE and GDD disabled: nothing to evaluate (consensus 82ecba329,
      * Governor.hs:168-174; with LoE and GDD disabled the watcher is not started,
      * NodeKernel.hs:358-359).
      */
    final case class Idle[F[_]] private[Snapshot] (
        state: State[F],
        fingerprint: InputFingerprint
    ) extends Snapshot[F]

    /** Syncing: the state, evaluated as it is (consensus 82ecba329, Governor.hs:176-188). */
    final case class Syncing[F[_]] private[Snapshot] (
        state: State[F],
        fingerprint: InputFingerprint
    ) extends Snapshot[F]

    def read[F[_]](state: State[F], enabled: Boolean): Snapshot[F] =
      if enabled && state.phase == Gsm.Phase.Syncing then Syncing(state, fingerprint(state))
      else Idle(state, fingerprint(state))

  final class Session[F[_]] private[GenesisGovernor] (owner: GenesisGovernor[F], val id: Long):
    def publish(candidate: Option[Fragment], latest: LatestSlot, idling: Boolean): F[Unit] =
      owner.publish(id, candidate, latest, idling)

  /** @param view
    *   the node's selection when the governor is created; the selection hands each later one to
    *   [[GenesisGovernor.select]]
    */
  def create[F[_]: Async](
      view: View,
      config: Config = Config(),
      observe: Option[Observation => F[Unit]] = None
  ): F[GenesisGovernor[F]] =
    for
      signal <- Deferred[F, Unit]
      selection <- Deferred[F, Unit]
      state <- Ref.of[F, State[F]](
        State(Gsm.Phase.PreSyncing, 0L, SortedMap.empty, None, view, 0L, signal, 0L, selection)
      )
      lock <- Mutex[F]
    yield new GenesisGovernor(state, lock, config, observe)
