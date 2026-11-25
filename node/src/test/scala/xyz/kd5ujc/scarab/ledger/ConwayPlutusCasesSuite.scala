// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.data.{NonEmptyList, NonEmptyVector}
import cats.syntax.all.*
import java.nio.file.Files
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoContextError,
  AlonzoEraUTxO,
  AsItem,
  AsIxItem,
  BabbageContextError,
  CollectError as EraCollectError,
  EraTx,
  EraTxBody,
  TxLevel,
  UTxO
}
import xyz.kd5ujc.scarab.ledger.era.conway.{
  ConwayContextError,
  ConwayPlutusPurpose,
  ConwayTx,
  ConwayUTxO
}
import xyz.kd5ujc.scarab.ledger.era.rules.ConwayPhase2
import xyz.kd5ujc.scarab.ledger.state.{Conway, ShelleyBasedEra}
import xyz.kd5ujc.scarab.node.tools.PlutusCases
import xyz.kd5ujc.scarab.plutus.PlutusLanguage
import xyz.kd5ujc.scarab.protocol.StateLayout

/** The case files `--plutus-cases` writes of a Conway transaction, from the era's observation
  * (`ConwayPhase2.Observation`, `PlutusCases.observeConway`, #85), are those the flat observation
  * of the same transaction writes (`Phase2.Observation`, `PlutusCases.observe`), byte for byte, on
  * every recorded Plutus case Conway's decoder reads (`fixtures/preview/plutus`, 40 of its 45):
  * with their scripts evaluated, and with their collection failed, each failure the era's
  * collection names given as the flat collection names it (`ConwayPhase2.storedErrors`) from the
  * flat transaction read on its own.
  */
