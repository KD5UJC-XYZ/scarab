// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, LedgerGlobals}

/** The constants of a network that the era-indexed rules read from their environment: Haskell's
  * `Globals` (`Cardano/Ledger/BaseTypes.hs:761-789`), the reader environment of `ShelleyBase =
  * ReaderT Globals Identity` (`BaseTypes.hs:796`), which every rule of the ledger runs in.
  *
  * It is made of the node's two records rather than a third copy of their fields: the ledger's own
  * constants ([[LedgerGlobals]]: the supply, the network, the update quorum) and the era history
  * ([[EpochGeometry]]: `epochInfo`, the stability windows and the security parameter). The pairing
  * is by value, so a chain that learns its first Shelley epoch while it syncs ([[resolved]])
  * changes both together through [[resolved]].
  */
final case class Globals(ledger: LedgerGlobals, geometry: EpochGeometry):
  /** `networkId`. */
  def networkId: Int = ledger.network

  /** `quorum`: for update-system votes and MIR certificates. */
  def quorum: Int = ledger.updateQuorum

  /** `maxLovelaceSupply`. */
  def maxLovelaceSupply: BigInt = ledger.maxSupply

  /** `activeSlotCoeff`, exact. */
  def activeSlotCoeff: Rational = ledger.activeSlotsCoeff

  /** `securityParameter`: the most blocks that can be rolled back. */
  def securityParameter: Int = geometry.securityParam

  /** `stabilityWindow` (3k/f): protocol updates must be submitted at least twice this before an
    * epoch boundary.
    */
  def stabilityWindow: BigInt = geometry.stabilityWindow

  /** `randomnessStabilisationWindow` (4k/f): slots before the end of the epoch at which the
    * candidate nonce stops updating.
    */
  def randomnessStabilisationWindow: BigInt = geometry.randomnessStabilisationWindow

  /** `epochInfo`, the two functions the rules use: the epoch of a slot, and the first slot of an
    * epoch.
    */
  def epochOf(slot: BigInt): BigInt = geometry.epochOf(slot)
  def firstSlot(epoch: BigInt): BigInt = geometry.firstSlot(epoch)

  /** The same constants once the chain is known to enter Shelley at `firstShelleyEpoch`. */
  def resolved(firstShelleyEpoch: BigInt): Globals =
    Globals(ledger.copy(firstEpoch = firstShelleyEpoch), geometry.resolved(firstShelleyEpoch))
