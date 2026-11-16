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
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node as CborNode, Value}
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, VolatileChain}
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorProvenance, MirrorState}
import xyz.kd5ujc.scarab.ledger.{Block, Credential, StorageError, TxIn, TxOut}
import xyz.kd5ujc.scarab.ledger.rules.{EraLedger, StakeDistribution}
import xyz.kd5ujc.scarab.ledger.state as mirror
import xyz.kd5ujc.scarab.ledger.state.{
  EraTag,
  LedgerState,
  NewEpochState,
  PoolDistr,
  ShelleyBasedEra
}
import xyz.kd5ujc.scarab.node.{MirrorSteps, NodeSetup}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.read.{EraRead, EraViews, InEra}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
import xyz.kd5ujc.scarab.protocol.{Era, LocalStateQuery, NtcVersion}
import xyz.kd5ujc.scarab.storage.MemoryUtxoStore
import xyz.kd5ujc.scarab.storage.haskell.state.{
  HaskellLedgerFile,
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
  *     encoding version, the chain-dependent state, the UTxO queries, the reward provenance, the
  *     queries that read nothing of the era's own, its current parameters and governance state, the
  *     queries of its certificate state and its pool distributions (as the reference wrote them in
  *     its own snapshots of each era), the stake distributions, the pools' reward information and
  *     the non-myopic member rewards of its current stake, and its ledger peers (the pools of its
  *     pool distribution with the relays of their current and pending registrations), and only
  *     these (the queries of Conway's governance stay Conway's);
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

  /** The item at `path` of the CBOR `bytes`, an index into each array on the way, as it was written
    * (the ledger state holds floats, the non-myopic likelihoods).
    */
  private def item(bytes: Bytes, path: Int*): CborNode =
    path.foldLeft(Cbor.decode(bytes, HaskellState.limits).fold(e => fail(e.toString), identity)) {
      (n, i) =>
        n.items.flatMap(_.drop(i).nextOption()).getOrElse(fail(s"no item $i in ${n.original.hex}"))
    }

  /** The governance state in a new-epoch state as the ledger writes it: `[epoch, blocks, blocks,
    * [chain account, [certificate state, [UTxO, deposited, fees, governance state, ..]], ..], ..]`
    * (`LedgerState/Types.hs`, cardano-ledger f649f975).
    */
  private def govIn(newEpochState: Bytes): Bytes = item(newEpochState, 3, 1, 1, 3).original

  /** The current parameters in a governance state: the third item of `ShelleyGovState`, the fourth
    * of `ConwayGovState` (`Shelley/Governance.hs:63-68`, `Conway/Governance.hs:359-369`,
    * cardano-ledger f649f975), and how the reference writes them below node-to-client version 21:
    * before Conway with the protocol version pair, the 15th parameter up to Alonzo and the 13th in
    * Babbage, as its two fields, one item more; Conway's as they are
    * (`Query/LegacyPParams.hs:43-68, 107-130, 167-190, 227-258, 302-330, 371-372`,
    * ouroboros-consensus 82ecba32).
    */
  private def paramsIn(era: Era, gov: Bytes): (Bytes, Bytes) =
    def split(at: Int): (Bytes, Bytes) =
      val current = item(gov, 2).original
      val fields = item(current).items.map(_.toVector).getOrElse(fail("no parameters"))
      val pair = fields(at).items.map(_.toVector).getOrElse(fail(s"$era: no protocol version"))
      assertEquals(pair.size, 2, s"$era: the protocol version")
      val legacy = CborOut.build { w =>
        w.array(fields.size + 1)
        (fields.take(at) ++ pair ++ fields.drop(at + 1)).foreach(f => w.raw(f.original))
      }
      assertEquals(legacy.size, current.size - 1, s"$era: the legacy parameters")
      (current, legacy)
    era match
      case Era.Shelley | Era.Allegra | Era.Mary | Era.Alonzo => split(14)
      case Era.Babbage                                       => split(12)
      case Era.Conway =>
        val current = item(gov, 3).original
        (current, current)
      case Era.Byron | Era.Dijkstra => fail(s"$era has no parameters here")

  /** `GetCurrentPParams` and `GetGovState` and their `GetCBOR` forms asked of `view` in `era` at
    * versions 16, 20, 21 and 23: the governance state `gov` at every version, its current
    * parameters from version 21 and their legacy form below it, and both at the latest version
    * through `GetCBOR`.
    */
  private def asksGovernance[S](label: String, era: Era, view: QueryView[S], gov: Bytes): Unit =
    val (current, legacy) = paramsIn(era, gov)
    List(NtcVersion.V16, NtcVersion.V20, NtcVersion.V21, NtcVersion.V23).foreach { version =>
      def asked(q: LedgerQuery): Either[Unanswered, String] =
        QueryAnswers
          .answerOrWhy(queryEnv, view, Map.empty, NtcQuery.IfCurrent(QueryEra.of(era), q), version)
          .map(_.hex)
      val at = s"$label at ${version.number}"
      assertEquals(
        asked(LedgerQuery.GetCurrentPParams),
        Right("81" + (if version.number < 21 then legacy else current).hex),
        s"$at: GetCurrentPParams"
      )
      assertEquals(
        asked(LedgerQuery.GetCBOR(LedgerQuery.GetCurrentPParams)),
        Right("81" + wrapped(current)),
        s"$at: GetCBOR GetCurrentPParams"
      )
      assertEquals(asked(LedgerQuery.GetGovState), Right("81" + gov.hex), s"$at: GetGovState")
      assertEquals(
        asked(LedgerQuery.GetCBOR(LedgerQuery.GetGovState)),
        Right("81" + wrapped(gov)),
        s"$at: GetCBOR GetGovState"
      )
    }

  test(
    "every state of every Shelley-based era answers its current parameters and governance state, as its new-epoch state holds them"
  ) {
    val proposing = shelleyBased.map { (block, state) =>
      val era = state.era
      val label = s"$era block ${block.header.blockNo}"
      val gov = govIn(
        ask(era, block, state, LedgerQuery.DebugNewEpochState)
          .map(result)
          .getOrElse(fail(s"$label: DebugNewEpochState is not answered"))
      )
      asksGovernance(label, era, view(block, state), gov)
      era != Era.Conway && item(gov, 0).entries.exists(_.nonEmpty)
    }
    // the proposed updates are in the answer of a state that holds them
    assert(proposing.exists(identity), "no state before Conway holds a proposal")
  }

  /** The ledger snapshots cardano-node 11.1.3 wrote, one in each Shelley-based era, on the devnet
    * that walks the eras (`fixtures/haskell-state`, by its `db-analyser`).
    */
  private val referenceSnapshots =
    Vector(
      "shelley-1000",
      "allegra-1800",
      "mary-2308",
      "alonzo-3013",
      "babbage-4000",
      "conway-4593"
    )

  test(
    "a state the reference wrote answers its current parameters and governance state with the reference's bytes"
  ) {
    // these queries read the ledger state only; the rest of the mirror is a capture state's
    val (_, capture) = shelleyBased.head
    val eras = referenceSnapshots.map { name =>
      val raw = Bytes.fromArray(Files.readAllBytes(Path.of(s"fixtures/haskell-state/$name/state")))
      val file = HaskellLedgerFile.decode(raw).fold(e => fail(s"$name: ${e.render}"), identity)
      val ext = file.ext.getOrElse(fail(s"$name: no Shelley-based state"))
      val state = MirrorState(ext, capture.extras, MirrorProvenance.complete)
      // the file is `[1, [ledger telescope, header state]]`, the telescope's last item the current
      // era's `[start, [2, [tip, new-epoch state, transition]]]`: the governance state there is the
      // reference's own bytes
      val current = item(raw, 1, 0).items.flatMap(_.toVector.lastOption).getOrElse(fail(name))
      val gov = govIn(item(current.original, 1, 1, 1).original)
      asksGovernance(name, file.era, QueryView(Point.Origin, None, state), gov)
      file.era
    }
    assertEquals(
      eras,
      Vector(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)
    )
  }

  /** The pool distribution in a new-epoch state as the ledger writes it, `nesPd`: its sixth item
    * (`[epoch, blocks, blocks, epoch state, reward update, pool distribution, ..]`,
    * `LedgerState/Types.hs`, cardano-ledger f649f975), `[pools, total active stake]`, each pool's
    * `[ratio, stake, VRF key hash]` (`State/PoolDistr.hs:70-77, 117-122`).
    */
  private def pdIn(newEpochState: Bytes): CborNode = item(newEpochState, 5)

  /** `GetPoolDistr2` and `GetPoolDistr`, plain and through `GetCBOR`, asked of `view` in `era` at
    * versions 16 and 23, against the pool distribution `pd` as the ledger state holds it: the whole
    * distribution for no set and for the empty one (#933), the entry of the pool a set names, none
    * for a pool the distribution does not hold, the total whatever is chosen; the retired form each
    * chosen pool's ratio and VRF key hash. How many pools the distribution holds.
    */
  private def asksPoolDistr[S](label: String, era: Era, view: QueryView[S], pd: CborNode): Int =
    val fields = pd.items.map(_.toVector).getOrElse(fail(s"$label: no pool distribution"))
    assertEquals(fields.size, 2, s"$label: the pool distribution")
    val pools = fields(0).entries.map(_.toVector).getOrElse(fail(s"$label: no pools"))
    def written(chosen: Vector[(CborNode, CborNode)]): Bytes = CborOut.build { w =>
      w.array(2).map(chosen.size)
      chosen.foreach((id, share) => w.raw(id.original).raw(share.original))
      w.raw(fields(1).original)
    }
    def legacy(chosen: Vector[(CborNode, CborNode)]): Bytes = CborOut.build { w =>
      w.map(chosen.size)
      chosen.foreach { (id, share) =>
        val parts = share.items.map(_.toVector).getOrElse(fail(s"$label: a pool's share"))
        assertEquals(parts.size, 3, s"$label: a pool's share")
        w.raw(id.original).array(2).raw(parts(0).original).raw(parts(2).original)
      }
    }
    def idOf(n: CborNode): Bytes = n.value match
      case Value.ByteString(b) => b
      case other               => fail(s"$label: a pool id $other")
    val one = pools.take(1)
    val named = Some(one.map((id, _) => idOf(id)).toSet)
    val unknown = Some(Set(Bytes(Vector.fill(28)(0.toByte))))
    List(NtcVersion.V16, NtcVersion.V23).foreach { version =>
      def asked(q: LedgerQuery): Either[Unanswered, String] =
        QueryAnswers
          .answerOrWhy(queryEnv, view, Map.empty, NtcQuery.IfCurrent(QueryEra.of(era), q), version)
          .map(_.hex)
      def both(q: LedgerQuery, expected: Bytes): Unit =
        val at = s"$label at ${version.number}: $q"
        assertEquals(asked(q), Right("81" + expected.hex), at)
        assertEquals(asked(LedgerQuery.GetCBOR(q)), Right("81" + wrapped(expected)), at)
      both(LedgerQuery.GetPoolDistr2(None), written(pools))
      both(LedgerQuery.GetPoolDistr2(Some(Set.empty)), written(pools))
      both(LedgerQuery.GetPoolDistr2(named), written(one))
      both(LedgerQuery.GetPoolDistr2(unknown), written(Vector.empty))
      both(LedgerQuery.GetPoolDistr(None), legacy(pools))
      both(LedgerQuery.GetPoolDistr(Some(Set.empty)), legacy(pools))
      both(LedgerQuery.GetPoolDistr(named), legacy(one))
      both(LedgerQuery.GetPoolDistr(unknown), legacy(Vector.empty))
    }
    pools.size

  test(
    "every state of every Shelley-based era answers its pool distribution, as its new-epoch state holds it, every pool for the empty set (#933)"
  ) {
    val held = shelleyBased.map { (block, state) =>
      val era = state.era
      val label = s"$era block ${block.header.blockNo}"
      val nes = ask(era, block, state, LedgerQuery.DebugNewEpochState)
        .map(result)
        .getOrElse(fail(s"$label: DebugNewEpochState is not answered"))
      era -> asksPoolDistr(label, era, view(block, state), pdIn(nes))
    }
    // the capture's distribution holds no pool in Shelley and Allegra: its stake is first in force
    // in Mary
    assertEquals(
      held.collect { case (era, pools) if pools > 0 => era }.distinct,
      Vector(Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway),
      "the eras with a state whose distribution holds a pool"
    )
  }

  /** The ledger snapshots of [[referenceSnapshots]], each with its era, a state of the mirror over
    * it and the reference's own bytes of its new-epoch state.
    */
  private lazy val referenceStates: Vector[(String, Era, MirrorState, Bytes)] =
    // the queries asked of these read the ledger state only; the rest of the mirror is a capture
    // state's
    val (_, capture) = shelleyBased.head
    referenceSnapshots.map { name =>
      val raw = Bytes.fromArray(Files.readAllBytes(Path.of(s"fixtures/haskell-state/$name/state")))
      val file = HaskellLedgerFile.decode(raw).fold(e => fail(s"$name: ${e.render}"), identity)
      val ext = file.ext.getOrElse(fail(s"$name: no Shelley-based state"))
      // the file is `[1, [ledger telescope, header state]]`, the telescope's last item the current
      // era's `[start, [2, [tip, new-epoch state, transition]]]`
      val current = item(raw, 1, 0).items.flatMap(_.toVector.lastOption).getOrElse(fail(name))
      val nes = item(current.original, 1, 1, 1).original
      (name, file.era, MirrorState(ext, capture.extras, MirrorProvenance.complete), nes)
    }

  test(
    "a state the reference wrote answers its pool distribution with the reference's bytes, every pool for the empty set (#933)"
  ) {
    val held = referenceStates.map { (name, era, state, nes) =>
      era -> asksPoolDistr(name, era, QueryView(Point.Origin, None, state), pdIn(nes))
    }
    // the reference's Shelley snapshot is of the era's first epoch, whose distribution is empty
    assertEquals(
      held,
      Vector(
        Era.Shelley -> 0,
        Era.Allegra -> 3,
        Era.Mary -> 3,
        Era.Alonzo -> 3,
        Era.Babbage -> 3,
        Era.Conway -> 3
      )
    )
  }

  /** `GetStakeDistribution2` and `GetStakeDistribution`, `GetRewardInfoPools`,
    * `GetNonMyopicMemberRewards` and `GetLedgerPeerSnapshot` in its three forms, plain and through
    * `GetCBOR`, asked of `view` in `era` at versions 16 and 23. The first four are answered from
    * the current stake of the state: the stake distributions give each pool's stake as a fraction
    * of the lovelace in circulation, written here, the ratio untagged before Conway and under tag
    * 30 in it (`encodeRatio`, `Binary/Encoding/Encoder.hs:301-306`, cardano-ledger f649f975), the
    * rewards as the Conway answers write them. The ledger peers are not: they are the pools of the
    * pool distribution in force (`nesPd`) with the relays of their current and pending
    * registrations (`queryStakePoolRelays`, `Api/State/Query.hs:720-736`, cardano-ledger f649f975;
    * `LedgerPeersSource.of`), as the Conway answers write them. How many pools have stake in the
    * current stake.
    */
  private def asksStakeAndPeers[S](label: String, era: Era, view: QueryView[S]): Int =
    view.ledger.toOption.flatten match
      case Some(InEra.Of(_, l)) => asksStakeAndPeersOf(label, era, view, l)
      case None                 => fail(s"$label: no Shelley-based state")

  private def asksStakeAndPeersOf[S, E <: EraTag](
      label: String,
      era: Era,
      view: QueryView[S],
      l: EraRead[E]
  ): Int =
    val nes = l.nes.fold(e => fail(s"$label: $e"), identity)
    val ls = nes.es.ls
    val current = StakeDistribution.snapshot(
      EraLedger.activeStake(ls)(using l.era),
      EraLedger.pState(ls)(using l.era).stakePools
    )
    val circulation = queryEnv.maxSupply - nes.es.chainAccount.reserves.value
    val pd = PoolDistr.of(current)
    def ratio(w: CborOut, n: BigInt, d: BigInt): Unit =
      val g = n.gcd(d)
      if era == Era.Conway then w.tag(30)
      w.array(2).integer(n / g).integer(d / g)
    def fraction(w: CborOut, stake: BigInt): Unit =
      if circulation == 0 then ratio(w, 0, 1) else ratio(w, stake, circulation)
    val distribution = CborOut.build { w =>
      w.array(2).map(pd.distribution.size)
      pd.distribution.foreach { (id, p) =>
        w.bytes(id.bytes).array(3)
        fraction(w, p.totalPoolStake.toBigInt)
        w.integer(p.totalPoolStake.toBigInt).bytes(p.vrf.bytes)
      }
      w.integer(pd.totalActiveStake.value.value)
    }
    val retired = CborOut.build { w =>
      w.map(pd.distribution.size)
      pd.distribution.foreach { (id, p) =>
        w.bytes(id.bytes).array(2)
        fraction(w, p.totalPoolStake.toBigInt)
        w.bytes(p.vrf.bytes)
      }
    }
    val members = Vector[Either[BigInt, Credential]](Left(BigInt(0)), Left(BigInt(1000000000))) ++
      current.activeStake.entries.keysIterator.take(2).map(c => Right(EraRead.credential(c)))
    val rewardInfo = RewardQueries.info(l.params, circulation, current, nes.es.nonMyopic)
    val memberRewards = RewardQueries
      .members(l.params, circulation, current, nes.es.nonMyopic, members)
      .fold(e => fail(s"$label: $e"), identity)
    val peers = xyz.kd5ujc.scarab.node.LedgerPeersSource
      .of(l)
      .fold(e => fail(s"$label: $e"), _.pools)
    val magic = queryEnv.networkMagic
    List(NtcVersion.V16, NtcVersion.V23).foreach { version =>
      def asked(q: LedgerQuery): Either[Unanswered, String] =
        QueryAnswers
          .answerOrWhy(queryEnv, view, Map.empty, NtcQuery.IfCurrent(QueryEra.of(era), q), version)
          .map(_.hex)
      def both(q: LedgerQuery, expected: Bytes, latest: Bytes): Unit =
        val at = s"$label at ${version.number}: $q"
        assertEquals(asked(q), Right("81" + expected.hex), at)
        assertEquals(asked(LedgerQuery.GetCBOR(q)), Right("81" + wrapped(latest)), at)
      def same(q: LedgerQuery, expected: Bytes): Unit = both(q, expected, expected)
      same(LedgerQuery.GetStakeDistribution2, distribution)
      same(LedgerQuery.GetStakeDistribution, retired)
      same(LedgerQuery.GetRewardInfoPools, rewardInfo)
      same(LedgerQuery.GetNonMyopicMemberRewards(members), memberRewards)
      val big = PeerSnapshotQueries.encode(peers, view.point, magic, Some(LedgerPeerKind.Big), true)
      same(LedgerQuery.GetLedgerPeerSnapshot(LedgerPeerKind.Big), big)
      val all = PeerSnapshotQueries.encode(peers, view.point, magic, Some(LedgerPeerKind.All), true)
      same(LedgerQuery.GetLedgerPeerSnapshot(LedgerPeerKind.All), all)
      // the retired form takes the version's SRV choice, `GetCBOR` the latest's
      both(
        LedgerQuery.GetLedgerPeerSnapshotLegacy,
        PeerSnapshotQueries.encode(peers, view.point, magic, None, version.number >= 22),
        PeerSnapshotQueries.encode(peers, view.point, magic, None, true)
      )
    }
    pd.distribution.size

  test(
    "every state of every Shelley-based era answers the stake distribution and the pools' rewards of its current stake, and its ledger peers"
  ) {
    val staked = shelleyBased.map { (block, state) =>
      val tip = Point.Block(UInt64.saturating(BigInt(block.header.slot.toLong)), block.header.hash)
      val era = state.era
      era -> asksStakeAndPeers(
        s"$era block ${block.header.blockNo}",
        era,
        QueryView(tip, Some(block.header.blockNo), state)
      )
    }
    assertEquals(
      staked.collect { case (era, pools) if pools > 0 => era }.distinct,
      Vector(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway),
      "every era has a state with stake delegated to a pool"
    )
  }

  test(
    "a state the reference wrote answers the stake distribution and the pools' rewards of its current stake, and its ledger peers"
  ) {
    val staked = referenceStates.map { (name, era, state, _) =>
      era -> asksStakeAndPeers(name, era, QueryView(Point.Origin, None, state))
    }
    assert(staked.forall(_._2 > 0), s"a reference state with no stake delegated to a pool: $staked")
  }

  // ---- the queries of the certificate state

  private def array(n: CborNode): Vector[CborNode] =
    n.items.map(_.toVector).getOrElse(fail(s"not an array: ${n.original.hex}"))

  private def entries(n: CborNode): Vector[(CborNode, CborNode)] =
    n.entries.map(_.toVector).getOrElse(fail(s"not a map: ${n.original.hex}"))

  private def uint(n: CborNode): BigInt = n.value match
    case Value.UInt(v) => v
    case _             => fail(s"not an unsigned integer: ${n.original.hex}")

  private def bytesOf(n: CborNode): Bytes = n.value match
    case Value.ByteString(b) => b
    case _                   => fail(s"not a byte string: ${n.original.hex}")

  /** A credential `[0, key hash]` or `[1, script hash]` as the queries take it. */
  private def credentialOf(n: CborNode): Credential = array(n) match
    case Vector(kind, hash) if uint(kind) == 0 => Credential.Key(bytesOf(hash))
    case Vector(kind, hash) if uint(kind) == 1 => Credential.Script(bytesOf(hash))
    case _                                     => fail(s"not a credential: ${n.original.hex}")

  /** A definite map of encoded keys and values, in the order given (the states here hold fewer than
    * 24 of each, which the ledger writes definite).
    */
  private def mapOf(pairs: Vector[(Bytes, Bytes)]): Bytes =
    assert(pairs.sizeIs < 24, s"${pairs.size} entries")
    CborOut.build { w =>
      w.map(pairs.size)
      pairs.foreach { (k, v) =>
        w.raw(k)
        w.raw(v)
      }
    }

  /** The certificate state's queries and their answers, derived from the bytes of a new-epoch state
    * as the ledger writes it, item by item, not by the codecs under test: `[epoch, blocks, blocks,
    * [chain account, [certificate state, UTxO state], snapshots, ..], ..]`, the certificate state
    * `[pools, delegation]` before Conway and `[voting, pools, delegation]` in it; the pools
    * `[VRF key hashes, pools, future parameters, retirements]`, each pool `[VRF key hash, pledge,
    * cost, margin, account, owners, relays, metadata?, deposit, delegators]`; the delegation
    * `[accounts, ..]`, Shelley's accounts `[accounts, pointers]`, each `[pointer, balance, deposit,
    * pool?]`, Conway's the accounts, each `[balance, deposit, pool?, DRep?]`; each snapshot
    * `[active stake, pools]`, the active stake `credential -> [stake, pool]`, a pool's `[stake, ..,
    * number of delegators (9th), ..]` (`Shelley/State/CertState.hs`, `Conway/State/CertState.hs`,
    * `State/StakePool.hs:192-205`, `Shelley/State/Account.hs:60-66`,
    * `Conway/State/Account.hs:127-134`, `State/SnapShots.hs:239-296`, cardano-ledger f649f975).
    *
    * The answers are those the reference writes (`Api/State/Query.hs:152-164, 485-525, 644-700`,
    * `Api/State/Query/Account.hs:24-33`, `Shelley/API/Wallet.hs:154-160`; `Shelley/Ledger/Query.hs:
    * 1018, 1024-1030`, ouroboros-consensus 82ecba32): a pool's parameters are its state's fields
    * with the account as a reward account on `network` and the metadata `null` able, the owners as
    * the state holds them (untagged before Conway, under tag 258 in it); the pools of
    * `GetStakePools` under tag 258 in every era; the stake snapshots, before protocol version 11 as
    * every state here is, of the pools with a delegator in a snapshot.
    */
  private def certAnswers(
      era: Era,
      newEpochState: Bytes,
      network: Int
  ): Vector[(LedgerQuery, String)] =
    val epochState = item(newEpochState, 3)
    val cert = array(item(epochState.original, 1, 0))
    val conway = era == Era.Conway
    val pools = array(if conway then cert(1) else cert(0))
    val delegation = array(if conway then cert(2) else cert(1))
    val registered = entries(pools(1))
    // each account: its credential's bytes, the credential, its balance, deposit and pool items
    val accounts = entries(if conway then delegation(0) else array(delegation(0))(0)).map {
      (c, a) =>
        val fields = array(a)
        val (balance, deposit, pool) =
          if conway then (fields(0), fields(1), fields(2)) else (fields(1), fields(2), fields(3))
        (c.original, credentialOf(c), balance, deposit, pool)
    }
    def params(id: CborNode, sps: CborNode): Bytes =
      val f = array(sps)
      val account = array(f(4))
      val header = (if uint(account(0)) == 0 then 0xe0 else 0xf0) | network
      CborOut.build { w =>
        w.array(9).raw(id.original)
        (0 to 3).foreach(i => w.raw(f(i).original))
        w.bytes(Bytes(Vector(header.toByte) ++ bytesOf(account(1)).value))
        w.raw(f(5).original).raw(f(6).original)
        array(f(7)) match
          case Vector()  => w.nul
          case Vector(m) => w.raw(m.original)
          case _         => fail("metadata")
      }
    val paramsMap = mapOf(registered.map((id, sps) => id.original -> params(id, sps)))
    val deposits = mapOf(registered.map((id, sps) => id.original -> array(sps)(8).original))
    val poolIds = registered.map((id, _) => bytesOf(id)).toSet
    // the stake snapshots: before protocol version 11, the pools asked for or, for none, those with
    // a delegator in any snapshot, each with its stake in each (zero where it has none); the totals
    // the snapshots' active stakes, 1 for none
    val snapshots = array(epochState).lift(2).map(array).getOrElse(fail("no snapshots")).take(3)
    val snapshotPools = snapshots.map(sn => entries(array(sn)(1)).map((id, p) => id -> array(p)))
    def stakeIn(i: Int, id: Bytes): BigInt =
      snapshotPools(i).collectFirst { case (k, p) if k.original == id => uint(p(0)) }.getOrElse(0)
    val totals = snapshots.map { sn =>
      entries(array(sn)(0)).map((_, sd) => uint(array(sd)(0))).sum.max(1)
    }
    def stakeSnapshots(ids: Vector[Bytes]): Bytes = CborOut.build { w =>
      w.array(4)
      w.raw(mapOf(ids.sortBy(_.hex).map { id =>
        id -> CborOut.build { row =>
          row.array(3)
          (0 to 2).foreach(i => row.uint(stakeIn(i, id)))
        }
      }))
      totals.foreach(w.uint)
    }
    val withDelegators = snapshotPools.flatten.collect {
      case (id, p) if uint(p(8)) > 0 => id.original
    }.distinct
    val oneDeposit = accounts.headOption.toVector.map { (c, credential, _, deposit, _) =>
      LedgerQuery.GetStakeDelegDeposits(Set(credential)) -> mapOf(Vector(c -> deposit.original))
    }
    Vector(
      LedgerQuery.GetFilteredDelegationsAndRewardAccounts(accounts.map(_._2).toSet) ->
        CborOut.build { w =>
          w.array(2)
          w.raw(mapOf(accounts.collect {
            case (c, _, _, _, pool) if pool.value != Value.Null => c -> pool.original
          }))
          w.raw(mapOf(accounts.map((c, _, balance, _, _) => c -> balance.original)))
        },
      LedgerQuery.GetFilteredDelegationsAndRewardAccounts(Set.empty) -> CborOut.build(
        _.array(2).map(0).map(0)
      ),
      LedgerQuery.GetStakePools -> CborOut.build { w =>
        w.tag(258).array(registered.size)
        registered.foreach((id, _) => w.raw(id.original))
      },
      LedgerQuery.GetStakePoolParams(poolIds) -> paramsMap,
      LedgerQuery.GetStakePoolParams(Set.empty) -> CborOut.build(_.map(0)),
      LedgerQuery.GetPoolState(None) -> CborOut.build(
        _.array(4).raw(paramsMap).raw(pools(2).original).raw(pools(3).original).raw(deposits)
      ),
      LedgerQuery.GetPoolState(Some(Set.empty)) -> CborOut.build(
        _.array(4).map(0).map(0).map(0).map(0)
      ),
      LedgerQuery.GetStakeSnapshots(None) -> stakeSnapshots(withDelegators),
      LedgerQuery.GetStakeSnapshots(Some(poolIds)) -> stakeSnapshots(
        registered.map((id, _) => id.original)
      ),
      LedgerQuery.GetStakeDelegDeposits(Set.empty) -> mapOf(
        accounts.map((c, _, _, deposit, _) => c -> deposit.original)
      )
    ).++(oneDeposit).map((q, answer) => q -> answer.hex)

  /** The certificate state's queries and their `GetCBOR` forms asked of `view` in `era` at versions
    * 16 and 23, against the answers derived from its new-epoch state `nes`.
    */
  private def asksCertState[S](label: String, era: Era, view: QueryView[S], nes: Bytes): Unit =
    // the answers are derived as the reference writes them before protocol version 11
    view.ledger.toOption.flatten.foreach { case InEra.Of(_, l) =>
      assert(l.params.protocolMajor < 11, s"$label: protocol ${l.params.protocolMajor}")
    }
    val expected = certAnswers(era, nes, queryEnv.mirror.network)
    List(NtcVersion.V16, NtcVersion.V23).foreach { version =>
      def asked(q: LedgerQuery): Either[Unanswered, String] =
        QueryAnswers
          .answerOrWhy(queryEnv, view, Map.empty, NtcQuery.IfCurrent(QueryEra.of(era), q), version)
          .map(_.hex)
      expected.foreach { (q, answer) =>
        val at = s"$label at ${version.number}: $q"
        assertEquals(asked(q), Right("81" + answer), at)
        val plain = Bytes.fromHex(answer).fold(e => fail(e.message), identity)
        assertEquals(asked(LedgerQuery.GetCBOR(q)), Right("81" + wrapped(plain)), s"$at, GetCBOR")
      }
    }

  test(
    "every state of every Shelley-based era answers the queries of its certificate state, as its new-epoch state holds it"
  ) {
    val delegated = shelleyBased.map { (block, state) =>
      val era = state.era
      val label = s"$era block ${block.header.blockNo}"
      val nes = ask(era, block, state, LedgerQuery.DebugNewEpochState)
        .map(result)
        .getOrElse(fail(s"$label: DebugNewEpochState is not answered"))
      asksCertState(label, era, view(block, state), nes)
      // whether the state has a pool and an account delegating to it, before Conway
      era != Era.Conway && (state.ext.ledgerState.state match
        case i: EraLedgerState.InShelleyEra[e] =>
          given ShelleyBasedEra[e] = i.era
          val c = i.state.nes.es.ls.cert
          EraViews.pState(c).stakePools.nonEmpty && EraViews.accounts(c).exists(_._2.pool.nonEmpty)
        case _: EraLedgerState.ByronCurrent => false)
    }
    assert(delegated.exists(identity), "no state before Conway has a pool and a delegation")
  }

  test(
    "a state the reference wrote answers the queries of its certificate state with the reference's bytes"
  ) {
    val eras = referenceStates.map { (name, era, state, nes) =>
      asksCertState(name, era, QueryView(Point.Origin, None, state), nes)
      era
    }
    assertEquals(
      eras,
      Vector(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)
    )
  }

  test(
    "the stake snapshots choose their pools at protocols 10 and 11 and total the active stake, as the reference"
  ) {
    // `queryStakeSnapshots` (`Api/State/Query.hs:644-700`, cardano-ledger f649f975): for no list of
    // pools, below protocol 11 those with `spssNumDelegators > 0` in a snapshot (682-686), from 11
    // those with stake in one (687-690); of a list, below 11 every pool of it (691), from 11 only
    // those with stake (`mkStakeSnapshotMaybe`, 656-668, 696). Below 11 each pool has its stake in
    // every snapshot, zero where it has none (`mkStakeSnapshot`, 669-678, 694-695). The totals are
    // `ssTotalActiveStake` (697-699), the sum of all the snapshot's active stake
    // (`sumAllActiveStake`, `State/Stake.hs:157-160`; `State/SnapShots.hs:397-403`), 1 for none.
    val (l, snapshots) = shelleyBased.iterator
      .filter(_._1.era != Era.Conway)
      .flatMap { (block, state) =>
        view(block, state).ledger.toOption.flatten.flatMap { case InEra.Of(_, l) =>
          l.snapshots.toOption.filter(_.mark.pools.count(_._2.stake.toBigInt > 0) >= 2).map(l -> _)
        }
      }
      .nextOption()
      .getOrElse(fail("no state before Conway with two pools with stake in its mark snapshot"))
    // `zero`: a pool whose delegators hold nothing, so no active stake delegates to it and its stake
    // is zero, though it has a delegator; `staked`: a pool with stake
    val (zero, held) = snapshots.mark.pools.head
    assert(!held.numDelegators.isZero, "the pool has no delegator")
    val (staked, stakedPool) =
      snapshots.mark.pools
        .find((id, p) => id != zero && p.stake.toBigInt > 0)
        .getOrElse(fail("no pool"))
    def hash28(byte: Int): Bytes = Bytes(Vector.fill(28)(byte.toByte))
    // `unknown`: a pool no snapshot holds, to which an active stake of 7 ada delegates; the active
    // stake counts it, no pool's stake does
    val unknown =
      mirror.KeyHash.fromBytes[mirror.StakePool](hash28(0xee)).fold(e => fail(e.toString), identity)
    val stray = mirror.Credential.KeyHashObj[mirror.Staking](
      mirror.KeyHash.fromBytes[mirror.Staking](hash28(0xcd)).fold(e => fail(e.toString), identity)
    )
    val strayStake = mirror.NonZero
      .from(mirror.CompactCoin.fromUnsigned(7000000L))
      .fold(e => fail(e.toString), identity)
    val mark = mirror.SnapShot(
      mirror.ActiveStake(
        snapshots.mark.activeStake.entries
          .filter((_, d) => d.delegation != zero)
          .updated(stray, mirror.StakeWithDelegation(strayStake, unknown))
      ),
      snapshots.mark.pools.updated(zero, held.copy(stake = mirror.CompactCoin.zero))
    )
    val snaps = snapshots.copy(mark = mark, set = mirror.SnapShot.empty, go = mirror.SnapShot.empty)
    // the pools' stakes, which the stray 7 ada is not in, and the active stake, which it is
    val poolStakes = mark.pools.valuesIterator.map(_.stake.toBigInt).sum
    val totals = (poolStakes + 7000000, BigInt(1), BigInt(1))
    def row(stake: BigInt) = (stake, BigInt(0), BigInt(0))
    val delegated = mark.pools.collect {
      case (id, p) if !p.numDelegators.isZero => id.bytes -> row(p.stake.toBigInt)
    }.toMap
    val withStake = mark.pools.collect {
      case (id, p) if p.stake.toBigInt > 0 => id.bytes -> row(p.stake.toBigInt)
    }.toMap
    val listed = Set(zero.bytes, unknown.bytes, staked.bytes)
    val asked: Vector[(String, Option[Set[Bytes]])] =
      Vector("none" -> None, "zero, unknown and staked" -> Some(listed), "empty" -> Some(Set.empty))
    val expected
        : Map[(Int, String), (Map[Bytes, (BigInt, BigInt, BigInt)], BigInt, BigInt, BigInt)] =
      Map(
        (10, "none") -> delegated,
        (10, "zero, unknown and staked") -> Map(
          zero.bytes -> row(0),
          unknown.bytes -> row(0),
          staked.bytes -> row(stakedPool.stake.toBigInt)
        ),
        (10, "empty") -> Map.empty,
        (11, "none") -> withStake,
        (11, "zero, unknown and staked") -> Map(staked.bytes -> row(stakedPool.stake.toBigInt)),
        (11, "empty") -> Map.empty
      ).view.mapValues(rows => (rows, totals._1, totals._2, totals._3)).toMap
    assert(delegated.contains(zero.bytes) && !withStake.contains(zero.bytes), "the cases coincide")
    val actual = (for
      major <- List(10, 11)
      (label, ids) <- asked
    yield (major, label) -> CertStateAnswers.stakeSnapshotsOf(
      l.params.copy(protocolMajor = BigInt(major)),
      snaps,
      ids
    )).toMap
    // the cases whose pools, or whose totals, are not the reference's, then the whole answers
    def differing(part: ((Map[Bytes, (BigInt, BigInt, BigInt)], BigInt, BigInt, BigInt)) => Any) =
      expected.keys.toVector.sorted.filter(k =>
        actual.get(k).map(part) != expected.get(k).map(part)
      )
    assertEquals(differing(_._1), Vector.empty, "the pools and their stakes")
    assertEquals(differing(a => (a._2, a._3, a._4)), Vector.empty, "the totals")
    assertEquals(actual, expected)
  }

  test("the queries of Conway's governance stay Conway's: a state of an earlier era answers none") {
    // every other block query the reference's decoder takes before Conway is answered there; these
    // it refuses there (`requireCG`), and so does the node's decoder, before an answer is asked
    shelleyBased.filter(_._1.era != Era.Conway).take(40).foreach { (block, state) =>
      val era = state.era
      assertEquals(ask(era, block, state, LedgerQuery.GetConstitution), None)
      assertEquals(ask(era, block, state, LedgerQuery.GetDRepState(Set.empty)), None)
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
