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
