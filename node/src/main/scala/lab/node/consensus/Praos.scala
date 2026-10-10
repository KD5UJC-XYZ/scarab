// SPDX-License-Identifier: Apache-2.0
package lab.node.consensus

import cats.syntax.all.*
import lab.Blake2b
import lab.cbor.Bytes
import lab.kes.Sum6Kes
import lab.node.{EpochGeometry, Header}
import lab.opcert.OperationalCertificate
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
    counters: Map[Bytes, BigInt]
)

/** Stake and VRF key of each pool for the leader schedule of one epoch. */
final case class PoolDistribution(stake: Map[Bytes, BigInt], vrf: Map[Bytes, Bytes]):
  lazy val total: BigInt = stake.values.sum

final case class PraosConfig(
    geometry: EpochGeometry,
    slotsPerKesPeriod: BigInt,
    maxKesEvolutions: BigInt,
    activeSlotsCoeff: Leader.Fraction
)

/** Praos header validation and chain-dependent state evolution. */
object Praos:
  /** The chain starts with every nonce equal to the Shelley genesis hash. */
  def initial(shelleyGenesisHash: Bytes): PraosState =
    val n = Nonce.Hash(shelleyGenesisHash)
    PraosState(0, n, n, n, Nonce.Neutral, Nonce.Neutral, Map.empty)

  /** Epoch transition: the new epoch nonce mixes the frozen candidate with the last block's lab. */
  def tick(state: PraosState, epoch: BigInt): PraosState =
    if epoch <= state.epoch then state
    else
      state.copy(
        epoch = epoch,
        epochNonce = state.candidate.combine(state.lastEpochBlock),
        lastEpochBlock = state.lab
      )

  /** Validate `header` (already ticked to its epoch) and return the updated state. */
  def update(
      state: PraosState,
      header: Header,
      pools: PoolDistribution,
      config: PraosConfig
  ): Either[String, PraosState] =
    val pool = Blake2b.hash224.hash(header.issuerVkey)
    val epochNonce = state.epochNonce match
      case Nonce.Neutral => Right(Vrf.NeutralNonce)
      case Nonce.Hash(b) => Vrf.Hash32.fromBytes(b).leftMap(_.toString)
    for
      stake <- pools.stake
        .get(pool)
        .toRight(s"issuer ${pool.hex} has no stake in the pool distribution")
      vrfHash <- pools.vrf.get(pool).toRight(s"issuer ${pool.hex} is not a registered pool")
      _ <- Either.cond(
        Blake2b.hash256.hash(header.vrfVkey) == vrfHash,
        (),
        "VRF key is not the pool's registered key"
      )
      nonce <- epochNonce
      slot <- Vrf.Slot.fromBigInt(header.slot).leftMap(_.toString)
      input <- Vrf.Input.create(slot, nonce).leftMap(_.toString)
      output <- Vrf.verify(input, header.vrfVkey, header.vrfProof, header.vrfOutput) match
        case Vrf.Result.VerifiedCertificate(o) => Right(o)
        case other                             => Left(s"VRF proof rejected: $other")
      value <- Leader.leaderValue(output)
      fraction <- Leader.Fraction.checked(stake, pools.total)
      decision <- Leader.check(value, fraction, config.activeSlotsCoeff)
      _ <- Either.cond(
        decision == Leader.Decision.Eligible,
        (),
        s"issuer not elected for slot ${header.slot}: $decision"
      )
      kesPeriod = header.slot / config.slotsPerKesPeriod
      start = header.opCert.kesPeriod
      _ <- Either.cond(
        start <= kesPeriod && kesPeriod < start + config.maxKesEvolutions,
        (),
        s"KES period $kesPeriod outside opcert window [$start, ${start + config.maxKesEvolutions})"
      )
      kes <- Sum6Kes
        .verify(
          header.opCert.hotVkey,
          (kesPeriod - start).toInt,
          header.bodyRaw,
          header.kesSignature
        )
        .leftMap(_.toString)
      _ <- Either.cond(
        kes == Sum6Kes.Result.SuppliedMessageSignatureVerified,
        (),
        "KES signature rejected"
      )
      oc <- OperationalCertificate
        .verify(
          header.issuerVkey,
          header.opCert.hotVkey,
          header.opCert.sequence,
          start,
          header.opCert.sigma
        )
        .leftMap(_.toString)
      _ <- Either.cond(
        oc == OperationalCertificate.Result.OperationalCertificateSignatureVerified,
        (),
        "opcert signature rejected"
      )
      current = state.counters.getOrElse(pool, BigInt(0))
      n = header.opCert.sequence
      _ <- Either.cond(
        current <= n && n <= current + 1,
        (),
        s"opcert counter $n not in [$current, ${current + 1}]"
      )
    yield
      val evolving = state.evolving.combine(nonceValue(output))
      val nextEpoch = config.geometry.firstSlot(config.geometry.epochOf(header.slot) + 1)
      state.copy(
        evolving = evolving,
        candidate =
          if header.slot + config.geometry.randomnessStabilisationWindow < nextEpoch then evolving
          else state.candidate,
        lab = header.prevHash.fold(Nonce.Neutral)(Nonce.Hash(_)),
        counters = state.counters.updated(pool, n)
      )

  /** A block's nonce contribution: the doubly hashed "N"-prefixed VRF output. */
  def nonceValue(output: Bytes): Nonce =
    Nonce.Hash(
      Blake2b.hash256.hash(Blake2b.hash256.hash(Bytes(Vector(0x4e.toByte) ++ output.value)))
    )
