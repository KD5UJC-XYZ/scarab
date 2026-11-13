// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra
import xyz.kd5ujc.scarab.plutus.*
import xyz.kd5ujc.scarab.protocol.Era

/** Both Plutus backends (ADR 0027) on the recorded script contexts of protocols 5 to 11 the
  * repository has: the recorded Preview and preprod transactions (`fixtures/preview/plutus`,
  * `fixtures/preprod/plutus`, which the rule suites of those networks replay), and the transactions
  * of the reference corpora that the ledger and node suites judge (`v1-context`, `context-errors`,
  * `script-results`, `context-matrix`, `spending-datum`, `plutus-boundaries` and
  * `plutus-boundary-transactions`). Each script request the ledger collects for them is evaluated
  * by the Scalus backend and by the own evaluator, which must give the same verdict, the same
  * failure class, and on success the same execution units and logs. Not compared: the Leios
  * testnet's contexts (`leios-testnet-contexts`, protocol 12, on which the own evaluator gives no
  * verdict by design), and the recorded blocks without the outputs they spend (the block `.hex`
  * files of Preview and preprod), which give no script context.
  *
  * The Scalus backend shows disagreement, not truth: a disagreement is diagnosed against the
  * reference, never resolved in favour of either side. No verdict from either backend is reported
  * apart and is not agreement. The two share the request (the script context, cost model, budget
  * and arguments the ledger builds, ADR 0027's amendment), so a fault there is invisible here; the
  * suites that compare with the Haskell oracle's results (`ScriptResultOracleSuite`,
  * `BoundaryTransactionsSuite`, `V1ContextSuite`, `ContextMatrixSuite`) cover it.
  */
