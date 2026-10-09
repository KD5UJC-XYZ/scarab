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
          joined.ledger.globals,
          joined.ledger.globals.randomnessStabilisationWindow
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
        _ = println(
          s"""NATIVE_BOUNDARY_RESULT={"sourceJoinId":"${joined.id.hex}","linkedOriginalsId":"${linked.id.hex}","stateId":"${state.id.hex}","acceptedBlocks":${report.counters.acceptedBlocks},"compactions":${report.counters.compactions},"retainedBlocks":${state.acquisition.size},"terminalSlot":${terminal.slot},"terminalBlockNo":${terminal.blockNo},"terminalHash":"${terminal.hash.hex}","epoch":1,"scopedBoundaryApplied":true,"nativeConformance":false,"runtimeImport":false,"fullLedgerValidated":false}"""
        )
        _ = assert(
          !joined.nativeConformance && !joined.runtimeImport && !joined.fullLedgerValidated
        )
      yield ()).unsafeToFuture()
    }
  }
