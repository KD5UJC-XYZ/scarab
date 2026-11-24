// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.consensus.{BlockNoVerdict, Praos2}
import xyz.kd5ujc.scarab.consensus.leios.{LeiosCertification, TipCommittee}
import xyz.kd5ujc.scarab.consensus.state.{
  ChainDepState,
  MirrorState,
  MirrorStep,
  ProtocolState,
  RestoredParams,
  StateMirror
}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.bls.{Certificate, MinSig, RbHash, SeatId}
import xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.{key, right}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  BlockFailure,
  ChainError,
  DijkstraBlockDefect,
  EbAnnouncement,
  Header,
  LeiosBlock,
  LeiosExtValidationError,
  NodeError,
  ProtocolParams,
  TxIn
}
import xyz.kd5ujc.scarab.ledger.era.Proposals as EraProposals
import xyz.kd5ujc.scarab.ledger.era.rules.{DijkstraClosure, LeiosClosureDefect}
import xyz.kd5ujc.scarab.ledger.rules.EraNes
import xyz.kd5ujc.scarab.ledger.state.{BlockIssuer, KeyHash, LeiosCommittee, LeiosSeat}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{DijkstraNotYet, EbHash, Era, LeiosTxHash, ReferenceBuild}
import xyz.kd5ujc.scarab.storage.MemoryUtxoStore

/** Dijkstra blocks applied by the node's block rules (`MirrorRules`, [[MirrorDijkstra]]) in the
  * Leios build, as `Forker.applyBlock` applies them (ouroboros-consensus 2416493b
  * `Storage/LedgerDB/Forker.hs:624-687`):
  *   - the captured blocks with a reachable state, from it: the devnet chain from Origin (33 Conway
  *     blocks, then the 8 Dijkstra blocks, one with a transaction) and the public testnet's first
  *     two blocks from its genesis, each adopted as the reference adopted it;
  *   - a release build refuses a Dijkstra block as before ([[BlockFailure.NotYet]]);
  *   - a crafted certifying block on the devnet's states: a closure the store lacks stops the node
  *     ([[DijkstraBlockDefect.Closure]]); an invalid certificate, or one with no announcement, is
  *     the block's refusal; the closure is applied to the parent unticked, and makes the state the
  *     block that held its transaction made.
  *
  * Incomplete (CNF-2): the testnet's other captured blocks (8,562, the certifying 64,850 and the
  * announcing 64,853) and the certifying 97,276 have no reachable recorded state (only the state at
  * 97,273 is recorded, and 97,274 and 97,275 only as headers), so they are not applied; no
  * recording holds a certifying block with its endorser block's closure and the state before it, so
  * the closure's application is checked on a crafted block with a recorded transaction.
  */
