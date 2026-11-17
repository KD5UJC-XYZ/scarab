// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.nio.file.{Files as JFiles, Path as JPath}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.consensus.state.{
  Bound,
  ByronMirror,
  ExtLedgerState,
  MirrorProvenance,
  MirrorState,
  MirrorStep,
  Past,
  PerasResolver,
  PerasRoundNo,
  PerasState,
  Placement
}
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.state.Word64
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.ntc.{CborOut, EraHistory}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.{Envelope, HaskellLedgerFile, HaskellState}

/** The era history's bounds under `--reference-build leios-b004fe0c` (#826) against the states a
  * node of that build wrote on a devnet that crossed from Conway into Dijkstra at epoch 1
  * (`fixtures/leios/state`): the bounds of the chain's origin as the genesis start builds them, and
  * the bound where Dijkstra starts as the block step places it ([[MirrorStep.startOf]]), each
  * compared field for field, the next Peras round included, with the bounds of the captured
  * telescopes; and the node-to-client era history of the build.
  */
class LeiosBoundsEvidenceSuite extends FunSuite:
  private val fixture = JPath.of("fixtures/leios/state")
  private val setup =
    NodeSetup
      .fromDirectory(ReferenceBuild.LeiosB004fe0c)(fixture.resolve("genesis"))
      .fold(e => fail(e.message), identity)
  private val placement = Placement(setup.geometry, setup.byronHistory, setup.praos.forks)
  private val start: MirrorState = setup.mirrorStart

  private def state(name: String): Bytes =
    Bytes.wrap(JFiles.readAllBytes(fixture.resolve(s"$name/state")))

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  private val bound = Envelope.bound(StateLayout.Leios)
  private def boundOf(n: Node): Bound =
    bound.decodeBytes(n.original, HaskellState.limits).fold(e => fail(e.render), identity)

  /** The past eras and the current era's start of a captured telescope: `[past..., current]`, a
    * past `[start, end]`, the current `[start, state]`.
    */
  private def bounds(telescope: Node): (Vector[Past], Bound) =
    val all = items(telescope)
    (
      all.init.map(p => Past(boundOf(items(p)(0)), boundOf(items(p)(1)))),
      boundOf(items(all.last)(0))
    )

  /** The ledger telescope and the header state's chain-dependent one of a `state` file:
    * `[version, [ledger, [tip, chain-dependent], Peras state]]`.
    */
  private def telescopes(bytes: Bytes): ((Vector[Past], Bound), (Vector[Past], Bound)) =
    val parts =
      items(items(Cbor.decode(bytes, HaskellState.limits).fold(e => fail(e.toString), identity))(1))
    (bounds(parts(0)), bounds(items(parts(1))(1)))

  private val round0 = Bound.Layout.Leios(PerasRoundNo(Word64.zero))

  test("the genesis start's bounds are the captured last Conway state's, round zero throughout") {
    val conway =
      HaskellLedgerFile
        .decode(state("conway-195"), StateLayout.Leios)
        .fold(e => fail(e.render), identity)
    val ext = conway.ext.getOrElse(fail("a Shelley-based state"))
    assertEquals(
      start.ext.layout,
      ExtLedgerState.Layout.Leios(PerasState(PerasResolver.Disabled, None))
    )
    assertEquals(start.ext.layout, conway.layout)
    assertEquals(ext.ledgerState.era, Era.Conway)
    assertEquals(start.ext.ledgerState.past, ext.ledgerState.past)
    assertEquals(start.ext.ledgerState.current.start, ext.ledgerState.current.start)
    assertEquals(start.ext.headerState.chainDep.past, ext.headerState.chainDep.past)
    assertEquals(
      start.ext.headerState.chainDep.current.start,
      ext.headerState.chainDep.current.start
    )
    assertEquals(ext.ledgerState.current.start, Bound(0, 0, 0, round0))
  }

  test(
    "Dijkstra's start, placed by the block step from Conway's, is the first Dijkstra state's"
  ) {
    val conway =
      HaskellLedgerFile
        .decode(state("conway-195"), StateLayout.Leios)
        .fold(e => fail(e.render), identity)
    val conwayStart = conway.ledger.current.start
    // the first Dijkstra block is at slot 203
    val dijkstraStart = MirrorStep.startOf(placement, Era.Dijkstra, 203, conwayStart)
    val ((ledgerPast, ledgerStart), (depPast, depStart)) = telescopes(state("dijkstra-203"))
    // what the step's `into` makes of the Conway telescope: Conway ends where Dijkstra starts
    val expectedPast = conway.ledger.past :+ Past(conwayStart, dijkstraStart)
    assertEquals(ledgerPast, expectedPast)
    assertEquals(
      depPast,
      conway.header.chainDep.past :+ Past(conway.header.chainDep.current.start, dijkstraStart)
    )
    assertEquals(ledgerStart, dijkstraStart)
    assertEquals(depStart, dijkstraStart)
    assertEquals(dijkstraStart, Bound(BigInt("20000000000000"), 200, 1, round0))
  }

  private def hex(s: String): Bytes =
    Bytes.fromHex(s.filterNot(_.isWhitespace)).fold(e => fail(e.toString), identity)

  test("GetInterpreter of the build: bounds of four items, Dijkstra's round length and rounds") {
    // at the origin in Conway, with Dijkstra configured at epoch 1: Conway ends there, and Dijkstra
    // ends at its safe zone (3k/f = 75 slots, an epoch of 200), adding 200 div 90 = 2 rounds
    // (`reconstructSummary`'s `TransitionKnown`, `mkUpperBound`). The trigger fires at the maximum
    // major version the reference has with `ExperimentalHardForksEnabled`, 12, which this node does
    // not configure while it refuses to enter Dijkstra (`HardForkConfig.trigger`, #54).
    val reference = placement.copy(forks = placement.forks.copy(maxMajorProtVer = 12))
    val eras = start.summary(reference)
    assertEquals(eras.size, Era.values.length)
    val answer = Cbor
      .decode(CborOut.build(EraHistory.encode(_, eras)), HaskellState.limits)
      .fold(e => fail(e.toString), identity)
    val summaries = items(answer)
    assertEquals(summaries.size, Era.values.length)
    assertEquals(
      summaries.map(s => items(s).take(2).map(items(_).size)),
      Vector.fill(8)(Vector(4, 4))
    )
    // the parameters of Byron to Conway have four items, Dijkstra's five
    assertEquals(summaries.init.map(s => items(items(s)(2)).size), Vector.fill(7)(4))
    // Dijkstra: [[2e13 ps, 200, 1, 0], [4e13 ps, 400, 2, 2], [200, 100 ms, [0, 75, [0]], 75, 90]],
    // hand-derived from `Summary.hs:573-581` and `EraParams.hs:253-266` (2416493b)
    assertEquals(
      summaries.last.original,
      hex(
        """83
           84 1b000012309ce54000 18c8 01 00
           84 1b0000246139ca8000 190190 02 02
           85 18c8 1864 83 00 184b 81 00 184b 185a"""
      )
    )
  }

  test(
    "GetInterpreter of the 11.1 build of the same network: bounds of three items, no round length"
  ) {
    val eleven = NodeSetup
      .fromDirectory(ReferenceBuild.Release11_1)(fixture.resolve("genesis"))
      .fold(e => fail(e.message), identity)
    val eras = eleven.mirrorStart.summary(
      Placement(eleven.geometry, eleven.byronHistory, eleven.praos.forks)
    )
    val summaries = items(
      Cbor
        .decode(CborOut.build(EraHistory.encode(_, eras)), HaskellState.limits)
        .fold(e => fail(e.toString), identity)
    )
    assert(summaries.nonEmpty)
    assertEquals(
      summaries.map(s => items(s).map(items(_).size)),
      Vector.fill(summaries.size)(Vector(3, 3, 4))
    )
  }

  test("leaving Byron in the Leios layout: the hand-over places Conway's start in the layout") {
    // a chain of this network started in Byron (the Byron start of the build's layout), handed over
    // by the devnet's block of slot 195: `startOf` from Byron's start, the layout carried
    val byron = ByronMirror
      .of(setup.mirrorEnv(), None, start.protocol.dep, start.consensus, MirrorProvenance.complete)
      .fold(e => fail(e.toString), identity)
    assertEquals(byron.ext.layout, start.ext.layout)
    val block = Block.decode(
      Bytes.wrap(JFiles.readAllBytes(fixture.resolve("conway-195/block"))),
      setup.geometry.byron.epochSlots
    ) match
      case Right(b: Block.Shelley) => b
      case other                   => fail(s"not a Shelley-based block: $other")
    val after = MirrorStep
      .handover(
        byron,
        placement,
        block,
        MirrorStep.nesOf(start).getOrElse(fail("expected a Shelley-based state")),
        start.protocol,
        start.consensus
      )
      .fold(e => fail(e.toString), identity)
    assertEquals(after.ext.layout, start.ext.layout)
    assertEquals(after.ext.ledgerState.past, start.ext.ledgerState.past)
    assertEquals(after.ext.ledgerState.current.start, Bound(0, 0, 0, round0))
    assertEquals(after.ext.headerState.chainDep.past, start.ext.headerState.chainDep.past)
    assertEquals(after.ext.headerState.chainDep.current.start, Bound(0, 0, 0, round0))
  }

  test(
    "GetEraStart's bound: four items in the Leios layout, three in 11.1's unless it has a round"
  ) {
    // `Serialise Bound` (2416493b `Summary.hs:573-581`; 82ecba32 `Summary.hs:526-538`)
    def written(layout: Bound.Layout) =
      CborOut.build(EraHistory.bound(_, Bound(BigInt("20000000000000"), 200, 1, layout)))
    val three = PerasRoundNo(Word64.fromUnsigned(3L))
    assertEquals(written(Bound.Layout.Leios(three)), hex("84 1b000012309ce54000 18c8 01 03"))
    assertEquals(written(round0), hex("84 1b000012309ce54000 18c8 01 00"))
    assertEquals(
      written(Bound.Layout.Node11_1(Some(three))),
      hex("84 1b000012309ce54000 18c8 01 03")
    )
    assertEquals(written(Bound.Layout.Node11_1(None)), hex("83 1b000012309ce54000 18c8 01"))
  }
