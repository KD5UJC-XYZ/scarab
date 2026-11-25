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
  Credential,
  DecoderVersion,
  LedgerDecodeError,
  PreprodDeepTx,
  TxOut
}
import xyz.kd5ujc.scarab.ledger.dijkstra.{DijkstraScript as FlatDijkstraScript, V12}
import xyz.kd5ujc.scarab.ledger.era.{BothLevels, EraTxBody, EraTxError, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxOut
import xyz.kd5ujc.scarab.protocol.Era

/** Dijkstra's native script ([[DijkstraNativeScript]]) against the Leios build:
  *   - every native script of every transaction the build accepted in
  *     `fixtures/reference/leios-testnet-blocks` (witness sets, auxiliary data, reference scripts,
  *     sub-transactions) reads, with its bytes and hash, and between them every constructor occurs;
  *   - the scripts the corpus names by their bytes read or are refused as the build did with the
  *     transaction that holds them;
  *   - the decoder agrees with the block decoder's ([[FlatDijkstraScript.native]]) on each of those
  *     and on malformed forms, verdict and refusal alike;
  *   - a Conway script carried into Dijkstra has the bytes and the hash the reference's
  *     `upgradeTimelock` gives, on the native reference scripts of
  *     `fixtures/reference/dijkstra-translation`.
  */
class DijkstraNativeScriptSuite extends FunSuite:
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

  // Both decoders on the encoding `raw`: this type's and the block decoder's.
  private def ours(raw: Bytes): Either[LedgerDecodeError, DijkstraNativeScript] =
    CborRead.decode(raw).flatMap(DijkstraNativeScript.read)
  private def theirs(raw: Bytes): Either[LedgerDecodeError, FlatDijkstraScript] =
    CborRead.decode(raw).flatMap(FlatDijkstraScript.native)

  private val blocks = lines("fixtures/reference/leios-testnet-blocks/cases.jsonl")
  private val txs = blocks.filter(r => string(r, "kind") == "tx")

  private def accepted(r: Json): Boolean = field(r, "accepted").flatMap(_.asBoolean).contains(true)

  /** The native scripts of a transaction the build read: its witness set's, its auxiliary data's,
    * its outputs' reference scripts, and those of each sub-transaction.
    */
  private def natives(tx: DijkstraTx[TxLevel.Top]): Vector[Bytes] =
    val b = EraTxBody.dijkstra
    def ofWits(w: DijkstraTxWits, aux: Option[DijkstraTxAuxData]) =
      w.scriptArrival
        .flatMap(h => w.scripts.get(h))
        .flatMap(DijkstraScript.instance.getNativeScript)
        .map(_.raw) ++
        aux.toVector.flatMap(_.nativeScripts).map(_.raw)
    // the outputs' reference scripts, each output read again by the output decoder
    def ofOutputs(outputs: Vector[DijkstraTxOut]) = outputs
      .map(o =>
        xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxOut
          .decode(DijkstraCorpus.node(o.rep.raw))
          .fold(e => fail(e.toString), identity)
      )
      .flatMap(_.referenceScript)
      .collect { case FlatDijkstraScript.Native(raw) => raw }
    val top = ofWits(tx.wits, tx.auxData) ++
      ofOutputs(b.allSizedOutputs[TxLevel.Top](tx.body)(using BothLevels.Top).map(_.value))
    top ++ b
      .subTransactions(tx.body)
      .toVector
      .flatMap(s =>
        ofWits(s.wits, s.auxData) ++
          ofOutputs(b.outputs[TxLevel.Sub](s.body)(using BothLevels.Sub))
      )

  private val readScripts: Vector[(String, Bytes)] = txs.filter(accepted).flatMap { r =>
    val raw = hex(string(r, "bytes"))
    CborRead
      .decode(raw, V12.nestedLimits)
      .fold(e => fail(s"${string(r, "name")}: not CBOR: $e"), DijkstraTx.inBlock)
      .fold(e => fail(s"${string(r, "name")}: the build accepted it: $e"), natives)
      .map(string(r, "name") -> _)
  }

  test("every native script the build read reads, with its bytes and its hash") {
    assert(readScripts.nonEmpty)
    readScripts.foreach { (name, raw) =>
      val script = ours(raw).fold(e => fail(s"$name: $e"), identity)
      assertEquals(script.raw, raw, name)
      assertEquals(script.hash, FlatDijkstraScript.Native(raw).hash, name)
    }
  }

  test("the scripts the build read hold every constructor, the guard among them") {
    import DijkstraNativeScript.Form
    // the constructors of every level of every script, walked on the heap
    def forms(s: DijkstraNativeScript): Vector[Form] =
      Iterator
        .iterate((Vector(s), Vector.empty[Form])) { (todo, seen) =>
          (todo.tail ++ subs(todo.head), seen :+ todo.head.form)
        }
        .dropWhile(_._1.nonEmpty)
        .next()
        ._2
    def subs(s: DijkstraNativeScript): Vector[DijkstraNativeScript] =
      s.getRequireAllOf
        .orElse(s.getRequireAnyOf)
        .orElse(s.getRequireMOf.map(_._2))
        .getOrElse(
          Vector.empty
        )
    // one per constructor, by an exhaustive match: a new constructor fails to compile here
    enum Kind:
      case Signature, AllOf, AnyOf, MOf, Start, Expire, Guard
    def kind(f: Form): Kind = f match
      case Form.RequireSignature(_) => Kind.Signature
      case Form.RequireAllOf(_)     => Kind.AllOf
      case Form.RequireAnyOf(_)     => Kind.AnyOf
      case Form.RequireMOf(_, _)    => Kind.MOf
      case Form.TimeStart(_)        => Kind.Start
      case Form.TimeExpire(_)       => Kind.Expire
      case Form.RequireGuard(_)     => Kind.Guard
    val seen = readScripts.flatMap((_, raw) => ours(raw).toOption.toVector.flatMap(forms))
    assertEquals(seen.map(kind).toSet, Kind.values.toSet)
  }

  /** The scripts the corpus names by their encoding (`tx witnesses native script <hex>`), with the
    * build's verdict on the transaction holding one.
    */
  private val named: Vector[(Bytes, Boolean)] = txs.flatMap { r =>
    val prefix = "tx witnesses native script "
    val name = string(r, "name")
    // the names that end in an encoding, not those that describe one ("nested 1000 deep")
    val encoding = name.drop(prefix.length)
    Option
      .when(name.startsWith(prefix) && encoding.forall(c => "0123456789abcdef".contains(c)))(
        (hex(encoding), accepted(r))
      )
      .toVector
  }

  test("a script the corpus names reads as the build read the transaction holding it") {
    assertEquals(named.size, 10)
    named.foreach((raw, verdict) => assertEquals(ours(raw).isRight, verdict, raw.hex))
  }

  /** Malformed forms at each of the decoder's checks: an unknown tag, a tag in a wider head, each
    * constructor with an item too few or too many, a key hash of 27 bytes, `m` past a signed 64-bit
    * integer, a slot past `Word64`, a guard whose credential has an unknown tag, a sub-script that
    * is malformed, an empty and a non-list script, and lists in each form.
    */
  private val forms: Vector[String] = Vector(
    "8200581c" + "11" * 28,
    "8201" + "80",
    "82019f" + "8200581c" + "11" * 28 + "ff",
    "8202" + "81" + "8200581c" + "11" * 28,
    "8303" + "01" + "82" + "8200581c" + "11" * 28 + "8204" + "00",
    "8303" + "3b7fffffffffffffff" + "80",
    "8303" + "1b7fffffffffffffff" + "80",
    "8303" + "1b8000000000000000" + "80",
    "8303" + "3b8000000000000000" + "80",
    "8204" + "1bffffffffffffffff",
    "8205" + "c249010000000000000000",
    "8206" + "8200581c" + "22" * 28,
    "8206" + "8201581c" + "22" * 28,
    "8206" + "8202581c" + "22" * 28,
    "8206" + "8201581b" + "22" * 27,
    "8207" + "00",
    "8218" + "00",
    "820600",
    "83060000",
    "8100",
    "80",
    "00",
    "8200581b" + "11" * 27,
    "8201" + "81" + "8207" + "00",
    "8201" + "a0",
    "9f00581c" + "11" * 28 + "ff"
  )

  test("the decoder agrees with the block decoder's, verdict and refusal") {
    (forms ++ named.map(_._1.hex) ++ readScripts.map(_._2.hex)).foreach { h =>
      val raw = hex(h)
      assertEquals(ours(raw).map(_.raw), theirs(raw).map(_ => raw), h)
    }
    // the malformed forms are refused, so the comparison covers refusals too
    assert(forms.count(h => ours(hex(h)).isLeft) >= 12)
  }

  test("each constructor answers its own reader and no other") {
    val guard = ours(hex("8206" + "8201581c" + "22" * 28)).fold(e => fail(e.toString), identity)
    assertEquals(guard.getRequireGuard, Some(Credential.Script(hex("22" * 28))))
    assertEquals(guard.getRequireSignature, None)
    val mOf = ours(hex("8303" + "3b7fffffffffffffff" + "80")).fold(e => fail(e.toString), identity)
    assertEquals(mOf.getRequireMOf, Some((BigInt(Long.MinValue), Vector.empty)))
    assertEquals(mOf.getRequireGuard, None)
    val expire = ours(hex("8205" + "1bffffffffffffffff")).fold(e => fail(e.toString), identity)
    assertEquals(expire.getTimeExpire, Some(BigInt("18446744073709551615")))
    assertEquals(expire.getTimeStart, None)
  }

  // What the Leios build's ledger gives for `[1, [[4, 0], [5, 0]]]`, read at version 12, and for
  // the same script read at Conway's version 9 and carried in by `upgradeTimelock`, as a probe of
  // the pinned image printed them on 2026-11-15 (`originalBytes`, `hashScript`, `==`).
  private val decodedNoBytesHash = "0d94e174732ef9aae73f395ab44507bfa983d65023c11a951f0c32e4"

  test("a decoded sub-script has no bytes, as the reference reads it, and equals any other") {
    val script = ours(hex("820182820400820500")).fold(e => fail(e.toString), identity)
    val subs = script.getRequireAllOf.getOrElse(fail("not all-of"))
    assertEquals(script.raw, hex("820182820400820500"))
    assertEquals(subs.map(_.raw), Vector(Bytes.empty, Bytes.empty))
    assertEquals(subs.map(_.hash.hex), Vector(decodedNoBytesHash, decodedNoBytesHash))
    // `[4, 0]` and `[5, 0]` compare equal: their memoized hashes are both the default
    assertEquals(subs(0), subs(1))
    assertEquals(subs.map(_.getTimeStart), Vector(Some(BigInt(0)), None))
    // a script with bytes is unequal to a decoded sub-script
    assertNotEquals(ours(hex("820400")).fold(e => fail(e.toString), identity), subs(0))
  }

  test("a translated script's sub-scripts each memoize their own encoding") {
    given ByteStrings = ByteStrings.of(DecoderVersion.of(Era.Conway))
    val conway = CborRead
      .decode(hex("82019f820400820500ff"))
      .left
      .map(_.toString)
      .flatMap(xyz.kd5ujc.scarab.ledger.era.conway.ConwayTimelock.decode(_).left.map(_.toString))
      .fold(e => fail(e), identity)
    val translated = DijkstraNativeScript.translate(conway).fold(e => fail(e.toString), identity)
    assertEquals(translated.raw, hex("820182820400820500"))
    val subs = translated.getRequireAllOf.getOrElse(fail("not all-of"))
    assertEquals(subs.map(_.raw.hex), Vector("820400", "820500"))
    assertNotEquals(subs(0), subs(1))
  }

  test("a script as deep as a block reads on a small stack") {
    // `[1, [[1, [ … [0, keyhash] … ]]]]`, 20,000 levels
    val raw = hex(("8201" + "81").repeat(20000) + "8200581c" + "11" * 28)
    assertEquals(PreprodDeepTx.onSmallStack(ours(raw)).map(_.raw), Right(raw))
  }

  test(
    "a guard-free script's time-lock tree is the one the time-lock reader reads; a guard has none"
  ) {
    given xyz.kd5ujc.scarab.ledger.ByteStrings = xyz.kd5ujc.scarab.ledger.ByteStrings.Chunked
    val key = "8200581c" + "22" * 28
    // all of: a signature, any of a validity start, 2 of an end and the signature
    val guardFree =
      hex("8201" + "83" + key + "820281820401" + "8303" + "02" + "82" + "820505" + key)
    val flatTree = CborRead
      .decode(guardFree)
      .flatMap(
        xyz.kd5ujc.scarab.ledger.Script
          .native(_, xyz.kd5ujc.scarab.ledger.NativeScript.Form.Timelock)
      )
      .fold(e => fail(e.toString), _.native)
    assertEquals(ours(guardFree).map(_.timelock), Right(flatTree))
    assert(flatTree.isDefined)
    // the same, with a guard nested two levels down
    val guarded = hex("8201" + "82" + key + "820281" + "8206" + "8200581c" + "33" * 28)
    assertEquals(ours(guarded).map(_.timelock), Right(None))
  }

  test("a script as deep as a block has its time-lock tree on a small stack") {
    val raw = hex(("8201" + "81").repeat(20000) + "8200581c" + "11" * 28)
    val script = ours(raw).fold(e => fail(e.toString), identity)
    val tree = PreprodDeepTx.onSmallStack(Right(script.timelock)).fold(identity, identity)
    assert(tree.isDefined)
    val guarded = hex(("8201" + "81").repeat(20000) + "8206" + "8200581c" + "11" * 28)
    val deepGuard = ours(guarded).fold(e => fail(e.toString), identity)
    assertEquals(PreprodDeepTx.onSmallStack(Right(deepGuard.timelock)), Right(None))
  }

  // ---- the translation from Conway

  private val translations = lines("fixtures/reference/dijkstra-translation/cases.jsonl")

  private def conwayOutput(raw: Bytes): ConwayTxOut =
    given ByteStrings = ByteStrings.of(DecoderVersion.of(Era.Conway))
    TxOut
      .fromBytes(raw, Address.Mode.of(Era.Conway))
      .left
      .map(_.toString)
      .flatMap(ConwayTxOut.fromStored(_).left.map(_.toString))
      .fold(e => fail(e), identity)

  private val nativeTranslations = translations.flatMap { row =>
    val name = string(row, "name")
    val out = conwayOutput(hex(string(row, "txOut")))
    out.rep.scriptRef
      .flatMap(s => xyz.kd5ujc.scarab.ledger.era.conway.ConwayScript.instance.getNativeScript(s))
      .map(native => (name, native, row))
  }

  test("the translation corpus holds native reference scripts of each form") {
    assert(nativeTranslations.sizeIs >= 10, nativeTranslations.size.toString)
  }

  nativeTranslations.foreach { (name, native, row) =>
    test(s"$name: a Conway script carried into Dijkstra as the reference carries it") {
      val translated = DijkstraNativeScript.translate(native).fold(e => fail(e.toString), identity)
      assertEquals(translated.raw, hex(string(row, "reference", "dijkstraScript", "bytes")))
      assertEquals(translated.hash, hex(string(row, "reference", "dijkstraScript", "hash")))
    }
  }

  test("a deep Conway script carries into Dijkstra on a small stack") {
    val depth = 1000
    val open = ("8201" + "81").repeat(depth)
    val raw = hex(open + "8200581c" + "11" * 28)
    given ByteStrings = ByteStrings.of(DecoderVersion.of(Era.Conway))
    val conway = CborRead
      .decode(raw)
      .left
      .map(_.toString)
      .flatMap(xyz.kd5ujc.scarab.ledger.era.conway.ConwayTimelock.decode(_).left.map(_.toString))
      .fold(e => fail(e), identity)
    val translated: Either[EraTxError, DijkstraNativeScript] =
      PreprodDeepTx.onSmallStack(DijkstraNativeScript.translate(conway))
    // a definite list of one is the form Dijkstra writes, so the bytes are the Conway ones
    assertEquals(translated.map(_.raw), Right(raw))
  }
