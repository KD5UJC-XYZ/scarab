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
import xyz.kd5ujc.scarab.node.ntc.FlatRecordings
import xyz.kd5ujc.scarab.node.tools.PlutusCases
import xyz.kd5ujc.scarab.plutus.PlutusLanguage
import xyz.kd5ujc.scarab.protocol.StateLayout

/** The case files `--plutus-cases` writes of a Conway transaction, from the era's observation
  * (`ConwayPhase2.Observation`, `PlutusCases.observeConway`, #85), are those the flat observation
  * of the same transaction writes (`Phase2.Observation`, `PlutusCases.observe`), byte for byte, on
  * every recorded Plutus case Conway's decoder reads (`fixtures/preview/plutus`, 40 of its 45):
  * with their scripts evaluated, and with their collection failed, each failure the era's
  * collection names given as the flat collection names it (`ConwayPhase2.storedErrors`) from the
  * flat transaction read on its own. The era observation's case files are also held to their bytes
  * as recorded before the flat Conway UTXO, UTXOW and UTXOS were deleted
  * (`fixtures/conway-flat-refusals/plutus-case-files.jsonl`, #85's item 7152417866), so that the
  * failures' text the case files carry stays the flat collection's.
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

  /** The failures of a collection of `r` 's scripts this suite names, each from what the
    * transaction holds, beside the flat failure of the same, read from the flat transaction: every
    * context error, a certificate's and a pointer's where the transaction has them, and the votes'
    * and proposals'.
    */
  private def uncollected(
      r: Read
  ): (NonEmptyList[EraCollectError[Conway]], NonEmptyVector[CollectError]) =
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
    (errors, expected)

  test(
    "every recorded Conway case, not collected: the era's failures named as the flat collection's, and its case file the flat one's"
  ) {
    val (era, flat) = read.map { r =>
      val (errors, expected) = uncollected(r)
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

  /** The failures of a collection of `r` 's scripts that name each purpose its scripts need: no
    * redeemer for it, and no context for it, in the order of the purposes; none where its scripts
    * need none.
    */
  private def purposeErrors(r: Read): Option[NonEmptyList[EraCollectError[Conway]]] =
    val u = summon[AlonzoEraUTxO[Conway]]
    val toAsItem = ConwayPlutusPurpose.hoistPlutusPurpose[AsIxItem, AsItem](
      [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsItem(a)
    )
    val utxo: UTxO[Conway] =
      ConwayUTxO
        .fromStored(r.ls.utxo.utxo.utxo.toMap)
        .fold(e => fail(s"${r.c.name}: $e"), identity)
    val needed = u.getScriptsNeeded[TxLevel.Top](utxo, t.body[TxLevel.Top](r.tx))(using
      u.txOf.bodyOf.top
    )
    val purposes = needed.unAlonzoScriptsNeeded.map((p, _) => toAsItem(p))
    NonEmptyList.fromList(
      purposes.toList.flatMap(p =>
        List(
          EraCollectError.NoRedeemer[Conway](p),
          EraCollectError.BadTranslation[Conway](ConwayContextError.PlutusPurposeNotSupported(p))
        )
      )
    )

  test(
    "every recorded Conway case: a purpose the era's collection names is given the flat purpose the flat rules need"
  ) {
    read.foreach { r =>
      val era = ConwayPhase2.observation(
        r.tx,
        r.ls,
        r.c.slot,
        None,
        Right(Phase2.Outcome.Passed(Phase2.Report(Vector.empty)))
      )
      val errors = purposeErrors(r)
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

  /** The era observations' case files of every recorded case ([[cases]]), each set by its name:
    * with the scripts evaluated (`evaluated/`), with the collection failed by every failure of
    * [[uncollected]] (`uncollected/`), and by those of [[purposeErrors]] (`purposes/`), each case
    * file by `<set>/<its name>`.
    */
  private lazy val eraCaseFiles: Map[String, String] =
    def set(name: String, outcomes: Vector[(Read, ConwayPhase2.Observation)]) =
      cases(outcomes.map(_._2))(_.observeConway).map((file, text) => s"$name/$file" -> text)
    def observed(r: Read, outcome: Either[NonEmptyList[EraCollectError[Conway]], Phase2.Outcome]) =
      r -> ConwayPhase2.observation(r.tx, r.ls, r.c.slot, Some(clock), outcome)
    val passed = Phase2.Outcome.Passed(Phase2.Report(Vector.empty))
    set("evaluated", read.map(observed(_, Right(passed)))) ++
      set("uncollected", read.map(r => observed(r, Left(uncollected(r)._1)))) ++
      set("purposes", read.flatMap(r => purposeErrors(r).map(es => observed(r, Left(es)))))

  test("the era observations' case files are the recorded bytes, every one, each once") {
    // the case files' bytes, recorded before the flat Conway UTXO, UTXOW and UTXOS were deleted,
    // when the flat observation's equalled them (#85's item 7152417866): the failures' text is
    // the flat collection's, through `ConwayPhase2.storedErrors`
    val recorded = FlatRecordings.plutusCaseFiles
    assertEquals(
      eraCaseFiles.view.mapValues(text => FlatRecordings.digest(text.getBytes("UTF-8"))).toMap,
      recorded
    )
    assertEquals(recorded.size, 3 * read.size - read.count(r => purposeErrors(r).isEmpty))
  }

  test(
    "the pinned case files name certificates, purposes of each kind needed, votes and proposals"
  ):
    // the text `ConwayTxCert.toStored` and `ConwayUtxowPredFailure.storedPurposes` give, which the
    // pin above would otherwise hold of no case
    val texts = eraCaseFiles.toVector
    def named(set: String, what: String) =
      texts.count((file, text) => file.startsWith(s"$set/") && text.contains(what))
    Vector(
      "uncollected" -> "; certificate ",
      "uncollected" -> "voting procedures in a Plutus V1 or V2 script context",
      "uncollected" -> "proposal procedures in a Plutus V1 or V2 script context",
      "purposes" -> "translation no redeemer for Spending(",
      "purposes" -> "; purpose Spending(",
      "purposes" -> "Minting(",
      "purposes" -> "Certifying(",
      "purposes" -> "Rewarding(",
      "purposes" -> "Voting(",
      "purposes" -> "Proposing("
    ).foreach((set, what) => assert(named(set, what) > 0, s"no case file of $set names $what"))
