// SPDX-License-Identifier: Apache-2.0
package lab.node.consensus

import lab.cbor.Bytes

/** Stake and VRF key of each pool for the leader schedule of one epoch. */
final case class PoolDistribution(stake: Map[Bytes, BigInt], vrf: Map[Bytes, Bytes]):
  lazy val total: BigInt = stake.values.sum
