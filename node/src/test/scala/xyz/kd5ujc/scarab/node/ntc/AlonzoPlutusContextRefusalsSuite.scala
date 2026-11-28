// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import cats.data.NonEmptyVector
import cats.syntax.all.*
import scala.util.chaining.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  CollectError as FlatCollectError,
  GenDelegPair,
  Phase2,
  ScriptContexts,
  TestLedger,
  TranslationError,
  Tx as FlatTx,
  TxIn,
  TxOut as FlatTxOut,
  Unsigned,
  Utxo,
  testByteStrings
}
import xyz.kd5ujc.scarab.ledger.ContextReferenceCases.{field, hex}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.alonzo.{AlonzoEraUTxO, AlonzoPParams, AlonzoTestKit, AlonzoTx}
import xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTestKit
import xyz.kd5ujc.scarab.ledger.rules.StAnnTx as FlatStAnnTx
import xyz.kd5ujc.scarab.ledger.state.{
  Alonzo,
  Empty,
  Genesis,
  KeyHash,
  NewEpochState,
  ShelleyBasedEra,
  ShelleyCertState
}
import xyz.kd5ujc.scarab.node.mempool.LocalSubmission
import xyz.kd5ujc.scarab.node.ntc.ReferenceRefusalSuite.*
import xyz.kd5ujc.scarab.plutus.PlutusLanguage
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, UtxoCodecs}

/** Alonzo's Plutus side on the era types against the flat path and the reference, where the ledger
  * module's `AlonzoPlutusContextSuite` cannot reach:
  *   - every Alonzo case of the recorded refusal corpora, with the UTxO set and the state each was
  *     refused on, at its parameters' protocol version and cost models: the V1 `TxInfo`, the
  *     scripts with their contexts, the scripts needed and the keys that must sign (the state's
  *     genesis delegations among them) are the flat path's;
  *   - the Alonzo cases of the forecast-horizon corpora, with the clock of their era history;
  *   - every Alonzo row of the context matrix (`fixtures/reference/context-matrix`): each request
  *     encodes to the reference's `PlutusWithContext` bytes whole.
  * The refusal corpora carry no era history, so slot `s` is the time `s` seconds for both paths.
  */
