// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import munit.FunSuite

class DomainErrorContractSuite extends FunSuite:
  test("an encoder preserves its own narrow error type and cause identity") {
    val cause = Cbor.Error.InvalidAdditionalInformation(31)
    val encoder = new Encoder[Int]:
      type Cause = Cbor.Error
      def encode(value: Int): Either[Cause, Bytes] = Left(cause)
    val retained: Either[Cbor.Error, Bytes] = encoder.encode(1)
    assert(retained.left.exists(_ eq cause))
  }

  test("associated error types reject arbitrary products") {
    assert(
      compileErrors(
        """new xyz.kd5ujc.scarab.codec.Encoder[Int] { type Cause = String; def encode(value: Int): Either[Cause, xyz.kd5ujc.scarab.codec.Bytes] = ??? }"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """new xyz.kd5ujc.scarab.codec.Encoder[Int] { type Cause = Some[String]; def encode(value: Int): Either[Cause, xyz.kd5ujc.scarab.codec.Bytes] = ??? }"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """new xyz.kd5ujc.scarab.codec.Encoder[Int] { type Cause = (Int, String); def encode(value: Int): Either[Cause, xyz.kd5ujc.scarab.codec.Bytes] = ??? }"""
      ).nonEmpty
    )
  }

  test("refined encoder and serde aliases require domain errors") {
    assert(
      compileErrors("""type Bad = xyz.kd5ujc.scarab.codec.Encoder.Aux[Int, String]""").nonEmpty
    )
    assert(
      compileErrors(
        """type Bad = xyz.kd5ujc.scarab.codec.Encoder.Aux[Int, Some[String]]"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """type Bad = xyz.kd5ujc.scarab.codec.Encoder.Aux[Int, (Int, String)]"""
      ).nonEmpty
    )
    assert(compileErrors("""type Bad = xyz.kd5ujc.scarab.codec.Serde.Aux[Int, String]""").nonEmpty)
    assert(
      compileErrors("""type Bad = xyz.kd5ujc.scarab.codec.Serde.Aux[Int, Some[String]]""").nonEmpty
    )
    assert(
      compileErrors("""type Bad = xyz.kd5ujc.scarab.codec.Serde.Aux[Int, (Int, String)]""").nonEmpty
    )
  }
