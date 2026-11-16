// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Address,
  ByteStrings,
  CborRead,
  Datum,
  DecoderVersion,
  TxOut as FlatTxOut,
  Value
}
import xyz.kd5ujc.scarab.ledger.dijkstra.{
  DijkstraDatum,
  DijkstraScript as FlatDijkstraScript,
  DijkstraTx,
  DijkstraTxOut as FlatDijkstraTxOut,
  V12
}
import xyz.kd5ujc.scarab.ledger.era.{
  BabbageEraTxOut,
  EraScript,
  EraTxOut,
  MultiAssetValue,
  Script,
  TxOut
}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxOut
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
import xyz.kd5ujc.scarab.protocol.Era

/** Dijkstra's script ([[DijkstraScript]]) and output ([[DijkstraTxOut]]) against the Leios build:
  *   - every output of every transaction the build accepted in
  *     `fixtures/reference/leios-testnet-blocks` reads, with the address, value, datum, bytes and
  *     reference script the block decoder read, the script's hash with Dijkstra's prefixes;
  *   - the script decoder agrees with the block decoder's ([[FlatDijkstraScript.script]]) on each
  *     language and on malformed forms, verdict and refusal alike;
  *   - a Conway output carried into Dijkstra has the bytes and reference script the reference's
  *     `upgradeTxOut` gives on `fixtures/reference/dijkstra-translation`;
  *   - the era's families reduce at Dijkstra to these types.
  */
class DijkstraTxOutSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private def lines(path: String): Vector[Json] =
    Files
      .readAllLines(Path.of(path))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => parser.parse(l).fold(e => fail(e.message), identity))

  private def field(j: Json, path: String*): Option[Json] =
    path.foldLeft(j.hcursor: io.circe.ACursor)(_.downField(_)).focus

  private def string(j: Json, path: String*): String =
    field(j, path*).flatMap(_.asString).getOrElse(fail(s"${path.mkString(".")} missing"))

  private def node(raw: Bytes) = CborRead.decode(raw, V12.nestedLimits)

  // ---- the testnet's transactions

  private val outputs: Vector[(String, FlatDijkstraTxOut)] = lines(
    "fixtures/reference/leios-testnet-blocks/cases.jsonl"
  ).filter(r =>
    string(r, "kind") == "tx" && field(r, "accepted").flatMap(_.asBoolean).contains(true)
  ).flatMap { r =>
    val tx = node(hex(string(r, "bytes")))
      .flatMap(DijkstraTx.inBlock)
      .fold(e => fail(s"${string(r, "name")}: the build accepted it: $e"), identity)
    val all = tx.body.outputs ++ tx.body.collateralReturn ++
      tx.body.subTransactions.flatMap(s => s.body.outputs ++ s.body.collateralReturn)
    all.map(string(r, "name") -> _)
  }

  private def datum(d: DijkstraDatum): Datum = d match
    case DijkstraDatum.NoDatum   => Datum.NoDatum
    case DijkstraDatum.Hash(h)   => Datum.Hash(h)
    case DijkstraDatum.Inline(r) => Datum.Inline(r)

  test("every output the build read reads, with its fields and its reference script") {
    assert(outputs.sizeIs > 100, outputs.size.toString)
    outputs.foreach { (name, flat) =>
      val out = node(flat.raw).flatMap(DijkstraTxOut.read).fold(e => fail(s"$name: $e"), _.rep)
      assertEquals(out.raw, flat.raw, name)
      assertEquals(out.address, flat.address, name)
      assertEquals(out.value, MultiAssetValue.of(Value(flat.value.coin, flat.value.assets)), name)
      assertEquals(out.datum, datum(flat.datum), name)
      assertEquals(
        out.scriptRef.map(DijkstraScript.instance.hashScript),
        flat.referenceScript.map(_.hash),
        name
      )
    }
  }

  test("the outputs the build read hold reference scripts of every Dijkstra language") {
    val prefixes = outputs.flatMap((_, flat) =>
      node(flat.raw)
        .flatMap(DijkstraTxOut.read)
        .toOption
        .flatMap(_.rep.scriptRef)
        .map(DijkstraScript.instance.scriptPrefixTag)
    )
    assertEquals(prefixes.toSet, Set[Byte](0, 1, 2, 3, 4))
  }

  test("every output the build read is written to the store's form and read back unchanged") {
    outputs.foreach { (name, flat) =>
      val out = node(flat.raw).flatMap(DijkstraTxOut.read).fold(e => fail(s"$name: $e"), identity)
      val stored = DijkstraTxOut.toStored(out)
      assertEquals(DijkstraTxOut.fromStored(stored), Right(out), name)
      assertEquals(stored.raw, flat.raw, name)
      assertEquals(stored.scriptRef.map(_.hash), flat.referenceScript.map(_.hash), name)
    }
  }

  test("the store keeps each Dijkstra language's kind, PlutusV4 among them") {
    val kinds = outputs.flatMap((_, flat) =>
      node(flat.raw)
        .flatMap(DijkstraTxOut.read)
        .toOption
        .flatMap(o => DijkstraTxOut.toStored(o).scriptRef)
        .map(_.kind)
    )
    assertEquals(kinds.toSet, xyz.kd5ujc.scarab.ledger.ScriptKind.values.toSet)
  }

  test("a native reference script with a guard is stored without a tree and read back whole") {
    val key = "8200581c" + "44" * 28
    // all of a signature and a guard by a key credential
    val native = "8201" + "82" + key + "8206" + "8200581c" + "55" * 28
    val script = "82" + "00" + native
    val wrapped = "d818" + f"58${script.length / 2}%02x" + script
    val raw = hex("a3" + "00" + "581d61" + "66" * 28 + "01" + "1a000f4240" + "03" + wrapped)
    val out = node(raw).flatMap(DijkstraTxOut.read).fold(e => fail(e.toString), identity)
    val stored = DijkstraTxOut.toStored(out)
    val kept = stored.scriptRef.getOrElse(fail("no reference script"))
    assertEquals(kept.kind, xyz.kd5ujc.scarab.ledger.ScriptKind.Native)
    assertEquals(kept.native, None)
    assertEquals(DijkstraTxOut.fromStored(stored), Right(out))
    val read = DijkstraTxOut
      .fromStored(stored)
      .toOption
      .flatMap(_.rep.scriptRef)
      .flatMap(DijkstraScript.instance.getNativeScript)
      .flatMap(_.getRequireAllOf)
      .getOrElse(fail("not all-of"))
    assertEquals(read.flatMap(_.getRequireGuard).size, 1)
  }

  test("an output's value size is the size of its value as it arrived") {
    // `{0: address, 1: [coin, assets]}` with the value an indefinite list
    val address = "581d61" + "00" * 28
    val value = "9f" + "1a000f4240" + "a1581c" + "33" * 28 + "a14001" + "ff"
    val raw = hex("a200" + address + "01" + value)
    val out = node(raw).flatMap(DijkstraTxOut.read).fold(e => fail(e.toString), _.rep)
    assertEquals(out.valueSize, value.length / 2)
  }

  // ---- the script decoder against the block decoder's

  private val scriptForms: Vector[String] = Vector(
    "8200" + "8200581c" + "11" * 28,
    "8201" + "4401020304",
    "8202" + "4401020304",
    "8203" + "4401020304",
    "8204" + "4401020304",
    "8204" + "5f420102420304ff",
    "8205" + "4401020304",
    "8204",
    "8304" + "4401020304" + "00",
    "8204" + "6401020304",
    "8200" + "8207" + "00",
    "9f04" + "4401020304" + "ff",
    "8218" + "04" + "4401020304"
  )

  test("the script decoder agrees with the block decoder's, verdict and refusal") {
    scriptForms.foreach { h =>
      val raw = hex(h)
      val ours = node(raw).flatMap(DijkstraScript.read)
      val theirs = node(raw).flatMap(FlatDijkstraScript.script)
      assertEquals(ours.map(DijkstraScript.instance.hashScript), theirs.map(_.hash), h)
    }
    // five are refused (tag 5, an item too few or too many, a text program, a malformed native
    // script); an indefinite list and a tag in a wider head are read at version 12
    assertEquals(scriptForms.count(h => node(hex(h)).flatMap(DijkstraScript.read).isLeft), 5)
  }

  test("a Plutus V4 script has the prefix 4 and the era's newest language") {
    val v4 = node(hex("8204" + "4401020304"))
      .flatMap(DijkstraScript.read)
      .fold(e => fail(e.toString), identity)
    assertEquals(DijkstraScript.instance.scriptPrefixTag(v4), 4.toByte)
    assertEquals(
      DijkstraScript.instance.toPlutusScript(v4).map(_.language),
      Some(xyz.kd5ujc.scarab.plutus.PlutusLanguage.V4)
    )
    assertEquals(DijkstraScript.instance.eraMaxLanguage, xyz.kd5ujc.scarab.plutus.PlutusLanguage.V4)
  }

  // ---- the translation from Conway

  private def conwayOutput(raw: Bytes): ConwayTxOut =
    given ByteStrings = ByteStrings.of(DecoderVersion.of(Era.Conway))
    FlatTxOut
      .fromBytes(raw, Address.Mode.of(Era.Conway))
      .left
      .map(_.toString)
      .flatMap(ConwayTxOut.fromStored(_).left.map(_.toString))
      .fold(e => fail(e), identity)

  private val translations = lines("fixtures/reference/dijkstra-translation/cases.jsonl")

  test("the translation corpus is the reference's 19 accepted cases") {
    assertEquals(translations.size, 19)
  }

  translations.foreach { row =>
    val name = string(row, "name")
    test(s"$name: a Conway output carried into Dijkstra as the reference carries it") {
      val conway = conwayOutput(hex(string(row, "txOut")))
      val out = DijkstraTxOut.translate(conway).fold(e => fail(e.toString), _.rep)
      val reference = field(row, "reference").getOrElse(fail("no reference"))
      field(reference, "dijkstraScript").filterNot(_.isNull) match
        case Some(script) =>
          assertEquals(out.raw, hex(string(row, "reference", "dijkstraTxOut")))
          assertEquals(
            out.scriptRef.map(DijkstraScript.instance.hashScript),
            Some(hex(string(script, "hash")))
          )
          assertEquals(
            out.scriptRef.map(DijkstraScript.instance.originalBytes),
            Some(hex(string(script, "bytes")))
          )
        case None =>
          // no native script: the output and its script are the Conway ones
          assertEquals(out.raw, conway.rep.raw)
          assertEquals(
            out.scriptRef.map(DijkstraScript.instance.hashScript),
            conway.rep.scriptRef.map(
              xyz.kd5ujc.scarab.ledger.era.conway.ConwayScript.instance.hashScript
            )
          )
      assertEquals(out.address, conway.rep.address)
      assertEquals(out.value, conway.rep.value)
      assertEquals(out.datum, conway.rep.datum)
    }
  }

  test("a translated output's native reference script keeps its sub-scripts' encodings") {
    // `{0: address, 1: coin, 3: 24(<<[0, [1, [_ [4, 0], [5, 0]]]]>>)}`
    val raw = hex(
      "a300" + "581d61" + "00" * 28 + "01" + "1a000f4240" + "03" + "d8184c" + "8200" +
        "82019f820400820500ff"
    )
    val out = DijkstraTxOut.translate(conwayOutput(raw)).fold(e => fail(e.toString), _.rep)
    val native = out.scriptRef
      .flatMap(DijkstraScript.instance.getNativeScript)
      .getOrElse(fail("no native script"))
    assertEquals(native.raw.hex, "820182820400820500")
    val subs = native.getRequireAllOf.getOrElse(fail("not all-of"))
    assertEquals(subs.map(_.raw.hex), Vector("820400", "820500"))
    assertNotEquals(subs(0), subs(1))
    // the output's bytes hold the new encoding of the script
    assert(out.raw.hex.contains("820182820400820500"), out.raw.hex)
  }

  test("an output Mary read leniently is carried into Dijkstra as it was read") {
    // output 0 of mainnet transaction 9a47e091: a pointer out of range, which Mary normalises to
    // `Ptr 0 0 0` and keeps in its bytes as it came (MainnetPointerSuite); Dijkstra's strict
    // decoder would refuse those bytes, and the translation does not read them again
    val hexTx = Files.readString(Path.of("fixtures/mainnet/mary-pointer-tx-9a47e091.hex")).trim
    val body = CborRead
      .decode(hex(hexTx))
      .flatMap(CborRead.array(_, xyz.kd5ujc.scarab.ledger.DecodeField.Transaction))
      .map(_.head.original)
      .fold(e => fail(e.toString), identity)
    val mary = xyz.kd5ujc.scarab.ledger.TxBody
      .decode(body, Era.Mary)
      .fold(e => fail(e.toString), _.outputs.head)
    val conway = ConwayTxOut.fromStored(mary).fold(e => fail(e.toString), identity)
    val out = DijkstraTxOut.translate(conway).fold(e => fail(e.toString), _.rep)
    assertEquals(out.address, mary.address)
    assertEquals(out.raw, mary.raw)
    assertEquals(out.value, conway.rep.value)
  }

  // ---- the families

  test("the era's families reduce at Dijkstra to its types") {
    val outputs: EraTxOut[Dijkstra] = summon[BabbageEraTxOut[Dijkstra]]
    val scripts = summon[xyz.kd5ujc.scarab.ledger.era.AlonzoEraScript[Dijkstra]]
    assert(outputs eq EraTxOut.dijkstra)
    assert(scripts eq EraScript.dijkstra)
    val read: TxOut[Dijkstra] = node(hex("a200" + "581d61" + "00" * 28 + "01" + "1a000f4240"))
      .flatMap(DijkstraTxOut.read)
      .fold(e => fail(e.toString), identity)
    val script: Option[Script[Dijkstra]] = EraTxOut.dijkstra.referenceScript(read)
    assertEquals(script, None)
    assertEquals(outputs.raw(read).size, 39)
  }

  test("a refusal of the block decoder is the type's, with its cause") {
    // an output map without its value
    val raw = hex("a100" + "581d61" + "00" * 28)
    val ours = node(raw).flatMap(DijkstraTxOut.read)
    assert(ours.isLeft)
    assertEquals(ours.left.toOption, node(raw).flatMap(FlatDijkstraTxOut.decode).left.toOption)
  }
