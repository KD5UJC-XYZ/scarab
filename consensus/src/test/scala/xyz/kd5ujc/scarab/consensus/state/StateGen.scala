// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import cats.Show
import cats.syntax.all.*
import org.scalacheck.{Arbitrary, Cogen, Gen}
import xyz.kd5ujc.scarab.consensus.ByronState
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.ledger.state.{
  Allegra,
  Alonzo,
  Babbage,
  BlockIssuer,
  BlockNo,
  Conway,
  EpochNo,
  EraTag,
  Genesis,
  Hash32,
  KeyHash,
  Mary,
  NewEpochState,
  Shelley,
  ShelleyBasedEra,
  SlotNo,
  Sorted,
  WithOrigin,
  Word64
}
import xyz.kd5ujc.scarab.ledger.state.LedgerStateGen
import xyz.kd5ujc.scarab.ledger.state.VocabularyGen.{*, given}
import xyz.kd5ujc.scarab.protocol.Era

/** Generators of the consensus tree of the mirror: small values, the interesting part being which
  * era each is in. Every era's state is generated for the era, so a telescope is built through its
  * smart constructor.
  */
object StateGen:
  private def arb[A: Arbitrary]: Gen[A] = Arbitrary.arbitrary[A]

  given Arbitrary[Nonce] = Arbitrary(
    Gen.oneOf(Gen.const(Nonce.Neutral), bytes(32).map(Nonce.Hash(_)))
  )
  given Arbitrary[Word32] = Arbitrary(arb[Int].map(Word32.fromUnsigned))
  given Arbitrary[PerasRoundNo] = Arbitrary(arb[Word64].map(PerasRoundNo(_)))
  given Arbitrary[ShelleyTip] = Arbitrary(
    for
      slot <- arb[SlotNo]
      blockNo <- arb[BlockNo]
      hash <- arb[Hash32]
    yield ShelleyTip(slot, blockNo, hash)
  )
  given Arbitrary[ShelleyTransition] = Arbitrary(arb[Word32].map(ShelleyTransition(_)))

  def newEpochState[E <: EraTag](using ShelleyBasedEra[E]): Gen[NewEpochState[E]] =
    arb[EpochNo].map(NewEpochState.empty[E](_, LedgerStateGen.params))

  def shelleyLedgerState[E <: EraTag](using ShelleyBasedEra[E]): Gen[ShelleyLedgerState[E]] =
    for
      tip <- arb[WithOrigin[ShelleyTip]]
      nes <- newEpochState[E]
      transition <- arb[ShelleyTransition]
      peras <- Gen.option(arb[PerasRoundNo])
    yield ShelleyLedgerState[E](tip, nes, transition, peras)

  /** The ledger state of `era`: Byron's, or a packed Shelley-based one. */
  def eraLedgerState(era: Era): Gen[EraLedgerState] =
    def packed[E <: EraTag](using ShelleyBasedEra[E]): Gen[EraLedgerState] =
      shelleyLedgerState[E].map(EraLedgerState.inEra[E](_))
    era match
      case Era.Byron =>
        Gen
          .oneOf(ByronState.initial, ByronState.initial.copy(firstShelleyEpoch = Some(BigInt(4))))
          .map(EraLedgerState.ByronCurrent(_))
      case Era.Shelley => packed[Shelley]
      case Era.Allegra => packed[Allegra]
      case Era.Mary    => packed[Mary]
      case Era.Alonzo  => packed[Alonzo]
      case Era.Babbage => packed[Babbage]
      case Era.Conway  => packed[Conway]

  private val nonce: Gen[Nonce] = arb[Nonce]
  private val lastSlot: Gen[WithOrigin[SlotNo]] = arb[WithOrigin[SlotNo]]

  private val ocertCounters
      : Gen[scala.collection.immutable.SortedMap[KeyHash[BlockIssuer], Word64]] =
    Gen
      .listOfN(3, arb[KeyHash[BlockIssuer]].flatMap(k => arb[Word64].map(k -> _)))
      .map(Sorted.map(_))

  val pbftState: Gen[PBftState] =
    Gen
      .listOf(arb[SlotNo].flatMap(slot => arb[KeyHash[Genesis]].map(PBftSigner(slot, _))))
      .map(window => PBftState.ofWindow(window.toVector))

  val tpraosState: Gen[TPraosState] =
    for
      slot <- lastSlot
      counters <- ocertCounters
      evolving <- nonce
      candidate <- nonce
      epoch <- nonce
      prevHash <- nonce
      lab <- nonce
    yield TPraosState(
      slot,
      TPraosLedgerState(
        PrtclState(counters, evolving, candidate),
        TicknState(epoch, prevHash),
        lab
      )
    )

  val praosState: Gen[PraosState] =
    for
      slot <- lastSlot
      counters <- ocertCounters
      evolving <- nonce
      candidate <- nonce
      epoch <- nonce
      previous <- nonce
      lab <- nonce
      lastEpochBlock <- nonce
    yield PraosState(slot, counters, evolving, candidate, epoch, previous, lab, lastEpochBlock)

  /** The chain-dependent state of the protocol `era` runs. */
  def chainDepState(era: Era): Gen[ChainDepState] =
    if era == Era.Byron then pbftState.map(ChainDepState.PBft(_))
    else if era.usesTPraos then tpraosState.map(ChainDepState.TPraos(_))
    else praosState.map(ChainDepState.Praos(_))

  given Arbitrary[Bound] = Arbitrary(
    for
      time <- Gen.choose(0L, 1000L)
      slot <- Gen.choose(0L, 1000L)
      epoch <- Gen.choose(0L, 1000L)
    yield Bound(BigInt(time), BigInt(slot), BigInt(epoch))
  )

  /** `n + 1` bounds, each after the one before. */
  def bounds(n: Int): Gen[Vector[Bound]] =
    Gen
      .listOfN(n + 1, Gen.choose(1L, 50L))
      .map(
        _.scanLeft(0L)(_ + _).tail.toVector
          .map(k => Bound(BigInt(k) * 1000, BigInt(k), BigInt(k / 10)))
      )

  /** A telescope in `era` (so with `era.index` past eras), its state from `state`. */
  def telescope[A: EraIndexed](era: Era, state: Era => Gen[A]): Gen[Telescope[A]] =
    for
      bs <- bounds(era.index)
      current <- state(era)
      past = bs.zip(bs.drop(1)).take(era.index).map((s, e) => Past(s, e))
    yield Telescope
      .make(past, Current(bs(era.index), current))
      .fold(e => sys.error(e.show), identity)

  val anyEra: Gen[Era] = Gen.oneOf(Era.values.toSeq)

  val cardanoLedgerState: Gen[CardanoLedgerState] = anyEra.flatMap(telescope(_, eraLedgerState))

  val chainDepTelescope: Gen[Telescope[ChainDepState]] = anyEra.flatMap(telescope(_, chainDepState))

  val tipInfo: Gen[TipInfo] =
    Gen.oneOf(
      for
        hash <- arb[Hash32]
        ebb <- Gen.oneOf(IsEBB.values.toSeq)
      yield TipInfo.ByronTip(hash, ebb),
      arb[Hash32].map(TipInfo.ShelleyBasedTip(ShelleyBasedEra[Conway], _))
    )

  val annTip: Gen[AnnTip] =
    for
      slot <- arb[SlotNo]
      blockNo <- arb[BlockNo]
      info <- tipInfo
    yield AnnTip(slot, blockNo, info)

  val headerState: Gen[HeaderState] =
    for
      tip <- Gen.oneOf(Gen.const(WithOrigin.Origin), annTip.map(WithOrigin.At(_)))
      chainDep <- chainDepTelescope
    yield HeaderState(tip, chainDep)

  /** Both telescopes in the same era, as a node's state is. */
  val extLedgerState: Gen[ExtLedgerState] =
    for
      era <- anyEra
      ledger <- telescope(era, eraLedgerState)
      header <- headerState
      chainDep <- telescope(era, chainDepState)
    yield ExtLedgerState(ledger, header.copy(chainDep = chainDep))

  // the instances of the tree
  given Arbitrary[NewEpochState[Conway]] = Arbitrary(newEpochState[Conway])
  given Arbitrary[ShelleyLedgerState[Conway]] = Arbitrary(shelleyLedgerState[Conway])
  given Arbitrary[EraLedgerState] = Arbitrary(anyEra.flatMap(eraLedgerState))
  given Arbitrary[ChainDepState] = Arbitrary(anyEra.flatMap(chainDepState))
  given Arbitrary[PraosState] = Arbitrary(praosState)
  given Arbitrary[AnnTip] = Arbitrary(annTip)
  given Arbitrary[HeaderState] = Arbitrary(headerState)
  given Arbitrary[ExtLedgerState] = Arbitrary(extLedgerState)
  given Arbitrary[Option[PerasRoundNo]] = Arbitrary(Gen.option(arb[PerasRoundNo]))
  given cardanoLedgerArbitrary: Arbitrary[Telescope[EraLedgerState]] = Arbitrary(cardanoLedgerState)
  given chainDepArbitrary: Arbitrary[Telescope[ChainDepState]] = Arbitrary(chainDepTelescope)

  given eqOriginAnnTip: cats.Eq[WithOrigin[AnnTip]] = cats.Eq.instance(StateEq.withOrigin(_, _))
  given eqOriginShelleyTip: cats.Eq[WithOrigin[ShelleyTip]] =
    cats.Eq.instance(StateEq.withOrigin(_, _))

  private def cogen[A: Show]: Cogen[A] = cogenShow
  given cogen1: Cogen[Bound] = cogen
  given cogen2: Cogen[Nonce] = cogen
  given cogen3: Cogen[NewEpochState[Conway]] = cogen
  given cogen4: Cogen[ShelleyLedgerState[Conway]] = cogen
  given cogen5: Cogen[ShelleyTransition] = cogen
  given cogen6: Cogen[EraLedgerState] = cogen
  given cogen7: Cogen[ChainDepState] = cogen
  given cogen8: Cogen[PraosState] = cogen
  given cogen9: Cogen[HeaderState] = cogen
  given cogen10: Cogen[ExtLedgerState] = cogen
  given cogen11: Cogen[Option[PerasRoundNo]] = cogen
  given cogen12: Cogen[WithOrigin[ShelleyTip]] = cogen
  given cogen13: Cogen[WithOrigin[SlotNo]] = cogen
  given cogen14: Cogen[WithOrigin[AnnTip]] = cogen
  given cardanoLedgerCogen: Cogen[Telescope[EraLedgerState]] = cogen
  given chainDepCogen: Cogen[Telescope[ChainDepState]] = cogen
