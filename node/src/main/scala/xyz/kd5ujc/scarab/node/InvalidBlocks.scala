// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Ref}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Header, ValidationError}

/** ChainDB's known-invalid map and fingerprint (consensus 82ecba329, ChainSel.hs:1360–1370). The
  * original typed cause is retained independently of the body cache and its sending session.
  * Waiters observe one coherent revision and signal, so recording an invalid block cannot race
  * between reading its fingerprint and subscribing to the next invalidity change.
  */
final class InvalidBlocks[F[_]: Async] private (
    state: Ref[F, InvalidBlocks.State[F]],
    collect: BigInt => F[Unit]
):
  import InvalidBlocks.*
  private val F = Async[F]

  def snapshot: F[Snapshot] = state.get.map(_.snapshot)

  def lookup(hash: Bytes): F[Option[ValidationError]] =
    state.get.map(_.entries.get(hash).map(_.refusal))

  def record(header: Header, refusal: ValidationError): F[Unit] =
    F.uncancelable { _ =>
      Deferred[F, Unit].flatMap { signal =>
        state
          .modify { before =>
            before.copy(
              entries = before.entries.updated(header.hash, Invalid(header.slot, refusal)),
              revision = before.revision + 1,
              changed = signal
            ) -> before.changed
          }
          .flatMap(_.complete(()).void)
      }
    }

  /** Background.hs:447 retains invalid blocks at the cutoff. Its fmap updates the map without
    * changing the fingerprint: garbage collection does not trigger the invalid-block rejector.
    */
  def pruneBefore(immutableSlot: BigInt): F[Unit] =
    F.uncancelable { _ =>
      collect(immutableSlot) *> state.update(current =>
        current.copy(entries =
          current.entries.filter((_, invalid) => invalid.slot >= immutableSlot)
        )
      )
    }

  def awaitChange(previous: BigInt): F[Snapshot] =
    F.tailRecM(()) { _ =>
      state.get.flatMap { current =>
        if current.revision != previous then F.pure(Right(current.snapshot))
        else current.changed.get.as(Left(()))
      }
    }

object InvalidBlocks:
  final case class Snapshot(revision: BigInt, entries: Map[Bytes, ValidationError])
  private final case class Invalid(slot: BigInt, refusal: ValidationError)
  private final case class State[F[_]](
      revision: BigInt,
      entries: Map[Bytes, Invalid],
      changed: Deferred[F, Unit]
  ):
    def snapshot: Snapshot = Snapshot(revision, entries.view.mapValues(_.refusal).toMap)

  def create[F[_]: Async]: F[InvalidBlocks[F]] = create(_ => Async[F].unit)

  private[node] def create[F[_]: Async](collect: BigInt => F[Unit]): F[InvalidBlocks[F]] =
    Deferred[F, Unit].flatMap { signal =>
      Ref.of[F, State[F]](State(0, Map.empty, signal)).map(new InvalidBlocks(_, collect))
    }
