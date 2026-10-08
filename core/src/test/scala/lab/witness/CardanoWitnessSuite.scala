// SPDX-License-Identifier: Apache-2.0
package lab.witness

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}

class CardanoWitnessSuite extends munit.FunSuite:
  import VerificationResult.*
  import WitnessInputError.*
  import WitnessTestData.*

  private def rawBytes(value: Bytes): String =
    Cbor.encode(Value.ByteString(value)).toOption.get.hex

  private def witnessItem(key: Bytes, signature: Bytes): String =
    "82" + rawBytes(key) + rawBytes(signature)

  private def item: String =
    val v = vector("sodium-valid-0")
    witnessItem(v.key, v.signature)

  private def transaction(
      body: String = "a10000",
      witnesses: String = "",
      validity: String = "f5",
      auxiliary: String = "f6"
  ): Bytes =
    val actualWitnesses = if witnesses.isEmpty then "a10081" + item else witnesses
    hex("84" + body + actualWitnesses + validity + auxiliary)

  private def acceptedRow: Vector[String] =
    rows("ledger-witnesses.tsv").find(_.head == "amaru-shelley-fixture-event-1-witness-0").get

  private def acceptedEnvelope: WitnessEnvelope =
    CardanoWitness.decodeEnvelope(hex(acceptedRow(1))).toOption.get

  private def bodyEntries(body: Bytes): Vector[(Node, Node)] =
    Cbor.decode(body).toOption.get.value match
      case Value.Map(entries) => entries
      case _                  => fail("fixture body must be a map")

  private def mapContent(body: Bytes): Map[Value, Value] =
    bodyEntries(body).map { case (key, value) => key.value -> value.value }.toMap

  test("all eight archived ledger witnesses sign the original 32 raw body-hash bytes") {
    val archived = rows("ledger-witnesses.tsv")
    assertEquals(archived.size, 8)
    archived.foreach { row =>
      assertEquals(row.size, 5)
      val v = vector(row(0))
      val body = ExactBodyCbor.create(hex(row(3))).toOption.get
      assertEquals(body.bytes, hex(row(3)), v.id)
      assertEquals(body.hash.bytes, hex(row(4)), v.id)
      assertEquals(body.hash.bytes, v.message, v.id)
      assertEquals(
        CardanoWitness.verifyVKeyWitness(body, v.witness),
        Right(SignatureVerified),
        v.id
      )
      assertEquals(
        CardanoWitness.verifyVKeyWitness(body.hash, v.witness),
        Right(SignatureVerified),
        v.id
      )
    }
  }

  test("a genuine accepted ledger envelope preserves its body and witness bytes") {
    val row = acceptedRow
    val envelope = acceptedEnvelope
    val v = vector(row(0))
    assertEquals(envelope.body.bytes, hex(row(3)))
    assertEquals(envelope.body.hash.bytes, hex(row(4)))
    assertEquals(envelope.witnesses(row(2).toInt), v.witness)
    assertEquals(
      CardanoWitness.verifyVKeyWitness(envelope.body, v.witness),
      Right(SignatureVerified)
    )
  }

  test("witness message is not body CBOR, a CBOR byte string, a prehash, or a domain prefix") {
    val envelope = acceptedEnvelope
    val witness = envelope.witnesses.head
    val message = envelope.body.hash.bytes
    val sha512 =
      Bytes.fromArray(java.security.MessageDigest.getInstance("SHA-512").digest(message.toArray))
    val wrongMessages = Vector(
      envelope.body.bytes,
      hex(rawBytes(message)),
      sha512,
      Bytes(Vector(0.toByte) ++ message.value)
    )
    wrongMessages.foreach { wrong =>
      assertEquals(
        StrictEd25519.verifyEd25519(witness.publicKey, witness.signature, wrong),
        Right(SignatureRejected)
      )
    }
  }

  test(
    "equivalent alternate integer encoding and reordered body preserve values but invalidate signature"
  ) {
    val envelope = acceptedEnvelope
    val original = envelope.body.bytes
    val entries = bodyEntries(original)
    assertEquals(entries.head._1.original, hex("00"))
    assert(entries.size < 24)
    val header = Vector((0xa0 | entries.size).toByte)
    val alternateInteger = Bytes(
      header ++ hex("1800").value ++ entries.head._2.original.value ++
        entries.tail.flatMap { case (key, value) => key.original.value ++ value.original.value }
    )
    val reordered = Bytes(
      header ++ entries.reverse.flatMap { case (key, value) =>
        key.original.value ++ value.original.value
      }
    )
    Vector(alternateInteger, reordered).foreach { changed =>
      assertEquals(mapContent(changed), mapContent(original))
      val body = ExactBodyCbor.create(changed).toOption.get
      assertEquals(body.bytes, changed)
      assertNotEquals(body.hash, envelope.body.hash)
      assertEquals(body.hash.bytes, Blake2b.hash256.hash(changed))
      assertEquals(
        CardanoWitness.verifyVKeyWitness(body, envelope.witnesses.head),
        Right(SignatureRejected)
      )
      val reparsed = CardanoWitness
        .decodeEnvelope(
          transaction(
            changed.hex,
            "a10081" + witnessItem(
              envelope.witnesses.head.publicKey.bytes,
              envelope.witnesses.head.signature.bytes
            )
          )
        )
        .toOption
        .get
      assertEquals(reparsed.body.bytes, changed)
      assertEquals(
        CardanoWitness.verifyVKeyWitness(reparsed.body, reparsed.witnesses.head),
        Right(SignatureRejected)
      )
    }
  }

  test("definite and indefinite equivalent body maps retain exact bytes and distinct hashes") {
    val definite = ExactBodyCbor.create(hex("a10000")).toOption.get
    val indefinite = ExactBodyCbor.create(hex("bf0000ff")).toOption.get
    assertEquals(mapContent(definite.bytes), mapContent(indefinite.bytes))
    assertNotEquals(definite.hash, indefinite.hash)
    assertEquals(
      CardanoWitness.decodeEnvelope(transaction("bf0000ff")).toOption.get.body.bytes,
      indefinite.bytes
    )
  }

  test("local accepted-witness body, hash, signature, and wrong-public-key mutations reject") {
    val envelope = acceptedEnvelope
    val witness = envelope.witnesses.head
    // Byte 10 lies within this pinned fixture's first public input transaction-id byte string.
    val changedBody = ExactBodyCbor.create(flip(envelope.body.bytes, 10)).toOption.get
    val changedHash = BodyHash32.create(flip(envelope.body.hash.bytes, 0)).toOption.get
    val changedSignature =
      witness.copy(signature = Signature64.create(flip(witness.signature.bytes, 0)).toOption.get)
    val wrongKey = witness.copy(publicKey = vector("sodium-valid-0").witness.publicKey)
    assertEquals(CardanoWitness.verifyVKeyWitness(changedBody, witness), Right(SignatureRejected))
    assertEquals(CardanoWitness.verifyVKeyWitness(changedHash, witness), Right(SignatureRejected))
    assertEquals(
      CardanoWitness.verifyVKeyWitness(envelope.body, changedSignature),
      Right(SignatureRejected)
    )
    assertEquals(
      CardanoWitness.verifyVKeyWitness(envelope.body, wrongKey),
      Right(SignatureRejected)
    )
  }

  test("tagged and untagged vkey sets are structural containers, including a false validity flag") {
    Vector("a10081" + item, "a100d9010281" + item).foreach { witnesses =>
      val envelope = CardanoWitness
        .decodeEnvelope(transaction(witnesses = witnesses, validity = "f4"))
        .toOption
        .get
      assertEquals(envelope.witnesses, Vector(vector("sodium-valid-0").witness))
      // This signature is valid for an empty message, not this body's hash. Parsing does no crypto.
      assertEquals(
        CardanoWitness.verifyVKeyWitness(envelope.body, envelope.witnesses.head),
        Right(SignatureRejected)
      )
    }
  }

  test("malformed or unsupported envelopes fail before any signature evaluation") {
    val key = vector("sodium-valid-0").key
    val sig = vector("sodium-valid-0").signature
    val malformed = Vector(
      "empty transaction" -> Bytes.empty,
      "non-array transaction" -> hex("a0"),
      "three transaction fields" -> hex("83a0a0f5"),
      "five transaction fields" -> hex("85a0a0f5f600"),
      "truncated transaction" -> hex("84a0a0f5"),
      "trailing data" -> Bytes(transaction().value :+ 0.toByte),
      "non-map body" -> transaction(body = "80"),
      "duplicate body key" -> transaction(body = "a200000001"),
      "duplicate body key alternate width" -> transaction(body = "a20000180001"),
      "negative body key" -> transaction(body = "a12000"),
      "text body key" -> transaction(body = "a1617800"),
      "non-map witness set" -> transaction(witnesses = "80"),
      "duplicate witness field" -> transaction(witnesses = "a20081" + item + "0081" + item),
      "duplicate witness field alternate width" -> transaction(witnesses =
        "a20081" + item + "180081" + item
      ),
      "non-uint witness field" -> transaction(witnesses = "a1617881" + item),
      "non-array vkey set" -> transaction(witnesses = "a10000"),
      "wrong vkey set tag" -> transaction(witnesses = "a100d9010381" + item),
      "nested set tags" -> transaction(witnesses = "a100d90102d9010281" + item),
      "one-field witness" -> transaction(witnesses = "a1008181" + rawBytes(key)),
      "three-field witness" -> transaction(witnesses =
        "a1008183" + rawBytes(key) + rawBytes(sig) + "00"
      ),
      "non-array witness" -> transaction(witnesses = "a10081a0"),
      "non-bytes key" -> transaction(witnesses = "a100818200" + rawBytes(sig)),
      "non-bytes signature" -> transaction(witnesses = "a1008182" + rawBytes(key) + "00"),
      "duplicate vkey witness" -> transaction(witnesses = "a10082" + item + item),
      "duplicate key different signature" -> transaction(witnesses =
        "a10082" + item + witnessItem(key, flip(sig, 0))
      ),
      "non-Boolean validity" -> transaction(validity = "00")
    )
    val unsupported = Vector(
      "missing vkeys" -> transaction(witnesses = "a0"),
      "empty vkeys" -> transaction(witnesses = "a10080"),
      "empty tagged vkeys" -> transaction(witnesses = "a100d9010280"),
      "script-only witness" -> transaction(witnesses = "a10180"),
      "vkeys plus scripts" -> transaction(witnesses = "a20081" + item + "0180"),
      "non-null auxiliary" -> transaction(auxiliary = "a0")
    )
    var cryptoCalls = 0
    def parseThenVerify(
        input: Bytes
    ): Either[WitnessInputError | VerificationError, VerificationResult] =
      CardanoWitness.decodeEnvelope(input).flatMap { parsed =>
        cryptoCalls += 1
        CardanoWitness.verifyVKeyWitness(parsed.body, parsed.witnesses.head)
      }
    malformed.foreach { case (label, bytes) =>
      val isMalformed = parseThenVerify(bytes) match
        case Left(MalformedCbor(_)) => true
        case _                      => false
      assert(isMalformed, label)
      assertEquals(cryptoCalls, 0, label)
    }
    unsupported.foreach { case (label, bytes) =>
      val isUnsupported = parseThenVerify(bytes) match
        case Left(UnsupportedShape(_)) => true
        case _                         => false
      assert(isUnsupported, label)
      assertEquals(cryptoCalls, 0, label)
    }
  }

  test("envelope public-key and signature length errors remain typed input failures") {
    val v = vector("sodium-valid-0")
    Vector(31, 33).foreach { size =>
      val malformedKey = Bytes(Vector.fill(size)(0.toByte))
      assertEquals(
        CardanoWitness.decodeEnvelope(
          transaction(witnesses = "a10081" + witnessItem(malformedKey, v.signature))
        ),
        Left(MalformedPublicKeyLength(size))
      )
    }
    Vector(63, 65).foreach { size =>
      val malformedSignature = Bytes(Vector.fill(size)(0.toByte))
      assertEquals(
        CardanoWitness.decodeEnvelope(
          transaction(witnesses = "a10081" + witnessItem(v.key, malformedSignature))
        ),
        Left(MalformedSignatureLength(size))
      )
    }
  }

  test("adapter programming failures remain typed instead of signature rejection") {
    // Invalid typed-API use probes the failure boundary, not byte-input conformance.
    val witness = vector("sodium-valid-0").witness
    val calls = Vector(
      CardanoWitness.verifyVKeyWitness(null.asInstanceOf[BodyHash32], witness),
      CardanoWitness.verifyVKeyWitness(null.asInstanceOf[ExactBodyCbor], witness),
      CardanoWitness.verifyVKeyWitness(ExactBodyCbor.create(hex("a0")).toOption.get, null)
    )
    calls.foreach { result =>
      assertEquals(
        result,
        Left(VerificationError.ImplementationFailure("java.lang.NullPointerException"))
      )
    }
  }
