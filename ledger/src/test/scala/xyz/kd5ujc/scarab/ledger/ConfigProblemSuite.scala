// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.time.Instant
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.Word64

class ConfigProblemSuite extends FunSuite:
  test("genesis syntax preserves the JSON parser failure") {
    Genesis.shelley("{") match
      case Left(ConfigError(ConfigProblem.JsonSyntax("shelley genesis", cause))) =>
        assert(cause.underlying != null)
      case other => fail(s"unexpected $other")
  }

  test("genesis credential decoding retains the hex cause") {
    val decoded = Bytes.fromHex("zz").left.map(e => ConfigError(ConfigProblem.Hex(e)))
    assertEquals(GenesisState.hex28("zz").map(_ => ()), decoded.map(_ => ()))
  }

  test("unknown credential and DRep presentations are distinct cases") {
    assertEquals(
      GenesisState.credential("invalid"),
      Left(ConfigError(ConfigProblem.Credential("invalid")))
    )
    assertEquals(GenesisState.drep("invalid"), Left(ConfigError(ConfigProblem.DRep("invalid"))))
  }

  test("invalid epoch geometry retains the field and value") {
    assertEquals(
      EpochGeometry.checked(Instant.EPOCH, 0, 100, TestGeometry.k(1), TestGeometry.f("1")),
      Left(ConfigError(ConfigProblem.NonPositive("slot length", BigDecimal(0))))
    )
    assertEquals(
      EpochGeometry.checked(Instant.EPOCH, 1000, 4, TestGeometry.k(1), TestGeometry.f("1")),
      Left(ConfigError(ConfigProblem.EpochTooShort(4, Word64.fromUnsigned(4))))
    )
  }
