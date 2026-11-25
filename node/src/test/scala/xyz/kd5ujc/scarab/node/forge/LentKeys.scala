// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.Sync
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover
import xyz.kd5ujc.scarab.ledger.{ForgeError, VrfCert}

/** VRF keys a test holds, lent as the forger lends the key in force ([[Leadership.Keys]]) but with
  * no credentials and no leases: inputs that name one of them by its verification key are evaluated
  * with it, `evaluated` wrapping each of its evaluations; others are not.
  */
object LentKeys:
  def of[F[_]: Sync](keys: Draft03Prover.SigningKey*): Leadership.Keys[F] =
    wrapped(keys*)(identity)

  def wrapped[F[_]: Sync](keys: Draft03Prover.SigningKey*)(
      evaluated: Leadership.Evaluator => Leadership.Evaluator
  ): Leadership.Keys[F] =
    new Leadership.Keys[F]:
      def evaluating[A](vrf: Bytes)(f: Leadership.Evaluator => A): F[Option[A]] =
        keys
          .find(_.publicKey == vrf)
          .traverse(k => Sync[F].blocking(f(evaluated(Leadership.Evaluator.of(k)))))

  /** [[Leadership.check]] of `slot` under `inputs`, with `key`, the key they name. */
  def check(
      slot: BigInt,
      inputs: EpochInputs,
      key: Draft03Prover.SigningKey
  ): Either[ForgeError, VrfCert] =
    Leadership.check(slot, inputs, Leadership.Evaluator.of(key))
