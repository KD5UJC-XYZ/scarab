// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.DecodeSite.Qualifier
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value as CborValue}
import xyz.kd5ujc.scarab.protocol.Era

class LedgerDecodeErrorSuite extends FunSuite:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)
  private def node(hex: String) = CborRead.decode(bytes(hex)).fold(e => fail(e.toString), identity)

  test("a primitive reader retains the expected type, field and actual value") {
    assertEquals(
      CborRead.uint(node("f5"), DecodeField.Fee),
      Left(
        LedgerDecodeError.Expected(
          DecodeField.Fee,
          LedgerDecodeError.Shape.Unsigned,
          CborValue.Bool(true)
        )
      )
    )
  }

  test("byte length and array arity are separate cases with their measured sizes") {
    assertEquals(
      CborRead.bytesN(node("4100"), 28, DecodeField.PoolId),
      Left(LedgerDecodeError.ByteLength(DecodeField.PoolId, 28, 1))
    )
    assertEquals(
      CborRead.arrayN(node("80"), 2, DecodeField.PoolId),
      Left(LedgerDecodeError.ArrayLength(DecodeField.PoolId, 2, 0))
    )
  }

  test("malformed CBOR preserves the codec cause") {
    val malformed = bytes("81")
    assertEquals(
      CborRead.decode(malformed),
      Cbor.decode(malformed).left.map(LedgerDecodeError.Syntax(_))
    )
  }

  test("a missing transaction field is retained without parsing error text") {
    assertEquals(
      TxBody.decode(bytes("a0"), Era.Babbage).map(_ => ()),
      Left(LedgerDecodeError.MissingField(DecodeField.TransactionBody, DecodeField.Inputs))
    )
  }

  test("address absence and invalid header remain distinguishable") {
    assertEquals(Address.decode(Bytes.empty), Left(LedgerDecodeError.Empty(DecodeField.Address)))
    assertEquals(Address.decode(bytes("f0")), Left(LedgerDecodeError.AddressHeader(240)))
  }

  test("unknown certificate and script tags preserve the numeric tag") {
    assertEquals(
      Certificate.decode(node("811863"), DecoderVersion.of(Era.Conway)),
      Left(LedgerDecodeError.TaggedArity(DecodeField.Certificate, 99, 1))
    )
    assertEquals(
      Script.decode(node("82186300")),
      Left(LedgerDecodeError.UnknownTag(DecodeField.ScriptType, 99))
    )
  }

  test("native script shape failures retain their structured ledger cause") {
    assertEquals(
      NativeScript.decode(node("8100")),
      Left(
        NativeScript.DecodeError.Malformed(
          LedgerDecodeError.TaggedArity(DecodeField.NativeScript, 0, 1)
        )
      )
    )
  }

  test("voting threshold arities are specific and carry actual counts") {
    assertEquals(
      PoolVotingThresholds.fromVector(Vector.empty),
      Left(LedgerDecodeError.ArrayLength(DecodeField.PoolVotingThresholds, 5, 0))
    )
    assertEquals(
      DRepVotingThresholds.fromVector(Vector.empty),
      Left(LedgerDecodeError.ArrayLength(DecodeField.DRepVotingThresholds, 10, 0))
    )
  }

  test("native script Word64 tags cannot wrap into a recognized constructor") {
    for (tag, encodedTag) <- Vector(
        (BigInt(1) << 32) -> "1b0000000100000000",
        ((BigInt(1) << 32) + 4) -> "1b0000000100000004",
        ((BigInt(1) << 64) - 1) -> "1bffffffffffffffff"
      )
    do
      assertEquals(
        NativeScript.decode(node("82" + encodedTag + "581c" + "00" * 28)),
        Left(
          NativeScript.DecodeError.Malformed(
            LedgerDecodeError.TaggedArity(DecodeField.NativeScript, tag, 2)
          )
        )
      )
  }

  test("a site renders as the label the decoders reported before sites were typed") {
    assertEquals(
      LedgerDecodeError.DuplicateKey(DecodeField.CostModels).toString,
      "DuplicateKey(cost models)"
    )
    assertEquals(
      LedgerDecodeError.Empty(DecodeField.Value / Qualifier.Assets).message,
      "Empty(value assets)"
    )
    assertEquals(
      LedgerDecodeError
        .Expected(DecodeField.Fee, LedgerDecodeError.Shape.Unsigned, CborValue.Null)
        .message,
      "fee: expected Unsigned"
    )
    assertEquals(
      LedgerDecodeError.MissingField(DecodeField.NativeScript, DecodeSite.Index(1)).toString,
      "MissingField(native script,1)"
    )
    assertEquals(
      LedgerDecodeError.ByteLength(DecodeField.PoolId, 28, 1).toString,
      "ByteLength(pool id,28,1)"
    )
    assertEquals(
      LedgerDecodeError.GenesisMissingField(byron.ByronGenesisKey.MaxTxSize).toString,
      "GenesisMissingField(maxTxSize)"
    )
    assertEquals(
      LedgerDecodeError
        .NativeScript(
          NativeScript.DecodeError.Malformed(
            LedgerDecodeError.TaggedArity(DecodeField.NativeScript, 0, 1)
          )
        )
        .toString,
      "NativeScript(Malformed(TaggedArity(native script,0,1)))"
    )
    assertEquals(
      ChainError
        .Decode(DecodeField.Block, LedgerDecodeError.Syntax(Cbor.Error.UnexpectedEnd))
        .message,
      "decode block: Syntax(UnexpectedEnd)"
    )
  }
