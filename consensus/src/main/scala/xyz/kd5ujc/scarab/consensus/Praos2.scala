// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.consensus.state.PraosState
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.vrf.PraosLeaderThreshold
import xyz.kd5ujc.scarab.ledger.{
  EbAnnouncement,
  EnvelopeError,
  EpochGeometry,
  LeiosHeader,
  Nonce,
  OCertError,
  PraosError,
  ProviderDefect
}
import xyz.kd5ujc.scarab.ledger.OCertRules.refusedAs
import xyz.kd5ujc.scarab.ledger.state.{
  BlockIssuer,
  BlockNo,
  EpochNo,
  KeyHash,
  ProtVer,
  SlotNo,
  StakePool,
  VRFVerKeyHash,
  VocabError,
  Word64,
  WithOrigin
}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word16, Word32}

/** Praos with the Leios overlay, at consensus 2416493b. These pure header rules consume a ledger
  * forecast supplied by their caller; they neither apply Dijkstra transactions nor persist state.
  * The public node's era activation and forecast production belong to the Dijkstra integration.
  */
object Praos2:
  /** The native header rules read the stored rational share and VRF hash, not the coin amount
    * (which also includes proposal deposits) or BLS key of `IndividualPoolStake` (cardano-ledger
    * 9df9f98c, `State/PoolDistr.hs:54-67`).
    */
  final case class PoolStake(share: Rational, vrf: VRFVerKeyHash)

  /** The fields of PolyPraosLedgerView read by header validation (Praos2.hs:131-159 and
    * Praos/Views.hs:70-93). Committee, quorum and transaction-byte bounds belong to body/vote
    * validation and are not consulted by these header predicates.
    */
  final case class LedgerView(
      pools: Map[KeyHash[StakePool], PoolStake],
      maxHeaderSize: Word16,
      maxBodySize: Word32,
      protocolVersion: ProtVer,
      announcementPeriodMillis: Word32,
      votePeriodMillis: Word32,
      diffusionPeriodMillis: Word32,
      maxEbReferencesSize: Word32
  )

  /** The epoch queries made by tick, certification and nonce freezing. A forecast failure is no
    * verdict on the header, as the reference's PastHorizonException is not a validation refusal.
    */
  trait EpochInfo:
    def epochOf(slot: SlotNo): Either[Defect, EpochNo]
    def firstSlot(epoch: EpochNo): Either[Defect, SlotNo]
    def epochSize(epoch: EpochNo): Either[Defect, Word64]
    def slotLengthMillis(slot: SlotNo): Either[Defect, BigInt]

  object EpochInfo:
    /** The fixed Shelley-era geometry used by the maintained testnet capture. */
    def fromGeometry(geometry: EpochGeometry): EpochInfo = new EpochInfo:
      def epochOf(slot: SlotNo): Either[Defect, EpochNo] =
        Right(EpochNo.fromUnsigned(geometry.epochOf(slot.toBigInt).toLong))
      def firstSlot(epoch: EpochNo): Either[Defect, SlotNo] =
        Right(SlotNo.fromUnsigned(geometry.firstSlot(epoch.toBigInt).toLong))
      def epochSize(epoch: EpochNo): Either[Defect, Word64] =
        Right(Word64.fromUnsigned(geometry.epochSlots(epoch.toBigInt).toLong))
      def slotLengthMillis(slot: SlotNo): Either[Defect, BigInt] =
        Right(
          if geometry.isByronSlot(slot.toBigInt) then geometry.byron.slotLengthMillis
          else BigInt(geometry.slotLengthMillis)
        )

  final case class Config(praos: PraosConfig, maxMajor: Word64, epochInfo: EpochInfo)

  /** Only the immediate predecessor's announcement is retained (PolyPraos.hs:307-335,665-668). */
  final case class AnnouncedBy(issuer: KeyHash[BlockIssuer], announcement: EbAnnouncement)
  final case class State(praos: PraosState, announcement: Option[AnnouncedBy])
  final case class Tip(hash: Bytes, blockNo: BlockNo, slot: SlotNo)
  final case class ChainState(tip: Option[Tip], protocol: State)
  final case class Ticked private[consensus] (chain: ChainState, ledger: LedgerView)

  enum Error:
    case UnexpectedBlockNo(expected: BlockNo, actual: BigInt)
    case UnexpectedSlotNo(expected: SlotNo, actual: BigInt)
    case UnexpectedPrevHash(expected: Option[Bytes], actual: Option[Bytes])
    case Envelope(cause: EnvelopeError)
    case LeiosEbTooBig(size: Long, maximum: Word32)
    case LeiosCertWithoutAnnouncement
    case LeiosCertTooYoung(announcingSlot: SlotNo, slot: SlotNo, earliest: SlotNo)
    case KesBeforeStart(start: BigInt, current: BigInt)
    case KesAfterEnd(current: BigInt, start: BigInt, maxEvolutions: BigInt)
    case Certificate(cause: OCertError)
    case CounterTooSmall(current: Word64, actual: Word64)
    case CounterOverIncremented(current: Word64, actual: Word64)
    case Praos(cause: PraosError)

  enum Defect:
    case SlotOutsideForecast(slot: SlotNo)
    case EpochOutsideForecast(epoch: EpochNo)
    case ZeroEpochSize(epoch: EpochNo)
    case NonPositiveSlotLength(milliseconds: BigInt)
    case NonPositiveKesPeriod(slots: BigInt)
    case SuccessorOverflow(kind: VocabError.Kind)
    case Vocabulary(cause: VocabError)

  type Failure = Error | Defect | ProviderDefect

  /** `tickChainDepStatePolyPraos`, PolyPraos.hs:551-587. The new-epoch test uses the old epoch's
    * first slot and size; it does not ask a limited forecast for the epoch of the new slot
    * (`Protocol/Ledger/Util.hs:isNewEpoch`). The reference's slot subtraction and epoch addition
    * are Word64, which wraps; here a result outside it is no verdict ([[Defect.Vocabulary]],
    * overflow-improvements.md#leios-praos2).
    */
  def tick(
      config: Config,
      ledger: LedgerView,
      slot: SlotNo,
      state: ChainState
  ): Either[Defect, Ticked] =
    val p = state.protocol.praos
    val info = config.epochInfo
    for
      oldEpoch <- p.lastSlot match
        case WithOrigin.Origin => Right(EpochNo.fromUnsigned(0))
        case WithOrigin.At(s)  => info.epochOf(s)
      size <- info.epochSize(oldEpoch)
      _ <- Either.cond(!size.isZero, (), Defect.ZeroEpochSize(oldEpoch))
      first <- info.firstSlot(oldEpoch)
      elapsed <- Word64
        .subtract(VocabError.Kind.SlotNo, slot.word, first.word)
        .bimap(Defect.Vocabulary(_), _.toBigInt)
      next <- EpochNo
        .fromBigInt(oldEpoch.toBigInt + elapsed / size.toBigInt)
        .leftMap(Defect.Vocabulary(_))
      protocol =
        if next.toBigInt > oldEpoch.toBigInt then
          State(
            p.copy(
              epochNonce = p.candidateNonce.combine(p.lastEpochBlockNonce),
              previousEpochNonce = p.epochNonce,
              lastEpochBlockNonce = p.labNonce
            ),
            None
          )
        else state.protocol
    yield Ticked(state.copy(protocol = protocol), ledger)

  /** Full ChainSync header validation: block number, slot, previous hash, checkpoint, envelope,
    * then protocol (`HeaderValidation.hs:374-386,518-526`).
    */
  def validate(config: Config, header: LeiosHeader, ticked: Ticked): Either[Failure, ChainState] =
    envelope(config, header, ticked) *> validateProtocol(config, header, ticked)

  /** The protocol portion used for an announced header that need not extend the local tip
    * (`HeaderValidation.hs:536-550`). Its caller still supplies the appropriate ticked forecast.
    */
  def validateProtocol(
      config: Config,
      header: LeiosHeader,
      ticked: Ticked
  ): Either[Failure, ChainState] =
    val p = ticked.chain.protocol.praos
    for
      slot <- SlotNo.fromBigInt(header.slot).leftMap(Defect.Vocabulary(_))
      blockNo <- BlockNo.fromBigInt(header.blockNo).leftMap(Defect.Vocabulary(_))
      _ <- leiosChecks(config.epochInfo, ticked.ledger, slot, header, ticked.chain.protocol)
      issuer <- Praos.issuerKey(header.issuerVkey).leftMap(Defect.Vocabulary(_))
      pool <- KeyHash.fromBytes[StakePool](issuer.bytes).leftMap(Defect.Vocabulary(_))
      sequence <- Praos.counterOf(header.opCert.sequence).leftMap(Defect.Vocabulary(_))
      _ <- certificate(
        config.praos,
        ticked.ledger.pools.contains(pool),
        p,
        issuer,
        sequence,
        header
      )
      registered <- ticked.ledger.pools
        .get(pool)
        .toRight(Error.Praos(PraosError.IssuerWithoutStake(pool.bytes)))
      vrfHash = Blake2b.hash256.hash(header.vrfVkey)
      _ <- Either.cond(
        vrfHash == registered.vrf.bytes,
        (),
        Error.Praos(PraosError.VrfKeyMismatch(pool.bytes, vrfHash, Set(registered.vrf.bytes)))
      )
      _ <- Praos
        .verifyVrfWithStake(
          header.praosView,
          header.vrf,
          p.epochNonce,
          config.praos,
          Some(Right(PraosLeaderThreshold.Fraction.storedStake(registered.share)))
        )
        .leftMap {
          case e: PraosError     => Error.Praos(e)
          case d: ProviderDefect => d
        }
      next <- reupdate(config, header, ticked)
    yield ChainState(Some(Tip(header.hash, blockNo, slot)), next)

  /** Reapply an already validated header, as `reupdateChainDepStatePolyPraos` does. The caller owns
    * that precondition; this path consumes the certified output without verifying its proof.
    */
  def reupdate(config: Config, header: LeiosHeader, ticked: Ticked): Either[Defect, State] =
    val p = ticked.chain.protocol.praos
    for
      slot <- SlotNo.fromBigInt(header.slot).leftMap(Defect.Vocabulary(_))
      issuer <- Praos.issuerKey(header.issuerVkey).leftMap(Defect.Vocabulary(_))
      sequence <- Praos.counterOf(header.opCert.sequence).leftMap(Defect.Vocabulary(_))
      epoch <- config.epochInfo.epochOf(slot)
      // the reference's `succ` and `+*` wrap in Word64; a result past it is no verdict here
      // (overflow-improvements.md#leios-praos2)
      nextEpoch <- epoch.next.toRight(Defect.SuccessorOverflow(VocabError.Kind.EpochNo))
      firstNext <- config.epochInfo.firstSlot(nextEpoch)
      evolving = p.evolvingNonce.combine(Praos.nonceValue(header.vrf.output))
      windowEnd <- SlotNo
        .fromBigInt(slot.toBigInt + config.praos.geometry.randomnessStabilisationWindow.toBigInt)
        .leftMap(Defect.Vocabulary(_))
      next = p.copy(
        lastSlot = WithOrigin.At(slot),
        evolvingNonce = evolving,
        candidateNonce =
          if windowEnd.toBigInt < firstNext.toBigInt then evolving else p.candidateNonce,
        labNonce = header.prevHash.fold(Nonce.Neutral)(Nonce.Hash(_)),
        ocertCounters = p.ocertCounters.updated(issuer, sequence)
      )
    yield State(next, header.leios.announcement.map(AnnouncedBy(issuer, _)))

  private def envelope(config: Config, header: LeiosHeader, ticked: Ticked): Either[Failure, Unit] =
    for
      expectedNo <- ticked.chain.tip match
        case None => Right(BlockNo.fromUnsigned(0))
        case Some(t) =>
          t.blockNo.word.successor
            .map(w => BlockNo.fromUnsigned(w.bits))
            .toRight(Defect.SuccessorOverflow(VocabError.Kind.BlockNo))
      _ <- Either.cond(
        header.blockNo == expectedNo.toBigInt,
        (),
        Error.UnexpectedBlockNo(expectedNo, header.blockNo)
      )
      expectedSlot <- ticked.chain.tip match
        case None => Right(SlotNo.fromUnsigned(0))
        case Some(t) =>
          t.slot.word.successor
            .map(w => SlotNo.fromUnsigned(w.bits))
            .toRight(Defect.SuccessorOverflow(VocabError.Kind.SlotNo))
      _ <- Either.cond(
        header.slot >= expectedSlot.toBigInt,
        (),
        Error.UnexpectedSlotNo(expectedSlot, header.slot)
      )
      previous = ticked.chain.tip.map(_.hash)
      _ <- Either.cond(
        header.prevHash == previous,
        (),
        Error.UnexpectedPrevHash(previous, header.prevHash)
      )
      _ <- config.praos.checkpoints.validate(header.praosView).leftMap(Error.Envelope(_))
      view = ticked.ledger
      _ <- Either.cond(
        view.protocolVersion.major.toBigInt <= config.maxMajor.toBigInt,
        (),
        Error.Envelope(
          EnvelopeError.ObsoleteNode(view.protocolVersion.major.toBigInt, config.maxMajor.toBigInt)
        )
      )
      _ <- Either.cond(
        header.raw.size <= view.maxHeaderSize.toInt,
        (),
        Error.Envelope(EnvelopeError.HeaderSizeTooLarge(header.raw.size, view.maxHeaderSize.toInt))
      )
      _ <- Either.cond(
        header.bodySize <= view.maxBodySize.toLong,
        (),
        Error.Envelope(EnvelopeError.BlockSizeTooLarge(header.bodySize, view.maxBodySize.toLong))
      )
    yield ()

  /** `minCertificationSlot`, Leios/Types.hs:299-317. The caller passes the current header slot's
    * forecast length, truncated to whole milliseconds as `slotLengthToMillisec` does.
    */
  def minCertificationSlot(
      slotMillis: BigInt,
      ledger: LedgerView,
      announcingSlot: SlotNo
  ): Either[Defect, SlotNo] =
    if slotMillis <= 0 then Left(Defect.NonPositiveSlotLength(slotMillis))
    else
      val total = BigInt(3) * ledger.announcementPeriodMillis.toLong +
        ledger.votePeriodMillis.toLong + ledger.diffusionPeriodMillis.toLong
      val gap = (total + slotMillis - 1) / slotMillis
      // the reference adds in Word64, which wraps (overflow-improvements.md#leios-praos2)
      SlotNo.fromBigInt(announcingSlot.toBigInt + gap).leftMap(Defect.Vocabulary(_))

  private def leiosChecks(
      info: EpochInfo,
      ledger: LedgerView,
      slot: SlotNo,
      header: LeiosHeader,
      state: State
  ): Either[Error | Defect, Unit] =
    for
      _ <- header.leios.announcement.traverse_ { a =>
        Either.cond(
          a.size <= ledger.maxEbReferencesSize.toLong,
          (),
          Error.LeiosEbTooBig(a.size, ledger.maxEbReferencesSize)
        )
      }
      _ <-
        if !header.leios.blockBodyContainsLeiosCert then Right(())
        else
          (state.announcement, state.praos.lastSlot) match
            case (Some(_), WithOrigin.At(announcingSlot)) =>
              for
                millis <- info.slotLengthMillis(slot)
                earliest <- minCertificationSlot(millis, ledger, announcingSlot)
                _ <- Either.cond(
                  slot.toBigInt >= earliest.toBigInt,
                  (),
                  Error.LeiosCertTooYoung(announcingSlot, slot, earliest)
                )
              yield ()
            case _ => Left(Error.LeiosCertWithoutAnnouncement)
    yield ()

  /** The certificate checks precede the VRF checks (PolyPraos.hs:615-624,900-937). The reference
    * adds KES periods and increments certificate counters in unsigned machine words.
    */
  private def certificate(
      config: PraosConfig,
      knownPool: Boolean,
      state: PraosState,
      issuer: KeyHash[BlockIssuer],
      sequence: Word64,
      header: LeiosHeader
  ): Either[Failure, Unit] =
    val start = header.opCert.kesPeriod
    for
      _ <- Either.cond(
        config.slotsPerKesPeriod > 0,
        (),
        Defect.NonPositiveKesPeriod(config.slotsPerKesPeriod)
      )
      current = header.slot / config.slotsPerKesPeriod
      _ <- Either.cond(start <= current, (), Error.KesBeforeStart(start, current))
      // the reference adds in a machine word: a sum of 2^64 or more wraps below `start`, so it
      // refuses every such header; so does the node, from the exact sum
      // (overflow-improvements.md#kes-window)
      end = start + config.maxKesEvolutions
      _ <- Either.cond(
        current < end && end.bitLength <= 64,
        (),
        Error.KesAfterEnd(current, start, config.maxKesEvolutions)
      )
      _ <- config.verdicts
        .opCert(header.praosView, config.slotsPerKesPeriod, config.maxKesEvolutions)
        .refusedAs(Error.Certificate(_))
      previous <- state.ocertCounters
        .get(issuer)
        .orElse(Option.when(knownPool)(Word64.zero))
        .toRight(Error.Certificate(OCertError.NoCounter(issuer.bytes)))
      _ <- Either.cond(
        previous.toBigInt <= sequence.toBigInt,
        (),
        Error.CounterTooSmall(previous, sequence)
      )
      upper = Word64.fromUnsigned(previous.bits + 1)
      _ <- Either.cond(
        sequence.toBigInt <= upper.toBigInt,
        (),
        Error.CounterOverIncremented(previous, sequence)
      )
    yield ()
