// SPDX-License-Identifier: Apache-2.0
package lab.node.consensus

import cats.syntax.all.*
import lab.Blake2b
import lab.cbor.Bytes
import lab.node.{Header, HeaderVrf, VrfCert}
import lab.node.ledger.Rational
import lab.vrf.{PraosLeaderThreshold as Leader, PraosVrfCertificate as Vrf}

/** Why a TPraos (Shelley..Alonzo) header is rejected. Closed: every rule has its own case. */
enum TPraosError:
  case NotTPraos(era: String)
  case Malformed(reason: String)
  case VrfKeyUnknown(pool: Bytes)
  case VrfKeyWrong(pool: Bytes)
  case BadNonceVrf(reason: String)
  case BadLeaderVrf(reason: String)
  case LeaderValueTooBig(slot: BigInt)
  case NotActiveOverlaySlot(slot: BigInt)
  case UnknownGenesisKey(key: Bytes)
  case WrongGenesisColdKey(issuer: Bytes, expected: Bytes)
  case WrongGenesisVrfKey(got: Bytes, expected: Bytes)
  case OCert(error: OCertError)

  def message: String = this match
    case NotTPraos(e)            => s"$e headers are not TPraos headers"
    case Malformed(r)            => r
    case VrfKeyUnknown(p)        => s"issuer ${p.hex} is not a registered pool"
    case VrfKeyWrong(p)          => s"VRF key is not the registered key of pool ${p.hex}"
    case BadNonceVrf(r)          => s"nonce VRF proof rejected: $r"
    case BadLeaderVrf(r)         => s"leader VRF proof rejected: $r"
    case LeaderValueTooBig(s)    => s"issuer not elected for slot $s: leader value above threshold"
    case NotActiveOverlaySlot(s) => s"slot $s is a non-active overlay slot: no block allowed"
    case UnknownGenesisKey(k)    => s"genesis key ${k.hex} has no delegation"
    case WrongGenesisColdKey(i, e) =>
      s"issuer ${i.hex} is not the genesis delegate ${e.hex} for this overlay slot"
    case WrongGenesisVrfKey(g, e) =>
      s"VRF key hash ${g.hex} is not the genesis delegate's ${e.hex}"
    case OCert(e) => e.message

/** TPraos header validation and chain-dependent state evolution: the Overlay, Prtcl, Updn, Tickn
  * and OCert rules of cardano-ledger, over the same [[PraosState]] that Praos continues from.
  */
object TPraos:
  /** The validated state and how leadership was established. */
  final case class Validated(state: PraosState, leader: LeaderCheck)

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

  private[node] def verifyVrf(
      alpha: Bytes,
      vkey: Bytes,
      cert: VrfCert
  ): Either[String, Unit] =
    lab.node.Verification.tpraosVrf(alpha, vkey, cert.proof, cert.output) match
      case Vrf.Result.VerifiedCertificate(_) => Right(())
      case other                             => Left(other.toString)

  /** Validate `header` against `view` (the ledger view of its epoch) and `state` (already ticked to
    * that epoch, see [[Praos.tick]]).
    */
  def update(
      state: PraosState,
      header: Header,
      view: LedgerView,
      config: PraosConfig
  ): Either[TPraosError, Validated] =
    header match
      case h: Header.Shelley =>
        h.vrf match
          case HeaderVrf.TPraos(nonceCert, leaderCert) =>
            run(state, h, nonceCert, leaderCert, view, config)
          case _: HeaderVrf.Praos => Left(TPraosError.NotTPraos(s"${h.era} (Praos)"))
      case h => Left(TPraosError.NotTPraos(h.era.toString))

  private def run(
      state: PraosState,
      header: Header.Shelley,
      nonceCert: VrfCert,
      leaderCert: VrfCert,
      view: LedgerView,
      config: PraosConfig
  ): Either[TPraosError, Validated] =
    val geometry = config.geometry
    val slot = header.slot
    val firstSlot = geometry.firstSlot(geometry.epochOf(slot))
    val issuer = Blake2b.hash224.hash(header.issuerVkey)
    val vrfHash = Blake2b.hash256.hash(header.vrfVkey)
    val nonceSeed = seed(SeedEta, slot, state.epochNonce)
    val leaderSeed = seed(SeedL, slot, state.epochNonce)
    def vrfChecks: Either[TPraosError, Unit] =
      for
        _ <- verifyVrf(nonceSeed, header.vrfVkey, nonceCert).leftMap(TPraosError.BadNonceVrf(_))
        _ <- verifyVrf(leaderSeed, header.vrfVkey, leaderCert).leftMap(TPraosError.BadLeaderVrf(_))
      yield ()
    val genesisKeys = view.genDelegs.keys.toVector.sortBy(_.hex)
    val delegateHashes = view.genDelegs.valuesIterator.map(_.delegate).toSet
    val registered = view.stake match
      case StakeView.Known(pools)      => pools.stake.contains(issuer)
      case StakeView.Unknown(registry) => registry.contains(issuer)
    for
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
      _ <- lab.node.Verification
        .opCert(header, config.slotsPerKesPeriod, config.maxKesEvolutions)
        .leftMap(TPraosError.OCert(_))
      _ <- OCertRules
        .checkCounter(
          state.counters,
          issuer,
          header.opCert.sequence,
          known = registered || delegateHashes.contains(issuer),
          bounded = false
        )
        .leftMap(TPraosError.OCert(_))
    yield
      val evolving = state.evolving.combine(nonceValue(nonceCert.output))
      val nextEpoch = geometry.firstSlot(geometry.epochOf(slot) + 1)
      Validated(
        state.copy(
          evolving = evolving,
          candidate =
            if slot + geometry.stabilityWindow < nextEpoch then evolving else state.candidate,
          lab = header.prevHash.fold(Nonce.Neutral)(Nonce.Hash(_)),
          counters = state.counters.updated(issuer, header.opCert.sequence)
        ),
        leader
      )
