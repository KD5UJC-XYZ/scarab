// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.mempack

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Address,
  ByteStrings,
  CborRead,
  DecodeField,
  DecoderVersion,
  ScriptKind,
  TxOut
}
import xyz.kd5ujc.scarab.ledger.testByteStrings
import xyz.kd5ujc.scarab.ledger.dijkstra.V12
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraCorpus, DijkstraTx}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxOut
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraScript, DijkstraTxOut}
import xyz.kd5ujc.scarab.ledger.state.{Babbage, Conway, Dijkstra}
import xyz.kd5ujc.scarab.protocol.Era

import MemPack.{Failure, Feature, TagSite}

/** The MemPack `TxOut` of the Dijkstra era (the Leios build, cardano-ledger 9df9f98c):
  *   - the `tables` of the first Dijkstra snapshot of `fixtures/leios/state` decode in Dijkstra and
  *     pack back byte for byte;
  *   - every output of every transaction the build accepted in
  *     `fixtures/reference/leios-testnet-blocks`, reference scripts of each Dijkstra language among
  *     them, packs with the script the era's output decoder read, and unpacks to the stored output
  *     the era's output gives;
  *   - outputs carried from Conway pack the native script the reference's `upgradeTxOut` gives
  *     (`fixtures/reference/dijkstra-translation`);
  *   - a PlutusV4 script and a native script with a guard pack to bytes derived by hand from the
  *     pinned encoders, and the earlier eras still refuse PlutusV4.
  */
class DijkstraTxOutMemPackSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private def right[E, A](e: Either[E, A]): A = e.fold(f => fail(f.toString), identity)

  private def node(raw: Bytes) = right(CborRead.decode(raw, V12.nestedLimits))

  /** The output of `raw` as the era's output decoder reads it, in the form the store keeps. */
  private def stored(raw: Bytes): TxOut =
    DijkstraTxOut.toStored(right(DijkstraTxOut.decode(node(raw))))

  /** The reference script of the stored output, as the era's own script reads it: its hash's prefix
    * (the kind) and its original bytes.
    */
  private def eraScript(out: TxOut): Option[(Int, Bytes)] =
    right(DijkstraTxOut.fromStored(out)).rep.scriptRef.map(s =>
      (DijkstraScript.instance.scriptPrefixTag(s).toInt, DijkstraScript.instance.originalBytes(s))
    )

  /** Packs `out` in Dijkstra and checks the packed bytes against the output: its fields, its script
    * against the era's reading of it, the unpacked output the stored form of the era's output, and
    * the canonical packing stable. Returns the packed bytes.
    */
  private def packChecked(label: String, out: TxOut): Bytes =
    val packed = right(TxOutMemPack.encode[Dijkstra](out))
    val p = right(TxOutMemPack.decodeParts[Dijkstra](packed))
    assertEquals(p.address, out.address.raw, label)
    assertEquals(p.value, out.value, label)
    assertEquals(p.datum, out.datum, label)
    assertEquals(p.script.map(s => (s.kind.tag, s.bytes)), eraScript(out), label)
    val back = right(TxOutMemPack.decode[Dijkstra](packed))
    assertEquals(back.scriptRef, out.scriptRef, label)
    // the stored form of the era's output: `fromStored` refuses any other
    assert(DijkstraTxOut.fromStored(back).isRight, label)
    assertEquals(TxOutMemPack.encode[Dijkstra](back), Right(packed), label)
    packed

  private def address = "581d61" + "66" * 28
  private def withScript(script: String): Bytes =
    val wrapped = "d818" + f"58${script.length / 2}%02x" + script
    hex("a3" + "00" + address + "01" + "1a000f4240" + "03" + wrapped)

  // ---- the tables of the Leios devnet's first Dijkstra state

  /** Entries of a `tables` file read whole: `[ {key bytes: value bytes} ]`. */
  private def tableEntries(path: String): Vector[(Bytes, Bytes)] =
    val items = right(
      CborRead.array(node(Bytes.fromArray(Files.readAllBytes(Path.of(path)))), DecodeField.Output)
    )
    assertEquals(items.size, 1)
    right(CborRead.map(items.head, DecodeField.Output)).map((k, v) =>
      (right(CborRead.bytes(k, DecodeField.Input)), right(CborRead.bytes(v, DecodeField.Output)))
    )

  test("the first Dijkstra state's tables decode in Dijkstra and pack back byte for byte") {
    val dijkstra = tableEntries("fixtures/leios/state/dijkstra-203/tables")
    // the outputs of the last Conway state, carried over unchanged
    assertEquals(dijkstra, tableEntries("fixtures/leios/state/conway-195/tables"))
    assertEquals(dijkstra.size, 7)
    dijkstra.foreach { (key, value) =>
      assertEquals(key.size, 34)
      val out = right(TxOutMemPack.decode[Dijkstra](value))
      assertEquals(TxOutMemPack.encode[Dijkstra](out), Right(value), key.hex)
      assertEquals(TxOutMemPack.decode[Conway](value), Right(out), key.hex)
      assert(DijkstraTxOut.fromStored(out).isRight, key.hex)
    }
  }

  // ---- the testnet's transactions

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

  private val outputs: Vector[(String, TxOut)] = lines(
    "fixtures/reference/leios-testnet-blocks/cases.jsonl"
  ).filter(r =>
    string(r, "kind") == "tx" && field(r, "accepted").flatMap(_.asBoolean).contains(true)
  ).flatMap { r =>
    val tx = right(DijkstraTx.inBlock(node(hex(string(r, "bytes")))))
    DijkstraCorpus.outputItems(tx).map(o => string(r, "name") -> stored(o))
  }

  test("every output the build accepted packs with its script and unpacks to the era's output") {
    assert(outputs.sizeIs > 100, outputs.size.toString)
    val kinds = outputs.flatMap { (name, out) =>
      val packed = packChecked(name, out)
      right(TxOutMemPack.decodeParts[Dijkstra](packed)).script.map(_.kind)
    }
    assertEquals(kinds.toSet, ScriptKind.values.toSet)
  }

  test("an output of the build with a PlutusV4 script is refused by the earlier eras") {
    val v4 = outputs
      .map(_._2)
      .filter(_.scriptRef.exists(_.kind == ScriptKind.PlutusV4))
    assert(v4.nonEmpty)
    v4.foreach { out =>
      val packed = right(TxOutMemPack.encode[Dijkstra](out))
      assertEquals(TxOutMemPack.encode[Conway](out), Left(Failure.UnknownScriptKind(BigInt(4))))
      assertEquals(
        TxOutMemPack.decode[Conway](packed),
        Left(Failure.UnknownTag(TagSite.PlutusLanguage, 3))
      )
      assertEquals(
        TxOutMemPack.decode[Babbage](packed),
        Left(Failure.UnknownTag(TagSite.PlutusLanguage, 3))
      )
    }
  }

  test("a script the build reads in another form packs its bytes as the era reads them") {
    // an indefinite record, a tag in a wider head, a chunked program
    Vector(
      "9f04" + "4401020304" + "ff",
      "8218" + "04" + "4401020304",
      "8204" + "5f420102420304ff"
    ).foreach { script =>
      val packed = packChecked(script, stored(withScript(script)))
      assertEquals(
        right(TxOutMemPack.decodeParts[Dijkstra](packed)).script,
        Some(RefScript(ScriptKind.PlutusV4, hex("01020304"))),
        script
      )
    }
  }

  // ---- outputs carried from Conway

  private def conwayOutput(raw: Bytes): ConwayTxOut =
    given ByteStrings = ByteStrings.of(DecoderVersion.of(Era.Conway))
    right(ConwayTxOut.fromStored(right(TxOut.fromBytes(raw, Address.Mode.of(Era.Conway)))))

  test("an output carried from Conway packs the native script the reference's upgrade gives") {
    val rows = lines("fixtures/reference/dijkstra-translation/cases.jsonl")
      .filter(r => field(r, "reference", "dijkstraScript").exists(!_.isNull))
    assert(rows.nonEmpty)
    rows.foreach { row =>
      val name = string(row, "name")
      val out = DijkstraTxOut.toStored(
        right(DijkstraTxOut.translate(conwayOutput(hex(string(row, "txOut")))))
      )
      val packed = packChecked(name, out)
      assertEquals(
        right(TxOutMemPack.decodeParts[Dijkstra](packed)).script.map(_.bytes),
        Some(hex(string(row, "reference", "dijkstraScript", "bytes"))),
        name
      )
    }
  }

  // ---- bytes derived by hand from the pinned encoders

  // The expected bytes below are derived from the source of cardano-ledger 9df9f98c and mempack
  // 0.2.1.0, not captured from the reference: `MemPack (BabbageTxOut era)` tag 5 with the address,
  // value, datum and script (`eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs:164-216`); the
  // address as a `Length` and its bytes (`CompactAddr`, a `ShortByteString`,
  // `libs/cardano-ledger-core/src/Cardano/Ledger/Address.hs:415-417`); `CompactValueAdaOnly` tag 0
  // and the coin as a `VarLen` (`eras/mary/impl/src/Cardano/Ledger/Mary/Value.hs:462-492`);
  // `NoDatum` tag 0 (`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Data.hs:276-293`);
  // `AlonzoScript` tag 0 native or 1 Plutus (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/
  // Scripts.hs:513-532`); a Dijkstra native script as its memoized bytes, a `Length` and the
  // bytes (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Scripts.hs:365-368`,
  // `libs/cardano-ledger-core/src/Cardano/Ledger/MemoBytes/Internal.hs:131-135`); a Dijkstra
  // Plutus script as its language's tag, 3 for PlutusV4, and its bytes (`Dijkstra/Scripts.hs:
  // 446-465`). A tag is one byte (mempack `src/Data/MemPack.hs:1547`); a `Length` is a `VarLen`,
  // seven bits a byte, the most significant group first (`MemPack.hs:1432`).
  private val packedHead = "05" + "1d" + "61" + "66" * 28 + "00" + "bd8440" + "00"

  test("a PlutusV4 reference script packs as Dijkstra's language tag 3") {
    // 130 bytes, so that the length takes two groups: 130 = 1 * 128 + 2
    val program = "ab" * 130
    val out = stored(withScript("8204" + "5882" + program))
    val expected = hex(packedHead + "01" + "03" + "8102" + program)
    assertEquals(TxOutMemPack.encode[Dijkstra](out), Right(expected))
    assertEquals(packChecked("v4", out), expected)
    assertEquals(TxOutMemPack.decode[Dijkstra](expected), Right(out))
    // the earlier eras have no PlutusV4: not read from the output, not packed, not unpacked
    assertEquals(TxOutMemPack.encode[Conway](out), Left(Failure.UnknownScriptKind(BigInt(4))))
    val parts = right(TxOutMemPack.decodeParts[Dijkstra](expected))
    assertEquals(
      TxOutMemPack.encodeParts[Conway](parts),
      Left(Failure.NotInEra(Feature.PlutusV4, Era.Conway))
    )
    assertEquals(
      TxOutMemPack.decode[Conway](expected),
      Left(Failure.UnknownTag(TagSite.PlutusLanguage, 3))
    )
  }

  test("a native reference script with a guard packs as its bytes and unpacks whole") {
    // all of a key's signature and a guard by a key credential: 69 bytes
    val native = "8201" + "82" + "8200581c" + "44" * 28 + "8206" + "8200581c" + "55" * 28
    val out = stored(withScript("8200" + native))
    // the stored output keeps no tree for it
    assertEquals(out.scriptRef.map(_.native), Some(None))
    val expected = hex(packedHead + "00" + "45" + native)
    assertEquals(TxOutMemPack.encode[Dijkstra](out), Right(expected))
    assertEquals(packChecked("guard", out), expected)
    assertEquals(TxOutMemPack.decode[Dijkstra](expected), Right(out))
    // Conway's native script is a time lock, which has no guard
    assert(
      TxOutMemPack.decode[Conway](expected) match
        case Left(Failure.ScriptRefused(_)) => true
        case _                              => false
    )
  }

  test("a native script is unpacked whole: bytes after it are refused") {
    // `[4, 0]` and a byte after it
    val packed = hex(packedHead + "00" + "04" + "82040000")
    assert(
      TxOutMemPack.decode[Dijkstra](packed) match
        case Left(Failure.ScriptRefused(_)) => true
        case _                              => false
    )
    assert(TxOutMemPack.decode[Dijkstra](hex(packedHead + "00" + "03" + "820400")).isRight)
  }
