// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Monad
import cats.effect.Ref
import cats.mtl.Stateful

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
