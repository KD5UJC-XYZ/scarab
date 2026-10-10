// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.Path
import java.time.Instant
import lab.submission.AdmissionProfile

/** Retained native sources are private and independently pinned by the test caller. */
class PlutusBootstrapSuite extends munit.FunSuite:
  private def get[A](value: Either[?, A]): A = value.fold(e => fail(e.toString), identity)
  sys.env.get("PLUTUS_BOOTSTRAP_BUNDLE").foreach { directory =>
    test("actual native sources bind opt-in environment to original parameters and genesis time") {
      val pin = sys.env.getOrElse("PLUTUS_BOOTSTRAP_MANIFEST_SHA256", fail("manifest pin required"))
      val joined =
        NativeLiveBoundaryMain.initial(Path.of(directory), pin, AdmissionProfile.PlutusV3)
      val epoch = joined.ledger.epochComponents
      assertEquals(epoch.admissionProfile, AdmissionProfile.PlutusV3)
      assertEquals(epoch.stake.plutusNetwork, Some(0))
      val context = get(SequenceInput.fromNativeDiagnostic(joined, joined.id))
      val environment = context.ledger.environment.plutus.getOrElse(fail("profile environment"))
      assertEquals(environment.parameters.sourceSHA256, epoch.parameters.current.sha256)
      assertEquals(environment.time.genesisDigest, epoch.parameters.genesisSHA256)
      val genesis = ReferenceJson.parse(epoch.parameters.genesisOriginal)
      val start = Instant.parse(ReferenceJson.string(ReferenceJson.field(genesis, "systemStart")))
      assertEquals(environment.time.systemStartMillis, BigInt(start.toEpochMilli))
      val duration = epoch.parameters.slotLength
      assertEquals(
        environment.time.slotLengthNumeratorMillis * duration.denominator,
        duration.numerator * 1000 * environment.time.slotLengthDenominator
      )
      assertEquals(context.ledger.profileId, AdmissionProfile.PlutusV3.id)
      assertEquals(context.ledger.outputMap, epoch.stake.utxo)
      assertEquals(context.ledger.fees, epoch.pots.fees)
    }
  }
