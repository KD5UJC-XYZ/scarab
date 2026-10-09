// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value}

class SequenceInputSuite extends munit.FunSuite:
  private def raw(s: String) = Bytes.fromArray(s.getBytes("UTF-8"))
  private def ok[A](e: Either[SequenceInput.Failure, A]): A =
    e.fold(f => fail(f.toString), identity)
  private def manifest(files: Map[String, Bytes]) = raw(
    "format\tcoherent-sequence-context-v1\n" + SequenceInput.sources.toVector
      .sortBy(_._1)
      .map((key, name) => key + "\t" + ClusterHeaderObservation.sha256(files(name)).hex)
      .mkString("\n") + "\n"
  )
  private val dummy = SequenceInput.sources.values.map(_ -> raw("{}")).toMap
  private def n(v: Value) = Node(v, Bytes.empty)
  private def arr(v: Value*) = Value.Arr(v.toVector.map(n))
  private def u(v: Int) = Value.UInt(BigInt(v))
  private def b(size: Int) = Value.ByteString(Bytes(Vector.fill(size)(0.toByte)))
  private def enc(v: Value) = Cbor.encode(v).toOption.get
  // Synthetic structural fixtures: deliberately no valid certificate, VRF or body commitment.
  private val header = enc(
    arr(
      arr(
        u(1),
        u(1),
        b(32),
        b(32),
        b(32),
        arr(b(64), b(80)),
        u(0),
        b(32),
        arr(b(32), u(0), u(0), b(64)),
        arr(u(11), u(2))
      ),
      b(448)
    )
  )
  private val envelope = enc(arr(u(6), Value.Tag(24, n(Value.ByteString(header)))))
  private def original(
      bodies: Vector[Bytes],
      witnesses: Vector[Bytes],
      aux: String = "a0",
      invalid: String = "80"
  ) =
    def h(s: String) = Bytes.fromHex(s).toOption.get.value
    def array(v: Vector[Bytes]) =
      assert(v.size < 24)
      Vector((0x80 + v.size).toByte) ++ v.flatMap(_.value)
    BoundedChainFollower.Original(
      envelope,
      Bytes(
        h("820785") ++ header.value ++ array(bodies) ++
          array(witnesses) ++ h(aux) ++ h(invalid)
      )
    )

  test("seven pre-only pins are exact; duplicate, missing, unknown and candidate sources reject") {
    assertEquals(SequenceInput.sources.size, 7)
    val good = new String(manifest(dummy).toArray, "UTF-8")
    for bad <- Vector(
        good + "format\tcoherent-sequence-context-v1\n",
        good + "other\t00\n",
        good.linesIterator.filterNot(_.startsWith("preUtxoSha256")).mkString("\n")
      )
    do assert(SequenceInput.bind(raw(bad), dummy).isLeft)
    assert(SequenceInput.bind(manifest(dummy), dummy + ("scala-transfer.md" -> raw("{}"))).isLeft)
    assert(SequenceInput.bind(manifest(dummy), dummy - "pre-utxo-cbor.md").isLeft)
  }
  test("source whitespace substitutions and oversized manifests reject") {
    SequenceInput.bind(manifest(dummy), dummy.updated("pre-utxo.md", raw("{} "))) match
      case Left(SequenceInput.Failure.Rejected("source", why)) =>
        assert(why.contains("pre-utxo.md"))
      case other => fail(other.toString)
    assert(SequenceInput.bind(raw("x" * 8193), dummy).isLeft)
  }
  test("public constructors and copy cannot manufacture context or block") {
    assert(compileErrors("val x: lab.SequenceInput.Context = null; x.copy()").nonEmpty)
    assert(
      compileErrors(
        "new lab.SequenceInput.Context(null,null,null,null,null,null,null,null,BigInt(0))"
      ).nonEmpty
    )
    assert(compileErrors("new lab.SequenceInput.Block(null,null,Vector.empty)").nonEmpty)
    assert(compileErrors("val x: lab.SequenceInput.Block = null; x.copy()").nonEmpty)
  }
  test("synthetic empty block is structural input, never a validated tip") {
    val input = original(Vector.empty, Vector.empty)
    val result = ok(SequenceInput.block(input))
    assertEquals(result.original, input)
    assertEquals(result.transactionMemos, Vector.empty)
    assert(!result.validatedTip)
  }
  test("synthetic multiple transactions preserve exact noncanonical spans and ordinal") {
    val bodies =
      Vector(Bytes.fromHex("a1001801").toOption.get, Bytes.fromHex("bf001802ff").toOption.get)
    val witnesses = Vector(Bytes.fromHex("bfff").toOption.get, Bytes.fromHex("a0").toOption.get)
    val result = ok(SequenceInput.block(original(bodies, witnesses)))
    assertEquals(
      result.transactionMemos.map(_.hex),
      Vector("84a1001801bffff5f6", "84bf001802ffa0f5f6")
    )
    for (memo, index) <- result.transactionMemos.zipWithIndex do
      val tx = Cbor.decode(memo).toOption.get.value.asInstanceOf[Value.Arr].value
      assertEquals(tx(0).original, bodies(index))
      assertEquals(tx(1).original, witnesses(index))
  }
  test("synthetic sixteen transaction boundary accepts; seventeen rejects") {
    val body = Bytes.fromHex("a0").toOption.get
    assert(SequenceInput.block(original(Vector.fill(16)(body), Vector.fill(16)(body))).isRight)
    assert(SequenceInput.block(original(Vector.fill(17)(body), Vector.fill(17)(body))).isLeft)
  }
  test("count mismatch, nonempty auxiliary data and invalid transaction indices reject") {
    val body = Bytes.fromHex("a0").toOption.get
    assert(SequenceInput.block(original(Vector(body), Vector.empty)).isLeft)
    assert(SequenceInput.block(original(Vector.empty, Vector.empty, "a10000")).isLeft)
    assert(SequenceInput.block(original(Vector.empty, Vector.empty, "80")).isLeft)
    assert(SequenceInput.block(original(Vector.empty, Vector.empty, "a0", "8100")).isLeft)
  }
  test("memo and aggregate raw block bounds reject before acceptance") {
    val huge = enc(b(65536))
    assert(
      SequenceInput.block(original(Vector(huge), Vector(Bytes.fromHex("a0").toOption.get))).isLeft
    )
    assert(
      SequenceInput
        .block(BoundedChainFollower.Original(envelope, Bytes(Vector.fill(1048577)(0.toByte))))
        .isLeft
    )
  }
  test("header/block identity and Conway era tag are checked structurally") {
    val good = original(Vector.empty, Vector.empty)
    assert(
      SequenceInput.block(good.copy(block = Bytes(good.block.value.updated(1, 6.toByte)))).isLeft
    )
    assert(
      SequenceInput.block(good.copy(block = Bytes(good.block.value.updated(7, 2.toByte)))).isLeft
    )
  }

  sys.env.get("COHERENT_POSITIVE_INPUT").foreach { location =>
    val dir = Path.of(location)
    def input = BranchInput.load(dir).fold(f => fail(f.toString), identity)
    test(
      "retained positive pre-source rebind excludes candidate bytes and preserves unknown nonce"
    ) {
      val old = input
      val context = ok(SequenceInput.fromInput(old))
      assertEquals(
        context.originals,
        old.originals.filter((k, _) => SequenceInput.sources.values.toSet(k))
      )
      assertEquals(
        context.sourcePins,
        old.sourcePins.filter((k, _) => SequenceInput.sources.keySet(k))
      )
      assert(context.suppliedCheckpoint && !context.validatedTip && !context.authenticatedSnapshot)
      assertEquals(
        ok(SequenceInput.bind(manifest(context.originals), context.originals)).id,
        context.id
      )
      assertEquals(context.certificateSeed.tip, old.certificates.seed.tip)
      val snapshot = PraosNonceSnapshot
        .parse(context.originals("pre-protocol-state.md"), context.sourcePins("preProtocolSha256"))
        .toOption
        .get
      assertEquals(context.nonces.seed.fields.previousEpoch, snapshot.fields.previousEpoch)
      assertEquals(ok(SequenceInput.block(old.original)).transactionMemos.head, old.transaction)
    }
    test("retained pre-protocol nonce omission rejects rather than inventing a neutral nonce") {
      val context = ok(SequenceInput.fromInput(input))
      val protocol = new String(context.originals("pre-protocol-state.md").toArray, "UTF-8")
      val changed = context.originals.updated(
        "pre-protocol-state.md",
        raw(protocol.replace("\"epochNonce\"", "\"missingNonce\""))
      )
      assert(SequenceInput.bind(manifest(changed), changed).isLeft)
    }
  }

  sys.env.get("COHERENT_BRANCH_EVIDENCE").foreach { location =>
    test(
      "retained whole Byron-containing checkpoint is Unsupported without filtering seed entries"
    ) {
      val dir = Path.of(location)
      val originals = SequenceInput.sources.values
        .map(name => name -> Bytes.fromArray(Files.readAllBytes(dir.resolve(name))))
        .toMap
      assertEquals(
        SequenceInput.bind(manifest(originals), originals).left.toOption,
        Some(SequenceInput.Failure.Unsupported("unsupported payment address kind/network/length"))
      )
    }
  }
