// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.data.NonEmptyList
import xyz.kd5ujc.scarab.codec.{DomainError, Rational}
import xyz.kd5ujc.scarab.ledger.json.FromJson
import xyz.kd5ujc.scarab.ledger.state.{NonZero, PositiveUnitInterval, Word64}

/** `validateGenesis` of the Shelley genesis (cardano-ledger f649f975,
  * `eras/shelley/impl/src/Cardano/Ledger/Shelley/Genesis.hs:673-762`).
  *
  * Not called, because the reference node does not call it. cardano-node 11.1.3 has one protocol
  * configuration, `NodeProtocolConfigurationCardano` (`Cardano/Node/Types.hs:211-220`), and
  * instantiates only it (`Cardano/Node/Protocol.hs:22-45`), reading the Shelley genesis without
  * validating it (`Cardano/Node/Protocol/Cardano.hs:122-125`); the call in
  * `mkSomeConsensusProtocolShelley` (`Cardano/Node/Protocol/Shelley.hs:84`) is unreachable.
  * ouroboros-consensus 82ecba32 (the release that node builds with) calls it only inside
  * `assertWithMsg` (`Ouroboros/Consensus/Cardano/Node.hs:596-597`), a no-op unless the `asserts`
  * flag is set (`Ouroboros/Consensus/Util/Assert.hs:9-13`; default off,
  * `ouroboros-consensus.cabal:34-37`), which cardano-node sets only for its separate `asserted`
  * build (`nix/haskell.nix:415-424`). The node start matches the release node: decided in #474.
  */
