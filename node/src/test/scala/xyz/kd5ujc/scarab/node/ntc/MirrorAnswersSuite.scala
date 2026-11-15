// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.protocol.ReferenceBuild
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
import xyz.kd5ujc.scarab.node.read.InEra
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
import xyz.kd5ujc.scarab.protocol.{Era, LocalStateQuery, NtcVersion}
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
  *     encoding version, the chain-dependent state, the UTxO queries, the reward provenance and the
  *     queries that read nothing of the era's own, and only these (the other queries stay
  *     Conway's);
  *   - a query for another era is the era mismatch, and Byron is refused.
  */
class MirrorAnswersSuite extends FunSuite:
  private val fixtures = Path.of("fixtures/eras")
  private val setup: NodeSetup =
    NodeSetup
      .fromDirectory(ReferenceBuild.Default)(fixtures.resolve("genesis"))
      .fold(e => fail(e.message), identity)

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
      NtcQuery.IfCurrent(QueryEra.of(era), query)
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

  /** What cardano-node 11.1.3 answered to `GetRewardProvenance` and to its `GetCBOR` form (the
    * result item of `MsgResult`), recorded on 2026-11-15 on the devnet that walks the eras
    * (`devnet.py up-eras`), in each era at a tip of it, at node-to-client versions 16 and 23. It is
    * the default provenance whatever the state (`Shelley/API/Wallet.hs:373-384`, cardano-ledger
    * f649f975), at the era's lowest version: its two rationals untagged before Conway, under tag 30
    * in it.
    */
  private val referenceRewardProvenance: Map[Era, (String, String)] =
    val before = (
      "819000a000000000000082000100820001000000a0a0",
      "81d818559000a000000000000082000100820001000000a0a0"
    )
    Map(
      Era.Shelley -> before,
      Era.Allegra -> before,
      Era.Mary -> before,
      Era.Alonzo -> before,
      Era.Babbage -> before,
      Era.Conway -> (
        "819000a0000000000000d81e82000100d81e820001000000a0a0",
        "81d81858199000a0000000000000d81e82000100d81e820001000000a0a0"
      )
    )

  test("every state of every Shelley-based era answers the reward provenance as the reference") {
    shelleyBased.foreach { (block, state) =>
      val era = state.era
      val (plain, wrapped) =
        referenceRewardProvenance.getOrElse(era, fail(s"$era: no recorded answer"))
      List(NtcVersion.V16, NtcVersion.V23).foreach { version =>
        def asked(q: LedgerQuery): Either[Unanswered, String] =
          QueryAnswers
            .answerOrWhy(
              queryEnv,
              view(block, state),
              Map.empty,
              NtcQuery.IfCurrent(QueryEra.of(era), q),
              version
            )
            .map(_.hex)
        val label = s"$era block ${block.header.blockNo} at ${version.number}"
        assertEquals(asked(LedgerQuery.GetRewardProvenance), Right(plain), label)
        assertEquals(
          asked(LedgerQuery.GetCBOR(LedgerQuery.GetRewardProvenance)),
          Right(wrapped),
          label
        )
      }
    }
  }

  /** What cardano-node 11.1.3 answered to `GetProposedPParamsUpdates` and to
    * `GetMaxMajorProtocolVersion` (the result item of `MsgResult`), recorded on 2026-11-15 on the
    * devnet that walks the eras (`devnet.py up-eras`), in Babbage, at node-to-client versions 16,
    * 20, 21 and 23. The first is the empty map whatever the state (`answerPureBlockQuery`,
    * `Shelley/Ledger/Query.hs:417-418`, ouroboros-consensus 82ecba32), the second the maximum the
    * node's protocols are configured with in every era (`Cardano/Node.hs:644-645, 677, 690`), 11
    * for this release, as on the eras capture.
    */
  private val referenceProposedUpdates = "81a0"
  private val referenceMaxMajorProtocolVersion = "810b"

  /** The answer of the eras capture's first Conway state to `GetGenesisConfig` at the latest
    * version, the project's regression golden (`fixtures/ntc-golden`); the genesis is the same in
    * every era.
    */
  private lazy val conwayGenesisConfig: String =
    Files
      .readAllLines(NtcGolden.file)
      .asScala
      .map(_.split('\t'))
      .collectFirst { case Array("conway-0", "GetGenesisConfig", value) => value }
      .getOrElse(fail("no GetGenesisConfig golden"))

  test(
    "every state of every Shelley-based era answers the queries that read nothing of the era's own"
  ) {
    val genesis = queryEnv.shelleyGenesis
    val legacyGenesis = GenesisConfig
      .encode(genesis, NtcVersion.V20)
      .fold(e => fail(e.message), _.hex)
    // the legacy record writes the Shelley protocol version as two fields, not as one pair: a byte
    // shorter, as the reference's answers on the devnet were (392 and 393 bytes)
    assertEquals(legacyGenesis.length, conwayGenesisConfig.length - 2)
    def at(block: Block, state: MirrorState): QueryView[MirrorState] =
      val tip = Point.Block(UInt64.saturating(BigInt(block.header.slot.toLong)), block.header.hash)
      QueryView(tip, Some(block.header.blockNo), state)
    shelleyBased.foreach { (block, state) =>
      val view = at(block, state)
      val era = state.era
      val nes = state.ext.ledgerState.state match
        case i: EraLedgerState.InShelleyEra[e] => i.state.nes
        case _: EraLedgerState.ByronCurrent    => fail(s"$era is Byron")
      val slot = BigInt(block.header.slot.toLong)
      // epoch 0 is Byron's 50 slots, every later epoch 500 (`fixtures/eras/README.md`)
      val epoch = (slot - 50) / 500 + 1
      val account = CborOut.build(
        _.array(2).uint(nes.es.chainAccount.treasury.value).uint(nes.es.chainAccount.reserves.value)
      )
      List(NtcVersion.V16, NtcVersion.V20, NtcVersion.V21, NtcVersion.V23).foreach { version =>
        val label = s"$era block ${block.header.blockNo} at ${version.number}"
        def asked(q: LedgerQuery): Either[Unanswered, String] =
          QueryAnswers
            .answerOrWhy(
              queryEnv,
              view,
              Map.empty,
              NtcQuery.IfCurrent(QueryEra.of(era), q),
              version
            )
            .map(_.hex)
        def both(q: LedgerQuery, plain: String, latest: String): Unit =
          assertEquals(asked(q), Right("81" + plain), s"$label: $q")
          val cbor = Bytes.fromHex(latest).fold(e => fail(e.message), identity)
          assertEquals(asked(LedgerQuery.GetCBOR(q)), Right("81" + wrapped(cbor)), s"$label: $q")
        val point = CborOut.build(_.array(2).uint(slot).bytes(block.header.hash)).hex
        both(LedgerQuery.GetLedgerTip, point, point)
        val epochNo = CborOut.build(_.uint(epoch)).hex
        both(LedgerQuery.GetEpochNo, epochNo, epochNo)
        val proposed = referenceProposedUpdates.drop(2)
        both(LedgerQuery.GetProposedPParamsUpdates, proposed, proposed)
        both(
          LedgerQuery.GetGenesisConfig,
          if version.number < 21 then legacyGenesis else conwayGenesisConfig,
          conwayGenesisConfig
        )
        both(LedgerQuery.GetAccountState, account.hex, account.hex)
        val maxMajor = referenceMaxMajorProtocolVersion.drop(2)
        both(LedgerQuery.GetMaxMajorProtocolVersion, maxMajor, maxMajor)
      }
    }
    // the proposed updates are answered empty in states that hold proposals too
    val proposing = shelleyBased.filter(_._1.era != Era.Conway).exists { (block, state) =>
      at(block, state).ledger.toOption.flatten.exists { case InEra.Of(_, l) =>
        l.ppup.exists((current, _) => current.nonEmpty)
      }
    }
    assert(proposing, "no state before Conway holds a proposal")
  }

  test("the other queries stay Conway's: a state of an earlier era answers none") {
    shelleyBased.filter(_._1.era != Era.Conway).take(40).foreach { (block, state) =>
      val era = state.era
      assertEquals(ask(era, block, state, LedgerQuery.GetCurrentPParams), None)
      assertEquals(ask(era, block, state, LedgerQuery.GetStakePools), None)
    }
  }

  test("a query for another era is the era mismatch, not the state") {
    val (block, state) = shelleyBased.last
    val answer = QueryAnswers
      .answer(
        queryEnv,
        view(block, state),
        Map.empty,
        NtcQuery.IfCurrent(QueryEra.Babbage, LedgerQuery.DebugNewEpochState)
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
    // `[0, [8 fields]]` (`TPraos.hs:263-296`, `Praos.hs:281-308`, ouroboros-consensus 82ecba32)
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
          NtcQuery.IfCurrent(QueryEra.of(era), q)
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
          NtcQuery.IfCurrent(QueryEra.Byron, LedgerQuery.GetUTxOWhole)
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
