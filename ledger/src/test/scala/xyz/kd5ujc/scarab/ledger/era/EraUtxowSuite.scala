// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.protocol.StateLayout
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Block,
  PredicateFailure,
  ProtocolParams,
  ScriptKind,
  TestLedger,
  Tx as FlatTx,
  TxChecks,
  Verdicts,
  ScriptIntegrity as FlatScriptIntegrity
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx}
import xyz.kd5ujc.scarab.ledger.era.rules.{AlonzoUtxow, BabbageUtxow, ShelleyUtxow}
import xyz.kd5ujc.scarab.ledger.rules.Test
import xyz.kd5ujc.scarab.ledger.state.{Conway, ShelleyBasedEra}
import xyz.kd5ujc.scarab.plutus.PlutusLanguage
import xyz.kd5ujc.scarab.protocol.Era

/** The UTXOW predicates on the era types (`era/rules/Utxow.scala`) and the script integrity they
  * check (`era/ScriptIntegrity.scala`), against the flat ones on every recorded Conway transaction:
  * the predicates that need only the transaction, and the integrity's bytes for every set of
  * languages. The recorded blocks have no UTxO set, so the scripts a transaction needs are not
  * known here; `ConwayUtxowChecksSuite` (node) compares the verdicts on the refusal corpus, with
  * its UTxO sets.
  */
