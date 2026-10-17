// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import org.scalacheck.{Arbitrary, Gen}
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.consensus.{BlockRef, BootstrapState, ByronState, ByronTip, HardForkConfig}
import xyz.kd5ujc.scarab.consensus.state.{
  ChainDepState,
  ConsensusMirrorEnv,
  EraHistory,
  ProtocolExtras,
  ProtocolState,
  StateGen
}
import xyz.kd5ujc.scarab.ledger.{LiteLedger, LiteParams, Nonce}
import xyz.kd5ujc.scarab.ledger.byron.{ByronLedger, ByronLedgerState, ByronTestKit}
import xyz.kd5ujc.scarab.ledger.state.{FlatStateGen, SlotNo, VocabularyGen, WithOrigin}
import xyz.kd5ujc.scarab.legacy as flat
import xyz.kd5ujc.scarab.protocol.Era

/** A state of the node and the block it is after (none: before the first). */
final case class Located(anchor: Option[BlockRef], state: FlatNodeState)

/** Generators of the node's whole state, era first: pick the era, then a state that the
  * configuration of [[env]] puts in it (each Shelley-based era starts two epochs after the one
  * before; the epoch is its first or its second), with the anchor in that epoch.
  */
object NodeStateGen:
  /** Shelley is entered at epoch 0 and each later era two epochs on. */
  private val firstEpoch: Map[Era, Long] =
    Map(
      Era.Byron -> 0L,
      Era.Shelley -> 0L,
      Era.Allegra -> 2L,
      Era.Mary -> 4L,
      Era.Alonzo -> 6L,
      Era.Babbage -> 8L,
      Era.Conway -> 10L
    )

  val env: ConsensusMirrorEnv = ConsensusMirrorEnv(
    FlatStateGen.env,
    HardForkConfig.fromEpochs(
      firstEpoch.view.filterKeys(e => e != Era.Byron && e != Era.Shelley).mapValues(BigInt(_)).toMap
    ),
    EraHistory.ByronParams(50, 1000, 10)
  )

  private val nonce: Gen[Nonce] =
    Gen.oneOf(Gen.const(Nonce.Neutral), VocabularyGen.bytes(32).map(Nonce.Hash(_)))

  /** The chain-dependent state of the protocol of `era` (Byron holds the state Shelley starts
    * from), as of the slot `at` of the last header.
    */
  private def protocolState(era: Era, epoch: BigInt, at: WithOrigin[SlotNo]): Gen[ProtocolState] =
    if era == Era.Byron || era.usesTPraos then
      for
        t <- StateGen.tpraosState
        previous <- nonce
      yield ProtocolState(
        ChainDepState.TPraos(t.copy(lastSlot = if era == Era.Byron then WithOrigin.Origin else at)),
        ProtocolExtras(epoch, previous)
      )
    else
      StateGen.praosState.map(p =>
        ProtocolState(
          ChainDepState.Praos(p.copy(lastSlot = at)),
          ProtocolExtras(epoch, Nonce.Neutral)
        )
      )

  private val byronLedger: ByronLedgerState =
    val keys = Vector(ByronTestKit.key(), ByronTestKit.key())
    ByronLedger
      .initial(ByronLedger.Config(ByronTestKit.genesis(keys, keys), requiresNetworkMagic = false))
      .fold(e => sys.error(e), identity)

  private val lite: LiteLedger = LiteLedger(
    0,
    LiteParams((BigInt(6), BigInt(0)), Rational(1), Nonce.Neutral, 65536, 16384, 1100),
    Map.empty,
    Vector.empty,
    Map.empty,
    Map.empty,
    Map.empty,
    2
  )

  private def byronState(inByron: Boolean): Gen[ByronState] =
    for
      pbft <- StateGen.pbftState
      tipHash <- VocabularyGen.bytes(32)
      tip <- Gen.option(Gen.oneOf(true, false).map(ByronTip(tipHash, 0, 7, _)))
    yield ByronState(
      tip,
      None,
      None,
      Option.when(inByron)(byronLedger),
      pbft,
      Option.when(!inByron)(BigInt(0))
    )

  /** A state of the node in `era`, after a block of its first or second epoch (or before any). */
  private def located(era: Era): Gen[Located] =
    val inByron = era == Era.Byron
    for
      e <- Gen.choose(firstEpoch(era), firstEpoch(era) + 1)
      offset <- Gen.choose(0L, 49L)
      slot = if inByron then offset else offset + e * 100
      hash <- VocabularyGen.bytes(32)
      blockNo <- Gen.choose(0L, 1000L)
      boundary <- if inByron then Gen.oneOf(true, false) else Gen.const(false)
      anchor <- Gen.frequency(
        1 -> Gen.const(None),
        9 -> Gen.const(Some(BlockRef(slot, hash, blockNo, boundary)))
      )
      ledger <- FlatStateGen.state(conway = era == Era.Conway)
      bootEra <- if inByron then Gen.const(None) else Gen.option(Gen.const(era))
      at = anchor
        .flatMap(a => SlotNo.fromBigInt(a.slot).toOption)
        .fold[WithOrigin[SlotNo]](WithOrigin.Origin)(WithOrigin.At(_))
      p <- protocolState(era, BigInt(e), at)
      byron <- byronState(inByron)
    yield Located(
      anchor,
      FlatNodeState(
        p,
        ledger.copy(epoch = BigInt(e)),
        BootstrapState(active = false, bootEra, lite, None, None),
        byron
      )
    )

  /** A state in any of `eras`. */
  def of(eras: Era*): Gen[Located] = Gen.oneOf(eras).flatMap(located)

  val inByron: Gen[Located] = of(Era.Byron)
  val inTPraosEras: Gen[Located] = of(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo)
  val inPraosEras: Gen[Located] = of(Era.Babbage, Era.Conway)

  given Arbitrary[Located] = Arbitrary(of(Era.values.toSeq*))
