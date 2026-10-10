// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.Path
import lab.cbor.Bytes
import lab.ledger.{ConwayNativeLikelihood as N, ConwayEpochBoundary as B}
import lab.submission.{StatePin, AdmissionProfile}
import ReferenceJson.{Json as J, field}

/** Optional independently pinned private inputs, never counted as available public fixtures. */
class RepeatedPlutusTerminalRetainedSuite extends munit.FunSuite:
  private def get[A](e: Either[?, A]): A = e.fold(e => fail(e.toString), identity)
  sys.env.get("PLUTUS_BOOTSTRAP_BUNDLE").foreach { directory =>
    lazy val manifest =
      sys.env.getOrElse("PLUTUS_BOOTSTRAP_MANIFEST_SHA256", fail("manifest pin required"))
    lazy val initial =
      PlutusResearchIO.initial(Path.of(directory), manifest, AdmissionProfile.PlutusV3)
    val oracle = new NativeLikelihoodOracle.Oracle[IO]:
      def generate(frozen: B.Frozen, id: Bytes): IO[N.Generated] =
        IO(get(lab.ledger.ConwayLikelihoodGeneration.generateJvm(frozen, id)))
    test("selected source-backed export and runtime hook bind the supplied complete state pin") {
      (for
        early <- PlutusServiceCheckpoint.start(None, initial, get(Bytes.fromHex(manifest)))
        started <- RepeatedPlutusBootstrap.start(initial, early, oracle)
        state = started.snapshot.state
        pin = get(
          StatePin.checked(
            initial.id,
            0,
            state.certificates.state.tip,
            state.id,
            state.ledger.id,
            state.ledger.environment.id,
            state.ledger.slot,
            AdmissionProfile.PlutusV3.id
          )
        )
        direct = RepeatedPlutusTerminal.encode(state, pin, initial.id, manifest)
        _ = assert(direct.isRight)
        _ = assertEquals(
          PlutusServiceRuntime.terminalObservation(
            state,
            pin,
            initial.id,
            manifest,
            Some(PlutusServiceCommand.Repeated.Jvm)
          ),
          direct
        )
        parsed = get(RepeatedPlutusTerminal.read(EvidenceJson.encode(get(direct))))
        _ = assertEquals(parsed.pin, pin)
        _ = assertEquals(parsed.source, initial.id)
        _ = assertEquals(field(parsed.json, "pin"), PlutusServiceRuntime.pin(pin))
        _ = Vector("coherent", "ledger", "environment", "slot").foreach { changed =>
          val wrong = Bytes(Vector.fill(32)(99.toByte))
          val p = get(
            StatePin.checked(
              pin.ownerId,
              pin.generation,
              pin.point,
              if changed == "coherent" then wrong else pin.coherentStateId,
              if changed == "ledger" then wrong else pin.ledgerStateId,
              if changed == "environment" then wrong else pin.environmentId,
              if changed == "slot" then pin.validationSlot + 1 else pin.validationSlot,
              pin.profileId
            )
          )
          val rejected = RepeatedPlutusTerminal.encode(state, p, initial.id, manifest)
          assert(rejected.isLeft)
          assertEquals(
            PlutusServiceRuntime.terminalObservation(
              state,
              p,
              initial.id,
              manifest,
              Some(PlutusServiceCommand.Repeated.Jvm)
            ),
            rejected
          )
        }
      yield ()).unsafeToFuture()
    }
  }
