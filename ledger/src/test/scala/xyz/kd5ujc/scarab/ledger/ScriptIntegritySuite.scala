// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.plutus.{ExUnits, PlutusLanguage}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra

/** The language views and the hash layout of `script_data_hash`. */
class ScriptIntegritySuite extends FunSuite:
  private def bytes(hex: String): Vector[Byte] = Bytes.fromHex(hex).toOption.get.value

  private val pp = TestLedger
    .params(7)
    .copy(costModels =
      Map(0 -> Vector(1, 2, 3), 1 -> Vector(4, 5, 6, 7), 2 -> Vector(-1, 300, 70000))
    )

  test("PlutusV1 is a byte string key and a byte string wrapping an indefinite list") {
    // a1 41 00 45 9f 01 02 03 ff
    assertEquals(
      ScriptIntegrity.languageViews(pp, Set(ScriptKind.PlutusV1)),
      bytes("a1" + "4100" + "45" + "9f010203ff")
    )
  }

  test("PlutusV2 and V3 are plain keys and definite lists") {
    assertEquals(
      ScriptIntegrity.languageViews(pp, Set(ScriptKind.PlutusV2)),
      bytes("a1" + "01" + "84" + "04050607")
    )
    // -1 = 20, 300 = 19 012c, 70000 = 1a 00011170
    assertEquals(
      ScriptIntegrity.languageViews(pp, Set(ScriptKind.PlutusV3)),
      bytes("a1" + "02" + "83" + "20" + "19012c" + "1a00011170")
    )
  }

  test("keys are canonically ordered: V2 and V3 before V1, whatever the set order") {
    val all = Set[ScriptKind](ScriptKind.PlutusV1, ScriptKind.PlutusV2, ScriptKind.PlutusV3)
    assertEquals(
      ScriptIntegrity.languageViews(pp, all),
      bytes(
        "a3" + "01" + "84" + "04050607" + "02" + "83" + "20" + "19012c" + "1a00011170" +
          "4100" + "45" + "9f010203ff"
      )
    )
  }

  test("PlutusV4 is a plain key 3 and a definite list, ordered with V2 and V3 before V1") {
    val withV4 = pp.copy(costModels = pp.costModels.updated(3, Vector(BigInt(9))))
    assertEquals(
      ScriptIntegrity.plutusLanguageViews(withV4, Set(PlutusLanguage.V4)),
      bytes("a1" + "03" + "81" + "09")
    )
    assertEquals(
      ScriptIntegrity.plutusLanguageViews(withV4, PlutusLanguage.values.toSet),
      bytes(
        "a4" + "01" + "84" + "04050607" + "02" + "83" + "20" + "19012c" + "1a00011170" +
          "03" + "81" + "09" + "4100" + "45" + "9f010203ff"
      )
    )
    assertEquals(
      ScriptIntegrity.plutusLanguageViews(pp.copy(costModels = Map.empty), Set(PlutusLanguage.V4)),
      bytes("a1" + "03" + "f6")
    )
  }

  test("no languages is the empty map; native scripts do not count") {
    assertEquals(ScriptIntegrity.languageViews(pp, Set.empty), bytes("a0"))
    assertEquals(ScriptIntegrity.languageViews(pp, Set(ScriptKind.Native)), bytes("a0"))
  }

  // `getLanguageView` encodes a missing cost model as `null` (cardano-ledger f649f975,
  // `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/PParams.hs:584`); V1 wraps it as bytes (575-576).
  private val noModels = pp.copy(costModels = Map.empty)
  private val onlyV1 = pp.copy(costModels = Map(0 -> Vector(1, 2, 3)))

  test("a language without a cost model has a null view: V1 bytes-wrapped, V2 and V3 bare") {
    assertEquals(
      ScriptIntegrity.languageViews(noModels, Set(ScriptKind.PlutusV1)),
      bytes("a1" + "4100" + "41f6")
    )
    assertEquals(
      ScriptIntegrity.languageViews(noModels, Set(ScriptKind.PlutusV2)),
      bytes("a1" + "01" + "f6")
    )
    assertEquals(
      ScriptIntegrity.languageViews(noModels, Set(ScriptKind.PlutusV3)),
      bytes("a1" + "02" + "f6")
    )
    assertEquals(
      ScriptIntegrity.languageViews(onlyV1, Set(ScriptKind.PlutusV1, ScriptKind.PlutusV3)),
      bytes("a2" + "02" + "f6" + "4100" + "45" + "9f010203ff")
    )
  }

  test("a cost model above 255 parameters uses the two-byte array header") {
    val big = pp.copy(costModels = Map(1 -> Vector.fill(300)(BigInt(1))))
    val views = ScriptIntegrity.languageViews(big, Set(ScriptKind.PlutusV2))
    assertEquals(views.take(5), bytes("a1" + "01" + "99012c"))
    assertEquals(views.size, 5 + 300)
  }

  private val redeemers = Bytes(bytes("81" + "840000" + "01" + "820102"))
  private val datums = Bytes(bytes("8101"))
  private def tx(rawRedeemers: Option[Bytes], rawDatums: Option[Bytes]): Tx =
    val wits = Witnesses(
      Vector.empty,
      datums = rawDatums.map(_ => Bytes(Vector(1))).toVector,
      redeemers = rawRedeemers.map(_ => Redeemer(0, 0, Bytes(Vector(1)), ExUnits(1, 2))).toVector,
      redeemersRaw = rawRedeemers,
      datumsRaw = rawDatums
    )
    Tx(TestLedger.body("h", Vector.empty, Vector.empty, 0), wits, 0, true, None)

  test("the hash is blake2b-256 of the witness set's redeemers, datums and the language views") {
    val expected = Blake2b.hash256.hash(
      Bytes(redeemers.value ++ datums.value ++ bytes("a1" + "01" + "84" + "04050607"))
    )
    assertEquals(
      ScriptIntegrity.hash(tx(Some(redeemers), Some(datums)), pp, Set(ScriptKind.PlutusV2))(using
        TestLedger.usualEra((pp).protocolMajor.toInt)
      ),
      Some(expected)
    )
  }

  test("datums are omitted when there are none") {
    val expected = Blake2b.hash256.hash(
      Bytes(redeemers.value ++ bytes("a1" + "4100" + "45" + "9f010203ff"))
    )
    assertEquals(
      ScriptIntegrity.hash(tx(Some(redeemers), None), pp, Set(ScriptKind.PlutusV1))(using
        TestLedger.usualEra((pp).protocolMajor.toInt)
      ),
      Some(expected)
    )
  }

  test("the hash counts a null language view, as the reference's encoding gives it") {
    // blake2b-256 of the redeemers ‖ a1 4100 41f6, and of the redeemers ‖ a2 02f6 4100 45 9f010203ff,
    // computed outside Scarab from the bytes `getLanguageView` and `encodeLangViews` write.
    assertEquals(
      ScriptIntegrity.hash(tx(Some(redeemers), None), noModels, Set(ScriptKind.PlutusV1))(using
        TestLedger.usualEra((noModels).protocolMajor.toInt)
      ),
      Some(Bytes(bytes("2d3edd84d1932280ccbc5aeba56e65f145f49276a0b5e4c3be8a5e97e5065697")))
    )
    assertEquals(
      ScriptIntegrity.hash(
        tx(Some(redeemers), None),
        onlyV1,
        Set(ScriptKind.PlutusV1, ScriptKind.PlutusV3)
      )(using TestLedger.usualEra((onlyV1).protocolMajor.toInt)),
      Some(Bytes(bytes("c47c02a6428d13235bce8dc7b0a5c13b699ce2dd400b49ffed0303f10f3da79e")))
    )
  }

  test("nothing to hash: no redeemers, no datums, no Plutus language") {
    assertEquals(
      ScriptIntegrity.hash(tx(None, None), pp, Set.empty)(using
        TestLedger.usualEra((pp).protocolMajor.toInt)
      ),
      None
    )
  }

  test("a cost model change changes the hash") {
    val other = pp.copy(costModels = pp.costModels.updated(1, Vector(4, 5, 6, 8)))
    assertNotEquals(
      ScriptIntegrity.hash(tx(Some(redeemers), None), pp, Set(ScriptKind.PlutusV2))(using
        TestLedger.usualEra((pp).protocolMajor.toInt)
      ),
      ScriptIntegrity.hash(tx(Some(redeemers), None), other, Set(ScriptKind.PlutusV2))(using
        TestLedger.usualEra((other).protocolMajor.toInt)
      )
    )
  }

  // These are the pre-change preimages, including the exact raw redeemer and datum bytes, for
  // every era at each of its usual protocol versions. The empty redeemer constructor is encoded
  // at eraProtVerLow; present bytes remain untouched (Alonzo/TxWits.hs:157-175,203-211 at f649f975).
  private val usualVersions: Vector[(ShelleyBasedEra[?], Vector[Int], Option[String])] = Vector(
    (ShelleyBasedEra.SShelley, Vector(2), None),
    (ShelleyBasedEra.SAllegra, Vector(3), None),
    (ShelleyBasedEra.SMary, Vector(4), None),
    (ShelleyBasedEra.SAlonzo, Vector(5, 6), Some("80")),
    (ShelleyBasedEra.SBabbage, Vector(7, 8), Some("80")),
    (ShelleyBasedEra.SConway, Vector(9, 10, 11), Some("a0")),
    (ShelleyBasedEra.SDijkstra, Vector(12, 13), Some("a0"))
  )

  usualVersions.foreach { case (era, versions, head) =>
    versions.foreach { major =>
      test(s"$era at version $major: integrity preimages are unchanged byte for byte") {
        val params = pp.copy(protocolMajor = major)
        val empty = tx(None, None)
        assertEquals(ScriptIntegrity.preimage(empty, params, Set.empty)(using era), None)
        assertEquals(ScriptIntegrity.hash(empty, params, Set.empty)(using era), None)
        head.foreach { emptyRedeemers =>
          val absent = tx(None, Some(datums))
          val preimage = Bytes(bytes(emptyRedeemers + datums.hex + "a0"))
          assertEquals(
            ScriptIntegrity.preimage(absent, params, Set.empty)(using era),
            Some(preimage)
          )
          assertEquals(
            ScriptIntegrity.hash(absent, params, Set.empty)(using era),
            Some(Blake2b.hash256.hash(preimage))
          )
          val supplied = tx(Some(redeemers), Some(datums))
          val original = redeemers ++ datums ++ Bytes(bytes("a1018404050607"))
          assertEquals(
            ScriptIntegrity.preimage(supplied, params, Set(ScriptKind.PlutusV2))(using era),
            Some(original)
          )
        }
      }
    }
  }
