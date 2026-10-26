// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import java.nio.file.Path
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, GenesisMirror, PraosState}
import xyz.kd5ujc.scarab.ledger.{Nonce, TxIn}
import xyz.kd5ujc.scarab.ledger.byron.ByronOut
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, GenesisStart, Globals, Handover}
import xyz.kd5ujc.scarab.ledger.state.{EpochNo, ShelleyBasedEra, Sorted, WithOrigin}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
import munit.FunSuite

/** The era-history query on a network that starts in Byron, at a tip years after the system start.
  * `cardano-cli query tip` asks for it (to place the tip in time). Two things broke it on preprod:
  * the Byron span of such a chain is open-ended in the configuration until the chain has entered
  * Shelley (an answer written from the configuration alone puts the first Shelley epoch a million
  * epochs out), and a time in picoseconds does not fit 64 bits after about 213 days (the reference
  * writes an `Integer`, a big number beyond 64 bits). Either made the encoder throw, and the
  * connection died without an answer.
  */
class EraHistoryNetworksSuite extends FunSuite:
  private val setup = NodeSetup
    .fromDirectory(Path.of("fixtures", "preprod", "genesis"))
    .fold(e => fail(e.message), identity)

  private val env = QueryEnv(
    setup.genesis.geometry.systemStart,
    setup.geometry,
    setup.praos.forks,
    setup.mirrorEnv().byron,
    setup.globals.maxSupply,
    setup.genesis.raw,
    setup.mirrorEnv().ledger,
    networkMagic = setup.genesis.networkMagic.toLong
  )

  /** The state of a chain in Conway whose Byron era ended at epoch 4, at slot 100,000,000: the
    * genesis Byron UTxO handed over to Shelley at epoch 4 and translated to Conway, with a Praos
    * state, at the origin of telescopes placed on the era history of that Byron span.
    */
  private val view =
    val firstShelley = BigInt(4)
    val neutral = Nonce.Neutral
    val praos = PraosState(
      WithOrigin.Origin,
      Sorted.map(List.empty),
      neutral,
      neutral,
      neutral,
      neutral,
      neutral,
      neutral
    )
    val start = setup.mirrorStart
    val geometry = setup.geometry.resolved(firstShelley)
    val env = setup.mirrorEnv()
    val byronUtxo = start.consensus.byron.ledger.fold(Map.empty[TxIn, ByronOut])(_.utxo)
    val context = setup.handOver.getOrElse(fail("preprod starts in Byron: it has a hand-over"))
    val shelley = Handover
      .toShelley(context, EpochNo.fromUnsigned(firstShelley.toLong), byronUtxo)
      .fold(e => fail(s"the hand-over refuses: $e"), identity)
    val conway = GenesisStart
      .translateTo(
        Globals(setup.globals, geometry),
        Era.Conway,
        EraNes.InEra(ShelleyBasedEra.SShelley, shelley)
      )
      .fold(e => fail(s"no translation to Conway: $e"), identity)
    val state = GenesisMirror
      .of(
        env.copy(ledger = env.ledger.copy(geometry = geometry)),
        conway,
        ChainDepState.Praos(praos),
        start.consensus.copy(
          boot = start.consensus.boot.copy(era = Some(Era.Conway)),
          byron = start.consensus.byron.copy(firstShelleyEpoch = Some(firstShelley))
        )
      )
      .fold(e => fail(s"the state does not mirror: $e"), identity)
    val point = Point.Block(UInt64.saturating(BigInt(100000000)), Bytes(Vector.fill(32)(1.toByte)))
    QueryView(point, Some(1), state)

  private def answer(query: NtcQuery): Bytes =
    QueryAnswers.answer(env, view, Map.empty, query).getOrElse(fail(s"$query is not answered"))

  test("the configuration alone leaves the Byron span open") {
    assert(setup.geometry.byron.derived)
    assertEquals(setup.geometry.firstShelleyEpoch, BigInt(1000000))
  }

  /** Written by hand from the summary of the chain (Byron: epochs 0 to 4 of 21,600 slots of 20 s;
    * the Shelley-based eras of 432,000 slots of 1 s, here all from epoch 4 because no hard fork was
    * observed; Conway ends at the safe zone beyond slot 100,000,000, epoch 236, at
    * 101,952,000,000,000,000,000 ps, which does not fit 64 bits).
    */
  private val interpreter =
    "9f8383000000831b17fb16d83be000001a000151800484195460194e2083001910e081001910e083831b17fb16" +
      "d83be000001a0001518004831b17fb16d83be000001a0001518004841a000697801903e883001a0001fa408100" +
      "1a0001fa4083831b17fb16d83be000001a0001518004831b17fb16d83be000001a0001518004841a0006978019" +
      "03e883001a0001fa4081001a0001fa4083831b17fb16d83be000001a0001518004831b17fb16d83be000001a00" +
      "01518004841a000697801903e883001a0001fa4081001a0001fa4083831b17fb16d83be000001a000151800483" +
      "1b17fb16d83be000001a0001518004841a000697801903e883001a0001fa4081001a0001fa4083831b17fb16d8" +
      "3be000001a0001518004831b17fb16d83be000001a0001518004841a000697801903e883001a0001fa4081001a" +
      "0001fa4083831b17fb16d83be000001a000151800483c2490586de43d5cca000001a05fa9d8018ec841a000697" +
      "801903e883001a0001fa4081001a0001fa40ff"

  test("GetInterpreter: Byron ends at epoch 4, and a late time is a tag-2 big number") {
    assertEquals(answer(NtcQuery.HardFork(HardForkQuery.GetInterpreter)).hex, interpreter)
  }
