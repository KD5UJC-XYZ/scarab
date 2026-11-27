// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.data.{NonEmptyList, NonEmptyMap, NonEmptySet}
import java.time.Instant
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Value as CborValue}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxIn
import xyz.kd5ujc.scarab.ledger.{Horizon, TxEnv, TxIn, Unsigned, Utxo, Verdicts}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus.*
import xyz.kd5ujc.scarab.ledger.era.rules.*
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  Credential,
  DeltaCoin,
  Dijkstra,
  EpochNo,
  Guard,
  KeyHash,
  Network,
  UtxoTables
}
import xyz.kd5ujc.scarab.plutus.{ExUnits, PlutusLanguage, ScarabEvaluator}

/** Dijkstra's UTXO and UTXOW on the era types (`DijkstraUtxo`, `DijkstraUtxow`, cardano-ledger
  * 9df9f98c), against what the reference recorded where the flat rules cannot judge:
  *   - every transaction of the public Leios testnet's chain without sub-transactions that the
  *     fixture holds (`fixtures/reference/leios-testnet-chain-contexts`, recorded with the outputs
  *     it spends; the testnet's ledger accepted each): UTXOW whole, its Plutus scripts evaluated by
  *     the node's evaluator, admits each, from the testnet's parameters and cost models, with no
  *     certificate state, at the slot of the ranking block that holds it; of an endorser block's
  *     closure, 90 of the 94 at the certifying ranking block's slot, the other four, whose validity
  *     ends before it, only at the endorser block's;
  *   - the 22 context refusals (`fixtures/reference/leios-testnet-context-refusals`): UTXOS records
  *     the batch's collection errors as `CollectErrors`, and evaluates nothing under the policy
  *     that skips the phase-two checks;
  *   - the injections of the earlier eras' failures, which the reference leaves partial (`error
  *     "Impossible"`, `Dijkstra/Rules/Utxo.hs:572-573`): on every constructor Dijkstra's UTXO
  *     reports, each equals the reference's composition through Conway's failure
  *     (`conwayToDijkstraUtxoPredFailure . alonzoToConwayUtxoPredFailure` and the others), and the
  *     two constructors the reference calls impossible have no injection: it does not compile;
  *   - the top level under `NonStatic`, on transactions built as `DijkstraImpBatch` builds them:
  *     the four checks the reference runs on the signal (`runTestOnSignal`,
  *     `Dijkstra/Rules/Utxow.hs:294, 301, 305, 318`, cardano-ledger 9df9f98c) skipped, the others
  *     run, and a transaction whose one fault is its signature admitted.
  *
  * Incomplete (CNF-2), not run here:
  *   - the 28 batches of the chain as the reference judges them: UTXOW reads the state after
  *     SUBLEDGERS (the threaded UTxO set and the pool state), the sub-transactions' rules (D7c);
  *     here UTXOW admits each against the set before it, a component check;
  *   - the forecast of a validity interval's end: the era history given ends far after the chain,
  *     so `OutsideForecast` is not exercised, and no recording holds a refusal of it: the reference
  *     accepts the recorded ends past the horizon (`PastHorizonReferenceSuite`, Alonzo to Conway)
  *     or refuses them in collection (`TimeTranslationPastHorizon`), its forecast predicate
  *     passing;
  *   - a refusal by the reference of a guard (`MissingRequiredGuards`, `MalformedGuardDatums`; the
  *     rules' own on a built transaction are above), of the legacy mode's value
  *     (`ValueNotConservedInLegacyMode`), of a pointer in the collateral return, of an input a
  *     sub-transaction spent, and of a batch's collateral: no recording holds one (#85,
  *     https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7056846371).
  */