class PlutusBackendDifferentialSuite extends FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")

  private val scalus = ScalusEvaluator.default
  private val scarab = ScarabEvaluator.default

  /** A recorded request, named by where it came from. */
  private final case class Recorded(id: String, request: EvalRequest)

  private def files(directory: String): Vector[Path] =
    val path = Path.of(directory)
    // committed fixtures: a missing directory fails rather than leaving no requests to compare
    if !Files.isDirectory(path) then fail(s"missing committed fixture directory $directory")
    val stream = Files.list(path)
    val all = stream.iterator.asScala.toVector
    stream.close()
    all.filter(_.toString.endsWith(".json")).sortBy(_.getFileName.toString)

  private def scriptsOf(c: PlutusCase): Map[Bytes, Script] =
    (c.spent.values ++ c.references.values)
      .flatMap(_.referenceScript)
      .map(s => s.hash -> s)
      .toMap ++ c.tx.witnesses.scriptsByHash

  private def requestsOf(id: String, c: PlutusCase): Vector[Recorded] =
    Phase2
      .collect(c.tx, c.params, scriptsOf(c), c.spent, c.references, c.posix)(using
        TestLedger.usualEra(c.params.protocolMajor.toInt)
      )
      .fold(
        _ => Vector.empty,
        _.zipWithIndex.map { case ((redeemer, request), n) =>
          Recorded(s"$id redeemer ${redeemer.tag}.${redeemer.index} (#$n)", request)
        }
      )

  /** The cases of a directory: one case a file, or an array of them; a file that is neither (a
    * corpus's manifest) holds none.
    */
  private def cases(directory: String): Vector[(String, PlutusCase)] =
    files(directory).flatMap { path =>
      val json = parse(Files.readString(path)).fold(e => fail(s"$path: ${e.message}"), identity)
      json.asArray match
        case Some(all) => all.zipWithIndex.map((j, n) => s"$path[$n]" -> PlutusCase.fromJson(j))
        case None if json.hcursor.downField("body").succeeded =>
          Vector(path.toString -> PlutusCase.fromJson(json))
        case None => Vector.empty
    }

  private lazy val recorded: Vector[Recorded] =
    val plutusCases = Vector(
      "fixtures/preview/plutus",
      "fixtures/preprod/plutus",
      "fixtures/reference/v1-context",
      "fixtures/reference/context-errors/submissions",
      "fixtures/reference/plutus-boundaries/transactions",
      "fixtures/reference/plutus-boundary-transactions/transactions",
      "fixtures/reference/spending-datum"
    ).flatMap(cases).flatMap(requestsOf)
    val collections = Vector(
      "fixtures/reference/context-errors/collection.json",
      "fixtures/reference/script-results/aggregation.json"
    ).flatMap { file =>
      parse(Files.readString(Path.of(file)))
        .fold(e => fail(e.message), identity)
        .asArray
        .getOrElse(fail(s"$file: not an array"))
        .map(ContextReferenceCases.Recorded(_))
        .flatMap { c =>
          c.collect.fold(
            _ => Vector.empty,
            _.zipWithIndex.map { case ((r, request), n) =>
              Recorded(s"$file ${c.name} redeemer ${r.tag}.${r.index} (#$n)", request)
            }
          )
        }
    }
    plutusCases ++ collections ++ matrix

  /** The rows of the context matrix (`fixtures/reference/context-matrix`, `ContextMatrixSuite`):
    * Alonzo, Babbage and Conway transactions with the outputs they read.
    */
  private def matrix: Vector[Recorded] =
    import ContextReferenceCases.{field, hex}
    val file = "fixtures/reference/context-matrix/matrix.json"
    parse(Files.readString(Path.of(file)))
      .fold(e => fail(e.message), identity)
      .asArray
      .getOrElse(fail(s"$file: not an array"))
      .flatMap { row =>
        val name = field[String](row, "name")
        val era = field[String](row, "era") match
          case "Alonzo"  => Era.Alonzo
          case "Babbage" => Era.Babbage
          case "Conway"  => Era.Conway
          case other     => fail(s"$name: unsupported era $other")
        val tx = CborRead
          .decode(hex(field[String](row, "transaction")))
          .fold(e => fail(e.toString), identity)
          .value match
          case CborValue.Arr(Vector(body, witnesses, flag, auxiliary)) =>
            val valid = flag.value match
              case CborValue.Bool(value) => value
              case _                     => fail(s"$name: not a validity flag")
            Tx.fromBlock(
              Block.Transaction(
                body.original,
                witnesses.original,
                Option.unless(auxiliary.value == CborValue.Null)(auxiliary.original),
                valid
              ),
              era
            ).fold(e => fail(e.toString), identity)
          case _ => fail(s"$name: not a complete transaction")
        val known = field[Vector[io.circe.Json]](row, "utxo").map { entry =>
          CborRead
            .decode(hex(field[String](entry, "input")))
            .flatMap(TxIn.decode)
            .fold(e => fail(e.toString), identity) ->
            TxOut
              .fromBytes(hex(field[String](entry, "output")))
              .fold(e => fail(e.toString), identity)
        }.toMap
        val spent = known.filter((input, _) => tx.body.inputs.contains(input))
        val references = known.filter((input, _) => tx.body.referenceInputs.contains(input))
        val scripts = (spent.values ++ references.values)
          .flatMap(_.referenceScript)
          .map(script => script.hash -> script)
          .toMap ++ tx.witnesses.scriptsByHash
        val models = field[Map[String, Vector[String]]](row, "costModels")
          .map((language, values) => language.toInt -> values.map(BigInt(_)))
        val params = TestLedger.params(field[Int](row, "protocolMajor")).copy(costModels = models)
        val start = BigInt(field[Int](row, "systemStartSeconds")) * 1000
        val length = BigInt(field[Int](row, "slotLengthMilliseconds"))
        // the era the row was recorded in, whatever its protocol version
        val witness: ShelleyBasedEra[?] = era match
          case Era.Alonzo  => ShelleyBasedEra.SAlonzo
          case Era.Babbage => ShelleyBasedEra.SBabbage
          case Era.Conway  => ShelleyBasedEra.SConway
          case other       => fail(s"$name: unsupported era $other")
        Phase2
          .collect(tx, params, scripts, spent, references, slot => Right(start + slot * length))(
            using witness
          )
          .fold(
            _ => Vector.empty,
            _.zipWithIndex.map { case ((r, request), n) =>
              Recorded(s"$file $name redeemer ${r.tag}.${r.index} (#$n)", request)
            }
          )
      }

  /** The class of a result: success, or the case of the shared algebra it failed with. */
  private def kind(result: EvalResult): String = result match
    case EvalResult.Success(_, _) => "success"
    case EvalResult.Failure(reason, _, _) =>
      reason match
        case EvalFailure.Decode(_)      => "decode"
        case EvalFailure.Unsupported(_) => "no verdict"
        case EvalFailure.OutOfBudget    => "budget"
        case EvalFailure.Script(_)      => "script"
        case EvalFailure.OpenTerm(_)    => "scope"

  /** How the two results of a request differ, if they do. */
  private enum Difference:
    case NoVerdict(theirs: EvalResult, ours: EvalResult)
    case Kind(theirs: EvalResult, ours: EvalResult)
    case Units(theirs: ExUnits, ours: ExUnits)
    case Logs(theirs: Vector[String], ours: Vector[String])

  private def compare(theirs: EvalResult, ours: EvalResult): Option[Difference] =
    (theirs, ours) match
      case (EvalResult.Failure(_: EvalFailure.Unsupported, _, _), _) |
          (_, EvalResult.Failure(_: EvalFailure.Unsupported, _, _)) =>
        Some(Difference.NoVerdict(theirs, ours))
      case _ if kind(theirs) != kind(ours) => Some(Difference.Kind(theirs, ours))
      case (EvalResult.Success(t, tl), EvalResult.Success(o, ol)) =>
        if t != o then Some(Difference.Units(t, o))
        else Option.when(tl != ol)(Difference.Logs(tl, ol))
      case _ => None

  private lazy val compared: Vector[(Recorded, EvalResult, Option[Difference])] =
    recorded.map { r =>
      val theirs = scalus.evaluate(r.request)
      val ours = scarab.evaluate(r.request)
      (r, ours, compare(theirs, ours))
    }

  test("the fixtures hold script requests of every language and both verdicts") {
    assert(recorded.size >= 100, s"only ${recorded.size} requests")
    val languages = recorded.map(_.request.language).toSet
    assert(
      Set(PlutusLanguage.V1, PlutusLanguage.V2, PlutusLanguage.V3).subsetOf(languages),
      languages.toString
    )
    val kinds = compared.map((_, ours, _) => kind(ours)).groupMapReduce(identity)(_ => 1)(_ + _)
    assert(kinds.contains("success") && kinds.size > 1, kinds.toString)
    val byLanguage = recorded.groupMapReduce(_.request.language)(_ => 1)(_ + _)
    println(
      s"plutus backend differential: ${recorded.size} requests " +
        s"(${byLanguage.toVector.sortBy(_._1.id).map((l, n) => s"$l $n").mkString(", ")}), " +
        s"own results ${kinds.toVector.sorted.map((k, n) => s"$k $n").mkString(", ")}, " +
        s"${compared.count(_._3.isDefined)} differences"
    )
  }

  test("both backends give every request a verdict") {
    val missing = compared.collect { case (r, _, Some(d: Difference.NoVerdict)) => s"${r.id}: $d" }
    assertEquals(missing, Vector.empty, s"${missing.size} of ${recorded.size} requests")
  }

  test("the same verdict and failure class, and on success the same units, for every request") {
    val differing = compared.collect {
      case (r, _, Some(d @ (_: Difference.Kind | _: Difference.Units))) => s"${r.id}: $d"
    }
    assertEquals(differing, Vector.empty, s"${differing.size} of ${recorded.size} requests")
  }

  test("on success the same logs, in order") {
    val differing = compared.collect { case (r, _, Some(d: Difference.Logs)) => s"${r.id}: $d" }
    assertEquals(differing, Vector.empty, s"${differing.size} of ${recorded.size} requests")
  }
