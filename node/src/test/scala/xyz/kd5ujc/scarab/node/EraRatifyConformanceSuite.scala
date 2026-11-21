// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  Credential,
  DRep as FlatDRep,
  DRepState as FlatDRepState,
  EnactState as FlatEnactState,
  GovAction as FlatGovAction,
  GovActionId,
  GovActionState as FlatGovActionState,
  Proposal,
  RatifyState as FlatRatifyState,
  RewardAccount,
  state as S
}
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayEraAccounts,
  ConwayEraPParams,
  EraAccounts,
  GovActionState,
  GovStateBases,
  GovStateRecord,
  RatifyEnv,
  RatifySignal,
  RatifyState,
  VotingStakePoolDistr
}
import xyz.kd5ujc.scarab.ledger.era.rules.{ConwayEnact, ConwayRatify, EnactSignal}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, Wire}
import xyz.kd5ujc.scarab.storage.haskell.state.NodeOrders.given

import HaskellStateCodecs.given

/** RATIFY and ENACT on the era types (`ConwayRatify`, `ConwayEnact`; cardano-ledger f649f975
  * `Conway/Rules/Ratify.hs`, `Conway/Rules/Enact.hs`) on the upstream Conway cases the repository
  * runs against the flat rules: the four generated RATIFY transitions and the six approval cases of
  * `conway-ratify`, the five RATIFY outcomes of `conway-governance-functional` and the ENACT case
  * of `conway-enact`. Each input is read into the era types as the governance state's record is
  * ([[GovStateRecord]]); the era rule's result is the reference implementation's, read the same
  * way, and, written back to the node's record, the recorded state the flat rule reaches
  * (`UpstreamRatifyConformanceSuite`, `UpstreamGovernanceFunctionalSuite`,
  * `UpstreamEnactConformanceSuite`).
  */
