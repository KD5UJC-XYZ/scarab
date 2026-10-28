// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.{Decoder, Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.rules.GenesisInjections

/** The Shelley, Alonzo and Conway genesis files against the unchanged reference parsers (#497).
  *
  * `fixtures/reference/genesis-fields/cases.jsonl` holds one case per line: a genesis file of the
  * repository, whole or with one field removed, given another JSON type or another value, and the
  * verdict of `FromJSON ShelleyGenesis`, `FromJSON AlonzoGenesis` or `FromJSON ConwayGenesis` of
  * cardano-ledger f649f975 on it. Each case is rebuilt from its base file and read by
  * [[GenesisSchema]], which must accept exactly what the reference accepts. A field removed or of
  * the wrong JSON type is refused at its own path. An accepted file is then read by every reader
  * the node runs after the check, beside the other preview genesis files; for a Shelley file the
  * parameters and the update quorum read are the ones the reference read.
  */
class GenesisSchemaSuite extends FunSuite:
  private def parse(text: String): Json = parser.parse(text).fold(throw _, identity)
  private def field[A: Decoder](row: Json, key: String): A =
    row.hcursor.get[A](key).fold(e => fail(e.message), identity)

  private val rows: Vector[Json] =
    Files
      .readString(Path.of("fixtures/reference/genesis-fields/cases.jsonl"))
      .linesIterator
      .map(parse)
      .toVector

  /** The case's change at `path`: removed, or replaced by `value`. */
  private def edit(json: Json, path: List[String], value: Option[Json]): Json = path match
    case Nil        => json
    case key :: Nil => json.mapObject(o => value.fold(o.remove(key))(o.add(key, _)))
    case key :: rest =>
      json.mapObject(o => o.add(key, edit(o(key).getOrElse(Json.obj()), rest, value)))

  /** The parser a case runs. */
  private enum Kind:
    case Shelley, Alonzo, Conway

  private given Decoder[Kind] = Decoder[String].emap {
    case "shelleyGenesis" => Right(Kind.Shelley)
    case "alonzoGenesis"  => Right(Kind.Alonzo)
    case "conwayGenesis"  => Right(Kind.Conway)
    case other            => Left(s"unknown kind $other")
  }

  /** The change a case makes to its base file. */
  private enum Change:
    case Whole
    case Remove(path: List[String])
    case Replace(path: List[String], value: Json)

  private def change(row: Json): Change =
    field[String](row, "op") match
      case "none"    => Change.Whole
      case "remove"  => Change.Remove(field[List[String]](row, "path"))
      case "replace" => Change.Replace(field[List[String]](row, "path"), field[Json](row, "value"))
      case other     => fail(s"unknown change $other")

  private def input(row: Json): Json =
    val base = parse(Files.readString(Path.of(field[String](row, "base"))))
    change(row) match
      case Change.Whole                => base
      case Change.Remove(path)         => edit(base, path, None)
      case Change.Replace(path, value) => edit(base, path, Some(value))

  private def read(row: Json): Either[ConfigError, Unit] =
    field[Kind](row, "kind") match
      case Kind.Shelley => GenesisSchema.shelley(input(row))
      case Kind.Alonzo  => GenesisSchema.alonzo(input(row))
      case Kind.Conway  => GenesisSchema.conway(input(row))

  private def file(row: Json): GenesisFile = field[Kind](row, "kind") match
    case Kind.Shelley => GenesisFile.Shelley
    case Kind.Alonzo  => GenesisFile.Alonzo
    case Kind.Conway  => GenesisFile.Conway

  private def preview(era: String): Json =
    parse(Files.readString(Path.of(s"fixtures/preview/genesis/$era-genesis.json")))

  /** The readers `NodeSetup` runs after the check, on the case's file beside the preview ones. */
  private def readers(row: Json): Either[ConfigError, (ProtocolParams, LedgerGlobals)] =
    val (shelley, alonzo, conway) = field[Kind](row, "kind") match
      case Kind.Shelley => (input(row), preview("alonzo"), preview("conway"))
      case Kind.Alonzo  => (preview("shelley"), input(row), preview("conway"))
      case Kind.Conway  => (preview("shelley"), preview("alonzo"), input(row))
    for
      genesis <- Genesis.shelley(shelley.noSpaces)
      files = GenesisState.Files(
        shelley,
        alonzo,
        conway,
        preview("byron"),
        Some(genesis.networkMagic)
      )
      _ <- GenesisInjections.read(files)
      globals <- GenesisState.globals(files, genesis)
      params <- ProtocolParams.fromGenesis(shelley, alonzo, Some(conway))
      _ <- ProtocolParams.translatedFromGenesis(shelley, alonzo, Some(conway))
      _ <- LiteLedger.fromGenesis(shelley)
    yield (params, globals)

  private def decimal(json: Json, key: String): Rational =
    Rational.fromDecimal(field[BigDecimal](json, key))

  private def reference(row: Json): Json = field[Json](row, "reference")
  private def accepted(row: Json): Boolean = field[Boolean](reference(row), "accepted")

  test("the fixture holds every kind, whole files and changed ones") {
    assertEquals(rows.size, 279)
    assertEquals(rows.map(field[Kind](_, "kind")).toSet, Kind.values.toSet)
    assert(rows.exists(r => change(r) == Change.Whole && accepted(r)))
    assert(rows.exists(r => !accepted(r)))
  }

  for row <- rows do
    test(s"${field[String](row, "name")}: as the reference") {
      val scarab = read(row)
      val failure = reference(row).hcursor.get[String]("failure").getOrElse("accepted")
      assertEquals(
        scarab.isRight,
        accepted(row),
        s"${scarab.fold(_.message, _ => "accepted")} | reference: $failure"
      )
      val name = field[String](row, "name")
      // a field removed or of another JSON type is refused where it is; the cost models' own
      // refusal (`GenesisCostModel`) names its field and language
      val path = field[List[String]](row, "path")
      if name.contains("/remove:") || name.contains("/wrongType:") then
        scarab.left.foreach(e =>
          e.problem match
            case ConfigProblem.GenesisField(f, at, _) =>
              assertEquals((f, at.toList), (file(row), path), e.message)
            case ConfigProblem.GenesisCostModel(_) =>
              assert(Set("costModels", "plutusV3CostModel")(path.head), e.message)
            case _ => fail(s"not a genesis field refusal: ${e.message}")
        )
      if accepted(row) then
        val (params, globals) = readers(row).fold(e => fail(e.message), identity)
        if field[Kind](row, "kind") == Kind.Shelley then
          val result = field[Json](reference(row), "result")
          val pp = field[Json](result, "protocolParams")
          assertEquals(params.minPoolCost, field[BigInt](pp, "minPoolCost"))
          assertEquals(params.minUTxOValue, field[BigInt](pp, "minUTxOValue"))
          assertEquals(params.maxBlockHeaderSize, field[BigInt](pp, "maxBlockHeaderSize"))
          assertEquals(params.d, decimal(pp, "decentralization"))
          assertEquals(params.rho, decimal(pp, "monetaryExpansion"))
          assertEquals(params.tau, decimal(pp, "treasuryCut"))
          assertEquals(params.a0, decimal(pp, "poolPledgeInfluence"))
          assertEquals(
            BigInt(globals.updateQuorum),
            field[BigInt](result, "updateQuorum").min(BigInt(Int.MaxValue))
          )
    }
