// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.ConwayEmptyGovernance as G

/** Generated bounded cost-model shapes; not Plutus evaluation or native conformance evidence. */
class AuditedParameterRolesCompositionSuite extends munit.FunSuite:
  private val F = SyntheticBoundaryCompositionFixture
  private def get[A](value: Either[?, A]): A = value.fold(e => fail(e.toString), identity)
  private def node(v: V) = Node(v, Bytes.empty)
  private def sha(b: Bytes) = ClusterHeaderObservation.sha256(b)
  private def original(languages: Vector[Int], otherDifference: Boolean = false): Bytes =
    val fields = get(Cbor.decode(F.parameterOriginal)).value match
      case V.Arr(xs) => xs
      case _         => fail("complete parameter array required")
    val counts = Map(0 -> 166, 1 -> 175, 2 -> 251)
    val models = V.Map(languages.map { language =>
      node(V.UInt(language)) -> node(V.Arr(Vector.fill(counts(language))(node(V.UInt(0)))))
    })
    val changed = fields.updated(15, node(models))
    get(
      Cbor.encode(
        V.Arr(if otherDifference then changed.updated(13, node(V.UInt(170000001))) else changed)
      )
    )

  private case class Bound(
      previous: GovernanceParameterPayload.Checked,
      current: GovernanceParameterPayload.Checked,
      globals: GovernanceGlobals.Checked,
      profile: CoherentSequence.SyntheticBoundaryProfile,
      input: G.Input
  )
  private def bound(otherDifference: Boolean = false): Bound =
    val previous = get(
      GovernanceParameterPayload.decode(original(Vector(0, 2)), sha(original(Vector(0, 2))))
    )
    val currentRaw = original(Vector(0, 1, 2), otherDifference)
    val current = get(GovernanceParameterPayload.decode(currentRaw, sha(currentRaw)))
    val roles = get(
      GovernanceParameterPayload.bindRoles(
        previous,
        current,
        previous.sha256,
        current.sha256,
        previous.rewards,
        current.rewards,
        F.context.ledger.environment.feeParameters,
        F.context.ledger.environment.minimumOutputParameters
      )
    )
    val prepared = get(
      NativeSeedParameters.decode(
        previous.original,
        current.original,
        F.prepared.genesisOriginal,
        F.prepared.genesisSHA256,
        0,
        0,
        1082026
      )
    )
    val globals = get(GovernanceGlobals.bind(prepared, prepared.bindingId, 0, 0, 1082026))
    val old = F.governance.oldDRep match
      case G.OldDRep.Complete(snapshot, ratify) =>
        G.OldDRep.Complete(
          snapshot,
          ratify.copy(enact =
            ratify.enact.copy(current = previous.payload, previous = previous.payload)
          )
        )
      case _ => fail("completed old governance fixture required")
    val input = F.governance.copy(
      parameters = G.Parameters(current.payload, previous.payload, G.FutureParameters.NoUpdate),
      oldDRep = old,
      globals = get(SyntheticBoundaryState.typedGlobals(globals))
    )
    val profile = get(
      CoherentSequence.syntheticBoundaryProfile(roles, F.checkedPools, globals, F.rewardWindow)
    )
    Bound(previous, current, globals, profile, input)

  private def create(b: Bound) = CoherentSequence.createWithSyntheticBoundary[IO](
    F.context,
    F.stakeSeed,
    b.profile,
    b.input,
    F.nonMyopic,
    F.initialPots,
    Map.empty,
    Map.empty,
    F.pin
  )

  test(
    "audited historical cost-model role composes and selects outer current without rewriting old enactment"
  ) {
    val b = bound()
    assertNotEquals(b.previous.sha256, b.current.sha256)
    assertEquals(b.previous.costModels.keySet, Set(0, 2))
    assertEquals(b.current.costModels.keySet, Set(0, 1, 2))
    assertEquals(b.previous.rewards.original, b.current.rewards.original)
    assertEquals(b.globals.previousParameterSHA256, b.previous.sha256)
    assertEquals(b.globals.currentParameterSHA256, b.current.sha256)
    (for
      runtime <- create(b).map(get(_))
      queue <- Ref.of[IO, Vector[EphemeralStreaming.Event]](
        F.signed(Vector(1, 5, 40).map(BigInt(_))).map(EphemeralStreaming.Event.Block.apply)
      )
      report <- EphemeralStreaming.run(runtime, EphemeralStreaming.Limits())(
        queue.modify(xs => (xs.drop(1), xs.headOption))
      )
      _ = assertEquals(report.stop, EphemeralStreaming.Stop.End)
      _ = assertEquals(report.counters.acceptedBlocks, 3L)
      state = report.snapshot.state
      component = state.syntheticBoundary.get
      applied = component.governanceAfter.get
      _ = assertEquals(applied.parameters.current.original, b.current.original)
      _ = assertEquals(applied.parameters.previous.original, b.current.original)
      _ = assertEquals(applied.parameters.future, G.FutureParameters.PotentialNone)
      _ = assertEquals(applied.before.parameters.current.original, b.current.original)
      _ = assertEquals(applied.before.parameters.previous.original, b.previous.original)
      _ = applied.before.oldDRep match
        case G.OldDRep.Complete(_, ratify) =>
          assertEquals(ratify.enact.current.original, b.previous.original)
          assertEquals(ratify.enact.previous.original, b.previous.original)
        case _ => fail("old completion was replaced")
      _ = assertEquals(component.nonMyopic.rewardPot, BigInt(8))
      _ = assertEquals(state.syntheticRewards.get.pots.treasury, BigInt(2))
      _ = assertEquals(applied.fresh.enact.treasury, BigInt(2))
      _ = assert(!state.fullLedgerValidated && !state.consensusValidated)
    yield ()).unsafeToFuture()
  }

  test(
    "historical role rejects non-cost differences despite equal consumed projections and coherent source pins"
  ) {
    val b = bound(otherDifference = true)
    assertEquals(b.previous.rewards.original, b.current.rewards.original)
    assertEquals(b.previous.feePerByte, b.current.feePerByte)
    assertEquals(b.previous.feeFixed, b.current.feeFixed)
    assertEquals(b.previous.maxTxSize, b.current.maxTxSize)
    assertEquals(b.previous.coinsPerUTxOByte, b.current.coinsPerUTxOByte)
    assertEquals(b.globals.previousParameterSHA256, b.previous.sha256)
    assertEquals(b.globals.currentParameterSHA256, b.current.sha256)
    assert(G.applyBoundary(b.input, 1).isLeft)
    create(b).map(result => assert(result.isLeft)).unsafeToFuture()
  }

  // Curated symbolic projection of independently checked native evidence. The
  // generated parameters below are deliberately not the original native bytes.
  private lazy val recordedRolePattern: ReferenceJson.Json.Obj =
    val stream = getClass.getResourceAsStream("/audited-governance-roles/native-role-pattern.json")
    require(stream != null, "missing curated parameter role pattern")
    val bytes =
      try Bytes.fromArray(stream.readNBytes(16385))
      finally stream.close()
    require(bytes.size <= 16384, "curated role pattern exceeds bound")
    assertEquals(sha(bytes).hex, "fa42291df8f2e5664df1c1c2a9f2863cf273a86ea7452d40d7b57c8e280b22c2")
    ReferenceJson.parse(bytes) match
      case value: ReferenceJson.Json.Obj => value
      case _                             => fail("curated role pattern object required")
  private def roleCases: Vector[ReferenceJson.Json.Obj] =
    ReferenceJson.array(recordedRolePattern.fields("cases")).map {
      case value: ReferenceJson.Json.Obj => value
      case _                             => fail("curated role case object required")
    }
  private def recordedRoles(value: ReferenceJson.Json): Map[String, String] = value match
    case ReferenceJson.Json.Obj(fields) =>
      assertEquals(
        fields.keySet,
        Set("completedCurrent", "completedPrevious", "outerCurrent", "outerPrevious")
      )
      fields.map { (key, value) =>
        val symbol = ReferenceJson.string(value)
        assert(Set("P", "C").contains(symbol))
        key -> symbol
      }
    case _ => fail("recorded four-role object required")

  test("curated native role metadata preserves exact provenance and limited regression scope") {
    import ReferenceJson.Json as J
    val fields = recordedRolePattern.fields
    assertEquals(
      fields.keySet,
      Set(
        "schema",
        "sourceResultSHA256",
        "nativeSourceCommit",
        "sourceSchema",
        "sourceProducer",
        "symbols",
        "cases",
        "scope"
      )
    )
    assertEquals(fields("schema"), J.Str("recorded-native-parameter-role-pattern-v1"))
    assertEquals(fields("sourceSchema"), J.Str("audited-governance-roles-result-v1"))
    assertEquals(fields("sourceProducer"), J.Str("native-newepoch-normalized-governance"))
    assertEquals(
      fields("sourceResultSHA256"),
      J.Str("8bc636e667c89fadefad6f9f5ae5aed72b930d3cfe8268a29bad7acf2293ffd7")
    )
    assertEquals(fields("nativeSourceCommit"), J.Str("7da4ebd71bd13246e90ff079535e128a53aa7ee5"))
    val symbols = fields("symbols").asInstanceOf[J.Obj].fields
    assertEquals(symbols.keySet, Set("P", "C"))
    val metadata = Map(
      "P" -> ("0f6d70064c39fb492af2f456aa87e3ce44195bb791da4a74a54d507bb01899ef", BigInt(1322)),
      "C" -> ("75146abbc571a0a633ea1b62dc34d4e0c85164f29a1492ecf20f1929070d9600", BigInt(1788))
    )
    metadata.foreach { case (symbol, (hash, length)) =>
      val item = symbols(symbol).asInstanceOf[J.Obj].fields
      assertEquals(item.keySet, Set("sha256", "bytes"))
      assertEquals(ReferenceJson.string(item("sha256")), hash)
      assertEquals(ReferenceJson.uint(item("bytes")), length)
    }
    assertEquals(
      roleCases.map(c => ReferenceJson.string(c.fields("id"))),
      Vector("exact-current", "previous-costs-only", "reject-non-cost-change")
    )
    val scope = fields("scope").asInstanceOf[J.Obj].fields
    assertEquals(
      scope,
      Map(
        "nativeFullParameterEqualityObserved" -> J.Lit("true"),
        "defaultTestUsesGeneratedParameters" -> J.Lit("true"),
        "defaultTestComparesRolePatternOnly" -> J.Lit("true"),
        "rawNativeParametersPublished" -> J.Lit("false"),
        "fullAuditedSeedExecuted" -> J.Lit("false"),
        "costModelEvaluationContextValidity" -> J.Lit("false"),
        "runtimeAdmission" -> J.Lit("false")
      )
    )
  }

  test(
    "generated pure boundary models reproduce all recorded before and after parameter role symbols"
  ) {
    import ReferenceJson.Json as J
    val b = bound()
    def symbol(original: Bytes): String =
      if original == b.previous.original then "P"
      else if original == b.current.original then "C"
      else fail("boundary returned a parameter outside generated P/C roles")
    roleCases.take(2).foreach { recorded =>
      val fields = recorded.fields
      assertEquals(
        fields.keySet,
        Set(
          "id",
          "before",
          "after",
          "afterFuture",
          "nativeSTSExecuted",
          "status",
          "wholeParameterEqualityChecked"
        )
      )
      assertEquals(fields("status"), J.Str("accepted"))
      assertEquals(fields("nativeSTSExecuted"), J.Lit("true"))
      assertEquals(fields("wholeParameterEqualityChecked"), J.Lit("true"))
      assertEquals(fields("afterFuture"), J.Str("PotentialNone"))
      val old = b.input.oldDRep match
        case G.OldDRep.Complete(snapshot, ratify) =>
          if fields("id") == J.Str("exact-current") then
            G.OldDRep.Complete(
              snapshot,
              ratify.copy(enact = ratify.enact.copy(current = b.current.payload))
            )
          else b.input.oldDRep
        case _ => fail("generated completed history required")
      val input = b.input.copy(oldDRep = old)
      val oldEnact = input.oldDRep match
        case G.OldDRep.Complete(_, ratify) => ratify.enact
        case _                             => fail("generated completed history required")
      val before = Map(
        "outerCurrent" -> symbol(input.parameters.current.original),
        "outerPrevious" -> symbol(input.parameters.previous.original),
        "completedCurrent" -> symbol(oldEnact.current.original),
        "completedPrevious" -> symbol(oldEnact.previous.original)
      )
      assertEquals(before, recordedRoles(fields("before")))
      val applied = get(G.applyBoundary(input, 1))
      val after = Map(
        "outerCurrent" -> symbol(applied.parameters.current.original),
        "outerPrevious" -> symbol(applied.parameters.previous.original),
        "completedCurrent" -> symbol(applied.fresh.enact.current.original),
        "completedPrevious" -> symbol(applied.fresh.enact.previous.original)
      )
      assertEquals(after, recordedRoles(fields("after")))
      assertEquals(applied.parameters.future, G.FutureParameters.PotentialNone)
    }
  }

  test("recorded non-cost profile rejection remains distinct from native STS rejection") {
    import ReferenceJson.Json as J
    val fields = roleCases.last.fields
    assertEquals(fields.keySet, Set("id", "nativeSTSExecuted", "reason", "status"))
    assertEquals(fields("id"), J.Str("reject-non-cost-change"))
    assertEquals(fields("nativeSTSExecuted"), J.Lit("false"))
    assertEquals(fields("status"), J.Str("profile-rejected"))
    assertEquals(fields("reason"), J.Str("non-cost-parameter-difference"))
    assert(G.applyBoundary(bound(otherDifference = true).input, 1).isLeft)
  }
