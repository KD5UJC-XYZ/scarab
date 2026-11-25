// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.Sync
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover
import xyz.kd5ujc.scarab.ledger.{ForgeError, VrfCert}

/** VRF keys a test holds, lent as the forger lends the key in force ([[Leadership.Keys]]) but with
  * no credentials and no leases: the first of them is the key in force, and inputs that name one of
  * them by its verification key are evaluated with it, `evaluated` wrapping each of its
  * evaluations; others are not.
  */
object LentKeys:
  def of[F[_]: Sync](keys: Draft03Prover.SigningKey*): Leadership.Keys[F] =
    wrapped(keys*)(identity)

  def wrapped[F[_]: Sync](keys: Draft03Prover.SigningKey*)(
      evaluated: Leadership.Evaluator => Leadership.Evaluator
  ): Leadership.Keys[F] =
    new Leadership.Keys[F]:
      def lent[A](f: (Bytes, Leadership.Evaluator) => A): F[Option[A]] =
        keys.headOption.traverse(k =>
          Sync[F].blocking(f(k.publicKey, evaluated(Leadership.Evaluator.of(k))))
        )
      def evaluating[A](vrf: Bytes)(f: Leadership.Evaluator => A): F[Option[A]] =
        keys
          .find(_.publicKey == vrf)
          .traverse(k => Sync[F].blocking(f(evaluated(Leadership.Evaluator.of(k)))))

  /** [[Leadership.check]] of `slot` under `inputs`, with the one of `keys` whose verification key
    * the inputs carry: inputs that name none of them fail the test, so that wrong bytes in the
    * inputs never pass for the key the test meant.
    */
  def check(
      slot: BigInt,
      inputs: EpochInputs,
      keys: Draft03Prover.SigningKey*
  ): Either[ForgeError, VrfCert] =
    keys
      .find(_.publicKey == inputs.vrf)
      .fold(
        throw new AssertionError(s"the inputs name the VRF key ${inputs.vrf.hex}, not a test's")
      )(k => Leadership.check(slot, inputs, Leadership.Evaluator.of(k)))
