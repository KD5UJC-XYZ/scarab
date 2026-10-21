// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{BlockFailure, Header, ValidationError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught

class InvalidBlocksSuite extends FailsOnUncaught:
  private def header(n: Int): Header = Header.Byron(
    Bytes.empty,
    Bytes(Vector(n.toByte)),
    false,
    0,
    n,
    21600,
    n,
    Some(Bytes(Vector((n - 1).toByte)))
  )
  private def refused(n: Int): ValidationError =
    ValidationError(header(n).point, BlockFailure.EpochPrecedesLedger(n))

  test("every native invalidity report replaces its typed cause and advances the fingerprint") {
    for
      registry <- InvalidBlocks.create[IO]
      _ <- registry.record(header(1), refused(1))
      replacement = ValidationError(header(1).point, BlockFailure.EpochPrecedesLedger(2))
      _ <- registry.record(header(1), replacement)
      state <- registry.snapshot
      found <- registry.lookup(header(1).hash)
    yield
      assertEquals(state.revision, BigInt(2))
      assertEquals(state.entries, Map(header(1).hash -> replacement))
      assertEquals(found, Some(replacement))
  }

  test("a waiter cannot miss an invalid block recorded before it subscribes") {
    TestControl.executeEmbed {
      for
        registry <- InvalidBlocks.create[IO]
        before <- registry.snapshot
        _ <- registry.record(header(1), refused(1))
        after <- registry.awaitChange(before.revision)
      yield assertEquals(after.entries, Map(header(1).hash -> refused(1)))
    }
  }

  test("recording a new invalid block wakes an existing waiter with its complete state") {
    TestControl.executeEmbed {
      for
        registry <- InvalidBlocks.create[IO]
        entered <- Deferred[IO, Unit]
        waiter <- (entered.complete(()) *> registry.awaitChange(0)).start
        _ <- entered.get
        _ <- registry.record(header(2), refused(2))
        state <- waiter.joinWithNever
      yield
        assertEquals(state.revision, BigInt(1))
        assertEquals(state.entries, Map(header(2).hash -> refused(2)))
    }
  }

  test("native garbage collection preserves invalid blocks exactly at the slot cutoff") {
    for
      registry <- InvalidBlocks.create[IO]
      _ <- Vector(1, 2, 3).traverse_(n => registry.record(header(n), refused(n)))
      _ <- registry.pruneBefore(2)
      state <- registry.snapshot
    yield
      assertEquals(state.entries.keySet, Set(header(2).hash, header(3).hash))
      assertEquals(state.revision, BigInt(3))
  }
