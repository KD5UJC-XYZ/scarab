// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import cats.{Eq, Show}
import cats.derived.*
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
  Praos2Defect,
  Praos2Error,
  PraosError,
  ProviderDefect
}
import xyz.kd5ujc.scarab.ledger.OCertRules.refusedAs
import xyz.kd5ujc.scarab.ledger.state.{
  BlockIssuer,
  BlockNo,
  Diff,
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

  /** The fields of PolyPraosLedgerView read by header validation (Praos2.hs:136-159 and
    * Praos/Views.hs:70-93, ouroboros-consensus 2416493b), and `plvMaxEbTxsSize`, the bound on the
    * bytes of the transactions an endorser block references (Praos/Views.hs:90-91), which the Leios
    * fetch reads (`getLeiosMaxEbTxsSizeFromView`, `Shelley/Ledger/Leios.hs:161-163`). The committee
    * and the quorum belong to vote validation and are not consulted by these header predicates.
    */
  final case class LedgerView(
      pools: Map[KeyHash[StakePool], PoolStake],
      maxHeaderSize: Word16,
      maxBodySize: Word32,
      protocolVersion: ProtVer,
      announcementPeriodMillis: Word32,
      votePeriodMillis: Word32,
      diffusionPeriodMillis: Word32,
      maxEbReferencesSize: Word32,
      maxEbTxsSize: Word32
  )

  /** The epoch queries made by tick, certification and nonce freezing. A forecast failure is no
    * verdict on the header, as the reference's PastHorizonException is not a validation refusal.
    */
  trait EpochInfo:
    def epochOf(slot: SlotNo): Either[Praos2Defect, EpochNo]
    def firstSlot(epoch: EpochNo): Either[Praos2Defect, SlotNo]
    def epochSize(epoch: EpochNo): Either[Praos2Defect, Word64]
    def slotLengthMillis(slot: SlotNo): Either[Praos2Defect, BigInt]

  object EpochInfo:
    /** The fixed Shelley-era geometry used by the maintained testnet capture. */
    def fromGeometry(geometry: EpochGeometry): EpochInfo = new EpochInfo:
      def epochOf(slot: SlotNo): Either[Praos2Defect, EpochNo] =
        Right(EpochNo.fromUnsigned(geometry.epochOf(slot.toBigInt).toLong))
      def firstSlot(epoch: EpochNo): Either[Praos2Defect, SlotNo] =
        Right(SlotNo.fromUnsigned(geometry.firstSlot(epoch.toBigInt).toLong))
      def epochSize(epoch: EpochNo): Either[Praos2Defect, Word64] =
        Right(Word64.fromUnsigned(geometry.epochSlots(epoch.toBigInt).toLong))
      def slotLengthMillis(slot: SlotNo): Either[Praos2Defect, BigInt] =
        Right(
          if geometry.isByronSlot(slot.toBigInt) then geometry.byron.slotLengthMillis
          else BigInt(geometry.slotLengthMillis)
        )

  final case class Config(praos: PraosConfig, maxMajor: Word64, epochInfo: EpochInfo)

  /** Only the immediate predecessor's announcement is retained (PolyPraos.hs:307-335,665-668,
    * ouroboros-consensus 2416493b).
    */
  final case class AnnouncedBy(issuer: KeyHash[BlockIssuer], announcement: EbAnnouncement)

  object AnnouncedBy:
    given Eq[AnnouncedBy] = Eq.fromUniversalEquals
    given Show[AnnouncedBy] = Show.fromToString

  /** `PolyPraosState (Praos2 c)` (PolyPraos.hs:290-313, ouroboros-consensus 2416493b), the
    * chain-dependent state of Praos2 (`Praos2.hs:117`): the eight fields of Praos and the
    * announcement of the last applied header.
    */
  final case class State(praos: PraosState, announcement: Option[AnnouncedBy])
      derives Eq,
        Show,
        Diff

  object State:
    /** The new-epoch branch of `tickChainDepStatePolyPraos` (PolyPraos.hs:551-587,
      * ouroboros-consensus 2416493b): the epoch nonce is the candidate combined with the last epoch
      * block nonce, the previous epoch nonce the old epoch nonce, the last epoch block nonce the
      * lab nonce, and the announcement is cleared, since an endorser block may not be certified
      * across an epoch boundary.
      */
    def newEpoch(s: State): State =
      val p = s.praos
      State(
        p.copy(
          epochNonce = p.candidateNonce.combine(p.lastEpochBlockNonce),
          previousEpochNonce = p.epochNonce,
          lastEpochBlockNonce = p.labNonce
        ),
        None
      )

  /** `OCINStaleness` (`Storage/LedgerDB/Forker.hs`, ouroboros-consensus 2416493b): whether a
    * relayed announcement's counter is at least the one the immutable tip recorded for its issuer,
    * so that it is processed and relayed, or below it or unrecorded, so that it is accepted from
    * the peer and neither.
    */
  enum OcinStaleness derives CanEqual:
    case Fresh, Stale

  final case class Tip(hash: Bytes, blockNo: BlockNo, slot: SlotNo)
  final case class ChainState(tip: Option[Tip], protocol: State)
  final case class Ticked private[consensus] (chain: ChainState, ledger: LedgerView)

  type Failure = Praos2Error | Praos2Defect | ProviderDefect

  /** `tickChainDepStatePolyPraos`, PolyPraos.hs:551-587 (ouroboros-consensus 2416493b). The
    * new-epoch test uses the old epoch's first slot and size; it does not ask a limited forecast
    * for the epoch of the new slot (`Protocol/Ledger/Util.hs:isNewEpoch`). Slot subtraction and
    * epoch addition are Word64.
    */
  def tick(
      config: Config,
      ledger: LedgerView,
      slot: SlotNo,
      state: ChainState
  ): Either[Praos2Defect, Ticked] =
    val p = state.protocol.praos
    val info = config.epochInfo
    for
      oldEpoch <- p.lastSlot match
        case WithOrigin.Origin => Right(EpochNo.fromUnsigned(0))
        case WithOrigin.At(s)  => info.epochOf(s)
      size <- info.epochSize(oldEpoch)
      _ <- Either.cond(!size.isZero, (), Praos2Defect.ZeroEpochSize(oldEpoch))
      first <- info.firstSlot(oldEpoch)
      elapsed = Word64.fromUnsigned((slot.toBigInt - first.toBigInt).toLong).toBigInt
      next = EpochNo.fromUnsigned((oldEpoch.toBigInt + elapsed / size.toBigInt).toLong)
      protocol =
        if next.toBigInt > oldEpoch.toBigInt then State.newEpoch(state.protocol)
        else state.protocol
    yield Ticked(state.copy(protocol = protocol), ledger)

  /** Full ChainSync header validation: block number, slot, previous hash, checkpoint, envelope,
    * then protocol (`HeaderValidation.hs:374-386,518-526`, ouroboros-consensus 2416493b).
    */
  def validate(config: Config, header: LeiosHeader, ticked: Ticked): Either[Failure, ChainState] =
    // the protocol checks run only once the envelope holds, as `validateHeader` runs them
    envelope(config, header, ticked).flatMap(_ => validateProtocol(config, header, ticked))

  /** The protocol portion used for an announced header that need not extend the local tip
    * (`HeaderValidation.hs:536-550`, ouroboros-consensus 2416493b). Its caller still supplies the
    * appropriate ticked forecast.
    */
  def validateProtocol(
      config: Config,
      header: LeiosHeader,
      ticked: Ticked
  ): Either[Failure, ChainState] =
    val p = ticked.chain.protocol.praos
    for
      slot <- SlotNo.fromBigInt(header.slot).leftMap(Praos2Defect.Vocabulary(_))
      blockNo <- BlockNo.fromBigInt(header.blockNo).leftMap(Praos2Defect.Vocabulary(_))
      _ <- leiosChecks(config.epochInfo, ticked.ledger, slot, header, ticked.chain.protocol)
      issuer <- Praos.issuerKey(header.issuerVkey).leftMap(Praos2Defect.Vocabulary(_))
      pool <- KeyHash.fromBytes[StakePool](issuer.bytes).leftMap(Praos2Defect.Vocabulary(_))
      sequence <- Praos.counterOf(header.opCert.sequence).leftMap(Praos2Defect.Vocabulary(_))
      _ <- certificate(
        config.praos,
        ticked.ledger.pools.contains(pool),
        p,
        issuer,
        sequence,
        header
      )
      _ <- election(config, ticked.ledger, p, pool, header)
      next <- reupdate(config, header, ticked)
    yield ChainState(Some(Tip(header.hash, blockNo, slot)), next)

  /** `validateAnnouncementChainDepState` of the Leios build (`Shelley/Ledger/Leios.hs:286-321`,
    * ouroboros-consensus 2416493b): the relaxed, out-of-context protocol checks of a header relayed
    * as an announcement, against the immutable tip's state ticked to its slot. Only the Leios
    * checks that need no predecessor run (`leiosContextFreeHeaderChecks`, `PolyPraos.hs:692-711`:
    * the announcement's size), then the election proof (`doValidateVRFSignature`, `:810-839`), then
    * the signatures and the counter (`doValidateKESSignatureWorker DoNotUpperBoundOCERT`,
    * `:879-920`): a counter above the recorded one is honest lag, so it has no upper bound, and a
    * counter below it, or none recorded, is [[OcinStaleness.Stale]] where full validation refuses.
    * No state is produced: the announcement is not applied.
    */
  def validateAnnouncement(
      config: Config,
      header: LeiosHeader,
      ticked: Ticked
  ): Either[Failure, OcinStaleness] =
    val p = ticked.chain.protocol.praos
    for
      _ <- announcementSize(ticked.ledger, header)
      issuer <- Praos.issuerKey(header.issuerVkey).leftMap(Praos2Defect.Vocabulary(_))
      pool <- KeyHash.fromBytes[StakePool](issuer.bytes).leftMap(Praos2Defect.Vocabulary(_))
      _ <- election(config, ticked.ledger, p, pool, header)
      sequence <- Praos.counterOf(header.opCert.sequence).leftMap(Praos2Defect.Vocabulary(_))
      _ <- signatures(config.praos, header)
    yield recorded(ticked.ledger.pools.contains(pool), p, issuer) match
      case Some(previous) if previous.toBigInt <= sequence.toBigInt => OcinStaleness.Fresh
      case Some(_) | None                                           => OcinStaleness.Stale

  /** `additionalEnvelopeChecks` of a Shelley-based block (`Shelley/Ledger/Ledger.hs`, ouroboros-
    * consensus 2416493b), the envelope checks a relayed announcement's header meets out of context
    * (`validateAnnouncementHeader`, `LeiosDemoLogic/Announcements/Validate.hs:218-224`): the node's
    * major version, the header's size and the body's size in `view`.
    */
  def additionalEnvelope(
      config: Config,
      header: LeiosHeader,
      view: LedgerView
  ): Either[EnvelopeError, Unit] =
    for
      _ <- Either.cond(
        view.protocolVersion.major.toBigInt <= config.maxMajor.toBigInt,
        (),
        EnvelopeError.ObsoleteNode(view.protocolVersion.major.toBigInt, config.maxMajor.toBigInt)
      )
      _ <- Either.cond(
        header.raw.size <= view.maxHeaderSize.toInt,
        (),
        EnvelopeError.HeaderSizeTooLarge(header.raw.size, view.maxHeaderSize.toInt)
      )
      _ <- Either.cond(
        header.bodySize <= view.maxBodySize.toLong,
        (),
        EnvelopeError.BlockSizeTooLarge(header.bodySize, view.maxBodySize.toLong)
      )
    yield ()

  /** `doValidateVRFSignature` (`PolyPraos.hs:810-839`, ouroboros-consensus 2416493b): the issuer's
    * stake and VRF key, then the proof and the leader threshold.
    */
  private def election(
      config: Config,
      ledger: LedgerView,
      p: PraosState,
      pool: KeyHash[StakePool],
      header: LeiosHeader
  ): Either[Failure, Unit] =
    for
      registered <- ledger.pools
        .get(pool)
        .toRight[Failure](Praos2Error.Praos(PraosError.IssuerWithoutStake(pool.bytes)))
      vrfHash = Blake2b.hash256.hash(header.vrfVkey)
      _ <- Either.cond[Failure, Unit](
        vrfHash == registered.vrf.bytes,
        (),
        Praos2Error.Praos(PraosError.VrfKeyMismatch(pool.bytes, vrfHash, Set(registered.vrf.bytes)))
      )
      _ <- Praos
        .verifyVrfWithStake(
          header.praosView,
          header.vrf,
          p.epochNonce,
          config.praos,
          Some(Right(PraosLeaderThreshold.Fraction.storedStake(registered.share)))
        )
        .leftMap[Failure] {
          case e: PraosError     => Praos2Error.Praos(e)
          case d: ProviderDefect => d
        }
    yield ()

  /** Reapply an already validated header, as `reupdateChainDepStatePolyPraos` does. The caller owns
    * that precondition; this path consumes the certified output without verifying its proof.
    */
  def reupdate(config: Config, header: LeiosHeader, ticked: Ticked): Either[Praos2Defect, State] =
    val p = ticked.chain.protocol.praos
    for
      slot <- SlotNo.fromBigInt(header.slot).leftMap(Praos2Defect.Vocabulary(_))
      issuer <- Praos.issuerKey(header.issuerVkey).leftMap(Praos2Defect.Vocabulary(_))
      sequence <- Praos.counterOf(header.opCert.sequence).leftMap(Praos2Defect.Vocabulary(_))
      epoch <- config.epochInfo.epochOf(slot)
      nextEpoch = EpochNo.fromUnsigned((epoch.toBigInt + 1).toLong)
      firstNext <- config.epochInfo.firstSlot(nextEpoch)
      evolving = p.evolvingNonce.combine(Praos.nonceValue(header.vrf.output))
      windowEnd = SlotNo.fromUnsigned(
        (slot.toBigInt + config.praos.geometry.randomnessStabilisationWindow.toBigInt).toLong
      )
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
            .toRight[Failure](Praos2Defect.SuccessorOverflow(VocabError.Kind.BlockNo))
      _ <- Either.cond[Failure, Unit](
        header.blockNo == expectedNo.toBigInt,
        (),
        Praos2Error.UnexpectedBlockNo(expectedNo, header.blockNo)
      )
      expectedSlot <- ticked.chain.tip match
        case None => Right(SlotNo.fromUnsigned(0))
        case Some(t) =>
          t.slot.word.successor
            .map(w => SlotNo.fromUnsigned(w.bits))
            .toRight[Failure](Praos2Defect.SuccessorOverflow(VocabError.Kind.SlotNo))
      _ <- Either.cond(
        header.slot >= expectedSlot.toBigInt,
        (),
        Praos2Error.UnexpectedSlotNo(expectedSlot, header.slot)
      )
      previous = ticked.chain.tip.map(_.hash)
      _ <- Either.cond(
        header.prevHash == previous,
        (),
        Praos2Error.UnexpectedPrevHash(previous, header.prevHash)
      )
      _ <- config.praos.checkpoints.validate(header.praosView).leftMap(Praos2Error.Envelope(_))
      _ <- additionalEnvelope(config, header, ticked.ledger).leftMap(Praos2Error.Envelope(_))
    yield ()

  /** `minCertificationSlot`, Leios/Types.hs:299-317 (ouroboros-consensus 2416493b). The caller
    * passes the current header slot's forecast length, truncated to whole milliseconds as
    * `slotLengthToMillisec` does.
    */
  def minCertificationSlot(
      slotMillis: BigInt,
      ledger: LedgerView,
      announcingSlot: SlotNo
  ): Either[Praos2Defect, SlotNo] =
    certificationGap(
      slotMillis,
      ledger.announcementPeriodMillis.toLong,
      ledger.votePeriodMillis.toLong,
      ledger.diffusionPeriodMillis.toLong
    ).map(gap => SlotNo.fromUnsigned((announcingSlot.toBigInt + gap).toLong))

  /** `certificationGapOfPeriods` (`LeiosDemoTypes.hs:2843-2853`, ouroboros-consensus 2416493b), the
    * `L` of CIP-164: the slots between an endorser block's announcement and the earliest block that
    * may certify it, `3 * announcement + vote + diffusion` milliseconds over the slot length,
    * rounded up, since a partly elapsed slot has not elapsed. A slot length of no millisecond is
    * the reference's `error`.
    */
  def certificationGap(
      slotMillis: BigInt,
      announcementMillis: BigInt,
      voteMillis: BigInt,
      diffusionMillis: BigInt
  ): Either[Praos2Defect, BigInt] =
    if slotMillis <= 0 then Left(Praos2Defect.NonPositiveSlotLength(slotMillis))
    else
      val total = BigInt(3) * announcementMillis + voteMillis + diffusionMillis
      Right((total + slotMillis - 1) / slotMillis)

  private def leiosChecks(
      info: EpochInfo,
      ledger: LedgerView,
      slot: SlotNo,
      header: LeiosHeader,
      state: State
  ): Either[Praos2Error | Praos2Defect, Unit] =
    for
      _ <- announcementSize(ledger, header)
      _ <-
        if !header.leios.blockBodyContainsLeiosCert then Right(())
        else
          (state.announcement, state.praos.lastSlot) match
            case (Some(_), WithOrigin.At(announcingSlot)) =>
              for
                millis <- info.slotLengthMillis(slot)
                earliest <- minCertificationSlot(millis, ledger, announcingSlot)
                _ <- Either.cond[Praos2Error | Praos2Defect, Unit](
                  slot.toBigInt >= earliest.toBigInt,
                  (),
                  Praos2Error.LeiosCertTooYoung(announcingSlot, slot, earliest)
                )
              yield ()
            case _ => Left(Praos2Error.LeiosCertWithoutAnnouncement)
    yield ()

  /** `leiosContextFreeHeaderChecks` (`PolyPraos.hs:692-711`, ouroboros-consensus 2416493b): the
    * announced endorser block's size within the view's bound.
    */
  private def announcementSize(
      ledger: LedgerView,
      header: LeiosHeader
  ): Either[Praos2Error, Unit] =
    header.leios.announcement.traverse_ { a =>
      Either.cond(
        a.size <= ledger.maxEbReferencesSize.toLong,
        (),
        Praos2Error.LeiosEbTooBig(a.size, ledger.maxEbReferencesSize)
      )
    }

  /** The certificate checks precede the VRF checks (PolyPraos.hs:615-624,900-937,
    * ouroboros-consensus 2416493b). The reference adds KES periods and increments certificate
    * counters in unsigned machine words.
    */
  private def certificate(
      config: PraosConfig,
      knownPool: Boolean,
      state: PraosState,
      issuer: KeyHash[BlockIssuer],
      sequence: Word64,
      header: LeiosHeader
  ): Either[Failure, Unit] =
    for
      _ <- signatures(config, header)
      previous <- recorded(knownPool, state, issuer)
        .toRight(Praos2Error.Certificate(OCertError.NoCounter(issuer.bytes)))
      _ <- Either.cond(
        previous.toBigInt <= sequence.toBigInt,
        (),
        Praos2Error.CounterTooSmall(previous, sequence)
      )
      upper = Word64.fromUnsigned(previous.bits + 1)
      _ <- Either.cond(
        sequence.toBigInt <= upper.toBigInt,
        (),
        Praos2Error.CounterOverIncremented(previous, sequence)
      )
    yield ()

  /** `currentIssueNo` of `doValidateKESSignatureWorker` (`PolyPraos.hs:921-928`,
    * ouroboros-consensus 2416493b): the counter recorded for the issuer, or 0 for a pool of the
    * stake distribution without one.
    */
  private def recorded(
      knownPool: Boolean,
      state: PraosState,
      issuer: KeyHash[BlockIssuer]
  ): Option[Word64] =
    state.ocertCounters.get(issuer).orElse(Option.when(knownPool)(Word64.zero))

  /** The KES period's bounds and the two signatures, the counter's checks aside
    * (`doValidateKESSignatureWorker`, `PolyPraos.hs:893-905`, ouroboros-consensus 2416493b).
    */
  private def signatures(config: PraosConfig, header: LeiosHeader): Either[Failure, Unit] =
    val start = header.opCert.kesPeriod
    for
      _ <- Either.cond(
        config.slotsPerKesPeriod > 0,
        (),
        Praos2Defect.NonPositiveKesPeriod(config.slotsPerKesPeriod)
      )
      current = header.slot / config.slotsPerKesPeriod
      _ <- Either.cond(start <= current, (), Praos2Error.KesBeforeStart(start, current))
      end = Word64.fromUnsigned((start + config.maxKesEvolutions).toLong).toBigInt
      _ <- Either.cond(
        current < end,
        (),
        Praos2Error.KesAfterEnd(current, start, config.maxKesEvolutions)
      )
      _ <- config.verdicts
        .opCert(header.praosView, config.slotsPerKesPeriod, config.maxKesEvolutions)
        .refusedAs(Praos2Error.Certificate(_))
    yield ()
