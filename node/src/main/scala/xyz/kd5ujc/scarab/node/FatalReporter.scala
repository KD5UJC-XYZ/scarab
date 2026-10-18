// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Concurrent, Ref}
import cats.effect.syntax.all.*
import cats.syntax.all.*

/** The first terminal failure is logged before shutdown is signalled. Concurrent observers of the
  * same failed connection cannot report a second fatal event.
  */
trait FatalReporter[F[_]]:
  def report(fault: Fatal): F[Unit]

object FatalReporter:
  def create[F[_]: Concurrent](log: Fatal => F[Unit], halt: Fatal => F[Unit]): F[FatalReporter[F]] =
    Ref.of[F, Boolean](false).map { claimed =>
      new FatalReporter[F]:
        def report(fault: Fatal): F[Unit] =
          Concurrent[F].uncancelable { poll =>
            claimed.getAndSet(true).flatMap {
              case true => Concurrent[F].unit
              // Claiming and installing the finalizer are atomic with respect to cancellation.
              // A failed or cancelled logger must still wake the owner that shuts the node down.
              case false => poll(log(fault)).guarantee(halt(fault))
            }
          }
    }
