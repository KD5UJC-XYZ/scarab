// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.{KeyPair, KeyPairGenerator, SecureRandom, Signature}
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.ledger.Coverage

/** Complete synthetic evidence directories. Random signing keys exist only in test memory. These
  * exercise inspect binding, not reference-node conformance or inclusion.
  */
class ClusterNegativeInspectionSuite extends munit.FunSuite:
  import ClusterNegativeObservation.*
  private def node(v: Value): Node = Node(v, Bytes.empty)
  private def arr(v: Value*): Value = Value.Arr(v.toVector.map(node))
  private def map(v: (Value, Value)*): Value =
    Value.Map(v.toVector.map { case (k, x) => node(k) -> node(x) })
  private def raw(v: Value): Bytes = Cbor.encode(v).fold(fail(_), identity)
  private def bs(v: Bytes): Value = Value.ByteString(v)
  private def number(n: Int): Value = Value.UInt(n)
  private def publicKey(pair: KeyPair): Bytes =
    Bytes.fromArray(pair.getPublic.getEncoded.takeRight(32))
  private def address(pair: KeyPair): Bytes =
    Bytes(Vector(0x60.toByte) ++ Blake2b.hash224.hash(publicKey(pair)).value)
  private def signed(body: Value, key: KeyPair): Bytes =
    val signer = Signature.getInstance("Ed25519")
    signer.initSign(key.getPrivate)
    signer.update(Blake2b.hash256.hash(raw(body)).toArray)
    raw(
      arr(
        body,
        map(number(0) -> arr(arr(bs(publicKey(key)), bs(Bytes.fromArray(signer.sign()))))),
        Value.Bool(true),
        Value.Null
      )
    )
  private def txid(tx: Bytes): Bytes =
    Coverage.decode(tx).fold(e => fail(e.toString), _.body.hash.bytes)
  private def write(dir: Path, name: String, value: String): Unit =
    Files.writeString(dir.resolve(name), value)
    ()
  private def textHash(text: String): String = sha256(Bytes.fromArray(text.getBytes("UTF-8")))
  private case class Fixture(dir: Path, noWitness: Bytes)

  private def fixture(dir: Path): Fixture =
    val keygen = KeyPairGenerator.getInstance("Ed25519")
    val owner = keygen.generateKeyPair()
    val recipient = keygen.generateKeyPair()
    val source = new Array[Byte](32)
    new SecureRandom().nextBytes(source)
    val input = arr(bs(Bytes.fromArray(source)), number(0))
    def output(value: Int) = arr(bs(address(recipient)), number(value))
    def body(fee: Int) = map(
      number(0) -> arr(input),
      number(1) -> arr(output(10200000 - fee)),
      number(2) -> number(fee)
    )
    val tx = signed(body(200000), owner)
    val wrong = signed(body(200000), recipient)
    val conflict = signed(body(200001), owner)
    val pre = raw(map(input -> arr(bs(address(owner)), number(10200000))))
    val post = raw(map(arr(bs(txid(tx)), number(0)) -> output(10000000)))
    val genesis = """{"networkMagic":1082026,"networkId":"Testnet"}"""
    val parameters =
      """{"protocolVersion":{"major":9,"minor":0},"txFeePerByte":1,"txFeeFixed":0,"maxTxSize":16384,"utxoCostPerByte":4310}"""
    def ledger(fees: Int) =
      s"""{"lastEpoch":0,"stateBefore":{"esLState":{"utxoState":{"fees":$fees}}}}"""
    val preHash = "01" * 32
    val postHash = "02" * 32
    def tips(hash: String, slot: Int) =
      val point = s"""{"hash":"$hash","slot":$slot,"block":$slot,"epoch":0,"era":"Conway"}"""
      s"[$point,$point]"
    val preTips = tips(preHash, 1)
    val postTips = tips(postHash, 2)
    val originals = Map(
      "transfer-genesis.md" -> genesis,
      "pre-parameters.md" -> parameters,
      "post-parameters.md" -> parameters,
      "pre-ledger-state.md" -> ledger(0),
      "post-ledger-state.md" -> ledger(200000),
      "pre-tips.md" -> preTips,
      "post-tips.md" -> postTips,
      "pre-utxo-cbor.md" -> pre.hex,
      "post-utxo-cbor.md" -> post.hex,
      "signed-transaction-cbor.md" -> tx.hex
    )
    originals.foreach { case (name, value) => write(dir, name, value) }
    val fields = Vector(
      "format" -> ClusterTransferCommand.ContextFormat,
      "genesisSha256" -> textHash(genesis),
      "parametersSha256" -> textHash(parameters),
      "networkMagic" -> "1082026",
      "major" -> "9",
      "minor" -> "0",
      "preHash" -> preHash,
      "postHash" -> postHash,
      "preSlot" -> "1",
      "postSlot" -> "2",
      "preEpoch" -> "0",
      "postEpoch" -> "0",
      "feePerByte" -> "1",
      "feeFixed" -> "0",
      "maxTxSize" -> "16384",
      "feesBefore" -> "0",
      "feesAfter" -> "200000",
      "binding" -> "paused-producer-tip-brackets",
      "preTipsSha256" -> textHash(preTips),
      "postTipsSha256" -> textHash(postTips),
      "preLedgerSha256" -> textHash(ledger(0)),
      "postLedgerSha256" -> textHash(ledger(200000))
    )
    write(
      dir,
      "transfer-context.md",
      fields.map { case (key, value) => s"$key\t$value\n" }.mkString
    )
    scenarios.zip(Vector(wrong, tx, conflict)).zipWithIndex.foreach {
      case ((scenario, transaction), index) =>
        val baseline = if scenario.usePost then "post" else "pre"
        write(dir, scenario.label + "-transaction-cbor.md", transaction.hex)
        val txFile = Vector(
          "/work/scenario-wrong-key.signed",
          "/work/transfer.signed",
          "/work/scenario-conflict.signed"
        )(index)
        val submission = s"scenario-submission-$index.md"
        val fields = s""""transactionCborSha256":"${sha256(
            transaction
          )}","returncode":1,"stdout":"","stderr":"${scenario.reference}""""
        write(
          dir,
          submission,
          s"""{$fields,"transactionFileUnchanged":true,"command":["cardano-cli","conway","transaction","submit","--tx-file","$txFile","--testnet-magic","1082026","--socket-path","/work/env/socket/node3/sock"]}"""
        )
        write(
          dir,
          scenario.label + "-result.md",
          s"""{$fields,"scope":"reference-local-submission-observation","transactionId":"${txid(
              transaction
            ).hex}","submissionEvidence":"$submission","passed":true,"stableStateVerified":true,"singleAcquiredSnapshot":false,"expectedReason":"${scenario.reference}"}"""
        )
        for
          phase <- Vector("pre", "post");
          kind <- Vector("utxo-cbor", "parameters", "ledger-state", "tips")
        do
          write(
            dir,
            scenario.label + "-" + phase + "-" + kind + ".md",
            originals(baseline + "-" + kind + ".md")
          )
    }
    Fixture(dir, raw(arr(body(200000), map(), Value.Bool(true), Value.Null)))

  private def withFixture(f: Fixture => Unit): Unit =
    val dir = Files.createTempDirectory("cluster-negative-inspect-")
    try
      val value = fixture(dir)
      assertEquals(inspect(dir), scenarios.map(s => s.label -> s.reason))
      f(value)
    finally
      val files = Files.list(dir)
      try files.forEach(p => { Files.deleteIfExists(p); () })
      finally files.close()
      Files.deleteIfExists(dir)

  test("complete inspect accepts generated signed positive and all three bound negatives") {
    withFixture(_ => ())
  }
  test(
    "witness-only replacement preserves body ID and selected rejection but fails full inspect digest"
  ) {
    withFixture { f =>
      val original =
        Bytes.fromHex(Files.readString(f.dir.resolve("wrong-key-transaction-cbor.md"))).toOption.get
      assertEquals(txid(original), txid(f.noWitness))
      val positive = ClusterTransferCommand.load(f.dir)
      assertEquals(
        evaluate(positive.context, positive.pre, f.noWitness),
        Outcome.Rejected(Rejection.MissingRequiredKeys)
      )
      write(f.dir, "wrong-key-transaction-cbor.md", f.noWitness.hex)
      val error = intercept[IllegalArgumentException](inspect(f.dir))
      assert(error.getMessage.contains("complete submitted transaction digest differs"))
    }
  }
  test(
    "rewriting negative receipt digest cannot replace the separately saved submission evidence"
  ) {
    withFixture { f =>
      val old =
        Bytes.fromHex(Files.readString(f.dir.resolve("wrong-key-transaction-cbor.md"))).toOption.get
      write(f.dir, "wrong-key-transaction-cbor.md", f.noWitness.hex)
      val receipt = Files.readString(f.dir.resolve("wrong-key-result.md"))
      write(f.dir, "wrong-key-result.md", receipt.replace(sha256(old), sha256(f.noWitness)))
      val error = intercept[IllegalArgumentException](inspect(f.dir))
      assert(error.getMessage.contains("submission receipt differs"))
    }
  }
  for (name, replacement, message) <- Vector(
      ("repeated-included-pre-tips.md", "\"slot\":3", "negative point differs"),
      ("conflicting-spend-post-utxo-cbor.md", "a0", "negative UTxO bytes differ"),
      ("wrong-key-post-ledger-state.md", "{}", "negative state source differs"),
      ("wrong-key-post-parameters.md", "{}", "negative state source differs")
    )
  do
    test("complete inspect rejects mismatched state binding: " + name) {
      withFixture { f =>
        val changed =
          if name.endsWith("tips.md") then
            Files.readString(f.dir.resolve(name)).replace("\"slot\":2", replacement)
          else replacement
        write(f.dir, name, changed)
        assert(intercept[IllegalArgumentException](inspect(f.dir)).getMessage.contains(message))
      }
    }
  test(
    "copying transaction and receipts from another valid directory fails original-body binding"
  ) {
    withFixture { a =>
      withFixture { b =>
        for name <- Vector(
            "wrong-key-transaction-cbor.md",
            "wrong-key-result.md",
            "scenario-submission-0.md"
          )
        do Files.copy(b.dir.resolve(name), a.dir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
        assert(
          intercept[IllegalArgumentException](inspect(a.dir)).getMessage
            .contains("wrong-key body differs")
        )
      }
    }
  }
  test("submission path traversal and altered command fail complete inspect") {
    withFixture { f =>
      val name = "wrong-key-result.md"
      val original = Files.readString(f.dir.resolve(name))
      write(
        f.dir,
        name,
        original.replace("scenario-submission-0.md", "../scenario-submission-0.md")
      )
      assert(
        intercept[IllegalArgumentException](inspect(f.dir)).getMessage
          .contains("invalid submission evidence path")
      )
      write(f.dir, name, original)
      val submission = "scenario-submission-0.md"
      write(
        f.dir,
        submission,
        Files.readString(f.dir.resolve(submission)).replace("node3/sock", "node1/sock")
      )
      assert(
        intercept[IllegalArgumentException](inspect(f.dir)).getMessage
          .contains("submission command differs")
      )
    }
  }
