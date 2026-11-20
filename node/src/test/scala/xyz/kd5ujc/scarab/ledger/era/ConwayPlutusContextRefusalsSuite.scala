// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.protocol.StateLayout
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
          right(s"$what typed")(ConwayTx.fromEnvelope(bytes, StateLayout.Node11_1)),
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
            case AlonzoContextError.Undecodable(site, _) => TranslationError.Undecodable(site)
            case AlonzoContextError.CertificateNotSupported(_) |
                AlonzoContextError.PlutusPurposeNotSupported(_) =>
              fail(s"Conway's injection keeps $a its own")
            case AlonzoContextError.ImpossibleContextError(_) =>
              fail(s"no Conway translation fails with $a")
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
        .toPlutusTxInfo(topTxInfo(pv, s))
        .unPlutusTxInfoResult
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

  /** A case's transaction information at the top level, no script used and no sub-transaction's. */
  private def topTxInfo(pv: ProtVer, s: Seen): LedgerTxInfo[TxLevel.Top, Conway] =
    LedgerTxInfo[TxLevel.Top, Conway](
      pv,
      clock,
      s.utxo,
      s.typed,
      Nil,
      LedgerLevelTxInfo.LedgerTopTxInfo(Map.empty)
    )

  /** A failure in a form both paths give it in (the votes as a map, a certificate and a proposal as
    * the flat ones).
    */
  private def normFlat(e: TranslationError): Any = e match
    case TranslationError.VotingProceduresFieldNotSupported(votes) =>
      ("votes", votes.map((voter, vs) => voter -> vs.toMap).toMap)
    case other => other

  private def normTyped(e: ConwayContextError[Conway]): Any = e match
    case ConwayContextError.VotingProceduresFieldNotSupported(votes) =>
      ("votes", votes.unVotingProcedures.map((voter, vs) => voter -> vs.toMap).toMap)
    case ConwayContextError.ProposalProceduresFieldNotSupported(proposals) =>
      TranslationError.ProposalProceduresFieldNotSupported(proposals.toVector.map(_.read))
    case ConwayContextError.TreasuryDonationFieldNotSupported(coin) =>
      TranslationError.TreasuryDonationFieldNotSupported(coin.value)
    case ConwayContextError.CurrentTreasuryFieldNotSupported(coin) =>
      TranslationError.CurrentTreasuryFieldNotSupported(coin.value)
    case ConwayContextError.CertificateNotSupported(cert) =>
      TranslationError.CertificateNotSupported(conway.ConwayTxCert.toStored(cert))
    case ConwayContextError.BabbageContextError(
          BabbageContextError.InlineDatumsNotSupported(source)
        ) =>
      TranslationError.InlineDatumsNotSupported(source)
    case other => asFlat(other)

  test(
    "the typed V1 and V2 TxInfos are the flat ones on every Conway case of the refusal corpora"
  ) {
    val ctx = EraPlutusContext.conway
    val outcomes = seen.flatMap { s =>
      val pv = ProtVer(
        Version.mkVersion(s.major).getOrElse(fail(s"version ${s.major}")),
        Unsigned.from[Unsigned.Width.W32](BigInt(0)).getOrElse(fail("minor"))
      )
      val result = ctx.mkTxInfoResult(topTxInfo(pv, s))
      val env = ScriptContexts.Environment(
        ShelleyBasedEra.SConway,
        s.major,
        s.stored.filter((in, _) => s.flat.body.inputs.contains(in)),
        s.stored.filter((in, _) => s.flat.body.referenceInputs.contains(in)),
        clock
      )
      val v1 = ctx
        .lookupTxInfoResult[Language.PlutusV1](result)
        .unPlutusTxInfoResult
        .bimap(normTyped, ToData[PV1.TxInfo].toBuiltinData)
      val v2 = ctx
        .lookupTxInfoResult[Language.PlutusV2](result)
        .unPlutusTxInfoResult
        .bimap(normTyped, ToData[PV2.TxInfo].toBuiltinData)
      Vector(PlutusLanguage.V1 -> v1, PlutusLanguage.V2 -> v2).map { (language, ours) =>
        val flat = ScriptContexts.txInfo(language, s.flat, env)
        assertEquals(ours, flat.left.map(normFlat), s"${s.label}, $language")
        flat
      }
    }
    assertEquals(outcomes.size, 2 * RefusalCaseCount)
    assert(outcomes.exists(_.isRight))
  }
