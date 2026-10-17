// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.governor

import org.scalacheck.Gen
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

import MockEnvironment.*

/** Generators of the environments the properties run in (`Arbitrary GovernorMockEnvironment`). */
object Generators:

  /** Sane targets of a size the environment can meet. */
  val targets: Gen[Targets] =
    for
      known <- Gen.choose(0, 30)
      established <- Gen.choose(0, known)
      active <- Gen.choose(0, established)
      root <- Gen.choose(0, known)
    yield Targets
      .validated(root, known, established, active, 0, 0, 0)
      .getOrElse(Targets.zero)

  private val demotion: Gen[Demotion] = Gen.frequency(
    4 -> Demotion.Noop,
    2 -> Demotion.ToWarm,
    2 -> Demotion.ToCooling,
    3 -> Demotion.ToCold
  )

  private val delay: Gen[Delay] =
    Gen.frequency(5 -> Delay.NoDelay, 3 -> Delay.ShortDelay, 2 -> Delay.LongDelay)

  private val script: Gen[PeerScript] =
    for
      count <- Gen.choose(1, 3)
      fails <- Gen.listOfN(count, Gen.frequency(17 -> false, 3 -> true))
      steps <- Gen
        .nonEmptyListOf(for d <- demotion; w <- delay yield ScriptStep(d, w))
        .map(_.take(4))
        // a connection that is going is released soon after (the manager does not hold it for ever)
        .map(
          _.flatMap(step =>
            if step.demotion == Demotion.ToCooling then
              List(step, ScriptStep(Demotion.ToCold, Delay.NoDelay))
            else List(step)
          )
        )
    yield
      // the last step repeats for ever, so a script ends in a step that does nothing (a connection
      // that drops every minute for ever is not a case to study)
      PeerScript(fails.toVector, (steps :+ ScriptStep(Demotion.Noop, Delay.NoDelay)).toVector)

  private def timed[A](first: Gen[A], horizon: FiniteDuration): Gen[Timed[A]] =
    for
      initial <- first
      changes <- Gen.choose(0, 3)
      later <- Gen.listOfN(
        changes,
        for
          minutes <- Gen.choose(5, horizon.toMinutes.toInt - 5)
          value <- first
        yield minutes.minutes -> value
      )
    yield Timed(initial, later.sortBy(_._1).distinctBy(_._1).toVector)

  private def groups(peers: Int, trustable: Gen[Boolean]): Gen[List[RootGroupSpec[Peer]]] =
    if peers == 0 then Gen.const(Nil)
    else
      Gen.choose(0, 3).flatMap { count =>
        Gen.listOfN(
          count,
          for
            size <- Gen.choose(1, 6)
            members <- Gen.listOfN(size, Gen.choose(0, peers - 1))
            flags <- Gen.listOfN(members.size, trustable)
            hot <- Gen.choose(1, 3)
            warm <- Gen.choose(hot, 5)
          yield RootGroupSpec(
            hot,
            warm,
            members.distinct
              .zip(flags)
              .map((p, t) =>
                p -> RootConfig(
                  false,
                  DiffusionMode.InitiatorAndResponder,
                  Provenance.Outbound,
                  t
                )
              )
          )
        )
      }

  /** An environment without bootstrap peers (the Praos mode of the reference's tests). */
  val environment: Gen[Env] =
    for
      peers <- Gen.choose(0, 24)
      scripts <- Gen.listOfN(peers, script)
      local <- groups(peers, Gen.const(false))
      public <- Gen.someOf(0 until peers).map(_.toSet)
      ts <- timed(targets, 6.hours)
      seed <- Gen.long
    yield Env(
      peers,
      scripts.zipWithIndex.map((s, i) => i -> s).toMap,
      local,
      public,
      Set.empty,
      useBootstrap = false,
      ts,
      Timed(LedgerStateJudgement.YoungEnough, Vector.empty),
      seed
    )

  /** An environment with bootstrap peers and a ledger judgement that changes. */
  val bootstrapEnvironment: Gen[Env] =
    for
      base <- environment.suchThat(_.peers >= 4)
      local <- groups(base.peers, Gen.frequency(1 -> true, 1 -> false))
      bootstrap <- Gen.someOf(0 until base.peers).map(_.toSet).suchThat(_.nonEmpty)
      judgement <- timed(
        Gen.oneOf(LedgerStateJudgement.TooOld, LedgerStateJudgement.YoungEnough),
        6.hours
      )
    yield base.copy(
      localRoots = local,
      bootstrapPeers = bootstrap,
      useBootstrap = true,
      judgement = judgement
    )
