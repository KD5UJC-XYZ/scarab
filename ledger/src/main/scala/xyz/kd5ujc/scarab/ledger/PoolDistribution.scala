// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes

/** Stake and VRF key of each pool for the leader schedule of one epoch. */
final case class PoolDistribution(stake: Map[Bytes, BigInt], vrf: Map[Bytes, Bytes]):
  lazy val total: BigInt = stake.values.sum