class MirrorDijkstraSuite extends CatsEffectSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  private val devnet = Path.of("fixtures", "dijkstra-devnet")

  private def setupOf(build: ReferenceBuild): NodeSetup =
    NodeSetup.fromDirectory(build)(devnet.resolve("genesis")).fold(e => fail(e.message), identity)

  private lazy val leiosSetup = setupOf(ReferenceBuild.Leios)

  /** The devnet chain from Origin: 33 Conway blocks, then 8 Dijkstra blocks. */
  private lazy val devnetChain: Vector[(Header, Bytes)] =
    Files
      .readAllLines(devnet.resolve("blocks.tsv"))
      .asScala
      .toVector
      .drop(1)
      .filter(_.nonEmpty)
      .map(_.split('\t'))
      .map { r =>
        val raw = hex(r(4))
        Block
          .decode(raw, leiosSetup.geometry.byron.epochSlots)
          .fold(e => fail(e.message), _.header) -> raw
      }

  /** The node's block rules, with a store behind the UTxO set or, `stored` off, the set in the
    * state.
    */
  private def rulesOf(setup: NodeSetup, stored: Boolean = true) =
    MemoryUtxoStore[IO].map(store =>
      new MirrorRules[IO](
        setup.praos,
        setup.globals,
        setup.byronRules,
        setup.handOver,
        setup.byronHistory,
        xyz.kd5ujc.scarab.plutus.PlutusBackend.default,
        utxoStore = Option.when(stored)(store)
      )
    )

  /** Each block applied in turn from `start`: the states, or the first error and its index. */
  private def applyAll(
      rules: MirrorRules[IO],
      start: MirrorState,
      blocks: Vector[(Header, Bytes)]
  ): IO[Either[(Int, NodeError), Vector[MirrorState]]] =
    blocks.zipWithIndex
      .foldLeftM[IO, Either[(Int, NodeError), Vector[MirrorState]]](Right(Vector(start))) {
        case (Left(e), _) => IO.pure(Left(e))
        case (Right(states), ((header, raw), i)) =>
          Handle
            .allow[NodeError](rules(states.last, header, raw).map(s => Right(states :+ s)))
            .rescue(e => IO.pure(Left((i, e))))
      }

  test(
    "the devnet chain from Origin, Conway into Dijkstra, is the node's chain under the Leios build"
  ) {
    for
      rules <- rulesOf(leiosSetup)
      applied <- applyAll(rules, leiosSetup.mirrorStart, devnetChain)
    yield
      val states = applied.fold((i, e) => fail(s"block $i: ${e.message}"), identity).tail
      assertEquals(states.size, 41)
      assertEquals(states.map(_.era).count(_ == Era.Dijkstra), 8)
      assertEquals(states.last.era, Era.Dijkstra)
      assertEquals(
        StateMirror.anchorOf(states.last.ext.headerState.tip).map(_.blockNo),
        Some(BigInt(40))
      )
      // block 33 takes the chain into Dijkstra, and only it
      val crossings = states.indices
        .drop(1)
        .filter(i => MirrorRules.DijkstraCrossing.of(states(i - 1), states(i)).isDefined)
      assertEquals(crossings, IndexedSeq(33))
  }
  test(
    "the testnet's first two blocks from its genesis are the node's chain under the Leios build"
  ) {
    val testnet = Path.of("fixtures", "leios", "testnet")
    val setup = NodeSetup
      .fromConfigFile(ReferenceBuild.Leios)(testnet.resolve("config/config.json"))
      .fold(e => fail(e.message), identity)
    val blocks = Files
      .readAllLines(testnet.resolve("blocks-rc-2026w44.tsv"))
      .asScala
      .toVector
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .map(_.split('\t'))
      .take(2)
      .map { r =>
        val raw = hex(r(5))
        Block
          .decode(raw, setup.geometry.byron.epochSlots)
          .fold(e => fail(e.message), _.header) -> raw
      }
    for
      rules <- rulesOf(setup)
      applied <- applyAll(rules, setup.mirrorStart, blocks)
    yield
      val states = applied.fold((i, e) => fail(s"block $i: ${e.message}"), identity).tail
      assertEquals(states.map(_.era), Vector(Era.Dijkstra, Era.Dijkstra))
      assertEquals(
        StateMirror.anchorOf(states.last.ext.headerState.tip).map(_.blockNo),
        Some(BigInt(1))
      )
  }

  // a restored Dijkstra state keeps its snapshot sidecar's records through the blocks it applies,
  // so the sidecar written at a later anchor still holds the fields of the eras before it (#826)
  test("the sidecar's records a restored Dijkstra state keeps survive the next block applied") {
    for
      rules <- rulesOf(leiosSetup, stored = false)
      applied <- applyAll(rules, leiosSetup.mirrorStart, devnetChain.take(35))
      states = applied.fold((i, e) => fail(s"block $i: ${e.message}"), identity)
      before = states.last
      _ = assertEquals(before.era, Era.Dijkstra)
      kept = Some(
        RestoredParams(
          ProtocolParams.empty.copy(d = Rational.one),
          Some(ProtocolParams.empty.copy(d = Rational.one))
        )
      )
      next <- applyAll(rules, before.copy(restored = kept), devnetChain.slice(35, 36))
    yield
      val after = next.fold((i, e) => fail(s"block $i: ${e.message}"), identity).last
      assertEquals(after.era, Era.Dijkstra)
      assertEquals(after.restored, kept)
  }

  test("a release build refuses a Dijkstra block as before (NotYet), from the state it follows") {
    devnetStates.map { states =>
      val (header, raw) = devnetChain(33)
      Vector(ReferenceBuild.Release11_1, ReferenceBuild.Release11_2).foreach { build =>
        assertEquals(
          MirrorRules.applyBlock(
            states(33),
            header,
            raw,
            leiosSetup.praos,
            leiosSetup.globals.copy(referenceBuild = build),
            leiosSetup.byronRules,
            leiosSetup.handOver,
            leiosSetup.byronHistory,
            xyz.kd5ujc.scarab.plutus.PlutusBackend.default
          ),
          Right(Left(BlockFailure.NotYet(DijkstraNotYet.Rules))),
          build.name
        )
      }
      // the same block and state under the Leios build: applied
      assert(
        MirrorRules
          .applyBlock(
            states(33),
            header,
            raw,
            leiosSetup.praos,
            leiosSetup.globals,
            leiosSetup.byronRules,
            leiosSetup.handOver,
            leiosSetup.byronHistory,
            xyz.kd5ujc.scarab.plutus.PlutusBackend.default
          )
          .exists(_.isRight)
      )
    }
  }

  test("follow runs the Leios build and the 11.1 line, and still refuses 11.2") {
    assert(ReferenceBuildFlag.follows(ReferenceBuild.Leios).isRight)
    assert(ReferenceBuildFlag.follows(ReferenceBuild.Release11_1).isRight)
    assert(ReferenceBuildFlag.follows(ReferenceBuild.Release11_2).isLeft)
  }

  // ---- a crafted certifying ranking block on the devnet chain's last state

  private def node(raw: Bytes): Node = Cbor.decode(raw).fold(e => fail(e.toString), identity)
  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")
  private def array(parts: Vector[Bytes]): Bytes =
    val n = parts.size
    parts.foldLeft(Bytes.fromArray(Array((0x80 + n).toByte)))(_ ++ _)
  private def encoded(v: Value): Bytes = Cbor.encode(v).fold(e => fail(e.toString), identity)

  /** The states after each block of the devnet chain. */
  private lazy val devnetStates: IO[Vector[MirrorState]] =
    rulesOf(leiosSetup, stored = false).flatMap(rules =>
      applyAll(rules, leiosSetup.mirrorStart, devnetChain)
        .map(_.fold((i, e) => fail(s"block $i: ${e.message}"), identity))
    )

  /** Block 40 of the devnet chain made the child of block 40 at `slot`, certifying with `cert`:
    * number 41, previous hash block 40's, the certificate flag set, an empty transaction list, and
    * the body its header commits to. Its KES signature is block 40's, so Praos2 refuses its header,
    * after the closure.
    */
  private def certifying(
      slot: BigInt,
      cert: Certificate,
      parent: Int = 40,
      declaredSize: Option[BigInt] = None
  ): (Header.Shelley, Bytes) =
    val (h40, _) = devnetChain(parent)
    val (_, raw40) = devnetChain.last
    val outer = items(node(raw40))
    val inner = items(outer(1))
    val signed = items(inner(0))
    val body = array(
      Vector(encoded(Value.Arr(Vector.empty)), cert.encode, encoded(Value.Null))
    )
    val fields = items(signed(0))
      .map(_.original)
      .updated(0, encoded(Value.UInt(BigInt(parent + 1))))
      .updated(1, encoded(Value.UInt(slot)))
      .updated(2, encoded(Value.ByteString(h40.hash)))
      .updated(6, encoded(Value.UInt(declaredSize.getOrElse(BigInt(body.size)))))
      .updated(7, encoded(Value.ByteString(Blake2b.hash256.hash(body))))
      .updated(10, encoded(Value.Bool(true)))
    val header = array(Vector(array(fields), signed(1).original))
    val raw = array(Vector(outer(0).original, array(Vector(header, body))))
    val decoded =
      Block.decode(raw, leiosSetup.geometry.byron.epochSlots).fold(e => fail(e.message), identity)
    decoded.header match
      case h: Header.Shelley => (h, raw)
      case other             => fail(s"not a Shelley-based header: $other")

  private lazy val keys = Vector.tabulate(3)(i => key(400 + i))
  private lazy val committee: TipCommittee = right(
    LeiosCertification.tipCommittee(
      LeiosCommittee(keys.map((_, vk) => LeiosSeat(Rational(1, 3), Some(vk.bytes)))),
      Rational(1, 2)
    )
  )
  private def signedOver(hash: Bytes): Certificate =
    val rb = RbHash.fromBytes(hash).getOrElse(fail("not 32 bytes"))
    right(
      Certificate.aggregate(
        committee.committee,
        (0 until 3)
          .map(i => right(SeatId.fromInt(i)) -> right(MinSig.sign(keys(i)._1, rb.signable)))
          .toMap
      )
    )

  private val eb: EbHash = EbHash.fromBytes(Bytes.fromArray(Array.fill(32)(7.toByte)))

  /** The parent's bookkeeping, its chain-dependent state announcing [[eb]] where `announcing`. */
  private def preOf(state: MirrorState, announcing: Boolean): MirrorRules.Pre =
    val protocol = state.protocol
    val dep = protocol.dep match
      case ChainDepState.Praos2(p) =>
        val issuer = KeyHash
          .fromBytes[BlockIssuer](Bytes.fromArray(Array.fill(28)(1.toByte)))
          .fold(e => fail(e.toString), identity)
        ChainDepState.Praos2(
          p.copy(announcement =
            Option.when(announcing)(Praos2.AnnouncedBy(issuer, EbAnnouncement(eb, 0L)))
          )
        )
      case other => fail(s"not a Praos2 state: $other")
    MirrorRules.Pre(
      MirrorStep.nesOf(state).getOrElse(fail("no ledger")),
      ProtocolState(dep, protocol.extras),
      state.consensus,
      state.plutus,
      entered = false
    )

  /** The certificate `block` carries. */
  private def certOf(block: (Header.Shelley, Bytes)): Certificate =
    xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraBlock
      .decode(block._2)
      .fold(e => fail(e.toString), _.blockBody.leiosCert.map(_.certificate))
      .getOrElse(fail("no certificate"))

  /** The Leios checks of `block` on `pre` with [[committee]], the closure from `closure`. */
  private def certification(
      pre: MirrorRules.Pre,
      block: (Header.Shelley, Bytes),
      closure: EbHash => Option[Vector[(LeiosTxHash, Bytes)]]
  ): MirrorDijkstra.Certification =
    MirrorDijkstra.certify[cats.Id](
      block._1.slot,
      pre.protocol.dep,
      block._1.prevHash,
      Right(Some(committee)),
      certOf(block),
      closure
    )

  private def applyCertifying(
      state: MirrorState,
      pre: MirrorRules.Pre,
      block: (Header.Shelley, Bytes),
      closure: EbHash => Option[Vector[(LeiosTxHash, Bytes)]]
  ): MirrorDijkstra.Applied =
    MirrorDijkstra(
      state,
      pre,
      block._1,
      block._2,
      leiosSetup.praos,
      leiosSetup.globals,
      leiosSetup.byronHistory,
      xyz.kd5ujc.scarab.plutus.PlutusBackend.default,
      MirrorDijkstra.Read(None, Some(certification(pre, block, closure)))
    )

  test("a certifying block whose closure the store lacks stops the node, never applied as empty") {
    devnetStates.map { states =>
      val parent = states.last
      val block = certifying(BigInt(240), signedOver(devnetChain.last._1.hash))
      assertEquals(
        applyCertifying(parent, preOf(parent, true), block, _ => None),
        Left(
          ChainError.DijkstraBlock(
            BigInt(240),
            DijkstraBlockDefect.Closure(LeiosClosureDefect.Missing(eb))
          )
        )
      )
      // the closure held, empty: past the closure to the header, which Praos2 refuses
      applyCertifying(
        parent,
        preOf(parent, true),
        block,
        h => Option.when(h == eb)(Vector.empty)
      ) match
        case Right(Left(BlockFailure.Praos2(_) | BlockFailure.Unverifiable(_, _))) => ()
        case other => fail(s"not refused by the header rules: $other")
    }
  }

  test("an invalid certificate or one without an announcement is the block's refusal, not a stop") {
    devnetStates.map { states =>
      val parent = states.last
      // signed over another hash than the parent's
      val forged = certifying(BigInt(240), signedOver(Bytes.fromArray(Array.fill(32)(9.toByte))))
      applyCertifying(parent, preOf(parent, true), forged, _ => None) match
        case Right(
              Left(
                BlockFailure.LeiosCertificate(_: LeiosExtValidationError.LeiosInvalidCertificate)
              )
            ) =>
          ()
        case other => fail(s"not refused as an invalid certificate: $other")
      val good = certifying(BigInt(240), signedOver(devnetChain.last._1.hash))
      applyCertifying(parent, preOf(parent, false), good, _ => None) match
        case Right(
              Left(
                BlockFailure.LeiosCertificate(
                  _: LeiosExtValidationError.LeiosCertificateWithoutAnnouncement
                )
              )
            ) =>
          ()
        case other => fail(s"not refused as without an announcement: $other")
    }
  }

  // the public testnet's first proposal, a parameter change of keys 45 and 46 its ledger admitted
  // (`fixtures/reference/leios-testnet-chain-contexts`): the state's governance record, the era's,
  // holds it (#85)
  private lazy val testnetProposal: Bytes =
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus
    DijkstraCorpus.chainContextCases
      .map(j => DijkstraCorpus.hex(DijkstraCorpus.str(j, "bytes")))
      .find { raw =>
        val tx = DijkstraClosure.deserializeTxForLeios(raw).fold(e => fail(e.toString), identity)
        xyz.kd5ujc.scarab.ledger.era.EraTxBody.dijkstra
          .proposalProcedures[xyz.kd5ujc.scarab.ledger.era.TxLevel.Top](
            xyz.kd5ujc.scarab.ledger.era.EraTx.dijkstra
              .body[xyz.kd5ujc.scarab.ledger.era.TxLevel.Top](tx)(using
                xyz.kd5ujc.scarab.ledger.era.BothLevels.Top
              )
          )(using xyz.kd5ujc.scarab.ledger.era.BothLevels.Top)
          .toVector
          .nonEmpty
      }
      .getOrElse(fail("no proposal in the testnet's transactions"))

  test("a valid proposal of a key Dijkstra adds is applied, and the state holds it (#85)") {
    devnetStates.map { states =>
      val parent = states.last
      val block = certifying(BigInt(240), signedOver(devnetChain.last._1.hash))
      val held = LeiosTxHash.fromBytes(Blake2b.hash256.hash(testnetProposal))
      val pre = preOf(parent, true)
      val checked = Some(
        signedOver(devnetChain.last._1.hash) -> certification(
          pre,
          block,
          h => Option.when(h == eb)(Vector(held -> testnetProposal))
        )
      )
      val env = MirrorDijkstra.envOf(
        parent,
        block._1,
        leiosSetup.praos,
        leiosSetup.globals,
        leiosSetup.byronHistory,
        xyz.kd5ujc.scarab.plutus.PlutusBackend.default
      )
      // the closure applied to the unticked parent: its proposal in the state's governance state
      MirrorDijkstra.certified(env, parent, pre.nes, checked).value match
        case Right(Right((applied, _))) =>
          val nes = MirrorDijkstra.inDijkstra(applied).getOrElse(fail("not a Dijkstra state"))
          val keys = EraProposals
            .proposalsActions(nes.es.ls.utxo.gov.cgsProposals)
            .map(_.gasAction)
            .collect { case xyz.kd5ujc.scarab.ledger.era.GovAction.ParameterChange(_, u, _) =>
              xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraProposals.updateKeys(u).toSet
            }
          assertEquals(keys, Vector(Set(BigInt(45), BigInt(46))))
        case other => fail(s"the closure not applied: $other")
    }
  }

  // the forger's certifying path (`partitionMempool`'s `resolveAndApplyLeiosClosure`,
  // `NodeKernel/Forge.hs:826-857` at ouroboros-consensus 2416493b) on a closure that holds the
  // testnet's keys-45/46 proposal: the closure applies on the era types and the pool's snapshot is
  // taken on the state that holds the proposal; only a closure that does not apply stops the node
  test(
    "the forger certifies a closure holding the keys-45/46 proposal; a missing closure stops it"
  ) {
    import xyz.kd5ujc.scarab.consensus.BlockRef
    import xyz.kd5ujc.scarab.consensus.leios.CertifyDecision
    import xyz.kd5ujc.scarab.ledger.StorageError
    import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState}
    import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
    import xyz.kd5ujc.scarab.node.forge.{
      BlockBuilder,
      DijkstraForge,
      DijkstraParent,
      DijkstraSnapshot,
      DijkstraSnapshots
    }
    import xyz.kd5ujc.scarab.node.mempool.EbMeasure
    import xyz.kd5ujc.scarab.protocol.EbPoint
    import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64
    given cats.mtl.Raise[IO, StorageError] with
      def functor = cats.Functor[IO]
      def raise[E <: StorageError, A](e: E): IO[A] = IO(fail(e.message))
    def keysOf(nes: NewEpochState[Dijkstra]) = EraProposals
      .proposalsActions(nes.es.ls.utxo.gov.cgsProposals)
      .map(_.gasAction)
      .collect { case xyz.kd5ujc.scarab.ledger.era.GovAction.ParameterChange(_, u, _) =>
        xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraProposals.updateKeys(u).toSet
      }
    devnetStates.flatMap { states =>
      val parent = states.last
      val tipHeader = devnetChain.last._1
      val tip = BlockRef(tipHeader.slot, tipHeader.hash, tipHeader.blockNo)
      val unticked =
        MirrorStep.nesOf(parent).flatMap(MirrorDijkstra.inDijkstra).getOrElse(fail("no Dijkstra"))
      val point = EbPoint(Word64.clamped(tip.slot), eb)
      val held = LeiosTxHash.fromBytes(Blake2b.hash256.hash(testnetProposal))
      val slot = BigInt(240)
      def certifier(stored: Option[Vector[(LeiosTxHash, Bytes)]]) = LeiosCertifier[IO](
        h => IO.pure(Option.when(h == eb)(stored).flatten),
        _ => IO.pure(None),
        None,
        (state, at) =>
          MirrorDijkstra.envAt(
            state,
            at,
            Era.Dijkstra,
            leiosSetup.praos,
            leiosSetup.globals,
            leiosSetup.byronHistory,
            xyz.kd5ujc.scarab.plutus.PlutusBackend.default
          )
      )
      // what the pool's snapshot is taken on: the parent with the closure applied, ticked
      def recording(seen: cats.effect.Ref[IO, Option[NewEpochState[Dijkstra]]]) =
        new DijkstraSnapshots[IO]:
          def forSlot(
              ticked: NewEpochState[Dijkstra],
              at: BigInt,
              clock: xyz.kd5ujc.scarab.ledger.PosixClock
          )(using cats.mtl.Raise[IO, StorageError]) =
            seen.set(Some(ticked)).as(DijkstraSnapshot.empty)
      val parentOf = DijkstraParent(parent, Some(tip), unticked, unticked, Some(point))
      val cert = signedOver(tip.hash)
      val decision = CertifyDecision.Certify(cert, point)
      def partition(c: forge.LeiosCertifying[IO, MirrorState], s: DijkstraSnapshots[IO]) =
        Handle
          .allow[Fatal](
            DijkstraForge
              .partition[IO, MirrorState](
                slot,
                parentOf,
                decision,
                TxMeasure.zero,
                EbMeasure.zero,
                s,
                c,
                nes => Right(nes),
                leiosSetup.txClock.at(parent, Era.Dijkstra, slot)
              )
              .map(Right(_))
          )
          .rescue(f => IO.pure(Left(f)))
      for
        seen <- cats.effect.Ref.of[IO, Option[NewEpochState[Dijkstra]]](None)
        applied <- partition(certifier(Some(Vector(held -> testnetProposal))), recording(seen))
        snapshotState <- seen.get
        missing <- partition(certifier(None), DijkstraSnapshots.unadmitted[IO])
      yield
        applied match
          case Right(split) =>
            split.body match
              case BlockBuilder.Assembly.InDijkstra(body, None) =>
                assertEquals(body.leiosCertificate, Some(cert))
                assertEquals(body.transactions, Vector.empty)
              case other => fail(s"not a certifying body: $other")
          case Left(f) => fail(s"the forger stopped on a valid closure: ${f.message}")
        assertEquals(keysOf(unticked), Vector.empty)
        assertEquals(
          snapshotState.map(keysOf),
          Some(Vector(Set(BigInt(45), BigInt(46))))
        )
        // a closure the store does not hold is the reference's `error`: the node stops
        missing match
          case Left(Fatal.Internal(xyz.kd5ujc.scarab.ledger.ChainError.DijkstraBlock(_, d))) =>
            assertEquals(d, DijkstraBlockDefect.Closure(LeiosClosureDefect.Missing(eb)))
          case other => fail(s"a missing closure did not stop the forger: $other")
    }
  }

  test(
    "a failure of the era's types is the bytes' refusal; the node's readings that disagree are its own"
  ) {
    import xyz.kd5ujc.scarab.ledger.era.EraTxError
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraBlockError
    val refused = EraTxError.NotInEra(xyz.kd5ujc.scarab.ledger.era.EraField.Update)
    assertEquals(DijkstraBlockError.of(refused), DijkstraBlockError.Era(refused))
    Vector(EraTxError.ScriptsReadAgain, EraTxError.WitnessDataReadAgain, EraTxError.OtherLevel)
      .foreach { own =>
        assertEquals(DijkstraBlockError.of(own), DijkstraBlockError.Projection(own))
        assertEquals(
          BlockNoVerdict.ofDijkstra(DijkstraBlockDefect.Projection(own)),
          Some(BlockNoVerdict.Projection(own))
        )
      }
    // a closure the store lacks stays a stop
    assertEquals(
      BlockNoVerdict.ofDijkstra(DijkstraBlockDefect.Closure(LeiosClosureDefect.Missing(eb))),
      None
    )
  }

  // `applyHelper` (ouroboros-consensus 2416493b `Ledger/Extended.hs:297-311`): BBODY, then the
  // header; the declared size is BBODY's (cardano-ledger 9df9f98c `Dijkstra/Rules/Bbody.hs:362`)
  test("a wrong declared size reaches BBODY, whose refusal comes before the header's") {
    devnetStates.map { states =>
      val parent = states.last
      val cert = signedOver(devnetChain.last._1.hash)
      val right = certifying(BigInt(240), cert)
      val actual = right._1.bodySize
      // the body hash is right, the declared size one more; its KES signature also fails Praos2
      val wrong = certifying(BigInt(240), cert, declaredSize = Some(actual + 1))
      val held = (h: EbHash) => Option.when(h == eb)(Vector.empty[(LeiosTxHash, Bytes)])
      applyCertifying(parent, preOf(parent, true), wrong, held) match
        case Right(Left(BlockFailure.DijkstraBbody(failures))) =>
          assert(
            failures.exists {
              case xyz.kd5ujc.scarab.ledger.era.rules.DijkstraBbodyPredFailure
                    .WrongBlockBodySizeBBODY(m) =>
                m.supplied == actual.toLong && m.expected == actual.toLong + 1 ||
                m.supplied == actual.toLong + 1 && m.expected == actual.toLong
              case _ => false
            },
            failures.toString
          )
        case other => fail(s"not BBODY's size refusal: $other")
      // the right size: BBODY passes, and the header is refused
      applyCertifying(parent, preOf(parent, true), right, held) match
        case Right(Left(BlockFailure.Praos2(_) | BlockFailure.Unverifiable(_, _))) => ()
        case other => fail(s"not refused by the header rules: $other")
    }
  }

  // a Dijkstra block's checkpoint is Praos2's envelope's, checked in `validateHeader` after the ledger
  // (`applyHelper`, ouroboros-consensus 2416493b `Ledger/Extended.hs:297-311`)
  test("a Dijkstra block that misses a checkpoint is refused by Praos2's envelope, after BBODY") {
    devnetStates.flatMap { states =>
      val (header, raw) = devnetChain(34)
      val other = Bytes.fromArray(Array.fill(32)(5.toByte))
      val checkpointed = leiosSetup.praos.copy(checkpoints =
        xyz.kd5ujc.scarab.consensus.HeaderCheckpoints(Map(header.blockNo -> other))
      )
      MemoryUtxoStore[IO].flatMap { store =>
        val rules = new MirrorRules[IO](
          checkpointed,
          leiosSetup.globals,
          leiosSetup.byronRules,
          leiosSetup.handOver,
          leiosSetup.byronHistory,
          xyz.kd5ujc.scarab.plutus.PlutusBackend.default,
          utxoStore = Some(store)
        )
        Handle
          .allow[NodeError](rules(states(34), header, raw).map(_ => None))
          .rescue(e => IO.pure(Some(e)))
          .map {
            case Some(
                  xyz.kd5ujc.scarab.ledger.ValidationError(
                    _,
                    BlockFailure.Praos2(xyz.kd5ujc.scarab.ledger.Praos2Error.Envelope(_))
                  )
                ) =>
              ()
            case other => fail(s"not Praos2's checkpoint refusal: $other")
          }
      }
    }
  }

  test("the closure is read only once the certificate holds, and resolved once") {
    import cats.data.State
    type Recorded[A] = State[Vector[EbHash], A]
    devnetStates.map { states =>
      val parent = states.last
      val pre = preOf(parent, true)
      def checked(block: (Header.Shelley, Bytes)) =
        MirrorDijkstra
          .certify[Recorded](
            block._1.slot,
            pre.protocol.dep,
            block._1.prevHash,
            State.pure(Right(Some(committee))),
            certOf(block),
            h => State(asked => (asked :+ h, Some(Vector.empty)))
          )
          .run(Vector.empty)
          .value
      val (forgedAsked, forged) =
        checked(certifying(BigInt(240), signedOver(Bytes.fromArray(Array.fill(32)(9.toByte)))))
      assertEquals(forgedAsked, Vector.empty)
      forged match
        case Right(
              Left(
                BlockFailure.LeiosCertificate(_: LeiosExtValidationError.LeiosInvalidCertificate)
              )
            ) =>
          ()
        case other => fail(s"not refused as an invalid certificate: $other")
      val (goodAsked, good) = checked(certifying(BigInt(240), signedOver(devnetChain.last._1.hash)))
      assertEquals(goodAsked, Vector(eb))
      good match
        case Right(Right(MirrorDijkstra.Verified(point, Right(closure)))) =>
          assertEquals(point.hash, eb)
          assertEquals(closure, Vector.empty)
        case other => fail(s"not verified: $other")
    }
  }

  test(
    "an invalid certificate reads nothing from the endorser-block store, through the block rules"
  ) {
    devnetStates.flatMap { states =>
      val parent = states.last
      val dep = preOf(parent, true).protocol.dep
      // the parent's chain-dependent state announces the endorser block
      val announcing = parent.copy(ext =
        parent.ext.copy(headerState =
          parent.ext.headerState.copy(chainDep = parent.ext.headerState.chainDep.withState(dep))
        )
      )
      val block = certifying(BigInt(240), signedOver(Bytes.fromArray(Array.fill(32)(9.toByte))))
      for
        asked <- cats.effect.Ref.of[IO, Vector[EbHash]](Vector.empty)
        committees <- cats.effect.Ref.of[IO, Option[
          ((LeiosCommittee, Rational), TipCommittee)
        ]](None)
        utxo <- MemoryUtxoStore[IO]
        rules = new MirrorRules[IO](
          leiosSetup.praos,
          leiosSetup.globals,
          leiosSetup.byronRules,
          leiosSetup.handOver,
          leiosSetup.byronHistory,
          xyz.kd5ujc.scarab.plutus.PlutusBackend.default,
          utxoStore = Some(utxo),
          leios = Some(MirrorRules.Leios(new MirrorDijkstraSuite.Recording(asked), committees))
        )
        result <- Handle
          .allow[NodeError](rules(announcing, block._1, block._2).map(_ => None))
          .rescue(e => IO.pure(Some(e)))
        read <- asked.get
      yield
        assertEquals(read, Vector.empty)
        result match
          case Some(
                xyz.kd5ujc.scarab.ledger.ValidationError(_, BlockFailure.LeiosCertificate(_))
              ) =>
            ()
          case other => fail(s"not the certificate's refusal: $other")
    }
  }

  // `neededTxInsForBlock` (cardano-ledger 9df9f98c `Cardano/Ledger/Block.hs:104-110`): a ranking
  // block's keys leave out what its own transactions create; a closure's keys keep them
  // (`leiosClosureTxKeySets`, ouroboros-consensus 2416493b `Shelley/Ledger/Leios.hs:198`)
  test(
    "a block's read set leaves out the outputs its own transactions create; a closure's does not"
  ) {
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus
    val txs = DijkstraCorpus.chainContextCases
      .filter(j => j.hcursor.get[Long]("block").contains(59559L))
      .map(j =>
        DijkstraClosure
          .deserializeTxForLeios(DijkstraCorpus.hex(DijkstraCorpus.str(j, "bytes")))
          .fold(e => fail(e.toString), identity)
      )
    val inner = hex("5417b77a68d658dd2e740a962fe5439606f883e9506ca309ac542491c4bd9b8a")
    val all = MirrorDijkstra.inputs(txs)
    assert(all.exists(_.id == inner), "no input of the block's own transaction")
    assertEquals(MirrorDijkstra.blockInputs(txs), all.filterNot(_.id == inner))
  }

  test("the closure is applied to the parent unticked: a block of the next epoch ticks after it") {
    devnetStates.map { states =>
      // block 36's state; the closure is block 37's transaction; the certifying block is at slot
      // 400, the first of epoch 2, while the parent is in epoch 1
      val parent = states(37)
      val (_, raw37) = devnetChain(37)
      val flatTx = LeiosBlock.decode(raw37).fold(e => fail(e.message), _.transactions) match
        case Vector(t) => t
        case other     => fail(s"not one transaction: ${other.size}")
      val tx = flatTx.raw
      val txHash = LeiosTxHash.fromBytes(Blake2b.hash256.hash(tx))
      val block = certifying(BigInt(400), signedOver(devnetChain(36)._1.hash), parent = 36)
      val pre = preOf(parent, true)
      val stamped = EraNes.mapUtxo(pre.nes)(_.at(41L))
      val eraTx = DijkstraClosure.deserializeTxForLeios(tx).fold(e => fail(e.toString), identity)
      val spent = MirrorDijkstra.inputs(Vector(eraTx))
      def utxo(nes: EraNes) = nes match
        case i: EraNes.InEra[e] => i.nes.es.ls.utxo.utxo.utxo
      val created = TxIn(flatTx.id, 0)
      val applied = MirrorDijkstra.certified(
        MirrorDijkstra.envOf(
          parent,
          block._1,
          leiosSetup.praos,
          leiosSetup.globals,
          leiosSetup.byronHistory,
          xyz.kd5ujc.scarab.plutus.PlutusBackend.default
        ),
        parent,
        stamped,
        Some(
          signedOver(devnetChain(36)._1.hash) ->
            certification(pre, block, h => Option.when(h == eb)(Vector(txHash -> tx)))
        )
      )
      assert(spent.nonEmpty && spent.forall(utxo(stamped).contains))
      applied.value match
        case Right(Right((closed, bytes))) =>
          // unticked: the parent's epoch, not the block's
          assertEquals(closed.epoch, pre.nes.epoch)
          assertEquals(closed.epoch.toBigInt, BigInt(1))
          assertEquals(bytes, DijkstraClosure.closureTxBytes(Vector(eraTx)))
          // the transaction's inputs spent and its first output created, as block 37 left them
          assert(spent.forall(i => !utxo(closed).contains(i)))
          assert(utxo(closed).contains(created))
          val after37 = MirrorStep.utxoOf(states(38)).getOrElse(fail("no UTxO"))
          assertEquals(utxo(closed).get(created), after37.get(created))
        case other => fail(s"the closure not applied: $other")
    }
  }
  test("the closure before the tick, not after: a ticked-parent mutant leaves another state") {
    devnetStates.map { states =>
      // the parent is block 36's state, in epoch 1; the certifying block is at slot 400, the first of
      // epoch 2, so its tick crosses Dijkstra's epoch boundary
      val parent = states(37)
      val flatTx =
        LeiosBlock.decode(devnetChain(37)._2).fold(e => fail(e.message), _.transactions) match
          case Vector(t) => t
          case other     => fail(s"not one transaction: ${other.size}")
      val cert = signedOver(devnetChain(36)._1.hash)
      val block = certifying(BigInt(400), cert, parent = 36)
      val pre = preOf(parent, true)
      val stamped = EraNes.mapUtxo(pre.nes)(_.at(37L))
      val checked = Some(
        cert -> certification(
          pre,
          block,
          h => Option.when(h == eb)(Vector(LeiosTxHash.fromBytes(flatTx.id) -> flatTx.raw))
        )
      )
      val env = MirrorDijkstra.envOf(
        parent,
        block._1,
        leiosSetup.praos,
        leiosSetup.globals,
        leiosSetup.byronHistory,
        xyz.kd5ujc.scarab.plutus.PlutusBackend.default
      )
      val tick = MirrorDijkstra.tickContextOf(env, parent)
      val mirrored = MirrorDijkstra
        .closedThenTicked(env, tick, parent, block._1, stamped, checked)
        .value match
        case Right(Right((nes, _))) => nes
        case other                  => fail(s"not applied: $other")
      // the mutant: the parent ticked first, then the closure on the ticked state
      val tickedFirst = xyz.kd5ujc.scarab.ledger.rules.EraBlock
        .tickedTo(tick)(stamped, block._1, Era.Dijkstra, block._1.hash)
        .fold(f => fail(s"the tick refused: $f"), identity)
      val mutant = MirrorDijkstra
        .certified(env, parent, tickedFirst, checked)
        .value match
        case Right(Right((i: EraNes.InEra[e], _))) => i
        case other                                 => fail(s"not applied: $other")
      assertEquals(mirrored.epoch.toBigInt, BigInt(2))
      assertEquals(mutant.nes.epoch.toBigInt, BigInt(2))
      // the closure's fee is in the fees the boundary's SNAP took when the closure came first, and
      // not when it came after the tick; the UTxO state's fees hold it either way, as the boundary
      // leaves them to the reward update
      val fee = flatTx.body.fee.getOrElse(fail("no fee"))
      assert(fee > 0, "a fee")
      assertEquals(mirrored.es.snapshots.fee.value - mutant.nes.es.snapshots.fee.value, fee)
      assertEquals(mutant.nes.es.ls.utxo.fees, mirrored.es.ls.utxo.fees)
    }
  }

  test(
    "the cumulative transaction bytes gain each Dijkstra block's transactions, and nothing else"
  ) {
    devnetStates.map { states =>
      def bytes(m: MirrorState): BigInt = m.ext.ledgerState.state match
        case i: xyz.kd5ujc.scarab.consensus.state.EraLedgerState.InShelleyEra[e] =>
          i.state.layout match
            case xyz.kd5ujc.scarab.consensus.state.ShelleyLedgerState.Layout.Leios(b) =>
              BigInt(java.lang.Long.toUnsignedString(b.bits))
            case other => fail(s"not the Leios layout: $other")
        case other => fail(s"not a Shelley-based state: $other")
      val sizes = (34 to 41).map { i =>
        LeiosBlock.decode(devnetChain(i - 1)._2).fold(e => fail(e.message), _.transactions)
      }
      (34 to 41).zip(sizes).foreach { (i, txs) =>
        val gained = txs
          .map(t =>
            DijkstraClosure.deserializeTxForLeios(t.raw).fold(e => fail(e.toString), identity)
          )
        assertEquals(
          bytes(states(i)) - bytes(states(i - 1)),
          BigInt(DijkstraClosure.closureTxBytes(gained).bits),
          s"block ${i - 1}"
        )
      }
      // block 37's transaction, the one Dijkstra block that holds any
      assertEquals(sizes.map(_.size), Vector(0, 0, 0, 0, 1, 0, 0, 0))
      assert(bytes(states(38)) > bytes(states(37)))
      // a certifying block, whose body holds no transaction: the state gains its closure's bytes
      // at the final assembly
      val parent = states.last
      val block = certifying(BigInt(240), signedOver(devnetChain.last._1.hash))
      val body = xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraBlock
        .decode(block._2)
        .fold(e => fail(e.toString), _.blockBody)
      val closureBytes = DijkstraClosure.closureTxBytes(
        Vector(
          DijkstraClosure
            .deserializeTxForLeios(sizes(4).headOption.getOrElse(fail("no transaction")).raw)
            .fold(e => fail(e.toString), identity)
        )
      )
      val nes = MirrorStep
        .nesOf(parent)
        .flatMap(MirrorDijkstra.inDijkstra)
        .getOrElse(fail("no Dijkstra ledger"))
      val blockStats = xyz.kd5ujc.scarab.ledger.PlutusStats(1, 2, 5, 3)
      val after = MirrorDijkstra
        .assembled(
          parent,
          preOf(parent, false),
          block._1,
          xyz.kd5ujc.scarab.consensus.state.Placement(
            leiosSetup.praos.geometry,
            leiosSetup.byronHistory,
            leiosSetup.praos.forks
          ),
          closureBytes,
          body,
          nes,
          parent.protocol,
          blockStats
        )
        .fold(e => fail(s"not assembled: $e"), identity)
      assert(BigInt(closureBytes.bits) > 0)
      assertEquals(bytes(after) - bytes(parent), BigInt(closureBytes.bits))
      // the node's phase-2 counters gain the block's, field by field (#85)
      assertEquals(after.plutus, parent.plutus |+| blockStats)
    }
  }
