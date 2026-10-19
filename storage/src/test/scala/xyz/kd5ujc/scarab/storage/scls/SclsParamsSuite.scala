// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.consensus.state.EraLedgerState
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.ledger.state.{ConwayNewEpochState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellLedgerFile, Params}
import xyz.kd5ujc.scarab.storage.scls.SclsRecord.Namespace

class SclsParamsSuite extends FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def bytes(hex: String): Bytes = right(Bytes.fromHex(hex))

  private val canonical = Files
    .readString(Path.of("fixtures/scls/conway-4593/entries.tsv"))
    .linesIterator
    .collect {
      case line if line.startsWith(Namespace.PParams.name + "\t") =>
        line.split("\t", -1) match
          case Array(_, key, value) => bytes(key) -> bytes(value)
          case _                    => fail("invalid reference PParams row")
    }
    .toMap

  private val current =
    val file = right(
      HaskellLedgerFile.decode(
        Bytes.wrap(Files.readAllBytes(Path.of("fixtures/haskell-state/conway-4593/state")))
      )
    )
    val ext = file.ext.getOrElse(fail("expected complete reference ledger state"))
    ext.ledgerState.state match
      case EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, ledgerState) =>
        ConwayNewEpochState.gov.get(ledgerState.nes).curPParams
      case _ => fail("expected Conway reference state")

  private val currentBytes = canonical.getOrElse(
    Bytes.wrap("curr".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
    fail("reference current PParams")
  )

  test("typed state projection matches the independently exported Haskell current PParams") {
    val projected = right(Params.projectConway(current))
    assertEquals(Params.conwayPParamsCodec.encodeUnchecked(projected), currentBytes)
  }

  test("all canonical fields replace the explicit base while absent-era fields survive") {
    val legacy = Params.empty.copy(
      d = Rational(BigInt(1), BigInt(3)),
      extraEntropy = Nonce.Hash(Bytes.wrap(Array.fill[Byte](32)(7))),
      minUTxOValue = 123,
      coinsPerUTxOWord = 456
    )
    val imported = right(Params.conwayPParamsCodec.decodeBytes(currentBytes))
    val rebuilt = Params.overlayConway(imported, legacy)
    assertEquals(
      Params.conwayCodec.encodeUnchecked(rebuilt),
      Params.conwayCodec.encodeUnchecked(current)
    )
    assertEquals(rebuilt.d, legacy.d)
    assertEquals(rebuilt.extraEntropy, legacy.extraEntropy)
    assertEquals(rebuilt.minUTxOValue, legacy.minUTxOValue)
    assertEquals(rebuilt.coinsPerUTxOWord, legacy.coinsPerUTxOWord)
    assertEquals(right(Params.projectConway(rebuilt)), imported)
  }

  test("canonical unbounded execution units survive overlay without snapshot narrowing") {
    val raw = Files
      .readString(Path.of("fixtures/scls/namespace-value-vectors/conway-f649.tsv"))
      .linesIterator
      .collectFirst {
        case line if line.startsWith("pparams-unbounded-execution-unit-natural\t") =>
          line.split("\t", -1) match
            case Array(_, _, value) => bytes(value)
            case _                  => fail("invalid reference Natural vector")
      }
      .getOrElse(fail("reference unbounded Natural vector"))
    val rebuilt = Params.overlayConway(right(Params.conwayPParamsCodec.decodeBytes(raw)), current)
    assert(rebuilt.maxTxExUnits.mem > BigInt(Long.MaxValue))
    assertEquals(
      Params.conwayPParamsCodec.encodeUnchecked(right(Params.projectConway(rebuilt))),
      raw
    )
    assert(Params.conwayCodec.check(rebuilt).isDefined)
  }

  test("projection refuses invalid represented fields and preserves legitimate zero values") {
    assert(Params.projectConway(current.copy(minFeeA = -1)).isLeft)
    val zero = current.copy(minFeeA = 0, minFeeB = 0)
    val rebuilt = Params.overlayConway(right(Params.projectConway(zero)), current)
    assertEquals(rebuilt.minFeeA, BigInt(0))
    assertEquals(rebuilt.minFeeB, BigInt(0))
  }
