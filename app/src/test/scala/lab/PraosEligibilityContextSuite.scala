// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.Bytes

class PraosEligibilityContextSuite extends munit.FunSuite:
  private def get[A](e: Either[String, A]): A = e.fold(fail(_), identity)
  test("active coefficient decimal/scientific parsing is exact and bounded") {
    for text <- Vector("0.05", "5.0e-2", "50e-3") do
      val f = PraosEligibilityContext.coefficient(ReferenceJson.Json.Num(text))
      assertEquals((f.numerator, f.denominator), (BigInt(1), BigInt(20)))
    for value <- Vector(
        ReferenceJson.Json.Num("0"),
        ReferenceJson.Json.Num("-0.05"),
        ReferenceJson.Json.Num("0.75"),
        ReferenceJson.Json.Num("1e1000"),
        ReferenceJson.Json.Num("1e-64"),
        ReferenceJson.Json.Str("0.05"),
        ReferenceJson.Json.Lit("null")
      )
    do intercept[IllegalArgumentException](PraosEligibilityContext.coefficient(value))
  }
  sys.env.get("PRAOS_ELIGIBILITY_EVIDENCE").foreach { directory =>
    val dir = Path.of(directory)
    val protocol = Bytes.fromArray(
      Files.readAllBytes(Path.of(sys.env("PRAOS_PROTOCOL_EVIDENCE"), "pre-protocol-state.md"))
    )
    val pin = get(Bytes.fromHex("2bfe6fc2e8024f8ab337c4eb2e857f06b94b1c4da49fe97a81aa66a7f66c5643"))
    assertEquals(ClusterHeaderObservation.sha256(protocol), pin)
    test("opt-in same-epoch supplied snapshot satisfies actual captured VRF and leader predicate") {
      val prepared = get(PraosEligibilityContext.loadTransfer(dir, protocol, pin))
      assertEquals(prepared.context.epoch, BigInt(0))
      assertEquals(
        (prepared.context.active.numerator, prepared.context.active.denominator),
        (BigInt(1), BigInt(20))
      )
      val (branch, eligible) = get(PraosEligibilityContext.applyPrepared(prepared))
      assertEquals(branch.state.tip.slot, BigInt(199))
      assertEquals(eligible.headers.size, 1)
      assert(eligible.suppliedContextEligibilityVerified)
      assert(!eligible.stateDerivedConsensus && !eligible.referenceRuntimeParity)
    }
    test("opt-in changed nonce has to rebind digest and then still fails VRF proof") {
      val text = new String(protocol.toArray, "UTF-8")
      val original =
        ReferenceJson.string(ReferenceJson.field(ReferenceJson.parse(protocol), "epochNonce"))
      val modified = Bytes.fromArray(text.replace(original, "00" * 32).getBytes("UTF-8"))
      assert(PraosEligibilityContext.loadTransfer(dir, modified, pin).isLeft)
      val changed = get(
        PraosEligibilityContext
          .loadTransfer(dir, modified, ClusterHeaderObservation.sha256(modified))
      )
      assert(
        PraosEligibilityContext
          .applyPrepared(changed)
          .left
          .toOption
          .get
          .contains("VRF certificate rejected")
      )
    }
    test("opt-in absent epoch nonce is rejected rather than defaulted to neutral") {
      val text =
        new String(protocol.toArray, "UTF-8").replace("\"epochNonce\"", "\"unusedEpochNonce\"")
      val modified = Bytes.fromArray(text.getBytes("UTF-8"))
      assert(
        PraosEligibilityContext
          .loadTransfer(dir, modified, ClusterHeaderObservation.sha256(modified))
          .left
          .toOption
          .get
          .contains("epochNonce")
      )
    }
  }
