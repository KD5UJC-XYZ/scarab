// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.data.NonEmptyVector
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra
import xyz.kd5ujc.scarab.plutus.{EvalRequest, PlutusData, PlutusLanguage}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.uplc.builtin.core.DataSerialisation

/** The script contexts the node builds for the recorded Preview and preprod transactions, and for
  * Conway transactions with several voters, against the pinned Haskell collection
  * (`collectPlutusScriptsWithContext`, cardano-ledger f649f975, cardano-node 11.1.3) at each
  * transaction's own era and protocol version. `fixtures/reference/recorded-contexts` holds what
  * `scripts/oracle/plutus/recorded-contexts` measured through the unchanged context-matrix
  * observer: each collected request as `ToCBOR PlutusWithContext` writes it
  * (`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Evaluate.hs:186-193`), that is the protocol
  * version, the language and script, the arguments (a list for PlutusV1 and PlutusV2, the script
  * context alone for PlutusV3), the budget and the cost model; or the collection's refusal. The
  * node must make the same requests in the same order, every argument byte for byte as
  * `serialiseData` writes it, and refuse where the reference refuses, for the same purposes in the
  * same order. A recorded transaction is read from its recorded file, which the fixture binds by
  * its hash; a recorded case runs on its recorded slot clock, the reference on the network's. The
  * requests of one transaction too large to keep whole are retained as digests: each argument and
  * the script by the SHA-256 of its bytes, the other fields as they are.
  */