class ConwayPlutusCasesSuite extends FunSuite:
  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
  private val t = EraTx.conway
  private val b = EraTxBody.conway
  private given t.bodyOf.Level[TxLevel.Top] = t.bodyOf.top

  /** A recorded case read by Conway's decoder, judged in a ledger holding its outputs. */
  private final case class Read(
      c: PlutusCase,
      tx: era.Tx[TxLevel.Top, Conway],
      ls: state.LedgerState[Conway]
  )

  private lazy val read: Vector[Read] = PlutusCase.all.flatMap { c =>
    val witnesses = c.tx.witnesses.raw.getOrElse(fail(s"${c.name}: no witness bytes"))
    ConwayTx
      .decode(
        Block.Transaction(c.tx.body.raw, witnesses, c.tx.auxiliary, c.tx.isValid),
        StateLayout.Node11_1
      )
      .toOption
      .map { tx =>
        val ls = state.Build
          .funded(
            state.Build.nes[Conway]((c.slot / PreviewConwayWorld.epochLength).toLong, c.params),
            c.spent ++ c.references ++ c.collateral
          )
          .es
          .ls
        Read(c, tx, ls)
      }
  }

  /** The case files of `observations`, by name, written through `record`, each kind kept. */
  private def cases[O](observations: Vector[O])(
      record: PlutusCases => O => Unit
  ): Map[String, String] =
    val dir = Files.createTempDirectory("conway-plutus-cases")
    try
      val recorder = PlutusCases(dir, perKind = Int.MaxValue, max = Int.MaxValue)
      observations.foreach(record(recorder))
      assertEquals(recorder.failed(), Vector.empty)
      Files
        .list(dir)
        .iterator()
        .asScala
        .map(f => f.getFileName.toString -> Files.readString(f))
        .toMap
    finally
      Files.list(dir).iterator().asScala.foreach(Files.delete)
      Files.delete(dir)

  private val clock: PosixClock = PosixClock.Total(slot => slot * 1000)

  /** The era observation of the case `r` whose scripts came to `outcome`, and the flat one of the
    * same transaction, read flat, with the same outputs, whose scripts came to `flat`.
    */
  private def both(
      r: Read,
      outcome: Either[NonEmptyList[EraCollectError[Conway]], Phase2.Outcome],
      flat: Phase2.Collected[Phase2.Outcome]
  ): (ConwayPhase2.Observation, Phase2.Observation) =
    val era = ConwayPhase2.observation(r.tx, r.ls, r.c.slot, Some(clock), outcome)
    assertEquals(era.params, r.c.params, r.c.name)
    (
      era,
      Phase2.Observation(r.c.tx, era.params, era.slot, era.spent, era.references, era.clock, flat)
    )

  test("the 40 recorded Conway cases are read by Conway's decoder") {
    assertEquals(read.size, 40)
  }

  test("every recorded Conway case, evaluated: the era observation's case file is the flat one's") {
    val outcome = Phase2.Outcome.Passed(Phase2.Report(Vector.empty))
    val (era, flat) = read.map(both(_, Right(outcome), Right(outcome))).unzip
    val written = cases(era)(_.observeConway)
    assertEquals(written.size, read.size)
    assertEquals(written, cases(flat)(_.observe))
  }

  test(
    "every recorded Conway case, not collected: the era's failures named as the flat collection's, and its case file the flat one's"
  ) {
    val (era, flat) = read.map { r =>
      val body = t.body[TxLevel.Top](r.tx)
      val tx = r.c.tx
      // the era's failures, each from what the transaction holds, beside the flat failure of the
      // same, read from the flat transaction
      val certs = (b.certs[TxLevel.Top](body).headOption, tx.body.certificates.headOption)
        .mapN((cert, flat) =>
          EraCollectError.BadTranslation[Conway](
            ConwayContextError.CertificateNotSupported(cert)
          ) -> CollectError.BadTranslation(TranslationError.CertificateNotSupported(flat))
        )
      val votes = Option
        .when(tx.body.votes.nonEmpty)(
          EraCollectError.BadTranslation[Conway](
            ConwayContextError.VotingProceduresFieldNotSupported(
              b.votingProcedures[TxLevel.Top](body)
            )
          ) -> CollectError.BadTranslation(
            TranslationError.VotingProceduresFieldNotSupported(tx.body.votes)
          )
        )
      val proposals = Option
        .when(tx.body.proposals.nonEmpty)(
          EraCollectError.BadTranslation[Conway](
            ConwayContextError.ProposalProceduresFieldNotSupported(
              b.proposalProcedures[TxLevel.Top](body)
            )
          ) -> CollectError.BadTranslation(
            TranslationError.ProposalProceduresFieldNotSupported(tx.body.proposals)
          )
        )
      def babbage(error: BabbageContextError[Conway]) =
        EraCollectError.BadTranslation[Conway](ConwayContextError.BabbageContextError(error))
      def alonzo(error: AlonzoContextError[Conway]) =
        babbage(BabbageContextError.AlonzoContextError(error))
      val input = tx.body.inputs.min
      val pointer = t.witsOf.rdmrsTxWits(t.wits[TxLevel.Top](r.tx)).entries.keys.headOption
      val flatPointer = tx.witnesses.redeemers.map(r => (r.tag, r.index)).minOption
      val pairs = Vector(
        EraCollectError.NoCostModel[Conway](PlutusLanguage.V2) ->
          CollectError.NoCostModel(PlutusLanguage.V2),
        EraCollectError.NoWitness[Conway](tx.body.id) -> CollectError.NoWitness(tx.body.id),
        alonzo(AlonzoContextError.TranslationLogicMissingInput(input)) ->
          CollectError.BadTranslation(TranslationError.UnresolvedInput(input)),
        alonzo(AlonzoContextError.TimeTranslationPastHorizon(TranslationError.NoClockSample(9))) ->
          CollectError.BadTranslation(TranslationError.NoClockSample(9)),
        babbage(BabbageContextError.InlineDatumsNotSupported(TxOutSource.TxOutFromOutput(0))) ->
          CollectError.BadTranslation(
            TranslationError.InlineDatumsNotSupported(TxOutSource.TxOutFromOutput(0))
          ),
        babbage(
          BabbageContextError.ReferenceScriptsNotSupported(TxOutSource.TxOutFromInput(input))
        ) ->
          CollectError.BadTranslation(
            TranslationError.ReferenceScriptsNotSupported(TxOutSource.TxOutFromInput(input))
          ),
        babbage(BabbageContextError.ReferenceInputsNotSupported(tx.body.inputs.toSet)) ->
          CollectError.BadTranslation(
            TranslationError.ReferenceInputsNotSupported(tx.body.inputs.toSet)
          ),
        babbage(BabbageContextError.ByronTxOutInContext(TxOutSource.TxOutFromOutput(1))) ->
          CollectError.BadTranslation(
            TranslationError.ByronTxOutInContext(TxOutSource.TxOutFromOutput(1))
          ),
        EraCollectError.BadTranslation[Conway](
          ConwayContextError.ReferenceInputsNotDisjointFromInputs(NonEmptyVector.one(input))
        ) -> CollectError.BadTranslation(
          TranslationError.ReferenceInputsNotDisjointFromInputs(NonEmptyVector.one(input))
        ),
        EraCollectError.BadTranslation[Conway](
          ConwayContextError.TreasuryDonationFieldNotSupported(state.Coin(7))
        ) -> CollectError.BadTranslation(TranslationError.TreasuryDonationFieldNotSupported(7)),
        EraCollectError.BadTranslation[Conway](
          ConwayContextError.CurrentTreasuryFieldNotSupported(state.Coin(8))
        ) -> CollectError.BadTranslation(TranslationError.CurrentTreasuryFieldNotSupported(8))
      ) ++ (pointer, flatPointer).mapN((p, f) =>
        babbage(BabbageContextError.RedeemerPointerPointsToNothing(p)) ->
          CollectError.BadTranslation(TranslationError.RedeemerPointerPointsToNothing(f._1, f._2))
      ) ++ certs ++ votes ++ proposals
      val errors = NonEmptyList.fromList(pairs.map(_._1).toList).getOrElse(fail("no failure"))
      val expected = NonEmptyVector.fromVector(pairs.map(_._2)).getOrElse(fail("no failure"))
      val (era, flat) = both(r, Left(errors), Left(expected))
      assertEquals(
        ConwayPhase2.storedErrors(era, errors).toList.map(_.toOption),
        expected.toList.map(Option(_)),
        r.c.name
      )
      (era, flat)
    }.unzip
    val written = cases(era)(_.observeConway)
    assertEquals(written.size, read.size)
    assertEquals(written, cases(flat)(_.observe))
  }

  test(
    "every recorded Conway case: a purpose the era's collection names is given the flat purpose the flat rules need"
  ) {
    val u = summon[AlonzoEraUTxO[Conway]]
    val toAsItem = ConwayPlutusPurpose.hoistPlutusPurpose[AsIxItem, AsItem](
      [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsItem(a)
    )
    read.foreach { r =>
      val utxo: UTxO[Conway] =
        ConwayUTxO
          .fromStored(r.ls.utxo.utxo.utxo.toMap)
          .fold(e => fail(s"${r.c.name}: $e"), identity)
      val needed = u.getScriptsNeeded[TxLevel.Top](utxo, t.body[TxLevel.Top](r.tx))(using
        u.txOf.bodyOf.top
      )
      val purposes = needed.unAlonzoScriptsNeeded.map((p, _) => toAsItem(p))
      val era = ConwayPhase2.observation(
        r.tx,
        r.ls,
        r.c.slot,
        None,
        Right(Phase2.Outcome.Passed(Phase2.Report(Vector.empty)))
      )
      val errors = NonEmptyList.fromList(
        purposes.toList.flatMap(p =>
          List(
            EraCollectError.NoRedeemer[Conway](p),
            EraCollectError.BadTranslation[Conway](ConwayContextError.PlutusPurposeNotSupported(p))
          )
        )
      )
      val flatNeeded = TxChecks.scriptsNeeded[Conway](r.c.tx.body, r.c.spent).map(_._1)
      val stored = errors.fold(Vector.empty)(ConwayPhase2.storedErrors(era, _).toList.toVector)
      assertEquals(
        stored.collect { case Right(CollectError.NoRedeemer(p)) => p }.toSet,
        flatNeeded.toSet,
        r.c.name
      )
      assertEquals(
        stored.collect {
          case Right(CollectError.BadTranslation(TranslationError.PlutusPurposeNotSupported(p))) =>
            p
        }.toSet,
        flatNeeded.toSet,
        r.c.name
      )
      assertEquals(stored.count(_.isLeft), 0, r.c.name)
    }
  }
