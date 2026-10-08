// SPDX-License-Identifier: Apache-2.0
package lab.kes

import lab.Blake2b
import lab.cbor.Bytes
import lab.witness.{PublicKey32, Signature64, VerificationError, VerificationResult}
import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, Executors, TimeUnit}

class Sum6KesSuite extends munit.FunSuite:
  import Sum6Kes.*
  private def b(s: String): Bytes = Bytes.fromHex(s).toOption.get
  private def admitted(name: String, pin: String): String =
    val stream = Files.newInputStream(Path.of("fixtures/sum6", name))
    val data =
      try stream.readNBytes(1024 * 1024 + 1)
      finally stream.close()
    assert(data.length <= 1024 * 1024)
    assertEquals(
      Bytes.fromArray(java.security.MessageDigest.getInstance("SHA-256").digest(data)).hex,
      pin
    )
    new String(data, java.nio.charset.StandardCharsets.UTF_8)
  private val originals = admitted(
    "originals.tsv",
    "fb4925760c498bfc5569c69f848a56226698b056d11cc3f358d08ca2ecb5dde4"
  ).linesIterator.map(_.split("\t", -1)).toVector
  private def args(r: Array[String]) = (
    Root32.fromBytes(b(r(1))).toOption.get,
    RelativePeriod.create(r(2).toInt).toOption.get,
    SuppliedMessage.fromBytes(b(r(3))).toOption.get,
    Signature448.fromBytes(b(r(4))).toOption.get
  )
  private val good = Right(Result.SuppliedMessageSignatureVerified)
  private val rejected = Right(Result.SignatureRejected)
  private def flip(bytes: Bytes, at: Int, bit: Int): Bytes =
    Bytes(bytes.value.updated(at, (bytes.value(at) ^ (1 << bit)).toByte))

  private val observations = admitted(
    "observations.tsv",
    "64772bfcad32d3958cb9851d7ce0ff0f05a91f1dd961cccbe111c10af7acb774"
  ).linesIterator.map(_.split("\t", -1)).toVector
  private val corpusBatches = observations.indices.toVector.grouped(256).toVector
  private val decodedOriginals = originals.map { r =>
    r(0) -> (b(r(1)), r(2).toInt, b(r(3)), b(r(4)))
  }.toMap

  test("Sum6 batch coverage: every one of 17336 rows exactly once and 3752 expected leaf calls") {
    assertEquals(observations.size, 17336)
    assertEquals(corpusBatches.size, 68)
    assert(corpusBatches.forall(batch => batch.nonEmpty && batch.size <= 256))
    assertEquals(corpusBatches.flatten, observations.indices.toVector)
    assertEquals(corpusBatches.flatten.distinct.size, 17336)
    assertEquals(observations.map(row => (row(0), row(1))).distinct.size, 17336)
    assertEquals(observations.count(_(2) == "VERIFIED"), 4)
    assertEquals(observations.count(_(2) == "REJECTED"), 17332)
    assertEquals(observations.map(_(4).toInt).sum, 3752)
    assertEquals(originals.map(_(2).toInt).distinct.sorted, Vector(28, 29, 35))
    assertEquals(decodedOriginals.size, 4)
    originals.foreach { original =>
      val rows = observations.filter(_(0) == original(0))
      assertEquals(rows.size, 4334)
      assertEquals(rows.count(_(1) == "original"), 1)
      assertEquals(rows.count(_(2) == "VERIFIED"), 1)
    }
  }

  for (indices, batch) <- corpusBatches.zipWithIndex do
    test(s"Sum6 exhaustive batch ${batch + 1}/68: rows ${indices.head}..${indices.last}") {
      val rows = indices.map(observations)
      var totalLeaves = 0
      var totalHashes = 0
      var totalVerified = 0
      rows.foreach { row =>
        val (originalRoot, originalPeriod, originalMessage, originalSignature) =
          decodedOriginals(row(0))
        var root = originalRoot; var period = originalPeriod
        var message = originalMessage; var signature = originalSignature
        row(1).split("/").toList match
          case List("original")  => ()
          case List("period", p) => period = p.toInt
          case List(field, "xor", offset, bit) =>
            field match
              case "root"      => root = flip(root, offset.toInt, bit.toInt)
              case "signature" => signature = flip(signature, offset.toInt, bit.toInt)
              case "message"   => message = flip(message, offset.toInt, bit.toInt)
              case _           => fail("unknown field")
          case List("signature", "swap-pair", d) =>
            val offset = 64 + 64 * (d.toInt - 1)
            val a = signature.value
            signature = Bytes(
              a.take(offset) ++ a.slice(offset + 32, offset + 64) ++
                a.slice(offset, offset + 32) ++ a.drop(offset + 64)
            )
          case List("message", "empty")         => message = Bytes.empty
          case List("message", "truncate-last") => message = Bytes(message.value.dropRight(1))
          case List("message", "append-zero")   => message = Bytes(message.value :+ 0.toByte)
          case _                                => fail("unknown recipe")
        var hashes = 0; var leaves = 0
        val actual = verifyWith(
          Root32.fromBytes(root).toOption.get,
          RelativePeriod.create(period).toOption.get,
          SuppliedMessage.fromBytes(message).toOption.get,
          Signature448.fromBytes(signature).toOption.get,
          pair => { hashes += 1; Blake2b.hash256.hash(pair) },
          (k, s, m) => { leaves += 1; lab.witness.StrictEd25519.verifyEd25519(k, s, m) }
        )
        assertEquals(actual, if row(2) == "VERIFIED" then good else rejected, row(1))
        assertEquals(hashes, row(3).toInt, row(1))
        assertEquals(leaves, row(4).toInt, row(1))
        totalLeaves += leaves
        totalHashes += hashes
        if actual == good then totalVerified += 1
      }
      // Each actual batch sum equals its pinned slice; exhaustive disjoint coverage above
      // makes their combined actual leaf total exactly 3752, without order-dependent state.
      assertEquals(totalLeaves, rows.map(_(4).toInt).sum)
      assertEquals(totalHashes, rows.map(_(3).toInt).sum)
      assertEquals(totalVerified, rows.count(_(2) == "VERIFIED"))
    }

  test("public byte facade verifies all four originals and rejects changed supplied messages") {
    originals.foreach { r =>
      assertEquals(verify(b(r(1)), r(2).toInt, b(r(3)), b(r(4))), good)
      assertEquals(verify(b(r(1)), r(2).toInt, flip(b(r(3)), 0, 0), b(r(4))), rejected)
    }
  }

  test("checked sizes ranges null storage and local message cap") {
    Vector(null, Bytes(null), Bytes.empty, b("00" * 31), b("00" * 33)).foreach(x =>
      assert(Root32.fromBytes(x).swap.toOption.get.isInstanceOf[Failure.Malformed])
    )
    Vector(null, Bytes(null), Bytes.empty, b("00" * 447), b("00" * 449)).foreach(x =>
      assert(Signature448.fromBytes(x).swap.toOption.get.isInstanceOf[Failure.Malformed])
    )
    Vector(Int.MinValue, -1, 64, Int.MaxValue).foreach(p => assert(RelativePeriod.create(p).isLeft))
    (0 to 63).foreach(p => assertEquals(RelativePeriod.create(p).toOption.get.value, p))
    Vector(null, Bytes(null), Bytes(Vector.fill(MaxMessageBytes + 1)(0.toByte))).foreach(x =>
      assert(SuppliedMessage.fromBytes(x).swap.toOption.get.isInstanceOf[Failure.Malformed])
    )
    assert(SuppliedMessage.fromArray(null).isLeft)
    assert(SuppliedMessage.fromArray(new Array[Byte](MaxMessageBytes + 1)).isLeft)
    assertEquals(
      SuppliedMessage.fromArray(new Array[Byte](MaxMessageBytes)).toOption.get.bytes.size,
      MaxMessageBytes
    )
    assertEquals(SuppliedMessage.fromBytes(Bytes.empty).toOption.get.bytes, Bytes.empty)
    assert(Root32.fromArray(null).isLeft)
    assert(Signature448.fromArray(null).isLeft)
    assert(Root32.fromArray(new Array[Byte](33)).isLeft)
    assert(Signature448.fromArray(new Array[Byte](449)).isLeft)
  }

  test("owned array snapshots getters immutable storage and no public copy bypass") {
    val r = originals.head
    val root = b(r(1)).toArray; val msg = b(r(3)).toArray; val sig = b(r(4)).toArray
    val k = Root32.fromArray(root).toOption.get
    val m = SuppliedMessage.fromArray(msg).toOption.get
    val s = Signature448.fromArray(sig).toOption.get
    root(0) = 0; msg(0) = 0; sig(0) = 0
    k.toArray(0) = 0; m.toArray(0) = 0; s.toArray(0) = 0
    assertEquals(k.bytes, b(r(1))); assertEquals(m.bytes, b(r(3))); assertEquals(s.bytes, b(r(4)))
    assertEquals(verifySignature(k, RelativePeriod.create(r(2).toInt).toOption.get, m, s), good)
    assert(!classOf[Root32].getMethods.exists(_.getName == "copy"))
    assert(!classOf[Signature448].getMethods.exists(_.getName == "copy"))
    assert(!classOf[RelativePeriod].getMethods.exists(_.getName == "copy"))
    assert(!classOf[SuppliedMessage].getMethods.exists(_.getName == "copy"))
    assert(compileErrors("new lab.kes.Sum6Kes.Root32(lab.cbor.Bytes.empty)").nonEmpty)
    assert(compileErrors("new lab.kes.Sum6Kes.Signature448(lab.cbor.Bytes.empty)").nonEmpty)
    assert(compileErrors("new lab.kes.Sum6Kes.RelativePeriod(64)").nonEmpty)
    assert(compileErrors("new lab.kes.Sum6Kes.SuppliedMessage(lab.cbor.Bytes.empty)").nonEmpty)
  }

  test(
    "every period consumes MSB first, exact pairs, unchanged message and one leaf after six hashes"
  ) {
    // Spy hashes model only routing, not cryptographic positives. Leaf always rejects.
    val raw = Bytes(Vector.tabulate(448)(i => (i * 73 + i / 32).toByte))
    val signature = Signature448.fromBytes(raw).toOption.get
    val message = SuppliedMessage.fromBytes(b("0001ff0080")).toOption.get
    for p <- 0 to 63 do
      var calls = 0; var leaves = 0
      var current = b("ff" * 32)
      val root = Root32.fromBytes(current).toOption.get
      val result = verifyWith(
        root,
        RelativePeriod.create(p).toOption.get,
        message,
        signature,
        pair => {
          val depth = 6 - calls; val offset = 64 + 64 * (depth - 1)
          assertEquals(pair, Bytes(raw.value.slice(offset, offset + 64)))
          val answer = current
          val selected = if ((p >> (depth - 1)) & 1) == 0 then 0 else 32
          current = Bytes(pair.value.slice(selected, selected + 32))
          calls += 1
          answer
        },
        (key, sig, msg) => {
          assertEquals(calls, 6); leaves += 1
          assertEquals(key.bytes, current)
          assertEquals(sig.bytes, Bytes(raw.value.take(64)))
          assertEquals(msg, message.bytes)
          Right(VerificationResult.SignatureRejected)
        }
      )
      assertEquals(result, rejected); assertEquals(calls, 6); assertEquals(leaves, 1)
  }

  test("first commitment mismatch at each depth stops work before leaf") {
    val (r, p, m, s) = args(originals.head)
    for stop <- 1 to 6 do
      var calls = 0
      val result = verifyWith(
        r,
        p,
        m,
        s,
        pair => {
          calls += 1
          val digest = Blake2b.hash256.hash(pair)
          if calls == stop then flip(digest, 0, 0) else digest
        },
        (_, _, _) => fail("leaf must not run")
      )
      assertEquals(result, rejected); assertEquals(calls, stop)
  }

  test("all null checked components fail before any dependency work") {
    val (r, p, m, s) = args(originals.head)
    Vector((null, p, m, s), (r, null, m, s), (r, p, null, s), (r, p, m, null)).foreach {
      case (root, period, message, signature) =>
        val result =
          verifyWith(root, period, message, signature, _ => fail("hash"), (_, _, _) => fail("leaf"))
        assert(result.swap.toOption.get.isInstanceOf[Failure.Malformed])
    }
    Vector(
      verify(null, 0, Bytes.empty, s.bytes),
      verify(r.bytes, 64, m.bytes, s.bytes),
      verify(r.bytes, 0, null, s.bytes),
      verify(r.bytes, 0, m.bytes, null)
    ).foreach(x => assert(x.swap.toOption.get.isInstanceOf[Failure.Malformed]))
  }

  test("dependency nonfatal failures stay typed; fatal errors propagate; rejection is distinct") {
    val (r, p, m, s) = args(originals.head)
    val reject: (PublicKey32, Signature64, Bytes) => Either[VerificationError, VerificationResult] =
      (_, _, _) => Right(VerificationResult.SignatureRejected)
    val brokenHash: Bytes => Bytes = _ => throw new IllegalArgumentException("injected")
    assertEquals(
      verifyWith(r, p, m, s, brokenHash, reject),
      Left(Failure.InternalFailure("java.lang.IllegalArgumentException"))
    )
    assertEquals(
      verifyWith(
        r,
        p,
        m,
        s,
        Blake2b.hash256.hash,
        (_, _, _) => throw new IllegalStateException("injected")
      ),
      Left(Failure.InternalFailure("java.lang.IllegalStateException"))
    )
    assertEquals(
      verifyWith(
        r,
        p,
        m,
        s,
        Blake2b.hash256.hash,
        (_, _, _) => Left(VerificationError.ImplementationFailure("injected"))
      ),
      Left(Failure.InternalFailure("injected"))
    )
    assertEquals(verifyWith(r, p, m, s, Blake2b.hash256.hash, reject), rejected)
    Vector(null, Bytes(null), Bytes.empty).foreach(x =>
      assert(
        verifyWith(r, p, m, s, _ => x, reject).swap.toOption.get
          .isInstanceOf[Failure.InternalFailure]
      )
    )
    assert(verifyWith(r, p, m, s, Blake2b.hash256.hash, (_, _, _) => null).isLeft)
    val fatal = new LinkageError("fatal")
    var caught: Throwable = null
    try verifyWith(r, p, m, s, _ => throw fatal, reject)
    catch case e: LinkageError => caught = e
    assertEquals(caught, fatal)
    caught = null
    try verifyWith(r, p, m, s, Blake2b.hash256.hash, (_, _, _) => throw fatal)
    catch case e: LinkageError => caught = e
    assertEquals(caught, fatal)
  }

  test("empty and maximum messages reach leaf unchanged only after six commitments") {
    val (r, p, _, s) = args(originals.head)
    Vector(Bytes.empty, Bytes(Vector.fill(MaxMessageBytes)(0.toByte))).foreach { bytes =>
      var calls = 0
      val result = verifyWith(
        r,
        p,
        SuppliedMessage.fromBytes(bytes).toOption.get,
        s,
        Blake2b.hash256.hash,
        (_, _, m) => {
          calls += 1; assertEquals(m, bytes); Right(VerificationResult.SignatureRejected)
        }
      )
      assertEquals(result, rejected); assertEquals(calls, 1)
    }
  }

  test("repeatability and bounded concurrent verification over shared immutable inputs") {
    val (r, p, m, s) = args(originals.head)
    (0 until 8).foreach(_ => assertEquals(verifySignature(r, p, m, s), good))
    val pool = Executors.newFixedThreadPool(4)
    try
      val tasks = (0 until 32).map(_ =>
        pool.submit(
          new Callable[Either[Failure, Result]]:
            def call(): Either[Failure, Result] = verifySignature(r, p, m, s)
        )
      )
      tasks.foreach(t => assertEquals(t.get(30, TimeUnit.SECONDS), good))
    finally
      pool.shutdownNow()
      assert(pool.awaitTermination(10, TimeUnit.SECONDS))
  }
