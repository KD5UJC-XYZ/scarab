// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.codec.Rational

/** A genesis delegation: the delegate's cold key hash (blake2b-224) and its VRF key hash
  * (blake2b-256).
  */
final case class GenDelegPair(delegate: Bytes, vrf: Bytes)

/** What the node knows about pool stake when it validates a header. */
enum StakeView:
  /** The ticked stake distribution of the epoch: leader thresholds are checked. */
  case Known(pools: PoolDistribution)

  /** No stake distribution (bootstrap): pool -> VRF key hashes it has registered. Every
    * cryptographic check still runs; the leader threshold cannot.
    */
  case Unknown(registered: Map[Bytes, Set[Bytes]])

/** How a header's leadership was established. Never silently "valid": a header whose threshold was
  * not evaluated says so.
  */
enum LeaderCheck:
  /** Active overlay slot of this genesis key: no threshold applies. */
  case Overlay(genesisKey: Bytes)

  /** The VRF output was compared with the pool's stake-based threshold. */
  case Threshold

  /** Every cryptographic check passed but the threshold was not evaluated. */
  case Unchecked(reason: LeaderCheck.UncheckedReason)

  def label: String = this match
    case Overlay(_)   => "overlay"
    case Threshold    => "threshold"
    case Unchecked(_) => "unchecked"

/** The slice of the ledger that TPraos header validation reads (`LedgerView`). */
final case class LedgerView(
    d: Rational,
    extraEntropy: Nonce,
    genDelegs: Map[Bytes, GenDelegPair],
    stake: StakeView
)

object LeaderCheck:
  enum UncheckedReason:
    case BootstrapStakeUnknown
    def message: String = "stake distribution unknown (bootstrap)"