end MirrorDijkstraSuite

object MirrorDijkstraSuite:
  /** An endorser-block store that records each closure asked for and holds none; nothing else of it
    * is read.
    */
  final class Recording(asked: cats.effect.Ref[IO, Vector[EbHash]])
      extends xyz.kd5ujc.scarab.storage.LeiosStore[IO]:
    import xyz.kd5ujc.scarab.storage.{BodyWritten, LeiosEb, LeiosStoreError, LocalFill}
    import xyz.kd5ujc.scarab.protocol.EbPoint
    import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}
    import cats.mtl.Raise
    private def unread[A]: IO[A] = IO.raiseError(new AssertionError("not read by the block rules"))
    def writeEbPoint(point: EbPoint, announced: Word32)(using Raise[IO, LeiosStoreError]) = unread
    def writeEbBody(point: EbPoint, body: LeiosEb, fills: Vector[LocalFill])(using
        Raise[IO, LeiosStoreError]
    ): IO[BodyWritten] = unread
    def writeTxs(point: EbPoint, txs: Vector[(Int, Bytes)])(using Raise[IO, LeiosStoreError]) =
      unread
    def lookupEbBody(hash: EbHash)(using Raise[IO, LeiosStoreError]) = unread
    def lookupEbClosure(hash: EbHash)(using Raise[IO, LeiosStoreError]) =
      asked.update(_ :+ hash).as(None)
    def batchRetrieveTxs(hash: EbHash, offsets: Vector[Int])(using Raise[IO, LeiosStoreError]) =
      unread
    def scanEbPoints(using Raise[IO, LeiosStoreError]) = unread
    def scanCompleteEbClosuresNotOlderThanSlot(slot: Word64)(using Raise[IO, LeiosStoreError]) =
      unread
    def garbageCollect(slot: Word64)(using Raise[IO, LeiosStoreError]) = unread
    def promoteToImmutable(points: Vector[EbPoint])(using Raise[IO, LeiosStoreError]) = unread
