// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.DefaultFun
import xyz.kd5ujc.scarab.uplc.builtin.Outcome
import CoreTesting.*

/** The reference's verdicts on the unlifting bounds of the `ensurable` variants
  * (`fixtures/reference/plutus-unlifting-bounds/verdicts.jsonl`): each of the 35 builtins that D
  * and E read as `CInteger` or `CByteString`, with that argument at its bound and one past it, in
  * variants C, D and E. The probes are those of `UnliftingBoundsCorpus` (`plutus` test tree),
  * rebuilt here as builtin applications: a probe discards the value, so it fails exactly when the
  * application does.
  *
  * For a core builtin the whole application is compared. For the others the denotation is not here,
  * so the verdict is compared with the unlifting alone: a failure must be the unlifting's, and a
  * success must unlift. The budgets of the rows need the machine and the costing and are not
  * compared.
  */
final class UnliftingBoundsReferenceSuite extends munit.FunSuite:
  private val max = (BigInt(1) << 262143) - 1
  private val min = -(BigInt(1) << 262143)
  private def a(n: Int): Probe = bytes(Bytes.wrap(Array.fill[Byte](n)(0x61)))
  private val small = bytes("61")
  private val schnorrKey = bytes("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
  private val edKey = bytes(Bytes.wrap(new Array[Byte](32)))
  private val signature = bytes(Bytes.wrap(new Array[Byte](64)))

  /** The holes of each builtin, as `UnliftingBoundsCorpus.holes` has them. */
  private val holes: Vector[(String, Vector[Probe => Vector[Probe]])] =
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
    ).map(f => f -> Vector[Probe => Vector[Probe]](x => Vector(x, int(1)), x => Vector(int(1), x)))
    val bytes2 = Vector(
      "appendByteString",
      "equalsByteString",
      "lessThanByteString",
      "lessThanEqualsByteString"
    ).map(f => f -> Vector[Probe => Vector[Probe]](x => Vector(x, small), x => Vector(small, x)))
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
    ).map(f => f -> Vector[Probe => Vector[Probe]](x => Vector(x)))
    val indexed = Vector("indexByteString", "readBit").map(f =>
      f -> Vector[Probe => Vector[Probe]](x => Vector(x, int(0)))
    )
    val hashToGroup = Vector("bls12_381_G1_hashToGroup", "bls12_381_G2_hashToGroup").map(f =>
      f -> Vector[Probe => Vector[Probe]](x => Vector(x, small))
    )
    val logical = Vector("andByteString", "orByteString", "xorByteString").map(f =>
      f -> Vector[Probe => Vector[Probe]](
        x => Vector(bool(false), x, small),
        x => Vector(bool(false), small, x)
      )
    )
    integers ++ bytes2 ++ bytes1 ++ indexed ++ hashToGroup ++ logical ++ Vector(
      "sliceByteString" -> Vector[Probe => Vector[Probe]](x => Vector(int(0), int(1), x)),
      "verifyEd25519Signature" -> Vector[Probe => Vector[Probe]](x => Vector(edKey, x, signature)),
      "verifySchnorrSecp256k1Signature" -> Vector[Probe => Vector[Probe]](x =>
        Vector(schnorrKey, x, signature)
      ),
      "byteStringToInteger" -> Vector[Probe => Vector[Probe]](x => Vector(bool(true), x)),
      "consByteString" -> Vector[Probe => Vector[Probe]](
        x => Vector(x, small),
        x => Vector(int(97), x)
      )
    )

  private def values(integerHole: Boolean): Vector[(String, Probe)] =
    if integerHole then
      Vector("max" -> int(max), "max+1" -> int(max + 1), "min" -> int(min), "min-1" -> int(min - 1))
    else Vector("65536" -> a(65536), "65537" -> a(65537))

  private def integerHole(fun: String, at: Int): Boolean =
    fun.endsWith("Integer") && !fun.startsWith("byteString") || (fun == "consByteString" && at == 0)

  private val probes: Map[String, (DefaultFun, Vector[Probe])] = (for
    (fun, fills) <- holes
    (fill, at) <- fills.zipWithIndex
    (label, value) <- values(integerHole(fun, at))
  yield s"$fun-arg$at-$label" -> (
    DefaultFun.fromName(fun).getOrElse(sys.error(fun)),
    fill(value)
  )).toMap

  private val verdicts =
    ReferenceVerdicts.read("fixtures/reference/plutus-unlifting-bounds/verdicts.jsonl")

  test("the probes are the reference's: 142 of them, each run in C, D and E") {
    assertEquals(probes.size, 142, "probes")
    assertEquals(verdicts.map(_.id).toSet, probes.keySet, "the ids of the verdicts")
    assertEquals(verdicts.size, 3 * 142, "rows")
  }

  test("every verdict: the core builtins in full, the others' unlifting") {
    val core = CoreDenotations.functions
    for verdict <- verdicts do
      val (function, args) = probes(verdict.id)
      val v = verdict.variant
      val where = s"${verdict.id} in V${verdict.language} at ${verdict.protocol} ($v)"
      if core.contains(function) then
        val succeeded = CoreTesting(v, function, args*) match
          case Outcome.Success(_, _) => true
          case Outcome.Failure(_, _) => false
        assertEquals(succeeded, verdict.succeeded, where)
      else assertEquals(CoreUnlifting(v)(function, args).isRight, verdict.succeeded, where)
  }
