// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra
import xyz.kd5ujc.scarab.plutus.{DataSerialiser, PlutusLanguage, ScalusEvaluator}

/** Complete CBOR bytes captured before #883 from 021142722c583c2ec962efb2a7ec6146ed636827: a
  * deterministic transaction, every supported language at each usual major version 5 to 12. These
  * guard the bytes, including interval closure, rather than a printed projection of Data. Eras
  * before Alonzo have no Plutus contexts. Dijkstra's V4 translation is separate.
  */
class ScriptContextUsualVersionSuite extends FunSuite:
  private def hash(seed: Int, length: Int): String =
    (0 until length).map(i => f"${(seed * 31 + i * 7 + 1) & 0xff}%02x").mkString
  private def head(major: Int, n: Int): String =
    if n < 24 then f"${(major << 5) | n}%02x"
    else if n < 256 then f"${(major << 5) | 24}%02x$n%02x"
    else if n < 65536 then f"${(major << 5) | 25}%02x${n >> 8}%02x${n & 0xff}%02x"
    else f"${(major << 5) | 26}%02x$n%08x"
  private def bytes(hex: String): String = head(2, hex.length / 2) + hex
  private val keyAddress = "60" + hash(1, 28)
  private def out(address: String, inline: Boolean = false, script: Boolean = false): TxOut =
    val fields = Vector(
      Some("00" + bytes(address)),
      Some("01" + head(0, 2000000)),
      Option.when(inline)("02" + "8201" + "d818" + bytes("01")),
      Option.when(script)("03" + "d818" + bytes("8202" + bytes("4e4d01000033222220051200120011")))
    ).flatten
    TxOut
      .fromBytes(
        Bytes.fromHex(head(5, fields.size) + fields.mkString).fold(e => fail(e.toString), identity)
      )
      .fold(e => fail(e.toString), identity)
  private def in(n: Int) =
    TxIn(Bytes.fromHex(hash(10 + n, 32)).fold(e => fail(e.toString), identity), n)
  private val plain = out(keyAddress)

  private val baseline: Vector[(ShelleyBasedEra[?], Int, PlutusLanguage, String)] = Vector(
    (
      ShelleyBasedEra.SAlonzo,
      5,
      PlutusLanguage.V1,
      "d8799f9fd8799fd8799fd8799f5820373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910ff00ffd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87a80ffffff9fd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87a80ffffa140a14001a140a140008080d8799fd8799fd87980d87a80ffd8799fd87a9f19c350ffd87a80ffff8080d8799f5820d161d71145abeec5ef15abcf0459cec60a27321e2f0ac0ef7ace5254f5944476ffff"
    ),
    (
      ShelleyBasedEra.SAlonzo,
      6,
      PlutusLanguage.V1,
      "d8799f9fd8799fd8799fd8799f5820373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910ff00ffd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87a80ffffff9fd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87a80ffffa140a14001a140a140008080d8799fd8799fd87980d87a80ffd8799fd87a9f19c350ffd87a80ffff8080d8799f5820d161d71145abeec5ef15abcf0459cec60a27321e2f0ac0ef7ace5254f5944476ffff"
    ),
    (
      ShelleyBasedEra.SBabbage,
      7,
      PlutusLanguage.V1,
      "d8799f9fd8799fd8799fd8799f5820373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910ff00ffd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87a80ffffff9fd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87a80ffffa140a14001a140a140008080d8799fd8799fd87980d87a80ffd8799fd87a9f19c350ffd87a80ffff8080d8799f5820d161d71145abeec5ef15abcf0459cec60a27321e2f0ac0ef7ace5254f5944476ffff"
    ),
    (
      ShelleyBasedEra.SBabbage,
      7,
      PlutusLanguage.V2,
      "d8799f9fd8799fd8799fd8799f5820373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910ff00ffd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffffff809fd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffffa140a14001a140a1400080a0d8799fd8799fd87980d87a80ffd8799fd87a9f19c350ffd87a80ffff80a0a0d8799f5820d161d71145abeec5ef15abcf0459cec60a27321e2f0ac0ef7ace5254f5944476ffff"
    ),
    (
      ShelleyBasedEra.SBabbage,
      8,
      PlutusLanguage.V1,
      "d8799f9fd8799fd8799fd8799f5820373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910ff00ffd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87a80ffffff9fd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87a80ffffa140a14001a140a140008080d8799fd8799fd87980d87a80ffd8799fd87a9f19c350ffd87a80ffff8080d8799f5820d161d71145abeec5ef15abcf0459cec60a27321e2f0ac0ef7ace5254f5944476ffff"
    ),
    (
      ShelleyBasedEra.SBabbage,
      8,
      PlutusLanguage.V2,
      "d8799f9fd8799fd8799fd8799f5820373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910ff00ffd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffffff809fd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffffa140a14001a140a1400080a0d8799fd8799fd87980d87a80ffd8799fd87a9f19c350ffd87a80ffff80a0a0d8799f5820d161d71145abeec5ef15abcf0459cec60a27321e2f0ac0ef7ace5254f5944476ffff"
    ),
    (
      ShelleyBasedEra.SConway,
      9,
      PlutusLanguage.V1,
      "d8799f9fd8799fd8799fd8799f5820373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910ff00ffd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87a80ffffff9fd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87a80ffffa140a14001a140a140008080d8799fd8799fd87980d87a80ffd8799fd87a9f19c350ffd87980ffff8080d8799f5820d161d71145abeec5ef15abcf0459cec60a27321e2f0ac0ef7ace5254f5944476ffff"
    ),
    (
      ShelleyBasedEra.SConway,
      9,
      PlutusLanguage.V2,
      "d8799f9fd8799fd8799fd8799f5820373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910ff00ffd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffffff809fd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffffa140a14001a140a1400080a0d8799fd8799fd87980d87a80ffd8799fd87a9f19c350ffd87980ffff80a0a0d8799f5820d161d71145abeec5ef15abcf0459cec60a27321e2f0ac0ef7ace5254f5944476ffff"
    ),
    (
      ShelleyBasedEra.SConway,
      9,
      PlutusLanguage.V3,
      "d8799f9fd8799fd8799f5820373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb02091000ffd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffffff809fd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffff01a080a0d8799fd8799fd87980d87a80ffd8799fd87a9f19c350ffd87980ffff80a0a05820d161d71145abeec5ef15abcf0459cec60a27321e2f0ac0ef7ace5254f5944476a080d87a80d87a80ff"
    ),
    (
      ShelleyBasedEra.SConway,
      10,
      PlutusLanguage.V1,
      "d8799f9fd8799fd8799fd8799f5820373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910ff00ffd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87a80ffffff9fd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87a80ffffa140a14001a140a140008080d8799fd8799fd87980d87a80ffd8799fd87a9f19c350ffd87980ffff8080d8799f5820d161d71145abeec5ef15abcf0459cec60a27321e2f0ac0ef7ace5254f5944476ffff"
    ),
    (
      ShelleyBasedEra.SConway,
      10,
      PlutusLanguage.V2,
      "d8799f9fd8799fd8799fd8799f5820373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910ff00ffd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffffff809fd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffffa140a14001a140a1400080a0d8799fd8799fd87980d87a80ffd8799fd87a9f19c350ffd87980ffff80a0a0d8799f5820d161d71145abeec5ef15abcf0459cec60a27321e2f0ac0ef7ace5254f5944476ffff"
    ),
    (
      ShelleyBasedEra.SConway,
      10,
      PlutusLanguage.V3,
      "d8799f9fd8799fd8799f5820373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb02091000ffd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffffff809fd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffff01a080a0d8799fd8799fd87980d87a80ffd8799fd87a9f19c350ffd87980ffff80a0a05820d161d71145abeec5ef15abcf0459cec60a27321e2f0ac0ef7ace5254f5944476a080d87a80d87a80ff"
    ),
    (
      ShelleyBasedEra.SConway,
      11,
      PlutusLanguage.V1,
      "d8799f9fd8799fd8799fd8799f5820373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910ff00ffd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87a80ffffff9fd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87a80ffffa140a14001a140a140008080d8799fd8799fd87980d87a80ffd8799fd87a9f19c350ffd87980ffff8080d8799f5820d161d71145abeec5ef15abcf0459cec60a27321e2f0ac0ef7ace5254f5944476ffff"
    ),
    (
      ShelleyBasedEra.SConway,
      11,
      PlutusLanguage.V2,
      "d8799f9fd8799fd8799fd8799f5820373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910ff00ffd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffffff809fd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffffa140a14001a140a1400080a0d8799fd8799fd87980d87a80ffd8799fd87a9f19c350ffd87980ffff80a0a0d8799f5820d161d71145abeec5ef15abcf0459cec60a27321e2f0ac0ef7ace5254f5944476ffff"
    ),
    (
      ShelleyBasedEra.SConway,
      11,
      PlutusLanguage.V3,
      "d8799f9fd8799fd8799f5820373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb02091000ffd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffffff809fd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffff01a080a0d8799fd8799fd87980d87a80ffd8799fd87a9f19c350ffd87980ffff80a0a05820d161d71145abeec5ef15abcf0459cec60a27321e2f0ac0ef7ace5254f5944476a080d87a80d87a80ff"
    ),
    (
      ShelleyBasedEra.SDijkstra,
      12,
      PlutusLanguage.V1,
      "d8799f9fd8799fd8799fd8799f5820373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910ff00ffd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87a80ffffff9fd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87a80ffffa140a14001a140a140008080d8799fd8799fd87980d87a80ffd8799fd87a9f19c350ffd87980ffff8080d8799f5820d161d71145abeec5ef15abcf0459cec60a27321e2f0ac0ef7ace5254f5944476ffff"
    ),
    (
      ShelleyBasedEra.SDijkstra,
      12,
      PlutusLanguage.V2,
      "d8799f9fd8799fd8799fd8799f5820373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910ff00ffd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffffff809fd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffffa140a14001a140a1400080a0d8799fd8799fd87980d87a80ffd8799fd87a9f19c350ffd87980ffff80a0a0d8799f5820d161d71145abeec5ef15abcf0459cec60a27321e2f0ac0ef7ace5254f5944476ffff"
    ),
    (
      ShelleyBasedEra.SDijkstra,
      12,
      PlutusLanguage.V3,
      "d8799f9fd8799fd8799f5820373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb02091000ffd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffffff809fd8799fd8799fd8799f581c20272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6ddffd87a80ffa140a1401a001e8480d87980d87a80ffff01a080a0d8799fd8799fd87980d87a80ffd8799fd87a9f19c350ffd87980ffff80a0a05820d161d71145abeec5ef15abcf0459cec60a27321e2f0ac0ef7ace5254f5944476a080d87a80d87a80ff"
    )
  )

  baseline.foreach { case (era, major, language, expected) =>
    test(s"$era at $major, $language: unchanged complete TxInfo bytes") {
      val body = TestLedger.body("x", Vector(in(0)), Vector(plain), 1, ttl = Some(50))
      val env =
        ScriptContexts.Environment(era, major, Map(in(0) -> plain), Map.empty, s => Right(s * 1000))
      val info = ScriptContexts
        .txInfo(language, Tx(body, Witnesses.empty, 0, true, None), env)
        .fold(e => fail(e.toString), identity)
      val bytes = Bytes.wrap(DataSerialiser.serialise(ScalusEvaluator.toData(info)))
      assertEquals(bytes.hex, expected)
    }
  }