class DijkstraUtxowSuite extends FunSuite:
  private given BothLevels[TxLevel.Top] = BothLevels.Top
  private given cats.Order[TxIn] = cats.Order.fromOrdering
  private given cats.Order[xyz.kd5ujc.scarab.ledger.Address] =
    cats.Order.by[xyz.kd5ujc.scarab.ledger.Address, xyz.kd5ujc.scarab.codec.Bytes](_.raw)(using
      cats.Order.fromOrdering
    )
  private val bodies = EraTxBody.dijkstra

  private val fixture = json("fixtures/reference/leios-testnet-chain-contexts/cases.json")

  /** The testnet's parameters as its genesis files give them (`ProtocolParams.fromGenesis` of its
    * Shelley, Alonzo and Conway genesis, then Dijkstra's), which no governance action changed but
    * for the endorser blocks' limits.
    */
  private lazy val stored: xyz.kd5ujc.scarab.ledger.ProtocolParams =
    def genesis(era: String) = json(s"fixtures/leios/testnet/config/$era-genesis.json")
    xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraFixture.genesis.upgrade(
      xyz.kd5ujc.scarab.ledger.ProtocolParams
        .fromGenesis(genesis("shelley"), genesis("alonzo"), Some(genesis("conway")))
        .fold(e => fail(e.toString), identity)
    )

  /** The testnet's parameters with the cost models the fixture records in force on the chain. */
  private lazy val pp: PParams[Dijkstra] =
    val p = EraPParams.dijkstra
    val languages = PlutusLanguage.values.map(l => s"Plutus$l" -> l).toMap
    val models = fixture.hcursor
      .get[Map[String, Vector[BigInt]]]("costModels")
      .fold(e => fail(e.message), identity)
      .map((name, values) => languages.getOrElse(name, fail(name)).id -> values)
    PParams
      .lensHKD[Dijkstra]
      .andThen(p.hkdCostModelsL[Identity])
      .replace(CostModels.from(models).fold(e => fail(e.toString), identity))(era(stored))

  private lazy val systemStart: BigInt =
    BigInt(Instant.parse(str(fixture, "systemStart")).getEpochSecond)

  private lazy val slotLength: BigInt =
    fixture.hcursor.get[BigInt]("slotLength").fold(e => fail(e.message), identity)

  /** The slot to POSIX milliseconds of the fixture's epoch information, as the reference's harness
    * fixes it (`fixedEpochInfo`).
    */
  private def clock(slot: BigInt): Either[xyz.kd5ujc.scarab.ledger.TranslationError, BigInt] =
    Right((systemStart + slot * slotLength) * 1000)

  /** An era history that ends far after the chain (the forecast is not exercised, above). */
  private lazy val history: Horizon =
    val epochSize = fixture.hcursor.get[BigInt]("epochSize").fold(e => fail(e.message), identity)
    val params = Horizon.Params(epochSize, slotLength * 1000, BigInt(10).pow(9), BigInt(10).pow(9))
    Horizon
      .History(Vector.empty, Horizon.Edge(0, 0, 0), params, Some(params))
      .at(Horizon.Transition.Unknown(Some(BigInt(0))))

  private def read(c: io.circe.Json): (DijkstraTx[TxLevel.Top], UTxO[Dijkstra]) =
    val tx = DijkstraTx.inBlock(node(hex(str(c, "bytes")))).fold(e => fail(e.toString), identity)
    val utxo = c.hcursor
      .get[Vector[(String, String)]]("utxo")
      .fold(e => fail(e.message), identity)
      .map { (i, o) =>
        txIn(DijkstraTxIn.decode(node(hex(i))).fold(e => fail(e.toString), identity)) ->
          DijkstraTxOut.decode(node(hex(o))).fold(e => fail(e.toString), identity)
      }
    (tx, UTxO[Dijkstra](utxo.toMap))

  private lazy val start = xyz.kd5ujc.scarab.ledger.state.Build
    .dijkstra(EpochNo.fromBigInt(0).fold(e => fail(e.toString), identity), stored)
    .es
    .ls

  private def judged(c: io.circe.Json): Either[DijkstraUtxoRun.Defect, Either[
    cats.data.NonEmptyChain[DijkstraUtxoRun.Rec],
    DijkstraUtxoRun.S
  ]] = judgedAt(c, c.hcursor.get[BigInt]("slot").fold(e => fail(e.message), identity))

  private def judgedAt(c: io.circe.Json, slot: BigInt) =
    val (tx, utxo) = read(c)
    val stored = Utxo(utxo.unUTxO.view.mapValues(StoredTxOut.dijkstra.toStored).toMap)
    val env = DijkstraUtxoEnv[Dijkstra](
      slot,
      pp,
      start.cert.pState,
      start.cert,
      utxo
    )
    DijkstraUtxoRun.run(
      env,
      DijkstraStAnnTx.mkDijkstraStAnnTopTx(clock, pp, utxo, Map.empty, tx),
      DijkstraUtxoContext(
        Network.Testnet,
        xyz.kd5ujc.scarab.ledger.SystemStart(Instant.ofEpochSecond(systemStart.toLong)),
        history,
        TxEnv.Validation.All
      ),
      start.utxo.copy[Dijkstra](utxo = UtxoTables(stored)),
      ScarabEvaluator.default,
      Verdicts.Direct,
      ScarabEvaluator.defaultDecoder
    )

  private lazy val chain: Vector[io.circe.Json] = chainContextCases

  /** The chain's transactions a ranking block holds, judged at its slot. The fixture records an
    * endorser block's transaction with the slot of the ranking block that certifies the endorser
    * block (`slot`) and the endorser block's own (`ebSlot`): four of the 94 without
    * sub-transactions end their validity interval before the first and are admitted only at the
    * second (the last test), so the slot a closure is judged at is BBODY's question (D8), not this
    * rule's.
    */
  private lazy val ranking: Vector[io.circe.Json] = chain.filter(c => str(c, "source") == "rb")

  test(
    "UTXOW whole admits every ranking block's transaction of the chain without sub-transactions"
  ) {
    assertEquals(chain.size, 200)
    assertEquals(ranking.size, 89)
    val (batches, single) =
      ranking.partition(c => bodies.subTransactions(read(c)._1.body).toVector.nonEmpty)
    assertEquals((single.size, batches.size), (78, 11))
    val refused = single.flatMap { c =>
      judged(c) match
        case Right(Right(_)) => None
        case other           => Some(s"${str(c, "name")}: $other")
    }
    assertEquals(refused, Vector.empty[String])
  }

  /** The language's tag of a script's hash by the language's encoding (`plutusLanguageTag`,
    * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Language.hs:525, 545, 565, 586`, the
    * encoding `fromEnum` of `Language`, `:273-278`, cardano-ledger 9df9f98c).
    */
  private val languageTags: Map[BigInt, Byte] =
    Map(BigInt(0) -> 0x01, BigInt(1) -> 0x02, BigInt(2) -> 0x03, BigInt(3) -> 0x04)

  /** The hash of the script of a context the reference collected, from its `PlutusWithContext`
    * encoding (`ToCBOR PlutusWithContext`,
    * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Evaluate.hs:186-193`, cardano-ledger
    * 9df9f98c): its script is the language and the binary, hashed as `hashPlutusScript` hashes them
    * (`Language.hs:201-205`).
    */
  private def recordedScriptHash(encoding: String): Bytes =
    node(hex(encoding)).value match
      case CborValue.Arr(Vector(_, script, _, _, _)) =>
        script.value match
          case CborValue.Arr(Vector(language, binary)) =>
            (language.value, binary.value) match
              case (CborValue.UInt(l), CborValue.ByteString(b)) =>
                Blake2b.hash224.hash(Bytes(Vector(languageTags.getOrElse(l, fail(s"$l")))) ++ b)
              case other => fail(s"not a script: $other")
          case other => fail(s"not a script: $other")
      case other => fail(s"not a PlutusWithContext: $other")

  /** The hashes of the scripts of the top level's contexts the reference collected for each of the
    * chain's transactions, in its order (`results.json`).
    */
  private lazy val recordedScripts: Map[String, Vector[Bytes]] =
    json("fixtures/reference/leios-testnet-chain-contexts/results.json").asArray
      .getOrElse(fail("the results are not an array"))
      .map { r =>
        val top = r.hcursor.downField("result").downField("top")
        assertEquals(top.get[Boolean]("collected"), Right(true), str(r, "name"))
        str(r, "name") -> top
          .get[Vector[String]]("contexts")
          .fold(e => fail(e.message), identity)
          .map(recordedScriptHash)
      }
      .toMap

  test("UTXOW whole evaluates the chain's Plutus scripts and finds every one passing") {
    val withScripts = ranking.filter { c =>
      val (tx, _) = read(c)
      bodies.subTransactions(tx.body).toVector.isEmpty &&
      EraTx.dijkstra.witsOf.rdmrsTxWits(EraTx.dijkstra.wits[TxLevel.Top](tx)).entries.nonEmpty
    }
    assertEquals(withScripts.size, 78)
    // the scripts each one's success event reports, by hash in their order, are the contexts the
    // reference collected for it, so that a script left unevaluated fails
    val evaluated = withScripts.map { c =>
      val name = str(c, "name")
      judged(c) match
        case Right(Right((_, events))) =>
          val passed = events.toVector.collect {
            case AlonzoUtxowEvent.WrappedShelleyEraEvent(
                  ShelleyUtxowEvent.UtxoEvent(
                    AlonzoUtxoEvent.UtxosEvent(ConwayUtxosEvent.SuccessfulPlutusScriptsEvent(ps))
                  )
                ) =>
              ps.toList.toVector.map(_.pwcScript.plutusRunnableScriptHash)
          }
          val recorded = recordedScripts.getOrElse(name, fail(s"$name: no recorded contexts"))
          assertEquals(passed.map(_.size), Vector(recorded.size), name)
          assertEquals(passed, Vector(recorded), name)
          recorded.size
        case other => fail(s"$name: $other")
    }
    // 110 contexts over the 78, of which 17 have several, nine at most
    assertEquals((evaluated.sum, evaluated.count(_ > 1), evaluated.max), (110, 17, 9))
  }

  // ---- the injections, on built values of every constructor Dijkstra's UTXO reports

  private lazy val sample: (TxIn, TxOut[Dijkstra]) =
    read(chain.headOption.getOrElse(fail("no case")))._2.unUTxO.headOption
      .getOrElse(fail("no output"))

  private lazy val shelley: Vector[DijkstraUtxo.ShelleyFailure[Dijkstra]] =
    val (in, out) = sample
    val o = EraTxOut.dijkstra
    Vector[DijkstraUtxo.ShelleyFailure[Dijkstra]](
      new ShelleyUtxoPredFailure.BadInputsUTxO[Dijkstra](NonEmptySet.one(in)),
      new ShelleyUtxoPredFailure.InputSetEmptyUTxO[Dijkstra](),
      new ShelleyUtxoPredFailure.FeeTooSmallUTxO[Dijkstra](Mismatch(Coin(1), Coin(2))),
      new ShelleyUtxoPredFailure.ValueNotConservedUTxO[Dijkstra](
        Mismatch(o.value(out), o.value(out))
      ),
      new ShelleyUtxoPredFailure.WrongNetwork[Dijkstra](
        Network.Testnet,
        NonEmptySet.one(o.address(out))
      ),
      new ShelleyUtxoPredFailure.OutputBootAddrAttrsTooBig[Dijkstra](NonEmptyList.one(out)),
      new ShelleyUtxoPredFailure.MaxTxSizeUTxO[Dijkstra](Mismatch(BigInt(3), BigInt(2)))
    )

  private lazy val alonzo: Vector[DijkstraUtxo.AlonzoFailure[Dijkstra]] =
    val (in, out) = sample
    val o = EraTxOut.dijkstra
    Vector[DijkstraUtxo.AlonzoFailure[Dijkstra]](
      new AlonzoUtxoPredFailure.OutsideForecast[Dijkstra](BigInt(7)),
      new AlonzoUtxoPredFailure.OutputTooBigUTxO[Dijkstra](NonEmptyList.one((5, 4, out))),
      new AlonzoUtxoPredFailure.WrongNetworkInTxBody[Dijkstra](
        Mismatch(Network.Mainnet, Network.Testnet)
      ),
      new AlonzoUtxoPredFailure.ExUnitsTooBigUTxO[Dijkstra](Mismatch(ExUnits(2, 2), ExUnits(1, 1))),
      new AlonzoUtxoPredFailure.TooManyCollateralInputs[Dijkstra](
        Mismatch(Unsigned.lit(4), Unsigned.lit(3))
      ),
      new AlonzoUtxoPredFailure.ScriptsNotPaidUTxO[Dijkstra](NonEmptyMap.one(in, out)),
      new AlonzoUtxoPredFailure.CollateralContainsNonADA[Dijkstra](o.value(out)),
      new AlonzoUtxoPredFailure.InsufficientCollateral[Dijkstra](DeltaCoin(-1), Coin(9)),
      new AlonzoUtxoPredFailure.NoCollateralInputs[Dijkstra]()
    )

  private lazy val babbage: Vector[DijkstraUtxo.BabbageFailure[Dijkstra]] =
    val (_, out) = sample
    Vector[DijkstraUtxo.BabbageFailure[Dijkstra]](
      new BabbageUtxoPredFailure.IncorrectTotalCollateralField[Dijkstra](DeltaCoin(5), Coin(6)),
      new BabbageUtxoPredFailure.BabbageOutputTooSmallUTxO[Dijkstra](
        NonEmptyList.one((out, Coin(8)))
      )
    )

  private lazy val allegra: Vector[DijkstraUtxo.AllegraFailure[Dijkstra]] = Vector(
    new AllegraUtxoPredFailure.OutsideValidityIntervalUTxO[Dijkstra](
      ValidityInterval(Some(BigInt(3)), Some(BigInt(4))),
      BigInt(9)
    )
  )

  /** The reference's injection through Conway's failure: `conwayToDijkstraUtxoPredFailure` of the
    * earlier era's injection into Conway, none where Conway's is one the reference calls
    * impossible.
    */
  private def throughConway(
      c: ConwayUtxoPredFailure[Dijkstra]
  ): Option[DijkstraUtxoPredFailure[Dijkstra]] =
    c match
      case ConwayUtxoPredFailure.WrongNetworkWithdrawal(_, _) => None
      case ConwayUtxoPredFailure.OutputTooSmallUTxO(_)        => None
      case f: DijkstraUtxo.ConwayFailure[Dijkstra] =>
        Some(DijkstraUtxo.conwayToDijkstraUtxoPredFailure(f))

  test("each injection equals the reference's composition through Conway's failure") {
    shelley.foreach(f =>
      assertEquals(
        Some(
          summon[InjectUtxoFailure[DijkstraUtxo.ShelleyFailure[Dijkstra], Dijkstra]]
            .injectFailure(f)
        ),
        throughConway(
          ConwayUtxo.allegraToConwayUtxoPredFailure[Dijkstra](
            AllegraUtxo.shelleyToAllegraUtxoPredFailure11_2(f)
          )
        ),
        f.toString
      )
    )
    allegra.foreach(f =>
      assertEquals(
        Some(
          summon[InjectUtxoFailure[DijkstraUtxo.AllegraFailure[Dijkstra], Dijkstra]]
            .injectFailure(f)
        ),
        throughConway(ConwayUtxo.allegraToConwayUtxoPredFailure[Dijkstra](f)),
        f.toString
      )
    )
    alonzo.foreach(f =>
      assertEquals(
        Some(
          summon[InjectUtxoFailure[DijkstraUtxo.AlonzoFailure[Dijkstra], Dijkstra]].injectFailure(f)
        ),
        throughConway(ConwayUtxo.alonzoToConwayUtxoPredFailure(f)),
        f.toString
      )
    )
    babbage.foreach(f =>
      assertEquals(
        Some(
          summon[InjectUtxoFailure[DijkstraUtxo.BabbageFailure[Dijkstra], Dijkstra]]
            .injectFailure(f)
        ),
        throughConway(ConwayUtxo.babbageToConwayUtxoPredFailure(f)),
        f.toString
      )
    )
  }

  test("the injection a predicate's constructor needs is found through the contravariant class") {
    val f = new AlonzoUtxoPredFailure.NoCollateralInputs[Dijkstra]()
    assertEquals(
      summon[InjectUtxoFailure[AlonzoUtxoPredFailure.NoCollateralInputs[Dijkstra], Dijkstra]]
        .injectFailure(f),
      DijkstraUtxoPredFailure.NoCollateralInputs[Dijkstra]()
    )
  }

  test("the constructors the reference calls impossible for Dijkstra's UTXO have no injection") {
    // the control: a constructor Dijkstra's UTXO reports has one, in the same form
    assertEquals(
      compileErrors(
        "summon[InjectUtxoFailure[ShelleyUtxoPredFailure.BadInputsUTxO[Dijkstra], Dijkstra]]"
      ),
      ""
    )
    assert(
      compileErrors(
        "summon[InjectUtxoFailure[ShelleyUtxoPredFailure.WrongNetworkWithdrawal[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectUtxoFailure[AlonzoUtxoPredFailure.OutputTooSmallUTxO[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectUtxoFailure[ConwayUtxoPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectUtxoFailure[AlonzoUtxoPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
  }

  test(
    "an endorser block's transaction refused at its ranking block's slot is admitted at its own"
  ) {
    val endorsed = chain.filter(c =>
      str(c, "source") == "eb" && bodies.subTransactions(read(c)._1.body).toVector.isEmpty
    )
    val refused = endorsed.filter(c =>
      judged(c) match
        case Right(Right(_)) => false
        case _               => true
    )
    refused.foreach { c =>
      judged(c) match
        case Right(Left(fs)) =>
          assert(
            fs.forall {
              case DijkstraUtxowPredFailure.UtxoFailure(
                    DijkstraUtxoPredFailure.OutsideValidityIntervalUTxO(_, _)
                  ) =>
                true
              case _ => false
            },
            s"${str(c, "name")}: $fs"
          )
        case other => fail(s"${str(c, "name")}: $other")
      val ebSlot = c.hcursor.get[BigInt]("ebSlot").fold(e => fail(e.message), identity)
      assert(judgedAt(c, ebSlot).exists(_.isRight), str(c, "name"))
    }
    assertEquals((endorsed.size, refused.size), (94, 4))
  }

  test("UTXOW on every batch of the chain against the UTxO set before it, a component check") {
    // a component check (CNF-2), not the batch's verdict: the threaded state and pool state are the
    // set before the batch, where LEDGER gives UTXOW those after SUBLEDGERS (D7c); every lookup of
    // UTXOW and UTXO reads the set before, as the reference's do, and the chain registers no pool.
    // An endorser block's batch is judged at the endorser block's slot where the certifying ranking
    // block's refuses it for its validity interval alone, as the last test shows for the others.
    val batches = chain.filter(c => bodies.subTransactions(read(c)._1.body).toVector.nonEmpty)
    assertEquals(batches.size, 28)
    val refused = batches.flatMap { c =>
      def admitted(slot: BigInt) = judgedAt(c, slot) match
        case Right(Right(_)) => true
        case _               => false
      val slot = c.hcursor.get[BigInt]("slot").fold(e => fail(e.message), identity)
      val ebSlot = c.hcursor.get[BigInt]("ebSlot").toOption
      Option.unless(admitted(slot) || ebSlot.exists(admitted))(s"${str(c, "name")}: ${judged(c)}")
    }
    assertEquals(refused, Vector.empty[String])
  }

  test("UTXOS reports the collection's errors of every context refusal, and evaluates nothing") {
    // `fixtures/reference/leios-testnet-context-refusals`: 22 transactions written on the testnet's
    // build, whose collections `DijkstraPlutusContextSuite` compares with the reference's; under the
    // policy that skips the phase-two checks (`when2Phase`), UTXOS records a refused collection's
    // errors as `CollectErrors` and nothing for one collected, and no script is evaluated
    val refusals = json("fixtures/reference/leios-testnet-context-refusals/cases.json")
    val c0 = refusals.hcursor
    val start = BigInt(Instant.parse(str(refusals, "systemStart")).getEpochSecond)
    val length = c0.get[BigInt]("slotLength").fold(e => fail(e.message), identity)
    val languages = PlutusLanguage.values.map(l => s"Plutus$l" -> l).toMap
    val models = c0
      .get[Map[String, Vector[BigInt]]]("costModels")
      .fold(e => fail(e.message), identity)
      .map((name, values) => languages.getOrElse(name, fail(name)).id -> values)
    val p = EraPParams.dijkstra
    val params = PParams
      .lensHKD[Dijkstra]
      .andThen(p.hkdCostModelsL[Identity])
      .replace(CostModels.from(models).fold(e => fail(e.toString), identity))(era(stored))
    val cases = c0.get[Vector[io.circe.Json]]("cases").fold(e => fail(e.message), identity)
    assertEquals(cases.size, 22)
    val unasked: xyz.kd5ujc.scarab.plutus.ScriptEvaluator[cats.Id] =
      request => fail(s"a script was evaluated: $request")
    val refused = cases.count { c =>
      val (tx, utxo) = read(c)
      val top = DijkstraStAnnTx.mkDijkstraStAnnTopTx(
        slot => Right((start + slot * length) * 1000),
        params,
        utxo,
        Map.empty,
        tx
      )
      val collection = EraUTxO.dijkstra.plutusScriptsWithContextStAnnTx[TxLevel.Top](top)
      val judged = DijkstraUtxoRun.Utxos.run(top, TxEnv.Validation.NonStatic, unasked)
      collection match
        case Left(errors) =>
          assertEquals(
            judged.map(_.left.map(_.toChain.toVector)),
            Right(Left(Vector(ConwayUtxosPredFailure.CollectErrors[Dijkstra](errors)))),
            str(c, "name")
          )
          true
        case Right(_) =>
          assertEquals(judged.map(_.isRight), Right(true), str(c, "name"))
          false
    }
    // the 11 refused at the top level, and the one collected there whose sub-transaction is
    // refused: the collection UTXOS reads is the batch's (`plutusScriptsWithContextStAnnTx`)
    assertEquals(refused, 12)
  }

  // ---- a needed native script in Dijkstra's form, with a guard (#1126)

  /** The devnet's transaction of block 37 (`fixtures/dijkstra-devnet`, provenance in its README),
    * which the reference accepted, in the era's types.
    */
  private lazy val devnetTx: DijkstraTx[TxLevel.Top] =
    val raw = java.nio.file.Files
      .readAllLines(java.nio.file.Path.of("fixtures", "dijkstra-devnet", "blocks.tsv"))
      .toArray(Array.empty[String])
      .toVector
      .drop(1)
      .map(_.split('\t'))
      .filter(p => p(0) == "8" && p(1) == "37")
      .map(p => hex(p(4)))
      .headOption
      .getOrElse(fail("no block 37"))
    xyz.kd5ujc.scarab.ledger.Block.decodeShelley(raw) match
      case Right(xyz.kd5ujc.scarab.ledger.Block.Dijkstra(b)) =>
        b.transactions.headOption.getOrElse(fail("no transaction"))
      case other => fail(s"not a Dijkstra block: $other")

  /** The devnet transaction's input: the initial funds of the genesis address `607be6…`, whose
    * input is the Blake2b-256 of the address, index 0.
    */
  private val genesisAddress =
    hex("607be6f4db9b5c16681157f9c2e15e57e341e21ecf94e553896f897a44")

  private val genesisInput = TxIn(Blake2b.hash256.hash(genesisAddress), 0)

  /** The parameters of the devnet's first Dijkstra epoch: protocol 12 with the Dijkstra genesis. */
  private lazy val devnetStored: xyz.kd5ujc.scarab.ledger.ProtocolParams =
    xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraFixture.genesis
      .upgrade(xyz.kd5ujc.scarab.ledger.TestLedger.params(12))

  /** The genesis input at a script address of the native script `native`, which the output holds as
    * its reference script, as the store keeps a Dijkstra output: a script with a guard has no
    * time-lock tree there. With the script's hash.
    */
  private def lockedBy(native: String): (Bytes, Utxo) =
    val hash = Blake2b.hash224.hash(hex("00" + native))
    val script = "8200" + native
    val wrapped = "d818" + f"58${script.length / 2}%02x" + script
    // `{0: script address, 1: the genesis funds, 3: the script}`
    val out = hex("a3" + "00" + "581d70" + hash.hex + "01" + "1b0003328b944c4000" + "03" + wrapped)
    val stored = DijkstraTxOut
      .decode(node(out))
      .map(StoredTxOut.dijkstra.toStored)
      .fold(e => fail(e.toString), identity)
    hash -> Utxo(Map(genesisInput -> stored))

  /** UTXOW of Dijkstra on the era types on the devnet transaction from `utxo`, at slot 225. */
  private def devnetUtxow(utxo: Utxo) =
    val tx = devnetTx
    val ls = xyz.kd5ujc.scarab.ledger.state.Build
      .dijkstra(EpochNo.fromBigInt(1).fold(e => fail(e.toString), identity), devnetStored)
      .es
      .ls
    val env = DijkstraUtxoEnv[Dijkstra](
      225,
      era(devnetStored),
      ls.cert.pState,
      ls.cert,
      DijkstraUtxoRun.originalUtxo(utxo, tx)
    )
    val params = Horizon.Params(100, 1000, 30, 30)
    DijkstraUtxoRun.run(
      env,
      DijkstraUtxoRun.annotate(s => fail(s"the clock was read at $s"), env, tx),
      DijkstraUtxoContext(
        Network.Testnet,
        xyz.kd5ujc.scarab.ledger.SystemStart(Instant.EPOCH),
        Horizon
          .History(Vector.empty, Horizon.Edge(0, 0, 0), params, Some(params))
          .at(Horizon.Transition.Unknown(Some(BigInt(1000)))),
        TxEnv.Validation.All
      ),
      ls.utxo.copy[Dijkstra](utxo = UtxoTables(utxo)),
      ScarabEvaluator.default,
      Verdicts.Direct,
      ScarabEvaluator.defaultDecoder
    )

  private val guardKey = "55" * 28
  // `[6, [0, keyhash]]`: a guard by a key credential
  private val guard = "8206" + "8200581c" + guardKey
  // the devnet transaction's signer: the payment key of the genesis address
  private val genesisSigner =
    "8200581c" + "7be6f4db9b5c16681157f9c2e15e57e341e21ecf94e553896f897a44"

  test("UTXOW applies the devnet transaction: its input spent, its outputs created, its fee kept") {
    val tx = devnetTx
    val address = xyz.kd5ujc.scarab.ledger.Address.decode(genesisAddress).getOrElse(fail("address"))
    val funds = xyz.kd5ujc.scarab.ledger.TxOut(
      address,
      xyz.kd5ujc.scarab.ledger.Value.coin(BigInt("900000000000000"))
    )
    devnetUtxow(Utxo(Map(genesisInput -> funds))) match
      case Right(Right((after, _))) =>
        assertEquals(
          after.utxo.utxo.keys.toSet,
          bodies.outputs[TxLevel.Top](tx.body).indices.map(i => TxIn(bodies.id(tx.body), i)).toSet
        )
        assertEquals(after.fees, bodies.fee(tx.body))
      case other => fail(s"not admitted: $other")
  }

  test("UTXOW refuses a needed reference script with a guard the body does not have") {
    // `evalDijkstraNativeScript` (`Dijkstra/Scripts.hs`, cardano-ledger 9df9f98c) holds a guard
    // only for a credential the body's guards hold (`validateDijkstraNativeScript`,
    // `Dijkstra/Tx.hs:434-437`); the devnet transaction has none
    val (hash, utxo) = lockedBy(guard)
    assertEquals(utxo.toMap.values.flatMap(_.scriptRef).map(_.native).toVector, Vector(None))
    devnetUtxow(utxo) match
      case Right(Left(fs)) =>
        assertEquals(
          fs.toChain.toVector,
          Vector[UtxowPredFailure[Dijkstra]](
            DijkstraUtxowPredFailure.ScriptWitnessNotValidatingUTXOW[Dijkstra](
              NonEmptySet.one(hash)(using cats.Order.fromOrdering)
            )
          )
        )
      case other => fail(s"not refused: $other")
  }

  test("UTXOW applies a transaction whose needed script holds by a signature beside its guard") {
    // `RequireAnyOf` of the guard and the transaction's signer
    val (_, utxo) = lockedBy("8202" + "82" + guard + genesisSigner)
    devnetUtxow(utxo) match
      case Right(Right((after, _))) =>
        assertEquals(after.utxo.utxo.keys.size, bodies.outputs[TxLevel.Top](devnetTx.body).size)
      case other => fail(s"not admitted: $other")
  }

  // ---- the top level under `NonStatic`, on built transactions (`DijkstraImpBatch`)

  private val B = DijkstraImpBatch
  private lazy val owner = xyz.kd5ujc.scarab.ledger.TestLedger.key()
  private lazy val signer = xyz.kd5ujc.scarab.ledger.TestLedger.key()
  private val builtIn = B.fresh(0x71)
  private val builtFee: BigInt = 1000000

  /** A top level spending `builtIn`, paying `builtFee` and the rest to the signer with the outputs
    * `before`, with the body fields `fields`, whose witness set holds the signer's key with a
    * signature of 32 zero bytes, not of the body's id.
    */
  private def badlySigned(
      before: Vector[xyz.kd5ujc.scarab.codec.Node],
      fields: (Int, xyz.kd5ujc.scarab.codec.Node)*
  ): DijkstraTx[TxLevel.Top] =
    val change = BigInt(20000000) - builtFee - BigInt(10000000) * before.size
    val body = B.body(
      (Vector(
        0 -> B.set(builtIn.node),
        1 -> B.arr((before :+ B.out(B.keyAddress(signer), change))*),
        2 -> B.uint(builtFee)
      ) ++ fields)*
    )
    val witness = B.arr(B.bytes(signer.vkey), B.bytes(signer.sign(Bytes(Vector.fill(32)(0)))))
    B.topTx(body, B.cmap(B.uint(0) -> B.set(witness)))

  /** The output `builtIn` names: 20 ada at `holder` 's address. */
  private def held(holder: xyz.kd5ujc.scarab.ledger.TestLedger.Key): UTxO[Dijkstra] =
    B.utxo(builtIn -> B.out(B.keyAddress(holder), 20000000))

  /** UTXOW of Dijkstra on a built transaction from the outputs it spends, at the devnet's slot 225,
    * under `validation`.
    */
  private def builtUtxow(
      tx: DijkstraTx[TxLevel.Top],
      spent: UTxO[Dijkstra],
      validation: TxEnv.Validation
  ) =
    val ls = xyz.kd5ujc.scarab.ledger.state.Build
      .dijkstra(EpochNo.fromBigInt(1).fold(e => fail(e.toString), identity), devnetStored)
      .es
      .ls
    val env = DijkstraUtxoEnv[Dijkstra](225, era(devnetStored), ls.cert.pState, ls.cert, spent)
    val params = Horizon.Params(100, 1000, 30, 30)
    DijkstraUtxoRun.run(
      env,
      DijkstraUtxoRun.annotate(s => fail(s"the clock was read at $s"), env, tx),
      DijkstraUtxoContext(
        Network.Testnet,
        xyz.kd5ujc.scarab.ledger.SystemStart(Instant.EPOCH),
        Horizon
          .History(Vector.empty, Horizon.Edge(0, 0, 0), params, Some(params))
          .at(Horizon.Transition.Unknown(Some(BigInt(1000)))),
        validation
      ),
      ls.utxo.copy[Dijkstra](utxo =
        UtxoTables(Utxo(spent.unUTxO.view.mapValues(StoredTxOut.dijkstra.toStored).toMap))
      ),
      ScarabEvaluator.default,
      Verdicts.Direct,
      ScarabEvaluator.defaultDecoder
    )

  test("top-level UTXOW under NonStatic admits a transaction whose one fault is its signature") {
    // `runTestOnSignal $ Shelley.validateVerifiedWits tx` (`Dijkstra/Rules/Utxow.hs:294`,
    // cardano-ledger 9df9f98c): applied again, the signatures are not checked, and the input is
    // spent as under `All` it would be
    val tx = badlySigned(Vector.empty)
    builtUtxow(tx, held(signer), TxEnv.Validation.All) match
      case Right(Left(fs)) =>
        assertEquals(
          fs.toChain.toVector,
          Vector[UtxowPredFailure[Dijkstra]](
            DijkstraUtxowPredFailure.InvalidWitnessesUTXOW[Dijkstra](NonEmptyList.one(signer.vkey))
          )
        )
      case other => fail(s"not refused: $other")
    builtUtxow(tx, held(signer), TxEnv.Validation.NonStatic) match
      case Right(Right((after, _))) =>
        assertEquals(
          after.utxo.utxo.keys.toSet,
          Set(TxIn(bodies.id(tx.body), 0))
        )
        assertEquals(after.fees, Coin(builtFee))
      case other => fail(s"not admitted: $other")
  }

  test(
    "top-level UTXOW under NonStatic skips the reference's four checks on the signal, not the rest"
  ) {
    // `dijkstraUtxowTransition` (`Dijkstra/Rules/Utxow.hs:216-325`, cardano-ledger 9df9f98c) runs
    // four of its checks with `runTestOnSignal` (`:294`, `:301`, `:305`, `:318`): the signatures,
    // the auxiliary data, the well-formed scripts and the required guards. A transaction failing
    // all four, and two checks run with `runTest` beside them: the needed key witnesses (`:297`;
    // its input is the owner's, whose key does not sign) and the required guards' datums
    // (`:321`; a key's guard with a datum). Its auxiliary data hash names no auxiliary data, and
    // its first output holds a reference script that is no Plutus program
    val guardKey = Bytes(Vector.fill(28)(0x72.toByte))
    val junk = Bytes(Vector(0.toByte))
    val auxHash = Bytes(Vector.fill(32)(0x77.toByte))
    val tx = badlySigned(
      Vector(B.refScriptOut(B.keyAddress(signer), 10000000, junk)),
      7 -> B.bytes(auxHash),
      24 -> B.cmap(B.arr(B.uint(0), B.bytes(guardKey)) -> B.uint(0))
    )
    def judged(validation: TxEnv.Validation) =
      builtUtxow(tx, held(owner), validation).map(_.left.map(_.toChain.toVector))
    given cats.Order[Bytes] = cats.Order.fromOrdering
    val guard = NonEmptySet.one[Credential[Guard]](
      Credential.KeyHashObj[Guard](
        KeyHash.fromBytes[Guard](guardKey).fold(e => fail(e.toString), identity)
      )
    )(using cats.Order[Credential[Guard]])
    val signal = Vector[UtxowPredFailure[Dijkstra]](
      DijkstraUtxowPredFailure.MissingRequiredGuards[Dijkstra](guard),
      DijkstraUtxowPredFailure.MalformedReferenceScripts[Dijkstra](
        NonEmptySet.one(B.scriptHash(3, junk))
      ),
      DijkstraUtxowPredFailure.MissingTxMetadata[Dijkstra](auxHash),
      DijkstraUtxowPredFailure.InvalidWitnessesUTXOW[Dijkstra](NonEmptyList.one(signer.vkey))
    )
    val malformed = DijkstraUtxowPredFailure.MalformedGuardDatums[Dijkstra](guard)
    val missing =
      DijkstraUtxowPredFailure.MissingVKeyWitnessesUTXOW[Dijkstra](NonEmptySet.one(owner.hash))
    // at the root the failures are in the reverse of the order the rule records them
    // (`DijkstraUtxoRun.run`): the guards' datums last checked, the signatures first
    assertEquals(
      judged(TxEnv.Validation.All),
      Right(Left(Vector(malformed, signal(0), signal(1), signal(2), missing, signal(3))))
    )
    assertEquals(judged(TxEnv.Validation.NonStatic), Right(Left(Vector(malformed, missing))))
  }

  test("top-level UTXOW under NonStatic still runs every check the reference runs with runTest") {
    // the seven checks `dijkstraUtxowTransition` runs with `runTest` (`Dijkstra/Rules/Utxow.hs:260,
    // 277, 281, 290, 297, 310, 321`, cardano-ledger 9df9f98c), each failing on one transaction: a
    // needed native script that does not hold (its input at the script's address, the script
    // requiring the owner's signature), a script witness no level needs, a datum no output or
    // input allows, a redeemer pointing at no input, the owner's key not signing, no script
    // integrity hash where the redeemers and datum need one, and a key's required guard with a
    // datum. The signer's signature is the body's. Under `All` the required guard is also missing
    // from the guards, on the signal (`:318`); under `NonStatic` every other failure stays, and so
    // do UTXO's, which the redeemers ask collateral of
    val failing = B.arr(B.uint(0), B.bytes(owner.hash))
    val extra = B.arr(B.uint(1), B.arr())
    val failingHash = B.scriptHash(0, B.encode(failing))
    val scriptIn = B.fresh(0x73)
    val guardKey = Bytes(Vector.fill(28)(0x74.toByte))
    val body = B.body(
      0 -> B.set(builtIn.node, scriptIn.node),
      1 -> B.arr(B.out(B.keyAddress(signer), BigInt(30000000) - builtFee)),
      2 -> B.uint(builtFee),
      24 -> B.cmap(B.arr(B.uint(0), B.bytes(guardKey)) -> B.uint(0))
    )
    val datum = B.uint(42)
    val datums = B.set(datum)
    // `{[0, 5] => [0, [0, 0]]}`: a spending redeemer at index 5, of the datum 0 and no units
    val redeemers =
      B.cmap(B.arr(B.uint(0), B.uint(5)) -> B.arr(B.uint(0), B.arr(B.uint(0), B.uint(0))))
    val wits = B.cmap(
      B.uint(0) -> B.set(B.arr(B.bytes(signer.vkey), B.bytes(signer.sign(B.id(body))))),
      B.uint(1) -> B.set(failing, extra),
      B.uint(4) -> datums,
      B.uint(5) -> redeemers
    )
    val tx = B.topTx(body, wits)
    val spent = UTxO[Dijkstra](
      held(owner).unUTxO ++
        B.utxo(scriptIn -> B.out(B.scriptAddress(failingHash), 10000000)).unUTxO
    )
    def judged(validation: TxEnv.Validation) =
      builtUtxow(tx, spent, validation).map(_.left.map(_.toChain.toVector))
    given cats.Order[Bytes] = cats.Order.fromOrdering
    val guard = NonEmptySet.one[Credential[Guard]](
      Credential.KeyHashObj[Guard](
        KeyHash.fromBytes[Guard](guardKey).fold(e => fail(e.toString), identity)
      )
    )(using cats.Order[Credential[Guard]])
    // the bytes the integrity hash is over (`hashScriptIntegrity`): the redeemers, the datums and
    // the language views, of no language
    val integrity = B.encode(redeemers) ++ B.encode(datums) ++ hex("a0")
    val five = Unsigned.from[Unsigned.Width.W32](BigInt(5)).getOrElse(fail("5"))
    val utxo = Vector[UtxowPredFailure[Dijkstra]](
      DijkstraUtxowPredFailure.UtxoFailure[Dijkstra](
        DijkstraUtxoPredFailure.InsufficientCollateral[Dijkstra](DeltaCoin(0), Coin(1500000))
      ),
      DijkstraUtxowPredFailure.UtxoFailure[Dijkstra](
        DijkstraUtxoPredFailure.NoCollateralInputs[Dijkstra]()
      )
    )
    // at the root in the reverse of the order the rule records them: UTXO's block first, then the
    // guards' datums, last checked, to the failing native script, first
    val runTests = Vector[UtxowPredFailure[Dijkstra]](
      DijkstraUtxowPredFailure.MalformedGuardDatums[Dijkstra](guard),
      DijkstraUtxowPredFailure.ScriptIntegrityHashMismatch[Dijkstra](
        Mismatch(None, Some(Blake2b.hash256.hash(integrity))),
        Some(integrity)
      ),
      DijkstraUtxowPredFailure.MissingVKeyWitnessesUTXOW[Dijkstra](NonEmptySet.one(owner.hash)),
      DijkstraUtxowPredFailure.ExtraRedeemers[Dijkstra](
        NonEmptyList.one(DijkstraPlutusPurpose.DijkstraSpending[AsIx](AsIx(five)))
      ),
      DijkstraUtxowPredFailure.NotAllowedSupplementalDatums[Dijkstra](
        NonEmptySet.one(B.dataHash(datum)),
        Set.empty
      ),
      DijkstraUtxowPredFailure.ExtraneousScriptWitnessesUTXOW[Dijkstra](
        NonEmptySet.one(B.scriptHash(0, B.encode(extra)))
      ),
      DijkstraUtxowPredFailure.ScriptWitnessNotValidatingUTXOW[Dijkstra](
        NonEmptySet.one(failingHash)
      )
    )
    assertEquals(judged(TxEnv.Validation.NonStatic), Right(Left(utxo ++ runTests)))
    assertEquals(
      judged(TxEnv.Validation.All),
      Right(
        Left(
          utxo ++ runTests.take(1) ++
            Vector(DijkstraUtxowPredFailure.MissingRequiredGuards[Dijkstra](guard)) ++
            runTests.drop(1)
        )
      )
    )
  }
