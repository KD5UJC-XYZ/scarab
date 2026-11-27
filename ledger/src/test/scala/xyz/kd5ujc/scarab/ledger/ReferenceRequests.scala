// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.{Decoder, Json}
import java.security.MessageDigest
import munit.Assertions
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.plutus.{EvalRequest, PlutusLanguage}
import xyz.kd5ujc.scarab.uplc.builtin.core.DataSerialisation

/** A request the node collected against one the reference recorded (`PlutusWithContext`,
  * cardano-ledger f649f975 `Plutus/Evaluate.hs`): every field of a request kept whole, or every
  * digest of one kept as digests. Shared by the suites that hold the node's contexts to the
  * recorded ones (`RecordedScriptContextsSuite`, the era types' `BabbagePlutusContextSuite`).
  */
object ReferenceRequests extends Assertions:
  private def field[A: Decoder](j: Json, name: String): A =
    j.hcursor.get[A](name).fold(e => fail(s"$name: ${e.getMessage}"), identity)

  private def hex(text: String): Bytes = Bytes.fromHex(text).fold(e => fail(e.toString), identity)

  private def cbor(bytes: Bytes): Node = Cbor.decode(bytes).fold(e => fail(e.toString), identity)

  /** The SHA-256 of `bytes`, in hexadecimal, as the recordings name a digest. */
  def sha256(bytes: Bytes): String =
    Bytes.wrap(MessageDigest.getInstance("SHA-256").digest(bytes.toArray)).hex

  /** Equal bytes, or a failure naming the first byte that differs and the bytes around it. */
  def sameBytes(ours: Bytes, theirs: Bytes, at: String): Unit =
    if ours != theirs then
      val first = ours.value.zip(theirs.value).indexWhere(_ != _) match
        case -1 => math.min(ours.size, theirs.size)
        case i  => i
      def around(b: Bytes) = b.slice(math.max(0, first - 8), math.min(b.size, first + 24)).hex
      fail(
        s"$at: first difference at byte $first of ${ours.size} (node) and ${theirs.size} " +
          s"(reference); from byte ${math.max(0, first - 8)}: node ${around(ours)}, " +
          s"reference ${around(theirs)}"
      )

  def integer(node: Node): BigInt = node.value match
    case Value.UInt(value) => value
    case Value.NInt(value) => value
    case other             => fail(s"not an integer: $other")

  /** A request of the reference: its `PlutusWithContext` encoding, or that encoding's digests. */
  enum Reference:
    case Whole(encoding: Bytes)
    case Digested(request: Json)

  def reference(script: Json): Reference =
    script.hcursor.downField("contextCbor").focus.flatMap(_.asString) match
      case Some(encoding) => Reference.Whole(hex(encoding))
      case None           => Reference.Digested(field[Json](script, "request"))

  def sameRequest(ours: EvalRequest, theirs: Reference, at: String): Unit = theirs match
    case Reference.Whole(encoding)   => sameEncoding(ours, encoding, at)
    case Reference.Digested(request) => sameDigests(ours, request, at)

  /** The node's request against the digests of the reference's: every field, each argument and the
    * script by the SHA-256 of its bytes.
    */
  def sameDigests(ours: EvalRequest, theirs: Json, at: String): Unit =
    assertEquals(
      BigInt(ours.protocolMajor),
      field[BigInt](theirs, "protocolMajor"),
      s"$at: version"
    )
    assertEquals(BigInt(ours.language.id), field[BigInt](theirs, "language"), s"$at: language")
    assertEquals(sha256(ours.script), field[String](theirs, "scriptSha256"), s"$at: script")
    val expected = field[Vector[String]](theirs, "argumentsSha256")
    assertEquals(ours.args.size, expected.size, s"$at: number of arguments")
    ours.args.zip(expected).zipWithIndex.foreach { case ((argument, digest), i) =>
      assertEquals(
        sha256(DataSerialisation.serialise(argument)),
        digest,
        s"$at: argument $i, the SHA-256 of its bytes"
      )
    }
    val budget = field[Json](theirs, "budget")
    assertEquals(
      (ours.budget.mem, ours.budget.steps),
      (field[BigInt](budget, "mem"), field[BigInt](budget, "steps")),
      s"$at: budget"
    )
    assertEquals(
      ours.costModel.map(BigInt(_)),
      field[Vector[BigInt]](theirs, "costModel"),
      s"$at: cost model"
    )

  /** The node's request against the reference's `PlutusWithContext` encoding. */
  def sameEncoding(ours: EvalRequest, theirs: Bytes, at: String): Unit =
    cbor(theirs).value match
      case Value.Arr(Vector(version, script, arguments, budget, model)) =>
        assertEquals(integer(version), BigInt(ours.protocolMajor), s"$at: protocol version")
        script.value match
          case Value.Arr(Vector(language, bytes)) =>
            assertEquals(integer(language), BigInt(ours.language.id), s"$at: language")
            assertEquals(bytes.value, Value.ByteString(ours.script), s"$at: script")
          case other => fail(s"$at: not a script: $other")
        val expected = ours.language match
          case PlutusLanguage.V1 | PlutusLanguage.V2 =>
            arguments.value match
              case Value.Arr(items) => items.map(_.original)
              case other            => fail(s"$at: not an argument list: $other")
          case PlutusLanguage.V3 | PlutusLanguage.V4 => Vector(arguments.original)
        assertEquals(ours.args.size, expected.size, s"$at: number of arguments")
        ours.args.zip(expected).zipWithIndex.foreach { case ((argument, reference), i) =>
          sameBytes(DataSerialisation.serialise(argument), reference, s"$at: argument $i")
        }
        budget.value match
          case Value.Arr(Vector(mem, steps)) =>
            assertEquals((integer(mem), integer(steps)), (ours.budget.mem, ours.budget.steps), at)
          case other => fail(s"$at: not a budget: $other")
        model.value match
          case Value.Arr(values) =>
            assertEquals(values.map(integer), ours.costModel.map(BigInt(_)), s"$at: cost model")
          case other => fail(s"$at: not a cost model: $other")
      case other => fail(s"$at: not a PlutusWithContext: $other")
