// SPDX-License-Identifier: Apache-2.0
package lab.node.consensus

import cats.syntax.all.*
import lab.Blake2b
import lab.cbor.Bytes
import lab.node.{EpochGeometry, Header, HeaderVrf}
import lab.vrf.{PraosLeaderThreshold as Leader, PraosVrfCertificate as Vrf}

enum Nonce:
  case Neutral
  case Hash(bytes: Bytes)

  /** `⭒`: Neutral is the identity; otherwise the hash of the concatenation. */
  def combine(other: Nonce): Nonce = (this, other) match
    case (Neutral, b)       => b
    case (a, Neutral)       => a
    case (Hash(a), Hash(b)) => Hash(Blake2b.hash256.hash(Bytes(a.value ++ b.value)))

  def render: Option[String] = this match
    case Neutral => None
    case Hash(b) => Some(b.hex)

/** Praos chain-dependent state (ouroboros-consensus `PraosState`). */
final case class PraosState(
    epoch: BigInt,
    evolving: Nonce,
    candidate: Nonce,
    epochNonce: Nonce,
    lab: Nonce,
    lastEpochBlock: Nonce,
    counters: Map[Bytes, BigInt],
    /** The epoch nonce of the epoch before (`praosStatePreviousEpochNonce`; it takes no part in
      * validation, node-to-client clients read it with the chain-dependent state).
      */
    previousEpochNonce: Nonce = Nonce.Neutral
)

/** Stake and VRF key of each pool for the leader schedule of one epoch. */
final case class PoolDistribution(stake: Map[Bytes, BigInt], vrf: Map[Bytes, Bytes]):
  lazy val total: BigInt = stake.values.sum

final case class PraosConfig(
    geometry: EpochGeometry,
    slotsPerKesPeriod: BigInt,
    maxKesEvolutions: BigInt,
    activeSlotsCoeff: Leader.Fraction,
    forks: HardForkConfig = HardForkConfig.default
)

