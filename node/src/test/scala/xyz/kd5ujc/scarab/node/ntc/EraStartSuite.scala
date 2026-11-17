// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.consensus.state.{
  Bound,
  Current,
  GenesisMirror,
  MirrorState,
  MirrorStep,
  PerasRoundNo,
  Placement,
  Telescope
}
import xyz.kd5ujc.scarab.ledger.state.Word64
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}

/** `GetEraStart` (`QueryAnytime`) as the whole answer, `Serialise (Maybe Bound)`, over a chain in
  * Byron whose Shelley starts at epoch 1 (`fixtures/eras`, `TestShelleyHardForkAtEpoch: 1`), under
  * each build. `answerQueryAnytime` (ouroboros-consensus 2416493b
  * `HardFork/Combinator/Ledger/Query.hs:503-514`; 82ecba32 `:484-495`) answers the current era's
  * start, the next era's where the current era's transition is known (`SituatedNext`: Byron's end,
  * `mkUpperBound` of Byron to epoch 1), and nothing for a later era (`SituatedFuture`).
  */
class EraStartSuite extends FunSuite:
  private val dir = Path.of("fixtures/eras/genesis")

  private def setupOf(build: ReferenceBuild): NodeSetup =
    NodeSetup.fromDirectory(build)(dir).fold(e => fail(e.message), identity)

  private def envOf(setup: NodeSetup): QueryEnv = QueryEnv(
    setup.byronParams
      .map(_.startTime)
      .fold(setup.geometry.systemStart)(java.time.Instant.ofEpochSecond),
    setup.geometry,
    setup.praos.forks,
    setup.byronHistory,
    setup.globals.maxSupply,
    setup.genesis.raw,
    setup.mirrorEnv().ledger,
    networkMagic = setup.genesis.networkMagic.toLong,
    ledgerConfig = LedgerConfig.answer(setup.ledgerConfig)
  )

  private def answerOf(setup: NodeSetup, state: MirrorState, query: NtcQuery): String =
    QueryAnswers
      .answer(envOf(setup), QueryView(Point.Origin, None, state), Map.empty, query)
      .fold(fail(s"$query is not answered"))(_.hex)

  private def answered(setup: NodeSetup, state: MirrorState, era: QueryEra): String =
    answerOf(setup, state, NtcQuery.EraStart(era))

  private val interpreter = NtcQuery.HardFork(HardForkQuery.GetInterpreter)

  // Shelley's start: 50 Byron slots of 1 s, `[time in picoseconds, slot, epoch]`
  private val shelleyStart = "1b00002d79883d2000" + "1832" + "01"

  test("11.1: Byron's start, Shelley's at the known transition, nothing for Allegra") {
    val setup = setupOf(ReferenceBuild.Release11_1)
    val start = setup.mirrorStart
    assertEquals(answered(setup, start, QueryEra.Byron), "81" + "83000000")
    assertEquals(answered(setup, start, QueryEra.Shelley), "81" + "83" + shelleyStart)
    assertEquals(answered(setup, start, QueryEra.Allegra), "80")
  }

  test("Leios: the same bounds with their next Peras round, four items") {
    val setup = setupOf(ReferenceBuild.LeiosB004fe0c)
    val start = setup.mirrorStart
    assertEquals(answered(setup, start, QueryEra.Byron), "81" + "8400000000")
    assertEquals(answered(setup, start, QueryEra.Shelley), "81" + "84" + shelleyStart + "00")
    assertEquals(answered(setup, start, QueryEra.Allegra), "80")
  }

  test("11.1: a stored PerasEnabled round is written, a fourth item, as 82ecba32 writes it") {
    // `Serialise Bound` writes `boundPerasRound` when it is `PerasEnabled` (82ecba32
    // `HardFork/History/Summary.hs:526-538`); no era of the line has rounds, so only a stored
    // bound can hold one, and it is answered as held
    val setup = setupOf(ReferenceBuild.Release11_1)
    val start = setup.mirrorStart
    val held =
      start.ext.ledgerState.current.start
        .copy(layout = Bound.Layout.Node11_1(Some(PerasRoundNo(Word64.fromUnsigned(3L)))))
    val ledger = Telescope
      .make(Vector.empty, Current(held, start.ext.ledgerState.state))
      .fold(e => fail(e.toString), identity)
    val state = start.copy(ext = start.ext.copy(ledgerState = ledger))
    assertEquals(answered(setup, state, QueryEra.Byron), "81" + "8400000003")
    // Shelley's start is computed by `mkUpperBound`, whose round is none in this line
    assertEquals(answered(setup, state, QueryEra.Shelley), "81" + "83" + shelleyStart)
    // `GetInterpreter` writes the same held start (`Query.hs:562` at 82ecba32): Byron's era is
    // `[start, end, params]`, the start of four items, the end of three
    assert(
      answerOf(setup, state, interpreter).startsWith(
        "9f" + "83" + "8400000003" + "83" + shelleyStart
      ),
      answerOf(setup, state, interpreter)
    )
  }

  /** `fixtures/eras` with Shelley at `epoch` (`TestShelleyHardForkAtEpoch`). */
  private def shelleyAt(build: ReferenceBuild, epoch: String): NodeSetup =
    NodeSetup
      .fromFiles(build) { name =>
        Option.when(Files.exists(dir.resolve(name)))(Files.readAllBytes(dir.resolve(name))).map {
          bytes =>
            if NodeSetup.ConfigNames.contains(name) then
              new String(bytes, "UTF-8")
                .replace(
                  "\"TestShelleyHardForkAtEpoch\": 1,",
                  s"\"TestShelleyHardForkAtEpoch\": $epoch,"
                )
                .getBytes("UTF-8")
            else bytes
        }
      }
      .fold(e => fail(e.message), identity)

  // 368934881474191033 Byron epochs of 50 slots are 2^64 + 34 slots: `mkUpperBound`'s `Word64`
  // `inEraSlots` is 34 (`Summary.hs:148-154` at 2416493b, `:132-138` at 82ecba32), its slot the
  // `Word64` sum `addSlots` and its time 34 slots of 1 s, exact (`addRelTime`)
  private val farEpoch = "368934881474191033"
  private val farShelleyStart = "1b00001eec3dec2000" + "1822" + "1b051eb851eb851eb9"

  test("11.1: a Shelley epoch past 2^64 Byron slots: Shelley's start wraps as the reference's") {
    val setup = shelleyAt(ReferenceBuild.Release11_1, farEpoch)
    assertEquals(
      answered(setup, setup.mirrorStart, QueryEra.Shelley),
      "81" + "83" + farShelleyStart
    )
    assert(
      answerOf(setup, setup.mirrorStart, interpreter)
        .startsWith("9f" + "83" + "83000000" + "83" + farShelleyStart),
      answerOf(setup, setup.mirrorStart, interpreter)
    )
  }

  test("Leios: a Shelley epoch past 2^64 Byron slots: the same wrapped start, round zero") {
    val setup = shelleyAt(ReferenceBuild.LeiosB004fe0c, farEpoch)
    assertEquals(
      answered(setup, setup.mirrorStart, QueryEra.Shelley),
      "81" + "84" + farShelleyStart + "00"
    )
    assert(
      answerOf(setup, setup.mirrorStart, interpreter)
        .startsWith("9f" + "83" + "8400000000" + "84" + farShelleyStart + "00"),
      answerOf(setup, setup.mirrorStart, interpreter)
    )
  }

  /** A chain of `fixtures/eras` whose Shelley starts at the far epoch, built Shelley-current by the
    * genesis start ([[GenesisMirror]], over [[EraHistory.summary]]): the Shelley state and its
    * chain-dependent state are those of the same network with Shelley at epoch 0.
    */
  private def farShelleyCurrent(build: ReferenceBuild): (NodeSetup, MirrorState) =
    val far = shelleyAt(build, farEpoch)
    val near = shelleyAt(build, "0").mirrorStart
    val mirror = GenesisMirror
      .of(
        far.mirrorEnv(),
        MirrorStep.nesOf(near).getOrElse(fail("a Shelley-based start")),
        near.ext.headerState.chainDep.state,
        near.consensus
      )
      .fold(e => fail(e.toString), identity)
    (far, mirror)

  /** The far Shelley start in `layout`, as the block step places it from Byron's start
    * ([[MirrorStep.startOf]]): the slot modulo 2^64, the time exact.
    */
  private def farStart(setup: NodeSetup, layout: Bound.Layout): Bound =
    val placement = Placement(setup.geometry, setup.byronHistory, setup.praos.forks)
    val byronStart = Bound(0, 0, 0, layout)
    val start = MirrorStep.startOf(placement, Era.Shelley, 0, byronStart)
    assertEquals(start, Bound(BigInt("34000000000000"), 34, BigInt(farEpoch), layout))
    start

  test(
    "11.1: a mirror built Shelley-current at the far epoch holds and answers the wrapped start"
  ) {
    val (setup, mirror) = farShelleyCurrent(ReferenceBuild.Release11_1)
    val start = farStart(setup, Bound.Layout.Node11_1(None))
    assertEquals(mirror.ext.ledgerState.past.map(_.end), Vector(start))
    assertEquals(mirror.ext.ledgerState.current.start, start)
    assertEquals(mirror.ext.headerState.chainDep.current.start, start)
    assertEquals(answered(setup, mirror, QueryEra.Byron), "81" + "83000000")
    assertEquals(answered(setup, mirror, QueryEra.Shelley), "81" + "83" + farShelleyStart)
    assert(
      answerOf(setup, mirror, interpreter)
        .startsWith("9f" + "83" + "83000000" + "83" + farShelleyStart),
      answerOf(setup, mirror, interpreter)
    )
  }

  test(
    "Leios: a mirror built Shelley-current at the far epoch holds and answers the wrapped start"
  ) {
    val (setup, mirror) = farShelleyCurrent(ReferenceBuild.LeiosB004fe0c)
    val start = farStart(setup, Bound.Layout.Leios(PerasRoundNo(Word64.zero)))
    assertEquals(mirror.ext.ledgerState.past.map(_.end), Vector(start))
    assertEquals(mirror.ext.ledgerState.current.start, start)
    assertEquals(mirror.ext.headerState.chainDep.current.start, start)
    assertEquals(answered(setup, mirror, QueryEra.Byron), "81" + "8400000000")
    assertEquals(answered(setup, mirror, QueryEra.Shelley), "81" + "84" + farShelleyStart + "00")
    assert(
      answerOf(setup, mirror, interpreter)
        .startsWith("9f" + "83" + "8400000000" + "84" + farShelleyStart + "00"),
      answerOf(setup, mirror, interpreter)
    )
  }
