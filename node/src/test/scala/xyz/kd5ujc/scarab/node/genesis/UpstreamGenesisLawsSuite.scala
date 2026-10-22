// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.genesis

import cats.effect.IO
import xyz.kd5ujc.scarab.network.FailsOnUncaught

import UpstreamGenesisCases.*
import UpstreamGenesisLaws.*
import UpstreamGenesisReplay.{Downloaded, Result, Terminal}

class UpstreamGenesisLawsSuite extends FailsOnUncaught:
  private def cases: IO[Vector[Scenario]] =
    UpstreamGenesisCases.read.map(_.fold(error => fail(error.toString), identity))
  private def reference(scenario: Scenario): Result = Result(
    scenario.expected.selection,
    Vector.empty,
    scenario.expected.peers.map(p => Terminal(p.peer, p.component, p.end, None)),
    Vector.empty,
    Vector.empty
  )

  test("all21 original keys decode, and reference observations satisfy every non-trace law") {
    cases.map { scenarios =>
      assertEquals(scenarios.map(_.law).toSet, Law.values.toSet)
      assertEquals(scenarios.groupMapReduce(_.law)(_ => 1)(_ + _).values.toSet, Set(4))
      scenarios.foreach(scenario =>
        assertEquals(evaluate(scenario, reference(scenario)), Vector.empty, scenario.label)
      )
      assertEquals(decode("upstream-LoP-7-seed-333001", Family.LoP, 333001), None)
      assertEquals(decode("upstream-LoP-0-seed-333001", Family.LoE, 333001), None)
      assertEquals(decode("upstream-LoP-0-seed-333001", Family.LoP, 333002), None)
    }
  }

  test("Density permits either original refusal, rejects multiple refusals and a stalled tip") {
    cases.map { scenarios =>
      val scenario =
        scenarios.find(_.law == Law.DensityDisconnect).fold(fail("density case missing"))(identity)
      val actual = reference(scenario)
      val peer = PeerId.Adversarial(1)
      Vector(End.DensityTooLow, End.CandidateTooSparse).foreach { end =>
        val refused =
          actual.copy(terminals = Vector(Terminal(peer, Component.ChainSyncClient, end, None)))
        assertEquals(evaluate(scenario, refused), Vector.empty)
        assert(
          evaluate(scenario, refused.copy(terminals = refused.terminals ++ refused.terminals))
            .exists(_.isInstanceOf[Violation.ChainSyncExceptions])
        )
      }
      val stalled = actual.copy(selection = actual.selection.copy(headers = Vector.empty))
      assert(evaluate(scenario, stalled).exists(_.isInstanceOf[Violation.TipEquality]))
    }
  }

  test("all component exception constraints and honest termination are enforced") {
    cases.map { scenarios =>
      scenarios.foreach { scenario =>
        val unexpected =
          Terminal(PeerId.Adversarial(1), Component.BlockFetchServer, End.Unexpected, None)
        assert(
          evaluate(scenario, reference(scenario).copy(terminals = Vector(unexpected)))
            .contains(Violation.UnexpectedException(unexpected))
        )
      }
      val scenario = scenarios
        .find(s =>
          s.law == Law.AdversarialBranches &&
            s.tree.headOption.exists(_.headers.lastOption.exists(_.slot.value >= s.genesisWindow))
        )
        .fold(fail("nontrivial uniform case missing"))(identity)
      val peer = PeerId.Honest(0)
      val actual = reference(scenario)
      val ended = actual.copy(terminals =
        actual.terminals :+
          Terminal(peer, Component.ChainSyncClient, End.Success, None)
      )
      assert(evaluate(scenario, ended).contains(Violation.HonestDisconnected(peer)))
    }
  }

  test("CSJ normal download bound preserves identity, honest attribution and exact tip boundary") {
    cases.map { scenarios =>
      val scenario = scenarios
        .find(s =>
          s.law == Law.CsjSharedHonest && s.tree.headOption
            .exists(
              _.headers.exists(p =>
                p.slot.value + s.jumpSize < s.tree.headOption
                  .fold(BigInt(0))(_.headers.lastOption.fold(BigInt(0))(_.slot.value)) + 1
              )
            )
        )
        .fold(fail("CSJ countable header missing"))(identity)
      val trunk = scenario.tree.headOption.fold(fail("trunk missing"))(identity)
      val tipSlot = trunk.headers.lastOption.fold(BigInt(0))(_.slot.value)
      val header = trunk.headers
        .find(_.slot.value + scenario.jumpSize < tipSlot + 1)
        .fold(fail("countable header missing"))(identity)
      val repeated = Vector.fill(scenario.jumpSize.toInt + 2)(Downloaded(PeerId.Honest(0), header))
      val actual = reference(scenario)
      assert(
        evaluate(scenario, actual.copy(downloaded = repeated))
          .exists(_.isInstanceOf[Violation.HonestDownloadBound])
      )
      assertEquals(evaluate(scenario, actual.copy(downloaded = repeated.drop(1))), Vector.empty)
      assertEquals(
        evaluate(
          scenario,
          actual.copy(downloaded = repeated.map(_.copy(peer = PeerId.Adversarial(1))))
        ),
        Vector.empty
      )
      val lastIncluded: xyz.kd5ujc.scarab.protocol.ChainSync.Point.Block =
        xyz.kd5ujc.scarab.protocol.ChainSync.Point.Block(
          xyz.kd5ujc.scarab.protocol.ChainSync.UInt64.saturating(tipSlot - scenario.jumpSize),
          header.hash
        )
      val firstExcluded: xyz.kd5ujc.scarab.protocol.ChainSync.Point.Block =
        xyz.kd5ujc.scarab.protocol.ChainSync.Point.Block(
          xyz.kd5ujc.scarab.protocol.ChainSync.UInt64.saturating(tipSlot + 1 - scenario.jumpSize),
          header.hash
        )
      assert(
        evaluate(scenario, actual.copy(downloaded = repeated.map(_.copy(header = lastIncluded))))
          .exists(_.isInstanceOf[Violation.HonestDownloadBound])
      )
      assertEquals(
        evaluate(scenario, actual.copy(downloaded = repeated.map(_.copy(header = firstExcluded)))),
        Vector.empty
      )
    }
  }

  test("CSJ Origin successor is zero and the unrepresentable maximum fails explicitly") {
    cases.map { scenarios =>
      import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
      import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.Fragment
      val scenario = scenarios
        .find(_.law == Law.CsjSharedHonest)
        .fold(fail("CSJ case missing"))(identity)
      val header =
        scenario.tree.flatMap(_.headers).headOption.fold(fail("header missing"))(identity)
      val zero: Point.Block = Point.Block(UInt64.saturating(0), header.hash)
      val actual =
        reference(scenario).copy(downloaded = Vector.fill(2)(Downloaded(PeerId.Honest(1), zero)))
      val origin = scenario.copy(tree = Vector(Fragment(Point.Origin, Vector.empty)), jumpSize = 0)
      assertEquals(evaluate(origin, actual), Vector.empty)
      val maximum: Point.Block = Point.Block(UInt64.saturating((BigInt(1) << 64) - 1), header.hash)
      val unrepresentable = scenario.copy(tree = Vector(Fragment(Point.Origin, Vector(maximum))))
      assertEquals(
        evaluate(unrepresentable, actual),
        Vector(Violation.InvalidTrunkSuccessor(maximum))
      )
      val wrapped = scenario.copy(jumpSize = 1)
      assert(
        evaluate(
          wrapped,
          actual.copy(downloaded = Vector.fill(3)(Downloaded(PeerId.Honest(1), maximum)))
        )
          .exists(_.isInstanceOf[Violation.HonestDownloadBound])
      )
    }
  }

  test("native fetch-context and timer grace stay coherent for the original starvation switches") {
    cases.map { scenarios =>
      import xyz.kd5ujc.scarab.node.GenesisSettings
      import xyz.kd5ujc.scarab.network.selection.{GenesisBlockFetch, Gsm}
      val switches = scenarios.map(_.config.starvation).toSet
      assertEquals(switches, Set(false, true))
      assertEquals(scenarios.count(!_.config.starvation), 8)
      // A nondefault configuration detects a separately hard-coded context grace, while the
      // native-default case exercises the same source path as the unchanged 84-case runtime replay.
      Vector(GenesisSettings(), GenesisSettings(blockFetchGracePicos = 37)).foreach { settings =>
        val context = UpstreamGenesisReplay.fetchContext(
          settings,
          Gsm.Phase.Syncing,
          17,
          GenesisBlockFetch.Starvation.EndedAt(11)
        )
        assertEquals(context.gracePicos, settings.blockFetchGracePicos)
      }
    }
  }