class EraRatifyConformanceSuite extends FunSuite:
  private given S.ProtVer = S.ProtVer(S.Word64.fromUnsigned(9), S.Word64.zero)
  private given ConwayEraPParams[S.Conway] = ConwayEraPParams.conway
  private given ConwayEraAccounts[S.Conway] = EraAccounts.conway

  private def right[A](what: String)(e: Either[?, A]): A = e.fold(x => fail(s"$what: $x"), identity)

  private def node(path: Path): Node =
    Cbor
      .decode(Bytes.fromArray(Files.readAllBytes(path)), Cbor.Limits(allowFloats = true))
      .fold(e => fail(e.message), identity)

  private def read[A](n: Node, codec: Codec[A]): A =
    codec.decode(n).fold(e => fail(e.render), identity)

  /** The eight fields of a native RATIFY environment, at the era's types: the pool distribution's
    * stakes and total as the voting stake ([[VotingStakePoolDistr]]), the DReps' distribution and
    * states read from the node's records.
    */
  private def environment(n: Node): RatifyEnv[S.Conway] =
    val fields = n.value match
      case Value.Arr(xs) if xs.size == 8 => xs
      case other                         => fail(s"not the eight RATIFY environment fields: $other")
    val instant = read(fields(0), summon[Codec[S.ConwayInstantStake]])
    val pools = read(fields(1), summon[Codec[S.PoolDistr]])
    val dreps =
      read(fields(2), Wire.sortedMapOf(summon[Codec[FlatDRep]], summon[Codec[S.CompactCoin]]))
    val drepState =
      read(fields(3), Wire.sortedMapOf(summon[Codec[Credential]], summon[Codec[FlatDRepState]]))
    val stakePools = read(
      fields(7),
      Wire.mapOf(summon[Codec[S.KeyHash[S.StakePool]]], summon[Codec[S.StakePoolState]])
    )
    RatifyEnv[S.Conway](
      instant,
      VotingStakePoolDistr(
        S.Sorted.map(pools.distribution.iterator.map((k, v) => k -> v.totalPoolStake)),
        pools.totalActiveStake
      ),
      right("DReps")(dreps.toVector.traverse((d, c) => Convert.drep(d).map(_ -> c))).toMap,
      right("DRep states")(drepState.toVector.traverse { (c, d) =>
        (
          Convert.credential[S.DRepRole](c),
          S.EpochNo.fromBigInt(d.expiry),
          S.CompactCoin.fromBigInt(d.deposit),
          d.delegs.toVector.traverse(Convert.credential[S.Staking])
        ).mapN((cred, expiry, deposit, delegs) =>
          cred -> S.DRepState(expiry, d.anchor, deposit, S.Sorted.set(delegs))
        )
      }).toMap,
      read(fields(4), summon[Codec[S.EpochNo]]),
      read(fields(5), summon[Codec[S.CommitteeState]]),
      read(fields(6), summon[Codec[S.ConwayAccounts]]),
      S.Sorted.map(stakePools)
    )

  private def flatSignal(n: Node): Vector[FlatGovActionState] =
    read(n, Wire.seqOf(summon[Codec[FlatGovActionState]]))

  private def eraSignal(n: Node): Vector[GovActionState[S.Conway]] =
    flatSignal(n).map(g => right(s"${g.id}")(GovStateRecord.conway.action(g)))

  private def flatState(n: Node): FlatRatifyState = read(n, summon[Codec[FlatRatifyState]])

  private def eraState(n: Node): RatifyState[S.Conway] =
    right("ratify state")(GovStateRecord.ratifyStateFromStored[S.Conway](flatState(n)))

  /** The era rule's state as the node records it, its parameters over `base` 's. */
  private def stored(rs: RatifyState[S.Conway], base: FlatRatifyState): FlatRatifyState =
    right("stored")(
      GovStateRecord.ratifyStateToStored[S.Conway](rs, GovStateBases.uniform(base.enact.params))
    )

  private def transition(dir: Path, before: String, expected: String): Unit =
    val env = environment(node(dir.resolve("environment.cbor")))
    val start = eraState(node(dir.resolve(s"$before.cbor")))
    val signal = RatifySignal(eraSignal(node(dir.resolve("signal.cbor"))))
    val end = right("RATIFY")(ConwayRatify.ratifyTransition(env, start, signal))
    assertEquals(end, eraState(node(dir.resolve(s"$expected.cbor"))), "the reference's state")
    assertEquals(
      stored(end, flatState(node(dir.resolve(s"$before.cbor")))),
      flatState(node(dir.resolve(s"$expected.cbor"))),
      "the node's record of it"
    )

  private val ratify = Path.of("node/src/test/resources/upstream/conway-ratify/cases")

  (500201 to 500204).foreach { seed =>
    test(s"the era's RATIFY reaches the reference's state on conway-ratify sts-sized5-$seed") {
      val dir = ratify.resolve(s"sts-sized5-$seed")
      assertEquals(
        Files.readString(dir.resolve("result.txt")),
        "implementation-accepted\nagda-accepted\n"
      )
      transition(dir, "state", "implementation-state")
    }
  }

  Vector("drep" -> 500301, "spo" -> 500401, "cc" -> 500501).foreach { (role, first) =>
    (first to first + 1).foreach { seed =>
      test(s"the era's $role approvals are the reference's on conway-ratify $role-$seed") {
        val dir = ratify.resolve(s"$role-$seed")
        val native = read(node(dir.resolve("implementation-booleans.cbor")), Wire.seqOf(Codec.bool))
        val env = environment(node(dir.resolve("environment.cbor")))
        val st = eraState(node(dir.resolve("state.cbor")))
        val signal = eraSignal(node(dir.resolve("signal.cbor")))
        val ours = signal.map(gas =>
          role match
            case "drep" => ConwayRatify.dRepAccepted(env, st, gas)
            case "spo"  => ConwayRatify.spoAccepted(env, st, gas)
            case _      => right("committee")(ConwayRatify.committeeAccepted(env, st, gas))
        )
        assertEquals(ours, native)
      }
    }
  }

  private val functional =
    Path.of("node/src/test/resources/upstream/conway-governance-functional/cases")

  Vector(
    "ratify-native-positive",
    "ratify-both-positive",
    "ratify-forward",
    "ratify-reverse",
    "ratify-missing-votes"
  ).foreach { id =>
    test(s"the era's RATIFY reaches the reference's state and approvals on $id") {
      val dir = functional.resolve(id)
      val env = environment(node(dir.resolve("environment.cbor")))
      val st = eraState(node(dir.resolve("state.cbor")))
      val approvals = read(node(dir.resolve("approvals.cbor")), Wire.seqOf(Codec.bool))
      assertEquals(
        eraSignal(node(dir.resolve("signal.cbor"))).flatMap(gas =>
          Vector(ConwayRatify.dRepAccepted(env, st, gas), ConwayRatify.spoAccepted(env, st, gas))
        ),
        approvals
      )
      transition(dir, "state", "implementation-state")
    }
  }

  test("the era's ENACT reaches the reference's enact state on conway-enact 496101") {
    val dir = Path.of("node/src/test/resources/upstream/conway-enact/cases/496101")
    val before = read(node(dir.resolve("state.cbor")), summon[Codec[FlatEnactState]])
    val expected =
      read(node(dir.resolve("implementation-state.cbor")), summon[Codec[FlatEnactState]])
    def era(e: FlatEnactState) =
      right("enact state")(
        GovStateRecord.ratifyStateFromStored[S.Conway](
          FlatRatifyState(e, Vector.empty, Set.empty, false)
        )
      ).rsEnactState
    val (id, action) = node(dir.resolve("signal.cbor")).value match
      case Value.Arr(Vector(i, a)) =>
        (read(i, summon[Codec[GovActionId]]), read(a, summon[Codec[FlatGovAction]]))
      case other => fail(s"not a two-field ENACT signal: $other")
    // the action at the era's types, read as an action of the governance state is
    val typed = right("action")(
      GovStateRecord.conway.action(
        FlatGovActionState(
          id,
          Map.empty,
          Map.empty,
          Map.empty,
          Proposal(BigInt(0), RewardAccount(0, Credential.Key(Bytes(Vector.fill(28)(0)))), action),
          BigInt(0),
          BigInt(0)
        )
      )
    ).gasAction
    val after = right("ENACT")(ConwayEnact.enactmentTransition(era(before), EnactSignal(id, typed)))
    assertEquals(after, era(expected))
  }
