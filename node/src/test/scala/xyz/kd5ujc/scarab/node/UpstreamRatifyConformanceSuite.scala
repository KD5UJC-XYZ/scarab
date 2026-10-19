// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.ledger.{
  CommitteeAuth,
  Credential,
  DRep,
  DRepState,
  GovActionState,
  Ratify,
  RatifyState,
  state as S
}
import xyz.kd5ujc.scarab.models.runtime.{Codec, Node}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, Wire}
import xyz.kd5ujc.scarab.storage.haskell.state.NodeOrders.given

import HaskellStateCodecs.given

/** The actual RATIFY STS and three independent upstream approval properties. */
class UpstreamRatifyConformanceSuite extends FunSuite:
  private val base = Path.of("node/src/test/resources/upstream/conway-ratify")
  private given S.ProtVer = S.ProtVer(S.Word64.fromUnsigned(9), S.Word64.zero)

  private def node(id: String, name: String): Node =
    Cbor
      .decode(
        Bytes.fromArray(Files.readAllBytes(base.resolve(s"cases/$id/$name.cbor"))),
        Cbor.Limits(allowFloats = true)
      )
      .fold(e => fail(e.message), identity)

  private def read[A](n: Node, codec: Codec[A]): A =
    codec.decode(n).fold(e => fail(e.render), identity)

  private def environment(id: String): Ratify.Env =
    val fields = node(id, "environment").value match
      case Value.Arr(xs) if xs.size == 8 => xs
      case other => fail(s"expected all eight native RATIFY environment fields, got $other")
    // Instant stake is retained and decoded, though the voting predicates read the already
    // computed DRep/pool distributions. These are the same typed conversions as ConwayPulser.
    val _ = read(fields(0), summon[Codec[S.ConwayInstantStake]])
    val pools = read(fields(1), summon[Codec[S.PoolDistr]])
    val dreps = read(fields(2), Wire.sortedMapOf(summon[Codec[DRep]], summon[Codec[S.CompactCoin]]))
    val drepState =
      read(fields(3), Wire.sortedMapOf(summon[Codec[Credential]], summon[Codec[DRepState]]))
    val epoch = read(fields(4), summon[Codec[S.EpochNo]])
    val committee = read(fields(5), summon[Codec[S.CommitteeState]])
    val accounts = read(fields(6), summon[Codec[S.ConwayAccounts]])
    val stakePools = read(
      fields(7),
      Wire.mapOf(summon[Codec[S.KeyHash[S.StakePool]]], summon[Codec[S.StakePoolState]])
    )
    Ratify.Env(
      dreps.view.mapValues(_.toBigInt).toMap,
      drepState,
      pools.distribution.iterator
        .map((key, value) => key.bytes -> value.totalPoolStake.toBigInt)
        .toMap,
      pools.totalActiveStake.value.value,
      epoch.toBigInt,
      S.FlatView.stakeMap(committee.creds) {
        case S.CommitteeAuthorization.CommitteeHotCredential(hot) =>
          CommitteeAuth.Hot(S.FlatView.credBack(hot))
        case S.CommitteeAuthorization.CommitteeMemberResigned(anchor) =>
          CommitteeAuth.Resigned(anchor)
      },
      S.FlatView.stakeMap(accounts.states)(S.FlatView.conwayAccount),
      S.FlatView.pools(0, stakePools)
    )

  private def signal(id: String): Vector[GovActionState] =
    read(node(id, "signal"), Wire.seqOf(summon[Codec[GovActionState]]))

  private def state(id: String, name: String): RatifyState =
    read(node(id, name), summon[Codec[RatifyState]])

  (500201 to 500204).foreach { seed =>
    test(s"native RATIFY state and ordered signal match at seed $seed") {
      val id = s"sts-sized5-$seed"
      val before = state(id, "state")
      val expected = state(id, "implementation-state")
      assertEquals(node(id, "context").value, Value.Null)
      assertEquals(
        Files.readString(base.resolve(s"cases/$id/result.txt")),
        "implementation-accepted\nagda-accepted\n"
      )
      assertEquals(Ratify.transition(environment(id), before, signal(id)), expected)
    }
  }

  Vector(
    ("DRep", 500301, Ratify.drepAccepted),
    ("SPO", 500401, Ratify.spoAccepted),
    ("CC", 500501, Ratify.committeeAccepted)
  ).foreach { (role, firstSeed, accepts) =>
    (firstSeed to firstSeed + 1).foreach { seed =>
      test(s"original RATIFY $role approval booleans match at seed $seed") {
        val id = s"${role.toLowerCase}-$seed"
        assertEquals(Files.readString(base.resolve(s"cases/$id/predicate.txt")), role)
        val native = read(node(id, "implementation-booleans"), Wire.seqOf(Codec.bool))
        val agda = read(node(id, "agda-booleans"), Wire.seqOf(Codec.bool))
        val inputs = signal(id)
        assertEquals(native.size, inputs.size)
        assertEquals(agda, native, "original independent formal predicate results")
        val env = environment(id)
        val before = state(id, "state")
        assertEquals(inputs.map(accepts(env, before.enact, _)), native)
      }
    }
  }

  test("every generated native input and result remains pinned") {
    val manifest = parse(Files.readString(base.resolve("manifest.json")))
      .fold(e => fail(e.message), identity)
      .hcursor
    val rows = manifest.get[Vector[Json]]("cases").fold(e => fail(e.message), identity)
    val expectedIds = (500201 to 500204).map(seed => s"sts-sized5-$seed").toSet ++
      Vector("drep" -> 500301, "spo" -> 500401, "cc" -> 500501).flatMap { (role, first) =>
        (first to first + 1).map(seed => s"$role-$seed")
      }
    assertEquals(rows.size, expectedIds.size)
    assertEquals(rows.map(_.hcursor.get[String]("id").toOption).toSet, expectedIds.map(Some(_)))
    rows.foreach { row =>
      val cursor = row.hcursor
      val id = cursor.get[String]("id").fold(e => fail(e.message), identity)
      assertEquals(cursor.get[Int]("upstreamExitCode").toOption, Some(0))
      val files = cursor.get[Map[String, Json]]("files").fold(e => fail(e.message), identity)
      val common = Set("environment.cbor", "state.cbor", "signal.cbor")
      val expectedFiles =
        if id.startsWith("sts-") then
          common ++ Set("implementation-state.cbor", "context.cbor", "result.txt")
        else common ++ Set("implementation-booleans.cbor", "agda-booleans.cbor", "predicate.txt")
      assertEquals(files.keySet, expectedFiles)
      files.foreach { (name, descriptor) =>
        val bytes = Files.readAllBytes(base.resolve(s"cases/$id/$name"))
        val sha = MessageDigest
          .getInstance("SHA-256")
          .digest(bytes)
          .map(b => f"${b & 0xff}%02x")
          .mkString
        assertEquals(descriptor.hcursor.get[String]("sha256").toOption, Some(sha), s"$id/$name")
        assertEquals(
          descriptor.hcursor.get[Int]("bytes").toOption,
          Some(bytes.length),
          s"$id/$name"
        )
      }
    }
    val starts = (500201 to 500204).map(seed => state(s"sts-sized5-$seed", "state"))
    assertEquals(starts.count(_.delayed), 3, "original delayed states must not be reset")
  }
