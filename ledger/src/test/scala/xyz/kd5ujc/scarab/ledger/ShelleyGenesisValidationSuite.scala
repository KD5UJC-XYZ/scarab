// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.data.NonEmptyList
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.ShelleyGenesisValidation.{Input, ValidationErr}
import xyz.kd5ujc.scarab.ledger.state.Word64

/** `validateGenesis` (cardano-ledger f649f975, `Shelley/Genesis.hs:673-762`). The expected minimum
  * epoch lengths are IEEE double results, computed independently as
  * `ceil(float((3 * k) % 2**64) / float(Fraction(f))) % 2**64`.
  */
class ShelleyGenesisValidationSuite extends FunSuite:
  private def word(n: BigInt): Word64 =
    Word64.fromBigInt(state.VocabError.Kind.SlotNo, n).fold(e => fail(e.toString), identity)

  private val maxWord = BigInt(2).pow(64) - 1

  /** The mainnet values: epoch 432000, k 2160, f 1/20, 62 KES evolutions, quorum 5 of 7. */
  private val mainnet = Input(
    word(432000),
    TestGeometry.k(2160),
    TestGeometry.f("0.05"),
    word(62),
    word(5),
    word(7)
  )

  private def epochCheck(epochLength: BigInt, k: BigInt, f: Rational) =
    ShelleyGenesisValidation.validate(
      mainnet.copy(
        epochLength = word(epochLength),
        securityParam = TestGeometry.k(word(k).bits),
        activeSlotsCoeff = TestGeometry.f(f)
      )
    )

  test("the mainnet values pass every check") {
    assertEquals(ShelleyGenesisValidation.validate(mainnet), Right(()))
  }

  test("the epoch must be at least ceiling(3k/f) long, computed in Double") {
    // mainnet: 3k/f = 129600 exactly
    assertEquals(epochCheck(129600, 2160, Rational(1, 20)), Right(()))
    assertEquals(
      epochCheck(129599, 2160, Rational(1, 20)),
      Left(
        NonEmptyList.one(
          ValidationErr.EpochNotLongEnough(word(129599), word(2160), Rational(1, 20), word(129600))
        )
      )
    )
    // 21 / 0.7 is 30 exactly, but 30.000000000000004 in Double: the reference asks for 31
    assertEquals(
      epochCheck(30, 7, Rational(7, 10)),
      Left(
        NonEmptyList.one(
          ValidationErr.EpochNotLongEnough(word(30), word(7), Rational(7, 10), word(31))
        )
      )
    )
    assertEquals(epochCheck(31, 7, Rational(7, 10)), Right(()))
  }

  test("3 * k wraps in Word64 before the division, and the ceiling wraps in Word64") {
    // k = ceiling(2^64 / 3): 3k wraps to 2, and 2 / 0.3 rounds up to 7
    val k = BigInt("6148914691236517206")
    assertEquals(epochCheck(7, k, Rational(3, 10)), Right(()))
    assertEquals(
      epochCheck(6, k, Rational(3, 10)),
      Left(
        NonEmptyList.one(
          ValidationErr.EpochNotLongEnough(word(6), word(k), Rational(3, 10), word(7))
        )
      )
    )
    // the stability window of the same k is computed in Rational, without the early wrap
    val geometry = TestGeometry(java.time.Instant.EPOCH, 1000, 7, 1, "1")
      .copy(
        securityParam = TestGeometry.k(word(k).bits),
        activeSlotsCoeff = TestGeometry.f(Rational(3, 10))
      )
    assertEquals(geometry.stabilityWindow.toBigInt, BigInt("6148914691236517212"))
    // k = 2^64 - 1, f = 10^-19: the Double quotient is a multiple of 2^64, so the minimum is 0
    assertEquals(epochCheck(0, maxWord, Rational(1, BigInt(10).pow(19))), Right(()))
  }

  test("at most 64 KES evolutions, the periods of Sum6KES") {
    assertEquals(
      ShelleyGenesisValidation.validate(mainnet.copy(maxKESEvolutions = word(64))),
      Right(())
    )
    assertEquals(
      ShelleyGenesisValidation.validate(mainnet.copy(maxKESEvolutions = word(65))),
      Left(NonEmptyList.one(ValidationErr.MaxKESEvolutionsUnsupported(word(65), 64)))
    )
  }

  test("the update quorum must exceed half the genesis nodes, unless there are none") {
    def quorum(q: Int, nodes: Int) =
      ShelleyGenesisValidation.validate(
        mainnet.copy(updateQuorum = word(q), genesisNodes = word(nodes))
      )
    assertEquals(quorum(4, 7), Right(()))
    assertEquals(
      quorum(3, 7),
      Left(NonEmptyList.one(ValidationErr.QuorumTooSmall(word(3), word(3), word(7))))
    )
    assertEquals(
      quorum(0, 1),
      Left(NonEmptyList.one(ValidationErr.QuorumTooSmall(word(0), word(0), word(1))))
    )
    assertEquals(quorum(0, 0), Right(()))
  }

  test("every failed check is reported, in the reference's order") {
    val all = Input(word(1), TestGeometry.k(1), TestGeometry.f("1"), word(65), word(1), word(2))
    assertEquals(
      ShelleyGenesisValidation.validate(all),
      Left(
        NonEmptyList.of(
          ValidationErr.EpochNotLongEnough(word(1), word(1), Rational.one, word(3)),
          ValidationErr.MaxKESEvolutionsUnsupported(word(65), 64),
          ValidationErr.QuorumTooSmall(word(1), word(1), word(2))
        )
      )
    )
  }

  test("the descriptions are describeValidationErr's texts") {
    assertEquals(
      ValidationErr
        .EpochNotLongEnough(word(129599), word(2160), Rational(1, 20), word(129600))
        .description,
      "Epoch length is too low. Your epoch length of EpochSize 129599 does not meet the minimum " +
        "epoch length of EpochSize 129600 required by your choice of parameters for k and f: 2160 " +
        "and 1 % 20. Epochs should be at least 10k/f slots long."
    )
    assertEquals(
      ValidationErr.MaxKESEvolutionsUnsupported(word(65), 64).description,
      "You have specified a 'maxKESEvolutions' higher than that supported by the underlying " +
        "algorithm. You requested 65 but the algorithm supports a maximum of 64"
    )
    assertEquals(
      ValidationErr.QuorumTooSmall(word(3), word(3), word(7)).description,
      "You have specified an 'updateQuorum' which is too small compared to the number of genesis " +
        "nodes. You requested 3, but given 7 genesis nodes 'updateQuorum' must be greater than 3"
    )
  }

  test("Rational and Word64 convert to Double rounding to nearest, ties to even") {
    assertEquals(ShelleyGenesisValidation.rationalToDouble(Rational(1, 20)), 0.05)
    assertEquals(ShelleyGenesisValidation.rationalToDouble(Rational(7, 10)), 0.7)
    assertEquals(ShelleyGenesisValidation.rationalToDouble(Rational(1, 3)), 1.0 / 3)
    assertEquals(ShelleyGenesisValidation.rationalToDouble(Rational(1, BigInt(10).pow(19))), 1e-19)
    // 2^53 + 1 is a tie between 2^53 and 2^53 + 2: even wins; 2^53 + 3 rounds up to 2^53 + 4
    val two53 = BigInt(2).pow(53)
    assertEquals(ShelleyGenesisValidation.rationalToDouble(Rational(two53 + 1)), two53.toDouble)
    assertEquals(
      ShelleyGenesisValidation.rationalToDouble(Rational(two53 + 3)),
      (two53 + 4).toDouble
    )
    assertEquals(ShelleyGenesisValidation.unsignedToDouble(word(maxWord)), 1.8446744073709552e19)
    assertEquals(ShelleyGenesisValidation.ceilingWord64(1.8446744073709552e19).toBigInt, BigInt(0))
    assertEquals(ShelleyGenesisValidation.ceilingWord64(30.000000000000004).toBigInt, BigInt(31))
  }

  test("the real network configurations pass validateGenesis") {
    List("mainnet", "preprod", "preview").foreach { network =>
      val text = Files.readString(Path.of(s"fixtures/$network/genesis/shelley-genesis.json"))
      val c = io.circe.parser.parse(text).fold(e => fail(e.message), identity).hcursor
      def word(name: String) =
        c.downField(name)
          .focus
          .toRight(name)
          .flatMap(json.FromJson.word64(_).left.map(_.message))
          .fold(e => fail(s"$network $e"), identity)
      val g = Genesis.shelley(text).fold(e => fail(e.message), identity).geometry
      val nodes = c.downField("genDelegs").keys.fold(0)(_.size)
      val input = ShelleyGenesisValidation.Input(
        word("epochLength"),
        g.securityParam,
        g.activeSlotsCoeff,
        word("maxKESEvolutions"),
        word("updateQuorum"),
        state.Word64.fromUnsigned(nodes.toLong)
      )
      assertEquals(ShelleyGenesisValidation.validate(input), Right(()), network)
    }
  }
