// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import xyz.kd5ujc.scarab.ledger.{
  PraosError,
  LeaderCheck,
  Nonce,
  OCertRules,
  PoolDistribution,
  StakeView
}
import xyz.kd5ujc.scarab.ledger.state.{BlockIssuer, KeyHash, SlotNo, VocabError, Word64, WithOrigin}
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, PraosState, ProtocolState}
import xyz.kd5ujc.scarab.protocol.Era
import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, Header, HeaderVrf}
import xyz.kd5ujc.scarab.crypto.vrf.{PraosLeaderThreshold as Leader, PraosVrfCertificate as Vrf}

/** @param verdicts
  *   Where the rules get signature verdicts from (see [[xyz.kd5ujc.scarab.ledger.Verdicts]]).
  */
final case class PraosConfig(
    geometry: EpochGeometry,
    slotsPerKesPeriod: BigInt,
    maxKesEvolutions: BigInt,
    activeSlotsCoeff: Leader.Fraction,
    forks: HardForkConfig = HardForkConfig.default,
    verdicts: xyz.kd5ujc.scarab.ledger.Verdicts = xyz.kd5ujc.scarab.ledger.Verdicts.Direct
)

/** Praos header validation and chain-dependent state evolution. */
object Praos:
  /** Epoch transition: the new epoch nonce mixes the frozen candidate with the last block's lab
    * (and, for TPraos, the protocol parameters' extra entropy). The state is first put in the
    * protocol of `era` ([[ProtocolState.in]]).
    */
  def tick(
      state: ProtocolState,
      era: Era,
      epoch: BigInt,
      extraEntropy: Nonce = Nonce.Neutral
  ): ProtocolState =
    val s = state.in(era)
    if epoch <= s.epoch then s
    else
      val extras = s.extras.copy(epoch = epoch)
      s.dep match
        case ChainDepState.Praos(p) =>
          ProtocolState(
            ChainDepState.Praos(
              p.copy(
                epochNonce = p.candidateNonce.combine(p.lastEpochBlockNonce).combine(extraEntropy),
                lastEpochBlockNonce = p.labNonce,
                previousEpochNonce = p.epochNonce
              )
            ),
            extras
          )
        case ChainDepState.TPraos(t) =>
          val dep = t.chainDep
          ProtocolState(
            ChainDepState.TPraos(
              t.copy(chainDep =
                dep.copy(tickn =
                  dep.tickn.copy(
                    epochNonce = dep.protocol.candidateNonce
                      .combine(dep.tickn.prevHashNonce)
                      .combine(extraEntropy),
                    prevHashNonce = dep.labNonce
                  )
                )
              )
            ),
            extras.copy(previousEpochNonce = dep.tickn.epochNonce)
          )
        case ChainDepState.PBft(_) => s

  /** Validate `header` (already ticked to its epoch) and return the updated state. */
  def update(
      state: ProtocolState,
      header: Header,
      pools: PoolDistribution,
      config: PraosConfig
  ): Either[PraosError, ProtocolState] =
    validate(state, header, StakeView.Known(pools), config).map(_._1)

  /** As [[update]], also reporting how leadership was established. With an unknown stake view every
    * cryptographic check runs but the leader threshold is reported as unchecked.
    */
  def validate(
      state: ProtocolState,
      header: Header,
      stake: StakeView,
      config: PraosConfig,
      nonceWindow: BigInt = BigInt(-1)
  ): Either[PraosError, (ProtocolState, LeaderCheck)] =
    val window =
      if nonceWindow >= 0 then nonceWindow else config.geometry.randomnessStabilisationWindow
    header match
      case h: Header.Shelley =>
        h.vrf match
          case HeaderVrf.Praos(cert) =>
            state.dep match
              case ChainDepState.Praos(p) =>
                updatePraos(p, h, cert, stake, config, window).map { (next, leader) =>
                  ProtocolState(ChainDepState.Praos(next), state.extras) -> leader
                }
              case _ =>
                Left(PraosError.WrongState(h.era))
          case _: HeaderVrf.TPraos => Left(PraosError.TPraosHeader(h.era))
      case h => Left(PraosError.NotPraos(h.era))

  /** The issuer of a header, as the key of the operational certificate counters. */
  private[consensus] def issuerKey(vkey: Bytes): Either[VocabError, KeyHash[BlockIssuer]] =
    KeyHash.fromBytes[BlockIssuer](Blake2b.hash224.hash(vkey))

  /** The slot of a header, as the last slot of the chain-dependent state. */
  private[consensus] def slotOf(slot: BigInt): Either[VocabError, WithOrigin[SlotNo]] =
    SlotNo.fromBigInt(slot).map(WithOrigin.At(_))

  /** The sequence number of an operational certificate, as a counter. */
  private[consensus] def counterOf(sequence: BigInt): Either[VocabError, Word64] =
    Word64.fromBigInt(VocabError.Kind.OcertCounter, sequence)

  private def updatePraos(
      state: PraosState,
      header: Header.Shelley,
      vrf: xyz.kd5ujc.scarab.ledger.VrfCert,
      stakeView: StakeView,
      config: PraosConfig,
      window: BigInt
  ): Either[PraosError, (PraosState, LeaderCheck)] =
    val pool = Blake2b.hash224.hash(header.issuerVkey)
    val vrfHash = Blake2b.hash256.hash(header.vrfVkey)
    for
      issuer <- issuerKey(header.issuerVkey).leftMap(PraosError.Vocabulary(_))
      lastSlot <- slotOf(header.slot).leftMap(PraosError.Vocabulary(_))
      sequence <- counterOf(header.opCert.sequence).leftMap(PraosError.Vocabulary(_))
      registered <- stakeView match
        case StakeView.Known(pools) =>
          for
            stake <- pools.stake
              .get(pool)
              .toRight(PraosError.IssuerWithoutStake(pool))
            registeredVrf <- pools.vrf
              .get(pool)
              .toRight(PraosError.UnregisteredPool(pool))
            _ <- Either.cond(
              vrfHash == registeredVrf,
              (),
              PraosError.VrfKeyMismatch(pool, vrfHash, Set(registeredVrf))
            )
          yield Some(stake -> pools.total)
        case StakeView.Unknown(registry) =>
          for
            keys <- registry.get(pool).toRight(PraosError.UnregisteredPool(pool))
            _ <- Either.cond(
              keys.contains(vrfHash),
              (),
              PraosError.VrfKeyMismatch(pool, vrfHash, keys)
            )
          yield None
      input <- vrfInput(state.epochNonce, header.slot).leftMap(PraosError.VrfInput(_))
      output <- config.verdicts.praosVrf(input, header.vrfVkey, vrf.proof, vrf.output) match
        case Vrf.Result.VerifiedCertificate(o) => Right(o)
        case other                             => Left(PraosError.VrfRejected(other))
      leader <- registered match
        case Some((stake, total)) =>
          for
            value <- Leader.leaderValue(output).leftMap(PraosError.LeaderThreshold(_))
            fraction <- Leader.Fraction.checked(stake, total).leftMap(PraosError.LeaderThreshold(_))
            decision <- Leader
              .check(value, fraction, config.activeSlotsCoeff)
              .leftMap(PraosError.LeaderThreshold(_))
            _ <- Either.cond(
              decision == Leader.Decision.Eligible,
              (),
              PraosError.NotElected(header.slot, decision)
            )
          yield LeaderCheck.Threshold
        case None => Right(LeaderCheck.Unchecked(LeaderCheck.UncheckedReason.BootstrapStakeUnknown))
      _ <- config.verdicts
        .opCert(header, config.slotsPerKesPeriod, config.maxKesEvolutions)
        .leftMap(PraosError.OCert(_))
      _ <- OCertRules
        // reaching here means the pool is in the stake view
        .checkCounter(
          state.ocertCounters.get(issuer).map(_.toBigInt),
          pool,
          header.opCert.sequence,
          known = true,
          bounded = true
        )
        .leftMap(PraosError.OCert(_))
    yield
      val evolving = state.evolvingNonce.combine(nonceValue(output))
      val nextEpoch = config.geometry.firstSlot(config.geometry.epochOf(header.slot) + 1)
      state.copy(
        lastSlot = lastSlot,
        evolvingNonce = evolving,
        candidateNonce =
          if header.slot + window < nextEpoch then evolving
          else state.candidateNonce,
        labNonce = header.prevHash.fold(Nonce.Neutral)(Nonce.Hash(_)),
        ocertCounters = state.ocertCounters.updated(issuer, sequence)
      ) -> leader

  /** The VRF input of a slot under an epoch nonce. */
  def vrfInput(epochNonce: Nonce, slot: BigInt): Either[Vrf.Failure, Vrf.Input] =
    for
      nonce <- epochNonce match
        case Nonce.Neutral => Right(Vrf.NeutralNonce)
        case Nonce.Hash(b) => Vrf.Hash32.fromBytes(b)
      s <- Vrf.Slot.fromBigInt(slot)
      input <- Vrf.Input.create(s, nonce)
    yield input

  /** A block's nonce contribution: the doubly hashed "N"-prefixed VRF output. */
  def nonceValue(output: Bytes): Nonce =
    Nonce.Hash(
      Blake2b.hash256.hash(Blake2b.hash256.hash(Bytes(Vector(0x4e.toByte) ++ output.value)))
    )