/** Praos header validation and chain-dependent state evolution. */
object Praos:
  /** The chain starts with every nonce equal to the Shelley genesis hash. */
  def initial(shelleyGenesisHash: Bytes): PraosState =
    val n = Nonce.Hash(shelleyGenesisHash)
    PraosState(0, n, n, n, Nonce.Neutral, Nonce.Neutral, Map.empty, previousEpochNonce = n)

  /** Epoch transition: the new epoch nonce mixes the frozen candidate with the last block's lab
    * (and, for TPraos, the protocol parameters' extra entropy).
    */
  def tick(state: PraosState, epoch: BigInt, extraEntropy: Nonce = Nonce.Neutral): PraosState =
    if epoch <= state.epoch then state
    else
      state.copy(
        epoch = epoch,
        epochNonce = state.candidate.combine(state.lastEpochBlock).combine(extraEntropy),
        lastEpochBlock = state.lab,
        previousEpochNonce = state.epochNonce
      )

  /** Validate `header` (already ticked to its epoch) and return the updated state. */
  def update(
      state: PraosState,
      header: Header,
      pools: PoolDistribution,
      config: PraosConfig
  ): Either[String, PraosState] =
    validate(state, header, StakeView.Known(pools), config).map(_._1)

  /** As [[update]], also reporting how leadership was established. With an unknown stake view every
    * cryptographic check runs but the leader threshold is reported as unchecked.
    */
  def validate(
      state: PraosState,
      header: Header,
      stake: StakeView,
      config: PraosConfig,
      nonceWindow: BigInt = BigInt(-1)
  ): Either[String, (PraosState, LeaderCheck)] =
    val window =
      if nonceWindow >= 0 then nonceWindow else config.geometry.randomnessStabilisationWindow
    header match
      case h: Header.Shelley =>
        h.vrf match
          case HeaderVrf.Praos(cert) => updatePraos(state, h, cert, stake, config, window)
          case _: HeaderVrf.TPraos   => Left(s"${h.era} headers use TPraos, not Praos")
      case h => Left(s"${h.era} headers are not Praos headers")

  private def updatePraos(
      state: PraosState,
      header: Header.Shelley,
      vrf: lab.node.VrfCert,
      stakeView: StakeView,
      config: PraosConfig,
      window: BigInt
  ): Either[String, (PraosState, LeaderCheck)] =
    val pool = Blake2b.hash224.hash(header.issuerVkey)
    val vrfHash = Blake2b.hash256.hash(header.vrfVkey)
    for
      registered <- stakeView match
        case StakeView.Known(pools) =>
          for
            stake <- pools.stake
              .get(pool)
              .toRight(s"issuer ${pool.hex} has no stake in the pool distribution")
            registeredVrf <- pools.vrf
              .get(pool)
              .toRight(s"issuer ${pool.hex} is not a registered pool")
            _ <- Either.cond(
              vrfHash == registeredVrf,
              (),
              "VRF key is not the pool's registered key"
            )
          yield Some(stake -> pools.total)
        case StakeView.Unknown(registry) =>
          for
            keys <- registry.get(pool).toRight(s"issuer ${pool.hex} is not a registered pool")
            _ <- Either.cond(
              keys.contains(vrfHash),
              (),
              "VRF key is not the pool's registered key"
            )
          yield None
      input <- vrfInput(state.epochNonce, header.slot)
      output <- lab.node.Verification.praosVrf(input, header.vrfVkey, vrf.proof, vrf.output) match
        case Vrf.Result.VerifiedCertificate(o) => Right(o)
        case other                             => Left(s"VRF proof rejected: $other")
      leader <- registered match
        case Some((stake, total)) =>
          for
            value <- Leader.leaderValue(output)
            fraction <- Leader.Fraction.checked(stake, total)
            decision <- Leader.check(value, fraction, config.activeSlotsCoeff)
            _ <- Either.cond(
              decision == Leader.Decision.Eligible,
              (),
              s"issuer not elected for slot ${header.slot}: $decision"
            )
          yield LeaderCheck.Threshold
        case None => Right(LeaderCheck.Unchecked("stake distribution unknown (bootstrap)"))
      _ <- lab.node.Verification
        .opCert(header, config.slotsPerKesPeriod, config.maxKesEvolutions)
        .leftMap(_.message)
      _ <- OCertRules
        .checkCounter(
          state.counters,
          pool,
          header.opCert.sequence,
          known = true, // reaching here means the pool is in the stake view
          bounded = true
        )
        .leftMap(_.message)
    yield
      val evolving = state.evolving.combine(nonceValue(output))
      val nextEpoch = config.geometry.firstSlot(config.geometry.epochOf(header.slot) + 1)
      state.copy(
        evolving = evolving,
        candidate =
          if header.slot + window < nextEpoch then evolving
          else state.candidate,
        lab = header.prevHash.fold(Nonce.Neutral)(Nonce.Hash(_)),
        counters = state.counters.updated(pool, header.opCert.sequence)
      ) -> leader

  /** The VRF input of a slot under an epoch nonce. */
  def vrfInput(epochNonce: Nonce, slot: BigInt): Either[String, Vrf.Input] =
    for
      nonce <- epochNonce match
        case Nonce.Neutral => Right(Vrf.NeutralNonce)
        case Nonce.Hash(b) => Vrf.Hash32.fromBytes(b).leftMap(_.toString)
      s <- Vrf.Slot.fromBigInt(slot).leftMap(_.toString)
      input <- Vrf.Input.create(s, nonce).leftMap(_.toString)
    yield input

  /** A block's nonce contribution: the doubly hashed "N"-prefixed VRF output. */
  def nonceValue(output: Bytes): Nonce =
    Nonce.Hash(
      Blake2b.hash256.hash(Blake2b.hash256.hash(Bytes(Vector(0x4e.toByte) ++ output.value)))
    )
