// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.Era

/** The PPUP quorum counts proposals by their values: `votedFuturePParams` keys its count by
  * `PParamsUpdate era` (`Rules/Ppup.hs:214-220`, cardano-ledger f649f975), whose derived `Ord`
  * compares each field by its decoded value, so equal values written differently are one update.
  * Both voting paths are covered: the bootstrap ledger's (`LiteLedger.voted`) and the era rules'
  * (`UpdateChecks.voted`). Each update is read from a body, so every encoding here is one the body
  * decoder accepts.
  */
class PpupVoteSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private val genesis = Genesis
    .shelley(Files.readString(Path.of("fixtures/preview/genesis/shelley-genesis.json")))
    .fold(e => fail(e.message), identity)
  private val lite = LiteLedger.fromGenesis(genesis.raw).fold(e => fail(e.message), identity)
  private val geometry = genesis.geometry
  private val delegates = lite.genDelegs.keys.toVector.sortBy(_.hex)

  /** The update of one delegate in the body `{6: [{delegate: fields}, 0]}` of `era`. */
  private def proposed(era: Era, fields: String): ParamUpdate =
    val body = hex("a10682a1581c" + delegates(0).hex + fields + "00")
    LiteLedger.effects(body, era) match
      case Right(Vector(LiteLedger.Effect.Proposal(updates, _))) =>
        updates.values.toVector match
          case Vector(u) => u
          case other     => fail(s"expected one update, got $other")
      case other => fail(s"expected one proposal, got $other")

  private def votes(updates: Vector[ParamUpdate]): Map[Bytes, ParamUpdate] =
    delegates.zip(updates).toMap

  // d = 1/2 (key 12) written five ways: reduced, unreduced, with two negative integers, and with
  // wider heads than needed; each a `UnitInterval` of 1 % 2 (`decodeRationalWithTag`,
  // `Plain.hs:159-167`).
  private val halves = Vector(
    "d81e820102",
    "d81e820204",
    "d81e822021",
    "d81e8218011802",
    "d81e821a000000011b0000000000000002"
  ).map(d => proposed(Era.Shelley, "a10c" + d))

  test("five proposals of d = 1/2 in five encodings reach the quorum of 5 together") {
    assertEquals(halves.distinct.size, 5)
    assertEquals(lite.quorum, 5)
    val ticked = LiteLedger.tick(
      lite.copy(curProposals = votes(halves)),
      geometry.firstSlot(1) + 5,
      geometry
    )
    assertEquals(ticked.map(_.params.d), Right(Rational(1, 2)))
  }

  test("four proposals of d = 1/2 and one of d = 1/3 do not reach the quorum") {
    val third = proposed(Era.Shelley, "a10cd81e820103")
    val ticked = LiteLedger.tick(
      lite.copy(curProposals = votes(halves.take(4) :+ third)),
      geometry.firstSlot(1) + 5,
      geometry
    )
    assertEquals(ticked.map(_.params.d), Right(lite.params.d))
  }

  test("the bootstrap ledger: two byte groups of one value are one winner, two values are none") {
    assertEquals(
      LiteLedger.voted(votes(halves.take(4)), lite.params, 2).map(_.map(_.d)),
      Right(Some(Rational(1, 2)))
    )
    val third = proposed(Era.Shelley, "a10cd81e820103")
    val thirdWide = proposed(Era.Shelley, "a10cd81e8218011803")
    assertEquals(
      LiteLedger.voted(votes(Vector(halves(0), halves(1), third, thirdWide)), lite.params, 2),
      Right(None)
    )
  }

  // An Alonzo update of the minimum fee (0), the cost model of PlutusV1 (18), the prices (19) and
  // the largest execution units of a transaction (20).
  private val alonzo = TestLedger.params(6)
  private def costModel(first: String) = "a10098a6" + first + "1864" * 165

  private val canonical = proposed(
    Era.Alonzo,
    "a4" + "001903e8" + "12" + costModel("1864") +
      "1382d81e820105d81e820a01" + "14821a009896801b00000002540be400"
  )

  // The same values: wide heads, unreduced rationals, and the cost model as a map that names
  // PlutusV1 twice, which below decoder version 9 keeps the last (`decodeMapByKey`,
  // `Decoder.hs:810-824`).
  private val rewritten = proposed(
    Era.Alonzo,
    "a4" + "001a000003e8" + "12" + "a200" + "98a6" + "00" * 166 + costModel("190064").drop(2) +
      "1382d81e82020ad81e82181402" + "14821b00000000009896801b00000002540be400"
  )

  private val wide = proposed(
    Era.Alonzo,
    "a4" + "001b00000000000003e8" + "12" + costModel("1864") +
      "1382d81e820105d81e821a0000000a01" + "14821a009896801b00000002540be400"
  )

  private val otherFee = proposed(
    Era.Alonzo,
    "a4" + "001903e9" + "12" + costModel("1864") +
      "1382d81e820105d81e820a01" + "14821a009896801b00000002540be400"
  )

  test("the era rules: three encodings of one Alonzo update reach a quorum of 3 together") {
    assertEquals(Vector(canonical, rewritten, wide).distinct.size, 3)
    val voted = UpdateChecks.voted[xyz.kd5ujc.scarab.ledger.state.Alonzo](
      alonzo,
      votes(Vector(canonical, rewritten, wide)),
      3
    )
    assertEquals(voted.map(_.minFeeA), Some(BigInt(1000)))
    assertEquals(voted.map(_.costModels.get(0)), Some(Some(Vector.fill(166)(BigInt(100)))))
    assertEquals(voted.map(p => (p.priceMem, p.priceSteps)), Some((Rational(1, 5), Rational(10))))
    assertEquals(voted.map(_.maxTxExUnits), Some(ExUnits(10000000, 10000000000L)))
  }

  test("the era rules: a different fee is a different update, and two byte groups are one") {
    assertEquals(
      UpdateChecks.voted[xyz.kd5ujc.scarab.ledger.state.Alonzo](
        alonzo,
        votes(Vector(canonical, rewritten, otherFee)),
        3
      ),
      None
    )
    assertEquals(
      UpdateChecks
        .voted[xyz.kd5ujc.scarab.ledger.state.Alonzo](
          alonzo,
          votes(Vector(canonical, canonical, rewritten, rewritten)),
          2
        )
        .map(_.minFeeA),
      Some(BigInt(1000))
    )
    assertEquals(
      UpdateChecks.voted[xyz.kd5ujc.scarab.ledger.state.Alonzo](
        alonzo,
        votes(Vector(canonical, rewritten, otherFee, otherFee)),
        2
      ),
      None
    )
  }

  test("a stored value this build cannot read is counted by its bytes and blocks no other update") {
    val unread = ParamUpdate(Map(2 -> hex("6161")))
    val other = ParamUpdate(Map(2 -> hex("6162")))
    assertEquals(ParamUpdate.typed(unread), Map(2 -> ParamUpdate.Typed.Unread(hex("6161"))))
    assertEquals(ParamUpdate.winners(Vector(unread, other, unread), 2), Vector(unread))
    assertEquals(
      UpdateChecks
        .voted[xyz.kd5ujc.scarab.ledger.state.Alonzo](
          alonzo,
          votes(Vector(canonical, unread, rewritten)),
          2
        )
        .map(_.minFeeA),
      Some(BigInt(1000))
    )
  }

  test("a coin, an epoch interval, a nonce, a protocol version: each compares by its value") {
    val hash = "5820" + "5b" * 32
    val shortest = proposed(
      Era.Shelley,
      "a5" + "051a000f4240" + "0712" + "0d8201" + hash + "0e820200" + "0f1a000f4240"
    )
    val widest = proposed(
      Era.Shelley,
      "a5" + "051b00000000000f4240" + "071812" + "0d98021801" + hash + "0e8218021800" +
        "0f1b00000000000f4240"
    )
    val otherNonce = proposed(
      Era.Shelley,
      "a5" + "051a000f4240" + "0712" + "0d8201" + "5820" + "5c" * 32 + "0e820200" + "0f1a000f4240"
    )
    assertNotEquals(shortest, widest)
    assertEquals(ParamUpdate.typed(shortest), ParamUpdate.typed(widest))
    assertEquals(ParamUpdate.winners(Vector(shortest, widest, otherNonce), 2), Vector(shortest))
  }

  test("a stored update in a form only an earlier build took compares by the value it applies") {
    val definite = ParamUpdate(Map(19 -> hex("82d81e820102d81e820103"), 13 -> hex("8100")))
    val stored = ParamUpdate(Map(19 -> hex("9fd81e820204d81e820103ff"), 13 -> hex("820000")))
    assertEquals(ParamUpdate.winners(Vector(definite, stored), 2), Vector(definite))
  }
