// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}

class GenesisSyncLoadSuite extends FunSuite:
  import BlockFetchPolicy.*
  import ChainSyncJumping.*
  def point(n: Int): Point = Point.Block(UInt64.saturating(n), Bytes.fromArray(Array(n.toByte)))
  def info(n: Int): JumpInfo[Unit] = JumpInfo(Point.Origin, (1 to n).map(point).toVector, ())
  def peer(id: Int, n: Int): Candidate[Int] = Candidate(
    id,
    n,
    Bytes.empty,
    Vector(
      Fragment(None, (1 to n).map(i => Header(Bytes.fromArray(Array(i.toByte)), i, 1)).toVector)
    )
  )

  test("DBF rotates only after chain-selection starvation grace and retains retired flight") {
    val initial = GenesisBlockFetch.State(Vector(1, 2), Some(1), BigInt(0))
    val candidates = Vector(
      peer(1, 3).copy(inFlight = InFlight(1, 1, Set(Bytes.fromArray(Array(1.toByte))))),
      peer(2, 3)
    )
    val before = GenesisBlockFetch.decide(
      Config(),
      initial,
      GenesisBlockFetch.DefaultGracePicos - 1,
      GenesisBlockFetch.Starvation.Ongoing,
      candidates
    )
    assertEquals(before.demoteDynamo, None)
    val after = GenesisBlockFetch.decide(
      Config(),
      initial,
      GenesisBlockFetch.DefaultGracePicos,
      GenesisBlockFetch.Starvation.Ongoing,
      candidates
    )
    assertEquals(after.demoteDynamo, Some(1))
    assertEquals(after.state.current, Some(2))
    assertEquals(after.decisions.collect { case Decision.Request(id, _) => id }.headOption, Some(2))
    assertEquals(candidates.head.inFlight.requests, 1)
  }
  test("DBF chooses best chain then oldest-serving peer, independently of other peer flight") {
    val result = GenesisBlockFetch.decide(
      Config(),
      GenesisBlockFetch.State[Int](),
      0,
      GenesisBlockFetch.Starvation.EndedAt(-1),
      Vector(peer(1, 2), peer(2, 4))
    )
    assertEquals(result.state.current, Some(1))
    val request = result.decisions.collect { case Decision.Request(id, fragments) =>
      id -> fragments.flatMap(_.headers).size
    }
    assertEquals(request, Vector(1 -> 2))
  }
  test("new caught-up peers disengage without changing existing CSJ roles") {
    val first = register(State[Int, Unit](), 1, Point.Origin, false)
    val second = register(first, 2, Point.Origin, true)
    assertEquals(second.peers.head.role, Role.Dynamo[Unit](None, BigInt(-1)))
    assertEquals(second.peers.last.role, Role.Disengaged[Unit](false))
  }
  test("CSJ jump boundary is strict and excludes the not-yet-validated header") {
    val s = update(
      register(register(State[Int, Unit](), 1, Point.Origin, false), 2, Point.Origin, false),
      1,
      info(3)
    )
    assertEquals(next(onRollForward(s, 1, point(3), 4), 2).instruction, Instruction.Wait)
    val sent = onRollForward(s, 1, point(4), 4)
    assertEquals(next(sent, 2).instruction, Instruction.Jump(info(3), false))
  }
  test("rejected jump bisects then promotes earliest dissenting objector") {
    val original = update(register(State[Int, Unit](), 1, Point.Origin, false), 1, info(8))
    val started = next(register(original, 2, Point.Origin, false), 2)
    val rejected = processJump(started.state, 2, info(8), false, false)
    assertEquals(next(rejected.state, 2).instruction, Instruction.Jump(info(4), false))
    val r4 = processJump(next(rejected.state, 2).state, 2, info(4), false, false)
    val r2 = processJump(next(r4.state, 2).state, 2, info(2), false, false)
    val r1 = processJump(next(r2.state, 2).state, 2, info(1), false, false)
    assertEquals(r1.state.peers.last.role, Role.Objector(true, info(8).prefix(0), point(1)))
    assertEquals(next(r1.state, 2).instruction, Instruction.Jump(info(8).prefix(0), true))
  }
  test("started objector is preferred during dynamo removal without restarting its chain") {
    val s = State(
      Vector(
        Peer(1, Role.Dynamo[Unit](None, 0), Some(info(4)), point(4)),
        Peer(2, Role.Jumping[Unit](None, Jumper.Happy(true, None)), None, Point.Origin),
        Peer(3, Role.Objector[Unit](false, info(1), point(2)), Some(info(3)), point(3))
      )
    )
    val removed = remove(s, 1)
    assertEquals(
      removed.peers.find(_.id == 3).map(_.role),
      Some(Role.Dynamo[Unit](None, BigInt(1)))
    )
    assertEquals(next(removed, 3).instruction, Instruction.RunNormally)
  }
