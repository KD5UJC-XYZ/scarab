// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import cats.Monad
import cats.effect.Clock
import cats.mtl.Handle
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRules
import xyz.kd5ujc.scarab.ledger.{Header, NodeError, ValidationError}

/** The block rules, measured: how long a block took to validate and apply, and which rule family
  * refused it. The wrapped rules are called exactly as before and their result, or the error they
  * raise, passes through unchanged.
  */
object MeteredRules:
  def apply[F[_]: Monad: Clock, S](rules: BlockRules[F, S])(using
      metrics: Metrics[F],
      handle: Handle[F, NodeError]
  ): BlockRules[F, S] = new BlockRules[F, S]:
    def apply(state: S, header: Header, block: Bytes): F[S] =
      for
        began <- Clock[F].monotonic
        result <- handle.attempt(rules(state, header, block))
        took <- Clock[F].monotonic.map(_ - began)
        next <- result match
          case Right(applied) =>
            metrics.observe(Catalogue.blockApplySeconds, took.toNanos / 1e9).as(applied)
          case Left(error: ValidationError) =>
            metrics.inc(Catalogue.validationErrors, error.failure.rule) *>
              handle.raise[NodeError, S](error)
          case Left(other) => handle.raise[NodeError, S](other)
      yield next
