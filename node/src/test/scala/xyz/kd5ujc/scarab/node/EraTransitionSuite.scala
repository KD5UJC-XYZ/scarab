// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus
import xyz.kd5ujc.scarab.ledger
import xyz.kd5ujc.scarab.storage
import xyz.kd5ujc.scarab.consensus.{BlockRef, FollowerEvent, FollowerEvents, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  BlockFailure,
  BlockRule,
  ByronGenesisFile,
  Header,
  HeaderVrf,
  NodeError,
  PeerError,
  ValidationError
}
import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import io.circe.parser
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era}
import xyz.kd5ujc.scarab.consensus.{ByronCheck, ByronRules}
import xyz.kd5ujc.scarab.consensus.state.{
  MirrorExtras,
  MirrorState,
  MirrorStep,
  PBftState,
  ProtocolState
}
import xyz.kd5ujc.scarab.node.read.{ChainRead, InEra}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.ledger.Nonce
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Era-walking regression suite over a committed capture of the `devnet.py up-eras` cluster
  * (`fixtures/eras`): a Byron start (one 50-slot epoch), then one Shelley-based epoch per protocol
  * step, Shelley, Allegra, Mary, Alonzo (2), Babbage (2), and Conway. Every block from Origin to
  * the third Conway block is there because the nonces depend on every VRF output.
  *
  * The suite decodes every block, checks era tags, ordering, linkage across every boundary and body
  * hashes, then runs the node's own block rules (Byron, TPraos, Praos) from the genesis state and
  * checks nonce carry-over and the epoch-nonce tick at each boundary. It needs no Docker.
  */
