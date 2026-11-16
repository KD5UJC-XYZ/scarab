// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.conformance

import cats.syntax.all.*
import java.util.Locale
import java.util.concurrent.FutureTask
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes

/** The harness of the cardano-cbor-dataset: its normal form, its verdicts, its rule tables and the
  * report it writes. The samples here are written by hand, not taken from the dataset.
  */
class CborDatasetSuite extends FunSuite:
  import Embedding.{hex, zeros}
  import DatasetCheck.{Category, Kind, Outcome}

  private def normal(in: String): Either[xyz.kd5ujc.scarab.codec.Cbor.Error, String] =
    CborNormalForm.of(hex(in)).map(_.hex)

  test("the normal form makes containers definite, joins chunks and shortens heads") {
    assertEquals(normal("9f0203ff"), Right("820203"))
    assertEquals(normal("bf0304ff"), Right("a10304"))
    assertEquals(normal("9fff"), Right("80"))
    assertEquals(normal("5f41aa42bbccff"), Right("43aabbcc"))
    assertEquals(normal("7f6178617aff"), Right("62787a"))
    assertEquals(normal("190007"), Right("07"))
    assertEquals(normal("d81e9f0305ff"), Right("d81e820305"))
  }

  test("the normal form keeps key order, repeated keys, other tags and float widths") {
    for same <- Vector("a203010401", "a203010302", "d9010380", "d9050f01", "fa3f800000", "f6")
    do assertEquals(normal(same), Right(same))
  }

  test("the normal form reduces a rational to its lowest terms, the sign on the numerator") {
    assertEquals(normal("d81e820408"), Right("d81e820102"))
    assertEquals(normal("d81e9f1904001808ff"), Right("d81e82188001"))
    assertEquals(normal("d81e820223"), Right("d81e822002"))
    assertEquals(normal("d81e82c2410204"), Right("d81e820102"))
    assertEquals(normal("82d81e820609d81e820102"), Right("82d81e820203d81e820102"))
    // a zero denominator, or an item that is not an integer, is no rational to reduce
    assertEquals(normal("d81e820200"), Right("d81e820200"))
    assertEquals(normal("d81e82410204"), Right("d81e82410204"))
  }

  test("the normalisation vectors stop the run when missing, failed or without a normal form") {
    import CborDatasetMain.{Failure, NormalVector, checkVectors}
    val dir = java.nio.file.Path.of("normalization")
    val good = NormalVector("a", hex("9f01ff"), Some(hex("8101")))
    assertEquals(
      checkVectors(dir, Vector("a"), Vector.empty),
      Left(Failure.MissingVectors(Vector("a")))
    )
    assertEquals(checkVectors(dir, Vector("a"), Vector(good)), Right(1))
    assertEquals(
      checkVectors(dir, Vector("a", "b"), Vector(good)),
      Left(Failure.MissingVectors(Vector("b")))
    )
    assertEquals(
      checkVectors(dir, Vector.empty, Vector(good)),
      Left(Failure.NoVectorTable(dir.resolve("README.md")))
    )
    assertEquals(
      checkVectors(
        dir,
        Vector("a", "b"),
        Vector(good, good.copy(name = "b", expected = Some(hex("9f01ff"))))
      ),
      Left(Failure.Normaliser(Vector("b")))
    )
    assertEquals(
      checkVectors(dir, Vector("c"), Vector(good.copy(name = "c", expected = None))),
      Left(Failure.Normaliser(Vector("c")))
    )
  }

  test("the vectors are the rows of the normalization README's table") {
    val readme =
      """| #   | Name                | In        | Out    | Why |
        || --- | ------------------- | --------- | ------ | --- |
        || 1   | indefinite-array    | `9f0102ff` | `820102` | x |
        || 20  | bignum-too-large    | `c249` | `c249` | y |
        |Rows 9 to 14 are the must-not-change cases.""".stripMargin
    assertEquals(
      CborDatasetMain.vectorTable(readme),
      Vector("0001-indefinite-array", "0020-bignum-too-large")
    )
  }

  /** The default locale, and its display and format locales, set to Persian for one test and given
    * back after it. The build runs one suite at a time, so no other suite runs under it.
    */
  private val persian = FunFixture[(Locale, Locale, Locale)](
    setup = _ =>
      val saved = (
        Locale.getDefault,
        Locale.getDefault(Locale.Category.DISPLAY),
        Locale.getDefault(Locale.Category.FORMAT)
      )
      Locale.setDefault(Locale.forLanguageTag("fa-IR"))
      saved
    ,
    teardown = (default, display, format) =>
      Locale.setDefault(default)
      Locale.setDefault(Locale.Category.DISPLAY, display)
      Locale.setDefault(Locale.Category.FORMAT, format)
  )

  persian.test("a vector's name has ASCII digits under a locale with other digits") { _ =>
    assertEquals(
      CborDatasetMain.vectorTable("| 1 | indefinite-array | `9f0102ff` | `820102` | x |"),
      Vector("0001-indefinite-array")
    )
  }

  test("an empty corpus, or one that differs from its inventory, is an incomplete run") {
    import CborDatasetMain.{Failure, Inventory, checkInventory, inventoryOf}
    val corpus = java.nio.file.Path.of("conway")
    assertEquals(
      inventoryOf("""{"samples": 3, "rules": [{"name": "coin"}, {"name": "epoch"}]}"""),
      Right(Inventory(3, Set("coin", "epoch")))
    )
    val inventory = Inventory(3, Set("coin", "epoch"))
    val all = Vector("coin/valid/1" -> "coin", "coin/valid/2" -> "coin", "epoch/valid/1" -> "epoch")
    assertEquals(checkInventory(corpus, inventory, all), Right(()))
    assertEquals(
      checkInventory(corpus, inventory, Vector.empty),
      Left(Failure.EmptyCorpus(corpus))
    )
    assertEquals(
      checkInventory(corpus, inventory, all.drop(1)),
      Left(Failure.InventoryMismatch(3, 2, Vector.empty, Vector.empty))
    )
    assertEquals(
      checkInventory(corpus, inventory, all.take(2)),
      Left(Failure.InventoryMismatch(3, 2, Vector("epoch"), Vector.empty))
    )
    // a sample moved to a rule the inventory does not list, the count unchanged
    val moved = all.updated(1, "slot/valid/1" -> "slot")
    assertEquals(
      checkInventory(corpus, inventory, moved),
      Left(Failure.InventoryMismatch(3, 3, Vector.empty, Vector("slot")))
    )
    // every sample of a rule moved to an unlisted one, the count unchanged
    val renamed = all.map((path, rule) => if rule == "epoch" then path -> "era" else path -> rule)
    assertEquals(
      checkInventory(corpus, inventory, renamed),
      Left(Failure.InventoryMismatch(3, 3, Vector("epoch"), Vector("era")))
    )
  }

  private def decoded(rules: Map[String, DatasetRule], rule: String)(bytes: String): Boolean =
    rules.get(rule) match
      case Some(DatasetRule.Mapped(_, _, _, run)) => run(hex(bytes)).isRight
      case other                                  => fail(s"$rule is not mapped: $other")

  test("a Conway protocol version is read as the header reads one, its major up to 13") {
    val version = decoded(ConwayRules.rules, "protocol_version")
    val major = decoded(ConwayRules.rules, "major_protocol_version")
    assert(version("820c00") && version("820d00"), "12.0 and 13.0 are versions")
    assert(!version("820e00"), "14.0 is beyond MaxVersion")
    assert(major("0c") && major("0d"), "12 and 13 are versions")
    assert(!major("0e"), "14 is beyond MaxVersion")
  }

  test("auxiliary data under tag 259 is told by its tag at any head width") {
    val keyed = decoded(ConwayRules.rules, "auxiliary_data_map")
    for head <- Vector("d90103", "da00000103", "db0000000000000103") do
      assert(keyed(head + "a0"), s"$head")
    assert(!keyed("a0"), "a bare map is not the keyed form")
    assert(!keyed("d90104a0"), "another tag is not the keyed form")
  }

  test("a Dijkstra body's certificates and proposals are not empty, as the body requires") {
    val certificates = decoded(DijkstraRules.rules, "certificates")
    val proposals = decoded(DijkstraRules.rules, "proposal_procedures")
    for empty <- Vector("80", "d9010280") do
      assert(!certificates(empty), s"certificates $empty")
      assert(!proposals(empty), s"proposals $empty")
  }

  test("a Conway block is read with each of its transactions, as BlockTransport reads one") {
    val block = decoded(ConwayRules.rules, "block")
    val header = Embedding.headerAround(Embedding.headerBody(0, hex("00"))).hex
    val body = "a3008001800200"
    def of(bodies: String, witnesses: String, aux: String) =
      "85" + header + bodies + witnesses + aux + "80"
    assert(block(of("81" + body, "81a0", "a0")), "a block of one well-formed transaction")
    assert(!block(of("81a0", "81a0", "a0")), "a body without its inputs")
    assert(!block(of("81" + body, "81a109f6", "a0")), "a witness set of an unknown key")
    assert(!block(of("81" + body, "81a0", "a100a100f7")), "auxiliary data of an undefined value")
  }

  test(
    "a Conway part read alone takes a detached record where its parent does, and no broken one"
  ) {
    val z28 = zeros(28)
    val anchor = "60" + "5820" + zeros(32)
    val value = "00" + "a1581c" + "11" * 28 + "a14001"
    val proposal = "00" + "581de0" + z28 + "8106"
    // the rule, a part holding a `RecD` record written detached (`9f ff` then its fields), which
    // production reads at that place and a generic parse refuses, and the same part broken
    val cases = Vector(
      ("ex_units", "9fff0000", "9fff00"),
      ("ex_unit_prices", "9fffd81e820102d81e820103", "9fffd81e820102"),
      ("protocol_param_update", "a1149fff0000", "a1149fff00"),
      ("anchor", "9fff" + anchor, "9fff60"),
      ("gov_action_id", "9fff5820" + zeros(32) + "00", "9fff5820" + zeros(32)),
      ("value", "9fff" + value, "9fff00"),
      ("proposal_procedure", "9fff" + proposal + "82" + anchor, "9fff" + proposal),
      ("proposal_procedures", "819fff" + proposal + "82" + anchor, "819fff" + proposal),
      ("voting_procedure", "9fff00f6", "9fff00"),
      (
        "certificate",
        "84108200581c" + z28 + "00" + "9fff" + anchor,
        "84108200581c" + z28 + "009fff60"
      ),
      ("transaction_output", "82581d61" + z28 + "9fff" + value, "82581d61" + z28 + "9fff00")
    )
    for (rule, detached, broken) <- cases do
      assert(decoded(ConwayRules.rules, rule)(detached), s"$rule: the detached record is read")
      assert(!decoded(ConwayRules.rules, rule)(broken), s"$rule: the broken record is refused")
  }

  test("the Conway rules read detached records as production does (recd-detached corpus)") {
    import io.circe.parser
    import scala.jdk.CollectionConverters.*
    val rules = Map(
      "body" -> "transaction_body",
      "witnesses" -> "transaction_witness_set",
      "auxiliary" -> "auxiliary_data",
      "transaction" -> "transaction",
      "blockbody" -> "block"
    )
    val header = Embedding.headerAround(Embedding.headerBody(0, hex("00"))).hex
    val lines = java.nio.file.Files
      .readAllLines(java.nio.file.Path.of("fixtures/reference/recd-detached/cases.jsonl"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
    val rows = lines.map(l => parser.parse(l).fold(e => fail(e.toString), _.hcursor))
    val conway = rows.flatMap { c =>
      (
        c.get[String]("era").toOption.filter(_ == "conway"),
        c.get[String]("kind").toOption.flatMap(rules.get),
        c.get[Boolean]("accepted").toOption,
        c.get[String]("bytes").toOption,
        c.get[String]("name").toOption
      ).mapN((_, rule, accepted, bytes, name) => (rule, accepted, bytes, name))
    }
    assertEquals(conway.map(_._1).toSet, rules.values.toSet)
    assert(conway.exists(r => r._2 && r._3.contains("9fff")), "no accepted detached record")
    for (rule, accepted, bytes, name) <- conway do
      val input = if rule == "block" then "85" + header + bytes else bytes
      assertEquals(decoded(ConwayRules.rules, rule)(input), accepted, s"$rule: $name")
  }

  test("the normal form folds a bignum that fits a head and keeps one that does not") {
    assertEquals(normal("c2420100"), Right("190100"))
    assertEquals(normal("c34100"), Right("20"))
    assertEquals(normal("c243000005"), Right("05"))
    assertEquals(normal("1bffffffffffffffff"), Right("1bffffffffffffffff"))
    assertEquals(normal("c24a0001" + "00" * 8), Right("c24901" + "00" * 8))
  }

  test("the normal form walks nesting deeper than a small stack") {
    val depth = 20000
    val deep = hex("81" * depth + "00")
    val task = new FutureTask(() => CborNormalForm.of(deep).map(_.size))
    new Thread(null, task, "small", 256 * 1024).start()
    assertEquals(task.get(), Right(depth + 1))
  }

  test("a category gives the expectation class, and an unknown one none") {
    assertEquals(Kind.of("valid"), Some(Kind.Valid))
    assertEquals(Kind.of("manual-valid"), Some(Kind.Valid))
    assertEquals(Kind.of("invalid-zap-0"), Some(Kind.Invalid))
    assertEquals(Kind.of("invalid-zap-12"), Some(Kind.Invalid))
    assertEquals(Kind.of("manual-invalid"), Some(Kind.Invalid))
    assertEquals(Kind.of("invalid-zap-"), None)
    assertEquals(Kind.of("other"), None)
  }

  private val input = "825820" + "11" * 32 + "05"

  private def conway(rule: String, kind: Kind, sample: String, expected: Option[String]) =
    DatasetCheck.check(rule, ConwayRules.rules.get(rule), kind, hex(sample), expected.map(hex))

  test(
    "a valid sample meets its expectation when its re-encoding normalises to the expected bytes"
  ) {
    assertEquals(conway("transaction_input", Kind.Valid, input, Some(input)), Outcome.Met)
    // an indefinite list is written definite by the encoder, as the expected bytes are
    assertEquals(
      conway("transaction_input", Kind.Valid, "9f5820" + "11" * 32 + "05ff", Some(input)),
      Outcome.Met
    )
  }

  test("a rational is written reduced, as the reference writes it") {
    assertEquals(conway("unit_interval", Kind.Valid, "d81e820204", Some("d81e820102")), Outcome.Met)
    // the expected bytes are brought to the normal form too, where a rational is reduced
    assertEquals(conway("unit_interval", Kind.Valid, "d81e820204", Some("d81e820204")), Outcome.Met)
  }

  test("an invalid sample is met when refused and fails as a decode when accepted") {
    assertEquals(conway("credential", Kind.Invalid, "8202581c" + zeros(28), None), Outcome.Met)
    assertEquals(
      conway("credential", Kind.Invalid, "8200581c" + zeros(28), None),
      Outcome.Failed(Category.Decode, "succeeded when expected to fail")
    )
  }

  test(
    "a decoded value without an encoder, a mismatch and a missing reference are encode failures"
  ) {
    val unwritten = DatasetRule.unwritten("a decoder", Reach.Direct)(Right(_))
    assert(DatasetCheck.check("x", Some(unwritten), Kind.Valid, hex("00"), Some(hex("00"))) match
      case Outcome.Failed(Category.Encode, _) => true
      case _                                  => false)
    assert(conway("transaction_input", Kind.Valid, input, Some("8200")) match
      case Outcome.Failed(Category.Encode, _) => true
      case _                                  => false)
    assert(conway("transaction_input", Kind.Valid, input, None) match
      case Outcome.Failed(Category.Encode, _) => true
      case _                                  => false)
  }

  /** The run of a mapped rule on `sample`: its refusal, or what its writer gives. */
  private def ran(rule: String, sample: String): Either[Refusal, Either[Unwritten, Bytes]] =
    ConwayRules.rules.get(rule) match
      case Some(DatasetRule.Mapped(_, _, _, run)) => run(hex(sample))
      case other                                  => fail(s"$rule is not mapped: $other")

  private val k28 = "11" * 28
  private val p28 = "22" * 28
  private val actionId = "825820" + zeros(32) + "00"
  private val proposal = "8400581de0" + zeros(28) + "8106" + "8260" + "5820" + zeros(32)

  /** For each rule written by a `ConwayCodecs` encoder: a valid sample written by hand, and the
    * bytes the reference writes it as at version 11. Where the encoder writes another form than the
    * sample's, the two differ after the normal form too: a set gains tag 258 (the owners of the
    * pool registration, written untagged and out of order, and the certificates and proposal
    * procedures), and the voters (a pool's vote before a committee member's, which `Ord Voter` puts
    * first) and the languages come out in the order of their keys.
    */
  private val encoded = Vector(
    (
      "certificate",
      "8a03581c" + "01" * 28 + "5820" + "02" * 32 + "011a1443fd00d81e820104581de0" + "03" * 28
        + "82581c" + "05" * 28 + "581c" + "04" * 28 + "80f6",
      "8a03581c" + "01" * 28 + "5820" + "02" * 32 + "011a1443fd00d81e820104581de0" + "03" * 28
        + "d9010282581c" + "04" * 28 + "581c" + "05" * 28 + "80f6"
    ),
    (
      "certificates",
      "82" + "82008200581c" + k28 + "8304581c" + p28 + "19012c",
      "d9010282" + "82008200581c" + k28 + "8304581c" + p28 + "19012c"
    ),
    ("voter", "8202581c" + k28, "8202581c" + k28),
    ("voting_procedure", "82028261615820" + "66" * 32, "82028261615820" + "66" * 32),
    (
      "voting_procedures",
      "a2" + "8204581c" + p28 + "a1" + actionId + "8201f6" + "8200581c" + k28 + "a1" + actionId
        + "8200f6",
      "a2" + "8200581c" + k28 + "a1" + actionId + "8200f6" + "8204581c" + p28 + "a1" + actionId
        + "8201f6"
    ),
    ("proposal_procedures", "81" + proposal, "d9010281" + proposal),
    ("script", "82008200581c" + k28, "82008200581c" + k28),
    ("script", "820343010203", "820343010203"),
    (
      "redeemer",
      "84051a000100009f01ff821903e81b0000000100000000",
      "84051a000100009f01ff821903e81b0000000100000000"
    ),
    (
      "vkeywitness",
      "825820" + "88" * 32 + "5840" + "99" * 64,
      "825820" + "88" * 32 + "5840" + "99" * 64
    ),
    (
      "bootstrap_witness",
      "845820" + "88" * 32 + "5840" + "99" * 64 + "5820" + "aa" * 32 + "41a0",
      "845820" + "88" * 32 + "5840" + "99" * 64 + "5820" + "aa" * 32 + "41a0"
    ),
    ("metadatum", "bf015f41014102ff029f7f6161ffffff", "a20142010202816161"),
    ("cost_models", "a20181200081" + "00", "a200810001" + "8120")
  )

  test("each rule written by a ConwayCodecs encoder meets a valid sample written by hand") {
    for (rule, sample, expected) <- encoded do
      assertEquals(conway(rule, Kind.Valid, sample, Some(expected)), Outcome.Met, s"$rule $sample")
  }

  test("Dijkstra's shared values use the production encoders after its own decoder") {
    val sharedWriters = Set(
      "voter",
      "voting_procedure",
      "voting_procedures",
      "vkeywitness",
      "bootstrap_witness",
      "metadatum"
    )
    for (rule, sample, expected) <- encoded if sharedWriters(rule) do
      assertEquals(
        DatasetCheck
          .check(rule, DijkstraRules.rules.get(rule), Kind.Valid, hex(sample), Some(hex(expected))),
        Outcome.Met,
        rule
      )
    for (rule, sample, expected) <- encoded if Set("vkeywitness", "bootstrap_witness")(rule) do
      val wide = "98" + (if rule == "vkeywitness" then "02" else "04") + sample.drop(2)
      DijkstraRules.rules.get(rule) match
        case Some(DatasetRule.Mapped(_, _, _, run)) =>
          assertEquals(run(hex(wide)), Right(Right(hex(expected))), rule)
        case other => fail(s"$rule is not mapped: $other")
    val assets = "a1581c" + zeros(28) + "a14001"
    for (rule, sample) <- Vector(
        "value" -> ("8200" + assets),
        "%multiasset<positive_coin>" -> assets
      )
    do
      assertEquals(
        DatasetCheck
          .check(rule, DijkstraRules.rules.get(rule), Kind.Valid, hex(sample), Some(hex(sample))),
        Outcome.Met
      )
    assertEquals(
      DatasetCheck.check(
        "%multiasset<positive_coin>",
        DijkstraRules.rules.get("%multiasset<positive_coin>"),
        Kind.Invalid,
        hex("a0"),
        None
      ),
      Outcome.Met,
      "Dijkstra refuses the empty multiasset that Conway accepts"
    )
  }

  test("Dijkstra redeemers preserve their exact bytes from the decoded witness set") {
    val sample = "bf820000829f01ff821903e81b0000000100000000ff"
    assertEquals(
      DatasetCheck.check(
        "redeemers",
        DijkstraRules.rules.get("redeemers"),
        Kind.Valid,
        hex(sample),
        Some(hex(sample))
      ),
      Outcome.Met
    )
    val changed = "a1820000828101821903e81b0000000100000000"
    assert(
      DatasetCheck.check(
        "redeemers",
        DijkstraRules.rules.get("redeemers"),
        Kind.Valid,
        hex(sample),
        Some(hex(changed))
      ) match
        case Outcome.Failed(Category.Encode, _) => true
        case _                                  => false
    )
  }

  test("the encoder, not the sample's bytes, writes a set, voters and cost models") {
    val rewritten = Set(
      "certificate",
      "certificates",
      "voting_procedures",
      "proposal_procedures",
      "cost_models"
    )
    for (rule, sample, expected) <- encoded if rewritten(rule) do
      assertNotEquals(
        CborNormalForm.of(hex(sample)),
        CborNormalForm.of(hex(expected)),
        s"$rule: the sample's own normal form would meet the expected bytes"
      )
      assertEquals(ran(rule, sample).map(_.map(_.hex)), Right(Right(expected)), rule)
  }

  /** A sample whose heads the encoder writes shorter and whose containers it writes definite, and
    * the bytes it writes: forms the normal form erases, so the comparison with the expected bytes
    * alone would not tell the encoder from the sample's own bytes. A script's outer pair is written
    * by the encoder, the native script inside it as the bytes it was read with.
    */
  private val reframed = Vector(
    ("script", "821800" + "8200581c" + k28, "8200" + "8200581c" + k28),
    ("script", "82180343010203", "820343010203"),
    ("metadatum", "bf015f41014102ff029f7f6161ffffff", "a20142010202816161")
  )

  test("a script and a metadatum are written from their values, not as the sample's bytes") {
    for (rule, sample, written) <- reframed do
      assertEquals(conway(rule, Kind.Valid, sample, Some(written)), Outcome.Met, s"$rule $sample")
      assertEquals(ran(rule, sample).map(_.map(_.hex)), Right(Right(written)), s"$rule $sample")
  }

  test("a Conway block is written as the header and body segments it was read with") {
    val header = Embedding.headerAround(Embedding.headerBody(0, hex("00"))).hex
    val block = "85" + header + "81a3008001800200" + "81a0" + "a0" + "80"
    assertEquals(conway("block", Kind.Valid, block, Some(block)), Outcome.Met)
    assertEquals(ran("block", block).map(_.map(_.hex)), Right(Right(block)))
  }

  test("a value the production encoder refuses is a typed refusal, never written") {
    import xyz.kd5ujc.scarab.ledger.Voter
    import xyz.kd5ujc.scarab.storage.haskell.state.ConwayCodecs
    assert(ConwayRules.written(ConwayCodecs.voter)(Voter.Pool(hex("00"))) match
      case Left(Unwritten.Refused(_)) => true
      case _                          => false)
  }

  test("a language is read as a bounded enum and re-encoded from its value in either era") {
    for
      rules <- Vector(ConwayRules.rules, DijkstraRules.rules)
      id <- 0 to 3
    do
      val expected = hex("0" + id.toHexString)
      val sample = hex("1b000000000000000" + id.toString)
      assertEquals(
        DatasetCheck.check("language", rules.get("language"), Kind.Valid, sample, Some(expected)),
        Outcome.Met
      )
      rules.get("language") match
        case Some(DatasetRule.Mapped(_, _, _, run)) =>
          assertEquals(run(sample), Right(Right(expected)))
        case other => fail(s"language is not mapped: $other")
    for
      rules <- Vector(ConwayRules.rules, DijkstraRules.rules)
      invalid <- Vector("20", "04", "1818", "1bffffffffffffffff", "f6", "c24100")
    do
      assertEquals(
        DatasetCheck.check("language", rules.get("language"), Kind.Invalid, hex(invalid), None),
        Outcome.Met,
        invalid
      )
    assertEquals(
      conway("language", Kind.Invalid, "00", None),
      Outcome.Failed(Category.Decode, "succeeded when expected to fail")
    )
  }

  test("an empty MultiAsset is written as a map, while an empty mint remains refused") {
    for sample <- Vector("a0", "bfff") do
      assertEquals(
        conway("%multiasset<positive_coin>", Kind.Valid, sample, Some("a0")),
        Outcome.Met
      )
      assertEquals(ran("%multiasset<positive_coin>", sample), Right(Right(hex("a0"))))
      assert(!decoded(ConwayRules.rules, "mint")(sample))
      assert(!decoded(DijkstraRules.rules, "mint")(sample))
    val nonempty = "a1581c" + zeros(28) + "a14001"
    assertEquals(
      conway("%multiasset<positive_coin>", Kind.Valid, nonempty, Some(nonempty)),
      Outcome.Met
    )
  }

  test("an address's embedded Byron payload is re-encoded, including its checksum") {
    // A hand-written address: root bytes all zero, no attributes, type 0. The payload array's
    // wide head is shortened on re-encoding; each CRC-32 covers its own payload bytes.
    val sample = "582c82d81858229803581c" + zeros(28) + "a0001ab183b518"
    val expected = "582b82d818582183581c" + zeros(28) + "a0001ade77bc61"
    assertNotEquals(normal(sample), normal(expected))
    for rules <- Vector(ConwayRules.rules, DijkstraRules.rules) do
      assertEquals(
        DatasetCheck
          .check("address", rules.get("address"), Kind.Valid, hex(sample), Some(hex(expected))),
        Outcome.Met
      )
      assert(!decoded(rules, "address")(sample.dropRight(2) + "00"), "a wrong CRC is refused")
  }

  test("a rule Scarab has no decoder of fails every sample, valid or invalid") {
    val unmapped = DatasetRule.Unmapped(Unmapped.NoDecoder)
    for kind <- Kind.values do
      assert(DatasetCheck.check("unmapped", Some(unmapped), kind, hex("00"), Some(hex("00"))) match
        case Outcome.Failed(Category.Decode, _) => true
        case _                                  => false)
    assert(DatasetCheck.check("no-such-rule", None, Kind.Invalid, hex("00"), None) match
      case Outcome.Failed(Category.Decode, _) => true
      case _                                  => false)
  }

  test("a byte-exact rule compares the kept bytes without normalising them") {
    val body = "a3008001800200"
    assertEquals(conway("transaction_body", Kind.Valid, body, Some(body)), Outcome.Met)
    val indefinite = "bf008001800200ff"
    assert(conway("transaction_body", Kind.Valid, indefinite, Some(body)) match
      case Outcome.Failed(Category.Encode, _) => true
      case _                                  => false)
  }

  test("an item is found in a parent's encoding by its path") {
    assertEquals(
      Step.at(hex("82a10205f6"), List(Step.Index(0), Step.Key(2))).map(_.hex),
      Right("05")
    )
    assertEquals(Step.at(hex("a1410102"), List(Step.FirstKey)).map(_.hex), Right("4101"))
    assert(Step.at(hex("80"), List(Step.Index(0))).isLeft)
  }

  /** The rules of the dataset's `EraSpec.hs` (cardano-cbor-dataset ed6c49a4), the names of the
    * corpus directories: every one has an entry, mapped or with the reason it is not.
    */
  private val shared = Set(
    "block",
    "header",
    "header_body",
    "transaction",
    "transaction_body",
    "transaction_input",
    "transaction_witness_set",
    "transaction_output",
    "value",
    "script",
    "datum_option",
    "certificate",
    "certificates",
    "native_script",
    "plutus_data",
    "redeemers",
    "auxiliary_data",
    "credential",
    "drep",
    "relay",
    "gov_action",
    "voting_procedure",
    "voting_procedures",
    "proposal_procedure",
    "proposal_procedures",
    "protocol_param_update",
    "cost_models",
    "voter",
    "metadatum",
    "drep_voting_thresholds",
    "pool_voting_thresholds",
    "ex_units",
    "anchor",
    "gov_action_id",
    "constitution",
    "operational_cert",
    "bootstrap_witness",
    "vkeywitness",
    "withdrawals",
    "mint",
    "address",
    "reward_account",
    "protocol_version",
    "coin",
    "epoch",
    "slot",
    "block_number",
    "network_id",
    "url",
    "dns_name",
    "unit_interval",
    "nonnegative_interval",
    "transaction_id",
    "script_hash",
    "addr_keyhash",
    "pool_keyhash",
    "vrf_keyhash",
    "auxiliary_data_hash",
    "policy_id",
    "asset_name",
    "stake_credential",
    "drep_credential",
    "committee_cold_credential",
    "committee_hot_credential",
    "vote",
    "ex_unit_prices",
    "pool_metadata",
    "kes_period",
    "vkey",
    "%multiasset<positive_coin>",
    "alonzo_transaction_output",
    "language",
    "auxiliary_data_array",
    "auxiliary_scripts",
    "hash28",
    "hash32",
    "script_data_hash",
    "guardrails_script_hash",
    "major_protocol_version",
    "positive_coin",
    "nonzero_int64",
    "big_int",
    "bounded_bytes",
    "ipv4",
    "ipv6",
    "kes_vkey",
    "vrf_vkey",
    "signature",
    "%constr<plutus_data>",
    "plutus_v1_script",
    "plutus_v2_script",
    "plutus_v3_script",
    "data",
    "metadata",
    "auxiliary_data_map",
    "babbage_transaction_output"
  )

  test("every rule of each era has an entry") {
    assertEquals(ConwayRules.rules.keySet, shared + "redeemer")
    assertEquals(
      DijkstraRules.rules.keySet,
      shared ++ Set(
        "eb_announcement",
        "block_body",
        "sub_transaction_body",
        "account_balance_interval",
        "account_balance_intervals"
      )
    )
  }

  test("each era is read at its eraProtVerHigh, the version the report names") {
    import xyz.kd5ujc.scarab.ledger.DecoderVersion
    import xyz.kd5ujc.scarab.protocol.Era
    assertEquals(ConwayRules.version, DecoderVersion.high(Era.Conway))
    assertEquals(DijkstraRules.version, DecoderVersion.high(Era.Dijkstra))
    assertEquals(CborDatasetMain.DatasetEra.Conway.version, ConwayRules.version)
    assertEquals(CborDatasetMain.DatasetEra.Dijkstra.version, DijkstraRules.version)
    assertEquals(CborDatasetMain.DatasetEra.Conway.protocolVersion, "11.0")
    assertEquals(CborDatasetMain.DatasetEra.Dijkstra.protocolVersion, "12.0")
  }

  test("the report holds the version 2 fields and one result per sample path") {
    val json = CborDatasetMain.reportJson(
      "11.0",
      "a" * 40,
      "b" * 40,
      1L,
      Vector(
        "coin/valid/00001-x.input.cbor" -> Outcome.Met,
        "coin/manual-invalid/y.input.cbor" -> Outcome.Failed(
          Category.Decode,
          "succeeded when expected to fail"
        )
      )
    )
    val keys = json.asObject.map(_.keys.toSet)
    assertEquals(
      keys,
      Some(
        Set(
          "version",
          "generated_at",
          "protocol_version",
          "dataset_commit",
          "implementation_commit",
          "results"
        )
      )
    )
    assertEquals(json.hcursor.downField("version").as[String], Right("2"))
    assertEquals(
      json.hcursor.downField("results").downField("coin/valid/00001-x.input.cbor").as[Boolean],
      Right(true)
    )
    assertEquals(
      json.hcursor.downField("results").downField("coin/manual-invalid/y.input.cbor").as[String],
      Right("decode: succeeded when expected to fail")
    )
  }
