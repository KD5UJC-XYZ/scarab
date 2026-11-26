// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.Eq
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.crypto.vrf.PraosLeaderThreshold as Leader
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, Nonce}

/** The inputs of the leader check of every slot of one epoch (#1101): the epoch and its slots, the
  * epoch nonce, the pool's stake and the total of the distribution that elects the epoch's leaders,
  * the active-slot coefficient and the pool's VRF key, by its verification key.
  * [[Leadership.check]] of a slot of the epoch reads these and the evaluations of that key, and
  * nothing else, so two equal values decide every slot alike. The signing key is not among them: an
  * evaluation borrows it from the credentials in force, if their key is the one named
  * ([[Leadership.Keys]], #1418).
  *
  * They are compared by value, as the first parameter list: the coefficient as an exact fraction.
  * The second list holds the coefficient the check takes, which has no equality of its own;
  * [[EpochInputs.of]] makes both lists from the same coefficient, so they cannot disagree.
  *
  * @param end
  *   The first slot of the next epoch.
  */
final case class EpochInputs private (
    epoch: BigInt,
    first: BigInt,
    end: BigInt,
    nonce: Nonce,
    stake: BigInt,
    total: BigInt,
    activeSlots: Rational,
    vrf: Bytes
)(val activeSlotsCoeff: Leader.Fraction)

object EpochInputs:
  /** The inputs of `epoch`, whose slots `geometry` places. */
  def of(
      geometry: EpochGeometry,
      epoch: BigInt,
      nonce: Nonce,
      stake: BigInt,
      total: BigInt,
      activeSlotsCoeff: Leader.Fraction,
      vrf: Bytes
  ): EpochInputs =
    new EpochInputs(
      epoch,
      geometry.firstSlot(epoch),
      geometry.firstSlot(epoch + 1),
      nonce,
      stake,
      total,
      Rational(activeSlotsCoeff.numerator, activeSlotsCoeff.denominator),
      vrf
    )(activeSlotsCoeff)

  given Eq[EpochInputs] = Eq.fromUniversalEquals
