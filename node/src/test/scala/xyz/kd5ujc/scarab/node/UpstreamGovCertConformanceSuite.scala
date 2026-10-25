// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Instant
import cats.syntax.all.*
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{
  Committee,
  DecoderVersion,
  EpochGeometry,
  GovActionId,
  GovActionState,
  LedgerGlobals,
  Certificate
}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.rules.{ConwayCert, ConwayGovCert, Globals, GovCert, JudgedSts}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, Params, Wire}

import HaskellStateCodecs.given

/** Full trace exports from cardano-ledger's actual Conway GOVCERT conformance property. The
  * observer only serializes the generated judgment after the upstream implementation and Agda
  * oracle have run; it does not replace their generator or change property semantics.
  */
class UpstreamGovCertConformanceSuite extends FunSuite:
  private val base = Path.of("node/src/test/resources/upstream/conway-govcert")
  private val seeds = 490049L to 490069L
  private val protVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)

  private def bytes(path: Path): Bytes = Bytes.fromArray(Files.readAllBytes(path))

  private def node(path: Path) =
    Cbor.decode(bytes(path)).fold(e => fail(s"$path: $e"), identity)

  private def item(
      n: xyz.kd5ujc.scarab.models.runtime.Node,
      index: Int
  ): xyz.kd5ujc.scarab.models.runtime.Node =
    n.value match
      case Value.Arr(xs) => xs.lift(index).getOrElse(fail(s"missing array item $index in $n"))
      case other         => fail(s"expected array, got $other")

  private def state(path: Path): ConwayCertState =
    given ProtVer = protVer
    summon[Codec[ConwayCertState]].decode(node(path)).fold(e => fail(e.render), identity)

  private def sha256(path: Path): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(path))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def globals: Globals =
    // These GOVCERT judgments do not read Globals. RuleT still requires the reference interpreter's
    // ambient capability; all rule inputs it does read come from the exported judgment below.
    Globals(
      LedgerGlobals(BigInt(0), BigInt(1), Rational.zero, network = 0),
      EpochGeometry(Instant.EPOCH, 1000L, 1L, 1, BigDecimal("0.1"))
    )

  test("pinned upstream GOVCERT exports retain all generated verdicts and provenance") {
    val families = seeds.map { seed =>
      val dir = base.resolve(s"cases/$seed")
      val result = Files.readString(dir.resolve("result.txt"))
      assertEquals(result, "implementation-accepted\nagda-accepted\n", s"seed $seed")
      val signal = node(dir.resolve("signal.cbor"))
      val tag = item(signal, 0).value match
        case Value.UInt(n) => n.toInt
        case other         => fail(s"seed $seed has non-integer signal tag $other")
      assertEquals(sha256(dir.resolve("signal.cbor")).length, 64)
      tag
    }
    assertEquals(families.count(_ == 16), 3)
    assertEquals(families.count(_ == 18), 4)
    assertEquals(families.count(_ == 14), 7)
    assertEquals(families.count(_ == 15), 7)
  }

  test("all generated GOVCERT families consume full upstream traces and match full Haskell state") {
    given ProtVer = protVer
    var matched = 0
    seeds.foreach { seed =>
      val dir = base.resolve(s"cases/$seed")
      val rawEnvironment = bytes(dir.resolve("environment.cbor"))
      val environment = Cbor.decode(rawEnvironment).fold(e => fail(s"seed $seed env: $e"), identity)
      // The upstream environment is [PParams, epoch, current committee, committee proposals].
      // Read every environment field with its exact Conway codec, while keeping every original
      // environment byte in the corpus. No missing input or committee proposal is defaulted.
      val envItems = environment.value match
        case Value.Arr(xs) if xs.size == 4 => xs
        case other => fail(s"seed $seed has unexpected full GOVCERT environment $other")
      val pp = Params.conwayCodec.decode(envItems(0)).fold(e => fail(e.render), identity)
      val epoch = summon[Codec[EpochNo]].decode(envItems(1)).fold(e => fail(e.render), identity)
      val committee = Wire
        .strictMaybe(summon[Codec[Committee]])
        .decode(envItems(2))
        .fold(e => fail(e.render), identity)
      val proposalEntries = envItems(3).value match
        case Value.Map(entries) =>
          entries
            .traverse { (key, proposal) =>
              (
                summon[Codec[GovActionId]].decode(key),
                summon[Codec[GovActionState]].decode(proposal)
              ).mapN((id, state) => id -> state)
            }
            .fold(e => fail(s"seed $seed committee proposal map: ${e.render}"), identity)
        case other => fail(s"seed $seed has malformed committee proposal map $other")
      assertEquals(environment.original, rawEnvironment)
      val contextBytes = bytes(dir.resolve("context.cbor"))
      val contextNode =
        Cbor.decode(contextBytes).fold(e => fail(s"seed $seed context: $e"), identity)
      assert(
        contextNode.value.isInstanceOf[Value.Arr],
        s"seed $seed context is retained as its original TRC"
      )

      val certificate = Certificate
        .decode(node(dir.resolve("signal.cbor")), DecoderVersion.of(Era.Conway))
        .fold(e => fail(s"seed $seed certificate: $e"), identity)
      ConwayCert
        .of(Bytes.empty, certificate)
        .fold(e => fail(s"seed $seed typed certificate: $e"), identity) match
        case ConwayCert.OfGov(ConwayGovCert.RegDRep(credential, deposit, anchor)) =>
          val before = state(dir.resolve("state.cbor"))
          val expected = state(dir.resolve("implementation-state.cbor"))
          val actual = JudgedSts
            .apply(GovCert.DRepAdapter)(
              xyz.kd5ujc.scarab.ledger.LedgerFailure.Rejected(Bytes.empty, _)
            )(
              globals,
              GovCert.DRepRuleEnv(Bytes.empty, pp, epoch.toBigInt),
              before,
              GovCert.DRepRuleSignal.Register(credential, deposit, anchor)
            )
            .fold(
              e => fail(s"seed $seed Scarab refused upstream-accepted registration: $e"),
              identity
            )
          assertEquals(actual, expected, s"seed $seed full ConwayCertState")
          assertEquals(actual.pState, before.pState, s"seed $seed pool state")
          assertEquals(actual.dState, before.dState, s"seed $seed delegation state")
          assertEquals(
            actual.vState.committeeState,
            before.vState.committeeState,
            s"seed $seed committee state"
          )
          assertEquals(
            actual.vState.numDormantEpochs,
            before.vState.numDormantEpochs,
            s"seed $seed dormant epochs"
          )
          assert(
            !before.vState.dreps.contains(credential),
            s"seed $seed generated registration was already present"
          )
          matched += 1
        case ConwayCert.OfGov(ConwayGovCert.UpdateDRep(credential, anchor)) =>
          val before = state(dir.resolve("state.cbor"))
          val expected = state(dir.resolve("implementation-state.cbor"))
          assert(
            before.vState.dreps.contains(credential),
            s"seed $seed generated update lacks registered DRep"
          )
          val actual = JudgedSts
            .apply(GovCert.DRepAdapter)(
              xyz.kd5ujc.scarab.ledger.LedgerFailure.Rejected(Bytes.empty, _)
            )(
              globals,
              GovCert.DRepRuleEnv(Bytes.empty, pp, epoch.toBigInt),
              before,
              GovCert.DRepRuleSignal.Update(credential, anchor)
            )
            .fold(e => fail(s"seed $seed Scarab refused upstream-accepted update: $e"), identity)
          assertEquals(actual, expected, s"seed $seed full ConwayCertState")
          assertEquals(actual.pState, before.pState, s"seed $seed pool state")
          assertEquals(actual.dState, before.dState, s"seed $seed delegation state")
          assertEquals(
            actual.vState.committeeState,
            before.vState.committeeState,
            s"seed $seed committee state"
          )
          assertEquals(
            actual.vState.numDormantEpochs,
            before.vState.numDormantEpochs,
            s"seed $seed dormant epochs"
          )
          matched += 1
        case ConwayCert.OfGov(ConwayGovCert.AuthCommitteeHotKey(cold, hot)) =>
          val before = state(dir.resolve("state.cbor"))
          val expected = state(dir.resolve("implementation-state.cbor"))
          val actual = JudgedSts
            .apply(GovCert.CommitteeAdapter)(
              xyz.kd5ujc.scarab.ledger.LedgerFailure.Rejected(Bytes.empty, _)
            )(
              globals,
              GovCert.CommitteeRuleEnv(Bytes.empty, committee, proposalEntries),
              before,
              GovCert.CommitteeRuleSignal.Authorize(cold, hot)
            )
            .fold(
              e => fail(s"seed $seed Scarab refused upstream-accepted committee authorization: $e"),
              identity
            )
          assertEquals(actual, expected, s"seed $seed full ConwayCertState")
          assertEquals(actual.pState, before.pState, s"seed $seed pool state")
          assertEquals(actual.dState, before.dState, s"seed $seed delegation state")
          assertEquals(actual.vState.dreps, before.vState.dreps, s"seed $seed DRep state")
          assertEquals(
            actual.vState.numDormantEpochs,
            before.vState.numDormantEpochs,
            s"seed $seed dormant epochs"
          )
          matched += 1
        case ConwayCert.OfGov(ConwayGovCert.ResignCommitteeColdKey(cold, anchor)) =>
          val before = state(dir.resolve("state.cbor"))
          val expected = state(dir.resolve("implementation-state.cbor"))
          val actual = JudgedSts
            .apply(GovCert.CommitteeAdapter)(
              xyz.kd5ujc.scarab.ledger.LedgerFailure.Rejected(Bytes.empty, _)
            )(
              globals,
              GovCert.CommitteeRuleEnv(Bytes.empty, committee, proposalEntries),
              before,
              GovCert.CommitteeRuleSignal.Resign(cold, anchor)
            )
            .fold(
              e => fail(s"seed $seed Scarab refused upstream-accepted committee resignation: $e"),
              identity
            )
          assertEquals(actual, expected, s"seed $seed full ConwayCertState")
          assertEquals(actual.pState, before.pState, s"seed $seed pool state")
          assertEquals(actual.dState, before.dState, s"seed $seed delegation state")
          assertEquals(actual.vState.dreps, before.vState.dreps, s"seed $seed DRep state")
          assertEquals(
            actual.vState.numDormantEpochs,
            before.vState.numDormantEpochs,
            s"seed $seed dormant epochs"
          )
          matched += 1
        case ConwayCert.OfGov(other) =>
          fail(s"seed $seed DRep adapter received unadapted GOVCERT $other")
        case other => fail(s"seed $seed DRep adapter received non-GOVCERT certificate $other")
    }
    assertEquals(matched, 21)
  }
