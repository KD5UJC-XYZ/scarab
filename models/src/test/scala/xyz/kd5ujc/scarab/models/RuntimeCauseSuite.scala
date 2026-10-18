// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError, Decoded, Node, Writer}

class RuntimeCauseSuite extends FunSuite:
  test("generated-model decoding retains malformed CBOR as a typed syntax cause") {
    val result = Codec.bytes.decodeBytes(Bytes.empty)
    assertEquals(result.left.map(_.kind), Left(DecodeError.Kind.Syntax(Cbor.Error.UnexpectedEnd)))
  }

  test("a decoder defect retains the original throwable") {
    val cause = new IllegalStateException("decoder defect")
    val broken = new Codec[Unit]:
      def decode(node: Node): Decoded[Unit] = throw cause
      def encode(value: Unit, out: Writer): Unit = ()
    val valid = Bytes.fromHex("00").fold(e => fail(e.message), identity)
    broken.decodeBytes(valid) match
      case Left(DecodeError(DecodeError.Kind.Internal(actual), _, _, _)) => assert(actual eq cause)
      case other => fail(s"expected retained decoder defect, got $other")
  }
