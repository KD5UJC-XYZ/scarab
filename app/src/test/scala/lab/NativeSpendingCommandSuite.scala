// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.unsafe.implicits.global
import lab.cbor.Bytes
import lab.ledger.NativeSpending

class NativeSpendingCommandSuite extends munit.FunSuite:
  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes("UTF-8"))
  private val tip = s"""{"era":"Conway","hash":"${"12" * 32}","slot":12,"block":3,"epoch":0}"""
  private def files: Map[String, Bytes] = Map(
    "transfer-genesis.md" -> raw(
      """{"networkId":"Testnet","networkMagic":1082026,"epochLength":500}"""
    ),
    "pre-parameters.md" -> raw(
      """{"protocolVersion":{"major":9,"minor":0},"txFeePerByte":44,"txFeeFixed":155381,"maxTxSize":16384,"utxoCostPerByte":4310}"""
    ),
    "pre-tips.md" -> raw(s"[$tip,$tip]"),
    "pre-ledger-state.md" -> raw(
      """{"lastEpoch":0,"stateBefore":{"esLState":{"utxoState":{"fees":100}}}}"""
    ),
    "pre-utxo-cbor.md" -> raw("a0\n")
  )
  private def manifest(originals: Map[String, Bytes]): Bytes = raw(
    "format\tnative-spending-pre-v1\n" + NativeSpendingCommand.sources.toVector
      .sortBy(_._1)
      .map((key, file) => key + "\t" + ClusterHeaderObservation.sha256(originals(file)).hex)
      .mkString("\n") + "\n"
  )
  private def bind(originals: Map[String, Bytes]) =
    NativeSpendingCommand.bindPre(manifest(originals), originals)
  private def checked = bind(files).fold(e => fail(e.toString), identity)
  test("pre-only manifest binds exact complete snapshot and parameters with no post sources") {
    val pre = checked
    assertEquals(pre.state.size, 0)
    assertEquals(pre.state.fees, BigInt(100))
    assertEquals(pre.state.slot, BigInt(12))
    assertEquals(pre.state.environment.epoch, BigInt(0))
    assertEquals(pre.state.revision, BigInt(0))
    assertEquals(pre.manifestDigest, ClusterHeaderObservation.sha256(manifest(files)))
    assert(NativeSpendingCommand.sources.values.forall(!_.startsWith("post-")))
  }
  test("pre context cannot be manufactured or copied") {
    assert(compileErrors("new lab.NativeSpendingCommand.Pre(null, BigInt(1), null)").nonEmpty)
    assert(compileErrors("val p: lab.NativeSpendingCommand.Pre = null; p.copy()").nonEmpty)
  }
  test("duplicate, missing, extra, wrong-format and malformed UTF-8 manifests reject") {
    val text = new String(manifest(files).toArray, "UTF-8")
    Vector(
      raw(text + "format\tnative-spending-pre-v1\n"),
      raw(text + "postSha256\t00\n"),
      raw(text.linesIterator.filterNot(_.startsWith("preTipsSha256")).mkString("\n")),
      raw(text.replace("native-spending-pre-v1", "conway-pv9-cluster-context-v2")),
      Bytes(Vector(0xc3.toByte, 0x28.toByte))
    ).foreach(m => assert(NativeSpendingCommand.bindPre(m, files).isLeft))
  }
  test("digest tampering and missing or extra source files reject") {
    assert(
      NativeSpendingCommand
        .bindPre(manifest(files), files.updated("pre-utxo-cbor.md", raw("a1")))
        .isLeft
    )
    assert(NativeSpendingCommand.bindPre(manifest(files), files - "pre-tips.md").isLeft)
    assert(
      NativeSpendingCommand
        .bindPre(manifest(files), files.updated("post-utxo-cbor.md", raw("a0")))
        .isLeft
    )
    val text = new String(manifest(files).toArray, "UTF-8")
    val pin = ClusterHeaderObservation.sha256(files("pre-utxo-cbor.md")).hex
    assert(NativeSpendingCommand.bindPre(raw(text.replace(pin, pin.toUpperCase)), files).isLeft)
  }
  test("manifest and source byte bounds reject") {
    assert(NativeSpendingCommand.bindPre(raw("x" * 8193), files).isLeft)
    assert(bind(files.updated("pre-utxo-cbor.md", Bytes.empty)).isLeft)
    assert(bind(files.updated("pre-utxo-cbor.md", raw("x" * 4194305))).isLeft)
  }
  test("different network and protocol versions are explicitly Unsupported") {
    val wrongNetwork = files.updated(
      "transfer-genesis.md",
      raw("""{"networkId":"Mainnet","networkMagic":764824073,"epochLength":500}""")
    )
    val wrongVersion = files.updated(
      "pre-parameters.md",
      raw(
        new String(files("pre-parameters.md").toArray, "UTF-8")
          .replace("\"minor\":0", "\"minor\":1")
      )
    )
    Vector(wrongNetwork, wrongVersion).foreach { original =>
      bind(original) match
        case Left(NativeSpendingCommand.Failure.Unsupported("pre-context", _)) => ()
        case other => fail(other.toString)
    }
  }
  test("unstable, unbounded or inconsistent tips and ledger epochs reject") {
    Vector(
      s"[$tip]",
      Vector.fill(33)(tip).mkString("[", ",", "]"),
      s"[$tip,${tip.replace("\"block\":3", "\"block\":4")}]"
    )
      .foreach(t => assert(bind(files.updated("pre-tips.md", raw(t))).isLeft))
    assert(
      bind(
        files.updated(
          "pre-ledger-state.md",
          raw("""{"lastEpoch":1,"stateBefore":{"esLState":{"utxoState":{"fees":100}}}}""")
        )
      ).isLeft
    )
    assert(
      bind(
        files.updated(
          "pre-tips.md",
          raw(
            s"[${tip.replace("\"slot\":12", "\"slot\":18446744073709551616")},${tip.replace("\"slot\":12", "\"slot\":18446744073709551616")}]"
          )
        )
      ).isLeft
    )
  }
  test("whole snapshot decoder rejects malformed maps rather than filtering state") {
    assert(bind(files.updated("pre-utxo-cbor.md", raw("80"))).isLeft)
    assert(bind(files.updated("pre-utxo-cbor.md", raw("a10000"))).isLeft)
  }
  test("diagnostic slot and transaction bounds fail before transaction interpretation") {
    val pre = checked
    val tx = Bytes(Vector(0x80.toByte))
    Vector(BigInt(-1), BigInt(11), BigInt(1) << 64).foreach(slot =>
      assert(NativeSpendingCommand.diagnose(pre, tx, slot).isLeft)
    )
    NativeSpendingCommand.diagnose(pre, tx, 500) match
      case Left(NativeSpendingCommand.Failure.Unsupported("pre-context", _)) => ()
      case other                                                             => fail(other.toString)
    assert(NativeSpendingCommand.diagnose(pre, Bytes(Vector.fill(65537)(0.toByte)), 12).isLeft)
    assertEquals(pre.state.revision, BigInt(0))
    assertEquals(pre.state.fees, BigInt(100))
  }
  test("native predicate names survive typed error reporting without claiming success") {
    val h = Bytes(Vector.fill(28)(1.toByte))
    val predicates = Vector(
      NativeSpending.Error.MissingScripts(Set(h)) -> "MissingScripts",
      NativeSpending.Error.WrongScriptHashes(Set(h), Set(h)) -> "WrongScriptHashes",
      NativeSpending.Error.FailedScripts(Set(h)) -> "FailedScripts"
    )
    predicates.foreach { (error, expected) =>
      val json = ReferenceJson.parse(
        raw(
          NativeSpendingCommand.renderFailure(
            NativeSpendingCommand.nativeFailure(error),
            "native-spending-diagnostic",
            Some(h)
          )
        )
      )
      assertEquals(ReferenceJson.field(json, "predicate"), ReferenceJson.Json.Str(expected))
      assertEquals(ReferenceJson.field(json, "outcome"), ReferenceJson.Json.Str("Rejected"))
      assertEquals(ReferenceJson.field(json, "passed"), ReferenceJson.Json.Lit("false"))
      assertEquals(
        ReferenceJson.field(json, "fullLedgerValidated"),
        ReferenceJson.Json.Lit("false")
      )
    }
  }
  test("failure JSON escapes supplied error text and distinguishes unsupported") {
    val json = ReferenceJson.parse(
      raw(
        NativeSpendingCommand.renderFailure(
          NativeSpendingCommand.Failure.Unsupported("stage", "quote\" slash\\ newline\n"),
          "native-spending-diagnostic"
        )
      )
    )
    assertEquals(ReferenceJson.field(json, "outcome"), ReferenceJson.Json.Str("Unsupported"))
    assertEquals(
      ReferenceJson.field(json, "detail"),
      ReferenceJson.Json.Str("quote\" slash\\ newline\n")
    )
  }
  test("invalid CLI arity fails before IO or network use") {
    NativeSpendingCommand
      .run(List("observe"))
      .map(code => assertEquals(code.code, 2))
      .unsafeToFuture()
  }

  sys.env.get("NATIVE_SPENDING_EVIDENCE").foreach { location =>
    val directory = java.nio.file.Path.of(location)
    def original(name: String): Bytes =
      val stream = java.nio.file.Files.newInputStream(directory.resolve(name))
      val bytes =
        try stream.readNBytes(131075)
        finally stream.close()
      assert(bytes.nonEmpty && bytes.length <= 131074, "bounded retained transaction required")
      Bytes.fromHex(new String(bytes, "UTF-8").trim).fold(fail(_), identity)
    Vector(
      "missing-script" -> "MissingScripts",
      "wrong-script" -> "WrongScriptHashes",
      "unrelated-signer" -> "FailedScripts"
    ).foreach { (label, expected) =>
      test(
        s"retained native $label: exact control derives and same-body negative rejects as $expected"
      ) {
        val pre = NativeSpendingCommand
          .loadPre(directory.resolve("negative-" + label + "-input"))
          .fold(e => fail(e.toString), identity)
        val control = original(label + "-control-transaction-cbor.md")
        val negative = original(label + "-transaction-cbor.md")
        val controlId = TransactionId.fromEnvelope(control).fold(fail(_), identity)
        val negativeId = TransactionId.fromEnvelope(negative).fold(fail(_), identity)
        assertEquals(negativeId, controlId)
        assert(control != negative, "retained witness variants must differ")
        val accepted = NativeSpendingCommand
          .diagnose(pre, control, pre.state.slot)
          .fold(e => fail(e.toString), identity)
        assertEquals(accepted.candidate.transactionId, controlId)
        assert(accepted.candidate.nativeAdmission.nonEmpty)
        NativeSpendingCommand.diagnose(pre, negative, pre.state.slot) match
          case Left(NativeSpendingCommand.Failure.Rejected("native", predicate, _)) =>
            assertEquals(predicate, expected)
          case other => fail(other.toString)
        assertEquals(pre.state.revision, BigInt(0))
      }
    }
  }
