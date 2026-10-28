// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.file.{Files, Path}
import scalus.uplc.Program
import xyz.kd5ujc.scarab.codec.Bytes

/** Phase-2 probes of the `ensurable` variants' unlifting bounds (issue 498): for each of the 35
  * builtins and each argument it takes as `CInteger` or `CByteString`, the application with that
  * argument at the bound (`2^262143 - 1`, `-2^262143`, 65,536 bytes) and one past it, the other
  * arguments small and valid. The value is discarded, so a probe succeeds unless the application
  * fails. Nothing here computes an expected value: the reference verdicts and budgets are in
  * `fixtures/reference/plutus-unlifting-bounds/verdicts.jsonl`.
  */
object UnliftingBoundsCorpus:
  final case class Probe(id: String, script: Bytes)

  private val max = (BigInt(1) << 262143) - 1
  private val min = -(BigInt(1) << 262143)
  private def integer(n: BigInt) = s"(con integer $n)"
  // `a` bytes, so that a probe of decodeUtf8 is valid UTF-8 at the bound
  private def bytes(n: Int) = s"(con bytestring #${"61" * n})"
  private val small = "(con bytestring #61)"
  // the x-only secp256k1 generator, a valid Schnorr key; the signature verifies to False
  private val schnorrKey =
    "(con bytestring #79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798)"
  private val edKey = s"(con bytestring #${"00" * 32})"
  private val signature = s"(con bytestring #${"00" * 64})"

  private def app(fun: String, args: String*): String =
    args.foldLeft(s"(builtin $fun)")((f, a) => s"[ $f $a ]")

  /** The probe bodies of one builtin: for each bounded argument, a hole filled at and past the
    * bound.
    */
  private def holes: Vector[(String, Vector[String => String])] =
    val integers = Vector(
      "addInteger",
      "subtractInteger",
      "multiplyInteger",
      "divideInteger",
      "quotientInteger",
      "remainderInteger",
      "modInteger",
      "lessThanInteger",
      "lessThanEqualsInteger"
    ).map(f =>
      f -> Vector[String => String](x => app(f, x, integer(1)), x => app(f, integer(1), x))
    )
    val bytes2 = Vector(
      "appendByteString",
      "equalsByteString",
      "lessThanByteString",
      "lessThanEqualsByteString"
    ).map(f => f -> Vector[String => String](x => app(f, x, small), x => app(f, small, x)))
    val bytes1 = Vector(
      "sha2_256",
      "sha3_256",
      "blake2b_256",
      "blake2b_224",
      "keccak_256",
      "ripemd_160",
      "decodeUtf8",
      "complementByteString",
      "countSetBits",
      "findFirstSetBit"
    )
      .map(f => f -> Vector[String => String](x => app(f, x)))
    val indexed = Vector("indexByteString", "readBit").map(f =>
      f -> Vector[String => String](x => app(f, x, integer(0)))
    )
    val hashToGroup = Vector("bls12_381_G1_hashToGroup", "bls12_381_G2_hashToGroup").map(f =>
      f -> Vector[String => String](x => app(f, x, small))
    )
    val logical = Vector("andByteString", "orByteString", "xorByteString").map(f =>
      f -> Vector[String => String](
        x => app(f, "(con bool False)", x, small),
        x => app(f, "(con bool False)", small, x)
      )
    )
    integers ++ bytes2 ++ bytes1 ++ indexed ++ hashToGroup ++ logical ++ Vector(
      "sliceByteString" -> Vector[String => String](x =>
        app("sliceByteString", integer(0), integer(1), x)
      ),
      "verifyEd25519Signature" -> Vector[String => String](x =>
        app("verifyEd25519Signature", edKey, x, signature)
      ),
      "verifySchnorrSecp256k1Signature" -> Vector[String => String](x =>
        app("verifySchnorrSecp256k1Signature", schnorrKey, x, signature)
      ),
      "byteStringToInteger" -> Vector[String => String](x =>
        app("byteStringToInteger", "(con bool True)", x)
      ),
      "consByteString" -> Vector[String => String](
        x => app("consByteString", x, small),
        x => app("consByteString", integer(97), x)
      )
    )

  /** The values a hole of each kind is filled with: at the bound and past it. */
  private def values(integerHole: Boolean): Vector[(String, String)] =
    if integerHole then
      Vector(
        "max" -> integer(max),
        "max+1" -> integer(max + 1),
        "min" -> integer(min),
        "min-1" -> integer(min - 1)
      )
    else Vector("65536" -> bytes(65536), "65537" -> bytes(65537))

  /** Whether hole `at` of `fun` takes an integer. */
  private def integerHole(fun: String, at: Int): Boolean =
    fun.endsWith("Integer") && !fun.startsWith("byteString") || (fun == "consByteString" && at == 0)

  private def compiled(body: String): Bytes =
    Bytes.fromArray(
      Program
        .parseUplc(s"(program 1.0.0 [ (lam r (con unit ())) $body ])")
        .fold(e => sys.error(e.toString), _.cborEncoded)
    )

  lazy val probes: Vector[Probe] = for
    (fun, fills) <- holes
    (fill, at) <- fills.zipWithIndex
    (label, value) <- values(integerHole(fun, at))
  yield Probe(s"$fun-arg$at-$label", compiled(fill(value)))

  /** The languages and protocols: variants D (V2 at 11) and E (V3 at 11), and C (V3 at 10), which
    * has no bound.
    */
  val settings: Vector[(PlutusLanguage, Int)] =
    Vector(PlutusLanguage.V2 -> 11, PlutusLanguage.V3 -> 11, PlutusLanguage.V3 -> 10)

  val budget: ExUnits = ExUnits(BigInt(1) << 40, BigInt(1) << 60)

  /** Writes the requests for the reference costing oracle (`scripts/oracle/plutus/costing`). */
  def main(args: Array[String]): Unit =
    val out = Path.of(args.headOption.getOrElse("/oracle/unlifting-bounds-requests.jsonl"))
    val rows = for
      probe <- probes
      (language, protocol) <- settings
    yield Json.obj(
      "id" -> Json.fromString(probe.id),
      "language" -> Json.fromInt(language.id + 1),
      "protocol" -> Json.fromInt(protocol),
      "script" -> Json.fromString(probe.script.hex),
      "costModel" -> Json
        .arr(PlutusBoundaryCorpus.costModel(language, protocol).map(Json.fromLong)*),
      "cpu" -> Json.fromBigInt(budget.steps),
      "memory" -> Json.fromBigInt(budget.mem)
    )
    Files.writeString(out, rows.map(_.noSpaces).mkString("\n") + "\n")
