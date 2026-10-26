// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import scala.util.control.NonFatal

/** Retain teardown defects even when cancellation interrupts partial connection acquisition.
  * Wrapping only `allocatedCase` misses releases run inside that acquisition. This transformation
  * preserves allocation, polling and each release's exit case; suspended binds keep it stack safe.
  * The connection owner publishes the retained values after every resource has been released.
  */
private[node] object ConnectionResources:
  def retainReleaseDefects[F[_]: Async, A](resource: Resource[F, A])(
      retain: Fatal.Defect => F[Unit]
  ): Resource[F, A] =
    val F = Async[F]
    Resource.suspend[F, A](F.delay[Resource[F, A]] {
      resource match
        case Resource.Allocate(acquire) =>
          Resource.applyFull[F, A] { poll =>
            acquire(poll).map { (value, release) =>
              value -> ((exit: Resource.ExitCase) =>
                F.defer(release(exit)).handleErrorWith {
                  case NonFatal(cause) =>
                    retain(Fatal.Defect(cause)) *> (exit match
                      case Resource.ExitCase.Succeeded => F.raiseError[Unit](cause)
                      case _                           => F.unit)
                  case other => F.raiseError(other)
                }
              )
            }
          }
        case Resource.Bind(source, next) =>
          retainReleaseDefects(source)(retain).flatMap(value =>
            retainReleaseDefects(next(value))(retain)
          )
        case Resource.Pure(value)  => Resource.pure[F, A](value)
        case Resource.Eval(effect) => Resource.eval(effect)
    })
