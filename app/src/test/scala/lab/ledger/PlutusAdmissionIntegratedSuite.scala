// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.plutus.PlutusExecution as E
import lab.vm.Pv9SubmissionEvaluator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

class PlutusAdmissionIntegratedSuite extends munit.FunSuite:
  private def get[A](e: Either[?, A]): A = e.fold(e => fail(e.toString), identity)
  private def n(v: V): Node = Node(v, Bytes.empty)
  private def a(v: V*): V = V.Arr(v.toVector.map(n))
  private def m(v: (Int, V)*): V = V.Map(v.toVector.map((k, v) => n(V.UInt(k)) -> n(v)))
  private def b(v: Bytes): V = V.ByteString(v)
  private def enc(v: V): Bytes = get(Cbor.encode(v))
  private def tag(v: V): V = V.Tag(258, n(v))
  private def sha(b: Bytes): Bytes =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(b.toArray))
  private val model =
    Bytes.fromArray(Files.readAllBytes(Path.of("vm/src/main/resources/plutus-pv9/cost-model.json")))
  private val script = Bytes.fromArray(
    Files.readAllBytes(Path.of("vm/src/test/resources/plutus-pv9-reference/script.cbor"))
  )
  private val key = new Ed25519PrivateKeyParameters(Array.fill[Byte](32)(7), 0)
  private val publicKey = Bytes.fromArray(key.generatePublicKey().getEncoded)
  private val beneficiary = Blake2b.hash224.hash(publicKey)
  private val scriptHash = Blake2b.hash224.hash(Bytes(Vector(3.toByte) ++ script.value))
  private val scriptAddress = Bytes(Vector(0x70.toByte) ++ scriptHash.value)
  private val keyAddress = Bytes(Vector(0x60.toByte) ++ beneficiary.value)
  private val inputId = Bytes(Vector.fill(32)(1.toByte))
  private val collateralId = Bytes(Vector.fill(32)(2.toByte))
  private val input = a(b(inputId), V.UInt(0))
  private val collateral = a(b(collateralId), V.UInt(0))
  private val datum = enc(V.Tag(121, n(a(b(beneficiary), V.UInt(5000000)))))
  private val scriptOutput =
    m(0 -> b(scriptAddress), 1 -> V.UInt(20000000), 2 -> a(V.UInt(1), V.Tag(24, n(b(datum)))))
  private val collateralOutput = a(b(keyAddress), V.UInt(5000000))
  private val utxo = enc(
    V.Map(Vector(n(input) -> n(scriptOutput), n(collateral) -> n(collateralOutput)))
  )
  private val redeemers = V.Map(
    Vector(n(a(V.UInt(0), V.UInt(0))) -> n(a(V.UInt(7), a(V.UInt(100000), V.UInt(30000000)))))
  )
  private val integrity = get(
    PlutusIntegrity.commitment(enc(redeemers), get(PlutusIntegrity.languageView(model)))
  )
  private def ratio(x: Int, y: Int): V = V.Tag(30, n(a(V.UInt(x), V.UInt(y))))
  private val costs = new String(model.toArray, "UTF-8").trim
    .drop(1)
    .dropRight(1)
    .split(",")
    .toVector
    .map(x => BigInt(x.trim))
  private val pValues = Vector
    .fill[V](31)(V.UInt(0))
    .updated(0, V.UInt(44))
    .updated(1, V.UInt(155381))
    .updated(3, V.UInt(16384))
    .updated(12, a(V.UInt(9), V.UInt(0)))
    .updated(14, V.UInt(4310))
    .updated(15, m(2 -> V.Arr(costs.map(x => n(if x < 0 then V.NInt(x) else V.UInt(x))))))
    .updated(16, a(ratio(577, 10000), ratio(721, 10000000)))
    .updated(17, a(V.UInt(14000000), V.UInt(10000000000L)))
    .updated(18, a(V.UInt(62000000), V.UInt(20000000000L)))
    .updated(19, V.UInt(5000))
    .updated(20, V.UInt(150))
    .updated(21, V.UInt(3))
  private val rawParameters = enc(V.Arr(pValues.map(n)))
  private val parameters = get(PlutusParameters.decode(rawParameters, sha(rawParameters), model))
  private val genesis = Bytes(Vector.fill(32)(3.toByte))
  private val base = get(
    ClusterTransition.environment(genesis, sha(rawParameters), 42, 0, 9, 0, 44, 155381, 16384, 4310)
  )
  private val profile = get(
    PlutusEnvironment.bind(
      base,
      parameters,
      PlutusContextInput.SlotTime(1700000000000L, 100, 1, genesis),
      0
    )
  )
  private val env = get(ClusterTransition.withPlutus(base, profile))
  private def state = get(ClusterTransition.checkpoint(env, utxo, 200000, 100, genesis))

  private def transaction(
      fee: Int = 300000,
      valid: Boolean = true,
      badSignature: Boolean = false,
      lower: Int = 0,
      upper: Int = 2000,
      commitment: Bytes = integrity,
      inputChoice: V = input
  ): Bytes =
    val body = m(
      0 -> tag(a(inputChoice)),
      1 -> a(a(b(keyAddress), V.UInt(20000000 - fee))),
      2 -> V.UInt(fee),
      3 -> V.UInt(upper),
      8 -> V.UInt(lower),
      11 -> b(commitment),
      13 -> tag(a(collateral))
    )
    val signer = new Ed25519Signer()
    signer.init(true, key)
    val message = Blake2b.hash256.hash(enc(body)).toArray
    signer.update(message, 0, message.length)
    val signed = signer.generateSignature()
    if badSignature then signed(0) = (signed(0) ^ 1).toByte
    val witness = m(
      0 -> tag(a(a(b(publicKey), b(Bytes.fromArray(signed))))),
      5 -> redeemers,
      7 -> tag(a(b(script)))
    )
    enc(a(body, witness, V.Bool(valid), V.Null))

  test("signed final envelope passes real PV9 VM and successful transition preserves collateral") {
    val before = state
    val original = transaction()
    val checked = get(PlutusAdmission.check(before, original, profile, Pv9SubmissionEvaluator, 100))
    assert(checked.execution.consumed.memory > 0)
    assert(checked.execution.consumed.steps > 0)
    assertEquals(checked.dependencies.size, 2)
    val prepared = get(ClusterTransition.prepareCheckedPlutus(before, checked))
    val applied = get(ClusterTransition.commit(before, prepared))
    val after = get(PlutusOutput.snapshot(applied.state.outputMap, 0))
    assertEquals(applied.state.fees, BigInt(500000))
    assert(!after.outputs.contains(get(TxIn.create(inputId, 0))))
    assertEquals(after.outputs(get(TxIn.create(collateralId, 0))).original, enc(collateralOutput))
    assertEquals(after.outputs.values.map(_.coin).sum + applied.state.fees, BigInt(25200000))
    assertEquals(prepared.originalTransaction, original)
    assertEquals(applied.state.profileId, E.ProfileId)
    val restored = get(
      ClusterTransition.trustedRestoreLocal(
        env,
        applied.state.checkpointId,
        get(ClusterTransition.localImage(applied.state)),
        applied.state.revision
      )
    )
    assertEquals(restored.id, applied.state.id)
    assertEquals(restored.outputMap, applied.state.outputMap)
  }
  test("default checkpoint and evaluator-free transition cannot admit the opt-in datum spend") {
    assert(ClusterTransition.checkpoint(base, utxo, 200000, 100, genesis).isLeft)
    assert(ClusterTransition.prepare(state, transaction(), 100).isLeft)
  }
  test("invalid signature low fee false validity and outside interval reject before VM success") {
    val before = state
    Vector(
      transaction(badSignature = true),
      transaction(fee = 1000),
      transaction(valid = false),
      transaction(lower = 101),
      transaction(upper = 100)
    ).foreach { tx =>
      assert(PlutusAdmission.check(before, tx, profile, Pv9SubmissionEvaluator, 100).isLeft)
    }
  }
  test("original integrity mismatch cannot be laundered through a valid collateral signature") {
    assert(
      PlutusAdmission
        .check(state, transaction(commitment = genesis), profile, Pv9SubmissionEvaluator, 100)
        .isLeft
    )
  }
  test(
    "typed evaluator failures propagate and forged success bindings cannot construct admission"
  ) {
    val failure = new E.Evaluator:
      def evaluate(r: E.Request) = Left(E.Failure.BudgetExhausted)
    assertEquals(
      PlutusAdmission.check(state, transaction(), profile, failure, 100),
      Left(PlutusAdmission.Failure.Execution(E.Failure.BudgetExhausted))
    )
    val forged = new E.Evaluator:
      def evaluate(r: E.Request) = Right(
        E.Success(
          r.requestDigest,
          r.scriptSHA256,
          r.contextSHA256,
          r.modelSHA256,
          r.declared,
          E.Budget(-1, 0)
        )
      )
    assertEquals(
      PlutusAdmission.check(state, transaction(), profile, forged, 100),
      Left(PlutusAdmission.Failure.BindingMismatch)
    )
  }
  test(
    "receipt cannot be applied to another state object and failed block leaves held state unchanged"
  ) {
    val before = state
    val checked =
      get(PlutusAdmission.check(before, transaction(), profile, Pv9SubmissionEvaluator, 100))
    assert(ClusterTransition.prepareCheckedPlutus(state, checked).isLeft)
    val oldId = before.id
    assert(
      ClusterTransition
        .prepareBlock(
          before,
          genesis,
          Vector(transaction(), transaction()),
          101,
          Some(Pv9SubmissionEvaluator)
        )
        .isLeft
    )
    assertEquals(before.id, oldId)
    val block = get(
      ClusterTransition.prepareBlock(
        before,
        genesis,
        Vector(transaction()),
        101,
        Some(Pv9SubmissionEvaluator)
      )
    )
    assertEquals(get(ClusterTransition.commitBlock(before, block)).state.fees, BigInt(500000))
  }

  test(
    "pool reserves collateral across independent spends and revalidation drops missing collateral"
  ) {
    val otherId = Bytes(Vector.fill(32)(4.toByte))
    val otherInput = a(b(otherId), V.UInt(0))
    val raw = enc(
      V.Map(
        Vector(
          n(input) -> n(scriptOutput),
          n(otherInput) -> n(scriptOutput),
          n(collateral) -> n(collateralOutput)
        )
      )
    )
    val before = get(ClusterTransition.checkpoint(env, raw, 200000, 100, genesis))
    val policy = lab.submission.AdmissionProfile.PlutusV3
    val first = get(
      AdmissionValidation.prepare(
        policy,
        "pin0",
        before,
        transaction(),
        Some(Pv9SubmissionEvaluator)
      )
    )
    val second = get(
      AdmissionValidation.prepare(
        policy,
        "pin0",
        before,
        transaction(inputChoice = otherInput),
        Some(Pv9SubmissionEvaluator)
      )
    )
    assert((first.spent intersect second.spent).isEmpty)
    val (pool, result) = AdaPool.admit(AdaPool.empty("pin0", profile = policy), first, 0)
    assert(result.isInstanceOf[AdaPool.Outcome.Accepted[?]])
    val collateralRef = get(TxIn.create(collateralId, 0))
    assertEquals(pool.reserved, first.dependencies)
    assertEquals(
      AdaPool.admit(pool, second, 1)._2,
      AdaPool.Outcome.Rejected(AdaPool.Rejection.InputsReserved(Set(collateralRef)))
    )
    val rawWithoutCollateral =
      enc(V.Map(Vector(n(input) -> n(scriptOutput), n(otherInput) -> n(scriptOutput))))
    val changed = get(ClusterTransition.checkpoint(env, rawWithoutCollateral, 200000, 101, genesis))
    val (moving, work) = AdaPool.move(pool, "pin1", Set.empty, 2)
    assert(moving.eligible(2).isEmpty)
    val rebuilt = AdaPool.revalidate(work, changed, Some(Pv9SubmissionEvaluator))
    val (finished, installed) = AdaPool.finish(moving, rebuilt, 3)
    assert(installed)
    assertEquals(finished.size, 0)
  }
  test("changed VM hash binding and explicit epoch crossing fail closed") {
    val forged = new E.Evaluator:
      def evaluate(r: E.Request) = Right(
        E.Success(
          genesis,
          r.scriptSHA256,
          r.contextSHA256,
          r.modelSHA256,
          r.declared,
          E.Budget(0, 0)
        )
      )
    assertEquals(
      PlutusAdmission.check(state, transaction(), profile, forged, 100),
      Left(PlutusAdmission.Failure.BindingMismatch)
    )
    assert(
      ClusterTransition
        .prepareSyntheticSuccessorBlock(
          state,
          1,
          200000,
          genesis,
          genesis,
          Vector.empty,
          1001,
          Some(Pv9SubmissionEvaluator)
        )
        .isLeft
    )
  }

  test("datum stake seed attaches only to matching profile and follows successful spend") {
    val before = state
    val owner = ConwayStake.owner()
    val context = get(ConwayStake.context(genesis, 1000, Map.empty, Map.empty))
    val empty = ConwayStake.emptySnapshot
    val snapshots = ConwayStake.Snapshots(empty, empty, empty, 0)
    assert(ConwayStake.decodeUtxo(utxo).isLeft)
    val decoded = get(ConwayStake.decodePlutusUtxo(utxo, 0))
    assertEquals(decoded(get(TxIn.create(inputId, 0))).original, enc(scriptOutput))
    assert(
      lab.ConwayStakeSeed
        .checkedComponents(context, utxo, Map.empty, snapshots, 0, 200000, genesis)
        .isLeft
    )
    val seed = get(
      lab.ConwayStakeSeed
        .checkedComponents(context, utxo, Map.empty, snapshots, 0, 200000, genesis, Some(0))
    )
    val stake = get(seed.attach(owner, before))
    assertEquals(stake.utxo, decoded)
    assert(
      lab.ConwayStakeSeed
        .checkedComponents(context, utxo, Map.empty, snapshots, 0, 200000, genesis, Some(1))
        .isLeft
    )
    val block = get(
      ClusterTransition.prepareBlock(
        before,
        genesis,
        Vector(transaction()),
        101,
        Some(Pv9SubmissionEvaluator)
      )
    )
    val candidate = get(ConwayStake.prepare(owner, stake, before, block))
    assertEquals(candidate.instantaneous, Map.empty[ConwayStake.Credential, BigInt])
    val ordinaryRaw = enc(V.Map(Vector(n(collateral) -> n(collateralOutput))))
    val ordinary = get(ClusterTransition.checkpoint(base, ordinaryRaw, 200000, 100, genesis))
    val explicit = get(
      lab.ConwayStakeSeed
        .checkedComponents(context, ordinaryRaw, Map.empty, snapshots, 0, 200000, genesis, Some(0))
    )
    assert(explicit.attach(owner, ordinary).isLeft)
  }

  test("aggregate declared block budget rejects before evaluation and accepts the exact limit") {
    val raw = enc(V.Arr(pValues.updated(18, a(V.UInt(100000), V.UInt(30000000))).map(n)))
    val p = get(PlutusParameters.decode(raw, sha(raw), model))
    val ordinary =
      get(ClusterTransition.environment(genesis, sha(raw), 42, 0, 9, 0, 44, 155381, 16384, 4310))
    val selected = get(PlutusEnvironment.bind(ordinary, p, profile.time, 0))
    val environment = get(ClusterTransition.withPlutus(ordinary, selected))
    val before = get(ClusterTransition.checkpoint(environment, utxo, 200000, 100, genesis))
    var evaluations = 0
    val evaluator = new E.Evaluator:
      def evaluate(request: E.Request) =
        evaluations += 1
        Pv9SubmissionEvaluator.evaluate(request)
    assertEquals(
      ClusterTransition
        .prepareBlock(before, genesis, Vector(transaction(), transaction()), 101, Some(evaluator))
        .left
        .toOption,
      Some(ClusterTransition.Failure.Unsupported("BlockExecutionUnitsTooLarge"))
    )
    assertEquals(evaluations, 0)
    assert(
      ClusterTransition
        .prepareBlock(before, genesis, Vector(transaction()), 101, Some(evaluator))
        .isRight
    )
    assertEquals(evaluations, 1)
    assertEquals(before.fees, BigInt(200000))
  }
