// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.Order
import cats.syntax.eq.*
import cats.syntax.show.*
import xyz.kd5ujc.scarab.consensus.state.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.{Codec, ConstraintViolation, Decoded, Node, Writer}
import xyz.kd5ujc.scarab.protocol.Era

import Envelope.*
import Wire.*

/** The consensus layer's part of the `state` file: the era-wrapped ledger state, the header state
  * with its tip and the protocols' chain-dependent states. Sources: `ouroboros-consensus` 4.2.1.0
  * and `ouroboros-consensus-protocol` (the pinned tree) and `cardano-protocol-tpraos` 1.x of the
  * pinned ledger; each codec names the lines.
  */
trait ConsensusCodecs extends EpochStateCodecs:
  // ---- the ledger state of a Shelley-based era

  /** `ShelleyTip` (`Shelley/Ledger/Ledger.hs:828-840`): `[slot, block number, hash]`. */
  given shelleyTip: Codec[ShelleyTip] = Record.auto[ShelleyTip]

  /** `ShelleyTransition` (`Ledger.hs:855-864`): a bare `Word32`. */
  given shelleyTransition: Codec[ShelleyTransition] =
    word32.imap(v => ShelleyTransition(Word32.fromUnsigned(v.toInt)))(t =>
      BigInt(t.afterVoting.toLong)
    )

  /** `PerasCertRound` (`SupportsPeras.hs:85`, a `Word64`). */
  given perasRound: Codec[PerasRoundNo] = word64.imap(PerasRoundNo(_))(_.word)

  /** `LedgerState (ShelleyBlock proto era)` (`Ledger.hs:866-912`): `[2, [tip, new-epoch state,
    * transition, latest Peras certificate round]]`. Only version 2 is read (the pinned decoder
    * reads no other). The state is written at the era's own version (`toCBOR`, [[Versions]]).
    */
  def shelleyLedgerState[E <: EraTag](using ShelleyBasedEra[E]): Codec[ShelleyLedgerState[E]] =
    versioned(
      2,
      Record.of[ShelleyLedgerState[E]](
        (
          withOriginMaybe(shelleyTip),
          newEpochState[E],
          shelleyTransition,
          strictMaybe(perasRound)
        )
      )
    )

  // ---- the era-wrapped ledger state of the telescope

  private def readIn[E <: EraTag](era: ShelleyBasedEra[E], n: Node): Decoded[LedgerFileEra] =
    given ShelleyBasedEra[E] = era
    shelleyLedgerState[E].decode(n).map(s => LedgerFileEra.Mirrored(EraLedgerState.inEra(s)))

  private def writeIn[E <: EraTag](
      era: ShelleyBasedEra[E],
      state: ShelleyLedgerState[E],
      out: Writer
  ): Unit =
    given ShelleyBasedEra[E] = era
    shelleyLedgerState[E].encode(state, out)

  private def checkIn[E <: EraTag](
      era: ShelleyBasedEra[E],
      state: ShelleyLedgerState[E]
  ): Option[ConstraintViolation] =
    given ShelleyBasedEra[E] = era
    shelleyLedgerState[E].check(state)

  private def readFileEra(era: Era)(n: Node): Decoded[LedgerFileEra] = era match
    case Era.Byron   => originalItem.decode(n).map(LedgerFileEra.ByronItem(_))
    case Era.Shelley => readIn(ShelleyBasedEra.SShelley, n)
    case Era.Allegra => readIn(ShelleyBasedEra.SAllegra, n)
    case Era.Mary    => readIn(ShelleyBasedEra.SMary, n)
    case Era.Alonzo  => readIn(ShelleyBasedEra.SAlonzo, n)
    case Era.Babbage => readIn(ShelleyBasedEra.SBabbage, n)
    case Era.Conway  => readIn(ShelleyBasedEra.SConway, n)

  private def writeFileEra(a: LedgerFileEra, out: Writer): Unit = a match
    case LedgerFileEra.ByronItem(item) => originalItem.encode(item, out)
    case LedgerFileEra.Mirrored(EraLedgerState.InShelleyEra(era, state)) =>
      writeIn(era, state, out)
    case LedgerFileEra.Mirrored(EraLedgerState.ByronCurrent(_)) =>
      () // refused by `checkFileEra`; a decoded telescope never holds it

  private def checkFileEra(a: LedgerFileEra): Option[ConstraintViolation] = a match
    case LedgerFileEra.ByronItem(_)                                      => None
    case LedgerFileEra.Mirrored(EraLedgerState.InShelleyEra(era, state)) => checkIn(era, state)
    case LedgerFileEra.Mirrored(EraLedgerState.ByronCurrent(_)) =>
      Some(
        ConstraintViolation(
          "LedgerFileEra",
          "Byron's state as the original item (ByronItem)",
          "ByronCurrent"
        )
      )

  /** The ledger state of the era at index `era` of the telescope: a Shelley-based era's read as the
    * mirror's, Byron's kept as the original item (its ledger state, the `ChainValidationState`, is
    * not modelled).
    */
  val ledgerFileEra: Era => Codec[LedgerFileEra] =
    eraCodec(readFileEra, writeFileEra, checkFileEra)

  // ---- the protocols' chain-dependent states

  /** `PBftSigner` as the inverted state holds it: the slot alone (`State.hs:300-308`). */
  private val signerSlots: Codec[Vector[SlotNo]] = new Codec[Vector[SlotNo]]:
    def decode(n: Node): Decoded[Vector[SlotNo]] = seqOf(slotNo).decode(n)

    /** `Serialise [a]` (`serialise`): indefinite when not empty. */
    def encode(a: Vector[SlotNo], out: Writer): Unit =
      if a.isEmpty then out.arrayHead(0)
      else
        out.indefiniteArray()
        a.foreach(slotNo.encode(_, out))
        out.break()

  /** `PBftState` (`Protocol/PBFT/State.hs:300-317`): `[1, {genesis key: [slots, newest first]}]`.
    * The signers in the window are written grouped by key and the counts not at all, so they are
    * recomputed on reading (`computeCounts`, `uninvert`); a state whose counts are not those of its
    * window is not writable.
    */
  given pbftState: Codec[PBftState] =
    val keyed = plainMapOf(keyHash[Genesis], signerSlots)
    versioned(
      1,
      new Codec[PBftState]:
        def decode(n: Node): Decoded[PBftState] =
          keyed.decode(n).map { byKey =>
            val window = byKey.toVector
              .flatMap((key, slots) => slots.map(PBftSigner(_, key)))
              .sortBy(_.slot)(using Order[SlotNo].toOrdering)
            PBftState.ofWindow(window)
          }
        def encode(a: PBftState, out: Writer): Unit =
          val inverted =
            Sorted.map(a.inWindow.groupMap(_.genesisKey)(_.slot).view.mapValues(_.reverse))
          keyed.encode(inverted, out)
        override def check(a: PBftState): Option[ConstraintViolation] =
          Option.unless(a.counts === PBftState.ofWindow(a.inWindow).counts)(
            ConstraintViolation(
              "PBftState",
              "the counts of the signers in the window",
              a.counts.show
            )
          )
    )

  private val shelleyProtocolVersion: ProtVer = Versions.eraLow(ShelleyBasedEra.SShelley)

  /** `PrtclState` (`TPraos/Rules/Prtcl.hs:77-85`), at the Shelley version: `[counters, evolving,
    * candidate]`.
    */
  given prtclState: Codec[PrtclState] =
    given ProtVer = shelleyProtocolVersion
    Record.of[PrtclState]((mapOf(keyHash[BlockIssuer], word64), nonce, nonce))

  /** `TicknState` (`Tickn.hs:44-51`): `[epoch nonce, previous hash nonce]`. */
  given ticknState: Codec[TicknState] = Record.auto[TicknState]

  /** The ledger's `ChainDepState` (`TPraos/API.hs:477-490`): `[protocol, tickn, lab nonce]`. */
  given tpraosLedgerState: Codec[TPraosLedgerState] = Record.auto[TPraosLedgerState]

  /** `TPraosState` (`Protocol/TPraos.hs:281-296`): `[1, [last slot, chain-dependent state]]`. The
    * last slot is written by `encodeWithOrigin` (`[]` or `[slot]`), unlike Praos's (`toCBOR`).
    */
  given tpraosState: Codec[TPraosState] =
    versioned(1, Record.of[TPraosState]((withOriginMaybe(slotNo), tpraosLedgerState)))

  /** `PraosState` (`Protocol/Praos.hs:281-308`): `[0, [last slot, counters, six nonces]]`, the
    * counters in the plain (definite-length) form.
    */
  given praosState: Codec[PraosState] =
    versioned(
      0,
      Record.of[PraosState](
        (
          withOriginTagged(slotNo),
          plainMapOf(keyHash[BlockIssuer], word64),
          nonce,
          nonce,
          nonce,
          nonce,
          nonce,
          nonce
        )
      )
    )

  private def readChainDep(era: Era)(n: Node): Decoded[ChainDepState] =
    if era == Era.Byron then pbftState.decode(n).map(ChainDepState.PBft(_))
    else if era.usesTPraos then tpraosState.decode(n).map(ChainDepState.TPraos(_))
    else praosState.decode(n).map(ChainDepState.Praos(_))

  private def writeChainDep(a: ChainDepState, out: Writer): Unit = a match
    case ChainDepState.PBft(s)   => pbftState.encode(s, out)
    case ChainDepState.TPraos(s) => tpraosState.encode(s, out)
    case ChainDepState.Praos(s)  => praosState.encode(s, out)

  private def checkChainDep(a: ChainDepState): Option[ConstraintViolation] = a match
    case ChainDepState.PBft(s)   => pbftState.check(s)
    case ChainDepState.TPraos(s) => tpraosState.check(s)
    case ChainDepState.Praos(s)  => praosState.check(s)

  /** The chain-dependent state of the era at index `era`: read as the protocol of that era (PBFT,
    * TPraos or Praos), written as the protocol the value is.
    */
  val chainDepStateOf: Era => Codec[ChainDepState] =
    eraCodec(readChainDep, writeChainDep, checkChainDep)
