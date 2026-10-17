// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.{Applicative, Monad}
import cats.effect.Ref
import cats.mtl.{Ask, Stateful}

/** `Stateful` backed by a concurrent `Ref`. Each operation is atomic; `modify` is a single
  * compare-and-set, so concurrent readers never observe a partially applied update.
  */
object RefStateful:
  def apply[F[_]: Monad, S](ref: Ref[F, S]): Stateful[F, S] = new Stateful[F, S]:
    def monad: Monad[F] = Monad[F]
    def get: F[S] = ref.get
    def set(s: S): F[Unit] = ref.set(s)
    override def modify(f: S => S): F[Unit] = ref.update(f)
    override def inspect[A](f: S => A): F[A] = Monad[F].map(ref.get)(f)

  /** `Ask` backed by a `Ref`: the reader of a cell somebody else writes. */
  def reader[F[_]: Applicative, S](ref: Ref[F, S]): Ask[F, S] = new Ask[F, S]:
    def applicative: Applicative[F] = Applicative[F]
    def ask[E2 >: S]: F[E2] = Applicative[F].widen(ref.get)
