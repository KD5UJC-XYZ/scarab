// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import xyz.kd5ujc.scarab.ledger.{LeaderCheck, LedgerView, Nonce, OCertRules, StakeView, TPraosError}
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, ProtocolState, TPraosState}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Header, HeaderVrf, Verdicts, VrfCert}
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.crypto.vrf.{PraosLeaderThreshold as Leader, PraosVrfCertificate as Vrf}

/** TPraos header validation and chain-dependent state evolution: the Overlay, Prtcl, Updn, Tickn
  * and OCert rules of cardano-ledger, over the [[TPraosState]] of the chain-dependent state.
  */
object TPraos:
  /** The validated state and how leadership was established. */
  final case class Validated(state: ProtocolState, leader: LeaderCheck)

  /** The slot is reserved for the overlay schedule (`isOverlaySlot`). */
  def isOverlaySlot(firstSlot: BigInt, d: Rational, slot: BigInt): Boolean =
    val s = slot - firstSlot
    ceiling(Rational(s) * d) < ceiling(Rational(s + 1) * d)

  /** `classifyOverlaySlot`: the genesis key (sorted ascending) that owns an overlay slot, or `None`
    * for a non-active overlay slot. `ascInv = floor(1 / f)`.
    */
  def overlayOwner(
      firstSlot: BigInt,
      genesisKeys: Vector[Bytes],
      d: Rational,
      activeSlotsCoeff: BigDecimal,
      slot: BigInt
  ): Option[Bytes] =
    val position = ceiling(Rational(slot - firstSlot) * d)
    val ascInv = Rational.fromDecimal(activeSlotsCoeff) match
      case f => Rational(f.denominator, f.numerator).floor
    Option.when(position % ascInv == 0 && genesisKeys.nonEmpty)(
      genesisKeys(((position / ascInv) % genesisKeys.size).toInt)
    )

  private def ceiling(r: Rational): BigInt = -Rational(-r.numerator, r.denominator).floor

  private def be64(n: BigInt): Vector[Byte] =
    Vector.tabulate(8)(i => ((n >> (8 * (7 - i))) & 255).toByte)

  /** `mkNonceFromNumber`: the VRF input constants. */
  private def numberSeed(n: Int): Bytes = Blake2b.hash256.hash(Bytes(be64(n)))
  val SeedEta: Bytes = numberSeed(0)
  val SeedL: Bytes = numberSeed(1)

  /** `mkSeed uc slot eta0`: `blake2b256(be64 slot ‖ eta0)` xor `uc` (eta0 omitted if neutral). */
  def seed(uc: Bytes, slot: BigInt, epochNonce: Nonce): Bytes =
    val nonce = epochNonce match
      case Nonce.Neutral => Vector.empty[Byte]
      case Nonce.Hash(b) => b.value
    val h = Blake2b.hash256.hash(Bytes(be64(slot) ++ nonce))
    Bytes(h.value.zip(uc.value).map((a, b) => (a ^ b).toByte))

  /** `mkNonceFromOutputVRF`: the block's nonce contribution, `blake2b256(vrf output)`. */
  def nonceValue(output: Bytes): Nonce = Nonce.Hash(Blake2b.hash256.hash(output))

  private[scarab] def verifyVrf(
      verdicts: Verdicts,
      alpha: Bytes,
      vkey: Bytes,
      cert: VrfCert
  ): Either[String, Unit] =
    verdicts.tpraosVrf(alpha, vkey, cert.proof, cert.output) match
      case Vrf.Result.VerifiedCertificate(_) => Right(())
      case other                             => Left(other.toString)

  /** Validate `header` against `view` (the ledger view of its epoch) and `state` (already ticked to
    * that epoch, see [[Praos.tick]]).
    */
  def update(
      state: ProtocolState,
      header: Header,
      view: LedgerView,
      config: PraosConfig
  ): Either[TPraosError, Validated] =
    header match
      case h: Header.Shelley =>
        h.vrf match
          case HeaderVrf.TPraos(nonceCert, leaderCert) =>
            state.dep match
              case ChainDepState.TPraos(t) =>
                run(t, h, nonceCert, leaderCert, view, config).map((next, leader) =>
                  Validated(ProtocolState(ChainDepState.TPraos(next), state.extras), leader)
                )
              case _ =>
                Left(
                  TPraosError.NotTPraos(s"${h.era} (the chain-dependent state is not TPraos's)")
                )
          case _: HeaderVrf.Praos => Left(TPraosError.NotTPraos(s"${h.era} (Praos)"))
      case h => Left(TPraosError.NotTPraos(h.era.toString))

  private def run(
      state: TPraosState,
      header: Header.Shelley,
      nonceCert: VrfCert,
      leaderCert: VrfCert,
      view: LedgerView,
      config: PraosConfig
  ): Either[TPraosError, (TPraosState, LeaderCheck)] =
    val geometry = config.geometry
    val slot = header.slot
    val firstSlot = geometry.firstSlot(geometry.epochOf(slot))
    val issuer = Blake2b.hash224.hash(header.issuerVkey)
    val vrfHash = Blake2b.hash256.hash(header.vrfVkey)
    val epochNonce = state.chainDep.tickn.epochNonce
    val nonceSeed = seed(SeedEta, slot, epochNonce)
    val leaderSeed = seed(SeedL, slot, epochNonce)
    def vrfChecks: Either[TPraosError, Unit] =
      for
        _ <- verifyVrf(config.verdicts, nonceSeed, header.vrfVkey, nonceCert).leftMap(
          TPraosError.BadNonceVrf(_)
        )
        _ <- verifyVrf(config.verdicts, leaderSeed, header.vrfVkey, leaderCert).leftMap(
          TPraosError.BadLeaderVrf(_)
        )
      yield ()
    val genesisKeys = view.genDelegs.keys.toVector.sortBy(_.hex)
    val delegateHashes = view.genDelegs.valuesIterator.map(_.delegate).toSet
    val registered = view.stake match
      case StakeView.Known(pools)      => pools.stake.contains(issuer)
      case StakeView.Unknown(registry) => registry.contains(issuer)
    for
      issuerKey <- Praos.issuerKey(header.issuerVkey).leftMap(TPraosError.Malformed(_))
      sequence <- Praos.counterOf(header.opCert.sequence).leftMap(TPraosError.Malformed(_))
      lastSlot <- Praos.slotOf(slot).leftMap(TPraosError.Malformed(_))
      leader <-
        if !isOverlaySlot(firstSlot, view.d, slot) then
          for
            registeredVrf <- view.stake match
              case StakeView.Known(pools) =>
                pools.stake
                  .get(issuer)
                  .flatMap(_ => pools.vrf.get(issuer))
                  .map(Set(_))
                  .toRight(TPraosError.VrfKeyUnknown(issuer))
              case StakeView.Unknown(registry) =>
                registry.get(issuer).toRight(TPraosError.VrfKeyUnknown(issuer))
            _ <- Either.cond(
              registeredVrf.contains(vrfHash),
              (),
              TPraosError.VrfKeyWrong(issuer)
            )
            _ <- vrfChecks
            check <- view.stake match
              case StakeView.Known(pools) =>
                for
                  value <- Leader
                    .rawLeaderValue(leaderCert.output)
                    .leftMap(TPraosError.Malformed(_))
                  fraction <- Leader.Fraction
                    .checked(pools.stake(issuer), pools.total)
                    .leftMap(TPraosError.Malformed(_))
                  decision <- Leader
                    .check(value, fraction, config.activeSlotsCoeff, Leader.Range512)
                    .leftMap(TPraosError.Malformed(_))
                  _ <- Either.cond(
                    decision == Leader.Decision.Eligible,
                    (),
                    TPraosError.LeaderValueTooBig(slot)
                  )
                yield LeaderCheck.Threshold
              case StakeView.Unknown(_) =>
                Right(LeaderCheck.Unchecked("stake distribution unknown (bootstrap)"))
          yield check
        else
          for
            gkey <- overlayOwner(firstSlot, genesisKeys, view.d, geometry.activeSlotsCoeff, slot)
              .toRight(TPraosError.NotActiveOverlaySlot(slot))
            pair <- view.genDelegs.get(gkey).toRight(TPraosError.UnknownGenesisKey(gkey))
            _ <- Either.cond(
              issuer == pair.delegate,
              (),
              TPraosError.WrongGenesisColdKey(issuer, pair.delegate)
            )
            _ <- Either.cond(
              vrfHash == pair.vrf,
              (),
              TPraosError.WrongGenesisVrfKey(vrfHash, pair.vrf)
            )
            _ <- vrfChecks
          yield LeaderCheck.Overlay(gkey)
      _ <- config.verdicts
        .opCert(header, config.slotsPerKesPeriod, config.maxKesEvolutions)
        .leftMap(TPraosError.OCert(_))
      _ <- OCertRules
        .checkCounter(
          state.chainDep.protocol.ocertCounters.get(issuerKey).map(_.toBigInt),
          issuer,
          header.opCert.sequence,
          known = registered || delegateHashes.contains(issuer),
          bounded = false
        )
        .leftMap(TPraosError.OCert(_))
    yield
      val chain = state.chainDep
      val evolving = chain.protocol.evolvingNonce.combine(nonceValue(nonceCert.output))
      val nextEpoch = geometry.firstSlot(geometry.epochOf(slot) + 1)
      TPraosState(
        lastSlot,
        chain.copy(
          protocol = chain.protocol.copy(
            ocertCounters = chain.protocol.ocertCounters.updated(issuerKey, sequence),
            evolvingNonce = evolving,
            candidateNonce =
              if slot + geometry.stabilityWindow < nextEpoch then evolving
              else chain.protocol.candidateNonce
          ),
          labNonce = header.prevHash.fold(Nonce.Neutral)(Nonce.Hash(_))
        )
      ) -> leader