class EraTransitionSuite extends CatsEffectSuite:
  private val fixtures = Path.of("fixtures/eras")

  private val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => fail(e.message), identity)
  private val geometry = setup.geometry

  private val raws: Vector[Bytes] =
    Files
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e), identity))

  private val blocks: Vector[Block] =
    raws.map(Block.decode(_, geometry.byron.epochSlots).fold(e => fail(e.message), identity))

  private val headers: Vector[Header] = blocks.map(_.header)

  /** `(era, first epoch)` per era, from the capture manifest. */
  private val schedule: Vector[(Era, BigInt)] =
    val json = parser
      .parse(Files.readString(fixtures.resolve("eras.json")))
      .fold(e => fail(e.message), identity)
    json.hcursor
      .downField("eras")
      .as[Vector[io.circe.Json]]
      .fold(e => fail(e.message), identity)
      .map { j =>
        val c = j.hcursor
        val name = c.get[String]("era").fold(e => fail(e.message), identity)
        val epoch = c.get[BigInt]("epoch").fold(e => fail(e.message), identity)
        Era.values.find(_.toString.equalsIgnoreCase(name)).getOrElse(fail(s"era $name")) -> epoch
      }

  private val order: Vector[Era] =
    Vector(
      Era.Byron,
      Era.Shelley,
      Era.Allegra,
      Era.Mary,
      Era.Alonzo,
      Era.Babbage,
      Era.Conway
    )

  /** Index of the first block of each era after the first. */
  private val boundaries: Vector[Int] =
    blocks.indices.filter(i => i > 0 && blocks(i).era != blocks(i - 1).era).toVector

  private def eraAt(i: Int): Era = blocks(i).era

  private def applyBlock(
      state: MirrorState,
      header: Header,
      raw: Bytes,
      praos: consensus.PraosConfig = setup.praos
  ): Either[BlockFailure, MirrorState] =
    MirrorSteps.block(setup, state, header, raw, Some(praos))

  private def validate(state: MirrorState, i: Int): Either[BlockFailure, MirrorState] =
    applyBlock(state, headers(i), raws(i))

  /** The protocol parameters in force in the ledger state of a Shelley-based mirror. */
  private def params(s: MirrorState): ledger.ProtocolParams =
    s.ledger(None).toOption.flatten.fold(fail("no Shelley-based ledger state")) {
      case InEra.Of(_, l) => l.params
    }

  /** The genesis delegations of the ledger state of a Shelley-based mirror. */
  private def genDelegs(s: MirrorState): Map[Bytes, ledger.GenDelegPair] =
    s.ledger(None).toOption.flatten.fold(fail("no Shelley-based ledger state")) {
      case InEra.Of(_, l) => l.genDelegsAt(0)
    }

  /** State before block `i` is `states(i)`; `states(blocks.size)` is the final state. */
  private lazy val states: Vector[MirrorState] =
    blocks.indices.foldLeft(Vector(setup.mirrorStart)) { (acc, i) =>
      validate(acc.last, i) match
        case Right(next) => acc :+ next
        case Left(f) =>
          fail(
            s"block $i (${eraAt(i)}, slot ${headers(i).slot}) rejected by ${f.rule.name}: ${f.reason}"
          )
    }

  private def ref(i: Int): BlockRef =
    BlockRef(headers(i).slot, headers(i).hash, headers(i).blockNo, headers(i).isBoundary)

  // ---------------------------------------------------------------- the fixture itself

  test("the capture walks every era in order and holds each era's boundary windows") {
    val eras = blocks
      .map(_.era)
      .foldLeft(Vector.empty[Era])((acc, e) => if acc.lastOption.contains(e) then acc else acc :+ e)
    assertEquals(eras, order)
    assertEquals(schedule.map(_._1), order)
    // the first three blocks of every era, and the three blocks before each transition
    order.foreach { era =>
      val indices = blocks.indices.filter(eraAt(_) == era)
      assert(indices.size >= 3, s"$era has ${indices.size} blocks")
      assert(indices.zip(indices.drop(1)).forall((a, b) => b == a + 1), s"$era is not contiguous")
    }
    assertEquals(boundaries.size, 6)
    boundaries.foreach { b =>
      assertEquals(blocks.slice(b - 3, b).map(_.era).distinct.size, 1)
      assertEquals(blocks.slice(b, b + 3).map(_.era).distinct.size, 1)
      assertNotEquals(eraAt(b - 1), eraAt(b))
    }
  }

  test(
    "Byron: the capture starts with the epoch 0 boundary block and one signed main block per slot"
  ) {
    val byron = blocks.collect { case b: Block.Byron => b.header }
    assertEquals(byron.size, 50)
    assert(byron.head.boundary)
    assertEquals(
      (byron.head.epoch, byron.head.slot, byron.head.blockNo),
      (BigInt(0), BigInt(0), BigInt(0))
    )
    assert(byron.tail.forall(!_.boundary))
    assert(
      byron.tail.forall(_.main.exists(_.signature.isInstanceOf[Header.ByronSignature.Delegated]))
    )
    assertEquals(byron.map(_.blockNo), (0 to 49).map(BigInt(_)).toVector.updated(0, BigInt(0)))
    assertEquals(byron.head.prevHash, setup.origin)
    assertEquals(byron.head.protocolMagic, BigInt(setup.genesis.networkMagic))
    assert(byron.forall(_.epochSlots == 50))
  }

  test("linkage: every block names its predecessor, across every era boundary") {
    assertEquals(headers.head.prevHash, setup.origin)
    headers.zip(headers.drop(1)).zipWithIndex.foreach { case ((a, b), i) =>
      assertEquals(b.prevHash, Some(a.hash), s"block ${i + 1} (${b.era})")
      val number = if b.isBoundary then a.blockNo else a.blockNo + 1
      assertEquals(b.blockNo, number, s"block number at ${i + 1}")
      assert(b.slot > a.slot || (b.slot == a.slot && a.isBoundary), s"slot order at ${i + 1}")
    }
  }

  test("Shelley-based blocks: body hash and size match the header commitment") {
    val shelley = blocks.collect { case b: Block.Shelley => b }
    assertEquals(shelley.size, blocks.size - 50)
    shelley.foreach { b =>
      assertEquals(
        b.bodyHash,
        b.header.bodyHash,
        s"body hash of ${b.era} block ${b.header.blockNo}"
      )
      assertEquals(BigInt(b.bodySize), b.header.bodySize)
      assertEquals(b.header.era, b.era)
    }
    assert(
      shelley.map(_.transactions.size).sum > 0,
      "the capture should include the pool setup transaction"
    )
  }

  test("header encoding matches the era: TPraos up to Alonzo, Praos from Babbage") {
    blocks.foreach {
      case b: Block.Shelley =>
        assertEquals(b.header.vrf.isInstanceOf[HeaderVrf.TPraos], b.era.usesTPraos)
      case _ => ()
    }
  }

  test("declared protocol versions never decrease and reach each era's first protocol version") {
    // The reference nodes declare their software's protocol version in every header; the ledger
    // version per era (2..9) is what the update proposals set, so only the lower bound is fixed.
    val majors = blocks.collect { case b: Block.Shelley => b.era -> b.header.protocolVersion._1 }
    val first = Map(
      Era.Shelley -> 2,
      Era.Allegra -> 3,
      Era.Mary -> 4,
      Era.Alonzo -> 5,
      Era.Babbage -> 7,
      Era.Conway -> 9
    )
    majors.foreach((era, major) =>
      assert(major >= first(era), s"$era block declares protocol $major")
    )
    majors.zip(majors.drop(1)).foreach((a, b) => assert(b._2 >= a._2))
  }

  // ---------------------------------------------------------------- the era history

  test(
    "era history: every block's epoch lies in its era, and eras start exactly at the configured epochs"
  ) {
    val start = schedule.toMap
    val end = schedule.map(_._2).drop(1) :+ BigInt(Long.MaxValue)
    schedule.zip(end).foreach { case ((era, from), until) =>
      val epochs =
        blocks.indices.filter(eraAt(_) == era).map(i => geometry.epochOf(headers(i).slot))
      assert(
        epochs.forall(e => e >= from && e < until),
        s"$era epochs ${epochs.min}..${epochs.max}"
      )
      assertEquals(epochs.min, from, s"first $era block is in the first $era epoch")
    }
    assertEquals(setup.config.hardForkEpochs.map((e, n) => e -> n), start - Era.Byron)
    assertEquals(geometry.byron.epochs, BigInt(1))
    assertEquals(geometry.firstSlot(1), BigInt(50))
    assertEquals(geometry.firstSlot(2), BigInt(550))
  }

  test("the Byron genesis hash is the configured or canonical hash and starts the chain") {
    val byron = setup.byron.getOrElse(fail("no Byron genesis"))
    assertEquals(headers.head.prevHash, Some(byron.hash))
    assertEquals(byron.epochSlots, BigInt(50))
    assertEquals(byron.delegations.size, 3)
  }

  test("a configured Byron genesis hash that differs from the file's is a configuration error") {
    val text = Files.readString(fixtures.resolve("genesis/byron-genesis.json"))
    val wrong = Some(Bytes(Vector.fill(32)(7)))
    assert(ByronGenesisFile.parse(text, wrong).left.exists(_.problem.isInstanceOf[xyz.kd5ujc.scarab.ledger.ConfigProblem.GenesisHashMismatch]))
    assertEquals(
      ByronGenesisFile.parse(text, setup.origin).map(_.hash),
      Right(setup.origin.get)
    )
    assert(NodeConfig.parse("{\"TestShelleyHardForkAtEpoch\": \"x\"}").isLeft)
    assertEquals(NodeConfig.parse("{}"), Right(NodeConfig.empty))
  }

  // ---------------------------------------------------------------- validation across the boundaries

  test("the version-triggered era history agrees with the configured epochs on the whole chain") {
    // The cluster places the boundaries with Test<Era>HardForkAtEpoch and raises the ledger's
    // protocol version in step; the mainnet-style history (era from the ledger's version alone)
    // must give the same era for every Shelley-based block.
    val byVersion = consensus.HardForkConfig.default
    val byEpoch = setup.praos.forks
    blocks.indices.filter(blocks(_).isInstanceOf[Block.Shelley]).foreach { i =>
      val epoch = geometry.epochOf(headers(i).slot)
      val major = params(states(i + 1)).protocolMajor
      assertEquals(byEpoch.eraAt(epoch, major), eraAt(i), s"block $i by epoch")
      assertEquals(byVersion.eraAt(epoch, major), eraAt(i), s"block $i by version (major $major)")
    }
  }

  test("the first block of an era is rejected when the era history does not enter it there") {
    val allegra = boundaries(1)
    assertEquals(eraAt(allegra), Era.Allegra)
    val late = setup.praos.copy(forks =
      consensus.HardForkConfig.fromEpochs(setup.config.hardForkEpochs.updated(Era.Allegra, 99))
    )
    val result = applyBlock(states(allegra), headers(allegra), raws(allegra), late)
    assertEquals(result.left.map(_.rule), Left(BlockRule.Envelope))
    assert(result.left.exists(_.reason.contains("the chain is in Shelley")))
    // and the configured history accepts it
    assert(validate(states(allegra), allegra).isRight)
  }

  test("the node's block rules accept the whole chain from the genesis state") {
    assertEquals(states.size, blocks.size + 1)
    assertEquals(states.last.consensus.boot.era, Some(Era.Conway))
    assertEquals(states.last.consensus.byron.tip.map(_.hash), Some(headers(49).hash))
  }

  test("the genesis delegations survive the translation to Conway, unused, as in the reference") {
    // K1: db-analyser shows `dsGenDelegs` in every Conway state; clearing it differed at epoch 10.
    val before = states(boundaries.last)
    val after = states(boundaries.last + 1)
    assert(params(before).protocolMajor < 9 && params(after).protocolMajor >= 9)
    assert(genDelegs(before).nonEmpty)
    assertEquals(genDelegs(after), genDelegs(before))
  }

  test("Byron headers: boundary block structural, every main signature verified") {
    val checks = headers.indices.take(50).map(i => byronUpdate(i, byronBlock(i)))
    assert(checks.forall(_.isRight), checks.collectFirst { case Left(e) => e }.toString)
    val verdicts = checks.map(_.toOption.get._2)
    assertEquals(verdicts.head, ByronCheck.Boundary)
    // every main block is on the round-robin schedule, none carries a transaction
    assert(verdicts.tail.forall(_ == ByronCheck.Verified(true, 0)), verdicts.toString)
  }

  test("epoch nonces: the tick at every epoch boundary, carried over from the genesis hash") {
    val genesisHash = Nonce.Hash(
      Blake2b.hash256.hash(
        Bytes.fromArray(Files.readAllBytes(fixtures.resolve("genesis/shelley-genesis.json")))
      )
    )
    assertEquals(states(0).protocol.epochNonce, genesisHash)
    val shelley = blocks.indices.filter(headers(_).isInstanceOf[Header.Shelley])
    var epochs = 0
    shelley.foreach { i =>
      val before = states(i).protocol
      val after = states(i + 1).protocol
      val epoch = geometry.epochOf(headers(i).slot)
      assertEquals(after.epoch, epoch)
      if epoch > before.epoch then
        epochs += 1
        assertEquals(
          after.epochNonce,
          before.view.candidateNonce.combine(before.view.lastEpochBlockNonce),
          s"epoch nonce of $epoch"
        )
        assertEquals(
          after.view.lastEpochBlockNonce,
          before.view.labNonce,
          s"last-epoch-block nonce of $epoch"
        )
      else
        assertEquals(after.epochNonce, before.epochNonce)
        assertEquals(after.view.lastEpochBlockNonce, before.view.lastEpochBlockNonce)
      assertEquals(after.view.labNonce, Nonce.Hash(headers(i).prevHash.get))
    }
    assertEquals(epochs, 8) // epochs 1..8 are entered by a first block
    // the first Shelley epoch nonce is the genesis hash itself (nothing mixed in yet)
    assertEquals(states(shelley.head + 1).protocol.epochNonce, genesisHash)
    // the Byron prefix leaves the Praos state untouched
    assert((0 until 50).forall(i => states(i + 1).protocol == setup.mirrorStart.protocol))
  }

  test(
    "candidate nonce freezes at the stability window: the evolving nonce of the last block before it"
  ) {
    val shelley = blocks.indices.filter(headers(_).isInstanceOf[Header.Shelley])
    val byEpoch = shelley.groupBy(i => geometry.epochOf(headers(i).slot))
    byEpoch.foreach { (epoch, indices) =>
      val window =
        if eraAt(indices.head) == Era.Conway then geometry.randomnessStabilisationWindow
        else geometry.stabilityWindow
      val next = geometry.firstSlot(epoch + 1)
      val frozen = indices.filter(i => headers(i).slot + window < next)
      val last = indices.last
      val expected =
        frozen.lastOption.fold(states(indices.head).protocol.view.candidateNonce)(j =>
          states(j + 1).protocol.view.evolvingNonce
        )
      // the final captured Conway epoch is incomplete: only compare the epochs that ended
      if next <= headers.last.slot then
        assertEquals(
          states(last + 1).protocol.view.candidateNonce,
          expected,
          s"candidate of epoch $epoch"
        )
    }
  }

  test(
    "leader accounting with the full ledger: overlay while d = 1, then every header threshold-checked"
  ) {
    val validated = blocks.indices.flatMap { i =>
      NodeRules.observe(geometry)(states(i), states(i + 1), ref(i)).collect {
        case e: FollowerEvent.EpochValidated => e
      }
    }
    assertEquals(validated.map(_.epoch), (1 to 7).map(BigInt(_)))
    validated.foreach { v =>
      val inEpoch = blocks.indices.count(i => geometry.epochOf(headers(i).slot) == v.epoch)
      assertEquals(v.threshold + v.overlay + v.unchecked, inEpoch.toLong, s"epoch ${v.epoch}")
      assertEquals(v.unchecked, 0L)
      if v.era == Era.Shelley || v.era == Era.Allegra then
        assertEquals((v.threshold, v.overlay), (0L, inEpoch.toLong))
      else assertEquals((v.threshold, v.overlay), (inEpoch.toLong, 0L))
    }
  }

  test("every era transition is observed in order, at the configured epoch") {
    val seen = blocks.indices.flatMap { i =>
      NodeRules.observe(geometry)(states(i), states(i + 1), ref(i)).collect {
        case FollowerEvent.EraEntered(from, to, first, epoch) => (from, to, epoch, first.slot, i)
      }
    }
    assertEquals(seen.map(_._2), order)
    assertEquals(seen.map(_._3), schedule.map(_._2))
    assertEquals(seen.map(_._1), None +: order.dropRight(1).map(Some(_)))
    assertEquals(seen.map(_._5).drop(1), boundaries)
    val byron = blocks.indices.flatMap { i =>
      NodeRules.observe(geometry)(states(i), states(i + 1), ref(i)).collect {
        case e: FollowerEvent.ByronEpochValidated => e
      }
    }
    assertEquals(
      byron.toVector,
      Vector[FollowerEvent](FollowerEvent.ByronEpochValidated(0, 1, 49, 0, 0))
    )
  }

  // ---------------------------------------------------------------- negative cases

  private def flip(b: Bytes, i: Int): Bytes = Bytes(b.value.updated(i, (b.value(i) ^ 1).toByte))

  private def byronBlock(i: Int): Block.Byron = blocks(i).asInstanceOf[Block.Byron]

  /** The Byron rules on block `block` in place of block `i`, from the state before block `i`. */
  private def byronUpdate(
      i: Int,
      block: Block.Byron,
      from: Int = -1
  ): Either[xyz.kd5ujc.scarab.ledger.ByronChainError, (ByronRules.State, ByronCheck)] =
    val before = states(if from >= 0 then from else i).consensus.byron
    ByronRules.update(
      ByronRules.State(before.tip, before.ledger.get, before.pbft),
      block,
      setup.byronRules.get
    )

  private def withHeader(i: Int)(f: Header.Byron => Header.Byron): Block.Byron =
    val b = byronBlock(i)
    b.copy(header = f(b.header))

  test("a Byron header with a tampered signature is rejected") {
    val h = headers(5).asInstanceOf[Header.Byron]
    val main = h.main.get
    val delegated = main.signature.asInstanceOf[Header.ByronSignature.Delegated]
    val bad = withHeader(5)(
      _.copy(main =
        Some(main.copy(signature = delegated.copy(signature = flip(delegated.signature, 3))))
      )
    )
    val result = byronUpdate(5, bad)
    assert(result.left.contains(xyz.kd5ujc.scarab.ledger.ByronChainError.SignatureRejected), result.toString)
  }

  test("a Byron header signed over different content is rejected") {
    val main = headers(5).asInstanceOf[Header.Byron].main.get
    val bad = withHeader(5)(_.copy(main = Some(main.copy(signed = flip(main.signed, 40)))))
    assert(byronUpdate(5, bad).isLeft)
  }

  test("a Byron header from a foreign delegate is rejected, not accepted unchecked") {
    val h = headers(5).asInstanceOf[Header.Byron]
    val main = h.main.get
    val delegated = main.signature.asInstanceOf[Header.ByronSignature.Delegated]
    val other = withHeader(5)(
      _.copy(main =
        Some(main.copy(signature = delegated.copy(delegate = flip(delegated.delegate, 0))))
      )
    )
    assert(byronUpdate(5, other).isLeft)
  }

  test("a Byron block whose body does not match the header's proof is rejected") {
    val b = byronBlock(5)
    val proof = b.header.bodyProof
    val bad = b.copy(header = b.header.copy(bodyProof = flip(proof, proof.size - 3)))
    val result = byronUpdate(5, bad)
    assert(result.isLeft, result.toString)
  }

  test("a Byron block that skips its predecessor or has the wrong magic is rejected") {
    val b = byronBlock(5)
    assert(byronUpdate(5, b, from = 3).isLeft)
    assert(byronUpdate(5, withHeader(5)(_.copy(protocolMagic = 1))).isLeft)
    val first = states(0).consensus.byron
    assert(
      ByronRules
        .update(ByronRules.State(None, first.ledger.get, first.pbft), b, setup.byronRules.get)
        .isLeft
    )
  }

  test("the PBFT counts, kept up to date by every block, are those of the window") {
    val byron = states.filter(_.consensus.byron.ledger.isDefined)
    assert(byron.exists(_.consensus.byron.pbft.inWindow.nonEmpty))
    byron.foreach { s =>
      assertEquals(
        s.consensus.byron.pbft.counts,
        PBftState.ofWindow(s.consensus.byron.pbft.inWindow).counts
      )
    }
  }

  test("a Byron block signing more than the threshold of a window is rejected") {
    val b = byronBlock(5)
    val key = byronUpdate(5, b).toOption.get._1.pbft.inWindow.last.genesisKey
    val crowded =
      PBftState.ofWindow(states(5).consensus.byron.pbft.inWindow.map(_.copy(genesisKey = key)))
    val before = states(5).consensus.byron
    val result = ByronRules.update(
      ByronRules.State(before.tip, before.ledger.get, crowded),
      b,
      setup.byronRules.get
    )
    assert(result.left.exists(_.isInstanceOf[xyz.kd5ujc.scarab.ledger.ByronChainError.SignatureThreshold]), result.toString)
  }

  test("the first Shelley block is rejected under a different initial nonce") {
    val first = blocks.indexWhere(_.era == Era.Shelley)
    // the chain-dependent state Shelley starts from, held by the Byron mirror, with another nonce
    val other = ProtocolState.initial(Era.Byron, Blake2b.hash256.hash(Bytes(Vector(1, 2, 3))))
    val wrong = states(first).extras match
      case MirrorExtras.InByron(_, boot, _) =>
        states(first).copy(extras = MirrorExtras.InByron(other.dep, boot, other.extras))
      case _ => fail("the state before the first Shelley block is not Byron's")
    val result = validate(wrong, first)
    assert(result.left.exists(_.rule == BlockRule.TPraos), result.toString)
  }

  test("a Byron block cannot follow the first Shelley block") {
    val first = blocks.indexWhere(_.era == Era.Shelley)
    val result = validate(states(first + 1), 10)
    assert(result.left.exists(_.rule == BlockRule.Structure), result.toString)
  }

  test("a Shelley block with a flipped body byte fails the body commitment") {
    val i = blocks.indexWhere(b => b.era == Era.Mary)
    val raw = raws(i)
    val bad = Bytes(raw.value.updated(raw.size - 2, (raw.value(raw.size - 2) ^ 1).toByte))
    val result = applyBlock(states(i), headers(i), bad)
    assert(result.isLeft, result.toString)
  }

  // ---------------------------------------------------------------- the chain model across eras

  test(
    "the volatile chain accepts the Byron start (genesis hash, repeated block number and slot) and rejects a wrong origin"
  ) {
    val empty = VolatileChain.genesis[Int](5, 0, setup.origin)
    val grown = headers.take(52).foldLeft(empty) { (c, h) =>
      c.extend(h, 0).fold(e => fail(e.message), _._1)
    }
    assertEquals(grown.tipRef.map(_.hash), Some(headers(51).hash))
    assert(VolatileChain.genesis[Int](5, 0).extend(headers.head, 0).isLeft)
    assert(
      VolatileChain
        .genesis[Int](5, 0, Some(Bytes(Vector.fill(32)(1))))
        .extend(headers.head, 0)
        .isLeft
    )
    assert(empty.extend(headers(1), 0).isLeft)
  }

  /** Replay `script` through the real follower and rules over a scripted peer; returns once the
    * peer has no more instructions.
    */
  private def replay(
      script: Vector[SyncEvent]
  ): IO[(Vector[FollowerEvent], VolatileChain[MirrorState])] =
    val bodies = headers.map(_.hash).zip(raws).toMap
    for
      chainRef <- Ref.of[IO, VolatileChain[MirrorState]](
        VolatileChain.genesis(geometry.securityParam, setup.mirrorStart, setup.origin)
      )
      eventsRef <- Ref.of[IO, Vector[FollowerEvent]](Vector.empty)
      queue <- Queue.unbounded[IO, SyncEvent]
      _ <- script.traverse_(queue.offer)
      done <- Deferred[IO, Unit]
      given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(chainRef)
      events = new FollowerEvents[IO]:
        def emit(event: FollowerEvent) = eventsRef.update(_ :+ event)
      peer = new Peer[IO]:
        def intersect(candidates: Vector[ChainSync.Point])(using Raise[IO, PeerError]) =
          IO.pure(candidates.lastOption)
        def next(using Raise[IO, PeerError]) = queue.tryTake.flatMap {
          case Some(event) => IO.pure(event)
          case None        => done.complete(()) *> IO.never[SyncEvent]
        }
        def fetch(header: Header)(using Raise[IO, PeerError]) = IO.pure(bodies(header.hash))
      outcome <- Handle
        .allow[NodeError] {
          val rules = mirrorRules
          Follower
            .create[IO, MirrorState](rules, events, geometry, NodeRules.observe(geometry))
            .flatMap(follower =>
              IO.race(follower.session(peer), done.get.timeout(60.seconds))
                .as(().asRight[NodeError])
            )
        }
        .rescue(e => IO.pure(e.asLeft[Unit]))
      _ = outcome.fold(e => fail(e.message), identity)
      chain <- chainRef.get
      seen <- eventsRef.get
    yield (seen, chain)

  private def mirrorRules(using cats.mtl.Raise[IO, ValidationError]): MirrorRules[IO] =
    MirrorRules[IO](
      setup.praos,
      setup.globals,
      setup.byronRules,
      setup.handOver,
      setup.byronHistory
    )

  private def forward(i: Int): SyncEvent = SyncEvent.RollForward(headers(i), ChainSync.Tip.Origin)

  private def entered(events: Vector[FollowerEvent]) = events.collect {
    case FollowerEvent.EraEntered(from, to, _, epoch) => (from, to, epoch)
  }

  test("the follower follows Origin to Conway: one era transition per era, in order") {
    replay(headers.indices.map(forward).toVector).map { (events, chain) =>
      assertEquals(
        entered(events),
        order.zip(schedule.map(_._2)).zip(None +: order.map(Some(_))).map {
          case ((to, epoch), from) =>
            (from, to, epoch)
        }
      )
      assertEquals(events.count(_.isInstanceOf[FollowerEvent.Extended]), blocks.size)
      assertEquals(chain.tip, headers.last.point)
      assertEquals(chain.length, geometry.securityParam)
      assertEquals(chain.state.consensus.boot.era, Some(Era.Conway))
      assert(!events.exists(_.isInstanceOf[FollowerEvent.RolledBack]))
    }
  }

  test(
    "a rollback across the Byron to Shelley boundary restores the Byron state; the era is entered again"
  ) {
    // The real blocks form one chain, so the rollback is driven on the chain model and the rules
    // directly (the follower only rolls back when a different, preferred branch exists).
    val b = boundaries.head
    given cats.mtl.Raise[IO, ValidationError] = new cats.mtl.Raise[IO, ValidationError]:
      def functor = cats.Functor[IO]
      def raise[E2 <: ValidationError, A](e: E2): IO[A] = IO.raiseError(RuntimeException(e.message))
    val rules = mirrorRules
    def apply(chain: VolatileChain[MirrorState], range: Range): IO[VolatileChain[MirrorState]] =
      range.toList.foldLeftM(chain) { (c, i) =>
        rules(c.state, headers(i), raws(i)).map(next =>
          c.extend(headers(i), next).fold(e => fail(e.message), _._1)
        )
      }
    val genesis = VolatileChain.genesis(geometry.securityParam, setup.mirrorStart, setup.origin)
    for
      first <- apply(genesis, 0 until b + 3)
      rolled = first.rollback(headers(b - 1).point).fold(e => fail(e.message), identity)
      replayed <- apply(rolled, b until b + 6)
    yield
      assertEquals(rolled.state.consensus.boot.era, Some(Era.Byron))
      assertEquals(rolled.state, states(b))
      assertEquals(first.length - rolled.length, 3)
      assertEquals(
        NodeRules
          .observe(geometry)(
            rolled.state,
            states(b + 1),
            BlockRef(headers(b).slot, headers(b).hash, headers(b).blockNo)
          )
          .collect { case FollowerEvent.EraEntered(from, to, _, _) => (from, to) },
        List((Some(Era.Byron), Era.Shelley))
      )
      assertEquals(replayed.tip, headers(b + 5).point)
      assertEquals(replayed.state, states(b + 6))
  }

  test(
    "with the UTxO set in a store and verification warmed ahead the whole chain reaches the same state"
  ) {
    Handle
      .allow[NodeError] {
        val program =
          for
            store <- storage.MemoryUtxoStore[IO]
            cache = VerificationCache.unsafe(4)
            rules = MirrorRules[IO](
              setup.praos.copy(verdicts = cache),
              setup.globals,
              setup.byronRules,
              setup.handOver,
              setup.byronHistory,
              utxoStore = Some(store),
              afterBlock =
                Some((_, s) => IO.delay(cache.noteEpochNonce(s.ledgerEpoch, s.protocol.epochNonce)))
            )
            last <- blocks.indices.grouped(8).toVector.foldLeftM(setup.mirrorStart) {
              (state, batch) =>
                cache.warm[IO](batch.toVector.map(raws), setup.praos, 4) *>
                  batch.toVector.foldLeftM(state) { (s, i) =>
                    rules(s, headers(i), raws(i))
                      .flatTap(n => storage.UtxoStore.advance(store, n.utxo))
                  }
            }
            all <- storage.UtxoStore.materialize(store, last.utxo)
          yield (last, all)

        program.map(_.asRight[NodeError])
      }
      .rescue(e => IO.pure(e.asLeft[(MirrorState, Map[ledger.TxIn, ledger.TxOut])]))
      .map { outcome =>
        val (last, all) = outcome.fold(e => fail(e.message), identity)
        val expected = states.last
        assertEquals(all, expected.utxo.toMap)
        assertEquals(last.utxo.stake, expected.utxo.stake)
        assertEquals(last.utxo.coin, expected.utxo.coin)
        assertEquals(last.utxo.count, expected.utxo.count)
        assertEquals(
          MirrorStep.mapUtxo(last)(_ => ledger.Utxo.empty),
          MirrorStep.mapUtxo(expected)(_ => ledger.Utxo.empty)
        )
        assertEquals(last.protocol, expected.protocol)
        assertEquals(last.consensus.boot, expected.consensus.boot)
      }
  }
