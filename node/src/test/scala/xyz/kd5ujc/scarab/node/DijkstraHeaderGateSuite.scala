// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.mtl.{Ask, Stateful, Tell}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{
  Anchor,
  BlockRef,
  ChainSelection,
  Compute,
  PraosConfig,
  SelectView,
  VolatileChain
}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{
  GenesisInternalProblem,
  Header,
  HeaderDefect,
  PeerError,
  Praos2Defect
}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.FailureClass
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader, ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellLedgerFile

/** The canonical header gate ([[HeaderCheck.forNodeChain]], the gate every peer session of the node
  * runs) on the public Leios testnet's headers under `--reference-build leios-b004fe0c`, with the
  * node's chain anchored at the testnet's ledger state at slot 2,168,639
  * (`fixtures/leios/state/testnet-2168639`) and the eight headers that follow it on that chain
  * (`fixtures/leios/testnet/headers-after-2168639.tsv`). The maximum major version is raised to 12,
  * as the reference's `ExperimentalHardForksEnabled` does (#54).
  */
class DijkstraHeaderGateSuite extends FailsOnUncaught:
  private given Tell[IO, GenesisInternalProblem] with
    val functor = summon[cats.Functor[IO]]
    def tell(problem: GenesisInternalProblem): IO[Unit] =
      IO.raiseError(new AssertionError(problem.message))

  private val testnet = Path.of("fixtures", "leios")
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)
  private lazy val setup = NodeSetup
    .fromConfigFile(ReferenceBuild.LeiosB004fe0c)(testnet.resolve("testnet/config/config.json"))
    .fold(e => fail(e.message), identity)
  private lazy val praos: PraosConfig =
    setup.praos.copy(forks = setup.praos.forks.copy(maxMajorProtVer = 12))
  private lazy val anchor: MirrorState =
    val raw = Bytes.wrap(Files.readAllBytes(testnet.resolve("state/testnet-2168639/state")))
    val file =
      HaskellLedgerFile.decode(raw, StateLayout.Leios).fold(e => fail(e.render), identity)
    setup.mirrorStart.copy(ext = file.ext.getOrElse(fail("no Shelley-based ledger")))
  private val anchorTip = BlockRef(
    BigInt(2168639),
    hex("aed73a1050263343a16d9a980b2f6dce1152c2e959b69987230a9e5f4844f619"),
    BigInt(97273)
  )
  private lazy val headers: Vector[Header] =
    Files
      .readAllLines(testnet.resolve("testnet/headers-after-2168639.tsv"))
      .asScala
      .toVector
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .map { line =>
        NtNHeader.codec
          .decode(hex(line.split('\t')(4)))
          .left
          .map(_.toString)
          .flatMap(Header.fromNtN(_, setup.geometry.byron.epochSlots).left.map(_.message))
          .fold(e => fail(e), identity)
      }

  /** A session of the canonical gate from the anchor's block, with `config` 's header rules: the
    * outcome of offering it every header, and its counts.
    */
  private def session(config: PraosConfig) =
    val chain = VolatileChain[MirrorState](
      setup.geometry.k.toInt,
      Anchor(Some(anchorTip), anchor),
      Vector.empty
    )
    val selection = ChainSelection.Local(
      chain.anchor.point,
      SelectView(anchorTip.blockNo, anchorTip.slot, None, 0, None),
      Vector.empty
    )
    for
      state <- Ref.of[IO, VolatileChain[MirrorState]](chain)
      given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(state)
      given Ask[IO, ChainSelection.Local] = Ask.const[IO, ChainSelection.Local](selection)
      gate <- HeaderCheck
        .forNodeChain[IO, MirrorState](
          NodeHeaderRules(config, setup.globals, setup.byronRules, setup.handOver),
          Compute.inline[IO],
          10000
        )
        .start(anchorTip.point)
      outcome <- Fault.fiber(
        gate.accept(headers.map(h => SyncEvent.RollForward(h, ChainSync.Tip.Origin)))
      )
      counts <- gate.counts
    yield (outcome, counts)

  test("the gate validates the testnet's headers after its ledger state, every one") {
    session(praos).map { (outcome, counts) =>
      assertEquals(outcome, Right(()))
      assertEquals(counts.validated, headers.size.toLong)
    }
  }

  test("a defect of the node's own configuration ends the session uncharged, not as unsettled") {
    // a KES period of no slots: Praos2 reaches no verdict on the first header
    session(praos.copy(slotsPerKesPeriod = 0)).map { (outcome, counts) =>
      val expected = PeerError.HeaderUndecided(
        headers.head.point,
        HeaderDefect.Praos2(Praos2Defect.NonPositiveKesPeriod(0))
      )
      assertEquals(outcome, Left(Fault.Failed(expected)))
      assertEquals(FailurePolicy.classify(expected), FailureClass.NotThePeers)
      assertEquals(counts.validated, 0L)
    }
  }
