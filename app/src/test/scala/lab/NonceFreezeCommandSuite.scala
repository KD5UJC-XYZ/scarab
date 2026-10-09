// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.unsafe.implicits.global
import lab.cbor.Bytes

class NonceFreezeCommandSuite extends munit.FunSuite:
  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes("UTF-8"))
  private def tips(slot: Int, block: Int, epoch: Int = 0): Bytes =
    val tip =
      s"""{"era":"Conway","hash":"${"12" * 32}","slot":$slot,"block":$block,"epoch":$epoch}"""
    raw(s"[$tip,$tip]")
  private val ledger = raw(
    s"""{"lastEpoch":0,"stakeDistrib":{"unPoolDistr":{"${"34" * 28}":{"individualPoolStakeVrf":"${"56" * 32}"}}}}"""
  )
  private val protocol = raw(
    s"""{"lastSlot":20,"oCertCounters":{"${"34" * 28}":0},"epochNonce":null,"candidateNonce":null,"evolvingNonce":null,"labNonce":null,"lastEpochBlockNonce":null}"""
  )
  private def files: Map[String, Bytes] = Map(
    "transfer-genesis.md" -> raw(
      """{"networkId":"Testnet","networkMagic":1082026,"epochLength":500,"securityParam":5,"activeSlotsCoeff":0.05,"slotsPerKESPeriod":129600,"maxKESEvolutions":60}"""
    ),
    "pre-tips.md" -> tips(20, 3),
    "post-tips.md" -> tips(120, 5),
    "pre-protocol-state.md" -> protocol,
    "post-protocol-state.md" -> protocol,
    "pre-ledger-state.md" -> ledger,
    "post-ledger-state.md" -> ledger,
    "pre-parameters.md" -> raw("""{"protocolVersion":{"major":9,"minor":0}}"""),
    "post-parameters.md" -> raw("""{"protocolVersion":{"major":9,"minor":0}}""")
  )
  private def manifest(originals: Map[String, Bytes]): Bytes = raw(
    "format\tpraos-nonce-candidate-freeze-v1\n" + NonceFreezeCommand.sources.toVector
      .sortBy(_._1)
      .map((key, name) => key + "\t" + ClusterHeaderObservation.sha256(originals(name)).hex)
      .mkString("\n") + "\n"
  )
  private def bind(originals: Map[String, Bytes]) =
    NonceFreezeCommand.bind(manifest(originals), originals)
  private def bound = bind(files).fold(e => fail(e.toString), identity)
  test("strict nine-source input seeds only explicit pre state and fixes cutoff geometry") {
    val b = bound
    assertEquals(b.seed.tip.slot, BigInt(20))
    assertEquals(b.nonces.seed.lastSlot, b.seed.tip.slot)
    assertEquals(b.nonces.seed.certificateStateId, b.seed.id)
    assertEquals(b.nonces.context.window, BigInt(400))
    assertEquals(b.nonces.context.epochLength, BigInt(500))
    assertEquals(b.nonces.seed.fields.previousEpoch, None)
    assertEquals(b.post.slot, BigInt(120))
    assertEquals(NonceFreezeCommand.MaxHeaders, 16)
  }
  test("bound and success report cannot be copied or manufactured") {
    assert(
      compileErrors(
        "new lab.NonceFreezeCommand.Report(null, Vector.empty, Vector.empty, false)"
      ).nonEmpty
    )
    assert(compileErrors("val r: lab.NonceFreezeCommand.Report = null; r.copy()").nonEmpty)
    assert(compileErrors("val b: lab.NonceFreezeCommand.Bound = null; b.copy()").nonEmpty)
  }
  test("post protocol ledger and parameters remain opaque bytes during pre seed construction") {
    val changed = files
      .updated("post-protocol-state.md", raw("not JSON"))
      .updated("post-ledger-state.md", raw("not JSON either"))
      .updated("post-parameters.md", raw("also not JSON"))
    val b = bind(changed).fold(e => fail(e.toString), identity)
    assertEquals(b.seed.id, bound.seed.id)
    assertEquals(b.nonces.seed.id, bound.nonces.seed.id)
    NonceFreezeCommand.replay(b, Vector.empty) match
      case Left(NonceFreezeCommand.Failure.Rejected("replay", _)) => ()
      case other                                                  => fail(other.toString)
  }
  test("manifest duplicate missing extra format and malformed encoding reject") {
    val text = new String(manifest(files).toArray, "UTF-8")
    Vector(
      raw(text + "format\tpraos-nonce-candidate-freeze-v1\n"),
      raw(text + "extra\t00\n"),
      raw(text.linesIterator.filterNot(_.startsWith("preTipsSha256")).mkString("\n")),
      raw(text.replace("praos-nonce-candidate-freeze-v1", "certificate-context-v1")),
      Bytes(Vector(0xc3.toByte, 0x28.toByte))
    ).foreach(m => assert(NonceFreezeCommand.bind(m, files).isLeft))
  }
  test("every owned source is pinned even when its oracle parsing is deferred") {
    files.keys.foreach { name =>
      assert(NonceFreezeCommand.bind(manifest(files), files.updated(name, raw("tamper"))).isLeft)
    }
    assert(NonceFreezeCommand.bind(manifest(files), files - "post-protocol-state.md").isLeft)
    assert(NonceFreezeCommand.bind(manifest(files), files.updated("other.md", raw("x"))).isLeft)
  }
  test("manifest and original byte bounds reject") {
    assert(NonceFreezeCommand.bind(raw("x" * 8193), files).isLeft)
    assert(bind(files.updated("post-protocol-state.md", Bytes.empty)).isLeft)
    assert(bind(files.updated("post-protocol-state.md", raw("x" * 4194305))).isLeft)
  }
  test("nonce-specific range bound admits sixteen endpoints and rejects one or seventeen") {
    assert(bind(files.updated("post-tips.md", tips(120, 19))).isRight)
    assert(bind(files.updated("post-tips.md", tips(120, 4))).isLeft)
    assert(bind(files.updated("post-tips.md", tips(120, 20))).isLeft)
    assert(
      NonceFreezeCommand
        .replay(bound, Vector.fill(17)(BoundedChainFollower.Original(Bytes.empty, Bytes.empty)))
        .isLeft
    )
  }
  test("different network and cutoff geometry remain explicitly Unsupported") {
    val genesis = new String(files("transfer-genesis.md").toArray, "UTF-8")
    Vector(
      genesis.replace("1082026", "764824073"),
      genesis.replace("\"epochLength\":500", "\"epochLength\":501"),
      genesis.replace("\"securityParam\":5", "\"securityParam\":6"),
      genesis.replace("0.05", "0.05001")
    )
      .foreach { text =>
        bind(files.updated("transfer-genesis.md", raw(text))) match
          case Left(NonceFreezeCommand.Failure.Unsupported(_)) => ()
          case other                                           => fail(other.toString)
      }
  }
  test("pre and post tips must lie in one genuine fixed epoch") {
    assert(bind(files.updated("post-tips.md", tips(520, 5, 1))).isLeft)
    assert(bind(files.updated("post-tips.md", tips(520, 5, 0))).isLeft)
    assert(bind(files.updated("pre-tips.md", tips(20, 3, 1))).isLeft)
    val tip = new String(tips(120, 5).toArray, "UTF-8")
    assert(
      bind(
        files.updated("post-tips.md", raw(tip.replaceFirst("\\\"block\\\":5", "\"block\":6")))
      ).isLeft
    )
  }
  test("missing nonce snapshot fields, mismatched slot and malformed counters reject") {
    val text = new String(protocol.toArray, "UTF-8")
    Vector(
      text.replace("\"candidateNonce\":null,", ""),
      text.replace("\"lastSlot\":20", "\"lastSlot\":19"),
      text.replace("\"oCertCounters\":{", "\"oCertCounters\":{\"bad\":0,")
    )
      .foreach(s => assert(bind(files.updated("pre-protocol-state.md", raw(s))).isLeft))
  }
  test("missing registration view and wrong PV reject before replay") {
    assert(bind(files.updated("pre-ledger-state.md", raw("""{"lastEpoch":0}"""))).isLeft)
    assert(
      bind(
        files.updated("pre-parameters.md", raw("""{"protocolVersion":{"major":9,"minor":1}}"""))
      ).isLeft
    )
  }
  test("CLI arity fails without network work") {
    NonceFreezeCommand.run(Nil).map(code => assertEquals(code.code, 2)).unsafeToFuture()
  }

  sys.env.get("NONCE_FREEZE_EVIDENCE").foreach { location =>
    val directory = java.nio.file.Path.of(location)
    def retained = NonceFreezeCommand.load(directory).fold(e => fail(e.toString), identity)
    def originals = ClusterHeaderObservation
      .capturesBounded(directory.resolve("scala-nonce-freeze.md"), 16)
      .fold(fail(_), identity)
      .map(c => BoundedChainFollower.Original(c.headerEnvelope, c.block))
    test(
      "retained complete same-epoch originals independently reproduce candidate update and freeze"
    ) {
      val report =
        NonceFreezeCommand.replay(retained, originals).fold(e => fail(e.toString), identity)
      val json = ReferenceJson.parse(raw(NonceFreezeCommand.render(report)))
      Vector(
        "passed",
        "candidateUpdatedBeforeCutoff",
        "candidateFrozenAfterCutoff",
        "rollbackReapplyChecked",
        "fiveNonceFieldsMatched",
        "finalCountersMatched",
        "rollbackEveryPrefixChecked",
        "deterministicReapplyChecked"
      )
        .foreach(name =>
          assertEquals(ReferenceJson.field(json, name), ReferenceJson.Json.Lit("true"))
        )
      Vector(
        "leaderEligibilityChecked",
        "stateDerivedConsensus",
        "epochTickChecked",
        "consensusValidated",
        "coherentBranchPublished"
      )
        .foreach(name =>
          assertEquals(ReferenceJson.field(json, name), ReferenceJson.Json.Lit("false"))
        )
      assert(
        report.nonceSteps.exists(s =>
          s.after.lastSlot % 500 < 100 && s.after.fields.candidate != s.before.fields.candidate
        )
      )
      assert(
        report.nonceSteps.exists(s =>
          s.after.lastSlot % 500 >= 100 && s.after.fields.candidate == s.before.fields.candidate && s.after.fields.evolving != s.before.fields.evolving
        )
      )
    }
    test(
      "retained sequence cannot omit its first original or use a rehashed malformed post oracle"
    ) {
      val b = retained
      val blocks = originals
      assert(NonceFreezeCommand.replay(b, blocks.tail).isLeft)
      val changed = b.originals.updated("post-protocol-state.md", raw("not JSON"))
      val rebound = bind(changed).fold(e => fail(e.toString), identity)
      assertEquals(rebound.seed.id, b.seed.id)
      assertEquals(rebound.nonces.seed.id, b.nonces.seed.id)
      NonceFreezeCommand.replay(rebound, blocks) match
        case Left(NonceFreezeCommand.Failure.Rejected("post-oracle", _)) => ()
        case other                                                       => fail(other.toString)
    }
  }
