// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models.gen

import java.nio.file.{Files, Path, Paths}

class GeneratorSuite extends munit.FunSuite:
  private val cddlDir: Path =
    Vector(Paths.get("models/src/main/cddl"), Paths.get("../models/src/main/cddl"))
      .find(Files.isDirectory(_))
      .getOrElse(fail("cddl directory not found"))

  private lazy val schema: Schema =
    Generator
      .configFrom(cddlDir)
      .flatMap(Generator.load(cddlDir, _))
      .fold(e => fail(e.message), identity)

  private def resolved(q: QName): RuleDef = schema.rule(q).fold(e => fail(e.message), identity)

  /** Generate from an inline schema in a temporary directory. */
  private def generateFrom(cddl: String, conf: String): Either[GenError, Map[String, String]] =
    val dir = Files.createTempDirectory("models-gen-test")
    Files.writeString(dir.resolve("t.cddl"), cddl)
    Config
      .parse(s"package test.models\nmodule t t t.cddl\n$conf")
      .flatMap(Generator.generate(dir, _))

  test("conway.cddl: the spliced header body has ten members, the Babbage-style nested layout") {
    val RuleBody.Type(RT.Arr(es)) = resolved(QName("conway", "header_body")).body: @unchecked
    assertEquals(es.size, 10)
    assertEquals(es.head.label, Some("block_number"))
  }

  test("alonzo.cddl: operational_cert and protocol_version are group rules spliced into the body") {
    val RuleBody.Type(RT.Arr(es)) = resolved(QName("alonzo", "header_body")).body: @unchecked
    assertEquals(es.size, 11)
    assert(es.takeRight(2).forall(_.value.isInstanceOf[RT.GroupRef]))
    assert(resolved(QName("alonzo", "operational_cert")).body.isInstanceOf[RuleBody.GroupBody])
  }

  test("generics expand: the hard-fork-combinator header is a choice of eight tagged pairs") {
    val RuleBody.Type(RT.Choice(alts)) =
      resolved(QName("chainsync-header", "header")).body: @unchecked
    assertEquals(alts.size, 8)
    val RT.Arr(Vector(tag, payload)) = alts(1): @unchecked
    assertEquals(tag.value, RT.LitInt(6))
    // #6.24(bytes .cbor conway.header)
    assertEquals(payload.value, RT.Tagged(24, RT.CborOf(RT.Ref(QName("conway", "header")))))
  }

  test("controls are interpreted: .size, ranges, .lt and .cbor") {
    assertEquals(resolved(QName("conway", "hash32")).body, RuleBody.Type(RT.SizedBytes(32, 32)))
    assertEquals(
      resolved(QName("conway", "major_protocol_version")).body,
      RuleBody.Type(RT.IntRange(0, 12))
    )
    assertEquals(
      resolved(QName("conway", "slot")).body,
      RuleBody.Type(RT.IntRange(0, (BigInt(1) << 64) - 1))
    )
    assertEquals(resolved(QName("byron", "u16")).body, RuleBody.Type(RT.IntRange(0, 65535)))
  }

  test(
    "the RFC 8610 prelude names that cuddle's postlude defines resolve (found by the cross-check)"
  ) {
    // byron.cddl uses `bigint` and `encoded-cbor` without defining them
    // resolving used to throw UnresolvedName("byron", "bigint")
    assert(resolved(QName("byron", "bvermod")).body.isInstanceOf[RuleBody.Type])
    val out = generateFrom(
      "r = [bigint, encoded-cbor, tdate, uri]\n",
      "root t:r\n"
    ).fold(e => fail(e.message), identity)
    val text = out.values.mkString
    assert(text.contains("rt.Codec.tagged(BigInt(24), rt.Codec.bytes)"), text)
    assert(text.contains("case Tag2(value: rt.Bytes)") && text.contains("case Tag3"), text)
  }

  test("sockets accumulate: $x /= a, $x /= b is a choice") {
    assertEquals(
      resolved(QName("features", "$crypto_alg")).body,
      RuleBody.Type(RT.Choice(Vector(RT.LitInt(0), RT.LitInt(1), RT.LitInt(2))))
    )
  }

  test("every conway.cddl rule parses and the file has the expected size") {
    val rules = schema.modules("conway").rules
    assert(rules.size > 150, s"only ${rules.size} rules")
    assert(rules.contains("transaction_body") && rules.contains("certificate"))
  }

  test("emitted code for a small schema is readable and uses the declared names") {
    val out = generateFrom(
      "root = [ id : id32, ? note : tstr ]\nid32 = bytes .size 32\n",
      "root t:root\npreserve t:root\n"
    ).fold(e => fail(e.message), identity)
    val text = out.values.mkString
    assert(text.contains("opaque type Id32 = rt.Bytes"), text)
    assert(
      text.contains("final case class Root(id: Id32, note: Option[String])(val raw: rt.Bytes)"),
      text
    )
    assert(text.contains("rt.Codec.bytesSized(32, 32)"), text)
  }

  test("generation errors are typed, not strings") {
    assertEquals(
      generateFrom("a = missing\n", "root t:a\n").left.toOption,
      Some(GenError.UnresolvedName("t", "missing"))
    )
    assert(
      generateFrom("a = [ * uint, bytes ]\n", "root t:a\n").left.toOption
        .exists(_.isInstanceOf[GenError.Unsupported])
    )
    assert(
      generateFrom("a = tstr .regexp \"x+\"\n", "root t:a\n").left.toOption
        .exists(_.isInstanceOf[GenError.Unsupported])
    )
    assert(
      generateFrom("a = [ ]\n", "root t:b\n").left.toOption
        .exists(_.isInstanceOf[GenError.UnresolvedName])
    )
    assert(
      generateFrom("a = uint\n", "root t:a\npreserve t:a\n").left.toOption
        .exists(_.isInstanceOf[GenError.Unsupported]),
      "preserve is only meaningful on arrays and maps"
    )
  }

  test("coverage: how many rules of each pinned specification can be generated on their own") {
    val config = Generator.configFrom(cddlDir).fold(e => fail(e.message), identity)
    val report = scala.collection.mutable.ArrayBuffer.empty[String]
    val reasons = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var totalOk = 0
    var total = 0
    for m <- config.modules.map(_.name).sorted do
      val rules = schema
        .modules(m)
        .rules
        .collect { case (n, (defs, _)) if defs.head.params.isEmpty => n }
        .toVector
        .sorted
      var ok = 0
      rules.foreach { n =>
        val q = QName(m, n)
        val single = config.copy(
          roots = Vector(q),
          preserve = Set.empty,
          external = Set.empty,
          cases = Map.empty
        )
        val result = new Emitter(schema, single).generate().left.toOption
        result match
          case None => ok += 1
          case Some(e) =>
            val key = e match
              case GenError.Unsupported(_, f) => f.productPrefix
              case other                      => other.getClass.getSimpleName
            reasons(key) += 1
      }
      report += f"$m%-40s ${rules.size}%4d rules, ${ok}%4d generate on their own"
      totalOk += ok
      total += rules.size
    println(report.mkString("\n"))
    println(
      reasons.toVector
        .sortBy(-_._2)
        .map((k, v) => s"  $v x $k")
        .mkString("unsupported features by frequency:\n", "\n", "")
    )
    println(s"total: $totalOk of $total non-generic rules")
    assert(totalOk * 100 >= total * 50, s"only $totalOk of $total rules generate")
  }

  test("name clashes between rules are reported") {
    val r = generateFrom("a-b = uint\na_b = uint\nr = [a-b, a_b]\n", "root t:r\n")
    assert(r.left.toOption.exists(_.isInstanceOf[GenError.NameClash]), r.toString)
  }
