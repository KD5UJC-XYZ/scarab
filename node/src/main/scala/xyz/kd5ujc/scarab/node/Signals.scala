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
    * line 596); this is the hook for it. A platform without the signal (Windows) just never calls
    * it.
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

  private def on[F[_]: Async](name: String, run: F[Unit]): Resource[F, Unit] =
    Dispatcher.sequential[F].flatMap { dispatcher =>
      Resource
        .make(
          Sync[F].delay(
            Either.catchNonFatal(
              Signal.handle(new Signal(name), handler(dispatcher.unsafeRunAndForget(run)))
            )
          )
        )(previous =>
          previous.traverse_(old => Sync[F].delay { Signal.handle(new Signal(name), old); () })
        )
        .void
    }
