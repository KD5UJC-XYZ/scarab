// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.effect.{IO, Ref}
import cats.effect.std.Mutex
import cats.effect.unsafe.implicits.global
import cats.mtl.Raise
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, VolatileChain}
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorState}
import xyz.kd5ujc.scarab.ledger.{Block, StorageError, TxIn, TxOut}
import xyz.kd5ujc.scarab.ledger.state.{EraTag, LedgerState, NewEpochState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.node.{MirrorSteps, NodeSetup}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.protocol.{Era, LocalStateQuery}
import xyz.kd5ujc.scarab.storage.MemoryUtxoStore
import xyz.kd5ujc.scarab.storage.haskell.state.{
  HaskellState,
  HaskellStateCodecs,
  UtxoCodecs,
  Versions
}

/** `DebugEpochState` and `DebugNewEpochState`, answered from the mirror, on the state of the chain
  * after every block of the eras capture (Byron to Conway):
  *
  *   - the answer is what the Haskell codec decodes, to a state that holds what the mirror holds
  *     and that writes the answer again byte for byte;
  *   - a Shelley-based era answers both queries (and their `GetCBOR` forms), in the era's own
  *     encoding version, the chain-dependent state and the UTxO queries, and only these (the other
  *     queries stay Conway's);
  *   - a query for another era is the era mismatch, and Byron is refused.
  */
class MirrorAnswersSuite extends FunSuite:
  private val fixtures = Path.of("fixtures/eras")
  private val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => fail(e.message), identity)

  private val queryEnv = QueryEnv(
    setup.byronParams
      .map(_.startTime)
      .fold(setup.geometry.systemStart)(java.time.Instant.ofEpochSecond),
    setup.geometry,
    setup.praos.forks,
    setup.mirrorEnv().byron,
    setup.globals.maxSupply,
    setup.genesis.raw,
    setup.mirrorEnv().ledger,
    networkMagic = setup.genesis.networkMagic.toLong
  )

  private val raws: Vector[Bytes] =
    Files
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e.message), identity))

  private val blocks: Vector[Block] =
    raws.map(Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity))

  /** The state after each block, with the block. */
  private lazy val states: Vector[(Block, MirrorState)] =
    blocks.indices.foldLeft(Vector.empty[(Block, MirrorState)]) { (acc, i) =>
      val before = acc.lastOption.fold(setup.mirrorStart)(_._2)
      MirrorSteps.block(setup, before, blocks(i).header, raws(i)) match
        case Right(next) => acc :+ (blocks(i) -> next)
        case Left(f)     => fail(s"block $i rejected by ${f.rule.name}: ${f.reason}")
    }

  private def view(block: Block, state: MirrorState): QueryView[MirrorState] =
    QueryView(Point.Origin, Some(block.header.blockNo), state)

  private def output(hex: String): TxOut =
    UtxoCodecs.txOut
      .decodeBytes(
        Bytes.fromHex(hex.filterNot(_.isWhitespace)).fold(e => fail(e.message), identity)
      )
      .fold(e => fail(e.toString), identity)

  private def ask(era: Era, block: Block, state: MirrorState, query: LedgerQuery): Option[Bytes] =
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

  /** The answers about `state`, which holds `nes` in the era `E`. */
  private def inEra[E <: EraTag](
      era: Era,
      block: Block,
      state: MirrorState,
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

  private lazy val shelleyBased: Vector[(Block, MirrorState)] =
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
      val era = state.era
      state.ext.ledgerState.state match
        case i: EraLedgerState.InShelleyEra[e] =>
          inEra[e](era, block, state, i.state.nes)(using i.era)
        case _: EraLedgerState.ByronCurrent => fail(s"$era is Byron")
    }
  }

  test("the other queries stay Conway's: a state of an earlier era answers neither") {
    shelleyBased.filter(_._1.era != Era.Conway).take(40).foreach { (block, state) =>
      val era = state.era
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

  test(
    "the chain-dependent state is answered in every Shelley-based era, in the protocol's shape"
  ) {
    // `[result]`, then the version and the state: TPraos's is `[1, [last slot, [..3..]]]`, Praos's
    // `[0, [8 fields]]` (`TPraos.hs:263-296`, `Praos.hs:281-308`)
    val shapes = states.collect {
      case (block, state) if block.era != Era.Byron =>
        val answer = ask(block.era, block, state, LedgerQuery.DebugChainDepState)
          .getOrElse(fail(s"${block.era}: no answer"))
        block.era -> answer.hex.take(8)
    }.toMap
    assertEquals(shapes(Era.Shelley), "81820182")
    assertEquals(shapes(Era.Alonzo), "81820182")
    assertEquals(shapes(Era.Babbage), "81820088")
    assertEquals(shapes(Era.Conway), "81820088")
  }

  test("the UTxO queries are answered in every Shelley-based era, each output in its era's form") {
    // an output every era holds, and one with a datum hash, which only Alonzo's `TxOut` and later
    // hold (the reference's bytes for each era: `UtxoAnswersReferenceSuite`)
    val plain =
      TxIn(Bytes(Vector.fill(32)(1.toByte)), 0) -> output(s"82 581d60${"33" * 28} 1a0016e360")
    val withHash = TxIn(Bytes(Vector.fill(32)(2.toByte)), 1) ->
      output(s"83 581d60${"44" * 28} 1a0016e360 5820${"da" * 32}")
    val utxo = Map(plain, withHash)
    val firsts = shelleyBased.groupBy(_._1.era).view.mapValues(_.head).toMap
    assertEquals(firsts.size, 6)
    firsts.foreach { case (era, (block, state)) =>
      val witness = Versions.shelleyBased(era).getOrElse(fail(s"$era is not Shelley-based"))
      val holdsHash = era.index >= Era.Alonzo.index
      def written(entries: Map[TxIn, TxOut]): String =
        "81" + UtxoCodecs.utxoIn(witness).write(entries).fold(e => fail(e.toString), _.hex)
      def asked(q: LedgerQuery): Either[Unanswered, Bytes] =
        QueryAnswers.answerOrWhy(
          queryEnv,
          view(block, state),
          utxo,
          NtcQuery.IfCurrent(era.index, q)
        )
      val whole = asked(LedgerQuery.GetUTxOWhole)
      if holdsHash then assertEquals(whole.map(_.hex), Right(written(utxo)), s"$era")
      else assertEquals(whole.left.toOption, Some(Unanswered.Unwritable), s"$era")
      assertEquals(
        asked(LedgerQuery.GetUTxOByTxIn(Set(plain._1))).map(_.hex),
        Right(written(Map(plain))),
        s"$era"
      )
      assertEquals(
        asked(LedgerQuery.GetUTxOByAddress(Set(plain._2.address.raw))).map(_.hex),
        Right(written(Map(plain))),
        s"$era"
      )
    }
  }

  test("a session acquired on a state of any Shelley-based era reads the UTxO from the store too") {
    given Raise[IO, StorageError] with
      def functor = cats.Functor[IO]
      def raise[E <: StorageError, A](error: E): IO[A] =
        IO.raiseError(new RuntimeException(error.message))
    // an entry only the backing store holds: the answer is the store's entries and the state's
    val stored =
      TxIn(Bytes(Vector.fill(32)(9.toByte)), 4) -> output(s"82 581d60${"55" * 28} 1a000f4240")
    val whole = (era: Era) =>
      CborOut.build(_.array(2).uint(0).array(2).uint(0).array(2).uint(era.index).array(1).uint(7))
    shelleyBased.groupBy(_._1.era).view.mapValues(_.head).toMap.foreach { case (era, (_, state)) =>
      val witness = Versions.shelleyBased(era).getOrElse(fail(s"$era is not Shelley-based"))
      val held = QueryView(Point.Origin, None, state).utxo
      val program = for
        store <- MemoryUtxoStore[IO]
        _ <- store.write(Vector(stored._1 -> Some(stored._2)), held.flushed)
        chain <- Ref.of[IO, VolatileChain[MirrorState]](
          VolatileChain(
            5,
            Anchor(None, state),
            Vector(BlockRef(1, Bytes(Vector.fill(32)(1.toByte)), 1) -> state)
          )
        )
        gate <- Mutex[IO]
        answer <- StateQuerySource(chain, IO.pure(queryEnv), store, gate)
          .acquire(LocalStateQuery.Target.VolatileTip)
          .use {
            case Right(session) => session.answer(whole(era))
            case Left(error)    => IO.raiseError(new RuntimeException(error.toString))
          }
      yield answer
      val expected = UtxoCodecs
        .utxoIn(witness)
        .write(held.toMap + stored)
        .fold(e => fail(e.toString), b => "81" + b.hex)
      assert(!held.isEmpty, s"$era: the state holds no UTxO")
      assertEquals(program.unsafeRunSync().map(_.map(_.hex)), Right(Right(expected)), s"$era")
    }
  }

  test("a Byron state answers no UTxO query") {
    val (block, state) = states.find(_._1.era == Era.Byron).getOrElse(fail("no Byron block"))
    assertEquals(
      QueryAnswers
        .answerOrWhy(
          queryEnv,
          view(block, state),
          Map.empty,
          NtcQuery.IfCurrent(Era.Byron.index, LedgerQuery.GetUTxOWhole)
        )
        .left
        .toOption,
      Some(Unanswered.Unsupported)
    )
  }

  test("a Byron state has no new-epoch state to answer") {
    val (block, state) = states.find(_._1.era == Era.Byron).getOrElse(fail("no Byron block"))
    assertEquals(
      MirrorAnswers.newEpochState(view(block, state)).left.toOption,
      Some(Unanswered.Unsupported)
    )
    assertEquals(
      MirrorAnswers.epochState(view(block, state)).left.toOption,
      Some(Unanswered.Unsupported)
    )
  }
