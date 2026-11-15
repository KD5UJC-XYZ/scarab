// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Resource
import cats.effect.kernel.Sync
import cats.effect.std.Dispatcher
import cats.effect.kernel.Async
import cats.syntax.all.*
import sun.misc.{Signal, SignalHandler}

/** Operating-system signals as effects. Wired in `Main` only: the rest of the node sees the effect
  * a signal triggers, not the signal.
  */
object Signals:
  /** What a hang-up does while [[hangup]] is in force. */
  private def handler(run: => Unit): SignalHandler = _ => run

  /** Run `onHangup` each time the process gets SIGHUP, for as long as the resource is held, and put
    * the previous handler back afterwards. The Haskell node reloads its block-forging credentials
    * and topology on SIGHUP (cardano-node 11.1.3, `Cardano/Node/Run.hs`: `installSigHUPHandler`,
    * line 596), and before that, from the start of its node run, keeps an initial handler that
    * reads the topology again and says the credentials are not (lines 412-435); this is the hook
    * for both, the run's handler in force over the initial one. A platform without the signal
    * (Windows) just never calls it.
    *
    * The effect runs on its own fiber: a slow reload does not hold the signal thread, and two
    * signals in a row run one after the other (`Dispatcher.sequential`).
    */
  def hangup[F[_]: Async](onHangup: F[Unit]): Resource[F, Unit] = on("HUP", onHangup)

  /** Run `onInterrupt` when the process gets SIGINT, for as long as the resource is held, in place
    * of the JVM's own handler (which would start the shutdown hooks): the caller ends the run
    * itself, as the reference's main thread ends on `UserInterrupt`. SIGTERM keeps the JVM's
    * handler.
    */
  def interrupt[F[_]: Async](onInterrupt: F[Unit]): Resource[F, Unit] = on("INT", onInterrupt)

  /** The handler that runs `run` for each signal. A kept site of EFF-5's one bridge: the JVM calls
    * a `SignalHandler` on its own signal thread, outside `F`, and takes nothing back, so the
    * handler hands the effect to the dispatcher, which [[on]]'s resource owns.
    */
  @SuppressWarnings(Array("xyz.kd5ujc.scarab.warts.SystemTimeAndUnsafeRun"))
  private def bridge[F[_]](dispatcher: Dispatcher[F], run: F[Unit]): SignalHandler =
    handler(dispatcher.unsafeRunAndForget(run))

  private def on[F[_]: Async](name: String, run: F[Unit]): Resource[F, Unit] =
    Dispatcher.sequential[F].flatMap { dispatcher =>
      Resource
        .make(
          Sync[F].delay(
            Either.catchNonFatal(
              Signal.handle(new Signal(name), bridge(dispatcher, run))
            )
          )
        )(previous =>
          previous.traverse_(old => Sync[F].delay { Signal.handle(new Signal(name), old); () })
        )
        .void
    }
