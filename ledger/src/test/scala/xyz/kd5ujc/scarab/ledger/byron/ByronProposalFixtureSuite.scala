// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}

/** Raw proposal verdicts executed by release CLI 11.2.3.0, retained with provenance in
  * fixtures/byron-version. This exercises decoding, not reference registration or header rules.
  */
class ByronProposalFixtureSuite extends FunSuite:
  private def raw(name: String): Bytes =
    Bytes
      .fromHex(Files.readString(Path.of(s"fixtures/byron-version/$name.hex")).trim)
      .fold(fail(_), identity)

  private def payload(proposal: Bytes): Either[String, UpdatePayload] =
    Cbor
      .decode(ByronCbor.of(0x82, 0x81) ++ proposal ++ ByronCbor.of(0x80))
      .flatMap(UpdatePayload.decode)

  test("reference-generated maximum-width signed proposal decodes over its original bytes") {
    val bytes = raw("valid")
    val decoded = payload(bytes).fold(fail(_), identity)
    val proposal = decoded.proposal.fold(fail("missing proposal"))(identity)
    val expected = ByronVersion.from(65535, 65535, 255).fold(e => fail(e.message), identity)
    assertEquals(proposal.version, expected)
    assertEquals(proposal.raw, bytes)
    assert(
      ByronSig.verify(
        proposal.issuer,
        proposal.signature,
        ByronSig.tag(ByronSig.SignUpdateProposal, ByronCbor.uint(4242)) ++ proposal.signedBody
      )
    )
  }

  import ByronVersion.{Error, Field}
  Vector(
    ("major-overflow", Field.Major, BigInt(65536)),
    ("minor-overflow", Field.Minor, BigInt(65536)),
    ("alt-overflow", Field.Alt, BigInt(256)),
    ("major-truncation", Field.Major, BigInt(4294967297L))
  ).foreach { (name, field, number) =>
    test(s"$name is rejected before registration, matching the raw reference decoder") {
      val bytes = raw(name)
      assert(payload(bytes).isLeft)
      val proposal = Cbor.decode(bytes).fold(fail(_), identity)
      proposal.value match
        case Value.Arr(version +: _) =>
          assertEquals(ByronVersion.decode(version), Left(Error.OutOfRange(field, number)))
        case _ => fail("fixture is not a proposal array")
    }
  }
