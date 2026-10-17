// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import java.nio.file.Path
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.node.NodeSetup
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
    .fromConfigFile(Path.of("fixtures", "preprod", "genesis", "config.json"))
    .fold(e => fail(e.message), identity)

  private val env = QueryEnv(
    setup.genesis.geometry.systemStart,
    setup.geometry,
    setup.praos.forks,
    setup.mirrorEnv().byron,
    setup.globals.maxSupply,
    setup.genesis.raw,
    setup.mirrorEnv().ledger
  )

  private given xyz.kd5ujc.scarab.node.read.ChainRead[xyz.kd5ujc.scarab.consensus.NodeState] =
    xyz.kd5ujc.scarab.node.read.ChainRead.nodeState(setup.mirrorEnv())

  /** The state of a chain in Conway whose Byron era ended at epoch 4, at slot 100,000,000. */
  private val view =
    val state = setup.start
      .withBoot(setup.start.boot.copy(era = Some(Era.Conway)))
      .withByron(setup.start.byron.copy(firstShelleyEpoch = Some(BigInt(4))))
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
