// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.governor

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.{Queue, Supervisor}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.GovernorFault
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.node.{GovernorConfig, Output, PeerGovernor, PeerStateActions, RootProvider}
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** The environment the governor runs in, in a test: the analogue of the reference's
  * `GovernorMockEnvironment` (`Test/Cardano/Network/PeerSelection/MockEnvironment.hs` 139-163). A
  * graph of peers whose connections fail, drop to warm, linger and close on a script; local roots,
  * public roots and bootstrap peers; the targets and the ledger judgement as scripts in time; a
  * seed. The governor loop of the node ([[PeerGovernor]]) runs against it on `TestControl`, so the
  * time is virtual and a run of ten hours takes milliseconds, and the same environment and seed
  * give the same trace.
  *
  * Peers are numbers. A connection's script is stepped as the reference's `connectionScript` is: a
  * step is a demotion and the delay after which it happens; `Noop` ends the script of that
  * connection, and the script of a peer goes on across its connections.
  */
object MockEnvironment:
  type Peer = Int

  /** What the environment does to a connection by itself. */
  enum Demotion:
    case Noop, ToWarm, ToCooling, ToCold

  /** `NoDelay` is one second, `ShortDelay` 60, `LongDelay` 600 (`interpretScriptDelay`). */
  enum Delay(val duration: FiniteDuration):
    case NoDelay extends Delay(1.second)
    case ShortDelay extends Delay(60.seconds)
    case LongDelay extends Delay(600.seconds)

  final case class ScriptStep(demotion: Demotion, delay: Delay)

  /** What happens to the connections of one peer: whether each attempt to connect fails, and the
    * steps of the demotions. The last of each repeats for ever.
    */
  final case class PeerScript(connectFails: Vector[Boolean], steps: Vector[ScriptStep])

  object PeerScript:
    val steady: PeerScript =
      PeerScript(Vector(false), Vector(ScriptStep(Demotion.Noop, Delay.NoDelay)))

  /** A value that changes at the given offsets from the start. */
  final case class Timed[A](first: A, later: Vector[(FiniteDuration, A)]):
    def all: Vector[(FiniteDuration, A)] = (Duration.Zero -> first) +: later

  final case class Env(
      peers: Int,
      scripts: Map[Peer, PeerScript],
      localRoots: List[RootGroupSpec[Peer]],
      publicRoots: Set[Peer],
      bootstrapPeers: Set[Peer],
      useBootstrap: Boolean,
      targets: Timed[Targets],
      judgement: Timed[LedgerStateJudgement],
      seed: Long,
      /** The relays the ledger offers an ordinary request (when there are any, they are offered
        * instead of the public roots, as the reference's `requestPublicRootPeers` does).
        */
      ledgerPeers: Set[Peer] = Set.empty,
      /** The relays the ledger offers a request for big ledger peers. */
      bigLedgerPeers: Set[Peer] = Set.empty
  ):
    def script(peer: Peer): PeerScript = scripts.getOrElse(peer, PeerScript.steady)

  /** What the environment does or is told, with the credits the busyness property gives it. */
  enum EnvEvent:
    case AddPeers(count: Int)
    case SetLocalRoots(size: Int)
    case SetPublicRoots(size: Int)
    case SetTargets(targets: Targets)
    case SetJudgement(judgement: LedgerStateJudgement)
    case Demote(demotion: Demotion, peer: Peer)
    case RootsResult(peers: Set[Peer])
    case RootsTtl
    case BigLedgerResult(peers: Set[Peer])
    case BigLedgerTtl
    case Establish(peer: Peer)
    case Activate(peer: Peer)
    case Deactivate(peer: Peer)
    case Close(peer: Peer)

    /** `envEventCredits` (the reference's, `PeerSelection.hs` 785-850). */
    def credits: Int = this match
      case AddPeers(n)              => 80 * 5 + n * 5
      case SetLocalRoots(n)         => n
      case SetPublicRoots(n)        => n
      case SetTargets(t)            => 80 + 10 * (t.known + t.established + t.active)
      case SetJudgement(_)          => 30
      case Demote(Demotion.Noop, _) => 10
      case Demote(_, _)             => 30
      case RootsResult(_)           => 15
      case RootsTtl                 => 60
      case BigLedgerResult(_)       => 10
      case BigLedgerTtl             => 60
      case _                        => 0

  /** One thing seen in a run, in order. */
  enum Seen:
    case Governor(output: Output[Peer])
    case State(state: GovernorState[Peer])
    case Env(event: EnvEvent)

    /** The status of every connection the environment holds, after a change. */
    case Connections(status: Map[Peer, Conn])

  enum Conn:
    case Warm, Hot, Cooling, Cold

  final case class Recording(
      seen: Vector[(Time, Seen)],
      fatal: Option[GovernorFault],
      /** The run was stopped because it recorded far more than any sane run does: a livelock. */
      overflowed: Boolean,
      env: Env,
      horizon: Time
  ):
    def governorOutputs: Vector[(Time, Output[Peer])] = seen.collect { case (t, Seen.Governor(o)) =>
      t -> o
    }
    def traces: Vector[(Time, Trace[Peer])] =
      governorOutputs.collect { case (t, Output.Traced(trace)) => t -> trace }
    def states: Vector[(Time, GovernorState[Peer])] = seen.collect { case (t, Seen.State(s)) =>
      t -> s
    }
    def envEvents: Vector[(Time, EnvEvent)] = seen.collect { case (t, Seen.Env(e)) => t -> e }
    def connections: Vector[(Time, Map[Peer, Conn])] =
      seen.collect { case (t, Seen.Connections(c)) => t -> c }

  private val config =
    RootConfig(false, DiffusionMode.InitiatorAndResponder, Provenance.Outbound, false)

  def rootGroup(hot: Int, warm: Int, members: Peer*): RootGroupSpec[Peer] =
    RootGroupSpec(hot, warm, members.toList.map(_ -> config))

  /** Run the governor in the environment for `horizon` of virtual time. */
  def run(env: Env, horizon: FiniteDuration = 10.hours): Recording =
    TestControl
      .executeEmbed(
        program(env, horizon),
        seed = Some(java.util.Base64.getEncoder.encodeToString(env.seed.toString.getBytes("UTF-8")))
      )
      .unsafeRunSync()

  private def program(env: Env, horizon: FiniteDuration): IO[Recording] =
    for
      origin <- IO.monotonic
      log <- Ref.of[IO, Vector[(Time, Seen)]](Vector.empty)
      overflow <- Deferred[IO, Unit]
      note = (what: Seen) =>
        IO.monotonic.flatMap(now =>
          log
            .modify(v => (v :+ ((now - origin) -> what), v.size))
            .flatMap(size => overflow.complete(()).void.whenA(size > MaxRecorded))
        )
      events <- Queue.unbounded[IO, Event[Peer]]
      fatal <- Deferred[IO, GovernorFault]
      statuses <- Ref.of[IO, Map[Peer, Ref[IO, Conn]]](Map.empty)
      cursors <- Ref.of[IO, Map[Peer, (Int, Int)]](Map.empty)
      snapshot = statuses.get
        .flatMap(_.toList.traverse((p, r) => r.get.map(p -> _)))
        .map(_.toMap)
        .flatMap(m => note(Seen.Connections(m)))
      roots = LocalRoots.fromGroups(env.localRoots)
      initial = GovernorState.initial[Peer](
        env.seed,
        roots,
        env.targets.first,
        Sensitivity.initial(env.useBootstrap)
      )
      stateRef <- Ref.of[IO, GovernorState[Peer]](initial)
      result <- Supervisor[IO](await = false).use { supervisor =>
        def report(e: Event[Peer]): IO[Unit] = events.offer(e)

        def next(peer: Peer): IO[ScriptStep] =
          cursors
            .modify { m =>
              val (i, j) = m.getOrElse(peer, (0, 0))
              (m.updated(peer, (i, j + 1)), j)
            }
            .map { j =>
              val steps = env.script(peer).steps
              steps(j.min(steps.size - 1))
            }

        /** The environment demotes a connection on its script. */
        def monitor(peer: Peer, conn: Ref[IO, Conn]): IO[Unit] =
          def loop: IO[Unit] =
            next(peer).flatMap { step =>
              step.demotion match
                case Demotion.Noop => note(Seen.Env(EnvEvent.Demote(Demotion.Noop, peer)))
                case demotion =>
                  IO.sleep(step.delay.duration) *> {
                    val lost = (released: Boolean) =>
                      Observation.Lost(FailureClass.Transient, 10.seconds, released, "env")
                    conn.get.flatMap { current =>
                      (demotion, current) match
                        case (_, Conn.Cold) => IO.pure(true)
                        case (Demotion.ToWarm, Conn.Hot) =>
                          conn.set(Conn.Warm) *> snapshot *>
                            report(Event.Observed(peer, Observation.HotEnded(Some(10.seconds)))) *>
                            IO.pure(false)
                        case (Demotion.ToWarm, _)               => IO.pure(false)
                        case (Demotion.ToCooling, Conn.Cooling) => IO.pure(false)
                        case (Demotion.ToCooling, _) =>
                          conn.set(Conn.Cooling) *> snapshot *>
                            report(Event.Observed(peer, lost(false))) *> IO.pure(false)
                        case (Demotion.ToCold, _) =>
                          conn.set(Conn.Cold) *> snapshot *>
                            report(Event.Observed(peer, lost(true))) *> IO.pure(true)
                        case (Demotion.Noop, _) => IO.pure(true)
                    }
                  }.flatMap { done =>
                    note(Seen.Env(EnvEvent.Demote(demotion, peer))) *> (if done then IO.unit
                                                                        else loop)
                  }
            }
          loop

        val actions = new PeerStateActions[IO, Peer]:
          def establish(peer: Peer, mode: DiffusionMode, provenance: Provenance) =
            note(Seen.Env(EnvEvent.Establish(peer))) *> IO.sleep(1.second) *> {
              cursors
                .modify { m =>
                  val (i, j) = m.getOrElse(peer, (0, 0))
                  (m.updated(peer, (i + 1, j)), i)
                }
                .flatMap { attempt =>
                  val fails = env.script(peer).connectFails
                  if peer >= env.peers || fails(attempt.min(fails.size - 1)) then
                    IO.pure(Some(FailureClass.Transient))
                  else
                    Ref.of[IO, Conn](Conn.Warm).flatMap { conn =>
                      statuses.update(_ + (peer -> conn)) *> snapshot *>
                        supervisor.supervise(monitor(peer, conn)).as(None)
                    }
                }
            }

          def activate(peer: Peer) =
            note(Seen.Env(EnvEvent.Activate(peer))) *> IO.sleep(1.second) *>
              statuses.get.map(_.get(peer)).flatMap {
                case None => IO.pure(Some(FailureClass.Transient))
                case Some(conn) =>
                  conn.get.flatMap {
                    case Conn.Warm =>
                      conn.set(Conn.Hot) *> snapshot.as(Option.empty[FailureClass])
                    case Conn.Hot => IO.pure(None)
                    case _        => IO.pure(Some(FailureClass.Transient))
                  }
              }

          def deactivate(peer: Peer) =
            note(Seen.Env(EnvEvent.Deactivate(peer))) *> statuses.get.map(_.get(peer)).flatMap {
              case None => IO.pure(PeerStateActions.Deactivation.Closed)
              case Some(conn) =>
                conn.get.flatMap {
                  case Conn.Hot | Conn.Warm =>
                    conn.set(Conn.Warm) *> snapshot.as(PeerStateActions.Deactivation.Warm)
                  case _ =>
                    conn.set(Conn.Cold) *> statuses.update(_ - peer) *> snapshot.as(
                      PeerStateActions.Deactivation.Closed
                    )
                }
            }

          def close(peer: Peer) =
            note(Seen.Env(EnvEvent.Close(peer))) *> statuses.get.map(_.get(peer)).flatMap {
              case None => IO.pure(true)
              case Some(conn) =>
                conn.set(Conn.Cold) *> statuses.update(_ - peer) *> snapshot.as(true)
            }

        val provider = new RootProvider[IO, Peer]:
          def request(max: Int, bootstrap: Boolean) =
            val found =
              if bootstrap then PublicRoots(Set.empty[Peer], env.bootstrapPeers)
              else if env.ledgerPeers.nonEmpty then
                PublicRoots(Set.empty[Peer], Set.empty[Peer], env.ledgerPeers)
              else PublicRoots(env.publicRoots, Set.empty[Peer])
            note(
              Seen.Env(EnvEvent.RootsResult(found.ordinary ++ found.bootstrap ++ found.ledger))
            ) *>
              supervisor
                .supervise(IO.sleep(60.seconds) *> note(Seen.Env(EnvEvent.RootsTtl)))
                .as(Some(found -> 60.seconds))

          def requestBigLedger(max: Int) =
            note(Seen.Env(EnvEvent.BigLedgerResult(env.bigLedgerPeers))) *>
              supervisor
                .supervise(IO.sleep(60.seconds) *> note(Seen.Env(EnvEvent.BigLedgerTtl)))
                .as(Some(env.bigLedgerPeers -> 60.seconds))

        given Stateful[IO, GovernorState[Peer]] = new Stateful[IO, GovernorState[Peer]]:
          def monad = cats.Monad[IO]
          def get = stateRef.get
          def set(s: GovernorState[Peer]) = stateRef.set(s) *> note(Seen.State(s))
        given Ask[IO, GovernorConfig] = Ask.const[IO, GovernorConfig](
          GovernorConfig(
            Policy(),
            AssociationMode.of(false, false, env.useBootstrap, bootstrapEmpty = false)
          )
        )
        given Raise[IO, GovernorError] = new Raise[IO, GovernorError]:
          def functor = cats.Functor[IO]
          def raise[E2 <: GovernorError, B](e: E2): IO[B] =
            val fault = e match
              case GovernorError.InvariantBroken(vs) =>
                GovernorFault.InvariantBroken(vs)
              case GovernorError.BootstrapPeersCriticalTimeout =>
                GovernorFault.BootstrapPeersCriticalTimeout
            fatal.complete(fault) *> IO.never
        given Tell[IO, Output[Peer]] = new Tell[IO, Output[Peer]]:
          def functor = cats.Functor[IO]
          def tell(l: Output[Peer]) = note(Seen.Governor(l))

        // the scripts of the environment: targets and the judgement over time
        def play[A](timed: Timed[A], event: A => Event[Peer], noted: A => EnvEvent): IO[Unit] =
          def go(rest: List[(FiniteDuration, A)], elapsed: FiniteDuration): IO[Unit] = rest match
            case Nil => IO.unit
            case (at, value) :: more =>
              IO.sleep(at - elapsed) *> note(Seen.Env(noted(value))) *> events.offer(
                event(value)
              ) *>
                go(more, at)
          go(timed.all.toList, Duration.Zero)

        val environment =
          note(Seen.Env(EnvEvent.AddPeers(env.peers))) *>
            note(Seen.Env(EnvEvent.SetLocalRoots(roots.size))) *>
            note(Seen.Env(EnvEvent.SetPublicRoots(env.publicRoots.size))) *>
            play(env.targets, t => Event.TargetsRequested(t), t => EnvEvent.SetTargets(t)) &>
            play(env.judgement, j => Event.JudgementReported(j), j => EnvEvent.SetJudgement(j))

        given Ask[IO, PeerMetrics[Peer]] =
          Ask.const[IO, PeerMetrics[Peer]](PeerMetrics.empty[Peer]())
        val governor: IO[Nothing] =
          PeerGovernor.run[IO, Peer](actions, provider, events, supervisor)
        val running = IO.race(governor, environment *> IO.never[Unit])
        IO.race(running, IO.race(IO.sleep(horizon), overflow.get)).void *> fatal.tryGet
      }
      seen <- log.get
      overflowed <- overflow.tryGet.map(_.isDefined)
    yield Recording(seen, result, overflowed, env, horizon)

  /** More than this many records in a run is a governor that does not rest. */
  private val MaxRecorded = 200000
