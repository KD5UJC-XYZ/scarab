// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.consensus.leios.ProtVerParity
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.consensus.{BlockRef, ByronState, ConsensusExtras, FollowState}
import xyz.kd5ujc.scarab.consensus.HeaderRules.Outcome
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorExtras, MirrorState, ProtocolExtras}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover
import xyz.kd5ujc.scarab.ledger.{
  Block,
  BlockTransport,
  ForgeError,
  ForgeProtocolProblem,
  Header,
  HeaderError,
  LeiosBlock,
  LeiosBlockBody,
  LeiosHeader,
  Nonce,
  OCertError,
  Praos2Error,
  TestLedger
}
import xyz.kd5ujc.scarab.ledger.era.{Tx, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx
import xyz.kd5ujc.scarab.ledger.rules.Globals
import xyz.kd5ujc.scarab.ledger.state.{
  CompactCoin,
  Dijkstra,
  KeyHash,
  NodeExtras,
  NewEpochState,
  ShelleyBasedEra,
  StakePool,
  VRFVerKeyHash,
  Word64
}
import xyz.kd5ujc.scarab.network.Word32
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.{NodeHeaderRules, NodeHeaderState, NodeSetup}
import xyz.kd5ujc.scarab.node.mempool.{Capacity, EbMeasure}
import xyz.kd5ujc.scarab.consensus.leios.AnnouncingHeader
import cats.data.NonEmptyVector
import xyz.kd5ujc.scarab.node.read.InEra
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{Era, HfcHeader, NtNHeader, ReferenceBuild}
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellLedgerFile
import xyz.kd5ujc.scarab.protocol.StateLayout

/** A Dijkstra block forged as the forger forges it under `--reference-build leios-rc-2026w47`, on
  * the public Leios testnet's ledger state at slot 2,168,639
  * (`fixtures/leios/state/testnet-2168639`, block 97,273), and checked as a peer's block is
  * checked. The pool is a test pool of throwaway keys: its stake is that of the testnet's ten
  * largest pools, moved to it in the state's set snapshot, the source of both the forger's leader
  * check and the header rules' ledger view; the total and every other pool are left as they are.
  *
  * The steps are the forger's own ([[Forger.ledgerAt]], [[Forger.leaderView]], [[Forger.inputsOf]],
  * [[Leadership.check]], [[Forger.tickedTo]], [[BlockBuilder.Assembly]]); only the KES signature is
  * made here, by the same `sign` the forger calls.
  *
  * Two blocks are forged: the minimal one, and a ranking block with the 40 transactions of the
  * testnet's block 64,853, taken from a snapshot of the pool ([[DijkstraSnapshot]],
  * [[BlockBuilder.ranking]]) within the capacity of Dijkstra's parameters in the state
  * ([[Capacity.dijkstraBlockUnit]]), whose body must be the recorded block's, byte for byte.
  *
  * Checked: the header through the peer's ChainSync path and Praos2's rules ([[NodeHeaderRules]]),
  * the block through BlockFetch's `blockMatchesHeader` ([[BlockTransport.check]]) and the body
  * checks of [[LeiosBlock.faults]], including the size BBODY checks. The ledger rules of a Dijkstra
  * block (BBODY's LEDGERS) are not on this branch: the node refuses a Dijkstra block there
  * (`BlockFailure.NotYet`), so they are not run here.
  */
class DijkstraForgeSuite extends munit.FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)
  private def rows(file: Path): Vector[Array[String]] =
    Files
      .readAllLines(file)
      .asScala
      .toVector
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .map(_.split('\t'))

  private val leios = Path.of("fixtures", "leios")
  private val build = ReferenceBuild.Leios
  private lazy val setup = NodeSetup
    .fromConfigFile(build)(leios.resolve("testnet/config/config.json"))
    .fold(e => fail(e.message), identity)
  private def geometry = setup.praos.geometry

  /** The testnet's state at slot 2,168,639, as `DijkstraHeaderRulesSuite` reads it, with the
    * bookkeeping of a chain in Shelley's eras that `import-ledger-snapshot` gives an imported state
    * (`LedgerSnapshotImport`), so that the node's reading of its chain-dependent state is the
    * state's header state, as for any chain past Byron.
    */
  private lazy val testnet: MirrorState =
    val raw = Bytes.wrap(Files.readAllBytes(leios.resolve("state/testnet-2168639/state")))
    val file = HaskellLedgerFile.decode(raw, StateLayout.Leios).fold(e => fail(e.render), identity)
    val ext = file.ext.getOrElse(fail("no Shelley-based ledger"))
    val shelley = MirrorExtras.InShelley(
      NodeExtras.empty,
      ConsensusExtras(
        FollowState(Some(Era.Dijkstra), None, None),
        ByronState.initial,
        ProtocolExtras(BigInt(100), Nonce.Neutral)
      )
    )
    setup.mirrorStart.copy(ext = ext, extras = shelley)
  private val tip = BlockRef(
    BigInt(2168639),
    hex("aed73a1050263343a16d9a980b2f6dce1152c2e959b69987230a9e5f4844f619"),
    BigInt(97273)
  )

  private def dijkstra(state: MirrorState): NewEpochState[Dijkstra] =
    state.ledger(None).toOption.flatten match
      case Some(i: InEra.Of[e]) =>
        i.era match
          case ShelleyBasedEra.SDijkstra => i.read.nes.fold(e => fail(e.toString), identity)
          case other                     => fail(s"not in Dijkstra: $other")
      case other => fail(s"no Shelley-based ledger: $other")

  // --- the test pool: throwaway keys, never a real pool's ---------------------------------------

  private val cold = TestLedger.key()
  private val kes =
    Sum6KesSigning.generate(Bytes.fromArray(Array.fill(32)(54.toByte))).toOption.get
  private val vrf =
    Draft03Prover.SigningKey.fromSeed(Bytes.fromArray(Array.fill(32)(85.toByte))).toOption.get
  private val poolId = Blake2b.hash224.hash(cold.vkey)

  /** The operational certificate from the KES period `start`, counter 0. */
  private def opCert(start: BigInt): Header.OpCert =
    def be8(n: Long) = Array.tabulate[Byte](8)(i => (n >> (8 * (7 - i))).toByte)
    val signable = Bytes.fromArray(kes.verificationKey.toArray ++ be8(0) ++ be8(start.toLong))
    Header.OpCert(kes.verificationKey, 0, start, cold.sign(signable))

  /** The testnet's state with the stake of its ten largest pools moved to the test pool. */
  private lazy val injected: MirrorState =
    val nes = dijkstra(testnet)
    val set = nes.es.snapshots.set
    val largest = set.pools.toVector.sortBy(-_._2.stake.toBigInt).take(10)
    val (_, first) = largest.head
    val merged = first.copy(
      stake = CompactCoin.fromUnsigned(largest.map(_._2.stake.toBigInt).sum.toLong),
      stakeRatio = largest.map(_._2.stakeRatio).reduce(_ + _),
      numDelegators = Word64.fromUnsigned(largest.map(_._2.numDelegators.toBigInt).sum.toLong),
      vrf = VRFVerKeyHash.fromBytes(Blake2b.hash256.hash(vrf.publicKey)).toOption.get
    )
    val id = KeyHash.fromBytes[StakePool](poolId).toOption.get
    val pools = set.pools.removedAll(largest.map(_._1)).updated(id, merged)
    val next =
      nes.copy(es = nes.es.copy(snapshots = nes.es.snapshots.copy(set = set.copy(pools = pools))))
    val ledger = testnet.ext.ledgerState
    val replaced = ledger.state match
      case EraLedgerState.InShelleyEra(ShelleyBasedEra.SDijkstra, s) =>
        EraLedgerState.InShelleyEra(ShelleyBasedEra.SDijkstra, s.copy(nes = next))
      case other => fail(s"not in Dijkstra: $other")
    testnet.copy(ext = testnet.ext.copy(ledgerState = ledger.withState(replaced)))

  // --- the forger's steps --------------------------------------------------------------------

  private val protocolVersion = (BigInt(12), BigInt(0))

  final case class Forged(slot: BigInt, raw: Bytes, header: LeiosHeader)

  /** The first slot after the tip the test pool leads, by the forger's leader check, and the
    * minimal block forged in it.
    */
  private lazy val epoch = geometry.epochOf(tip.slot + 1)
  private lazy val ledger =
    Forger
      .ledgerAt(injected, Some(tip), epoch, tip.slot + 1, build)
      .fold(e => fail(e.message), identity)

  /** The inputs of the test pool's leader check in the tip's epoch, as the forger derives them. */
  private lazy val inputs: EpochInputs =
    val view = Forger.leaderView(ledger, injected.protocol, epoch)
    Forger.inputsOf(geometry, epoch, view, poolId, setup.praos.activeSlotsCoeff, vrf.publicKey)

  /** The first slot the test pool leads, its certificate, and the state ticked into it. */
  private lazy val led: (BigInt, xyz.kd5ujc.scarab.ledger.VrfCert, Forger.Ticked) =
    val window = (tip.slot + 1) until (tip.slot + 1 + geometry.stabilityWindow.toBigInt)
    val (slot, cert) = window.iterator
      .filter(s => geometry.epochOf(s) == epoch)
      .map(s => s -> LentKeys.check(s, inputs, vrf))
      .collectFirst { case (s, Right(cert)) => s -> cert }
      .getOrElse(fail("the test pool leads no slot of the forecast range"))
    val ticked = Forger
      .tickedTo(
        Globals(setup.globals, geometry),
        setup.praos.forks.transition(geometry.k),
        ledger,
        slot
      )
      .fold(e => fail(e.message), identity)
    (ticked, ledger) match
      case (Forger.TipLedger.InDijkstra(nes), Forger.TipLedger.InDijkstra(parent)) =>
        (slot, cert, Forger.Ticked.InDijkstra(nes, parent))
      case other => fail(s"ticked out of Dijkstra: $other")

  /** The block of `body` forged in the test pool's slot, as [[Forger]]'s `assemble` builds it. */
  private def forge(body: BlockBuilder.Assembly): Forged =
    val (slot, cert, _) = led
    val period = slot / setup.praos.slotsPerKesPeriod
    val headerBody = body.headerBody(
      tip.blockNo + 1,
      slot,
      Some(tip.hash),
      cold.vkey,
      vrf.publicKey,
      cert,
      opCert(period),
      protocolVersion
    )
    val raw = body.block(headerBody, kes.sign(headerBody).toOption.get)
    val header = LeiosBlock.decode(raw).fold(e => fail(e.message), _.header)
    Forged(slot, raw, header)

  private lazy val forged: Forged = forge(BlockBuilder.Assembly.minimalDijkstra)

  // --- the checks a peer's block meets -------------------------------------------------------

  private lazy val rules =
    NodeHeaderRules[MirrorState](
      setup.praos,
      setup.globals.copy(referenceBuild = build),
      setup.byronRules,
      setup.handOver
    )

  /** A header as a peer's ChainSync sends it, `[7, tag24(header)]`, read as the peer path does. */
  private def received(raw: Bytes): Header =
    HfcHeader
      .encode(HfcHeader.Dijkstra(raw))
      .left
      .map(_.toString)
      .flatMap(NtNHeader.codec.decode(_).left.map(_.toString))
      .flatMap(Header.fromNtN(_, geometry.byron.epochSlots).left.map(_.message))
      .fold(e => fail(e), identity)

  // the block's ledger is ticked once from its parent's state, as block application ticks it
  // (`applyChainTick`, `EraBlock.ticked`): past the new epoch's slot of no return a tick to the
  // epoch's first slot and then to the slot solidifies the future parameters the boundary made
  test("a Dijkstra block's ledger is its parent's ticked once to the slot, across a boundary") {
    val g = Globals(setup.globals, geometry)
    val transition = setup.praos.forks.transition(geometry.k)
    val parent = Forger.TipLedger.InDijkstra(dijkstra(testnet))
    val next = parent.epoch + 1
    val slot = g.slotOfNoReturn(next)
    assertEquals(geometry.epochOf(slot), next)
    def tick(from: Forger.TipLedger, to: BigInt) =
      Forger.tickedTo(g, transition, from, to).fold(e => fail(e.message), identity)
    val base = Forger.crossed((Some(tip.hash), next), parent, testnet.protocol, next).ledger
    assertEquals(base, parent)
    val direct = tick(parent, slot)
    assertEquals(tick(base, slot), direct)
    // the two ticks the forger made before differ from the block's at that slot
    assertNotEquals(tick(tick(parent, geometry.firstSlot(next)), slot), direct)
  }

  test("the test pool leads some slots after the tip and not others: the leader check decides") {
    val led = ((tip.slot + 1) to (tip.slot + 400)).map(s => LentKeys.check(s, inputs, vrf).isRight)
    val count = led.count(identity)
    // a third of the stake, and at the testnet's active slot coefficient about one slot in 120:
    // the three of these 400 slots its VRF key wins, the first of them the forged block's
    val share = Rational(inputs.stake, inputs.total)
    assert(share > Rational(3, 10) && share < Rational(35, 100), share)
    assertEquals(count, 3)
    assertEquals(forged.slot, tip.slot + 1 + led.indexWhere(identity))
  }

  /** The scheduler's inputs on the injected chain under `build`: [[Forger.scheduleInputs]], all of
    * the scheduler's derivation but its cache, given the slot's own ledger view of the tip.
    */
  private def scheduled(build: ReferenceBuild): LeaderSchedule.InForce =
    Forger.scheduleInputs[cats.Id, MirrorState](
      injected,
      Some(tip),
      build,
      geometry,
      poolId,
      setup.praos.activeSlotsCoeff,
      vrf.publicKey,
      0
    )(e =>
      Forger
        .ledgerAt(injected, Some(tip), e, geometry.firstSlot(e), build)
        .map(Forger.leaderView(_, injected.protocol, e))
    )

  test(
    "the leader schedule covers the Dijkstra tip's epoch, its led slots the slot check's (#54)"
  ) {
    assertEquals(ledger.epoch, epoch)
    // the tip's epoch is scheduled, and the next once the tip is past that epoch's freeze slot
    val inForce = scheduled(build)
    assertEquals(
      inForce.epochs.map(_.epoch),
      epoch +: Vector(epoch + 1).filter(next => tip.slot >= Forger.freeze(geometry, next))
    )
    // the scheduler's inputs are those the slot's check derives, and so are its led slots
    val ofEpoch = inForce.byEpoch.getOrElse(epoch, fail(s"epoch $epoch not scheduled"))
    assertEquals(ofEpoch, inputs)
    val from = tip.slot + 1
    val until = from + 400
    val chunk = LeaderSchedule.decided(ofEpoch, from, until, Leadership.Evaluator.of(vrf))
    assertEquals(chunk.failure, None)
    assertEquals(
      chunk.led.map(_._1),
      (from until until).filter(s => LentKeys.check(s, inputs, vrf).isRight).toVector
    )
    assert(chunk.led.nonEmpty, "the test pool leads some of these slots")
  }

  test("the forger reads the same share of every pool as Praos2's ledger view") {
    // the forger's stake over the distribution's total, and the rational share Praos2 reads
    // (`IndividualPoolStake`'s stake, `DijkstraCrossing`), for every pool of the unchanged state
    val nes = dijkstra(testnet)
    val forger = Forger.TipLedger.InDijkstra(nes).leaderPools(nextEpoch = false)
    val shares = nes.pd.distribution.iterator.map((id, p) => id.bytes -> p.stake).toMap
    assertEquals(forger.stake.keySet, shares.keySet)
    assert(shares.size > 100, shares.size)
    forger.stake.foreach((id, stake) => assertEquals(Rational(stake, forger.total), shares(id)))
  }

  test("a minimal Dijkstra block forged on the testnet's state passes the header rules of a peer") {
    val start = rules.at(injected) match
      case s: NodeHeaderState.Shelley => s.copy(previous = Some(tip))
      case other                      => fail(s"not a Shelley header state: $other")
    val header = received(forged.header.raw)
    assertEquals(header.era, Era.Dijkstra)
    rules.validate(injected, Some(tip), start, header) match
      case Outcome.Valid(_) => ()
      case other            => fail(s"slot ${forged.slot}: $other")
  }

  test("the header rules refuse the forged header in the state where the pool has no stake") {
    // the issuer is neither a pool of the distribution nor in the counters: `NoCounterForKeyHashOCERT`
    val start = rules.at(testnet) match
      case s: NodeHeaderState.Shelley => s.copy(previous = Some(tip))
      case other                      => fail(s"not a Shelley header state: $other")
    assertEquals(
      rules.validate(testnet, Some(tip), start, received(forged.header.raw)),
      Outcome.Invalid(HeaderError.Praos2(Praos2Error.Certificate(OCertError.NoCounter(poolId))))
    )
  }

  test("the forged block matches its header and is a well-formed ranking block") {
    val header = received(forged.header.raw)
    val block = BlockTransport.check(header, forged.raw).fold(e => fail(e.message), identity)
    block match
      case d: Block.Dijkstra => assertEquals(d.block.faults, Vector.empty)
      case other             => fail(s"not a Dijkstra block: $other")
    assertEquals(
      Block.decode(forged.raw, geometry.byron.epochSlots).map(_.era),
      Right(Era.Dijkstra)
    )
  }

  /** The CBOR shape of an item: its kind, the length of a byte string, a list's items in order. */
  private def shape(n: Node): String = n.value match
    case Value.UInt(_)       => "uint"
    case Value.ByteString(b) => s"bytes(${b.size})"
    case Value.Bool(b)       => s"bool($b)"
    case Value.Null          => "null"
    case Value.Arr(xs)       => xs.map(shape).mkString("[", ",", "]")
    case other               => fail(s"unexpected item $other")

  test("the forged block has the shape of the testnet's empty blocks, item for item") {
    // block 1 of the testnet: an empty block after another, no announcement, no certificate
    val recorded = rows(leios.resolve("testnet/blocks-rc-2026w44.tsv")).collectFirst {
      case r if r(0) == "block-1" => hex(r(5))
    }.get
    def decoded(raw: Bytes): Node = Cbor.decode(raw).fold(e => fail(e.toString), identity)
    assertEquals(shape(decoded(forged.raw)), shape(decoded(recorded)))
    val ours = LeiosBlock.decode(forged.raw).toOption.get
    val theirs = LeiosBlock.decode(recorded).toOption.get
    assertEquals(LeiosBlockBody(Vector.empty, None, None).encode.hex, "8380f6f6")
    assertEquals(ours.bodyHash, theirs.bodyHash)
    assertEquals(ours.bodySize, theirs.bodySize)
    assertEquals(ours.header.bodyHash, theirs.header.bodyHash)
    assertEquals(ours.header.bodySize, BigInt(4))
    assertEquals(ours.header.leios, theirs.header.leios)
    assertEquals(forged.header.leios.versionInfo.highestSupportedMajor, BigInt(12))
    assertEquals(forged.header.blockNo, tip.blockNo + 1)
    assertEquals(forged.header.prevHash, Some(tip.hash))
    assertEquals(forged.header.issuerVkey, cold.vkey)
    assertEquals(forged.header.vrfVkey, vrf.publicKey)
    // the KES signature is over the header body the block holds
    assertEquals(forged.header.signable, forged.header.body.encode)
    assertEquals(Bytes.fromArray(forged.raw.toArray.take(2)), hex("8208"))
  }

  test("a release build keeps the refusal of a Dijkstra tip") {
    val epoch = geometry.epochOf(tip.slot + 1)
    ReferenceBuild.values.filter(b => !Forger.forgesDijkstra(b)).foreach { release =>
      assertEquals(
        Forger.ledgerAt(injected, Some(tip), epoch, tip.slot + 1, release),
        Left(ForgeError.UnsupportedProtocol(ForgeProtocolProblem.Earlier(Era.Dijkstra))),
        release.toString
      )
      // nor is a schedule computed for it: the slot's check refuses it
      assertEquals(scheduled(release), LeaderSchedule.InForce.none)
    }
  }

  // --- a ranking block with transactions ------------------------------------------------------

  /** Block 64,853 of the testnet (`announcing-with-txs` of `blocks-rc-2026w44.tsv`), which the
    * reference forged with 40 transactions in its body and an endorser block announced, and its
    * body's bytes as the block holds them.
    */
  private lazy val recorded: (LeiosBlock, Bytes) =
    val raw = rows(leios.resolve("testnet/blocks-rc-2026w44.tsv")).collectFirst {
      case r if r(0) == "announcing-with-txs" => hex(r(5))
    }.get
    val body = Cbor.decode(raw).fold(e => fail(e.toString), identity).value match
      case Value.Arr(Vector(_, block)) =>
        block.value match
          case Value.Arr(Vector(_, b)) => b.original
          case other                   => fail(s"not [header, body]: $other")
      case other => fail(s"not [tag, block]: $other")
    (LeiosBlock.decode(raw).fold(e => fail(e.message), identity), body)

  private def word32(n: Long): Word32 = Word32.fromLong(n).getOrElse(fail(s"not a Word32: $n"))

  /** The parameters of the slot the test pool forges in. */
  private lazy val params = led._3.params

  /** Block 64,853's transactions as a snapshot of the pool would hold them, oldest first, each with
    * the measure admission gives it ([[Capacity.dijkstraMeasure]]). The block is from slot
    * 1,455,869 and the only state here is of slot 2,168,639, whose UTxO set no longer holds the
    * outputs its transactions spend: they are not revalidated here, and their reference-script
    * bytes, measured against the UTxO set at admission, are taken as none. The revalidation is the
    * pool's ([[DijkstraSnapshot]]).
    */
  private lazy val snapshot: DijkstraSnapshot =
    DijkstraSnapshot(recordedTxs.map(tx => DijkstraHeld(tx, Capacity.dijkstraMeasure(tx, 0L))))

  /** Block 64,853's transactions, read again by the era's decoder from the bytes the block holds.
    */
  private lazy val recordedTxs: Vector[Tx[TxLevel.Top, Dijkstra]] =
    recorded._1.transactions

  test("a ranking block's capacity is the testnet's, from Dijkstra's parameters") {
    // `blockCapacityConwayMeasure` at Dijkstra: 90,112 less 1,024 bytes, the block's units, and
    // the parameters' 1 MiB of reference scripts
    assertEquals(
      Capacity.dijkstraBlockUnit(params),
      TxMeasure(89088L, BigInt(72000000), BigInt(20000000000L), 1048576L)
    )
    // the closure part of an endorser block's capacity, as the pool reserves it: the parameters
    // of the state as Scarab decodes it, which differ from both genesis files' (260,000 bytes,
    // 720,000,000 and 2e11 units, 1 MiB); no other recording holds them, so they are pinned here,
    // not confirmed against the reference
    val eb = TxMeasure(2000000L, BigInt(2160000000L), BigInt(600000000000L), 12000000L)
    // within the LeiosFetch limit of 600,000 bytes, so the wire guard passes
    assertEquals(params.maxEndorserBlockReferencesSize, BigInt(308000))
    assertEquals(Capacity.endorserBlockReservation(params), Right(eb))
    // the pool holds two of each, and an override is counted in units of both
    val unit = Capacity.dijkstraBlockUnit(params) + eb
    assertEquals(Capacity.ofDijkstra(params, None), Right(unit + unit))
    assertEquals(
      Capacity.ofDijkstra(params, Some(word32(unit.bytes * 2 + 1))).map(_.bytes),
      Right(unit.bytes * 3)
    )
    assertEquals(Capacity.ofDijkstra(params, Some(word32(1))), Right(unit))
  }

  test("block 64,853's transactions measure as the reference's sizes, and fit one ranking block") {
    val txs = recordedTxs
    assertEquals(txs.size, 40)
    // `sizeTxF` writes the three memoized parts under a one-byte head, the block form also the flag
    txs.foreach(tx =>
      assertEquals(tx.sizeTxF, DijkstraTx.forBlockInclusion(tx).size.toLong - 1, tx.id.hex)
    )
    val total = snapshot.held.map(_.measure).reduce(_ + _)
    assertEquals(total.bytes, txs.map(_.sizeTxF + Capacity.PerTxOverhead).sum)
    val ranking = BlockBuilder.ranking(snapshot, Capacity.dijkstraBlockUnit(params))
    assertEquals(ranking.transactions, txs)
    assertEquals(ranking.used, total)
    assert(Capacity.fits(total)(Capacity.dijkstraBlockUnit(params)), total)
  }

  test("the forged body of block 64,853's transactions is the reference's, byte for byte") {
    val ranking = BlockBuilder.ranking(snapshot, Capacity.dijkstraBlockUnit(params))
    val (block, recordedBody) = recorded
    BlockBuilder.Assembly.dijkstra(ranking.transactions, None) match
      case BlockBuilder.Assembly.InDijkstra(body, _) =>
        assertEquals(body.encode, recordedBody)
        assertEquals(body.hash, block.header.bodyHash)
        assertEquals(BigInt(body.size), block.header.bodySize)
      case other => fail(s"not a Dijkstra body: $other")
  }

  test(
    "a ranking block forged with transactions passes Praos2's header rules and the block checks"
  ) {
    val ranking = BlockBuilder.ranking(snapshot, Capacity.dijkstraBlockUnit(params))
    val body = BlockBuilder.Assembly.dijkstra(ranking.transactions, None)
    val withTxs = forge(body)
    assertEquals(BigInt(body.size), withTxs.header.bodySize)
    assert(body.size <= params.maxBlockBodySize, body.size)
    assertEquals(body.ids, recorded._1.transactions.map(_.id))
    val start = rules.at(injected) match
      case s: NodeHeaderState.Shelley => s.copy(previous = Some(tip))
      case other                      => fail(s"not a Shelley header state: $other")
    val header = received(withTxs.header.raw)
    rules.validate(injected, Some(tip), start, header) match
      case Outcome.Valid(_) => ()
      case other            => fail(s"slot ${withTxs.slot}: $other")
    BlockTransport.check(header, withTxs.raw).fold(e => fail(e.message), identity) match
      case d: Block.Dijkstra =>
        assertEquals(d.block.faults, Vector.empty)
        assertEquals(d.block.transactions, recorded._1.transactions)
        assertEquals(d.block.bodyHash, Blake2b.hash256.hash(recorded._2))
        assertEquals(d.block.bodySize, recorded._2.size.toLong)
      case other => fail(s"not a Dijkstra block: $other")
    assertEquals(withTxs.header.leios.blockBodyContainsLeiosCert, false)
    assertEquals(withTxs.header.leios.announcement, None)
    assertEquals(
      Block.decode(withTxs.raw, geometry.byron.epochSlots).map(_.era),
      Right(Era.Dijkstra)
    )
  }

  // --- a ranking block that announces an endorser block ---------------------------------------

  test("the endorser block's capacity is the testnet's, from Dijkstra's parameters") {
    // the closure part is the pool's reservation; the references are 308,000 less the 5 bytes
    // of the widest head
    assertEquals(
      Capacity.endorserBlockCapacity(params),
      Right(
        EbMeasure(
          TxMeasure(2000000L, BigInt(2160000000L), BigInt(600000000000L), 12000000L),
          307995L
        )
      )
    )
  }

  test("a ranking block announcing an endorser block passes Praos2's header rules and the checks") {
    val endorsing =
      BlockBuilder.Endorsing(
        led._1,
        ProtVerParity.Even,
        NonEmptyVector.fromVectorUnsafe(recordedTxs)
      )
    val body = BlockBuilder.Assembly.dijkstra(Vector.empty, None, Some(endorsing))
    val announcing = forge(body)
    assertEquals(announcing.header.leios.announcement, Some(endorsing.forged.announcement))
    assertEquals(endorsing.forged.point.slot.toBigInt, announcing.slot)
    val start = rules.at(injected) match
      case s: NodeHeaderState.Shelley => s.copy(previous = Some(tip))
      case other                      => fail(s"not a Shelley header state: $other")
    val header = received(announcing.header.raw)
    rules.validate(injected, Some(tip), start, header) match
      case Outcome.Valid(_) => ()
      case other            => fail(s"slot ${announcing.slot}: $other")
    val decoded = BlockTransport.check(header, announcing.raw).fold(e => fail(e.message), identity)
    decoded match
      case d: Block.Dijkstra => assertEquals(d.block.faults, Vector.empty)
      case other             => fail(s"not a Dijkstra block: $other")
    // what the forge hands on: the header announcing the block it forged, as a peer would read
    // the announcement from it (`mkForgedAnnouncingHeader` and `mkAnnouncingHeader` agree)
    val handed = Forger.announced(decoded, body).getOrElse(fail("no endorser block handed on"))
    assertEquals(handed.forged, endorsing.forged)
    assertEquals(Some(handed.announcing), AnnouncingHeader.of(announcing.header))
    assertEquals(handed.announcing.fields.point, endorsing.forged.point)
    // a block with no endorser block hands none on
    assertEquals(Forger.announced(decoded, BlockBuilder.Assembly.minimalDijkstra), None)
  }
