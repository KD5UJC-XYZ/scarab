// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import cats.{Defer, MonadThrow}
import cats.effect.Clock
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRules
import xyz.kd5ujc.scarab.ledger.{ChainError, Header, StorageError, ValidationError}

/** The block rules, measured: how long a block took to validate and apply, and which rule family
  * refused it. The wrapped rules are called exactly as before and their result, or the error they
  * raise, passes through unchanged.
  */
object MeteredRules:
  def apply[F[_]: MonadThrow: Clock: Defer, S](rules: BlockRules[F, S])(using
      metrics: Metrics[F]
  ): BlockRules[F, S] = new BlockRules[F, S]:
    def apply(state: S, header: Header, block: Bytes)(using
        validation: Raise[F, ValidationError],
        storage: Raise[F, StorageError],
        chain: Raise[F, ChainError]
    ): F[S] =
      for
        began <- Clock[F].monotonic
        result <- Defer[F].defer {
          Handle
            .allow[ValidationError](
              Defer[F].defer(rules(state, header, block)).map(_.asRight[ValidationError])
            )
            .rescue(error => error.asLeft[S].pure[F])
        }
        took <- Clock[F].monotonic.map(_ - began)
        next <- result match
          case Right(applied) =>
            metrics.observe(Catalogue.blockApplySeconds, took.toNanos / 1e9).as(applied)
          case Left(error) =>
            metrics.inc(Catalogue.validationErrors, error.failure.rule) *>
              validation.raise[ValidationError, S](error)
      yield next
