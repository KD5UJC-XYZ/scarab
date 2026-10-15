// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, StateMirror}
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.state.{EraTag, LedgerState, NewEpochState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.node.{MirrorRun, NodeRules, NodeSetup}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, HaskellStateCodecs, Versions}

/** `DebugEpochState` and `DebugNewEpochState`, answered from the mirror, on the state of the node
  * after every block of the eras capture (Byron to Conway):
  *
  *   - the answer is what the Haskell codec decodes, to a state that holds what the mirror holds
  *     and that writes the answer again byte for byte;
  *   - a Shelley-based era answers both queries (and their `GetCBOR` forms), in the era's own
  *     encoding version, and only these (the other queries stay Conway's);
  *   - a query for another era is the era mismatch, and Byron is refused.
  */
class MirrorAnswersSuite extends FunSuite:
  private val fixtures = Path.of("fixtures/eras")
  private val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => fail(e.message), identity)

  private val queryEnv = QueryEnv(
    setup.byronParams
      .flatMap(_.startTime)
      .fold(setup.geometry.systemStart)(java.time.Instant.ofEpochSecond),
    setup.geometry,
    setup.praos.forks,
    setup.mirrorEnv().byron,
    setup.globals.maxSupply,
    setup.genesis.raw,
    setup.mirrorEnv().ledger
  )

  private val raws: Vector[Bytes] =
    Files
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e), identity))

  private val blocks: Vector[Block] =
    raws.map(Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity))

  /** The state after each block, with the block. */
  private lazy val states: Vector[(Block, NodeState)] =
    blocks.indices.foldLeft(Vector.empty[(Block, NodeState)]) { (acc, i) =>
      val before = acc.lastOption.fold(setup.start)(_._2)
      NodeRules.applyBlock(
        before,
        blocks(i).header,
        raws(i),
        setup.praos,
        setup.globals,
        setup.byronRules
      ) match
        case Right(next)          => acc :+ (blocks(i) -> next)
        case Left((rule, reason)) => fail(s"block $i rejected by $rule: $reason")
    }

  private def view(block: Block, state: NodeState): QueryView =
    QueryView(Point.Origin, Some(block.header.blockNo), state)

  private def ask(era: Era, block: Block, state: NodeState, query: LedgerQuery): Option[Bytes] =
    QueryAnswers.answer(
      queryEnv,
      view(block, state),
      Map.empty,
      NtcQuery.IfCurrent(era.index, query)
    )

  /** An `IfCurrent` answer is `[result]`: the result's bytes. */
  private def result(answer: Bytes): Bytes = Bytes(answer.value.tail)

  /** What `GetCBOR` answers about a result: `#6.24(bytes)` around it. */
  private def wrapped(plain: Bytes): String = CborOut.build(_.tag(24).bytes(plain)).hex

  /** The answers about `state`, whose mirror holds `nes` in the era `E`. */
  private def inEra[E <: EraTag](
      era: Era,
      block: Block,
      state: NodeState,
      nes: NewEpochState[E]
  )(using witness: ShelleyBasedEra[E]): Unit =
    val label = s"$era block ${block.header.blockNo}"

    val answer = ask(era, block, state, LedgerQuery.DebugNewEpochState)
      .map(result)
      .getOrElse(fail(s"$label: DebugNewEpochState is not answered"))
    val decoded = HaskellState.decode[E](answer).fold(e => fail(s"$label: ${e.render}"), identity)
    assertEquals(HaskellState.encode[E](decoded).map(_.hex), Right(answer.hex), label)
    assertEquals(decoded.epoch, nes.epoch, label)
    assertEquals(decoded.bprev, nes.bprev, label)
    assertEquals(decoded.bcur, nes.bcur, label)
    assertEquals(decoded.pd, nes.pd, label)
    assertEquals(decoded.es.chainAccount, nes.es.chainAccount, label)
    assertEquals(decoded.es.snapshots, nes.es.snapshots, label)
    // (the pools by their identities: a pool registered with the empty relay list `80` reads back as
    // no relays, the same sequence)
    assertEquals(
      LedgerState.pState[E].get(decoded.es.ls).stakePools.keySet,
      LedgerState.pState[E].get(nes.es.ls).stakePools.keySet,
      label
    )

    val epochAnswer = ask(era, block, state, LedgerQuery.DebugEpochState)
      .map(result)
      .getOrElse(fail(s"$label: DebugEpochState is not answered"))
    val epochCodec = HaskellStateCodecs.epochState[E](using witness, Versions.eraLow(witness))
    val epoch =
      epochCodec.decodeBytes(epochAnswer, HaskellState.limits).fold(e => fail(e.render), identity)
    assertEquals(epochCodec.write(epoch).map(_.hex), Right(epochAnswer.hex), label)
    assertEquals(epoch.snapshots, nes.es.snapshots, label)

    // the CBOR-in-CBOR forms hold the same bytes
    assertEquals(
      ask(era, block, state, LedgerQuery.GetCBOR(LedgerQuery.DebugNewEpochState))
        .map(result(_).hex),
      Some(wrapped(answer)),
      label
    )
    assertEquals(
      ask(era, block, state, LedgerQuery.GetCBOR(LedgerQuery.DebugEpochState))
        .map(result(_).hex),
      Some(wrapped(epochAnswer)),
      label
    )

  private lazy val shelleyBased: Vector[(Block, NodeState)] =
    states.filter(_._1.era != Era.Byron)

  test("the capture has a state in every Shelley-based era") {
    assertEquals(
      shelleyBased.map(_._1.era).distinct,
      Vector(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)
    )
  }

  test(
    "both queries answer every state of every Shelley-based era, as the Haskell codec reads it"
  ) {
    shelleyBased.foreach { (block, state) =>
      val era = StateMirror.eraOf(setup.mirrorEnv(), state)
      MirrorRun
        .run(setup.mirrorEnv())(_.toMirror(None, state))
        .fold(e => fail(s"$era block ${block.header.blockNo}: $e"), _.value)
        .ext
        .ledgerState
        .state match
        case i: EraLedgerState.InShelleyEra[e] =>
          inEra[e](era, block, state, i.state.nes)(using i.era)
        case _: EraLedgerState.ByronCurrent => fail(s"$era is Byron")
    }
  }

  test("the other queries stay Conway's: a state of an earlier era answers neither") {
    shelleyBased.filter(_._1.era != Era.Conway).take(40).foreach { (block, state) =>
      val era = StateMirror.eraOf(setup.mirrorEnv(), state)
      assertEquals(ask(era, block, state, LedgerQuery.GetCurrentPParams), None)
      assertEquals(ask(era, block, state, LedgerQuery.GetEpochNo), None)
    }
  }

  test("a query for another era is the era mismatch, not the state") {
    val (block, state) = shelleyBased.last
    val answer = QueryAnswers
      .answer(
        queryEnv,
        view(block, state),
        Map.empty,
        NtcQuery.IfCurrent(Era.Babbage.index, LedgerQuery.DebugNewEpochState)
      )
      .getOrElse(fail("no answer"))
    // [[5, "Babbage"], [6, "Conway"]]: two eras named
    assertEquals(answer.value.head & 0xff, 0x82)
    assertNotEquals(
      answer.hex,
      ask(Era.Conway, block, state, LedgerQuery.DebugNewEpochState).get.hex
    )
  }

  test("a Byron state has no new-epoch state to answer") {
    val (block, state) = states.find(_._1.era == Era.Byron).getOrElse(fail("no Byron block"))
    val env = setup.mirrorEnv()
    assertEquals(
      MirrorAnswers.newEpochState(env, view(block, state)).left.toOption,
      Some(MirrorRefusal.ByronLedger)
    )
    assertEquals(
      MirrorAnswers.epochState(env, view(block, state)).left.toOption,
      Some(MirrorRefusal.ByronLedger)
    )
  }
