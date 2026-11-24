// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.data.NonEmptyVector
import cats.effect.{Deferred, IO, Ref}
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import io.circe.parser
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.HeaderRules.Outcome
import xyz.kd5ujc.scarab.consensus.leios.{CertifyDecision, LeiosCertification, TipCommittee}
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorState, MirrorStep}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.bls.{Certificate, RbHash, SeatId, Vote}
import xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.{key, right}
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover
import xyz.kd5ujc.scarab.ledger.{
  Block,
  BlockTransport,
  ForgeError,
  ForgeProtocolProblem,
  Header,
  LeiosBlock,
  LeiosHeader,
  NodeError,
  StorageError,
  TestLedger,
  VrfCert
}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTx
import xyz.kd5ujc.scarab.ledger.rules.{EraLedger, Globals}
import xyz.kd5ujc.scarab.ledger.state.{
  CompactCoin,
  Dijkstra,
  KeyHash,
  LeiosCommittee,
  LeiosSeat,
  NewEpochState,
  ShelleyBasedEra,
  SnapShots,
  StakePool,
  VRFVerKeyHash,
  Word64
}
import xyz.kd5ujc.scarab.node.{
  Fatal,
  LeiosCertifier,
  LeiosVotes,
  LeiosVotesDefect,
  MirrorDijkstra,
  MirrorRules,
  NodeHeaderRules,
  NodeHeaderState,
  NodeSetup
}
import xyz.kd5ujc.scarab.node.assertions.Assertions
import xyz.kd5ujc.scarab.node.mempool.{
  Capacity,
  DijkstraLedger,
  Mempool,
  MempoolEnv,
  MempoolEvents,
  PoolLedger,
  Source
}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.plutus.PlutusBackend
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, Era, HfcHeader, NtNHeader, ReferenceBuild}

/** A Dijkstra ranking block that certifies, forged as the forger forges it (`decideLeiosCertify`
  * and `partitionMempool`, ouroboros-consensus 2416493b `NodeKernel/Forge.hs:324-396, 760-869`;
  * [[DijkstraForge]]), on the devnet's chain (`fixtures/dijkstra-devnet`) after its block 36, and
  * checked as a peer's block is checked.
  *
  * The state is changed for the test in three places, each a throwaway value: the set snapshot's
  * stake is all a test pool's, whose keys are made here, so that it leads; the set snapshot's
  * committee is four seats of throwaway BLS keys; and the Leios periods are 100 ms each, so the
  * certification gap is 5 slots of the devnet's 0.1 s, inside the forecast range of its 75. The
  * test pool forges a block that announces an endorser block of the devnet's one Dijkstra
  * transaction (that of its block 37), the block is applied by the node's rules, the committee's
  * four votes for it are added to a vote state, and the block after it, `gap` slots later,
  * certifies. The pool holds the same transaction, so the certified closure spends its inputs.
  */
class DijkstraCertifySuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  private given Assertions[IO] = Assertions.noop[IO]
  private given Raise[IO, StorageError] with
    def functor = cats.Functor[IO]
    def raise[E <: StorageError, A](error: E): IO[A] = IO(fail(error.message))
  private given Raise[IO, Fatal] with
    def functor = cats.Functor[IO]
    def raise[E <: Fatal, A](error: E): IO[A] = IO(fail(error.message))
  private given Raise[IO, LeiosVotesDefect] with
    def functor = cats.Functor[IO]
    def raise[E <: LeiosVotesDefect, A](error: E): IO[A] = IO(fail(error.toString))

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  private val devnet = Path.of("fixtures", "dijkstra-devnet")
  private val build = ReferenceBuild.Leios

  private lazy val setup: NodeSetup =
    NodeSetup.fromDirectory(build)(devnet.resolve("genesis")).fold(e => fail(e.message), identity)

  private def geometry = setup.praos.geometry
  private def transition = setup.praos.forks.transition(geometry.k)
  private def globals = Globals(setup.globals, geometry)

  /** The devnet chain up to its block 36, and its block 37, which holds the Dijkstra transaction.
    */
  private lazy val chain: Vector[(Header, Bytes)] =
    Files
      .readAllLines(devnet.resolve("blocks.tsv"))
      .asScala
      .toVector
      .drop(1)
      .filter(_.nonEmpty)
      .map(_.split('\t'))
      .take(38)
      .map { r =>
        val raw = hex(r(4))
        Block.decode(raw, geometry.byron.epochSlots).fold(e => fail(e.message), _.header) -> raw
      }

  private def refOf(h: Header): BlockRef = BlockRef(h.slot, h.hash, h.blockNo)

  private lazy val tip36: BlockRef = refOf(chain(36)._1)

  /** The state after block 36. */
  private lazy val state36: IO[MirrorState] =
    val rules = new MirrorRules[IO](
      setup.praos,
      setup.globals,
      setup.byronRules,
      setup.handOver,
      setup.byronHistory,
      PlutusBackend.default
    )
    chain.take(37).foldLeftM(setup.mirrorStart) { case (state, (header, raw)) =>
      Handle
        .allow[NodeError](rules(state, header, raw))
        .rescue(e => IO(fail(s"block ${header.blockNo}: ${e.message}")))
    }

  /** Block 37's transaction, as a block holds it, and as the wallet submitted it. */
  private lazy val tx: DijkstraTx =
    LeiosBlock.decode(chain(37)._2).fold(e => fail(e.message), _.transactions) match
      case Vector(t) => t
      case other     => fail(s"not one transaction: ${other.size}")
  private lazy val submitted: Bytes =
    val json = parser.parse(Files.readString(devnet.resolve("transaction.json")))
    hex(json.flatMap(_.hcursor.get[String]("cborHex")).fold(e => fail(e.getMessage), identity))

  // --- the test pool and the committee: throwaway keys, never a real pool's -------------------

  private val cold = TestLedger.key()
  private val kes =
    Sum6KesSigning.generate(Bytes.fromArray(Array.fill(32)(54.toByte))).toOption.get
  private val vrf =
    Draft03Prover.SigningKey.fromSeed(Bytes.fromArray(Array.fill(32)(85.toByte))).toOption.get
  private val poolId = Blake2b.hash224.hash(cold.vkey)

  private def opCert: Header.OpCert =
    val signable = Bytes.fromArray(kes.verificationKey.toArray ++ Array.fill[Byte](16)(0))
    Header.OpCert(kes.verificationKey, 0, 0, cold.sign(signable))

  private val voters = Vector(1, 2, 3, 4).map(key)

  private val periodMillis = BigInt(100)

  private def nesOf(state: MirrorState): NewEpochState[Dijkstra] =
    MirrorStep.nesOf(state).flatMap(MirrorDijkstra.inDijkstra).getOrElse(fail("not in Dijkstra"))

  private def withNes(state: MirrorState)(
      f: NewEpochState[Dijkstra] => NewEpochState[Dijkstra]
  ): MirrorState =
    val ledger = state.ext.ledgerState
    val replaced = ledger.state match
      case EraLedgerState.InShelleyEra(ShelleyBasedEra.SDijkstra, s) =>
        EraLedgerState.InShelleyEra(ShelleyBasedEra.SDijkstra, s.copy(nes = f(s.nes)))
      case other => fail(s"not in Dijkstra: $other")
    state.copy(ext = state.ext.copy(ledgerState = ledger.withState(replaced)))

  /** The state after block 36 with the test pool, the committee and the short Leios periods. */
  private lazy val injected: IO[MirrorState] = state36.map(withNes(_) { nes =>
    val snapshots = nes.es.snapshots
    val set = snapshots.set
    val all = set.pools.toVector
    val (_, first) = all.head
    val merged = first.copy(
      stake = CompactCoin.fromUnsigned(all.map(_._2.stake.toBigInt).sum.toLong),
      stakeRatio = all.map(_._2.stakeRatio).reduce(_ + _),
      numDelegators = Word64.fromUnsigned(all.map(_._2.numDelegators.toBigInt).sum.toLong),
      vrf = VRFVerKeyHash.fromBytes(Blake2b.hash256.hash(vrf.publicKey)).toOption.get
    )
    val id = KeyHash.fromBytes[StakePool](poolId).toOption.get
    val committee =
      LeiosCommittee(voters.map((_, vk) => LeiosSeat(Rational(1, 4), Some(vk.bytes))))
    val layout = snapshots.layout match
      case SnapShots.Layout.Leios(epoch, size, age, _) =>
        SnapShots.Layout.Leios(epoch, size, age, committee)
      case SnapShots.Layout.Node11_1 => fail("not the Leios layout")
    val ls = nes.es.ls
    val g = ls.utxo.gov
    val shortened = xyz.kd5ujc.scarab.ledger.era.GovStateRecord
      .view(g.cgsCurPParams)
      .copy(
        leiosAnnouncementPeriodLength = periodMillis,
        leiosVotePeriodLength = periodMillis,
        leiosDiffusionPeriodLength = periodMillis
      )
    val gov = g.copy(cgsCurPParams =
      xyz.kd5ujc.scarab.ledger.era.GovStateRecord.dijkstra
        .pparams(shortened)
        .fold(e => fail(e.toString), identity)
    )
    nes.copy(es =
      nes.es.copy(
        snapshots = snapshots
          .copy(set = set.copy(pools = set.pools.empty.updated(id, merged)), layout = layout),
        ls = ls.copy(utxo = ls.utxo.copy(gov = gov))
      )
    )
  })

  /** `3 * 100 + 100 + 100` ms over the devnet's 100 ms slots. */
  private val gap = BigInt(5)

  // --- the forger's steps ---------------------------------------------------------------------

  private val protocolVersion = (BigInt(12), BigInt(0))

  private def epoch = geometry.epochOf(tip36.slot + 1)

  private def ledgerAt(state: MirrorState, tip: BlockRef, slot: BigInt): Forger.TipLedger =
    Forger.ledgerAt(state, Some(tip), epoch, slot, build).fold(e => fail(e.message), identity)

  private def inputsOf(state: MirrorState, tip: BlockRef): EpochInputs =
    val view = Forger.leaderView(ledgerAt(state, tip, tip.slot + 1), state.protocol, epoch)
    Forger.inputsOf(geometry, epoch, view, poolId, setup.praos.activeSlotsCoeff, vrf)

  private def led(inputs: EpochInputs, slot: BigInt): Option[VrfCert] =
    Leadership.check(slot, inputs).toOption

  /** The announcing slot `a`: the first after block 36 the test pool leads whose slot `a + gap` it
    * leads too, in the same epoch.
    */
  private lazy val slots: IO[(BigInt, VrfCert, VrfCert)] = injected.map { s =>
    val inputs = inputsOf(s, tip36)
    val last = geometry.firstSlot(epoch + 1) - 1
    ((tip36.slot + 1) to (last - gap))
      .collectFirst(Function.unlift(a => (led(inputs, a), led(inputs, a + gap)).tupled.map((a, _))))
      .map { case (a, (ca, cb)) => (a, ca, cb) }
      .getOrElse(fail("the test pool leads no pair of slots a gap apart"))
  }

  final case class Forged(slot: BigInt, raw: Bytes, header: Header, leios: LeiosHeader)

  private def forge(
      tip: BlockRef,
      slot: BigInt,
      cert: VrfCert,
      body: BlockBuilder.Assembly
  ): Forged =
    val headerBody = body.headerBody(
      tip.blockNo + 1,
      slot,
      Some(tip.hash),
      cold.vkey,
      vrf.publicKey,
      cert,
      opCert,
      protocolVersion
    )
    val raw = body.block(headerBody, kes.sign(headerBody))
    val header = Block.decode(raw, geometry.byron.epochSlots).fold(e => fail(e.message), _.header)
    Forged(slot, raw, header, LeiosBlock.decode(raw).fold(e => fail(e.message), _.header))

  /** The block announcing the endorser block of the transaction, and the state after it. */
  private lazy val announcing: IO[(Forged, BlockBuilder.Endorsing, MirrorState)] =
    (injected, slots).tupled.map { (s, picked) =>
      val (a, cert, _) = picked
      val endorsing = BlockBuilder.Endorsing(a, NonEmptyVector.one(tx))
      val forged =
        forge(tip36, a, cert, BlockBuilder.Assembly.dijkstra(Vector.empty, None, Some(endorsing)))
      MirrorRules.applyBlock(
        s,
        forged.header,
        forged.raw,
        setup.praos,
        setup.globals,
        setup.byronRules,
        setup.handOver,
        setup.byronHistory,
        PlutusBackend.default
      ) match
        case Right(Right(after)) => (forged, endorsing, after)
        case other               => fail(s"the announcing block was not applied: $other")
    }

  private def committeeOf(state: MirrorState): TipCommittee =
    LeiosCertification
      .leiosCommitteeOfTip(nesOf(state))(using ShelleyBasedEra.SDijkstra)
      .fold(e => fail(e.toString), _.getOrElse(fail("no committee")))

  /** The vote state of the announcing state's committee, with the votes of `seats` added. */
  private def votes(state: MirrorState, rb: Bytes, seats: Vector[Int]): IO[LeiosVotes[IO]] =
    val rbHash = RbHash.fromBytes(rb).getOrElse(fail("not a ranking block hash"))
    LeiosVotes.create[IO](IO.pure(Right(Some(committeeOf(state)))), IO.pure(_ => true)).flatTap {
      vs =>
        seats.traverse_ { i =>
          val vote = right(Vote.sign(voters(i)._1, right(SeatId.fromInt(i)), rbHash))
          vs.add(vote)
        }
    }

  private def envOf(state: MirrorState, slot: BigInt): MempoolEnv =
    MempoolEnv(
      setup.globals,
      geometry,
      PlutusBackend.default.evaluator,
      PlutusBackend.default.decoder,
      clock = Some(era => setup.txClock.at(state, era, slot))
    )

  /** The pool of the chain whose tip is `tip`, holding the devnet's transaction. */
  private def pool(state: MirrorState, tip: BlockRef): IO[Mempool[IO]] =
    val env = envOf(state, tip.slot + 1)
    val ledger = PoolLedger.at(state, Some(tip), env, transition) match
      case Right(Right(d: DijkstraLedger)) => d
      case other                           => fail(s"not a Dijkstra ledger: $other")
    Mempool
      .create[IO](Right(ledger), tip.slot, env, None, MempoolEvents.none[IO])
      .flatTap(_.add(submitted, Source.Local).map(added => assert(added.isRight, added)))

  private def ticked(nes: NewEpochState[Dijkstra], slot: BigInt): NewEpochState[Dijkstra] =
    Forger.tickedTo(globals, transition, Forger.TipLedger.InDijkstra(nes), slot) match
      case Right(Forger.TipLedger.InDijkstra(next)) => next
      case other                                    => fail(s"not ticked in Dijkstra: $other")

  /** The parent the block of `slot` is forged on, the state `state` at `tip`, as the forger reads
    * it: what the tip announced from its chain-dependent state ticked to the slot's epoch.
    */
  private def parentOf(
      state: MirrorState,
      tip: BlockRef,
      slot: BigInt
  ): DijkstraParent[MirrorState] =
    val ledger = ledgerAt(state, tip, slot)
    DijkstraParent(
      state,
      Some(tip),
      nesOf(state),
      ticked(nesOf(state), slot),
      LeiosCertification.announcement(Forger.leaderView(ledger, state.protocol, epoch).protocol.dep)
    )

  private def certifier(
      closures: Map[EbHash, Vector[(xyz.kd5ujc.scarab.protocol.LeiosTxHash, Bytes)]],
      vs: LeiosVotes[IO]
  ): LeiosCertifying[IO, MirrorState] =
    LeiosCertifier[IO](
      hash => IO.pure(closures.get(hash)),
      vs.certificate,
      None,
      (state, slot) =>
        MirrorDijkstra.envAt(
          state,
          slot,
          Era.Dijkstra,
          setup.praos,
          setup.globals,
          setup.byronHistory,
          PlutusBackend.default
        )
    )

  private def closureOf(e: BlockBuilder.Endorsing) = Map(
    e.forged.point.hash -> e.forged.closure.toVector
  )

  private def decide(
      parent: DijkstraParent[MirrorState],
      slot: BigInt,
      certifying: LeiosCertifying[IO, MirrorState]
  ): IO[CertifyDecision] =
    DijkstraForge.decide[IO, MirrorState](
      slot,
      BigInt(geometry.slotLengthMillis),
      parent,
      certifying
    )

  private def partitioned(
      state: MirrorState,
      parent: DijkstraParent[MirrorState],
      slot: BigInt,
      decision: CertifyDecision,
      certifying: LeiosCertifying[IO, MirrorState],
      mempool: Mempool[IO]
  ): IO[DijkstraPartition] =
    val params = MirrorEra.params(parent.ticked)
    val ebLimit = Capacity.endorserBlockCapacity(params).fold(e => fail(e.toString), identity)
    DijkstraForge.partition[IO, MirrorState](
      slot,
      parent,
      decision,
      Capacity.dijkstraBlockUnit(params),
      ebLimit,
      DijkstraSnapshots.ofPool[IO](mempool, None, IO.pure(envOf(state, slot))),
      certifying,
      nes => Right(ticked(nes, slot)),
      setup.txClock.at(state, Era.Dijkstra, slot)
    )

  private object MirrorEra:
    def params(nes: NewEpochState[Dijkstra]) = EraLedger.curPParams(nes.es.ls.utxo.gov)

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

  // --- the tests ------------------------------------------------------------------------------

  test("the announcing block is applied, and the state after it announces its endorser block") {
    announcing.map { (forged, endorsing, after) =>
      assertEquals(
        LeiosCertification.announcement(after.protocol.dep),
        Some(endorsing.forged.point)
      )
      assertEquals(endorsing.forged.point.slot.toBigInt, forged.slot)
    }
  }

  test(
    "decideLeiosCertify in the forger: too early a slot short of the gap, at it and after it the block certifies"
  ) {
    for
      a <- announcing
      (forged, endorsing, after) = a
      vs <- votes(after, forged.header.hash, Vector(0, 1, 2, 3))
      cert <- vs.certificate(RbHash.fromBytes(forged.header.hash).get)
      c = certifier(closureOf(endorsing), vs)
      tip = refOf(forged.header)
      point = endorsing.forged.point
      early <- decide(parentOf(after, tip, forged.slot + gap - 1), forged.slot + gap - 1, c)
      at <- decide(parentOf(after, tip, forged.slot + gap), forged.slot + gap, c)
      later <- decide(parentOf(after, tip, forged.slot + gap + 1), forged.slot + gap + 1, c)
    yield
      val certificate = cert.getOrElse(fail("no certificate of four votes"))
      assertEquals(early, CertifyDecision.TooEarly(point, gap))
      assertEquals(at, CertifyDecision.Certify(certificate, point))
      assertEquals(later, CertifyDecision.Certify(certificate, point))
      assertEquals(
        DijkstraForge.decided(forged.slot + gap, at),
        Vector(LeiosForgeTrace.BlockCertified(forged.slot + gap, point))
      )
  }

  test(
    "no certificate, or no closure, or no announcement: the block certifies nothing and takes the pool's transaction"
  ) {
    for
      a <- announcing
      (forged, endorsing, after) = a
      s36 <- injected
      slot = forged.slot + gap
      tip = refOf(forged.header)
      point = endorsing.forged.point
      // three of the four seats: a quarter short of nothing, but under the quorum of 0.75
      fewer <- votes(after, forged.header.hash, Vector(0, 1))
      all <- votes(after, forged.header.hash, Vector(0, 1, 2, 3))
      noCert <- decide(parentOf(after, tip, slot), slot, certifier(closureOf(endorsing), fewer))
      noClosure <- decide(parentOf(after, tip, slot), slot, certifier(Map.empty, all))
      nothing <- decide(parentOf(s36, tip36, slot), slot, certifier(closureOf(endorsing), all))
      mempool <- pool(after, tip)
      split <- partitioned(
        after,
        parentOf(after, tip, slot),
        slot,
        noCert,
        certifier(closureOf(endorsing), fewer),
        mempool
      )
    yield
      assertEquals(noCert, CertifyDecision.NoCertificate(point))
      assertEquals(noClosure, CertifyDecision.ClosureNotDownloaded(point))
      assertEquals(nothing, CertifyDecision.NothingAnnounced)
      assertEquals(
        DijkstraForge.decided(slot, noCert),
        Vector(LeiosForgeTrace.NoCertificate(point))
      )
      assertEquals(
        DijkstraForge.decided(slot, noClosure),
        Vector(LeiosForgeTrace.NotDownloaded(point))
      )
      split.body match
        case BlockBuilder.Assembly.InDijkstra(body, endorsing) =>
          assertEquals(body.leiosCertificate, None)
          assertEquals(body.transactions.map(_.id), Vector(tx.id))
          assertEquals(endorsing, None)
        case other => fail(s"not a Dijkstra body: $other")
  }

  // `tickedProtocolStateLeiosAnnouncement` is `Nothing` once the tick crosses an epoch boundary
  // (`Shelley/Ledger/Leios.hs:341-343`, ouroboros-consensus 2416493b): a block of the next epoch on
  // the announcing block certifies nothing, however long after the announcement
  test("in the next epoch the parent's announcement is cleared by the tick: nothing to certify") {
    for
      a <- announcing
      (forged, endorsing, after) = a
      vs <- votes(after, forged.header.hash, Vector(0, 1, 2, 3))
      tip = refOf(forged.header)
      next = epoch + 1
      slot = geometry.firstSlot(next) + 1
      ledger = ledgerAt(after, tip, forged.slot + 1)
      announced = LeiosCertification.announcement(
        Forger.leaderView(ledger, after.protocol, next).protocol.dep
      )
      parent = parentOf(after, tip, forged.slot + gap).copy(announced = announced)
      decision <- decide(parent, slot, certifier(closureOf(endorsing), vs))
    yield
      assertEquals(
        LeiosCertification.announcement(after.protocol.dep),
        Some(endorsing.forged.point)
      )
      assertEquals(announced, None)
      assertEquals(decision, CertifyDecision.NothingAnnounced)
  }

  /** The certifying block forged at `a + gap` from the committee's four votes, its partition, and
    * the certificate.
    */
  private lazy val certifying
      : IO[(Forged, DijkstraPartition, Certificate, MirrorState, Forged, BlockBuilder.Endorsing)] =
    for
      a <- announcing
      picked <- slots
      (forged, endorsing, after) = a
      (_, _, certB) = picked
      vs <- votes(after, forged.header.hash, Vector(0, 1, 2, 3))
      c = certifier(closureOf(endorsing), vs)
      slot = forged.slot + gap
      tip = refOf(forged.header)
      parent = parentOf(after, tip, slot)
      decision <- decide(parent, slot, c)
      mempool <- pool(after, tip)
      split <- partitioned(after, parent, slot, decision, c, mempool)
      cert = decision match
        case CertifyDecision.Certify(certificate, _) => certificate
        case other                                   => fail(s"does not certify: $other")
    yield (forge(tip, slot, certB, split.body), split, cert, after, forged, endorsing)

  test(
    "a certifying block from the committee's votes passes the Leios checks, Praos2's header rules, BlockFetch's and block application"
  ) {
    certifying.map { (certifyingBlock, split, cert, after, announcingBlock, endorsing) =>
      val tip = refOf(announcingBlock.header)
      // the body: the certificate and no transaction; the header's flag says so
      split.body match
        case BlockBuilder.Assembly.InDijkstra(body, _) =>
          assertEquals(body.leiosCertificate, Some(cert))
          assertEquals(body.transactions, Vector.empty)
        case other => fail(s"not a Dijkstra body: $other")
      assert(certifyingBlock.leios.leios.blockBodyContainsLeiosCert)
      // the Leios checks block application runs, against the parent's chain-dependent state
      LeiosCertification.check(
        after.protocol.dep,
        Some(tip.hash),
        Some(committeeOf(after)),
        cert
      ) match
        case Right(Right(certified)) => assertEquals(certified.point, endorsing.forged.point)
        case other                   => fail(s"the certificate does not hold: $other")
      // Praos2's header rules, as a peer's ChainSync runs them
      val start = rules.at(after) match
        case s: NodeHeaderState.Shelley => s.copy(previous = Some(tip))
        case other                      => fail(s"not a Shelley header state: $other")
      rules.validate(after, Some(tip), start, received(certifyingBlock.leios.raw)) match
        case Outcome.Valid(_) => ()
        case other            => fail(s"slot ${certifyingBlock.slot}: $other")
      // BlockFetch's check of the block against its header
      BlockTransport.check(certifyingBlock.header, certifyingBlock.raw) match
        case Right(d: Block.Dijkstra) => assertEquals(d.block.faults, Vector.empty)
        case other                    => fail(s"not a well-formed Dijkstra block: $other")
      // the node's own application of the block, with the closure the store holds
      val certification = MirrorDijkstra.certify[cats.Id](
        certifyingBlock.slot,
        after.protocol.dep,
        Some(tip.hash),
        Right(Some(committeeOf(after))),
        cert,
        closureOf(endorsing).get
      )
      MirrorRules.applyBlock(
        after,
        certifyingBlock.header,
        certifyingBlock.raw,
        setup.praos,
        setup.globals,
        setup.byronRules,
        setup.handOver,
        setup.byronHistory,
        PlutusBackend.default,
        dijkstra = MirrorDijkstra.Read(None, Some(certification))
      ) match
        case Right(Right(next)) =>
          // the closure's transaction is applied: its outputs are in the UTxO set
          val utxo = nesOf(next).es.ls.utxo.utxo.utxo
          assert(utxo.contains(xyz.kd5ujc.scarab.ledger.TxIn(tx.id, 0)))
          assert(
            !nesOf(after).es.ls.utxo.utxo.utxo.contains(xyz.kd5ujc.scarab.ledger.TxIn(tx.id, 0))
          )
        case other => fail(s"the certifying block was not applied: $other")
    }
  }

  test(
    "the endorser block a certifying block announces is formed from the pool's snapshot after the closure"
  ) {
    for
      c <- certifying
      (_, split, _, after, announcingBlock, endorsing) = c
      tip = refOf(announcingBlock.header)
      slot = announcingBlock.slot + gap
      parent = parentOf(after, tip, slot)
      vs <- votes(after, announcingBlock.header.hash, Vector(0, 1, 2, 3))
      real = certifier(closureOf(endorsing), vs)
      // the same decision with a closure that changes nothing: the pool's transaction applies then
      unchanged = new LeiosCertifying[IO, MirrorState]:
        def closureHeld(eb: EbHash)(using Raise[IO, StorageError]) = real.closureHeld(eb)
        def certificate(rbHash: RbHash) = real.certificate(rbHash)
        def closed(
            chain: MirrorState,
            t: Option[BlockRef],
            p: NewEpochState[Dijkstra],
            point: EbPoint,
            s: BigInt
        )(using Raise[IO, StorageError]) =
          IO.pure(Right(p))
      mempool <- pool(after, tip)
      untouched <- partitioned(after, parent, slot, split.decision, unchanged, mempool)
    yield
      // after the certified closure, the transaction's inputs are spent: no endorser block
      split.body match
        case BlockBuilder.Assembly.InDijkstra(body, e) =>
          assertEquals(e, None)
          assertEquals(body.transactions, Vector.empty)
        case other => fail(s"not a Dijkstra body: $other")
      // on the parent without it, the ranking block still takes none, and the endorser block
      // takes the transaction
      untouched.body match
        case BlockBuilder.Assembly.InDijkstra(body, Some(e)) =>
          assertEquals(body.transactions, Vector.empty)
          assert(body.leiosCertificate.isDefined)
          assertEquals(e.transactions.toVector.map(_.id), Vector(tx.id))
          val rb = Bytes.fromArray(Array.fill[Byte](32)(7))
          val eb = e.forged
          assertEquals(
            DijkstraForge.forged(slot, rb, untouched.body),
            Vector(
              LeiosForgeTrace.BlockForged(
                slot,
                eb.point,
                1,
                eb.body.encodedSize,
                eb.closure.head._2.size.toLong
              ),
              LeiosForgeTrace.BlockAnnounced(rb, eb.point),
              LeiosForgeTrace.CertifiedAndAnnounced(slot, rb)
            )
          )
        case other => fail(s"no endorser block: $other")
  }

  // the memo of the certified closure's application (#1380)

  /** A certifier whose closure application gives `result` of its run's number, from 1, counted. */
  private def counted(
      runs: Ref[IO, Int],
      result: Int => Raise[IO, StorageError] ?=> IO[Either[NodeError, NewEpochState[Dijkstra]]]
  ): LeiosCertifying[IO, MirrorState] = new LeiosCertifying[IO, MirrorState]:
    def closureHeld(eb: EbHash)(using Raise[IO, StorageError]) = IO.pure(true)
    def certificate(rbHash: RbHash) = IO.pure(None)
    def closed(
        chain: MirrorState,
        t: Option[BlockRef],
        p: NewEpochState[Dijkstra],
        point: EbPoint,
        s: BigInt
    )(using Raise[IO, StorageError]) =
      runs.updateAndGet(_ + 1).flatMap(n => result(n))

  private def ebHash(b: Int): EbHash =
    EbHash.fromBytes(Bytes.fromArray(Array.fill[Byte](32)(b.toByte)))

  private def applied(
      memo: LeiosCertifying[IO, MirrorState],
      state: MirrorState,
      tip: Option[BlockRef],
      eb: EbHash
  )(using Raise[IO, StorageError]): IO[Either[NodeError, NewEpochState[Dijkstra]]] =
    memo.closed(
      state,
      tip,
      nesOf(state),
      EbPoint(xyz.kd5ujc.scarab.protocol.LeiosWord.Word64.clamped(BigInt(1)), eb),
      BigInt(1)
    )

  private val refused: NodeError = ForgeError.UnsupportedProtocol(ForgeProtocolProblem.LeftConway)

  test(
    "the memoised closure is the one the certifier applies, at the forging slot and later (#1380)"
  ) {
    for
      c <- certifying
      (_, split, _, after, announcingBlock, endorsing) = c
      tip = refOf(announcingBlock.header)
      slot = announcingBlock.slot + gap
      parent = parentOf(after, tip, slot)
      point = split.decision match
        case CertifyDecision.Certify(_, p) => p
        case other                         => fail(s"not certified: $other")
      vs <- votes(after, announcingBlock.header.hash, Vector(0, 1, 2, 3))
      real = certifier(closureOf(endorsing), vs)
      memo <- LeiosCertifying.memoized(real)
      fresh <- real.closed(parent.chain, parent.tip, parent.unticked, point, slot)
      later <- real.closed(parent.chain, parent.tip, parent.unticked, point, slot + 3)
      // warmed in the slot after the tip, as the forger warms it, then asked for when it forges
      warmed <- memo.closed(parent.chain, parent.tip, parent.unticked, point, tip.slot + 1)
      forged <- memo.closed(parent.chain, parent.tip, parent.unticked, point, slot)
    yield
      assert(fresh.isRight, fresh.toString)
      assertEquals(later, fresh)
      assertEquals(warmed, fresh)
      assertEquals(forged, fresh)
  }

  test(
    "the memo applies a closure once per tip and endorser block, and keeps only results (#1380)"
  ) {
    for
      state <- state36
      nes = nesOf(state)
      other = Some(refOf(chain(35)._1))
      runs <- Ref.of[IO, Int](0)
      failing <- Ref.of[IO, Boolean](false)
      memo <- LeiosCertifying.memoized(
        counted(runs, _ => failing.get.map(f => if f then Left(refused) else Right(nes)))
      )
      a1 <- applied(memo, state, Some(tip36), ebHash(1))
      a2 <- applied(memo, state, Some(tip36), ebHash(1))
      once <- runs.get
      // another endorser block or another tip is another application
      _ <- applied(memo, state, Some(tip36), ebHash(2))
      _ <- applied(memo, state, other, ebHash(1))
      three <- runs.get
      // two are kept: the first, the oldest, is applied again
      _ <- applied(memo, state, Some(tip36), ebHash(1))
      four <- runs.get
      // a failure the application reached is given, and not kept
      _ <- failing.set(true)
      f1 <- applied(memo, state, other, ebHash(3))
      _ <- failing.set(false)
      f2 <- applied(memo, state, other, ebHash(3))
      six <- runs.get
      // nor is a raise: the next call applies the closure again
      raising <- Ref.of[IO, Int](0)
      raised <- LeiosCertifying.memoized(
        counted(
          raising,
          n =>
            if n == 1 then
              summon[Raise[IO, StorageError]].raise(StorageError.DatabaseInUse("leios"))
            else IO.pure(Right(nes))
        )
      )
      r1 <- Handle
        .allow[StorageError](applied(raised, state, Some(tip36), ebHash(1)).map(Right(_)))
        .rescue(e => IO.pure(Left(e)))
      r2 <- applied(raised, state, Some(tip36), ebHash(1))
      twice <- raising.get
    yield
      assertEquals((a1, a2, once), (Right(nes), Right(nes), 1))
      assertEquals((three, four), (3, 4))
      assertEquals((f1, f2, six), (Left(refused), Right(nes), 6))
      assertEquals((r1, r2, twice), (Left(StorageError.DatabaseInUse("leios")), Right(nes), 2))
  }

  test(
    "a call while the closure is applied waits for it, and applies it itself once cancelled (#1380)"
  ) {
    for
      state <- state36
      nes = nesOf(state)
      runs <- Ref.of[IO, Int](0)
      entered <- Deferred[IO, Unit]
      gate <- Deferred[IO, Unit]
      // the first run waits at the gate once it has begun
      underlying = counted(
        runs,
        n => (entered.complete(()) *> gate.get).whenA(n == 1).as(Right(nes))
      )
      memo <- LeiosCertifying.memoized(underlying)
      first <- applied(memo, state, Some(tip36), ebHash(1)).start
      _ <- entered.get
      second <- applied(memo, state, Some(tip36), ebHash(1)).start
      _ <- gate.complete(())
      a <- first.joinWithNever
      b <- second.joinWithNever
      waited <- runs.get
      // cancelled while it applies, the next call applies the closure itself
      cancelledRuns <- Ref.of[IO, Int](0)
      began <- Deferred[IO, Unit]
      held = counted(
        cancelledRuns,
        n => (began.complete(()) *> IO.never[Unit]).whenA(n == 1).as(Right(nes))
      )
      cancelling <- LeiosCertifying.memoized(held)
      stuck <- applied(cancelling, state, Some(tip36), ebHash(1)).start
      _ <- began.get
      _ <- stuck.cancel
      after <- applied(cancelling, state, Some(tip36), ebHash(1))
      reapplied <- cancelledRuns.get
    yield
      assertEquals((a, b, waited), (Right(nes), Right(nes), 1))
      assertEquals((after, reapplied), (Right(nes), 2))
  }

  test(
    "a call that waited never gets the application's refusal: it asks again and applies it (#1380)"
  ) {
    for
      state <- state36
      nes = nesOf(state)
      runs <- Ref.of[IO, Int](0)
      entered <- Deferred[IO, Unit]
      gate <- Deferred[IO, Unit]
      // the first run is refused once it is let go, the next applies
      memo <- LeiosCertifying.memoized(
        counted(
          runs,
          n =>
            if n == 1 then (entered.complete(()) *> gate.get).as(Left(refused))
            else IO.pure(Right(nes))
        )
      )
      owner <- applied(memo, state, Some(tip36), ebHash(1)).start
      _ <- entered.get
      waiter <- applied(memo, state, Some(tip36), ebHash(1)).start
      _ <- gate.complete(())
      own <- owner.joinWithNever
      waited <- waiter.joinWithNever
      n <- runs.get
    yield assertEquals((own, waited, n), (Left(refused), Right(nes), 2))
  }

  test(
    "an application still running is never dropped by the bound, however many are reached (#1380)"
  ) {
    for
      state <- state36
      nes = nesOf(state)
      runs <- Ref.of[IO, Int](0)
      entered <- Deferred[IO, Unit]
      gate <- Deferred[IO, Unit]
      memo <- LeiosCertifying.memoized(
        counted(runs, n => (entered.complete(()) *> gate.get).whenA(n == 1).as(Right(nes)))
      )
      held <- applied(memo, state, Some(tip36), ebHash(1)).start
      _ <- entered.get
      // three others reached, one more than the bound keeps
      _ <- List(2, 3, 4).traverse_(b => applied(memo, state, Some(tip36), ebHash(b)))
      again <- applied(memo, state, Some(tip36), ebHash(1)).start
      _ <- gate.complete(())
      first <- held.joinWithNever
      second <- again.joinWithNever
      whileHeld <- runs.get
      // the oldest reached one was dropped, and is applied again
      _ <- applied(memo, state, Some(tip36), ebHash(2))
      dropped <- runs.get
    yield
      assertEquals((first, second, whileHeld), (Right(nes), Right(nes), 4))
      assertEquals(dropped, 5)
  }

  test("after a cancelled application the calls that waited apply the closure once (#1380)") {
    for
      state <- state36
      nes = nesOf(state)
      runs <- Ref.of[IO, Int](0)
      began <- Deferred[IO, Unit]
      memo <- LeiosCertifying.memoized(
        counted(runs, n => (began.complete(()) *> IO.never[Unit]).whenA(n == 1).as(Right(nes)))
      )
      stuck <- applied(memo, state, Some(tip36), ebHash(1)).start
      _ <- began.get
      waiters <- List.fill(3)(applied(memo, state, Some(tip36), ebHash(1)).start).sequence
      _ <- stuck.cancel
      results <- waiters.traverse(_.joinWithNever)
      n <- runs.get
    yield assertEquals((results, n), (List.fill(3)(Right(nes)), 2))
  }
