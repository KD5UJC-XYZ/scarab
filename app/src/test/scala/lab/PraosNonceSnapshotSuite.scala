// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.header.PraosNonceEvolution as Nonces

class PraosNonceSnapshotSuite extends munit.FunSuite:
  private def get[A](e: Either[String, A]): A = e.fold(fail(_), identity)
  private def hex(s: String): Bytes = get(Bytes.fromHex(s))
  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes("UTF-8"))
  private val minimal =
    """{"lastSlot":1,"evolvingNonce":null,"candidateNonce":null,"epochNonce":null,"labNonce":null,"lastEpochBlockNonce":null}"""
  test("snapshot absence is explicit unknown; other nonce fields cannot silently default") {
    val bytes = raw(minimal)
    val parsed = get(PraosNonceSnapshot.parse(bytes, ClusterHeaderObservation.sha256(bytes)))
    assertEquals(parsed.fields.previousEpoch, None)
    val known = raw(minimal.dropRight(1) + ",\"previousEpochNonce\":null}")
    assertEquals(
      get(
        PraosNonceSnapshot.parse(known, ClusterHeaderObservation.sha256(known))
      ).fields.previousEpoch,
      Some(Nonces.Nonce.Neutral)
    )
    val missing = raw(minimal.replace("\"candidateNonce\":null,", ""))
    assert(PraosNonceSnapshot.parse(missing, ClusterHeaderObservation.sha256(missing)).isLeft)
    assert(PraosNonceSnapshot.parse(missing, ClusterHeaderObservation.sha256(bytes)).isLeft)
    val bad = raw(minimal.replace("\"epochNonce\":null", "\"epochNonce\":\"00\""))
    assert(PraosNonceSnapshot.parse(bad, ClusterHeaderObservation.sha256(bad)).isLeft)
  }
  sys.env.get("NONCE_TRANSFER_EVIDENCE").foreach { directory =>
    val dir = Path.of(directory)
    val protocolDir = Path.of(sys.env("NONCE_PROTOCOL_EVIDENCE"))
    def read(p: Path): Bytes = Bytes.fromArray(Files.readAllBytes(p))
    val pre = read(protocolDir.resolve("pre-protocol-state.md"))
    val post = read(protocolDir.resolve("post-protocol-state.md"))
    val prePin = hex("2bfe6fc2e8024f8ab337c4eb2e857f06b94b1c4da49fe97a81aa66a7f66c5643")
    val postPin = hex("ec20238d170f26d8183d7fa8d12c0220b46b959044fdeb33f4393d0c19fd2678")
    val genesis = read(dir.resolve("transfer-genesis.md"))
    def certificate = get(CertificateBranch.loadTransfer(dir, pre, prePin))
    test(
      "opt-in retained complete one-header interval matches every exported nonce and undoes exactly"
    ) {
      val c = certificate
      val p = get(PraosNonceSnapshot.bind(c.context, c.seed, genesis, pre, prePin))
      val branch = get(CertificateBranch.replay(c.context, c.seed, c.acquisition))
      assertEquals(branch.steps.size, 1)
      val step = get(Nonces.applyHeader(p.context, p.seed, branch.steps.head))
      val expected = get(PraosNonceSnapshot.parse(post, postPin))
      assertEquals(step.after.lastSlot, expected.lastSlot)
      assertEquals(step.after.fields, expected.fields)
      assertEquals(step.epochNonceUsed, p.seed.fields.epoch)
      assertEquals(step.after.fields.previousEpoch, None)
      assertEquals(get(Nonces.undo(step.after, step)).id, p.seed.id)
      assertEquals(
        get(
          Nonces.applyHeader(p.context, get(Nonces.undo(step.after, step)), branch.steps.head)
        ).after.id,
        step.after.id
      )
      assert(Nonces.applyHeader(p.context, step.after, branch.steps.head).isLeft)
      assert(Nonces.undo(p.seed, step).isLeft)
      assert(
        step.nonceTransitionChecked && step.vrfProofChecked && !step.leaderEligibilityChecked && !step.stateDerivedConsensus
      )
    }
    test("opt-in wrong nonce, source, anchor, counter snapshot and fork state reject") {
      val c = certificate
      val p = get(PraosNonceSnapshot.bind(c.context, c.seed, genesis, pre, prePin))
      val branch = get(CertificateBranch.replay(c.context, c.seed, c.acquisition))
      val changed = raw(
        new String(pre.toArray, "UTF-8")
          .replace(
            ReferenceJson.string(ReferenceJson.field(ReferenceJson.parse(pre), "epochNonce")),
            "00" * 32
          )
      )
      assert(PraosNonceSnapshot.bind(c.context, c.seed, genesis, changed, prePin).isLeft)
      val rebound = get(
        PraosNonceSnapshot
          .bind(c.context, c.seed, genesis, changed, ClusterHeaderObservation.sha256(changed))
      )
      assert(Nonces.applyHeader(rebound.context, rebound.seed, branch.steps.head).isLeft)
      val slot =
        raw(new String(pre.toArray, "UTF-8").replace("\"lastSlot\": 127", "\"lastSlot\": 126"))
      assert(
        PraosNonceSnapshot
          .bind(c.context, c.seed, genesis, slot, ClusterHeaderObservation.sha256(slot))
          .isLeft
      )
      val wrongCounters = raw(new String(pre.toArray, "UTF-8").replace(": 0", ": 1"))
      assert(
        PraosNonceSnapshot
          .bind(
            c.context,
            c.seed,
            genesis,
            wrongCounters,
            ClusterHeaderObservation.sha256(wrongCounters)
          )
          .isLeft
      )
      val alternative = get(
        Nonces.seed(p.context, c.seed, p.seed.fields.copy(candidate = Nonces.Nonce.Neutral), prePin)
      )
      val a = get(Nonces.applyHeader(p.context, p.seed, branch.steps.head))
      val b = get(Nonces.applyHeader(p.context, alternative, branch.steps.head))
      assert(Nonces.undo(a.after, b).isLeft)
    }
  }
