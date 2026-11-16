// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.Eq
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.crypto.vrf.{Draft03Prover, PraosLeaderThreshold as Leader}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, Nonce}

/** The inputs of the leader check of every slot of one epoch (#1101): the epoch and its slots, the
  * epoch nonce, the pool's stake and the total of the distribution that elects the epoch's leaders,
  * the active-slot coefficient and the pool's VRF key. [[Leadership.check]] of a slot of the epoch
  * reads these and nothing else, so two equal values decide every slot alike.
  *
  * They are compared by value, as the first parameter list: the coefficient as an exact fraction
  * and the VRF key by its public key. The second list holds the coefficient and the key that the
  * check takes, which have no equality of their own; [[EpochInputs.of]] makes both lists from the
  * same coefficient and key, so they cannot disagree.
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
)(val activeSlotsCoeff: Leader.Fraction, val key: Draft03Prover.SigningKey)

object EpochInputs:
  /** The inputs of `epoch`, whose slots `geometry` places. */
  def of(
      geometry: EpochGeometry,
      epoch: BigInt,
      nonce: Nonce,
      stake: BigInt,
      total: BigInt,
      activeSlotsCoeff: Leader.Fraction,
      key: Draft03Prover.SigningKey
  ): EpochInputs =
    new EpochInputs(
      epoch,
      geometry.firstSlot(epoch),
      geometry.firstSlot(epoch + 1),
      nonce,
      stake,
      total,
      Rational(activeSlotsCoeff.numerator, activeSlotsCoeff.denominator),
      key.publicKey
    )(activeSlotsCoeff, key)

  given Eq[EpochInputs] = Eq.fromUniversalEquals
