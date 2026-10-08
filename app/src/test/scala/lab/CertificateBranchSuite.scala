// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.header.PraosCertificateState as Certificate
import lab.network.ChainSync

class CertificateBranchSuite extends munit.FunSuite:
  private def get[A](e: Either[String, A]): A = e.fold(fail(_), identity)
  private def hex(s: String): Bytes = get(Bytes.fromHex(s))
  private val hash = Bytes(Vector.fill(32)(1.toByte))
  private val pool = Bytes(Vector.fill(28)(2.toByte))
  private val point = Certificate.Point(hash, 1, 1)
  private val wirePoint = ChainSync.Point.Block(get(ChainSync.UInt64.from(1)), hash)
  private val context = get(
    Certificate.Context.checked(hash, hash, 0, 499, 129600, 60, Map(pool -> hash))
  )
  private val seed = get(Certificate.seed(context, point, Map.empty, hash))
  private val checkpoint = get(BoundedChainFollower.checked(wirePoint, Vector.empty))

  test("empty checked branch preserves the explicit seed and rolls back only inside its window") {
    val branch = get(CertificateBranch.replay(context, seed, checkpoint))
    assertEquals(branch.state.id, seed.id)
    assertEquals(get(CertificateBranch.rollback(branch, wirePoint)).state.id, seed.id)
    assert(CertificateBranch.rollback(branch, ChainSync.Point.Origin).isLeft)
  }
  test("wrong anchor/context and malformed acquisition cannot advance a certificate branch") {
    val bad = get(Certificate.seed(context, point.copy(slot = 2), Map.empty, hash))
    assert(CertificateBranch.replay(context, bad, checkpoint).isLeft)
    val other = get(Certificate.Context.checked(hash, hash, 0, 500, 129600, 60, Map(pool -> hash)))
    assert(CertificateBranch.replay(other, seed, checkpoint).isLeft)
    val branch = get(CertificateBranch.replay(context, seed, checkpoint))
    assert(
      CertificateBranch
        .append(context, branch, BoundedChainFollower.Original(Bytes.empty, Bytes.empty))
        .isLeft
    )
    assertEquals(branch.state.id, seed.id)
  }
  sys.env.get("CERTIFICATE_TRANSFER_EVIDENCE").foreach { directory =>
    val dir = Path.of(directory)
    val stateDirectory = Path.of(sys.env("CERTIFICATE_PROTOCOL_EVIDENCE"))
    def raw(name: String): Bytes = Bytes.fromArray(Files.readAllBytes(stateDirectory.resolve(name)))
    val pre = raw("pre-protocol-state.md")
    val post = raw("post-protocol-state.md")
    val prePin = hex("2bfe6fc2e8024f8ab337c4eb2e857f06b94b1c4da49fe97a81aa66a7f66c5643")
    val postPin = hex("ec20238d170f26d8183d7fa8d12c0220b46b959044fdeb33f4393d0c19fd2678")
    assertEquals(ClusterHeaderObservation.sha256(pre), prePin)
    assertEquals(ClusterHeaderObservation.sha256(post), postPin)
    def prepared = get(CertificateBranch.loadTransfer(dir, pre, prePin))
    test("opt-in actual counter snapshot advances with captured header and matches post map") {
      val p = prepared
      val branch = get(CertificateBranch.replay(p.context, p.seed, p.acquisition))
      val expected = ReferenceJson.field(ReferenceJson.parse(post), "oCertCounters") match
        case ReferenceJson.Json.Obj(entries) =>
          entries.map((key, n) => hex(key) -> ReferenceJson.uint(n))
        case _ => fail("counter map required")
      assertEquals(branch.state.counters, expected)
      assertEquals(branch.state.tip.slot, BigInt(199))
      assertEquals(branch.steps.size, 1)
      assert(branch.steps.forall(s => !s.consensusValidated && !s.vrfEligibilityChecked))
      val restored = get(CertificateBranch.rollback(branch, branch.acquisition.anchor))
      assertEquals(restored.state.id, p.seed.id)
      assertEquals(restored.state.counters, p.seed.counters)
      val reapplied =
        get(CertificateBranch.append(p.context, restored, p.acquisition.originals.head))
      assertEquals(reapplied.state.id, branch.state.id)
      assert(CertificateBranch.append(p.context, branch, p.acquisition.originals.head).isLeft)
    }
    test("opt-in synthetic missing-entry fork restores absence, not decremented zero") {
      val p = prepared
      val actual = get(CertificateBranch.replay(p.context, p.seed, p.acquisition))
      val issuer = actual.steps.head.issuer
      // Deliberate synthetic fork test, NOT the reference snapshot used above.
      val synthetic = get(Certificate.seed(p.context, p.seed.tip, p.seed.counters - issuer, hash))
      val fork = get(CertificateBranch.replay(p.context, synthetic, p.acquisition))
      assertEquals(fork.state.counters(issuer), BigInt(0))
      assert(
        !get(CertificateBranch.rollback(fork, fork.acquisition.anchor)).state.counters
          .contains(issuer)
      )
      assert(Certificate.undo(actual.state, fork.steps.head).isLeft)
    }
    test("opt-in counter source digest and slot binding reject modified snapshots") {
      val modified = Bytes.fromArray(
        new String(pre.toArray, "UTF-8")
          .replace("\"lastSlot\": 127", "\"lastSlot\": 126")
          .getBytes("UTF-8")
      )
      assertNotEquals(modified, pre)
      assert(CertificateBranch.loadTransfer(dir, modified, prePin).isLeft)
      assert(
        CertificateBranch
          .loadTransfer(dir, modified, ClusterHeaderObservation.sha256(modified))
          .left
          .toOption
          .get
          .contains("snapshot slot mismatch")
      )
    }
  }