class EraUtxowSuite extends munit.FunSuite:
  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway

  private val tx = EraTx.conway
  private val top: tx.bodyOf.Level[TxLevel.Top] = tx.bodyOf.top

  private lazy val transactions: Vector[Block.Transaction] =
    EraCorpus
      .shelleyBlocks(
        Vector(
          "fixtures/eras/blocks.hex",
          "fixtures/preview/conway.hex",
          "fixtures/preview/conway-governance.hex"
        )
      )
      .filter(_.era == Era.Conway)
      .flatMap(_.transactions)

  private def both(t: Block.Transaction): (FlatTx, ConwayTx) =
    (
      FlatTx.fromBlock(t, Era.Conway).toOption.get,
      ConwayTx.decode(t, StateLayout.Node11_1).toOption.get
    )

  /** A purpose's tag, index and script, as the flat path names it and as `getScriptsNeeded` does.
    */
  private def flatNeeded(f: FlatTx): Vector[(Int, BigInt, Bytes)] =
    xyz.kd5ujc.scarab.ledger.Purpose
      .collection(f.body, Map.empty, ShelleyBasedEra.SConway)
      .flatMap(p => p.scriptHash.map(h => (p.tag, BigInt(p.index), h)))

  private def typedNeeded(c: ConwayTx): Vector[(Int, BigInt, Bytes)] =
    val u = EraUTxO.conway
    u.getScriptsNeeded[TxLevel.Top](UTxO[Conway](Map.empty), tx.body[TxLevel.Top](c)(using top))(
      using u.txOf.bodyOf.top
    ).unAlonzoScriptsNeeded
      .map { (purpose, hash) =>
        import xyz.kd5ujc.scarab.ledger.era.conway.ConwayPlutusPurpose.*
        val (tag, ix) = purpose match
          case ConwaySpending(a)    => (0, a.ix.value)
          case ConwayMinting(a)     => (1, a.ix.value)
          case ConwayCertifying(a)  => (2, a.ix.value)
          case ConwayWithdrawing(a) => (3, a.ix.value)
          case ConwayVoting(a)      => (4, a.ix.value)
          case ConwayProposing(a)   => (5, a.ix.value)
        (tag, ix, hash)
      }

  test("getScriptsNeeded is the flat path's on every recorded Conway transaction, inputs aside"):
    val needs = transactions.map { t =>
      val (f, c) = both(t)
      val typed = typedNeeded(c)
      assertEquals(typed, flatNeeded(f))
      typed
    }
    // the inputs, policies and certificates in an order a mistake would change are crafted
    // (`ConwayUtxowChecksSuite`), as no recorded transaction has them
    val patterns: Vector[(String, Vector[(Int, BigInt, Bytes)] => Boolean)] = Vector(
      "a withdrawal from a script's account" -> (_.exists(_._1 == 3)),
      "a script voter" -> (_.exists(_._1 == 4))
    )
    assertEquals(patterns.collect { case (what, p) if !needs.exists(p) => what }, Vector.empty)

  private def params(major: Int): (ProtocolParams, PParams[Conway]) =
    val flat = TestLedger.params(major)
    (flat, PParams[Conway](ConwayPParams.fromStored(flat).toOption.get))

  private val kinds: Map[PlutusLanguage, ScriptKind] = Map(
    PlutusLanguage.V1 -> ScriptKind.PlutusV1,
    PlutusLanguage.V2 -> ScriptKind.PlutusV2,
    PlutusLanguage.V3 -> ScriptKind.PlutusV3
  )

  /** Every set of Conway's languages. */
  private val languageSets: Vector[Set[PlutusLanguage]] =
    kinds.keySet.subsets().toVector

  private def integrity(c: ConwayTx, pp: PParams[Conway], langs: Set[PlutusLanguage]) =
    ScriptIntegrity.mkScriptIntegrity[Conway, TxLevel.Top](pp, c, langs)(using
      tx,
      summon[AlonzoEraPParams[Conway]],
      tx.witsOf,
      top
    )

  /** A UTXOW predicate's verdict in the node's flat form, through Conway's injection `inject`. */
  private def flatOf[A](test: xyz.kd5ujc.scarab.ledger.rules.Test[A])(
      inject: A => xyz.kd5ujc.scarab.ledger.era.rules.ConwayUtxowPredFailure[Conway]
  ): xyz.kd5ujc.scarab.ledger.rules.Test[PredicateFailure] =
    EraUtxoRun.utxowFlat(test)(inject).getOrElse(fail(s"no flat form: $test"))

  test("the corpus has Conway transactions, some with redeemers and some with datums") {
    val flats = transactions.map(both(_)._1)
    assert(flats.exists(_.witnesses.redeemers.nonEmpty))
    assert(flats.exists(_.witnesses.datums.nonEmpty))
  }

  test("validateVerifiedWits gives the flat check's verdict on every recorded Conway transaction") {
    transactions.foreach { t =>
      val (flat, c) = both(t)
      assertEquals(
        ShelleyUtxow
          .validateVerifiedWits[Conway, TxLevel.Top](c, Verdicts.Direct)(using tx, top)
          .map(test => flatOf(test)(EraUtxoRun.shelleyW)),
        TxChecks.verifiedWits(flat, Verdicts.Direct),
        flat.body.id.hex
      )
    }
  }

  test("the script integrity is the flat one's, its bytes and hash, for every set of languages") {
    for
      t <- transactions
      major <- Vector(9, 10, 11)
      langs <- languageSets
    do
      val (flat, c) = both(t)
      val (flatPp, pp) = params(major)
      val kindsOf = langs.map(kinds)
      val si = integrity(c, pp, langs)
      assertEquals(
        si.map(ScriptIntegrity.originalBytes(_)),
        FlatScriptIntegrity.preimage[Conway](flat, flatPp, kindsOf)
      )
      assertEquals(
        si.map(ScriptIntegrity.hashScriptIntegrity(_)),
        FlatScriptIntegrity.hash[Conway](flat, flatPp, kindsOf)
      )
      // the verdict: the flat check's, which compares the same hash and reports the same mismatch
      assertEquals(
        flatOf(
          AlonzoUtxow.checkScriptIntegrityHash[Conway, TxLevel.Top](c, pp, si)(using
            tx,
            summon[EraPParams[Conway]],
            summon[xyz.kd5ujc.scarab.ledger.era.Era[Conway]],
            top
          )
        )(EraUtxoRun.alonzoW),
        Test.failureUnless(
          FlatScriptIntegrity.hash[Conway](flat, flatPp, kindsOf) == flat.body.scriptDataHash,
          PredicateFailure.utxow(FlatScriptIntegrity.mismatch[Conway](flat, flatPp, kindsOf))
        ),
        s"${flat.body.id.hex} at $major with $langs"
      )
  }

  test("a language view is the flat one's, with and without a cost model") {
    val (flatPp, pp) = params(9)
    // the parameters have cost models for V1 and V2 only, so V3's view has `null`
    languageSets.foreach { langs =>
      assertEquals(
        LangDepView.encodeLangViews(langs.map(LangDepView.getLanguageView(pp, _))),
        Bytes(FlatScriptIntegrity.plutusLanguageViews(flatPp, langs)),
        langs.toString
      )
    }
  }

  test("PlutusV1's view keeps the double-bagged tag and the indefinite list") {
    val (_, pp) = params(9)
    val v1 = LangDepView.getLanguageView(pp, PlutusLanguage.V1)
    assertEquals(v1.tag, Bytes.fromHex("4100").toOption.get)
    // bytes(9f 01 02 03 ff): the model `[1, 2, 3]` as an indefinite list, wrapped as bytes
    assertEquals(v1.params, Bytes.fromHex("459f010203ff").toOption.get)
    val v3 = LangDepView.getLanguageView(pp, PlutusLanguage.V3)
    assertEquals(
      v3,
      LangDepView(Bytes.fromHex("02").toOption.get, Bytes.fromHex("f6").toOption.get)
    )
  }

  test("a transaction with no redeemers, no datums and no language has no script integrity") {
    val (_, pp) = params(9)
    val plain = transactions
      .map(both)
      .find((flat, _) => flat.witnesses.redeemers.isEmpty && flat.witnesses.datums.isEmpty)
    val (_, c) = plain.getOrElse(fail("no recorded transaction without redeemers and datums"))
    assertEquals(integrity(c, pp, Set.empty), None)
    assert(integrity(c, pp, Set(PlutusLanguage.V2)).nonEmpty)
  }

  test("babbageMissingScripts: the extraneous, then the missing, beside the reference scripts") {
    val (_, pp) = params(9)
    def h(b: Int) = Bytes(Vector.fill(28)(b.toByte))
    val result = flatOf(
      BabbageUtxow.babbageMissingScripts[Conway](
        pp,
        sNeeded = Set(h(1), h(2), h(3)),
        sRefs = Set(h(2)),
        sReceived = Set(h(1), h(4))
      )
    )(EraUtxoRun.shelleyW)
    assertEquals(
      Test.failures(result).map(_.toNonEmptyVector.toVector),
      Some(
        Vector(
          PredicateFailure.utxow(
            xyz.kd5ujc.scarab.ledger.UtxowFailure.ExtraneousScriptWitnesses(Set(h(4)))
          ),
          PredicateFailure.utxow(
            xyz.kd5ujc.scarab.ledger.UtxowFailure.MissingScriptWitnesses(Set(h(3)))
          )
        )
      )
    )
    assertEquals(
      flatOf(BabbageUtxow.babbageMissingScripts[Conway](pp, Set(h(1), h(2)), Set(h(2)), Set(h(1))))(
        EraUtxoRun.shelleyW
      ),
      Test.pass[PredicateFailure]
    )
  }

  test("each era's protocol versions are the ones the flat decoders read it at") {
    import xyz.kd5ujc.scarab.ledger.DecoderVersion
    import xyz.kd5ujc.scarab.ledger.era.Era as EraVersions
    def versions[E <: xyz.kd5ujc.scarab.ledger.state.EraTag](using e: EraVersions[E]) =
      DecoderVersion.Range(e.eraProtVerLow.value, e.eraProtVerHigh.value)
    assertEquals(
      versions[xyz.kd5ujc.scarab.ledger.state.Allegra],
      DecoderVersion.range(Era.Allegra)
    )
    assertEquals(versions[xyz.kd5ujc.scarab.ledger.state.Mary], DecoderVersion.range(Era.Mary))
    assertEquals(
      versions[xyz.kd5ujc.scarab.ledger.state.Babbage],
      DecoderVersion.range(Era.Babbage)
    )
    assertEquals(versions[Conway], DecoderVersion.range(Era.Conway))
    assertEquals(
      versions[xyz.kd5ujc.scarab.ledger.state.Dijkstra],
      DecoderVersion.range(Era.Dijkstra)
    )
  }

  private def hex(s: String): Bytes = Bytes.fromHex(s).toOption.get

  /** Conway's parameters with the cost models `models`, by language tag. */
  private def withModels(models: Map[Int, Vector[BigInt]]): PParams[Conway] =
    PParams[Conway](
      ConwayPParams.fromStored(TestLedger.params(9).copy(costModels = models)).toOption.get
    )

  test("each language's view, with and without its cost model, written out (getLanguageView)") {
    val all =
      withModels(Map(0 -> Vector(1, 2, 3), 1 -> Vector(4, 5, 6, 7), 2 -> Vector(8, -1, 300)))
    val none = withModels(Map.empty)
    def view(pp: PParams[Conway], l: PlutusLanguage) = LangDepView.getLanguageView(pp, l)
    // PlutusV1: the tag serialised twice (`41 00`), the model an indefinite list serialised as bytes
    assertEquals(view(all, PlutusLanguage.V1), LangDepView(hex("4100"), hex("459f010203ff")))
    // a missing model is `null`, serialised as bytes for PlutusV1 (`Alonzo/PParams.hs:571-584`,
    // cardano-ledger f649f975)
    assertEquals(view(none, PlutusLanguage.V1), LangDepView(hex("4100"), hex("41f6")))
    // PlutusV2 and V3: the tag serialised once, the model a definite list
    assertEquals(view(all, PlutusLanguage.V2), LangDepView(hex("01"), hex("8404050607")))
    assertEquals(view(none, PlutusLanguage.V2), LangDepView(hex("01"), hex("f6")))
    assertEquals(view(all, PlutusLanguage.V3), LangDepView(hex("02"), hex("83082019012c")))
    assertEquals(view(none, PlutusLanguage.V3), LangDepView(hex("02"), hex("f6")))
    // the three in one map, in the short-lex order of their tags: V2, V3, then V1's two-byte tag
    assertEquals(
      LangDepView.encodeLangViews(
        Set(PlutusLanguage.V1, PlutusLanguage.V2, PlutusLanguage.V3).map(view(all, _))
      ),
      hex("a3" + "01" + "8404050607" + "02" + "83082019012c" + "4100" + "459f010203ff")
    )
    assertEquals(LangDepView.encodeLangViews(Set.empty), hex("a0"))
  }

  test("views of one tag are encoded in their derived order, whatever order the set was built in") {
    // `Set.toList` of the views in the derived (tag, parameters) order, then the stable sort by tag
    // (`Alonzo/PParams.hs:547-548, 587-597`, cardano-ledger f649f975)
    val a = LangDepView(hex("01"), hex("8102"))
    val b = LangDepView(hex("01"), hex("8101"))
    val expected = hex("a2" + "01" + "8101" + "01" + "8102")
    assertEquals(LangDepView.encodeLangViews(Set(a, b)), expected)
    assertEquals(LangDepView.encodeLangViews(Set(b, a)), expected)
    // a shorter tag first, whatever its bytes; and a shorter parameter first among equal tags
    assertEquals(
      LangDepView.encodeLangViews(
        Set(LangDepView(hex("4100"), hex("00")), LangDepView(hex("ff"), hex("00")))
      ),
      hex("a2" + "ff00" + "410000")
    )
    assertEquals(
      LangDepView.encodeLangViews(
        Set(LangDepView(hex("01"), hex("8101")), LangDepView(hex("01"), hex("81")))
      ),
      hex("a2" + "0181" + "018101")
    )
  }