class AlonzoPlutusContextRefusalsSuite extends FunSuite:
  private given ShelleyBasedEra[Alonzo] = ShelleyBasedEra.SAlonzo
  private val t = EraTx.alonzo
  private given t.bodyOf.Level[TxLevel.Top] = t.bodyOf.top
  private val u = EraUTxO.alonzo
  private val ctx = EraPlutusContext.alonzo

  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private def protVer(major: Int): ProtVer =
    ProtVer(
      Version.mkVersion(major).getOrElse(fail(s"version $major")),
      Unsigned.from[Unsigned.Width.W32](BigInt(0)).getOrElse(fail("minor"))
    )

  /** One case: the transaction both ways, the outputs the set holds of it, its parameters, the
    * genesis delegations in force and its clock.
    */
  private final case class Seen(
      label: String,
      flat: FlatTx,
      era: AlonzoTx,
      known: Map[TxIn, FlatTxOut],
      params: xyz.kd5ujc.scarab.ledger.ProtocolParams,
      genDelegs: Map[Bytes, GenDelegPair],
      cert: ShelleyCertState,
      posix: BigInt => Either[TranslationError, BigInt]
  ):
    def major: Int = params.protocolMajor.toInt
    def spent: Map[TxIn, FlatTxOut] = known.filter((in, _) => flat.body.inputs.contains(in))
    lazy val utxo: UTxO[Alonzo] = right(s"$label UTxO")(AlonzoEraUTxO.fromStored(known))
    def pp: PParams[Alonzo] =
      PParams[Alonzo](right(s"$label parameters")(AlonzoPParams.fromStored(params)))
    def lti: LedgerTxInfo[TxLevel.Top, Alonzo] =
      LedgerTxInfo[TxLevel.Top, Alonzo](
        protVer(major),
        posix,
        utxo,
        era,
        Nil,
        LedgerLevelTxInfo.LedgerTopTxInfo(Map.empty)
      )
    def env: ScriptContexts.Environment =
      ScriptContexts.Environment(ShelleyBasedEra.SAlonzo, major, spent, Map.empty, posix)

  private val clock: BigInt => Either[TranslationError, BigInt] = slot => Right(slot * 1000)

  private lazy val refused: Vector[Seen] =
    val loaded = right("the refusal corpus")(load(Path.of("fixtures/reference/refusals")))
    loaded.corpora.filter(_.era == RefusalEra.Alonzo).flatMap { c =>
      val codecs = right(s"${c.path} layout")(stateCodecs(c.build))
      c.cases.map { rc =>
        val what = s"${c.path}/${rc.label}"
        val bytes = right(s"$what item")(LocalSubmission.unwrap(rc.item)).raw
        val nes: NewEpochState[Alonzo] = right(s"$what state")(
          codecs.newEpochState[Alonzo].decodeBytes(c.states(rc.state), HaskellState.limits)
        )
        val stored = right(s"$what UTxO")(UtxoCodecs.utxo.decodeBytes(c.utxos(rc.utxo)))
        Seen(
          what,
          right(s"$what flat")(FlatTx.fromEnvelope(bytes, Era.Alonzo)),
          right(s"$what typed")(AlonzoTx.fromEnvelope(bytes, StateLayout.Node11_1)),
          stored,
          nes.es.ls.utxo.gov.curPParams,
          nes.es.ls.cert.dState.genDelegs.map((g, pair) => g.bytes -> pair),
          nes.es.ls.cert,
          clock
        )
      }
    }

  private enum Collected:
    case NoRedeemer(tag: Int, ix: BigInt)
    case NoWitness(hash: Bytes)
    case NoCostModel(language: PlutusLanguage)
    case BadTranslation(failure: TranslationError)

  private def asIx(p: AlonzoPlutusPurpose[AsIxItem, Alonzo]): AlonzoPlutusPurpose[AsIx, Alonzo] =
    EraScript.alonzo
      .hoistPlutusPurpose[AsIxItem, AsIx]([ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsIx(a))(
        p
      )

  private def needed(s: Seen): Vector[(AlonzoPlutusPurpose[AsIxItem, Alonzo], Bytes)] =
    u.getScriptsNeeded[TxLevel.Top](s.utxo, t.body[TxLevel.Top](s.era)).unAlonzoScriptsNeeded

  private def sameCollection(
      s: Seen
  ): Either[List[Collected], List[xyz.kd5ujc.scarab.plutus.EvalRequest]] =
    val era = AlonzoPlutusEvaluate
      .collectPlutusScriptsWithContext[Alonzo](s.posix, s.pp, s.era, s.utxo)
      .fold(
        es =>
          Left(es.toList.map {
            case CollectError.NoRedeemer(purpose) =>
              val p = needed(s)
                .map(_._1)
                .find(p =>
                  EraScript.alonzo.hoistPlutusPurpose[AsIxItem, AsItem](
                    [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsItem(a)
                  )(p) == purpose
                )
              val ix = asIx(p.getOrElse(fail(s"${s.label}: $purpose is not needed")))
              Collected.NoRedeemer(ix.tag, ix.ix.value)
            case CollectError.NoWitness(hash)       => Collected.NoWitness(hash)
            case CollectError.NoCostModel(language) => Collected.NoCostModel(language)
            case CollectError.BadTranslation(failure) =>
              Collected.BadTranslation(AlonzoTestKit.flatContextError(failure))
          }),
        pwcs => Right(pwcs.map(PlutusWithContext.evalRequest))
      )
    val flat = Phase2
      .collect[Alonzo](
        s.flat,
        s.params,
        s.flat.witnesses.scriptsByHash,
        s.spent,
        Map.empty,
        s.posix
      )
      .fold(
        es =>
          Left(es.toVector.toList.map {
            case FlatCollectError.NoRedeemer(p)     => Collected.NoRedeemer(p.tag, BigInt(p.index))
            case FlatCollectError.NoWitness(h)      => Collected.NoWitness(h)
            case FlatCollectError.NoCostModel(l)    => Collected.NoCostModel(l)
            case FlatCollectError.BadTranslation(f) => Collected.BadTranslation(f)
          }),
        rs => Right(rs.toList.map(_._2))
      )
    assertEquals(era, flat, s.label)
    era

  /** The typed V1 `TxInfo` of `s` as data, or its failure as the flat one, against the flat path.
    */
  private def sameTxInfo(s: Seen): Either[TranslationError, xyz.kd5ujc.scarab.plutus.PlutusData] =
    val v1 = ctx
      .lookupTxInfoResult[Language.PlutusV1](ctx.mkTxInfoResult(s.lti))
      .unPlutusTxInfoResult
      .left
      .map(AlonzoTestKit.flatContextError)
      .map(ToData[PV1.TxInfo].toBuiltinData)
    assertEquals(v1, ScriptContexts.txInfo(PlutusLanguage.V1, s.flat, s.env), s.label)
    v1

  test(
    "every Alonzo case of the refusal corpora: TxInfos, contexts, scripts and keys are the flat path's"
  ) {
    // the Alonzo cases of the corpora: a loader that loses one fails here
    assertEquals(refused.size, 1 + 4 + 8)
    val outcomes = refused.map { s =>
      val info = sameTxInfo(s)
      val collected = sameCollection(s)
      val ann = FlatStAnnTx(s.flat, Utxo(s.known))
      assertEquals(
        needed(s).map((p, h) => (asIx(p).tag, asIx(p).ix.value, h)),
        ann.needed[Alonzo].map((p, h) => (p.tag, BigInt(p.index), h)),
        s.label
      )
      val empty: ShelleyCertState = Empty.certState[Alonzo]
      val certState = empty.copy(dState =
        empty.dState.copy(genDelegs =
          empty.dState.genDelegs ++ s.genDelegs.map((g, pair) =>
            right("genesis key")(KeyHash.fromBytes[Genesis](g)) -> pair
          )
        )
      )
      assertEquals(
        u.getWitsVKeyNeeded[TxLevel.Top](certState, s.utxo, t.body[TxLevel.Top](s.era)),
        BabbageTestKit.flatWitsNeeded(s.flat, s.known, s.genDelegs),
        s.label
      )
      val balance =
        AlonzoTestKit.sameBalance(s.era, s.flat, s.known, s.params, s.cert, s.label)
      (info, collected, needed(s).size, balance)
    }
    // what the cases reach: 13 TxInfos, every one built; three requests, no failure to collect, and
    // three scripts needed
    val infos = outcomes.map(_._1)
    assertEquals(
      (
        infos.count(_.isRight),
        infos.collect { case Left(e) => e.productPrefix }.groupMapReduce(identity)(_ => 1)(_ + _)
      ),
      (13, Map.empty[String, Int])
    )
    assertEquals(
      (
        outcomes.map(_._2).collect { case Right(rs) => rs.size }.sum,
        outcomes.map(_._2).collect { case Left(es) => es.map(_.productPrefix) }.flatten
      ),
      (3, Vector.empty[String])
    )
    assertEquals(outcomes.map(_._3).sum, 3)
    // the value consumed and produced and the minimum fee, against the state's deposits and pools:
    // 12 cases balanced, and one whose fee is below the minimum
    assertEquals(
      (
        outcomes.count(o => o._4._1 == o._4._2),
        refused.zip(outcomes).count((s, o) => o._4._3 > s.flat.body.fee)
      ),
      (12, 1)
    )
    // the genesis delegations are in force
    assert(refused.exists(_.genDelegs.nonEmpty))
  }

  // ---- the forecast-horizon corpus's Alonzo cases, with the clock of their era history

  /** The reference's verdict on each case of [[horizon]], in its order. */
  private lazy val horizonAccepted: Vector[Boolean] =
    val dir = Path.of("fixtures/reference/plutus-forecast-horizon/alonzo-horizon/cases")
    Files.list(dir).iterator.asScala.toVector.sorted.map { file =>
      val json = parse(Files.readString(file)).fold(e => fail(e.message), identity)
      field[Boolean](json, "accepted")
    }

  private lazy val horizon: Vector[Seen] =
    val root = Path.of("fixtures/reference/plutus-forecast-horizon")
    Vector("alonzo-horizon").flatMap { name =>
      val dir = root.resolve(name)
      val setup = right(s"$name genesis")(
        xyz.kd5ujc.scarab.node.NodeSetup
          .fromDirectory(xyz.kd5ujc.scarab.protocol.ReferenceBuild.Default)(dir.resolve("genesis"))
      )
      Files.list(dir.resolve("cases")).iterator.asScala.toVector.sorted.map { file =>
        val json = parse(Files.readString(file)).fold(e => fail(e.message), identity)
        val label = s"$name/${field[String](json, "label")}"
        def bytes(key: String) =
          Bytes.fromArray(Files.readAllBytes(dir.resolve(field[String](json, key))))
        val item = hex(field[String](json, "item"))
        val raw = right(s"$label item")(LocalSubmission.unwrap(item)).raw
        val nes: NewEpochState[Alonzo] =
          right(s"$label state")(HaskellState.decode[Alonzo](bytes("state")).leftMap(_.render))
        val tip = right(s"$label tip")(json.hcursor.downField("tip").get[BigInt]("slot"))
        val clock = PastHorizonReferenceSuite.clockAt(setup, Era.Alonzo, nes.epoch.toBigInt, tip)
        Seen(
          label,
          right(s"$label flat")(FlatTx.fromEnvelope(raw, Era.Alonzo)),
          right(s"$label typed")(AlonzoTx.fromEnvelope(raw, StateLayout.Node11_1)),
          right(s"$label UTxO")(UtxoCodecs.utxo.decodeBytes(bytes("utxo"))),
          nes.es.ls.utxo.gov.curPParams,
          nes.es.ls.cert.dState.genDelegs.map((g, pair) => g.bytes -> pair),
          nes.es.ls.cert,
          clock.posix
        )
      }
    }

  test(
    "the Alonzo cases of the forecast-horizon corpus: contexts and collections are the flat path's, and the reference's verdicts"
  ) {
    val outcomes = horizon.map { s =>
      sameTxInfo(s)
      (s.label, sameCollection(s))
    }
    // the reference accepted both
    assertEquals(horizonAccepted, Vector(true, true))
    // both collected, as the reference accepted both: Alonzo's epoch information extends the era
    // history past the horizon (the corpus's README)
    assertEquals(
      outcomes.map((label, o) =>
        label -> o.fold(_.map(_.productPrefix), rs => rs.map(_ => "request"))
      ),
      Vector(
        "alonzo-horizon/plutus-upper-bound-inside-horizon-alonzo" -> List("request"),
        "alonzo-horizon/plutus-upper-bound-past-horizon-alonzo" -> List("request")
      )
    )
  }

  // ---- the context matrix's Alonzo rows, against the reference's bytes

  private def matrixRow(row: Json): Seen =
    val name = field[String](row, "name")
    val parts = Cbor
      .decode(hex(field[String](row, "transaction")))
      .fold(e => fail(e.toString), identity)
      .value match
      case CborValue.Arr(Vector(body, witnesses, flag, auxiliary)) =>
        val valid = flag.value match
          case CborValue.Bool(value) => value
          case other                 => fail(s"not a validity flag: $other")
        Block.Transaction(
          body.original,
          witnesses.original,
          Option.unless(auxiliary.value == CborValue.Null)(auxiliary.original),
          valid
        )
      case other => fail(s"not a complete transaction: $other")
    val known = field[Vector[Json]](row, "utxo").map { entry =>
      right("input")(
        TxIn.decode(right("input")(Cbor.decode(hex(field[String](entry, "input")))))
      ) ->
        right("output")(FlatTxOut.fromBytes(hex(field[String](entry, "output"))))
    }.toMap
    val start = BigInt(field[Int](row, "systemStartSeconds")) * 1000
    val length = BigInt(field[Int](row, "slotLengthMilliseconds"))
    Seen(
      name,
      right(name)(FlatTx.fromBlock(parts, Era.Alonzo)),
      right(name)(AlonzoTx.decode(parts, StateLayout.Node11_1)),
      known,
      TestLedger
        .params(field[Int](row, "protocolMajor"))
        .copy(costModels =
          field[Map[String, Vector[String]]](row, "costModels")
            .map((language, values) => language.toInt -> values.map(BigInt(_)))
        ),
      Map.empty,
      Empty.certState[Alonzo],
      slot => Right(start + slot * length)
    )

  test("each Alonzo row of the context matrix: the reference's request bytes, each of them") {
    val rows = parse(Files.readString(Path.of("fixtures/reference/context-matrix/matrix.json")))
      .fold(e => fail(e.message), identity)
      .asArray
      .getOrElse(fail("not an array"))
      .filter(field[String](_, "era") == "Alonzo")
    val requests = rows.flatMap { row =>
      val s = matrixRow(row)
      val oracle = field[Json](row, "oracle")
      assert(field[Boolean](oracle, "collected"), s.label)
      val pwcs = right(s.label)(
        AlonzoPlutusEvaluate.collectPlutusScriptsWithContext[Alonzo](s.posix, s.pp, s.era, s.utxo)
      )
      val expected = field[Vector[Json]](oracle, "scripts")
      assertEquals(pwcs.size, expected.size, s.label)
      pwcs.zip(expected).map { (pwc, reference) =>
        val ours = PlutusFailureCbor.context(PlutusWithContext.evalRequest(pwc))
        val theirs = hex(field[String](reference, "contextCbor"))
        assertEquals(ours, theirs, s.label)
        // a byte of the reference's changed is told apart
        val mutant =
          Bytes(theirs.value.updated(theirs.size / 2, (theirs.value(theirs.size / 2) ^ 1).toByte))
        assertNotEquals(ours, mutant, s.label)
        s.label
      }
    }
    // 20 rows, all collected: one request each, four of each all-purposes row
    assertEquals((rows.size, requests.size), (20, 18 + 2 * 4))
  }

  // ---- the recorded redeemer-index transactions, with the outputs they spend

  /** One Alonzo transaction of `fixtures/devnet/redeemer-index.jsonl`: the case, the transaction
    * with the outputs it spends and gives as collateral, the Plutus V1 cost model and protocol
    * version in force, and the reference's verdict, a refusal with its `ApplyTxErr` bytes.
    */
  private final case class Indexed(seen: Seen, kind: String, reason: Option[Bytes])

  private def storedOutput(text: String): FlatTxOut =
    right("output")(
      right("output CBOR")(Cbor.decode(hex(text))).pipe(node =>
        FlatTxOut.decode(
          node,
          xyz.kd5ujc.scarab.ledger.Address.Mode.Rigorous,
          FlatTxOut.Forms.Stored,
          xyz.kd5ujc.scarab.ledger.ScriptKind.newestKnown
        )
      )
    )

  private lazy val redeemerIndex: Vector[Indexed] =
    Files
      .readAllLines(Path.of("fixtures/devnet/redeemer-index.jsonl"))
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
      .map(line => parse(line).fold(e => fail(e.message), identity))
      .filter(field[String](_, "era") == "alonzo")
      .map { json =>
        val label = field[String](json, "label")
        val raw = hex(field[String](json, "tx"))
        val major = right(s"$label version")(
          json.hcursor.downField("protocol").downField("protocolVersion").get[Int]("major")
        )
        val known = field[Map[String, String]](json, "spent").map { (key, out) =>
          key.split('#') match
            case Array(id, ix) => TxIn(hex(id), BigInt(ix)) -> storedOutput(out)
            case _             => fail(s"$label: input $key")
        }
        val accepted = field[String](json, "verdict") match
          case "accepted" => true
          case "refused"  => false
          case other      => fail(s"$label: verdict $other")
        val reason = json.hcursor.get[String]("reason").toOption.map(hex)
        assertEquals(reason.isEmpty, accepted, label)
        Indexed(
          Seen(
            label,
            right(s"$label flat")(FlatTx.fromEnvelope(raw, Era.Alonzo)),
            right(s"$label typed")(AlonzoTx.fromEnvelope(raw, StateLayout.Node11_1)),
            known,
            TestLedger
              .params(major)
              .copy(costModels = Map(0 -> field[Vector[BigInt]](json, "costModelPlutusV1"))),
            Map.empty,
            Empty.certState[Alonzo],
            clock
          ),
          label.stripPrefix("alonzo-"),
          reason
        )
      }

  /** The flat path's failures to collect `s` 's scripts, none where it collects them. */
  private def flatCollectErrors(s: Seen): Vector[FlatCollectError] =
    Phase2
      .collect[Alonzo](
        s.flat,
        s.params,
        s.flat.witnesses.scriptsByHash,
        s.spent,
        Map.empty,
        s.posix
      )
      .fold(_.toVector, _ => Vector.empty)

  test(
    "the recorded redeemer-index transactions: the collections are the flat path's, and the reference's failures"
  ) {
    val outcomes = redeemerIndex.map { r =>
      val collected = sameCollection(r.seen)
      sameTxInfo(r.seen)
      // a failure to collect is among the reference's failures, as UTXOS's `CollectErrors`, byte for
      // byte
      NonEmptyVector.fromVector(flatCollectErrors(r.seen)).foreach { errors =>
        val utxos = ContextFailureCbor
          .utxos(ContextFailureCbor.ContextEra.Alonzo, errors)
          .getOrElse(fail(s"${r.kind}: no encoding"))
        val reason = r.reason.getOrElse(fail(s"${r.kind}: accepted, though not collected"))
        assert(
          reason.hex.sliding(utxos.hex.length, 2).contains(utxos.hex),
          s"${r.kind}: ${utxos.hex} not in ${reason.hex}"
        )
      }
      r.kind -> collected.fold(
        _.map {
          case Collected.NoRedeemer(tag, ix) => (tag, ix)
          case Collected.NoWitness(_) | Collected.NoCostModel(_) | Collected.BadTranslation(_) =>
            fail(s"${r.kind}: not a missing redeemer")
        },
        requests => List.fill(requests.size)((-1, BigInt(-1)))
      )
    }
    // each case's missing redeemers by tag and index, in the reference's order (minting, certifying,
    // withdrawing, spending), and a collected script as (-1, -1): the two repeated certificates
    // share the first one's index, so a redeemer at index 0 serves both, and one at index 1 is
    // UTXOW's extra redeemer, not a failure to collect
    assertEquals(
      outcomes.toMap,
      Map(
        "missing-redeemers-every-purpose" -> List(
          (1, BigInt(0)),
          (2, BigInt(0)),
          (3, BigInt(0)),
          (0, BigInt(0))
        ),
        "repeated-certificate-redeemers-0-1" -> List((-1, BigInt(-1)), (-1, BigInt(-1))),
        "repeated-certificate-no-redeemer" -> List((2, BigInt(0)), (2, BigInt(0))),
        "distinct-certificates-redeemer-0" -> List((2, BigInt(1))),
        "repeated-certificate-redeemer-0" -> List((-1, BigInt(-1)), (-1, BigInt(-1)))
      )
    )
    // the one the reference accepted
    assertEquals(
      redeemerIndex.filter(_.reason.isEmpty).map(_.kind),
      Vector("repeated-certificate-redeemer-0")
    )
  }
