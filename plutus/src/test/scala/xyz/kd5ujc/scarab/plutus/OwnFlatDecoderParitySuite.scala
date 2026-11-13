// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import munit.FunSuite
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scalus.uplc.builtin.{BuiltinValue, ByteString as SByteString}
import scalus.uplc.{Constant as SConstant, DefaultUni as SUni, Term as Uplc}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as Cv}
import xyz.kd5ujc.scarab.uplc.{
  Constant,
  DefaultUni,
  PlutusLanguage as Language,
  Program,
  ProtocolMajor,
  Term,
  TermF
}
import xyz.kd5ujc.scarab.uplc.flat.{Checks, FlatDecoder, SerialisedScript}

/** The own Flat decoder and `deserialiseScript` of the `uplc` module (ADR 0027) against the Scalus
  * backend's admission ([[ScalusEvaluator.pureDecoder]]) and its Flat walker ([[FlatScan.decode]]),
  * over every script the fixtures hold: the reference corpora, the boundary and flat decoding
  * probes, the conformance programs and every Plutus script of the recorded Preview and preprod
  * transactions and blocks. At each language and protocol where the language exists both must give
  * the same verdict, and where both decode the program must be the same. Where the Haskell
  * reference judged a script, both must agree with it, and a disagreement of the Scalus path with
  * it is reported as the Scalus path's.
  *
  * Rules of the comparison: a variable index of 2^31 or more is one Scalus keeps as -1 (with its
  * ordinal in [[FlatScan.wideVariables]]), so the two compare equal when the own decoder's index is
  * that wide and Scalus's is -1; a version component Scalus holds saturated at `Int.MaxValue`. A
  * Scalus admission that is [[ScriptDecodeFailure.Unavailable]] is no verdict and is reported
  * apart. The causes of a refusal are not compared: FlatScan checks the ledger's bounds, values and
  * builtins after its walk, in another order.
  */
