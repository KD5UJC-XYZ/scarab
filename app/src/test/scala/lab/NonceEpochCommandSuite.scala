// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.unsafe.implicits.global
import lab.cbor.Bytes
import lab.header.PraosNonceEvolution as Nonces

class NonceEpochCommandSuite extends munit.FunSuite:
  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes("UTF-8"))
  private def tips(slot: Int, block: Int, epoch: Int): Bytes =
    val tip =
      s"""{"era":"Conway","hash":"${"12" * 32}","slot":$slot,"block":$block,"epoch":$epoch}"""
    raw(s"[$tip,$tip]")
  private val ledger = raw(
    s"""{"lastEpoch":1,"stakeDistrib":{"unPoolDistr":{"${"34" * 28}":{"individualPoolStakeVrf":"${"56" * 32}"}}}}"""
  )
  private val protocol = raw(
    s"""{"lastSlot":920,"oCertCounters":{"${"34" * 28}":0},"epochNonce":null,"candidateNonce":null,"evolvingNonce":null,"labNonce":null,"lastEpochBlockNonce":null}"""
  )
  private def files: Map[String, Bytes] = Map(
    "transfer-genesis.md" -> raw(
      """{"networkId":"Testnet","networkMagic":1082026,"epochLength":500,"securityParam":5,"activeSlotsCoeff":0.05,"slotsPerKESPeriod":129600,"maxKESEvolutions":60}"""
    ),
    "pre-tips.md" -> tips(920, 3, 1),
    "post-tips.md" -> tips(1120, 5, 2),
    "pre-protocol-state.md" -> protocol,
    "post-protocol-state.md" -> protocol,
    "pre-ledger-state.md" -> ledger,
    "post-ledger-state.md" -> ledger,
    "pre-parameters.md" -> raw("""{"protocolVersion":{"major":9,"minor":0}}"""),
    "post-parameters.md" -> raw("""{"protocolVersion":{"major":9,"minor":0}}""")
  )
  private def manifest(originals: Map[String, Bytes]): Bytes = raw(
    "format\tpraos-nonce-epoch-rotation-v1\nkeyMode\tpre-anchor-supplied-verification-keys-v1\n" +
      NonceEpochCommand.sources.toVector
        .sortBy(_._1)
        .map((key, name) => key + "\t" + ClusterHeaderObservation.sha256(originals(name)).hex)
        .mkString("\n") + "\n"
  )
  private def bind(originals: Map[String, Bytes]) =
    NonceEpochCommand.bind(manifest(originals), originals)
  private def bound = bind(files).fold(e => fail(e.toString), identity)
  test(
    "explicit supplied-key assumption and profile bind state attribution without claiming authority"
  ) {
    val b = bound
    val expected = ClusterHeaderObservation.sha256(
      raw(
        s"${NonceEpochCommand.ProfileId}\nkeyMode=${NonceEpochCommand.KeyMode}\npreLedgerSha256=${b.pins("preLedgerSha256").hex}\n"
      )
    )
    assertEquals(b.certificates.registrationDigest, expected)
    assert(b.certificates.registrationDigest != b.pins("preLedgerSha256"))
    assertEquals(b.certificates.firstSlot, BigInt(920))
    assertEquals(b.certificates.lastSlot, BigInt(1120))
    assertEquals(b.nonces.context.window, BigInt(400))
    assertEquals(b.nonces.seed.fields.previousEpoch, None)
    assertEquals(b.nonces.seed.certificateStateId, b.seed.id)
  }
  test("missing changed or duplicate key assumption rejects") {
    val text = new String(manifest(files).toArray, "UTF-8")
    Vector(
      text.linesIterator.filterNot(_.startsWith("keyMode\t")).mkString("\n"),
      text.replace(NonceEpochCommand.KeyMode, "ledger-authoritative"),
      text + "keyMode\tpre-anchor-supplied-verification-keys-v1\n"
    )
      .foreach(s => assert(NonceEpochCommand.bind(raw(s), files).isLeft))
  }
  test("bound and success report cannot be manufactured or copied") {
    assert(
      compileErrors(
        "new lab.NonceEpochCommand.Report(null, Vector.empty, Vector.empty, false)"
      ).nonEmpty
    )
    assert(compileErrors("val r: lab.NonceEpochCommand.Report = null; r.copy()").nonEmpty)
    assert(compileErrors("val b: lab.NonceEpochCommand.Bound = null; b.copy()").nonEmpty)
  }
  test("post-state oracle bytes cannot affect pre certificate or nonce seeds") {
    val changed = files
      .updated("post-protocol-state.md", raw("invalid protocol JSON"))
      .updated("post-ledger-state.md", raw("invalid ledger JSON"))
      .updated("post-parameters.md", raw("invalid parameters JSON"))
    val b = bind(changed).fold(e => fail(e.toString), identity)
    assertEquals(b.seed.id, bound.seed.id)
    assertEquals(b.nonces.seed.id, bound.nonces.seed.id)
    NonceEpochCommand.replay(b, Vector.empty) match
      case Left(NonceEpochCommand.Failure.Rejected("replay", _)) => ()
      case other                                                 => fail(other.toString)
  }
  test("strict fields encoding source digests and bounds reject") {
    val text = new String(manifest(files).toArray, "UTF-8")
    Vector(
      raw(text + "extra\t00\n"),
      raw(text + "format\tpraos-nonce-epoch-rotation-v1\n"),
      raw(text.replace(NonceEpochCommand.ProfileId, NonceFreezeCommand.ProfileId)),
      raw("x" * 8193),
      Bytes(Vector(0xc3.toByte, 0x28.toByte))
    ).foreach(m => assert(NonceEpochCommand.bind(m, files).isLeft))
    files.keys.foreach(n =>
      assert(NonceEpochCommand.bind(manifest(files), files.updated(n, raw("tamper"))).isLeft)
    )
    assert(NonceEpochCommand.bind(manifest(files), files - "post-ledger-state.md").isLeft)
    assert(bind(files.updated("post-protocol-state.md", Bytes.empty)).isLeft)
    assert(bind(files.updated("post-protocol-state.md", raw("x" * 4194305))).isLeft)
  }
  test("exactly two adjacent epochs and strict capture windows are required") {
    Vector(tips(620, 5, 1), tips(1620, 5, 3), tips(1120, 5, 1), tips(1039, 5, 2), tips(1181, 5, 2))
      .foreach(t => assert(bind(files.updated("post-tips.md", t)).isLeft))
    Vector(tips(420, 3, 0), tips(899, 3, 1), tips(961, 3, 1))
      .foreach(t => assert(bind(files.updated("pre-tips.md", t)).isLeft))
    assert(bind(files.updated("post-tips.md", tips(1040, 5, 2))).isRight)
    assert(bind(files.updated("post-tips.md", tips(1180, 5, 2))).isRight)
  }
  test("small complete range admits sixteen and rejects one or seventeen successors") {
    assert(bind(files.updated("post-tips.md", tips(1120, 19, 2))).isRight)
    assert(bind(files.updated("post-tips.md", tips(1120, 4, 2))).isLeft)
    assert(bind(files.updated("post-tips.md", tips(1120, 20, 2))).isLeft)
    assert(
      NonceEpochCommand
        .replay(bound, Vector.fill(17)(BoundedChainFollower.Original(Bytes.empty, Bytes.empty)))
        .isLeft
    )
  }
  test("changed supplied key map changes state identity even though post map is not parsed") {
    val changed = files.updated(
      "pre-ledger-state.md",
      raw(new String(ledger.toArray, "UTF-8").replace("56" * 32, "78" * 32))
    )
    val b = bind(changed).fold(e => fail(e.toString), identity)
    assert(b.certificates.id != bound.certificates.id)
    assert(b.seed.id != bound.seed.id)
    assert(b.nonces.seed.id != bound.nonces.seed.id)
  }
  test("missing nonce pre fields and inconsistent anchor reject") {
    val text = new String(protocol.toArray, "UTF-8")
    Vector(
      text.replace("\"candidateNonce\":null,", ""),
      text.replace("\"lastSlot\":920", "\"lastSlot\":919")
    )
      .foreach(s => assert(bind(files.updated("pre-protocol-state.md", raw(s))).isLeft))
    assert(bind(files.updated("pre-ledger-state.md", raw("""{"lastEpoch":1}"""))).isLeft)
  }
  test("network parameter version and geometry remain closed") {
    val genesis = new String(files("transfer-genesis.md").toArray, "UTF-8")
    Vector(
      genesis.replace("1082026", "764824073"),
      genesis.replace("0.05", "0.05001"),
      genesis.replace("\"securityParam\":5", "\"securityParam\":6")
    )
      .foreach(s => assert(bind(files.updated("transfer-genesis.md", raw(s))).isLeft))
    assert(
      bind(
        files.updated("pre-parameters.md", raw("""{"protocolVersion":{"major":9,"minor":1}}"""))
      ).isLeft
    )
  }
  test("rotation equations reject old nonce and newly rotated LAB ordering") {
    def nonce(n: Byte) = Nonces.Nonce.Hash(Bytes(Vector.fill(32)(n)))
    val before = Nonces.Fields(nonce(1), nonce(2), nonce(3), None, nonce(4), nonce(5))
    val expected = NonceEpochCommand.combine(before.candidate, before.lastEpochBlock)
    val after =
      before.copy(epoch = expected, previousEpoch = Some(before.epoch), lastEpochBlock = before.lab)
    assert(NonceEpochCommand.rotationMatches(before, after, expected))
    assert(!NonceEpochCommand.rotationMatches(before, after, before.epoch))
    val wrong = NonceEpochCommand.combine(before.candidate, before.lab)
    assert(!NonceEpochCommand.rotationMatches(before, after.copy(epoch = wrong), wrong))
    assert(!NonceEpochCommand.rotationMatches(before, after.copy(previousEpoch = None), expected))
    assert(
      !NonceEpochCommand.rotationMatches(
        before,
        after.copy(lastEpochBlock = before.lastEpochBlock),
        expected
      )
    )
    assertEquals(
      NonceEpochCommand.combine(Nonces.Nonce.Neutral, before.candidate),
      before.candidate
    )
  }
  test("CLI arity fails before network access") {
    NonceEpochCommand.run(Nil).map(code => assertEquals(code.code, 2)).unsafeToFuture()
  }

  sys.env.get("NONCE_EPOCH_EVIDENCE").foreach { location =>
    val directory = java.nio.file.Path.of(location)
    def retained = NonceEpochCommand.load(directory).fold(e => fail(e.toString), identity)
    def originals = ClusterHeaderObservation
      .capturesBounded(directory.resolve("scala-nonce-epoch.md"), 16)
      .fold(fail(_), identity)
      .map(c => BoundedChainFollower.Original(c.headerEnvelope, c.block))
    test("retained full original sequence derives one rotation and compares external oracle") {
      val b = retained
      val report = NonceEpochCommand.replay(b, originals).fold(e => fail(e.toString), identity)
      val ticks = report.nonceSteps.filter(s => s.before.lastSlot / 500 != s.after.lastSlot / 500)
      assertEquals(ticks.size, 1)
      val tick = ticks.head
      assert(
        NonceEpochCommand
          .rotationMatches(tick.before.fields, tick.after.fields, tick.epochNonceUsed)
      )
      val json = ReferenceJson.parse(raw(NonceEpochCommand.render(report)))
      Vector(
        "passed",
        "epochTickChecked",
        "tickedNonceUsedForVrf",
        "oldLabRotatedToLastEpochBlock",
        "fiveNonceFieldsMatched",
        "finalCountersMatched",
        "rollbackReapplyChecked",
        "suppliedRegistrationKeysOnly"
      )
        .foreach(n => assertEquals(ReferenceJson.field(json, n), ReferenceJson.Json.Lit("true")))
      Vector(
        "crossEpochRegistrationContinuityProven",
        "certificateRegistrationAuthorityValidated",
        "leaderEligibilityChecked",
        "stateDerivedConsensus",
        "consensusValidated"
      )
        .foreach(n => assertEquals(ReferenceJson.field(json, n), ReferenceJson.Json.Lit("false")))
      val restored = report.nonceSteps.reverse.foldLeft(report.nonceSteps.last.after)(
        (state, step) => Nonces.undo(state, step).fold(fail(_), identity)
      )
      assertEquals(restored.id, b.nonces.seed.id)
      assertEquals(restored.fields.previousEpoch, b.nonces.seed.fields.previousEpoch)
      assertEquals(tick.after.fields.previousEpoch, Some(tick.before.fields.epoch))
    }
    test(
      "retained boundary original cannot be omitted or post oracle rehashed to malformed content"
    ) {
      val b = retained; val blocks = originals
      val boundary = blocks.indexWhere(o =>
        ReferenceCaptureCommand.header(o.envelope).toOption.get.slot / 500 == b.epoch + 1
      )
      assert(boundary >= 1)
      assert(NonceEpochCommand.replay(b, blocks.patch(boundary, Nil, 1)).isLeft)
      val changed = bind(b.originals.updated("post-protocol-state.md", raw("not JSON")))
        .fold(e => fail(e.toString), identity)
      assertEquals(changed.nonces.seed.id, b.nonces.seed.id)
      NonceEpochCommand.replay(changed, blocks) match
        case Left(NonceEpochCommand.Failure.Rejected("post-oracle", _)) => ()
        case other                                                      => fail(other.toString)
    }
    test("retained first new-epoch VRF rejects stale nonce and wrong LAB rotation seeds") {
      val b = retained
      val report = NonceEpochCommand.replay(b, originals).fold(e => fail(e.toString), identity)
      val index =
        report.nonceSteps.indexWhere(s => s.before.lastSlot / 500 != s.after.lastSlot / 500)
      val tick = report.nonceSteps(index)
      val certificate = report.certificateSteps(index)
      val before = tick.before.fields
      assert(tick.epochNonceUsed != before.epoch)
      val wrongLabEpoch = NonceEpochCommand.combine(before.candidate, before.lab)
      assert(tick.epochNonceUsed != wrongLabEpoch)
      // These synthetic counterexamples retain the exact original certificate/VRF proof.
      Vector(
        before.copy(candidate = before.epoch, lastEpochBlock = Nonces.Nonce.Neutral),
        before.copy(lastEpochBlock = before.lab)
      ).foreach { wrong =>
        val seed = Nonces
          .seed(b.nonces.context, certificate.before, wrong, b.pins("preProtocolSha256"))
          .fold(fail(_), identity)
        val result = Nonces.applyHeader(b.nonces.context, seed, certificate)
        assert(result.left.toOption.exists(_.startsWith("nonce VRF rejected:")), result.toString)
      }
    }
    test("retained changed post key map is Unsupported rather than a new replay authority") {
      val b = retained
      val old = new String(b.originals("post-ledger-state.md").toArray, "UTF-8")
      val changed = b.certificates.registrations.values
        .foldLeft(old)((text, key) => text.replace(key.hex, "00" * 32))
      val rebound = bind(b.originals.updated("post-ledger-state.md", raw(changed)))
        .fold(e => fail(e.toString), identity)
      assertEquals(rebound.seed.id, b.seed.id)
      assertEquals(rebound.nonces.seed.id, b.nonces.seed.id)
      NonceEpochCommand.replay(rebound, originals) match
        case Left(NonceEpochCommand.Failure.Unsupported(detail)) =>
          assertEquals(
            detail,
            "observed post registration keys differ from the fixed supplied-key profile"
          )
        case other => fail(other.toString)
    }
    test("retained originals reject changed supplied verification keys") {
      val b = retained
      val old = new String(b.originals("pre-ledger-state.md").toArray, "UTF-8")
      val changed = b.certificates.registrations.values
        .foldLeft(old)((text, key) => text.replace(key.hex, "00" * 32))
      val rebound = bind(b.originals.updated("pre-ledger-state.md", raw(changed)))
        .fold(e => fail(e.toString), identity)
      NonceEpochCommand.replay(rebound, originals) match
        case Left(NonceEpochCommand.Failure.Rejected("certificate", _)) => ()
        case other                                                      => fail(other.toString)
    }
  }
