// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

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
  LeiosHeader,
  Nonce,
  OCertError,
  Praos2Error,
  TestLedger
}
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
import xyz.kd5ujc.scarab.node.{NodeHeaderRules, NodeHeaderState, NodeSetup}
import xyz.kd5ujc.scarab.node.read.InEra
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{Era, HfcHeader, NtNHeader, ReferenceBuild}
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellLedgerFile
import xyz.kd5ujc.scarab.protocol.StateLayout

/** A Dijkstra block forged as the forger forges it under `--reference-build leios-b004fe0c`, on the
  * public Leios testnet's ledger state at slot 2,168,639 (`fixtures/leios/state/testnet-2168639`,
  * block 97,273), and checked as a peer's block is checked. The pool is a test pool of throwaway
  * keys: its stake is that of the testnet's ten largest pools, moved to it in the state's set
  * snapshot, the source of both the forger's leader check and the header rules' ledger view; the
  * total and every other pool are left as they are.
  *
  * The steps are the forger's own ([[Forger.ledgerAt]], [[Forger.leaderView]], [[Forger.inputsOf]],
  * [[Leadership.check]], [[Forger.tickedTo]], [[BlockBuilder.Assembly]]); only the KES signature is
  * made here, by the same `sign` the forger calls.
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
  private val build = ReferenceBuild.LeiosB004fe0c
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
  private lazy val forged: Forged =
    val epoch = geometry.epochOf(tip.slot + 1)
    val ledger =
      Forger
        .ledgerAt(injected, Some(tip), epoch, tip.slot + 1, build)
        .fold(e => fail(e.message), identity)
    val view = Forger.leaderView(ledger, injected.protocol, epoch)
    val inputs =
      Forger.inputsOf(geometry, epoch, view, poolId, setup.praos.activeSlotsCoeff, vrf)
    val window = (tip.slot + 1) until (tip.slot + 1 + geometry.stabilityWindow.toBigInt)
    val (slot, cert) = window.iterator
      .filter(s => geometry.epochOf(s) == epoch)
      .map(s => s -> Leadership.check(s, inputs))
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
    ticked match
      case Forger.TipLedger.InDijkstra(_) => ()
      case other                          => fail(s"ticked out of Dijkstra: $other")
    val body = BlockBuilder.Assembly.minimalDijkstra
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
    val raw = body.block(headerBody, kes.sign(headerBody))
    val header = LeiosBlock.decode(raw).fold(e => fail(e.message), _.header)
    Forged(slot, raw, header)

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
    assertEquals(ours.body.encode.hex, "8380f6f6")
    assertEquals(ours.body.encode, theirs.body.encode)
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
    }
  }