class RecordedScriptContextsSuite extends FunSuite:
  private val root = Path.of("fixtures/reference/recorded-contexts")

  private val rows: Vector[Json] =
    parse(Files.readString(root.resolve("contexts.json")))
      .fold(e => fail(e.message), identity)
      .asArray
      .getOrElse(fail("contexts.json is not an array"))

  private def field[A: Decoder](j: Json, name: String): A =
    j.hcursor.get[A](name).fold(e => fail(s"$name: ${e.getMessage}"), identity)

  private def hex(text: String): Bytes = Bytes.fromHex(text).fold(e => fail(e.toString), identity)

  private def cbor(bytes: Bytes): Node = Cbor.decode(bytes).fold(e => fail(e.toString), identity)

  private def sha256(bytes: Bytes): String =
    Bytes.wrap(MessageDigest.getInstance("SHA-256").digest(bytes.toArray)).hex

  private def sha256(path: Path): String = sha256(Bytes.wrap(Files.readAllBytes(path)))

  private def named(group: String): Vector[Json] =
    rows.filter(field[String](_, "name").startsWith(s"$group/"))

  /** What the node collects from. */
  private final case class Input(
      tx: Tx,
      params: ProtocolParams,
      spent: Map[TxIn, TxOut],
      references: Map[TxIn, TxOut],
      posix: BigInt => Either[TranslationError, BigInt]
  ):
    def scripts: Map[Bytes, Script] =
      (spent.values ++ references.values)
        .flatMap(_.referenceScript)
        .map(script => script.hash -> script)
        .toMap ++ tx.witnesses.scriptsByHash

  private def provenance(row: Json): Json = field[Json](row, "provenance")

  /** A recorded case, from the file the fixture names and binds by its hash. */
  private def recorded(row: Json): Input =
    val path = Path.of(field[String](provenance(row), "sourcePath"))
    assertEquals(sha256(path), field[String](provenance(row), "sourceFileSha256"), s"$path")
    val c = PlutusCase.load(path)
    assertEquals(c.protocolMajor, field[Int](row, "protocolMajor"))
    Input(c.tx, c.params, c.spent, c.references, c.posix)

  /** A derived transaction, retained whole in the fixture with the outputs it reads. */
  private def derived(row: Json, era: Era): Input =
    val tx = cbor(hex(field[String](row, "transaction"))).value match
      case Value.Arr(Vector(body, witnesses, flag, auxiliary)) =>
        val valid = flag.value match
          case Value.Bool(value) => value
          case other             => fail(s"not a validity flag: $other")
        Tx.fromBlock(
          Block.Transaction(
            body.original,
            witnesses.original,
            Option.unless(auxiliary.value == Value.Null)(auxiliary.original),
            valid
          ),
          era
        ).fold(e => fail(e.toString), identity)
      case other => fail(s"not a complete transaction: $other")
    val known = field[Vector[Json]](row, "utxo").map { entry =>
      TxIn
        .decode(cbor(hex(field[String](entry, "input"))))
        .fold(e => fail(e.toString), identity) ->
        TxOut.fromBytes(hex(field[String](entry, "output"))).fold(e => fail(e.toString), identity)
    }.toMap
    val models = field[Map[String, Vector[String]]](row, "costModels")
      .map((language, values) => language.toInt -> values.map(BigInt(_)))
    val start = BigInt(field[Int](row, "systemStartSeconds")) * 1000
    val length = BigInt(field[Int](row, "slotLengthMilliseconds"))
    Input(
      tx,
      TestLedger.params(field[Int](row, "protocolMajor")).copy(costModels = models),
      known.filter((input, _) => tx.body.inputs.contains(input)),
      known.filter((input, _) => tx.body.referenceInputs.contains(input)),
      slot => Right(start + slot * length)
    )

  private def era(row: Json): (Era, ShelleyBasedEra[?]) =
    field[String](row, "era") match
      case "Babbage" => (Era.Babbage, ShelleyBasedEra.SBabbage)
      case "Conway"  => (Era.Conway, ShelleyBasedEra.SConway)
      case other     => fail(s"no recorded case of era $other")

  private def input(row: Json): Input =
    field[String](provenance(row), "kind") match
      case "recorded-chain-transaction"   => recorded(row)
      case "api-only-derived-transaction" => derived(row, era(row)._1)
      case other                          => fail(s"unknown case kind $other")

  private def collect(row: Json): Phase2.Collected[Vector[(Redeemer, EvalRequest)]] =
    val in = input(row)
    Phase2.collect(in.tx, in.params, in.scripts, in.spent, in.references, in.posix)(using
      era(row)._2
    )

  /** Equal bytes, or a failure naming the first byte that differs and the bytes around it. */
  private def sameBytes(ours: Bytes, theirs: Bytes, at: String): Unit =
    if ours != theirs then
      val first = ours.value.zip(theirs.value).indexWhere(_ != _) match
        case -1 => math.min(ours.size, theirs.size)
        case i  => i
      def around(b: Bytes) = b.slice(math.max(0, first - 8), math.min(b.size, first + 24)).hex
      fail(
        s"$at: first difference at byte $first of ${ours.size} (node) and ${theirs.size} " +
          s"(reference); from byte ${math.max(0, first - 8)}: node ${around(ours)}, " +
          s"reference ${around(theirs)}"
      )

  private def integer(node: Node): BigInt = node.value match
    case Value.UInt(value) => value
    case Value.NInt(value) => value
    case other             => fail(s"not an integer: $other")

  /** A request of the reference: its `PlutusWithContext` encoding, or that encoding's digests. */
  private enum Reference:
    case Whole(encoding: Bytes)
    case Digested(request: Json)

  private def reference(script: Json): Reference =
    script.hcursor.downField("contextCbor").focus.flatMap(_.asString) match
      case Some(encoding) => Reference.Whole(hex(encoding))
      case None           => Reference.Digested(field[Json](script, "request"))

  private def sameRequest(ours: EvalRequest, theirs: Reference, at: String): Unit = theirs match
    case Reference.Whole(encoding)   => sameEncoding(ours, encoding, at)
    case Reference.Digested(request) => sameDigests(ours, request, at)

  /** The node's request against the digests of the reference's: every field, each argument and the
    * script by the SHA-256 of its bytes.
    */
  private def sameDigests(ours: EvalRequest, theirs: Json, at: String): Unit =
    assertEquals(
      BigInt(ours.protocolMajor),
      field[BigInt](theirs, "protocolMajor"),
      s"$at: version"
    )
    assertEquals(BigInt(ours.language.id), field[BigInt](theirs, "language"), s"$at: language")
    assertEquals(sha256(ours.script), field[String](theirs, "scriptSha256"), s"$at: script")
    val expected = field[Vector[String]](theirs, "argumentsSha256")
    assertEquals(ours.args.size, expected.size, s"$at: number of arguments")
    ours.args.zip(expected).zipWithIndex.foreach { case ((argument, digest), i) =>
      assertEquals(
        sha256(DataSerialisation.serialise(argument)),
        digest,
        s"$at: argument $i, the SHA-256 of its bytes"
      )
    }
    val budget = field[Json](theirs, "budget")
    assertEquals(
      (ours.budget.mem, ours.budget.steps),
      (field[BigInt](budget, "mem"), field[BigInt](budget, "steps")),
      s"$at: budget"
    )
    assertEquals(
      ours.costModel.map(BigInt(_)),
      field[Vector[BigInt]](theirs, "costModel"),
      s"$at: cost model"
    )

  /** The node's request against the reference's `PlutusWithContext` encoding. */
  private def sameEncoding(ours: EvalRequest, theirs: Bytes, at: String): Unit =
    cbor(theirs).value match
      case Value.Arr(Vector(version, script, arguments, budget, model)) =>
        assertEquals(integer(version), BigInt(ours.protocolMajor), s"$at: protocol version")
        script.value match
          case Value.Arr(Vector(language, bytes)) =>
            assertEquals(integer(language), BigInt(ours.language.id), s"$at: language")
            assertEquals(bytes.value, Value.ByteString(ours.script), s"$at: script")
          case other => fail(s"$at: not a script: $other")
        val expected = ours.language match
          case PlutusLanguage.V1 | PlutusLanguage.V2 =>
            arguments.value match
              case Value.Arr(items) => items.map(_.original)
              case other            => fail(s"$at: not an argument list: $other")
          case PlutusLanguage.V3 | PlutusLanguage.V4 => Vector(arguments.original)
        assertEquals(ours.args.size, expected.size, s"$at: number of arguments")
        ours.args.zip(expected).zipWithIndex.foreach { case ((argument, reference), i) =>
          sameBytes(DataSerialisation.serialise(argument), reference, s"$at: argument $i")
        }
        budget.value match
          case Value.Arr(Vector(mem, steps)) =>
            assertEquals((integer(mem), integer(steps)), (ours.budget.mem, ours.budget.steps), at)
          case other => fail(s"$at: not a budget: $other")
        model.value match
          case Value.Arr(values) =>
            assertEquals(values.map(integer), ours.costModel.map(BigInt(_)), s"$at: cost model")
          case other => fail(s"$at: not a cost model: $other")
      case other => fail(s"$at: not a PlutusWithContext: $other")

  private def kind(error: CollectError): String = error match
    case CollectError.NoRedeemer(_)     => "NoRedeemer"
    case CollectError.NoWitness(_)      => "NoWitness"
    case CollectError.NoCostModel(_)    => "NoCostModel"
    case CollectError.BadTranslation(_) => "BadTranslation"

  /** A voter as the ledger encodes it, its kind and hash (`EncCBOR Voter`,
    * `eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Procedures.hs:365-376`, f649f975).
    */
  private def encoded(voter: Voter): (BigInt, Bytes) = voter match
    case Voter.CommitteeHot(Credential.Key(h))    => (BigInt(0), h)
    case Voter.CommitteeHot(Credential.Script(h)) => (BigInt(1), h)
    case Voter.DRepVoter(Credential.Key(h))       => (BigInt(2), h)
    case Voter.DRepVoter(Credential.Script(h))    => (BigInt(3), h)
    case Voter.Pool(h)                            => (BigInt(4), h)

  /** The node's refusal against the reference's: the same kinds in the same order, and each missing
    * redeemer's purpose the reference's (`[0, purpose]`, a purpose `[tag, item]`).
    */
  private def sameRefusal(errors: NonEmptyVector[CollectError], expected: Json): Unit =
    assertEquals(errors.toVector.map(kind), field[Vector[String]](expected, "failureKinds"))
    val reference = cbor(hex(field[String](expected, "collectErrorsCbor"))).value match
      case Value.Arr(items) => items
      case other            => fail(s"not a list of errors: $other")
    errors.toVector.zip(reference).foreach {
      case (CollectError.NoRedeemer(Purpose.Voting(_, voter)), error) =>
        error.value match
          case Value.Arr(Vector(code, purpose)) =>
            assertEquals(integer(code), BigInt(0), "NoRedeemer")
            purpose.value match
              case Value.Arr(Vector(tag, item)) =>
                assertEquals(integer(tag), BigInt(4), "a voting purpose")
                item.value match
                  case Value.Arr(Vector(voterKind, hash)) =>
                    val (k, h) = encoded(voter)
                    assertEquals((integer(voterKind), hash.value), (k, Value.ByteString(h)))
                  case other => fail(s"not a voter: $other")
              case other => fail(s"not a purpose: $other")
          case other => fail(s"not a collection error: $other")
      case (other, _) => fail(s"the fixture has no refusal of this kind: $other")
    }

  test("every recorded Preview and preprod transaction and four several-voter probes") {
    val preview = named("preview").map(field[String](_, "name").stripPrefix("preview/"))
    assertEquals(preview, PlutusCase.all.map(_.name))
    assert(preview.size >= 45, preview.size)
    val preprod = Path.of("fixtures/preprod/plutus")
    val recorded = Files.list(preprod).iterator.asScala.map(_.getFileName.toString).toVector
    assertEquals(
      named("preprod").map(row => Path.of(field[String](provenance(row), "sourcePath"))),
      recorded.filter(_.endsWith(".json")).sorted.map(preprod.resolve)
    )
    assert(named("preprod").size >= 4, named("preprod").size)
    assertEquals(
      named("voters").map(field[String](_, "name")),
      Vector(
        "voters/ord-indices",
        "voters/transaction-order-indices",
        "voters/canonical-order-indices",
        "voters/every-index"
      )
    )
  }

  test("the several-voter probe has script and key voters of each kind, out of Ord Voter order") {
    val row = rows.find(field[String](_, "name") == "voters/ord-indices").getOrElse(fail("missing"))
    val voters = input(row).tx.body.votes.map(_._1)
    assertEquals(voters.map(encoded(_)._1), Vector(4, 2, 3, 0, 1).map(BigInt(_)))
    assertEquals(voters.sorted(using Voter.order.toOrdering), voters.reverse)
    collect(row) match
      case Right(requests) =>
        assertEquals(
          requests.map(_._1).filter(_.tag == 4).map(_.index).sorted,
          Vector(BigInt(0), BigInt(2))
        )
      case Left(errors) => fail(errors.toString)
  }

  rows.foreach { row =>
    val name = field[String](row, "name")
    test(s"$name: the reference's requests, argument for argument, or its refusal") {
      val expected = field[Json](row, "oracle")
      (collect(row), field[Boolean](expected, "collected")) match
        case (Right(requests), true) =>
          val references = field[Vector[Json]](expected, "scripts")
          assertEquals(requests.size, references.size, "number of requests")
          requests.zip(references).zipWithIndex.foreach { case (((redeemer, ours), theirs), i) =>
            sameRequest(
              ours,
              reference(theirs),
              s"request $i (redeemer ${redeemer.tag}.${redeemer.index})"
            )
          }
        case (Left(errors), false) => sameRefusal(errors, expected)
        case (ours, collected) =>
          fail(s"the reference ${if collected then "collected" else "refused"}; the node: $ours")
    }
  }

  /** Whether `needle` is `haystack` or one of its parts, the parts visited from a work list. */
  private def contains(haystack: PlutusData, needle: PlutusData): Boolean =
    @scala.annotation.tailrec
    def go(todo: List[PlutusData]): Boolean = todo match
      case Nil                          => false
      case d :: _ if d == needle        => true
      case PlutusData.Constr(_, f) :: r => go(f.toList ++ r)
      case PlutusData.Map(kvs) :: r     => go(kvs.toList.flatMap((k, v) => List(k, v)) ++ r)
      case PlutusData.List(xs) :: r     => go(xs.toList ++ r)
      case _ :: r                       => go(r)
    go(List(haystack))

  /** The script context of a PlutusV3 request of the reference, from its `PlutusWithContext`. */
  private def v3Context(encoding: Bytes): Option[PlutusData] =
    cbor(encoding).value match
      case Value.Arr(Vector(_, script, argument, _, _)) =>
        script.value match
          case Value.Arr(Vector(language, _))
              if integer(language) == BigInt(PlutusLanguage.V3.id) =>
            Some(PlutusData.fromNode(argument).fold(e => fail(e.toString), identity))
          case _ => None
      case other => fail(s"not a PlutusWithContext: $other")

  test(
    "the recorded parameter changes as the era types show them are in the reference's contexts"
  ) {
    import xyz.kd5ujc.scarab.ledger.era.{PParamsUpdate, ToPlutusData}
    import xyz.kd5ujc.scarab.ledger.era.conway.ConwayPParams
    import xyz.kd5ujc.scarab.ledger.state.Conway
    val seen = (named("preview") ++ named("preprod")).flatMap { row =>
      val name = field[String](row, "name")
      val updates = input(row).tx.body.proposals.map(_.action).collect {
        case GovAction.ParameterChange(_, update, _) => update
      }
      val contexts = field[Vector[Json]](field[Json](row, "oracle"), "scripts")
        .flatMap(s => s.hcursor.downField("contextCbor").focus.flatMap(_.asString))
        .flatMap(h => v3Context(hex(h)))
      updates.map { u =>
        val typed = ToPlutusData[PParamsUpdate[Conway]].toPlutusData(
          PParamsUpdate[Conway](ConwayPParams.fromUpdate(u).fold(e => fail(s"$name: $e"), identity))
        )
        assertNotEquals(typed, PlutusData.Map(Vector.empty), name)
        assert(contexts.nonEmpty, s"$name: no PlutusV3 context")
        contexts.foreach(c => assert(contains(c, typed), s"$name: $typed"))
        name
      }
    }
    assertEquals(seen.size, 2, seen)
  }
