// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Resource}
import cats.mtl.Handle
import xyz.kd5ujc.scarab.ledger.NodeError

/** A live enclosing node scope for test services whose lifetime spans several sessions. Rule and
  * store operations use each caller's capability; the session also catches this enclosing scope for
  * services that share its lifetime. In `Main` it is the outer `Handle.allow[NodeError]`.
  */
object NodeScope:
  /** A scope that lives as long as the resource. */
  def resource: Resource[IO, Handle[IO, NodeError]] =
    Resource
      .eval(Deferred[IO, Handle[IO, NodeError]])
      .flatMap { handle =>
        Handle
          .allowF[IO, NodeError](h => handle.complete(h) *> IO.never[Unit])
          .rescue(e => IO.raiseError(new RuntimeException(e.message)))
          .background
          .evalMap(_ => handle.get)
      }

  /** A scope for the rest of the run of the test's runtime: for a fixture made in a `for` that has
    * no place for a resource.
    */
  def forTest: IO[Handle[IO, NodeError]] = resource.allocated.map(_._1)
