// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import java.nio.file.Path
import lab.cbor.Bytes
import lab.header.PraosCertificateState.Point
import ReferenceJson.{Json as J, field, string, uint, array}

/** Private opt-in supplied-state replay. No public runner, persistence or native admission. */
class NativeBoundaryDiagnosticSuite extends NativeLedgerV2AuditedFixture:
  private def point(j: J): Point =
    assertEquals(obj(j).keySet, Set("hash", "slot", "blockNo"))
    Point(hash(field(j, "hash")), uint(field(j, "slot")), uint(field(j, "blockNo")))

  private def checkEndpoint(
      joined: NativeLedgerV2.Checked,
      state: CoherentSequence.State,
      terminal: Point,
      before: CoherentSequence.State
  ): Unit = sys.env.get("NATIVE_ENDPOINT_BUNDLE").foreach { directory =>
    val root = Path.of(directory)
    val raw = read(root.resolve("endpoint-inputs.json"), 16384)
    assertEquals(
      sha(raw).hex,
      sys.env.getOrElse(
        "NATIVE_ENDPOINT_MANIFEST_SHA256",
        fail("external endpoint manifest pin required")
      )
    )
    val descriptor = ReferenceJson.parse(raw)
    assertEquals(
      obj(descriptor).keySet,
      Set("schema", "point", "genesisSHA256", "acquisitionResultSHA256", "inputs")
    )
    assertEquals(string(field(descriptor, "schema")), "native-endpoint-reviewed-inputs-v1")
    assertEquals(point(field(descriptor, "point")), terminal)
    assertEquals(hash(field(descriptor, "genesisSHA256")), joined.ledger.globals.genesisSHA256)
    val acquisitionResult = read(
      Path.of(
        sys.env.getOrElse(
          "NATIVE_ENDPOINT_ACQUISITION_RESULT",
          fail("pinned acquisition result required")
        )
      ),
      65536
    )
    assertEquals(sha(acquisitionResult), hash(field(descriptor, "acquisitionResultSHA256")))
    // This verifies the retained attribution bytes, not snapshot authenticity.
    val inputs = obj(field(descriptor, "inputs"))
    assertEquals(inputs.keySet, NativeProtocolBootstrap.InputNames)
    val originals = inputs.map { (name, row) =>
      assertEquals(obj(row).keySet, Set("sha256", "bytes"))
      val bytes = read(root.resolve(name), 524288)
      assertEquals(BigInt(bytes.size), uint(field(row, "bytes")), name)
      assertEquals(sha(bytes), hash(field(row, "sha256")), name)
      name -> bytes
    }
    val pins = inputs.map((name, row) => name -> hash(field(row, "sha256")))
    val endpoint = get(NativeProtocolBootstrap.checkAcquisition(originals, pins, terminal))
    val protocol = get(NativeEndpointProtocol.compare(endpoint, terminal, endpoint.id, state))
    println(s"NATIVE_ENDPOINT_PROTOCOL_EQUAL=${protocol.protocolSHA256.hex}")
    val ledger = get(NativeEndpointLedger.compare(endpoint, terminal, endpoint.id, state, joined))
    val wrongId = Bytes(endpoint.id.value.updated(0, (endpoint.id.value.head ^ 1).toByte))
    val wrongPoint = terminal.copy(blockNo = terminal.blockNo + 1)
    assert(NativeEndpointProtocol.compare(endpoint, terminal, wrongId, state).isLeft)
    assert(NativeEndpointProtocol.compare(endpoint, wrongPoint, endpoint.id, state).isLeft)
    assert(NativeEndpointLedger.compare(endpoint, terminal, wrongId, state, joined).isLeft)
    assert(NativeEndpointLedger.compare(endpoint, wrongPoint, endpoint.id, state, joined).isLeft)
    println(s"NATIVE_ENDPOINT_PROTOCOL_ID=${protocol.acquisitionId.hex}")
    println("NATIVE_ENDPOINT_LEDGER_DOMAINS=" + ledger.domainsChecked.toVector.sorted.mkString(","))
    val governance = get(NativeEndpointGovernance.compare(ledger, state))
    assert(NativeEndpointGovernance.compare(ledger, before).isLeft)
    assert(
      governance.normalizedSerializationEqual && !governance.liveCursorEqual &&
        !governance.fullLedgerEquality && !ledger.fullLedgerEquality
    )
    val domains =
      (ledger.domainsChecked ++ governance.domainsChecked + "praos-eight-payload-fields").toVector.sorted
    val domainsJson = domains.map(x => "\"" + x + "\"").mkString("[", ",", "]")
    val unsupportedJson = (ledger.unsupportedDomains ++ Set(
      "productive-nonempty-go-rewards",
      "general-consensus-conformance",
      "native-snapshot-authenticity",
      "live-streaming",
      "persistence"
    )).toVector.sorted.map(x => "\"" + x + "\"").mkString("[", ",", "]")
    println(
      s"""NATIVE_ENDPOINT_RESULT={"endpointAcquisitionId":"${endpoint.id.hex}","sourceJoinId":"${joined.id.hex}","replayStateId":"${state.id.hex}","terminalSlot":${terminal.slot},"terminalBlockNo":${terminal.blockNo},"terminalHash":"${terminal.hash.hex}","domainsChecked":$domainsJson,"unsupportedDomains":$unsupportedJson,"representedProtocolEqual":true,"finiteLedgerProfileEqual":true,"normalizedGovernanceEqual":true,"livePulserCursorEqual":false,"nativeConformance":false,"runtimeImport":false,"fullLedgerValidated":false}"""
    )
  }

  sys.env.get("NATIVE_LINKED_BLOCKS_BUNDLE").foreach { directory =>
    test("pinned original linked blocks cross one scoped supplied native boundary offline") {
      val joined = loadFresh(
        sys.env.getOrElse("NATIVE_LEDGER_V2_BUNDLE", fail("fresh v2 input directory required"))
      )
      val root = Path.of(directory)
      val manifest = read(root.resolve("linked-blocks.json"), 65536)
      assertEquals(
        sha(manifest).hex,
        sys.env.getOrElse(
          "NATIVE_LINKED_BLOCKS_MANIFEST_SHA256",
          fail("external linked manifest pin required")
        )
      )
      val descriptor = ReferenceJson.parse(manifest)
      assertEquals(
        obj(descriptor).keySet,
        Set("schema", "sourceJoinId", "databaseInventorySHA256", "anchor", "terminal", "blocks")
      )
      assertEquals(string(field(descriptor, "schema")), "native-linked-originals-v1")
      assertEquals(hash(field(descriptor, "sourceJoinId")), joined.id)
      hash(
        field(descriptor, "databaseInventorySHA256")
      ) // Attribution pin, not proof of extraction or DB validity.
      assertEquals(point(field(descriptor, "anchor")), joined.point)
      val terminal = point(field(descriptor, "terminal"))
      val rows = array(field(descriptor, "blocks"))
      assert(rows.nonEmpty && rows.size <= 128)
      val inputs = rows.zipWithIndex.map { (row, index) =>
        assertEquals(obj(row).keySet, Set("file", "sha256", "bytes"))
        val name = f"block-$index%04d.cbor"
        assertEquals(string(field(row, "file")), name)
        val bytes = read(root.resolve(name), 1048576)
        assertEquals(BigInt(bytes.size), uint(field(row, "bytes")))
        assertEquals(sha(bytes), hash(field(row, "sha256")))
        bytes
      }
      val linked = get(
        NativeLinkedBlocks.bind(
          joined.point,
          terminal,
          joined.ledger.globals.geometry.epochLength,
          joined.id,
          inputs,
          rows.map(row => hash(field(row, "sha256")))
        )
      )
      val context = get(SequenceInput.fromNativeDiagnostic(joined, joined.id))
      val epoch = joined.ledger.epochComponents
      val profile = get(
        CoherentSequence.syntheticBoundaryProfile(
          joined.ledger.parameterRoles,
          joined.ledger.pools,
          joined.ledger.globals
        )
      )
      (for
        ordinary <- CoherentSequence.create[IO](context)
        _ = assert(ordinary.isLeft)
        runtime <- CoherentSequence
          .createWithSyntheticBoundary[IO](
            context,
            epoch.stake,
            profile,
            joined.ledger.governanceInput,
            epoch.nonMyopic,
            epoch.pots,
            epoch.previousBlocks,
            epoch.currentBlocks,
            epoch.reward.componentSHA256
          )
          .map(get(_))
        before <- runtime.snapshot
        queue <- Ref.of[IO, Vector[EphemeralStreaming.Event]](
          linked.blocks.map(EphemeralStreaming.Event.Block.apply)
        )
        report <- EphemeralStreaming.run(runtime, EphemeralStreaming.Limits())(
          queue.modify(xs => (xs.drop(1), xs.headOption))
        )
        _ = assertEquals(report.stop, EphemeralStreaming.Stop.End)
        _ = assertEquals(report.counters.acceptedBlocks, linked.blocks.size.toLong)
        state = report.snapshot.state
        _ = assertEquals(state.certificates.state.tip, terminal)
        _ = assertEquals(state.ledger.environment.epoch, BigInt(1))
        _ = assert(state.syntheticBoundary.exists(_.boundaryApplied))
        _ = assert(state.acquisition.size <= 8)
        _ = checkEndpoint(joined, state, terminal, before.state)
        _ = println(
          s"""NATIVE_BOUNDARY_RESULT={"sourceJoinId":"${joined.id.hex}","linkedOriginalsId":"${linked.id.hex}","stateId":"${state.id.hex}","acceptedBlocks":${report.counters.acceptedBlocks},"compactions":${report.counters.compactions},"retainedBlocks":${state.acquisition.size},"terminalSlot":${terminal.slot},"terminalBlockNo":${terminal.blockNo},"terminalHash":"${terminal.hash.hex}","epoch":1,"scopedBoundaryApplied":true,"nativeConformance":false,"runtimeImport":false,"fullLedgerValidated":false}"""
        )
        _ = assert(
          !joined.nativeConformance && !joined.runtimeImport && !joined.fullLedgerValidated
        )
      yield ()).unsafeToFuture()
    }
  }