class OwnFlatDecoderParitySuite extends FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(15, "min")

  private val scalusDecoder = ScalusEvaluator.pureDecoder
  private val Protocols = 5 to 11

  private def introduced(language: Language): Int = language match
    case Language.V1 => 5
    case Language.V2 => 7
    case Language.V3 => 9
    case Language.V4 => 12

  /** The cells a script runs at: each language it may be, at each protocol of 5 to 11 that has it.
    */
  private def cells(languages: Seq[Language]): Seq[(Language, Int)] =
    for
      language <- languages
      protocol <- Protocols
      if protocol >= introduced(language)
    yield (language, protocol)

  private val Ledger = Vector(Language.V1, Language.V2, Language.V3)

  /** A script as the ledger holds it, where it came from and the languages it may be. */
  private final case class Script(
      id: String,
      source: String,
      bytes: Bytes,
      languages: Seq[Language]
  )

  private def hex(id: String, s: String): Bytes =
    Bytes.fromHex(s).fold(e => fail(s"$id: $e"), identity)

  private def jsonl(file: String): Vector[Json] =
    Files
      .readAllLines(Path.of(file))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(line => io.circe.parser.parse(line).fold(e => fail(s"$file: ${e.message}"), identity))

  private def str(row: Json, name: String): String =
    row.hcursor.get[String](name).fold(e => fail(e.message), identity)

  // --- the scripts -------------------------------------------------------------------------------

  private lazy val corpusScripts: Vector[Script] =
    Vector(
      "fixtures/reference/plutus-costing/scripts.jsonl",
      "fixtures/reference/plutus-boundaries/scripts.jsonl",
      "fixtures/reference/plutus-boundaries/transaction-scripts.jsonl",
      "fixtures/reference/plutus-upstream-boundaries/scripts.jsonl",
      "fixtures/reference/plutus-boundary-transactions/scripts.jsonl"
    ).flatMap { file =>
      jsonl(file).zipWithIndex.flatMap { (row, n) =>
        row.hcursor.get[String]("script").toOption.map { s =>
          val id = row.hcursor.get[String]("id").getOrElse(s"$file:$n")
          Script(id, file, hex(id, s), Ledger)
        }
      }
    }

  private lazy val probeScripts: Vector[Script] =
    FlatDecodeCorpus.probes.map(p => Script(p.id, "flat-decode probes", p.script, Ledger)) ++
      PlutusBoundaryCorpus.probes.map(p => Script(p.id, "boundary probes", p.script, Ledger))

  private lazy val conformanceScripts: Vector[Script] =
    FlatFixtureScripts.conformance.map((id, flat) =>
      Script(id, "conformance", Bytes.wrap(FlatProgram.cborByteString(flat)), Ledger)
    )

  private def decodeCbor(id: String, bytes: Bytes): Node =
    Cbor.decode(bytes, Cbor.Limits.Unbounded).fold(e => fail(s"$id: ${e.message}"), identity)

  private def items(n: Node): Vector[Node] = n.value match
    case Cv.Arr(xs)    => xs
    case Cv.Tag(_, xs) => items(xs)
    case _             => Vector.empty

  private def entries(n: Node): Vector[(Node, Node)] = n.value match
    case Cv.Map(kvs) => kvs
    case _           => Vector.empty

  private def uint(n: Node): Option[BigInt] = n.value match
    case Cv.UInt(v) => Some(v)
    case _          => None

  private def byteString(n: Node): Option[Bytes] = n.value match
    case Cv.ByteString(b) => Some(b)
    case _                => None

  /** The Plutus scripts of a witness set (keys 3, 6 and 7). */
  private def witnessScripts(set: Node): Vector[(Language, Bytes)] =
    entries(set).flatMap { (k, v) =>
      val language = uint(k).collect {
        case n if n == 3 => Language.V1
        case n if n == 6 => Language.V2
        case n if n == 7 => Language.V3
      }
      language.toVector.flatMap(l => items(v).flatMap(byteString).map(l -> _))
    }

  /** The Plutus script of an output's reference script (key 3: `#6.24` around `[language, bytes]`).
    */
  private def referenceScript(id: String, output: Node): Option[(Language, Bytes)] =
    entries(output).collectFirst { case (k, v) if uint(k).contains(BigInt(3)) => v }.flatMap {
      ref =>
        ref.value match
          case Cv.Tag(t, inner) if t == 24 =>
            byteString(inner).flatMap { b =>
              items(decodeCbor(id, b)) match
                case Vector(kind, script) =>
                  val language = uint(kind).collect {
                    case n if n == 1 => Language.V1
                    case n if n == 2 => Language.V2
                    case n if n == 3 => Language.V3
                  }
                  language.flatMap(l => byteString(script).map(l -> _))
                case _ => None
            }
          case _ => None
    }

  private def bodyScripts(id: String, body: Node): Vector[(Language, Bytes)] =
    entries(body)
      .collectFirst { case (k, v) if uint(k).contains(BigInt(1)) => items(v) }
      .toVector
      .flatten
      .flatMap(referenceScript(id, _))

  private def files(directory: String, suffix: String): Vector[Path] =
    val stream = Files.list(Path.of(directory))
    val all = Try(stream.iterator().asScala.toVector).fold(e => fail(e.toString), identity)
    stream.close()
    all.filter(_.getFileName.toString.endsWith(suffix)).sortBy(_.toString)

  /** The recorded Plutus cases: their witness sets and the reference scripts of their outputs. */
  private lazy val recordedCases: Vector[Script] =
    Vector("fixtures/preview/plutus", "fixtures/preprod/plutus")
      .flatMap(files(_, ".json"))
      .flatMap { path =>
        val id = path.toString
        val row = io.circe.parser.parse(Files.readString(path)).fold(e => fail(e.message), identity)
        val outputs = Vector("spent", "references", "collateral")
          .flatMap(name => row.hcursor.downField(name).as[Vector[Json]].getOrElse(Vector.empty))
          .flatMap(o => o.hcursor.get[String]("out").toOption)
        val found = witnessScripts(decodeCbor(id, hex(id, str(row, "witnesses")))) ++
          bodyScripts(id, decodeCbor(id, hex(id, str(row, "body")))) ++
          outputs.flatMap(o => referenceScript(id, decodeCbor(id, hex(id, o))))
        found.zipWithIndex.map { case ((l, b), i) =>
          Script(s"$id#$i", "recorded cases", b, Seq(l))
        }
      }

  /** The recorded transactions and blocks: witness sets and the outputs of their bodies. */
  private lazy val recordedChain: Vector[Script] =
    val transactions =
      files("fixtures/preprod", ".hex").filter(_.getFileName.toString.startsWith("tx-")).flatMap {
        path =>
          val id = path.toString
          val tx = items(decodeCbor(id, hex(id, Files.readString(path).trim)))
          (tx.lift(1).toVector.flatMap(witnessScripts) ++ tx.headOption.toVector.flatMap(
            bodyScripts(id, _)
          ))
            .map((id, _))
      }
    val blocks =
      (files("fixtures/preview", ".hex") ++ files("fixtures/preprod", "blocks.hex")).flatMap {
        path =>
          Files.readAllLines(path).asScala.toVector.filter(_.nonEmpty).zipWithIndex.flatMap {
            (line, n) =>
              val id = s"$path:$n"
              items(decodeCbor(id, hex(id, line.trim))) match
                case Vector(era, block) if uint(era).exists(_ >= 5) =>
                  val parts = items(block)
                  (parts.lift(2).toVector.flatMap(items).flatMap(witnessScripts) ++
                    parts.lift(1).toVector.flatMap(items).flatMap(bodyScripts(id, _))).map((id, _))
                case _ => Vector.empty
          }
      }
    (transactions ++ blocks).zipWithIndex.map { case ((id, (l, b)), i) =>
      Script(s"$id#$i", "recorded chain", b, Seq(l))
    }

  // --- the comparison ----------------------------------------------------------------------------

  private val Wide = BigInt(1) << 31

  /** The first place where the own program and Scalus's differ, walked on the heap. */
  private def difference(ours: Program, theirs: scalus.uplc.DeBruijnedProgram): Option[String] =
    def saturated(n: BigInt): Int = if n.isValidInt then n.toInt else Int.MaxValue
    val (major, minor, patch) = theirs.version
    val version =
      (saturated(ours.version.major), saturated(ours.version.minor), saturated(ours.version.patch))
    if version != (major, minor, patch) then
      Some(s"version ${ours.version.show} against ${theirs.version}")
    else
      @tailrec def go(pending: List[(Term, Uplc)]): Option[String] = pending match
        case Nil => None
        case (o, t) :: rest =>
          (o.unfix, t) match
            case (TermF.Var(i), Uplc.Var(name, _)) =>
              val index = i.index.toBigInt
              if (index >= Wide && name.index == -1) || index == BigInt(name.index) then go(rest)
              else Some(s"variable $index against ${name.index}")
            case (TermF.LamAbs(b), Uplc.LamAbs(_, tb, _))   => go((b, tb) :: rest)
            case (TermF.Delay(b), Uplc.Delay(tb, _))        => go((b, tb) :: rest)
            case (TermF.Force(b), Uplc.Force(tb, _))        => go((b, tb) :: rest)
            case (TermF.Apply(f, a), Uplc.Apply(tf, ta, _)) => go((f, tf) :: (a, ta) :: rest)
            case (TermF.Error, Uplc.Error(_))               => go(rest)
            case (TermF.Builtin(f), Uplc.Builtin(tf, _)) =>
              if f.toString == tf.toString then go(rest) else Some(s"builtin $f against $tf")
            case (TermF.Constant(c), Uplc.Const(tc, _)) =>
              if constant(c) == tc then go(rest) else Some(s"constant $c against $tc")
            case (TermF.Constr(tag, fs), Uplc.Constr(ttag, tfs, _)) =>
              if tag.bits != ttag.value || fs.size != tfs.size then
                Some(s"constr ${tag.show}/${fs.size} against ${ttag.toUnsignedString}/${tfs.size}")
              else go(fs.toList.zip(tfs) ::: rest)
            case (TermF.Case(s, bs), Uplc.Case(ts, tbs, _)) =>
              if bs.size != tbs.size then Some(s"case of ${bs.size} against ${tbs.size}")
              else go((s, ts) :: bs.toList.zip(tbs) ::: rest)
            case (layer, other) =>
              Some(s"${layer.getClass.getSimpleName} against ${other.getClass.getSimpleName}")
      go(List(ours.term -> theirs.term))

  private def uni(u: DefaultUni): SUni = u match
    case DefaultUni.Integer              => SUni.Integer
    case DefaultUni.ByteString           => SUni.ByteString
    case DefaultUni.String               => SUni.String
    case DefaultUni.Unit                 => SUni.Unit
    case DefaultUni.Bool                 => SUni.Bool
    case DefaultUni.Data                 => SUni.Data
    case DefaultUni.Bls12_381_G1_Element => SUni.BLS12_381_G1_Element
    case DefaultUni.Bls12_381_G2_Element => SUni.BLS12_381_G2_Element
    case DefaultUni.Bls12_381_MlResult   => SUni.BLS12_381_MlResult
    case DefaultUni.Value                => SUni.BuiltinValue
    case DefaultUni.List(e)              => SUni.Apply(SUni.ProtoList, uni(e))
    case DefaultUni.Array(e)             => SUni.Apply(SUni.ProtoArray, uni(e))
    case DefaultUni.Pair(a, b)           => SUni.Apply(SUni.Apply(SUni.ProtoPair, uni(a)), uni(b))

  /** The Scalus constant of an own constant (test code: constants of the fixtures are shallow, and
    * the comparison runs on a large stack).
    */
  private def constant(c: Constant): SConstant = c match
    case Constant.Integer(n)    => SConstant.Integer(n)
    case Constant.ByteString(b) => SConstant.ByteString(SByteString.fromArray(b.toArray))
    case Constant.String(t)     => SConstant.String(t.string)
    case Constant.Unit          => SConstant.Unit
    case Constant.Bool(b)       => SConstant.Bool(b)
    case Constant.Data(d)       => SConstant.Data(ScalusEvaluator.toData(d))
    case Constant.Value(v) =>
      SConstant.BuiltinValue(
        BuiltinValue.fromEntryList(
          v.toList.toList.map((currency, tokens) =>
            SByteString.fromArray(currency.bytes.toArray) ->
              tokens.toList.map((token, q) =>
                SByteString.fromArray(token.bytes.toArray) -> q.amount
              )
          )
        )
      )
    case l: Constant.List    => SConstant.List(uni(l.elementType), l.items.toList.map(constant))
    case a: Constant.Array   => SConstant.Array(uni(a.elementType), a.items.map(constant))
    case Constant.Pair(a, b) => SConstant.Pair(constant(a), constant(b))
    case other               => fail(s"no Flat constant: $other")

  private enum Finding:
    /** The two decoders disagree at a language and protocol. */
    case Verdict(script: Script, language: Language, protocol: Int, ours: String, theirs: String)

    /** Both decode, and the programs differ. */
    case Program(script: Script, where: String)

    /** Scalus gives no verdict. */
    case Unavailable(script: Script, language: Language, protocol: Int, cause: String)

    def show: String = this match
      case Verdict(s, l, p, o, t) =>
        s"${s.source} ${s.id} at $l/$p: own $o, Scalus $t; script ${s.bytes.hex}"
      case Program(s, w) => s"${s.source} ${s.id}: programs differ at $w; script ${s.bytes.hex}"
      case Unavailable(s, l, p, c) => s"${s.source} ${s.id} at $l/$p: Scalus unavailable ($c)"

  private def compare(script: Script): Vector[Finding] =
    val verdicts = cells(script.languages).flatMap { (language, protocol) =>
      val ours = SerialisedScript.deserialiseScript(language, ProtocolMajor(protocol), script.bytes)
      scalusDecoder.validate(language, protocol, script.bytes) match
        case Left(ScriptDecodeFailure.Unavailable(cause)) =>
          Vector(Finding.Unavailable(script, language, protocol, cause.message))
        case theirs if theirs.isRight == ours.isRight => Vector.empty
        case theirs =>
          Vector(
            Finding.Verdict(
              script,
              language,
              protocol,
              ours.fold(_.toString, p => s"admits ${p.version.show}"),
              theirs.fold(_.message, _ => "admits")
            )
          )
    }
    val programs = PlutusScriptBytes.flatOf(script.bytes.toArray).toVector.flatMap { flat =>
      (FlatDecoder.program(Bytes.wrap(flat), Checks.unrestricted), FlatScan.decode(flat)) match
        case (Right(ours), Right(theirs)) =>
          difference(ours, theirs.program).map(Finding.Program(script, _)).toVector
        case _ => Vector.empty
    }
    verdicts.toVector ++ programs

  /** Runs `body` on a thread of a large stack: Scalus's terms and data compare by recursion. */
  private def onBigStack[A](body: => A): A =
    val result = new java.util.concurrent.atomic.AtomicReference[Try[A]]()
    val worker =
      new Thread(null, () => result.set(Try(body)), "own-flat-parity", 1024L * 1024 * 1024)
    worker.start()
    worker.join()
    result.get().fold(throw _, identity)

  private lazy val all: Vector[Script] =
    corpusScripts ++ probeScripts ++ conformanceScripts ++ recordedCases ++ recordedChain

  private lazy val findings: Vector[Finding] = onBigStack(all.flatMap(compare))

  test("every source yields its scripts") {
    // A source that yields fewer scripts lost a fixture or its reader broke.
    def count(source: String): Int = all.count(_.source == source)
    assert(count("fixtures/reference/plutus-costing/scripts.jsonl") >= 1858)
    assert(count("fixtures/reference/plutus-boundaries/scripts.jsonl") >= 53)
    assert(count("fixtures/reference/plutus-upstream-boundaries/scripts.jsonl") >= 6)
    assert(count("flat-decode probes") == FlatDecodeCorpus.probes.size)
    assert(count("boundary probes") == PlutusBoundaryCorpus.probes.size)
    assert(count("conformance") >= 950, s"${count("conformance")} conformance programs")
    assert(
      count("recorded cases") >= 90,
      s"${count("recorded cases")} scripts in the recorded cases"
    )
    assert(
      count("recorded chain") >= 25,
      s"${count("recorded chain")} scripts in the recorded chain"
    )
    // every recorded Plutus case holds a script: in its witness set or behind a reference input
    val cases =
      Vector("fixtures/preview/plutus", "fixtures/preprod/plutus").flatMap(files(_, ".json"))
    val withScripts = recordedCases.map(_.id.takeWhile(_ != '#')).toSet
    assertEquals(cases.map(_.toString).filterNot(withScripts), Vector.empty)
    val languages = (recordedCases ++ recordedChain).flatMap(_.languages).toSet
    assertEquals(languages, Set(Language.V1, Language.V2, Language.V3))
  }

  test(
    "the own decoder and Scalus's admit and refuse the same scripts, and decode the same programs"
  ) {
    val compared = onBigStack(all.count { s =>
      PlutusScriptBytes
        .flatOf(s.bytes.toArray)
        .exists(flat =>
          FlatDecoder.program(Bytes.wrap(flat), Checks.unrestricted).isRight && FlatScan
            .decode(flat)
            .isRight
        )
    })
    assert(compared >= 3000, s"only $compared programs compared")
    val disagreements = findings.filterNot(_.isInstanceOf[Finding.Unavailable]).map(_.show)
    assertEquals(disagreements, Vector.empty, disagreements.take(20).mkString("\n"))
  }

  test("the comparison of programs sees a difference") {
    val decoded = conformanceScripts.take(40).flatMap { s =>
      PlutusScriptBytes.flatOf(s.bytes.toArray).toVector.flatMap { flat =>
        (FlatDecoder.program(Bytes.wrap(flat), Checks.unrestricted), FlatScan.decode(flat)) match
          case (Right(ours), Right(theirs)) => Vector(ours -> theirs.program)
          case _                            => Vector.empty
      }
    }
    assert(decoded.size >= 2)
    val shifted = decoded.zip(decoded.drop(1)).filter((a, b) => a._1 != b._1)
    assert(shifted.nonEmpty)
    shifted.foreach { case ((ours, _), (_, theirs)) =>
      assert(onBigStack(difference(ours, theirs)).isDefined)
    }
    decoded.foreach((ours, theirs) => assertEquals(onBigStack(difference(ours, theirs)), None))
  }

  test("Scalus answers for every script it is asked about") {
    val unavailable = findings.collect { case u: Finding.Unavailable => u.show }
    assertEquals(unavailable, Vector.empty, unavailable.take(20).mkString("\n"))
  }

  // --- the Haskell verdicts ----------------------------------------------------------------------

  private def sha256(b: Bytes): String =
    MessageDigest.getInstance("SHA-256").digest(b.toArray).map(x => f"${x & 0xff}%02x").mkString

  test("where the Haskell reference judged a script, both decoders agree with it") {
    val byHash = (corpusScripts ++ probeScripts).map(s => sha256(s.bytes) -> s).toMap
    val rows = Vector(
      "fixtures/reference/plutus-flat-decode/verdicts.jsonl" -> Vector("admitted"),
      "fixtures/reference/plutus-boundaries/verdicts.jsonl" -> Vector("admitted"),
      "fixtures/reference/plutus-upstream-boundaries/verdicts.jsonl" -> Vector(
        "released",
        "admitted"
      )
    ).flatMap { (file, path) =>
      jsonl(file).map { row =>
        val admitted = path
          .foldLeft(row.hcursor: io.circe.ACursor)(_.downField(_))
          .as[Boolean]
          .fold(e => fail(e.message), identity)
        val language =
          Language.values.find(_.id + 1 == row.hcursor.get[Int]("language").getOrElse(0))
        (
          file,
          str(row, "id"),
          str(row, "scriptSha256"),
          language,
          row.hcursor.get[Int]("protocol").getOrElse(0),
          admitted
        )
      }
    }
    assertEquals(rows.size, 435 + 477 + 54)
    val wrong = onBigStack(rows.flatMap { (file, id, sha, language, protocol, admitted) =>
      (byHash.get(sha), language) match
        case (Some(script), Some(l)) =>
          val ours =
            SerialisedScript.deserialiseScript(l, ProtocolMajor(protocol), script.bytes).isRight
          val theirs = scalusDecoder.validate(l, protocol, script.bytes)
          Vector(
            Option.when(ours != admitted)(
              s"own: $file $id at $l/$protocol, reference admitted=$admitted"
            ),
            Option.when(theirs.isRight != admitted)(
              s"Scalus: $file $id at $l/$protocol, reference admitted=$admitted, Scalus $theirs"
            )
          ).flatten
        case _ => Vector(s"$file $id: no script with SHA-256 $sha")
    })
    assertEquals(wrong, Vector.empty, wrong.take(20).mkString("\n"))
  }
