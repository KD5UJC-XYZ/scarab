// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.syntax.all.*
import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.{
  ScriptContexts,
  TranslationError,
  Tx as FlatTx,
  TxIn,
  TxOut as FlatTxOut,
  Unsigned
}
import xyz.kd5ujc.scarab.ledger.era.conway.{
  ConwayContextError,
  ConwayPlutusPurpose,
  ConwayTx,
  ConwayUTxO
}
import xyz.kd5ujc.scarab.ledger.state.{Conway, ConwayNewEpochState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.node.mempool.LocalSubmission
import xyz.kd5ujc.scarab.node.ntc.ReferenceRefusalSuite.*
import xyz.kd5ujc.scarab.plutus.PlutusLanguage
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, UtxoCodecs}

/** Conway's Plutus V3 `TxInfo` on the era types (`EraPlutusTxInfo 'PlutusV3 ConwayEra`) against the
  * flat translation (`ScriptContexts`) on every transaction of the recorded Conway refusal corpora,
  * with the UTxO set each was refused against, at its parameters' protocol version: the same data,
  * or the same failure. The corpora carry no era history, so slot `s` is the time `s` seconds for
  * both.
  */
class ConwayPlutusContextRefusalsSuite extends FunSuite:
  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private final case class Seen(
      label: String,
      flat: FlatTx,
      typed: ConwayTx,
      stored: Map[TxIn, FlatTxOut],
      utxo: UTxO[Conway],
      major: Int
  )

  private val seen: Vector[Seen] =
    val loaded = right("the refusal corpus")(load(Path.of("fixtures/reference/refusals")))
    loaded.corpora.filter(_.era == RefusalEra.Conway).flatMap { c =>
      val codecs = right(s"${c.path} layout")(stateCodecs(c.build))
      c.cases.map { rc =>
        val what = s"${c.path}/${rc.label}"
        val bytes = right(s"$what item")(LocalSubmission.unwrap(rc.item)).raw
        val nes = right(s"$what state")(
          codecs.newEpochState[Conway].decodeBytes(c.states(rc.state), HaskellState.limits)
        )
        val stored = right(s"$what UTxO")(UtxoCodecs.utxo.decodeBytes(c.utxos(rc.utxo)))
        Seen(
          what,
          right(s"$what flat")(FlatTx.fromEnvelope(bytes, Era.Conway)),
          right(s"$what typed")(ConwayTx.fromEnvelope(bytes)),
          stored,
          right(s"$what typed UTxO")(ConwayUTxO.fromStored(stored)),
          ConwayNewEpochState.gov.get(nes).curPParams.protocolMajor.toInt
        )
      }
    }

  private val clock: BigInt => Either[TranslationError, BigInt] = slot => Right(slot * 1000)

  /** The flat failure a typed one corresponds to. */
  private def asFlat(e: ConwayContextError[Conway]): TranslationError = e match
    case ConwayContextError.BabbageContextError(b) =>
      b match
        case BabbageContextError.AlonzoContextError(a) =>
          a match
            case AlonzoContextError.TranslationLogicMissingInput(in) =>
              TranslationError.UnresolvedInput(in)
            case AlonzoContextError.TimeTranslationPastHorizon(cause) => cause
            case AlonzoContextError.Undecodable(site) => TranslationError.Undecodable(site)
        case BabbageContextError.ByronTxOutInContext(source) =>
          TranslationError.ByronTxOutInContext(source)
        case BabbageContextError.RedeemerPointerPointsToNothing(ptr) =>
          TranslationError.RedeemerPointerPointsToNothing(ptr.tag, ptr.ix.value)
        case other => fail(s"no Plutus V3 translation fails with $other")
    case ConwayContextError.ReferenceInputsNotDisjointFromInputs(common) =>
      TranslationError.ReferenceInputsNotDisjointFromInputs(common)
    case other => fail(s"no Plutus V3 translation fails with $other")

  test("the typed V3 TxInfo is the flat one on every Conway case of the refusal corpora") {
    // the Conway cases of the corpora: a loader that loses one fails here
    assertEquals(seen.size, RefusalCaseCount)
    val outcomes = seen.map { s =>
      val pv = ProtVer(
        Version.mkVersion(s.major).getOrElse(fail(s"version ${s.major}")),
        Unsigned.from[Unsigned.Width.W32](BigInt(0)).getOrElse(fail("minor"))
      )
      val ours = EraPlutusTxInfo.conwayPlutusV3
        .toPlutusTxInfo(LedgerTxInfo(pv, clock, s.utxo, s.typed))
        .unPlutusTxInfoResult
        .flatMap(_(ConwayPlutusPurpose.ConwaySpending(AsPurpose())))
        .bimap(asFlat, ToData[PV3.TxInfo].toBuiltinData)
      val env = ScriptContexts.Environment(
        ShelleyBasedEra.SConway,
        s.major,
        s.stored.filter((in, _) => s.flat.body.inputs.contains(in)),
        s.stored.filter((in, _) => s.flat.body.referenceInputs.contains(in)),
        clock
      )
      val flat = ScriptContexts.txInfo(PlutusLanguage.V3, s.flat, env)
      assertEquals(ours, flat, s.label)
      flat
    }
    assert(outcomes.exists(_.isRight))
    // the refusals the corpora reach: Byron outputs and inputs the UTxO set lacks
    assertEquals(
      outcomes.collect {
        case Left(TranslationError.ByronTxOutInContext(_)) => "Byron"
        case Left(TranslationError.UnresolvedInput(_))     => "missing"
      },
      Vector("Byron", "Byron", "missing", "missing", "missing")
    )
  }

  private val RefusalCaseCount = 84
