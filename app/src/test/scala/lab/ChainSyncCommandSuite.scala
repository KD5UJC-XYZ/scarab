// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.network.{ChainSync, ChainSyncFixtures}
import ChainSync.*

class ChainSyncCommandSuite extends munit.FunSuite:
  private val root =
    if Files.exists(Path.of("fixtures/chain-sync")) then Path.of("fixtures/chain-sync")
    else Path.of("../fixtures/chain-sync")
  ChainSyncCommand.Payloads.foreach { case (name, hash, ntn) =>
    test(s"pinned $name bytes, adapter family and every truncated prefix") {
      val bytes = Bytes.fromArray(Files.readAllBytes(root.resolve(name)))
      assert(ChainSyncCommand.checkPayload(name, bytes, hash, ntn).isRight)
      val wire = Bytes(
        Vector(0x83.toByte, 2.toByte) ++ bytes.value ++ Vector(0x82.toByte, 0x80.toByte, 0.toByte)
      )
      (0 until wire.size).foreach { cut =>
        assertEquals(
          decodePrefix(
            State.NextCanAwait,
            Role.Server,
            Bytes(wire.value.take(cut)),
            ChainSyncFixtures.rawItem
          ),
          DecodeResult.NeedMore,
          s"$name cut=$cut"
        )
      }
      val withSuffix = Bytes(wire.value ++ Vector(0x81.toByte, 0.toByte))
      decodePrefix(State.NextCanAwait, Role.Server, withSuffix, ChainSyncFixtures.rawItem) match
        case DecodeResult.Decoded(Message.RollForward(p, _), consumed) =>
          assertEquals(p, bytes)
          assertEquals(consumed, wire.size)
        case other => fail(s"$other")
      val altered = Bytes((bytes.value.head ^ 1).toByte +: bytes.value.tail)
      assert(ChainSyncCommand.checkPayload(name, altered, hash, ntn).isLeft)
      assert(ChainSyncCommand.checkPayload(name, bytes, hash, !ntn).isLeft)
    }
  }
  test("selftest completes offline with precise false validation/runtime flags") {
    val report = ChainSyncCommand.verify(root)
    Vector(
      "referenceRuntimeChecked=false",
      "cardanoHeaderValidated=false",
      "cardanoBlockValidated=false",
      "ledgerRollbackImplemented=false",
      "livePeerChecked=false",
      "transportExchanges=0",
      "finalState=Done"
    ).foreach(s => assert(report.contains(s)))
  }
  test("seeded fragment accumulation preserves exact large payload boundaries") {
    val bytes = Bytes.fromArray(Files.readAllBytes(root.resolve("ntc-block-conway.cbor")))
    val wire = Bytes(
      Vector(0x83.toByte, 2.toByte) ++ bytes.value ++ Vector(0x82.toByte, 0x80.toByte, 0.toByte)
    )
    val rng = new scala.util.Random(1010L)
    var offset = 0
    var pending = Bytes.empty
    while offset < wire.size do
      val end = math.min(offset + rng.nextInt(127) + 1, wire.size)
      pending = Bytes(pending.value ++ wire.value.slice(offset, end))
      offset = end
      val result = decodePrefix(State.NextCanAwait, Role.Server, pending, ChainSyncFixtures.rawItem)
      if offset < wire.size then assertEquals(result, DecodeResult.NeedMore)
      else
        result match
          case DecodeResult.Decoded(Message.RollForward(p, _), consumed) =>
            assertEquals(p, bytes); assertEquals(consumed, wire.size)
          case other => fail(s"$other")
  }
