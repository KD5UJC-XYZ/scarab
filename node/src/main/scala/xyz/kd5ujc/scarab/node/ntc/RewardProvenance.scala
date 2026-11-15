// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.{FloatWidth, Rational, Value}
import xyz.kd5ujc.scarab.ledger.PoolParams
import xyz.kd5ujc.scarab.ledger.state.{
  BlocksMade,
  Coin,
  KeyHash,
  ProtVer,
  RewardBlockCount,
  Sorted,
  StakePool,
  Word64
}
import xyz.kd5ujc.scarab.models.runtime.{Codec, Decoded, Node, Writer}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, Record, Wire}

// The answer of `GetRewardProvenance`: the types of `Cardano/Ledger/Shelley/RewardProvenance.hs`
// (cardano-ledger f649f975), each written as the record of its fields in declaration order
// (`Rec`), with the ledger's encoders of the field types.

/** `RewardProvenancePool` (`RewardProvenance.hs:30-55`, cardano-ledger f649f975): one pool's part
  * of a reward calculation.
  */
private[ntc] final case class RewardProvenancePool(
    poolBlocksP: RewardBlockCount,
    sigmaP: Rational,
    sigmaAP: Rational,
    ownerStakeP: Coin,
    stakePoolParamsP: PoolParams,
    pledgeRatioP: Rational,
    maxPP: Coin,
    appPerfP: Rational,
    poolRP: Coin,
    lRewardP: Coin
)

/** `Desirability` (`RewardProvenance.hs:73-77`, cardano-ledger f649f975): a pool's desirability
  * score and hit rate.
  */
private[ntc] final case class Desirability(desirabilityScore: Double, hitRateEstimate: Double)

/** `RewardProvenance` (`RewardProvenance.hs:95-135`, cardano-ledger f649f975): the intermediate
  * values of a reward calculation.
  */
private[ntc] final case class RewardProvenance(
    spe: Word64,
    blocks: BlocksMade,
    maxLL: Coin,
    deltaR1: Coin,
    deltaR2: Coin,
    r: Coin,
    totalStake: Coin,
    blocksCount: BigInt,
    d: Rational,
    expBlocks: BigInt,
    eta: Rational,
    rPot: Coin,
    deltaT1: Coin,
    activeStake: Coin,
    pools: SortedMap[KeyHash[StakePool], RewardProvenancePool],
    desirabilities: SortedMap[KeyHash[StakePool], Desirability]
)

private[ntc] object RewardProvenance:
  import HaskellStateCodecs.{blocksMade, coin, keyHash, poolParams, rewardBlockCount}

  /** `instance Default RewardProvenance` (`RewardProvenance.hs:150-168`, cardano-ledger f649f975):
    * every number zero, every rational `0 % 1` and every map empty.
    */
  val default: RewardProvenance = RewardProvenance(
    Word64.zero,
    BlocksMade.empty,
    Coin.zero,
    Coin.zero,
    Coin.zero,
    Coin.zero,
    Coin.zero,
    BigInt(0),
    Rational.zero,
    BigInt(0),
    Rational.zero,
    Coin.zero,
    Coin.zero,
    Coin.zero,
    Sorted.empty,
    Sorted.empty
  )

  /** A `Double` as cborg's `encodeDouble` writes it: the eight bytes of a binary64, as the
    * performance estimate of `GetRewardInfoPools` is written. The node only writes a provenance, so
    * the decoder reads that form only.
    */
  private val double: Codec[Double] = new Codec[Double]:
    def decode(n: Node): Decoded[Double] = n.value match
      case Value.Float(x, FloatWidth.Double) => Right(x)
      case _                                 => Left(Codec.mismatch("double", n))
    def encode(a: Double, out: Writer): Unit = out.float64(a)

  /** `EncCBOR RewardProvenancePool` (`RewardProvenance.hs:242-255`, cardano-ledger f649f975). */
  def pool(using ProtVer): Codec[RewardProvenancePool] =
    Record.of[RewardProvenancePool](
      (
        rewardBlockCount,
        Wire.ratio,
        Wire.ratio,
        coin,
        poolParams,
        Wire.ratio,
        coin,
        Wire.ratio,
        coin,
        coin
      )
    )

  /** `EncCBOR Desirability` (`RewardProvenance.hs:235-237`, cardano-ledger f649f975). */
  val desirability: Codec[Desirability] = Record.of[Desirability]((double, double))

  /** `EncCBOR RewardProvenance` (`RewardProvenance.hs:272-291`, cardano-ledger f649f975) at the
    * given encoding version: a `Rational` takes tag 30 from version 9 only (`encodeRatio`), and the
    * maps are the ledger's (`encodeMap`).
    */
  def codec(using ProtVer): Codec[RewardProvenance] =
    Record.of[RewardProvenance](
      (
        Wire.word64,
        blocksMade,
        coin,
        coin,
        coin,
        coin,
        coin,
        Wire.integer,
        Wire.ratio,
        Wire.integer,
        Wire.ratio,
        coin,
        coin,
        coin,
        Wire.mapOf(keyHash[StakePool], pool),
        Wire.mapOf(keyHash[StakePool], desirability)
      )
    )
