// SPDX-License-Identifier: Apache-2.0
package lab.ledger.storage

import java.security.MessageDigest
import lab.cbor.Bytes
import lab.plutus.PlutusExecution

class RestrictedLedgerImageSuite extends munit.FunSuite:
  import RestrictedLedgerImage.*
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(fail(_), identity)
  private def sha(b: Bytes): Bytes =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(b.toArray))
  private val sources =
    Map("genesis" -> hex("0102"), "parameters" -> hex("0304"), "manifest" -> hex("0506"))
  private val binding = Binding(
    PlutusExecution.ProfileId,
    1082026,
    0,
    Point(201, 0, sha(hex("01"))),
    sha(hex("02")),
    sources.map((n, b) => n -> sha(b))
  )
  // Intentionally nonminimal coin and indefinite outer map; preserve exact original framing.
  private val output = hex("82581d60" + "11" * 28 + "1b00000000004c4b40")
  private val utxo = hex("bf825820" + "22" * 32 + "00" + output.hex + "ff")
  private def encoded = encode(binding, sources, utxo, 300000).fold(fail(_), identity)
  private def resign(payload: Vector[Byte]): Bytes = {
    val b = Bytes(payload); Bytes(payload ++ sha(b).value)
  }
  test(
    "roundtrip preserves complete image, original output spans, source bytes and large fee pot"
  ) {
    val maximum = (BigInt(1) << 128) - 1
    val raw = encode(binding, sources, utxo, maximum).fold(fail(_), identity)
    val image = decode(raw, binding).fold(fail(_), identity)
    assertEquals(image.original, raw)
    assertEquals(image.originalUtxo, utxo)
    assertEquals(image.snapshot.outputs.values.head.original, output)
    assertEquals(image.sources, sources)
    assertEquals(image.fees, maximum)
    assertEquals(encode(image.binding, image.sources, image.originalUtxo, image.fees), Right(raw))
    assert(!image.completeValidatorState && !image.restoreAuthorized && !image.fullLedgerValidated)
  }
  test("every truncation and single-byte mutation rejects") {
    val raw = encoded
    (0 until raw.size).foreach(n => assert(decode(Bytes(raw.value.take(n)), binding).isLeft))
    raw.value.indices.foreach(i =>
      assert(decode(Bytes(raw.value.updated(i, (raw.value(i) ^ 1).toByte)), binding).isLeft)
    )
  }
  test("freshly checksummed unknown version, wrong magic and trailing payload reject") {
    val payload = encoded.value.dropRight(32)
    assert(decode(resign(payload.updated(11, 2.toByte)), binding).isLeft)
    assert(decode(resign(payload.updated(0, 0.toByte)), binding).isLeft)
    assert(decode(resign(payload :+ 0.toByte), binding).isLeft)
  }
  test("negative and oversized declared lengths reject even with repaired checksum") {
    val payload = encoded.value.dropRight(32)
    for length <- Vector(
        Vector.fill[Byte](4)(-1),
        Vector[Byte](0x7f, -1, -1, -1),
        Vector.fill[Byte](4)(0)
      )
    do assert(decode(resign(payload.patch(12, length, 4)), binding).isLeft)
  }
  test("independent expected point, profile, source and environment bindings are mandatory") {
    val raw = encoded
    val wrong = Vector(
      binding.copy(point = binding.point.copy(blockNo = 1)),
      binding.copy(point = binding.point.copy(slot = 202)),
      binding.copy(point = binding.point.copy(hash = sha(hex("03")))),
      binding.copy(environmentId = sha(hex("04"))),
      binding.copy(profile = "future"),
      binding.copy(sourcePins = binding.sourcePins.updated("genesis", sha(hex("09"))))
    )
    wrong.foreach(b => assert(decode(raw, b).isLeft))
    assert(decode(raw, null).isLeft)
  }
  test("same semantic UTxO with different original bytes changes image identity") {
    val other = hex(utxo.hex.replace("1b00000000004c4b40", "1a004c4b40"))
    val raw = encode(binding, sources, other, 300000).fold(fail(_), identity)
    assertNotEquals(raw, encoded)
    assertNotEquals(sha(raw), sha(encoded))
    assertEquals(
      decode(raw, binding).fold(fail(_), identity).snapshot.outputs.values.head.coin,
      BigInt(5000000)
    )
  }
  test("duplicate semantic references and unsupported output features fail closed") {
    val key = "825820" + "22" * 32 + "00"
    val duplicate = hex("a2" + key + output.hex + key + output.hex)
    assert(encode(binding, sources, duplicate, 0).isLeft)
    assert(encode(binding, sources, hex("a10000"), 0).isLeft)
    assert(encode(binding, sources, Bytes(utxo.value :+ 0.toByte), 0).isLeft)
  }
  test("inline datum and nonminimal embedded integer remain byte exact") {
    val datum = "d8799f581c" + "33" * 28 + "1b0000000000000001ff"
    val out = "a300581d70" + "44" * 28 + "011a01312d00028201d818582b" + datum
    val image = hex("a1825820" + "55" * 32 + "00" + out)
    val raw = encode(binding, sources, image, 0).fold(fail(_), identity)
    val decoded = decode(raw, binding).fold(fail(_), identity)
    assertEquals(decoded.snapshot.outputs.values.head.datum.get.original, hex(datum))
  }
  test("source content, domain, nulls, aggregate and fee bounds reject") {
    assert(encode(binding, sources.updated("genesis", hex("09")), utxo, 0).isLeft)
    assert(encode(binding, sources - "manifest", utxo, 0).isLeft)
    assert(encode(binding, sources.updated("extra", hex("01")), utxo, 0).isLeft)
    assert(encode(binding, sources, utxo, -1).isLeft)
    assert(encode(binding, sources, utxo, BigInt(1) << 128).isLeft)
    assert(encode(binding, sources, null, 0).isLeft)
    assert(decode(null, binding).isLeft)
    assert(decode(Bytes(Vector.fill(MaxBytes + 1)(0.toByte)), binding).isLeft)
    assert(encode(binding.copy(epoch = 1), sources, utxo, 0).isLeft)
    assert(encode(binding.copy(point = binding.point.copy(slot = 1000)), sources, utxo, 0).isLeft)
  }

  test("repaired outer checksum cannot hide corrupt source or malformed UTxO") {
    val payload = encoded.value.dropRight(32)
    val firstSource = 16 + binding.profile.length + 32 + 64 + 32 + 4
    assert(decode(resign(payload.updated(firstSource, 9.toByte)), binding).isLeft)
    val utxoStart = payload.size - utxo.size
    assert(decode(resign(payload.updated(utxoStart, 0xff.toByte)), binding).isLeft)
  }
  test("checksum is not authentication of a structurally valid substituted fee pot") {
    val payload = encoded.value.dropRight(32)
    val feeLast = payload.size - utxo.size - 4 - 1
    val replacement = resign(payload.updated(feeLast, (payload(feeLast) ^ 1).toByte))
    val decoded = decode(replacement, binding).fold(fail(_), identity)
    assertNotEquals(decoded.fees, BigInt(300000))
    assertNotEquals(decoded.imageSHA256, sha(encoded))
    assert(!decoded.restoreAuthorized)
  }
