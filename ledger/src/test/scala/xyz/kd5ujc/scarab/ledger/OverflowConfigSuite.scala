// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.json.{FromJson, JsonValue, NativeJson, Scientific}

/** The configuration and parsing sites where the reference wraps a value and the node refuses it or
  * computes it exactly instead (`docs/compatibility/overflow-improvements.md`): below each boundary
  * the result is the reference's, at and above it a typed refusal, never a wrapped value.
  */
class OverflowConfigSuite extends FunSuite:
  private val modulus = BigInt(1) << 64

  // ---- overflow-improvements.md#byron-genesis-words

  private val byronBase =
    Files.readString(Path.of("fixtures/devnet/lsq/genesis/byron-genesis.json"))

  private def byron(from: String, to: String): Either[ConfigError, ByronGenesis] =
    assert(byronBase.contains(from), from)
    ByronGenesisFile.parse(Bytes.fromArray(byronBase.replace(from, to).getBytes("UTF-8")), None)

  private def refusedAs(field: ByronGenesisField, value: BigInt, bits: Int)(
      read: Either[ConfigError, ByronGenesis]
  ): Unit =
    assertEquals(
      read.left.map(_.problem),
      Left(ConfigProblem.ByronGenesisValue(field, ByronFieldError.OutOfRange(value, bits)))
    )

  test("a Byron genesis word at its maximum is read; past it or below 0 it is refused") {
    val magic = "\"protocolMagic\": 1082026"
    assertEquals(
      byron(magic, "\"protocolMagic\": 4294967295").map(_.protocolMagic),
      Right(4294967295L)
    )
    refusedAs(ByronGenesisField.ProtocolMagic, BigInt(4294967296L), 32)(
      byron(magic, "\"protocolMagic\": 4294967296")
    )
    refusedAs(ByronGenesisField.ProtocolMagic, -1, 32)(byron(magic, "\"protocolMagic\": -1"))
    assertEquals(byron("\"k\": 5", "\"k\": 0").map(_.securityParam), Right(BigInt(0)))
    refusedAs(ByronGenesisField.K, -1, 64)(byron("\"k\": 5", "\"k\": -1"))
    val script = "\"scriptVersion\": 0"
    assertEquals(
      byron(script, "\"scriptVersion\": 65535").map(_.params.scriptVersion),
      Right(BigInt(65535))
    )
    refusedAs(ByronGenesisField.ScriptVersion, 65536, 16)(byron(script, "\"scriptVersion\": 65536"))
    val unlock = "\"unlockStakeEpoch\": \"18446744073709551615\""
    assertEquals(byron(unlock, unlock).map(_.params.unlockStakeEpoch), Right(modulus - 1))
    refusedAs(ByronGenesisField.UnlockStakeEpoch, modulus, 64)(
      byron(unlock, "\"unlockStakeEpoch\": \"18446744073709551616\"")
    )
    refusedAs(ByronGenesisField.UnlockStakeEpoch, -1, 64)(
      byron(unlock, "\"unlockStakeEpoch\": \"-1\"")
    )
  }

  // ---- overflow-improvements.md#epoch-geometry-windows

  test("a genesis whose doubled stability window reaches 2^64 is refused; below it, read") {
    // at f = 1 twice the stability window is 6k: k = 3074457345618258602 gives 2^64 - 4
    val k = BigInt("3074457345618258602")
    def geometry(k: BigInt) =
      EpochGeometry.checked(
        java.time.Instant.EPOCH,
        1000,
        432000,
        TestGeometry.k(k.toLong),
        TestGeometry.f(Rational.one)
      )
    val below = geometry(k).fold(e => fail(e.message), identity)
    assertEquals(below.stabilityWindow.toBigInt, 3 * k)
    assertEquals(below.randomnessStabilisationWindow.toBigInt, 4 * k)
    assertEquals(
      geometry(k + 1).left.map(_.problem),
      Left(ConfigProblem.OutOfRange("genesis: twice the stability window", 6 * (k + 1), 64))
    )
  }

  // ---- overflow-improvements.md#json-number-exponents

  private def number(text: String): Scientific =
    NativeJson.decodeStrict(Bytes.fromArray(text.getBytes("US-ASCII"))) match
      case Right(JsonValue.Number(n)) => n
      case other                      => fail(s"$text: $other")

  test("a JSON exponent is exact where the reference's machine Int wraps it") {
    assertEquals(number("1e9223372036854775807").base10Exponent, BigInt(Long.MaxValue))
    assertEquals(number("1e9223372036854775808").base10Exponent, BigInt(Long.MaxValue) + 1)
    assertEquals(number("1e18446744073709551616").base10Exponent, modulus)
    assertEquals(number("1.5e-9223372036854775808").base10Exponent, BigInt(Long.MinValue) - 1)
  }

  test("a number whose exponent the reference wraps into range is refused as out of range") {
    def word64(text: String) =
      FromJson.word64(io.circe.parser.parse(text).fold(e => fail(e.message), identity))
    assertEquals(word64("1e20").map(_.toBigInt), Left(json.NumberError.NotWord64(number("1e20"))))
    assertEquals(word64("1e19").map(_.toBigInt), Right(BigInt(10).pow(19)))
    // the reference reads the exponent 2^64 as 0, and so 1
    assert(word64("1e18446744073709551616").isLeft)
    assert(word64("1e18446744073709551615").isLeft)
  }