object ShelleyGenesisValidation:
  /** `ValidationErr` (`Genesis.hs:677-681`). Epoch sizes are `EpochSize` (`Word64`). */
  enum ValidationErr extends DomainError:
    case EpochNotLongEnough(
        epochLength: Word64,
        securityParam: Word64,
        activeSlotsCoeff: Rational,
        minEpochLength: Word64
    )
    case MaxKESEvolutionsUnsupported(requested: Word64, supported: Long)
    case QuorumTooSmall(quorum: Word64, maxTooSmall: Word64, genesisNodes: Word64)

    /** Text after `describeValidationErr` (`Genesis.hs:683-715`); `EpochSize` shows as
      * `EpochSize n` (`Show via Quiet`, cardano-slotting 0.2.2.0 `Slot.hs:140-143`).
      */
    def description: String = this match
      case EpochNotLongEnough(es, secParam, asc, minEpochSize) =>
        "Epoch length is too low. Your epoch length of " + epochSize(es) +
          " does not meet the minimum epoch length of " + epochSize(minEpochSize) +
          " required by your choice of parameters for k and f: " + secParam.decimal + " and " +
          FromJson.showRational(asc) + ". Epochs should be at least 10k/f slots long."
      case MaxKESEvolutionsUnsupported(reqKES, supportedKES) =>
        "You have specified a 'maxKESEvolutions' higher" +
          " than that supported by the underlying algorithm." + " You requested " +
          reqKES.decimal + " but the algorithm supports a maximum of " + supportedKES
      case QuorumTooSmall(q, maxTooSmal, nodes) =>
        "You have specified an 'updateQuorum' which is" +
          " too small compared to the number of genesis nodes." + " You requested " + q.decimal +
          ", but given " + nodes.decimal + " genesis nodes 'updateQuorum' must be greater than " +
          maxTooSmal.decimal

  private def epochSize(w: Word64): String = s"EpochSize ${w.decimal}"

  /** The fields `validateGenesis` reads (`Genesis.hs:719-727`); `genesisNodes` is
    * `length sgGenDelegs`, the number of distinct genesis key hashes.
    */
  final case class Input(
      epochLength: Word64,
      securityParam: NonZero[Word64],
      activeSlotsCoeff: PositiveUnitInterval,
      maxKESEvolutions: Word64,
      updateQuorum: Word64,
      genesisNodes: Word64
  )

  /** `totalPeriodsKES (Proxy @(Sum6KES Ed25519DSIGN Blake2b_256))`: 2^6 periods (`Genesis.hs:752`).
    */
  val KesPeriods: Long = 64L

  /** Every failed check, in the reference's order: epoch length, KES evolutions, update quorum
    * (`Genesis.hs:728-762`).
    */
  def validate(input: Input): Either[NonEmptyList[ValidationErr], Unit] =
    NonEmptyList
      .fromList(
        List(checkEpochLength(input), checkKesEvolutions(input), checkQuorumSize(input)).flatten
      )
      .toLeft(())

  /** `minLength = ceiling (fromIntegral @_ @Double (3 * k) / fromRational f)`, rejected when it is
    * greater than the epoch length (`Genesis.hs:737-751`). `3 * k` is `Word64` and wraps before the
    * conversion; the conversions to `Double` round to nearest, ties to even, as GHC's do; `ceiling`
    * into `Word64` keeps the low 64 bits of the exact integer (`properFractionDouble`, GHC 9.6.7
    * `GHC/Float.hs:629-634, 656-689`, with no rewrite rule for `Word64`).
    */
  private def checkEpochLength(input: Input): Option[ValidationErr] =
    val k = input.securityParam.value
    val f = input.activeSlotsCoeff.rational
    val minLength = ceilingWord64(
      unsignedToDouble(Word64.wrappingMultiply(Word64.fromUnsigned(3L), k)) / rationalToDouble(f)
    )
    Option.when(Word64.order.gt(minLength, input.epochLength))(
      ValidationErr.EpochNotLongEnough(input.epochLength, k, f, minLength)
    )

  private def checkKesEvolutions(input: Input): Option[ValidationErr] =
    Option.unless(Word64.order.lteqv(input.maxKESEvolutions, Word64.fromUnsigned(KesPeriods)))(
      ValidationErr.MaxKESEvolutionsUnsupported(input.maxKESEvolutions, KesPeriods)
    )

  private def checkQuorumSize(input: Input): Option[ValidationErr] =
    val nodes = input.genesisNodes
    val maxTooSmal = Word64.fromUnsigned(java.lang.Long.divideUnsigned(nodes.bits, 2L))
    Option.unless(nodes.isZero || Word64.order.gt(input.updateQuorum, maxTooSmal))(
      ValidationErr.QuorumTooSmall(input.updateQuorum, maxTooSmal, nodes)
    )

  /** `fromIntegral :: Word64 -> Double`: the nearest double, ties to even. */
  private[ledger] def unsignedToDouble(w: Word64): Double = w.toBigInt.toDouble

  /** `fromRational :: Rational -> Double` (`fromRat''`, GHC 9.6.7 `GHC/Float.hs:1216-1281`) of a
    * positive rational: the nearest double, ties to even. The quotient is taken to at least 65 bits
    * and an inexact remainder set as a sticky low bit, so `BigInteger.doubleValue` (correctly
    * rounded) rounds it as the exact quotient would round.
    */
  private[ledger] def rationalToDouble(r: Rational): Double =
    val (n, d) = (r.numerator.bigInteger, r.denominator.bigInteger)
    val shift = 65 - (n.bitLength - d.bitLength)
    val (num, den) = if shift >= 0 then (n.shiftLeft(shift), d) else (n, d.shiftLeft(-shift))
    val quotient = num.divideAndRemainder(den)
    val sticky = if quotient(1).signum == 0 then quotient(0) else quotient(0).setBit(0)
    Math.scalb(sticky.doubleValue, -shift)

  /** `ceiling :: Double -> Word64` of a finite, non-negative double: the exact ceiling, its low 64
    * bits.
    */
  private[ledger] def ceilingWord64(x: Double): Word64 =
    Word64.fromUnsigned(new java.math.BigDecimal(Math.ceil(x)).toBigInteger.longValue)
