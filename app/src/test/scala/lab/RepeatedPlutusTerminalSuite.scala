// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.{Bytes, Cbor, Value as V}
import lab.header.PraosCertificateState.Point
import lab.ledger.{ConwayEpochBoundary as B, ConwayNativeLikelihood as N, ConwayNonMyopic as NM}
import lab.submission.{StatePin, AdmissionProfile}
import ReferenceJson.Json as J
import RepeatedPlutusTerminal as T

/** Generated source-shaped inputs, not a native endpoint agreement claim. */
class RepeatedPlutusTerminalSuite extends NativeLedgerSeedFixtures:
  private lazy val sourceBundle = bundle()
  private lazy val source = sourceBundle.epoch
  private def sourceSeed = source.originals("derived-full-epoch-seed.cbor")
  private def sourceDebug = source.originals("original-debug-epoch.cbor")
  private def whole = source.originals("original-whole-utxo.cbor")
  private def sourceValue = get(Cbor.decode(sourceSeed)).value
  private lazy val components = get(T.decodeComponents(sourceSeed, source.pots.maxSupply))
  private val terminalPoint = Point(bytes(32, 1), 36, 1)
  private lazy val pin = get(
    StatePin.checked(
      bytes(32, 2),
      1,
      terminalPoint,
      bytes(32, 3),
      bytes(32, 4),
      bytes(32, 5),
      terminalPoint.slot,
      AdmissionProfile.PlutusV3.id
    )
  )
  private def protocolOriginal: Bytes = raw(
    a(
      V.UInt(0),
      a(
        a(V.UInt(1), V.UInt(36)),
        m(),
        a(V.UInt(0)),
        a(V.UInt(0)),
        a(V.UInt(0)),
        a(V.UInt(0)),
        a(V.UInt(0)),
        a(V.UInt(0))
      )
    )
  )
  private val nullJson = J.Lit("null")
  private def observation(c: T.Components = components): J =
    val projected = T.componentsJson(c)
    obj(
      "schema" -> J.Str(T.Schema),
      "diagnosticOnly" -> J.Lit("true"),
      "restartSupported" -> J.Lit("false"),
      "fullLedgerValidated" -> J.Lit("false"),
      "pin" -> PlutusServiceRuntime.pin(pin),
      "sourceJoinId" -> J.Str(bytes(32, 8).hex),
      "initialManifestSHA256" -> J.Str(bytes(32, 9).hex),
      "outputMapFile" -> J.Str("terminal-output-map.cbor"),
      "outputMapSHA256" -> J.Str(sha(whole).hex),
      "fees" -> J.Num(c.pots.fees.toString),
      "epoch" -> J.Num(c.epoch.toString),
      "validationSlot" -> J.Num("36"),
      "instantaneousStake" -> ReferenceJson.field(projected, "instantaneousStake"),
      "representedProtocol" -> obj(
        "lastSlot" -> J.Num("36"),
        "counters" -> J.Arr(Vector.empty),
        "evolving" -> nullJson,
        "candidate" -> nullJson,
        "epoch" -> nullJson,
        "lab" -> nullJson,
        "lastEpochBlock" -> nullJson,
        "previousEpoch" -> obj("present" -> J.Lit("true"), "value" -> nullJson)
      ),
      "components" -> projected,
      "componentsSHA256" -> J.Str(sha(EvidenceJson.encode(projected)).hex),
      "repeatedEpoch" -> obj(
        "componentId" -> J.Str(bytes(32, 10).hex),
        "transitions" -> J.Num(c.epoch.toString),
        "generationMode" -> J.Str("pure-jvm"),
        "frozenId" -> nullJson,
        "allocationId" -> nullJson,
        "nonMyopicId" -> J.Str(c.nonMyopic.id.hex),
        "rewardContext" -> RepeatedTerminalRewardPulser.contextJson(sourceBundle.globals),
        "checkedLikelihood" -> nullJson
      )
    )
  private def changed(j: J, path: List[String], value: J): J = path match
    case Nil => value
    case key :: rest =>
      j match
        case J.Obj(fields) => J.Obj(fields.updated(key, changed(fields(key), rest, value)))
        case _             => fail("test object path")
  private def parseObservation(j: J) = T.read(EvidenceJson.encode(j))
  private def compare(
      j: J,
      seed: Bytes = sourceSeed,
      debug: Bytes = sourceDebug,
      before: Bytes = sourceSeed,
      output: Bytes = whole,
      protocol: Bytes = protocolOriginal
  ) =
    T.compareComponents(
      get(parseObservation(j)),
      before,
      seed,
      debug,
      whole,
      output,
      protocol,
      sourceBundle.globals
    )

  test("all bounded native-shaped absent components and original UTxO replacement compare") {
    assertEquals(components.epoch, BigInt(0))
    assertEquals(components.instantaneous, source.stake.instantaneous)
    assertEquals(components.mark.active, source.stake.snapshots.mark.active)
    assertEquals(components.mark.pools, source.stake.snapshots.mark.pools)
    assertEquals(components.reward, T.Reward.Absent)
    assertEquals(get(compare(observation())), T.Comparison(0, 0, 1))
  }
  test("self-rehashed terminal pot mutation is rejected against complete native components") {
    val changedComponents = components.copy(pots = components.pots.copy(treasury = 1))
    assert(compare(observation(changedComponents)).isLeft)
  }
  test("native non-UTxO sibling originals and historical source indexes cannot be spliced") {
    val foreign = raw(change(sourceValue, Vector(3, 1, 0, 1, 0), m(b(32, 3) -> V.UInt(1))))
    val debug = raw(change(get(Cbor.decode(foreign)).value, Vector(3, 1, 1, 0), m()))
    assert(compare(observation(), foreign, debug).isLeft)
    assert(compare(observation(), sourceSeed, debug).isLeft)
  }
  test("changed output digest, full protocol slot and foreign checked source globals reject") {
    assert(compare(observation(), output = raw(m())).isLeft)
    val otherProtocol =
      raw(change(get(Cbor.decode(protocolOriginal)).value, Vector(1, 0, 1), V.UInt(37)))
    assert(compare(observation(), protocol = otherProtocol).isLeft)
    assert(
      T.compareComponents(
        get(parseObservation(observation())),
        sourceSeed,
        sourceSeed,
        sourceDebug,
        whole,
        whole,
        protocolOriginal,
        bundle(epochLength = 500).globals
      ).isLeft
    )
  }
  test("terminal closed fields and coherent pin slot validation fail before comparison") {
    val original = observation()
    assert(parseObservation(changed(original, List("pin", "validationSlot"), J.Num("35"))).isLeft)
    assert(
      parseObservation(
        changed(original, List("pin", "profileId"), J.Str(AdmissionProfile.AdaVkey.id))
      ).isLeft
    )
    assert(parseObservation(changed(original, List("componentsSHA256"), J.Str("00" * 32))).isLeft)
    assert(
      parseObservation(
        J.Obj(original.asInstanceOf[J.Obj].fields.updated("unrecognized", J.Num("0")))
      ).isLeft
    )
  }
  test("absent metadata cannot claim generation or a foreign mode/transition count") {
    val original = observation()
    Vector(
      List("repeatedEpoch", "transitions") -> J.Num("1"),
      List("repeatedEpoch", "generationMode") -> J.Str("native-assisted"),
      List("repeatedEpoch", "frozenId") -> J.Str("01" * 32),
      List("repeatedEpoch", "checkedLikelihood") -> obj()
    ).foreach { (path, value) =>
      assert(parseObservation(changed(original, path, value)).isLeft)
    }
    val foreignId = changed(original, List("repeatedEpoch", "nonMyopicId"), J.Str("01" * 32))
    assert(compare(foreignId).isLeft)
  }
  private def completedObservation(checked: Boolean, nonempty: Boolean = false): J =
    val nm = get(NM.state(Map.empty, 0))
    val selected = components.copy(reward = T.Reward.Complete(B.Deltas(0, 0, 0), Map.empty, nm))
    val frozen = bytes(32, 30)
    val requestRow = if nonempty then bytes(28, 4).hex + " 0 1000 0\n" else ""
    val outputRow =
      if nonempty then bytes(28, 4).hex + " 0000000000000000 " + "00000000" * 100 + "\n" else ""
    val request = text(N.Profile + "\n" + frozen.hex + "\n1000 1 20 0 1\n" + requestRow)
    val requestText = new String(request.toArray, "US-ASCII")
    val evidence = text(
      (if checked then requestText + "--native--\n"
       else
         lab.ledger.ConwayLikelihoodGeneration.Profile + "\n" + requestText + "--jvm--\n"
      ) + outputRow
    )
    val mode = J.Str(if checked then "checked-jvm" else "pure-jvm")
    val generation = obj(
      "frozenId" -> J.Str(frozen.hex),
      "applicationEpoch" -> J.Num("0"),
      "observedSlot" -> J.Num("401"),
      "preTickTupleId" -> J.Str(bytes(32, 31).hex),
      "requestSHA256" -> J.Str(sha(request).hex),
      "requestOriginal" -> J.Str(request.hex),
      "evidenceSHA256" -> J.Str(sha(evidence).hex),
      "evidenceOriginal" -> J.Str(evidence.hex),
      "nativeResponseSHA256" -> (if checked then J.Str(sha(evidence).hex) else nullJson),
      "nativeResponseOriginal" -> (if checked then J.Str(evidence.hex) else nullJson),
      "mode" -> mode,
      "nativeValidated" -> J.Lit(checked.toString),
      "diagnosticNativeDependency" -> J.Lit(checked.toString),
      "computedRaw32Words" -> J.Num(if nonempty then "100" else "0"),
      "computedRaw64Words" -> J.Num(if nonempty then "1" else "0"),
      "raw32Comparisons" -> J.Num(if checked && nonempty then "100" else "0"),
      "raw64Comparisons" -> J.Num(if checked && nonempty then "1" else "0"),
      "jvmMismatchWords" -> J.Num("0")
    )
    val base = observation(selected)
    val fields = ReferenceJson.field(base, "repeatedEpoch").asInstanceOf[J.Obj].fields ++ Map(
      "generationMode" -> mode,
      "frozenId" -> J.Str(frozen.hex),
      "allocationId" -> J.Str(bytes(32, 32).hex),
      "checkedLikelihood" -> generation
    )
    val result = changed(base, List("repeatedEpoch"), J.Obj(fields))
    changed(
      changed(
        changed(result, List("pin", "point", "slot"), J.Num("801")),
        List("pin", "validationSlot"),
        J.Num("801")
      ),
      List("validationSlot"),
      J.Num("801")
    )
  test("current PureJvm and CheckedJvm evidence representations remain distinct and source-bound") {
    Vector(false, true).foreach { checked =>
      val value = completedObservation(checked)
      assert(parseObservation(value).isRight)
      val basePath = List("repeatedEpoch", "checkedLikelihood")
      Vector(
        "requestSHA256" -> J.Str("00" * 32),
        "frozenId" -> J.Str("01" * 32),
        "computedRaw32Words" -> J.Num("100"),
        "observedSlot" -> J.Num("37"),
        "jvmMismatchWords" -> J.Num("1"),
        "nativeValidated" -> J.Lit((!checked).toString)
      ).foreach { (name, v) =>
        assert(parseObservation(changed(value, basePath :+ name, v)).isLeft)
      }
      val original = get(
        Bytes.fromHex(
          ReferenceJson.string(
            ReferenceJson.field(value, "repeatedEpoch", "checkedLikelihood", "evidenceOriginal")
          )
        )
      )
      val altered = Bytes(original.value :+ '\n'.toByte)
      val rehashed = changed(
        changed(value, basePath :+ "evidenceOriginal", J.Str(altered.hex)),
        basePath :+ "evidenceSHA256",
        J.Str(sha(altered).hex)
      )
      assert(parseObservation(rehashed).isLeft)
    }
    assert(
      parseObservation(
        changed(
          completedObservation(false),
          List("repeatedEpoch", "checkedLikelihood", "nativeResponseSHA256"),
          J.Str("01" * 32)
        )
      ).isLeft
    )
  }
  test("nonempty registered zero-stake pool checks every raw32 word and raw64 probability") {
    Vector(false, true).foreach { checked =>
      val value = completedObservation(checked, nonempty = true)
      assert(parseObservation(value).isRight)
      val basePath = List("repeatedEpoch", "checkedLikelihood")
      val rawEvidence = get(
        Bytes.fromHex(
          ReferenceJson.string(
            ReferenceJson.field(value, "repeatedEpoch", "checkedLikelihood", "evidenceOriginal")
          )
        )
      )
      val wire = new String(rawEvidence.toArray, "US-ASCII")
      // Analytically exact probability and weights are +0; change one last raw32 bit and rehash.
      val altered = text(wire.dropRight(2) + "1\n")
      val changedEvidence = changed(
        changed(value, basePath :+ "evidenceOriginal", J.Str(altered.hex)),
        basePath :+ "evidenceSHA256",
        J.Str(sha(altered).hex)
      )
      val synchronized =
        if checked then
          changed(
            changed(changedEvidence, basePath :+ "nativeResponseOriginal", J.Str(altered.hex)),
            basePath :+ "nativeResponseSHA256",
            J.Str(sha(altered).hex)
          )
        else changedEvidence
      assert(parseObservation(synchronized).isLeft)
    }
  }
  test("observations above 64 KiB retain bounded exact likelihood words") {
    val weights =
      get(NM.likelihood(Vector.tabulate(100)(i => if i % 2 == 0 then 0 else 0x80000000)))
    val nm = get(NM.state((1 to 80).map(i => bytes(28, i) -> weights).toMap, 123))
    val j = observation(components.copy(nonMyopic = nm))
    val original = EvidenceJson.encode(j)
    assert(original.size > 65536 && original.size < T.MaxBytes)
    assertEquals(get(T.read(original)).original, original)
    assert(T.read(Bytes(Vector.fill(T.MaxBytes + 1)(0.toByte))).isLeft)
  }
  test("unsupported pending effects, donated coins and malformed deposits reject explicitly") {
    Vector(
      paths("donations") -> V.UInt(1),
      paths("deposits") -> V.UInt(1),
      paths("futurePools") -> m(b(28, 99) -> a()),
      Vector(6) -> m()
    ).foreach { (path, replacement) =>
      assert(
        T.decodeComponents(raw(change(sourceValue, path, replacement)), source.pots.maxSupply)
          .isLeft
      )
    }
  }
  test("NoUpdate and PotentialNone remain distinct while pending parameter payloads reject") {
    assertEquals(components.governance.future, RepeatedTerminalGovernance.Future.NoUpdate)
    val potentialSeed = raw(change(sourceValue, paths("futureParameters"), a(V.UInt(2), a())))
    val potentialDebug = raw(change(get(Cbor.decode(potentialSeed)).value, Vector(3, 1, 1, 0), m()))
    val potential = get(T.decodeComponents(potentialSeed, source.pots.maxSupply))
    assertEquals(potential.governance.future, RepeatedTerminalGovernance.Future.PotentialNone)
    assertNotEquals(T.componentsJson(potential), T.componentsJson(components))
    assert(compare(observation(potential), potentialSeed, potentialDebug).isRight)
    assert(compare(observation(), potentialSeed, potentialDebug).isLeft)
    assert(compare(observation(potential)).isLeft)
    Vector(
      a(V.UInt(1), baseParameters),
      a(V.UInt(2), a(baseParameters)),
      a(V.UInt(2), V.Null),
      a(V.UInt(0), a())
    ).foreach { pending =>
      assert(
        T.decodeComponents(
          raw(change(sourceValue, paths("futureParameters"), pending)),
          source.pots.maxSupply
        ).isLeft
      )
    }
  }
  private def decodedReward(value: V) = T.decodeReward(get(RepeatedTerminalCbor.decode(raw(value))))
  test("native Complete reward order negates serialized reserves and fees") {
    val result = get(
      decodedReward(a(a(V.UInt(1), a(V.UInt(3), V.UInt(5), m(), V.UInt(7), a(m(), V.UInt(9))))))
    )
    result match
      case T.Reward.Complete(deltas, rewards, nm) =>
        assertEquals(deltas, B.Deltas(3, -5, -7)); assert(rewards.isEmpty);
        assertEquals(nm.rewardPot, BigInt(9))
      case _ => fail("complete expected")
    val signed = get(
      decodedReward(a(a(V.UInt(1), a(V.NInt(-3), V.NInt(-5), m(), V.NInt(-7), a(m(), V.UInt(0))))))
    )
    signed match
      case T.Reward.Complete(deltas, _, _) => assertEquals(deltas, B.Deltas(-3, 5, 7))
      case _                               => fail("complete expected")
  }
  test("active native Pulsing tag zero is never treated as Complete") {
    assert(decodedReward(a(a(V.UInt(0), a(), a()))).isLeft)
  }
  test("active terminal comparison keeps phase and binds independently checked source globals") {
    // An empty active VMap is still Pulsing until the next native pulseStep.
    val active = a(
      a(
        V.UInt(0),
        a(
          V.UInt(0),
          a(V.UInt(9), V.UInt(0)),
          a(m(), V.UInt(0)),
          V.UInt(0),
          V.UInt(0),
          V.UInt(0),
          m(),
          m()
        ),
        a(
          V.UInt(1),
          a(set(credential(11)), V.UInt(100), a(V.UInt(9), V.UInt(0)), m()),
          m(),
          a(m(), m())
        )
      )
    )
    val seed = raw(change(sourceValue, Vector(4), active))
    val debug = raw(change(get(Cbor.decode(seed)).value, Vector(3, 1, 1, 0), m()))
    val projected = T.componentsJson(get(T.decodeComponents(seed, source.pots.maxSupply)))
    val terminalProtocol =
      raw(change(get(Cbor.decode(protocolOriginal)).value, Vector(1, 0, 1), V.UInt(401)))
    val original = completedObservation(false)
    val observation = changed(
      changed(
        changed(
          changed(
            changed(
              changed(original, List("components"), projected),
              List("componentsSHA256"),
              J.Str(sha(EvidenceJson.encode(projected)).hex)
            ),
            List("pin", "point", "slot"),
            J.Num("401")
          ),
          List("pin", "validationSlot"),
          J.Num("401")
        ),
        List("validationSlot"),
        J.Num("401")
      ),
      List("representedProtocol", "lastSlot"),
      J.Num("401")
    )
    def checked(
        j: J,
        nativeSeed: Bytes = seed,
        nativeDebug: Bytes = debug,
        globals: GovernanceGlobals.Checked = sourceBundle.globals
    ) =
      parseObservation(j).flatMap { o =>
        T.compareComponents(
          o,
          sourceSeed,
          nativeSeed,
          nativeDebug,
          whole,
          whole,
          terminalProtocol,
          globals
        )
      }
    assert(checked(observation).isRight)
    assert(checked(observation, sourceSeed, sourceDebug).isLeft)
    assert(checked(observation, globals = bundle(epochLength = 500).globals).isLeft)
    val base = List("repeatedEpoch", "rewardContext")
    Vector(
      "globalsId" -> J.Str("01" * 32),
      "sourceBindingId" -> J.Str("01" * 32),
      "genesisSHA256" -> J.Str("01" * 32),
      "rewardGlobalsId" -> J.Str("01" * 32),
      "randomnessWindow" -> J.Num("300"),
      "securityParameter" -> J.Num("4"),
      "epochLength" -> J.Num("999"),
      "activeSlotCoefficient" -> J.Arr(Vector(J.Num("1"), J.Num("19"))),
      "maxSupply" -> J.Num((source.pots.maxSupply - 1).toString)
    ).foreach { (key, value) =>
      assert(checked(changed(observation, base :+ key, value)).isLeft, key)
    }
    val metadata = ReferenceJson.field(observation, "repeatedEpoch").asInstanceOf[J.Obj]
    assert(
      parseObservation(
        changed(observation, List("repeatedEpoch"), J.Obj(metadata.fields - "rewardContext"))
      ).isLeft
    )
    val wrongPhase = changed(observation, List("components", "reward", "phase"), J.Str("complete"))
    val rehashedPhase = changed(
      wrongPhase,
      List("componentsSHA256"),
      J.Str(sha(EvidenceJson.encode(ReferenceJson.field(wrongPhase, "components"))).hex)
    )
    assert(checked(rehashedPhase).isLeft)
  }
  test("native reward duplicate ordering keys reject even if amounts differ") {
    val rewardMap =
      m(credential(11) -> set(a(V.UInt(0), b(28, 4), V.UInt(1)), a(V.UInt(0), b(28, 4), V.UInt(2))))
    assert(
      decodedReward(
        a(a(V.UInt(1), a(V.UInt(0), V.UInt(0), rewardMap, V.UInt(0), a(m(), V.UInt(0)))))
      ).isLeft
    )
  }
  test("Complete raw likelihood preserves signed zero and refuses native-incompatible doubles") {
    def result(words: String) =
      val hex = "818201850000a00082a1581c" + "04" * 28 + "9f" + words + "ff00"
      T.decodeReward(get(RepeatedTerminalCbor.decode(get(Bytes.fromHex(hex)))))
    get(result("fa80000000" + "fa00000000" * 99)) match
      case T.Reward.Complete(_, _, nm) =>
        assertEquals(nm.likelihoods(bytes(28, 4)).rawBits, Vector(0x80000000) ++ Vector.fill(99)(0))
      case _ => fail("complete expected")
    assert(result("fb0000000000000000" * 100).isLeft)
    assert(result("fa00000000" * 99).isLeft)
  }

  private val F = SyntheticBoundaryCompositionFixture
  test("selected repeated tuple uses checked DRep completion with nonzero chain treasury") {
    val input = F.governance.copy(
      committeeState = Map.empty,
      treasury = 7,
      accounts = F.governance.accounts.map((c, a) => c -> a.copy(vote = None))
    )
    val pots = F.initialPots.copy(treasury = 7, reserves = F.initialPots.reserves - 7)
    (for
      runtime <- CoherentSequence
        .createWithRepeatedBoundary[IO](
          F.context,
          F.stakeSeed,
          F.boundaryProfile,
          input,
          F.nonMyopic,
          pots,
          Map.empty,
          Map.empty,
          F.pin,
          3
        )
        .map(get(_))
      states <- F.signed(Vector[BigInt](1, 40, 80, 120)).traverse { block =>
        for
          old <- runtime.snapshot
          preview <-
            if block.header.slot / F.epochLength == old.state.ledger.environment.epoch then
              IO.pure(None)
            else
              runtime
                .prepareSyntheticSuccessor(old.fence, block.header.hash, block.header.slot)
                .map(x => Some(get(x)))
          candidate <- runtime
            .prepareRepeatedBlock(
              old.fence,
              block,
              preview,
              (_, _) => IO.raiseError[N.Generated](new IllegalStateException("unexpected freeze"))
            )
            .map(get(_))
          result <- runtime.publish(candidate).map(get(_))
        yield result.state
      }
      _ = states.tail.foreach { state =>
        val projected = get(T.fromState(state))
        assertEquals(projected.pots.treasury, BigInt(7))
        assertEquals(projected.governance.completed.enact.treasury, BigInt(0))
        assertEquals(projected.governance.future, RepeatedTerminalGovernance.Future.PotentialNone)
        assertEquals(projected.epoch, state.ledger.environment.epoch)
        assertEquals(projected.governance.dormant, input.dormant + projected.epoch)
      }
      last = states.last
      bad = get(
        StatePin.checked(
          bytes(32, 20),
          1,
          last.certificates.state.tip,
          last.id,
          last.ledger.id,
          bytes(32, 21),
          last.ledger.slot,
          AdmissionProfile.PlutusV3.id
        )
      )
      direct = T.encode(last, bad, bytes(32, 22), "23" * 32)
      _ = assert(direct.isLeft)
      _ = assertEquals(
        PlutusServiceRuntime.terminalObservation(
          last,
          bad,
          bytes(32, 22),
          "23" * 32,
          Some(PlutusServiceCommand.Repeated.Jvm)
        ),
        direct
      )
    yield ()).unsafeToFuture()
  }
