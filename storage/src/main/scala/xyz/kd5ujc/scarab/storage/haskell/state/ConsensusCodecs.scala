// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.Order
import cats.syntax.eq.*
import cats.syntax.show.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.consensus.Praos2
import xyz.kd5ujc.scarab.consensus.state.*
import xyz.kd5ujc.scarab.ledger.EbAnnouncement
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.{
  Arr,
  Codec,
  ConstraintViolation,
  DecodeError,
  Decoded,
  Node,
  Writer,
  at
}
import xyz.kd5ujc.scarab.protocol.{DijkstraNotYet, EbHash, Era, StateLayout}

import Envelope.*
import Wire.*

/** The consensus layer's part of the `state` file: the era-wrapped ledger state, the header state
  * with its tip and the protocols' chain-dependent states. Sources: `ouroboros-consensus` 4.2.1.0
  * and `ouroboros-consensus-protocol` (the pinned tree) and `cardano-protocol-tpraos` 1.x of the
  * pinned ledger; each codec names the lines.
  */
trait ConsensusCodecs extends EpochStateCodecs:
  // ---- the ledger state of a Shelley-based era

  /** `ShelleyTip` (`Shelley/Ledger/Ledger.hs:828-840`, ouroboros-consensus 82ecba32): `[slot, block
    * number, hash]`.
    */
  given shelleyTip: Codec[ShelleyTip] = arrayIn(stateLayout, Record.auto[ShelleyTip])

  /** `ShelleyTransition` (`Ledger.hs:855-864`, ouroboros-consensus 82ecba32): a bare `Word32`. */
  given shelleyTransition: Codec[ShelleyTransition] =
    word32.imap(v => ShelleyTransition(Word32.fromUnsigned(v.toInt)))(t =>
      BigInt(t.afterVoting.toLong)
    )

  /** `PerasRoundNo` (`SupportsPeras.hs:85`, ouroboros-consensus 82ecba32, a `Word64`). */
  given perasRound: Codec[PerasRoundNo] = word64.imap(PerasRoundNo(_))(_.word)

  /** The fourth field of the Shelley ledger state in this codec's layout
    * ([[ShelleyLedgerState.Layout]]): the latest Peras certificate round, a `StrictMaybe`
    * (`encodeStrictMaybe`, 82ecba32 `Ledger.hs:866-912`), in the 11.1 layout; the cumulative
    * transaction bytes, a `Word64` (`toCBOR`/`fromCBOR`, 2416493b `Ledger.hs:885-922`), in the
    * Leios layout.
    */
  private val shelleyLedgerLayout: Codec[ShelleyLedgerState.Layout] =
    new Codec[ShelleyLedgerState.Layout]:
      private val round = strictMaybe(perasRound)
      def decode(n: Node): Decoded[ShelleyLedgerState.Layout] = stateLayout match
        case StateLayout.Node11_1 => round.decode(n).map(ShelleyLedgerState.Layout.Node11_1(_))
        case StateLayout.Leios    => word64.decode(n).map(ShelleyLedgerState.Layout.Leios(_))
      def encode(a: ShelleyLedgerState.Layout, out: Writer): Unit = a match
        case ShelleyLedgerState.Layout.Node11_1(r) => round.encode(r, out)
        case ShelleyLedgerState.Layout.Leios(b)    => word64.encode(b, out)
      override def check(a: ShelleyLedgerState.Layout): Option[ConstraintViolation] =
        ofThisLayout("ShelleyLedgerState", a)

  /** `LedgerState (ShelleyBlock proto era)` (`Ledger.hs:866-912`, ouroboros-consensus 82ecba32):
    * `[2, [tip, new-epoch state, transition, fourth field]]`, the fourth by layout
    * ([[shelleyLedgerLayout]]). Only version 2 is read (the decoders of both pins read no other).
    * The state is written at the era's own version (`toCBOR`, [[Versions]]).
    */
  def shelleyLedgerState[E <: EraTag](using ShelleyBasedEra[E]): Codec[ShelleyLedgerState[E]] =
    versionedIn(stateLayout)(
      2,
      arrayIn(
        stateLayout,
        Record.of[ShelleyLedgerState[E]](
          (
            withOriginMaybe(shelleyTip, stateLayout),
            newEpochState[E],
            shelleyTransition,
            shelleyLedgerLayout
          )
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
    // Dijkstra's state is the Leios build's in its layout; the 11.1 line's is not read
    case Era.Dijkstra =>
      stateLayout match
        case StateLayout.Leios => readIn(ShelleyBasedEra.SDijkstra, n)
        case StateLayout.Node11_1 =>
          Left(DecodeError.at(n, DecodeError.Kind.Domain(DijkstraNotYet.LedgerState)))

  private def writeFileEra(a: LedgerFileEra, out: Writer): Unit = a match
    case LedgerFileEra.ByronItem(item) => originalItem.encode(item, out)
    case LedgerFileEra.Mirrored(EraLedgerState.InShelleyEra(era, state)) =>
      writeIn(era, state, out)
    case LedgerFileEra.Mirrored(EraLedgerState.ByronCurrent(_)) =>
      () // refused by `checkFileEra`; a decoded telescope never holds it

  /** A Dijkstra state is written in the Leios build's layout only: the 11.1 line's Dijkstra state
    * codec refuses every value ([[Versions.notYet]]), named here as the era.
    */
  private def checkFileEra(a: LedgerFileEra): Option[ConstraintViolation] = a match
    case LedgerFileEra.ByronItem(_) => None
    case LedgerFileEra.Mirrored(EraLedgerState.InShelleyEra(ShelleyBasedEra.SDijkstra, state)) =>
      stateLayout match
        case StateLayout.Leios => checkIn(ShelleyBasedEra.SDijkstra, state)
        case StateLayout.Node11_1 =>
          Some(
            ConstraintViolation(
              "LedgerFileEra",
              s"the state of an era the $stateLayout layout writes",
              "Dijkstra"
            )
          )
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

  /** `PBftSigner` as the inverted state holds it: the slot alone (`State.hs:300-308`,
    * ouroboros-consensus 82ecba32).
    */
  private val signerSlots: Codec[Vector[SlotNo]] = new Codec[Vector[SlotNo]]:
    def decode(n: Node): Decoded[Vector[SlotNo]] = seqOf(slotNo).decode(n)

    /** `Serialise [a]` (`serialise`): indefinite when not empty. */
    def encode(a: Vector[SlotNo], out: Writer): Unit =
      if a.isEmpty then out.arrayHead(0)
      else
        out.indefiniteArray()
        a.foreach(slotNo.encode(_, out))
        out.break()

  /** `PBftState` (`Protocol/PBFT/State.hs:300-317`, ouroboros-consensus 82ecba32): `[1, {genesis
    * key: [slots, newest first]}]`. The signers in the window are written grouped by key and the
    * counts not at all, so they are recomputed on reading (`computeCounts`, `uninvert`); a state
    * whose counts are not those of its window is not writable.
    */
  given pbftState: Codec[PBftState] =
    val keyed = plainMapOf(keyHash[Genesis], signerSlots)
    versionedIn(stateLayout)(
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

  /** `PrtclState` (`TPraos/Rules/Prtcl.hs:77-85`, cardano-ledger f649f975), at the Shelley version:
    * `[counters, evolving, candidate]`.
    */
  given prtclState: Codec[PrtclState] =
    given ProtVer = shelleyProtocolVersion
    Record.of[PrtclState]((mapOf(keyHash[BlockIssuer], word64), nonce, nonce))

  /** `TicknState` (`Tickn.hs:44-51`, cardano-ledger f649f975): `[epoch nonce, previous hash
    * nonce]`.
    */
  given ticknState: Codec[TicknState] = Record.auto[TicknState]

  /** The ledger's `ChainDepState` (`TPraos/API.hs:477-490`, cardano-ledger f649f975): `[protocol,
    * tickn, lab nonce]`.
    */
  given tpraosLedgerState: Codec[TPraosLedgerState] = Record.auto[TPraosLedgerState]

  /** `TPraosState` (`Protocol/TPraos.hs:281-296`, ouroboros-consensus 82ecba32): `[1, [last slot,
    * chain-dependent state]]`. The last slot is written by `encodeWithOrigin` (`[]` or `[slot]`),
    * unlike Praos's (`toCBOR`).
    */
  given tpraosState: Codec[TPraosState] =
    versionedIn(stateLayout)(
      1,
      arrayIn(
        stateLayout,
        Record.of[TPraosState]((withOriginMaybe(slotNo, stateLayout), tpraosLedgerState))
      )
    )

  /** The last slot of a Praos state, written by `toCBOR` (`[0]` or `[1, slot]`). */
  private val praosLastSlot: Codec[WithOrigin[SlotNo]] =
    arrayIn(stateLayout, withOriginTagged(slotNo))

  /** The operational certificate counters of a Praos state, in the plain (definite-length) form. */
  private val praosCounters: Codec[SortedMap[KeyHash[BlockIssuer], Word64]] =
    plainMapOf(keyHash[BlockIssuer], word64)

  /** The eight fields of a Praos state, as a record. */
  private val praosFields: Codec[PraosState] =
    Record.of[PraosState](
      (praosLastSlot, praosCounters, nonce, nonce, nonce, nonce, nonce, nonce)
    )

  /** `PraosState` (`Protocol/Praos.hs:281-308`, ouroboros-consensus 82ecba32): `[0, [last slot,
    * counters, six nonces]]`, the counters in the plain (definite-length) form.
    */
  given praosState: Codec[PraosState] =
    versionedIn(stateLayout)(0, arrayIn(stateLayout, praosFields))

  /** The endorser block hash of an announcement: a `SafeHash` of 32 bytes (`extractHash`,
    * `unsafeMakeSafeHash`, PolyPraos.hs:341-348, ouroboros-consensus 2416493b).
    */
  private val ebHash: Codec[EbHash] = hash(32).imap(EbHash.fromBytes)(_.bytes)

  /** The size of an announced endorser block, a `Word32` (`ebReferencesAnnouncementSize`,
    * cardano-ledger 9df9f98c `Block.hs:169-173`).
    */
  private val ebSize: Codec[Long] = Codec.uintLong(0xffffffffL)

  /** `AnnouncedBy` (`encodeAnnouncedBy`, `decodeAnnouncedBy`, PolyPraos.hs:333-348,
    * ouroboros-consensus 2416493b): `[issuer, endorser block hash, size]`, the announcement's two
    * fields inline.
    */
  private val announcedBy: Codec[Praos2.AnnouncedBy] = arrayIn(
    stateLayout,
    new Codec[Praos2.AnnouncedBy]:
      def decode(n: Node): Decoded[Praos2.AnnouncedBy] =
        Arr.exact(n, 3).flatMap { xs =>
          for
            issuer <- keyHash[BlockIssuer].decode(xs(0)).at("issuer")
            hash <- ebHash.decode(xs(1)).at("hash")
            size <- ebSize.decode(xs(2)).at("size")
          yield Praos2.AnnouncedBy(issuer, EbAnnouncement(hash, size))
        }
      def encode(a: Praos2.AnnouncedBy, out: Writer): Unit =
        out.arrayHead(3)
        keyHash[BlockIssuer].encode(a.issuer, out)
        ebHash.encode(a.announcement.hash, out)
        ebSize.encode(a.announcement.size, out)
      override def check(a: Praos2.AnnouncedBy): Option[ConstraintViolation] =
        ebHash
          .check(a.announcement.hash)
          .map(_.at("hash"))
          .orElse(ebSize.check(a.announcement.size).map(_.at("size")))
  )

  /** The announcement of a Praos2 state: `encodeStrictMaybe encodeAnnouncedBy`, `[]` or `[x]`
    * (PolyPraos.hs:392,418, ouroboros-consensus 2416493b; `Util/CBOR.hs:358-362`).
    */
  private val praos2Announcement: Codec[Option[Praos2.AnnouncedBy]] =
    arrayIn(stateLayout, strictMaybe(announcedBy))

  /** `PolyPraosState (Praos2 c)` (`Serialise`, PolyPraos.hs:368-419, ouroboros-consensus 2416493b),
    * the chain-dependent state of Praos2 (`Praos2.hs:117`): `[1, [last slot, counters, six nonces,
    * announcement]]`, the eight fields of Praos's and the announcement, at version 1. Only the
    * Leios build has it; this codec does not write it in another layout.
    */
  given praos2State: Codec[Praos2.State] =
    val fields = new Codec[Praos2.State]:
      def decode(n: Node): Decoded[Praos2.State] =
        Arr.exact(n, 9).flatMap { xs =>
          for
            lastSlot <- praosLastSlot.decode(xs(0)).at("lastSlot")
            counters <- praosCounters.decode(xs(1)).at("ocertCounters")
            evolving <- nonce.decode(xs(2)).at("evolvingNonce")
            candidate <- nonce.decode(xs(3)).at("candidateNonce")
            epoch <- nonce.decode(xs(4)).at("epochNonce")
            previous <- nonce.decode(xs(5)).at("previousEpochNonce")
            lab <- nonce.decode(xs(6)).at("labNonce")
            lastEpochBlock <- nonce.decode(xs(7)).at("lastEpochBlockNonce")
            announcement <- praos2Announcement.decode(xs(8)).at("announcement")
          yield Praos2.State(
            PraosState(
              lastSlot,
              counters,
              evolving,
              candidate,
              epoch,
              previous,
              lab,
              lastEpochBlock
            ),
            announcement
          )
        }
      def encode(a: Praos2.State, out: Writer): Unit =
        val p = a.praos
        out.arrayHead(9)
        praosLastSlot.encode(p.lastSlot, out)
        praosCounters.encode(p.ocertCounters, out)
        nonce.encode(p.evolvingNonce, out)
        nonce.encode(p.candidateNonce, out)
        nonce.encode(p.epochNonce, out)
        nonce.encode(p.previousEpochNonce, out)
        nonce.encode(p.labNonce, out)
        nonce.encode(p.lastEpochBlockNonce, out)
        praos2Announcement.encode(a.announcement, out)
      override def check(a: Praos2.State): Option[ConstraintViolation] =
        praosFields
          .check(a.praos)
          .orElse(praos2Announcement.check(a.announcement).map(_.at("announcement")))
    val versioned = versionedIn(stateLayout)(1, arrayIn(stateLayout, fields))
    new Codec[Praos2.State]:
      def decode(n: Node): Decoded[Praos2.State] = versioned.decode(n)
      def encode(a: Praos2.State, out: Writer): Unit = versioned.encode(a, out)
      override def check(a: Praos2.State): Option[ConstraintViolation] = stateLayout match
        case StateLayout.Leios => versioned.check(a)
        case StateLayout.Node11_1 =>
          Some(
            ConstraintViolation(
              "PolyPraosState",
              s"a state of the ${StateLayout.Leios.toString} layout",
              stateLayout.toString
            )
          )

  /** The protocol of Dijkstra is the build's: Praos in 11.1 (`ShelleyBlock (Praos c) DijkstraEra`,
    * ouroboros-consensus 82ecba32 `Cardano/Block.hs:256`), Praos2 in the Leios build (`ShelleyBlock
    * (Praos2 c) DijkstraEra`, 2416493b `Cardano/Block.hs:277`).
    */
  private def readChainDep(era: Era)(n: Node): Decoded[ChainDepState] = era match
    case Era.Byron => pbftState.decode(n).map(ChainDepState.PBft(_))
    case Era.Shelley | Era.Allegra | Era.Mary | Era.Alonzo =>
      tpraosState.decode(n).map(ChainDepState.TPraos(_))
    case Era.Babbage | Era.Conway => praosState.decode(n).map(ChainDepState.Praos(_))
    case Era.Dijkstra =>
      stateLayout match
        case StateLayout.Leios    => praos2State.decode(n).map(ChainDepState.Praos2(_))
        case StateLayout.Node11_1 => praosState.decode(n).map(ChainDepState.Praos(_))

  private def writeChainDep(a: ChainDepState, out: Writer): Unit = a match
    case ChainDepState.PBft(s)   => pbftState.encode(s, out)
    case ChainDepState.TPraos(s) => tpraosState.encode(s, out)
    case ChainDepState.Praos(s)  => praosState.encode(s, out)
    case ChainDepState.Praos2(s) => praos2State.encode(s, out)

  private def checkChainDep(a: ChainDepState): Option[ConstraintViolation] = a match
    case ChainDepState.PBft(s)   => pbftState.check(s)
    case ChainDepState.TPraos(s) => tpraosState.check(s)
    case ChainDepState.Praos(s)  => praosState.check(s)
    case ChainDepState.Praos2(s) => praos2State.check(s)

  /** The chain-dependent state of the era at index `era`: read as the protocol of that era (PBFT,
    * TPraos, Praos or Praos2), written as the protocol the value is.
    */
  val chainDepStateOf: Era => Codec[ChainDepState] =
    eraCodec(readChainDep, writeChainDep, checkChainDep)
