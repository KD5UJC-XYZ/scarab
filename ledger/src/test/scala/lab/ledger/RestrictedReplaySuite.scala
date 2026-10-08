// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import lab.cbor.{Bytes, Cbor, Node, Value as CValue}
import lab.ledger.RestrictedReplay as R
import scala.compiletime.testing.typeChecks

/** Archived final-output checks are independent expectations. Mutations and transition laws are
  * project tests, not new Haskell ledger goldens. No signatures are generated for this suite.
  */
class RestrictedReplaySuite extends munit.FunSuite:
  private val repository =
    if Files.exists(Path.of("fixtures/restricted-replay")) then Path.of(".") else Path.of("..")
  private val packet = repository.resolve("fixtures/restricted-replay")
  private def raw(name: String): Bytes = Bytes.fromArray(Files.readAllBytes(packet.resolve(name)))
  private def good[A](r: R.Checked[A]): A = r.fold(e => fail(e.toString), identity)
  private def sha(b: Bytes): Bytes =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(b.toArray))
  private def initial: R.State = good(
    R.initialize(
      good(R.environment(raw("pparams.cbor"))),
      raw("initial-utxo.cbor"),
      sha(raw("attribution.json"))
    )
  )
  private def tx(name: String, index: Int): Bytes = raw(s"$name-$index.cbor")
  private def decoded(raw: Bytes): Node = Cbor.decode(raw).fold(fail(_), identity)
  private def encode(value: CValue): Bytes = Cbor.encode(value).fold(fail(_), identity)
  private def node(value: CValue): Node = Node(value, Bytes.empty)
  private def entries(raw: Bytes): Vector[(Node, Node)] = decoded(raw).value match
    case CValue.Map(xs) => xs
    case _              => fail("expected map")
  private def envelope(raw: Bytes): Vector[Node] = decoded(raw).value match
    case CValue.Arr(xs) => xs
    case _              => fail("expected envelope")
  private def modifyBody(raw: Bytes)(f: Vector[(Node, Node)] => Vector[(Node, Node)]): Bytes =
    val parts = envelope(raw)
    val fields = parts(0).value match
      case CValue.Map(xs) => xs
      case _              => fail("expected body")
    encode(CValue.Arr(parts.updated(0, node(CValue.Map(f(fields))))))
  private def addBody(raw: Bytes, key: Int, value: CValue): Bytes =
    modifyBody(raw)(_ :+ (node(CValue.UInt(key)) -> node(value)))
  private def setBody(raw: Bytes, key: Int, value: CValue): Bytes =
    modifyBody(raw)(
      _.map((k, v) => if k.value == CValue.UInt(key) then (k, node(value)) else (k, v))
    )
  private def assertRejected[A](r: R.Checked[A], stage: String): Unit = r match
    case Left(R.Failure.Rejected(actual, _)) => assertEquals(actual, stage)
    case other                               => fail(s"expected rejection $stage, got $other")
  private def assertUnsupported[A](r: R.Checked[A]): Unit = r match
    case Left(R.Failure.Unsupported(_)) => ()
    case other                          => fail(s"expected unsupported, got $other")
  private def assertStale[A](r: R.Checked[A]): Unit = r match
    case Left(R.Failure.StaleState(_)) => ()
    case other                         => fail(s"expected stale state, got $other")
  private def assertResource[A](r: R.Checked[A]): Unit = r match
    case Left(R.Failure.ResourceLimit(_)) => ()
    case other                            => fail(s"expected resource limit, got $other")
  private def assertContent(a: R.State, b: R.State): Unit =
    assertEquals(a.stateId, b.stateId)
    assertEquals(a.outputMap, b.outputMap)
    assertEquals(a.feesSinceCheckpoint, b.feesSinceCheckpoint)
    assertEquals(a.utxo.view.mapValues(_.original).toMap, b.utxo.view.mapValues(_.original).toMap)

  test("archived fixture bytes have immutable pins independent of editable manifests") {
    val pins = Vector(
      "SHA256SUMS" -> "c6c67aca632f666e8ea76abb476b578f886d8f6c3cbc18cc65a933cce5d87074",
      "attribution.json" -> "090ce3ab908d74ed317e9641feb6761e13da2bc14d316186905024bef6d9b278",
      "expectations.json" -> "4255e64707480a11aab77ad7ae010e791e7fa9ba04137fb9427a0e36af846ea1",
      "initial-utxo.cbor" -> "3403e98e8bbdb656b05ce8082a78a0c60670ed2466696a494c5eff5b7a834896",
      "missing-vkey-1.cbor" -> "68efb43e8912ac53b387b89a8a181d1286ad8597c1e96f65c858c83a6cf3cf89",
      "missing-vkey-2.cbor" -> "ec192bb372150c07cea2b06412d8f0bfd3347c5a476d8dbfcde1e0e2ce1734f6",
      "missing-vkey-final-utxo.cbor" -> "5a127c1e9c7fd7537e51f6fe8be3f21551017a3add32edeacd32aa73d40bb1d9",
      "missing-vkey.trace.tsv" -> "936dddea2e97973f811bc18c2f07710b5b6774483cb816c40bcdd051f73897d5",
      "pparams.cbor" -> "23a62c9e17bfb1c9755134f173e29e7ff87f9991a84b5bb40ec177b262647633",
      "value-conservation-1.cbor" -> "8435826b0bf402ea445d8c4f1289b3bfe46e4e8e57a458c06fb903d9a6ca7ab8",
      "value-conservation-2.cbor" -> "9e6e88bb88277c13b6e411ecbc305b397d8431c5f61c31304f18fd66ef134d87",
      "value-conservation-final-utxo.cbor" -> "4b399005786dc5b2f3584920af3d47e1fe5585001be936ab3597a24c9091139b",
      "value-conservation.trace.tsv" -> "bb9df57d2cc633c830163ec532fb38cc5c984b12c2a88211b26b01f64820b339"
    )
    pins.foreach((name, expected) => assertEquals(sha(raw(name)).hex, expected, name))
  }

  test("profile descriptor binds immutable predicate source revisions") {
    R.PredicateSourceSha256.foreach { (path, expected) =>
      val actual = sha(Bytes.fromArray(Files.readAllBytes(repository.resolve(path)))).hex
      assertEquals(actual, expected, path)
      assert(R.ProfileDescriptor.contains(s"source=$path:$expected"))
    }
    assertEquals(
      sha(
        Bytes.fromArray(R.ProfileDescriptor.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
      ),
      R.ProfileHash
    )
  }

  for (name, stage) <- Vector("value-conservation" -> "balance", "missing-vkey" -> "coverage") do
    test(s"$name: accepted setup matches independent exact archived final output bytes") {
      val before = initial
      val applied = good(R.applyTransaction(before, tx(name, 1)))
      val expected = Coverage.decodeResolved(raw(s"$name-final-utxo.cbor")).toOption.get
      assertEquals(
        applied.state.utxo.view.mapValues(_.original).toMap,
        expected.view.mapValues(_.original).toMap
      )
      assertEquals(applied.state.feesSinceCheckpoint, BigInt(167041))
      assertEquals(applied.state.revision.number, BigInt(1))
      assertEquals(applied.delta.get.transactions.size, 1)
      assertEquals(applied.delta.get.transactions.head.originalTransaction, tx(name, 1))
      assertEquals(before.feesSinceCheckpoint, BigInt(0))
      assertEquals(before.utxo.size, 1)
    }
    test(s"$name: rejected dependent event sees current outputs and changes nothing") {
      val applied = good(R.applyTransaction(initial, tx(name, 1)))
      val before = applied.state
      assertRejected(R.applyTransaction(before, tx(name, 2)), stage)
      assertEquals(before.feesSinceCheckpoint, BigInt(167041))
      assertEquals(before.revision.number, BigInt(1))
      assertEquals(before.stateId, applied.delta.get.afterId)
      assertRejected(R.applyTransaction(initial, tx(name, 2)), "inputs")
    }
    test(s"$name: atomic dependent rejection publishes no partial state") {
      val before = initial
      assertRejected(R.applyBatch(before, Vector(tx(name, 1), tx(name, 2))), stage)
      assertRejected(R.applyBatch(before, Vector(tx(name, 2), tx(name, 1))), "inputs")
      assertEquals(before.feesSinceCheckpoint, BigInt(0))
      assertEquals(before.revision.number, BigInt(0))
      assertContent(before, initial)
    }
    test(s"$name: exact undo, duplicate replay, stale undo and fresh reapplication") {
      val before = initial
      val applied = good(R.applyTransaction(before, tx(name, 1)))
      val delta = applied.delta.get
      assertRejected(R.applyTransaction(applied.state, tx(name, 1)), "inputs")
      assertStale(R.undo(applied.state, before.revision, delta))
      val undone = good(R.undo(applied.state, applied.state.revision, delta))
      assertContent(undone, before)
      assertEquals(undone.revision.number, BigInt(2))
      assertStale(R.undo(undone, undone.revision, delta))
      val replayed = good(R.applyTransaction(undone, tx(name, 1)))
      assertContent(replayed.state, applied.state)
      assertEquals(replayed.state.revision.number, BigInt(3))
      assert(replayed.delta.get.transitionId != delta.transitionId)
      assertStale(R.undo(replayed.state, replayed.state.revision, delta))
      assertContent(
        good(R.undo(replayed.state, replayed.state.revision, replayed.delta.get)),
        before
      )
    }

  test("alternative branches require rollback and cannot spend a consumed common root") {
    val s = initial
    val a = good(R.applyTransaction(s, tx("value-conservation", 1)))
    assertRejected(R.applyTransaction(a.state, tx("missing-vkey", 1)), "inputs")
    val restored = good(R.undo(a.state, a.state.revision, a.delta.get))
    val b = good(R.applyTransaction(restored, tx("missing-vkey", 1)))
    assertRejected(R.applyTransaction(b.state, tx("value-conservation", 1)), "inputs")
    assertStale(R.undo(b.state, b.state.revision, a.delta.get))
    assertContent(good(R.undo(b.state, b.state.revision, b.delta.get)), s)
  }

  test("prepared result is fenced after apply/undo ABA and after branch change") {
    val before = initial
    val prepared = good(R.prepareBatch(before, Vector(tx("missing-vkey", 1))))
    val a = good(R.applyTransaction(before, tx("value-conservation", 1)))
    assertStale(R.commitPrepared(a.state, prepared))
    val restored = good(R.undo(a.state, a.state.revision, a.delta.get))
    assertEquals(restored.stateId, before.stateId)
    assertStale(R.commitPrepared(restored, prepared))
    assertEquals(good(R.commitPrepared(before, prepared)).state.feesSinceCheckpoint, BigInt(167041))
  }

  test("empty batches have no delta or new revision; empty preparations are also fenced") {
    val s = initial
    val empty = good(R.applyBatch(s, Vector.empty))
    assert(empty.state eq s)
    assertEquals(empty.delta, None)
    val prepared = good(R.prepareBatch(s, Vector.empty))
    val a = good(R.applyTransaction(s, tx("value-conservation", 1)))
    assertStale(R.commitPrepared(a.state, prepared))
  }

  test("constructor and copy paths cannot manufacture checked states, deltas or success") {
    assert(!typeChecks("new lab.ledger.RestrictedReplay.Revision(BigInt(0))"))
    assert(!typeChecks("new lab.ledger.RestrictedReplay.Environment(null, null)"))
    assert(
      !typeChecks("new lab.ledger.RestrictedReplay.ResearchCheckpoint(null, null, null, null)")
    )
    assert(
      !typeChecks(
        "new lab.ledger.RestrictedReplay.State(null, Map.empty, BigInt(0), null, null, null, None)"
      )
    )
    assert(!typeChecks("new lab.ledger.RestrictedReplay.PreparedBatch(null, null, Vector.empty)"))
    assert(
      !typeChecks(
        "new lab.ledger.RestrictedReplay.BatchDelta(null, null, null, null, Vector.empty, None)"
      )
    )
    assert(
      !typeChecks(
        "new lab.ledger.RestrictedReplay.Delta(null, null, Map.empty, Map.empty, BigInt(0), null, null, null)"
      )
    )
    assert(!typeChecks("new lab.ledger.RestrictedReplay.BatchApplied(null, None)"))
    assert(
      !typeChecks("(null: lab.ledger.RestrictedReplay.State).copy(feesSinceCheckpoint = BigInt(3))")
    )
  }

  test("mint and script fields are unsupported by presence, including empty values") {
    val s = initial
    for key <- Vector(3, 4, 5, 7, 8, 9, 11, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22) do
      assertUnsupported(
        R.applyTransaction(s, addBody(tx("value-conservation", 1), key, CValue.Map(Vector.empty)))
      )
    val parts = envelope(tx("value-conservation", 1))
    for key <- Vector(1, 2, 3, 4, 5, 6, 7) do
      val witnessMap =
        node(CValue.Map(Vector(node(CValue.UInt(key)) -> node(CValue.Arr(Vector.empty)))))
      assertUnsupported(R.applyTransaction(s, encode(CValue.Arr(parts.updated(1, witnessMap)))))
  }

  test("genuine Mary mint traces remain unsupported regardless of standalone balance results") {
    val rows = Files.readAllLines(repository.resolve("fixtures/ledger/ledger-vectors.tsv"))
    import scala.jdk.CollectionConverters.*
    val mint = rows.asScala.filter(_.startsWith("conway-pv9-mary-")).toVector
    assertEquals(mint.size, 2)
    mint.foreach(line =>
      assertUnsupported(
        R.applyTransaction(initial, Bytes.fromHex(line.split("\t")(1)).toOption.get)
      )
    )
  }

  test("empty witnesses reject required-key coverage instead of vacuous signature success") {
    val parts = envelope(tx("value-conservation", 1))
    val empty = encode(CValue.Arr(parts.updated(1, node(CValue.Map(Vector.empty)))))
    assertRejected(R.applyTransaction(initial, empty), "coverage")
  }

  test("empty and duplicate inputs are rejected before signature work") {
    val original = tx("value-conservation", 1)
    assertRejected(
      R.applyTransaction(initial, setBody(original, 0, CValue.Arr(Vector.empty))),
      "inputs"
    )
    val body = entries(envelope(original)(0).original)
    val ins = body.find(_._1.value == CValue.UInt(0)).get._2.value match
      case CValue.Tag(_, inner) =>
        inner.value match
          case CValue.Arr(xs) => xs
          case _              => fail("inputs")
      case _ => fail("inputs tag")
    assert(R.applyTransaction(initial, setBody(original, 0, CValue.Arr(ins ++ ins))).isLeft)
  }

  test("body and signature mutations never change state") {
    val original = tx("value-conservation", 1)
    val changedFee = setBody(original, 2, CValue.UInt(167042))
    assertRejected(R.applyTransaction(initial, changedFee), "signature")
    val parts = envelope(original)
    val corruptedWitness = Bytes(
      parts(1).original.value
        .updated(parts(1).original.size - 1, (parts(1).original.value.last ^ 1).toByte)
    )
    val corrupted = Bytes(
      Vector(0x84.toByte) ++ parts(0).original.value ++ corruptedWitness.value ++ Vector(
        0xf5.toByte,
        0xf6.toByte
      )
    )
    assertRejected(R.applyTransaction(initial, corrupted), "signature")
  }

  test("memo witness size is checked on original bytes even when signatures still verify") {
    val parts = envelope(tx("value-conservation", 1))
    assertEquals(parts(1).original.value.head, 0xa1.toByte)
    val indefiniteWitnesses =
      Vector(0xbf.toByte) ++ parts(1).original.value.tail ++ Vector(0xff.toByte)
    val wider = Bytes(
      Vector(0x84.toByte) ++ parts(0).original.value ++ indefiniteWitnesses ++ Vector(
        0xf5.toByte,
        0xf6.toByte
      )
    )
    assertRejected(R.applyTransaction(initial, wider), "fee")
  }

  test("original outer framing changes neither exact body signatures nor memo size") {
    val original = tx("value-conservation", 1)
    assertEquals(original.value.head, 0x84.toByte)
    val alternate = Bytes(Vector(0x9f.toByte) ++ original.value.tail ++ Vector(0xff.toByte))
    val a = good(R.applyTransaction(initial, original))
    val b = good(R.applyTransaction(initial, alternate))
    assertContent(a.state, b.state)
    assert(a.delta.get.transitionId != b.delta.get.transitionId)
    assertEquals(b.delta.get.transactions.head.originalTransaction, alternate)
  }

  test("encoded multiasset remains unsupported even when normalized asset quantities are zero") {
    val original = tx("value-conservation", 1)
    val fields = entries(envelope(original)(0).original)
    val outputs = fields.find(_._1.value == CValue.UInt(1)).get._2.value match
      case CValue.Arr(xs) => xs
      case _              => fail("outputs")
    val out = outputs.head.value match
      case CValue.Arr(xs) => xs
      case _              => fail("output")
    val zeroAssets = CValue.Map(
      Vector(
        node(CValue.ByteString(Bytes(Vector.fill(28)(0.toByte)))) -> node(
          CValue.Map(Vector(node(CValue.ByteString(Bytes.empty)) -> node(CValue.UInt(0))))
        )
      )
    )
    for assets <- Vector(CValue.Map(Vector.empty), zeroAssets) do
      val amount = node(CValue.Arr(Vector(out(1), node(assets))))
      val changed = node(CValue.Arr(out.updated(1, amount)))
      assertUnsupported(
        R.applyTransaction(initial, setBody(original, 1, CValue.Arr(outputs.updated(0, changed))))
      )
  }

  test("state scope is checked at checkpoint admission including unspent output entries") {
    val original = raw("initial-utxo.cbor")
    val entry = entries(original).head
    val parts = entry._2.value match
      case CValue.Arr(xs) => xs
      case _              => fail("checkpoint output")
    val multi = node(CValue.Arr(Vector(parts(1), node(CValue.Map(Vector.empty)))))
    val unsupported =
      encode(CValue.Map(Vector(entry._1 -> node(CValue.Arr(parts.updated(1, multi))))))
    assertUnsupported(
      R.initialize(
        good(R.environment(raw("pparams.cbor"))),
        unsupported,
        sha(raw("attribution.json"))
      )
    )
  }

  test("output collision is rejected without overwriting unrelated checkpoint entries") {
    val s = initial
    val txid = Coverage.decode(tx("value-conservation", 1)).toOption.get.body.hash.bytes
    val key = node(CValue.Arr(Vector(node(CValue.ByteString(txid)), node(CValue.UInt(0)))))
    val collisionMap = encode(
      CValue.Map(
        entries(raw("initial-utxo.cbor")) :+ (key -> entries(raw("initial-utxo.cbor")).head._2)
      )
    )
    val collision =
      good(R.initialize(s.checkpoint.environment, collisionMap, s.checkpoint.attributionDigest))
    assertRejected(R.applyTransaction(collision, tx("value-conservation", 1)), "output")
    assertEquals(collision.utxo.size, 2)
    assertEquals(collision.feesSinceCheckpoint, BigInt(0))
  }

  test("checkpoint output byte framing survives apply and exact undo") {
    val original = raw("initial-utxo.cbor")
    val entry = entries(original).head
    val output = entry._2.original
    assertEquals(output.value.head, 0x82.toByte)
    val altered = Bytes(
      Vector(0xa1.toByte) ++ entry._1.original.value ++ Vector(
        0x9f.toByte
      ) ++ output.value.tail ++ Vector(0xff.toByte)
    )
    val s = good(
      R.initialize(initial.checkpoint.environment, altered, initial.checkpoint.attributionDigest)
    )
    val applied = good(R.applyTransaction(s, tx("value-conservation", 1)))
    val undone = good(R.undo(applied.state, applied.state.revision, applied.delta.get))
    assertContent(s, undone)
    assertEquals(undone.utxo.values.head.original.value.head, 0x9f.toByte)
  }

  test("parameter identity, malformed checkpoint and null boundaries are checked") {
    assertUnsupported(R.environment(Bytes.empty))
    assert(R.initialize(null, Bytes.empty, Bytes.empty).isLeft)
    assert(
      R.initialize(initial.checkpoint.environment, raw("initial-utxo.cbor"), Bytes.empty).isLeft
    )
    assert(
      R.initialize(
        initial.checkpoint.environment,
        Bytes(Vector(0xa0.toByte)),
        initial.checkpoint.attributionDigest
      ).isLeft
    )
    assert(R.applyTransaction(initial, null).isLeft)
    assert(R.prepareBatch(initial, null).isLeft)
    assert(R.commitPrepared(initial, null).isLeft)
    assert(R.undo(initial, initial.revision, null).isLeft)
  }

  test("count, byte and pre-projection witness limits are resource failures") {
    assertResource(R.applyBatch(initial, Vector.fill(65)(Bytes.empty)))
    assertResource(
      R.applyTransaction(initial, Bytes(Vector.fill(R.MaxTransactionBytes + 1)(0.toByte)))
    )
    assertResource(
      R.applyBatch(initial, Vector.fill(9)(Bytes(Vector.fill(R.MaxTransactionBytes)(0.toByte))))
    )
    val parts = envelope(tx("value-conservation", 1))
    val witnesses = node(
      CValue.Map(
        Vector(node(CValue.UInt(0)) -> node(CValue.Arr(Vector.fill(129)(node(CValue.Null)))))
      )
    )
    assertResource(R.applyTransaction(initial, encode(CValue.Arr(parts.updated(1, witnesses)))))
    val refs = CValue.Arr(Vector.fill(4097)(node(CValue.Null)))
    assertResource(R.applyTransaction(initial, setBody(tx("value-conservation", 1), 0, refs)))
  }

  test("duplicate VKeys fail malformed admission and extra unrelated witnesses are verified") {
    val original = tx("value-conservation", 1)
    val parts = envelope(original)
    val required = Coverage.decode(original).toOption.get.witnesses.head
    val unrelated = Coverage.decode(tx("missing-vkey", 2)).toOption.get.witnesses.head
    assert(required.publicKey != unrelated.publicKey)
    def witness(w: lab.witness.VKeyWitness): Node = node(
      CValue.Arr(
        Vector(
          node(CValue.ByteString(w.publicKey.bytes)),
          node(CValue.ByteString(w.signature.bytes))
        )
      )
    )
    def withWitnesses(ws: Vector[Node]): Bytes =
      val witnessMap = encode(CValue.Map(Vector(node(CValue.UInt(0)) -> node(CValue.Arr(ws)))))
      Bytes(
        Vector(0x84.toByte) ++ parts(0).original.value ++ witnessMap.value ++ Vector(
          0xf5.toByte,
          0xf6.toByte
        )
      )
    R.applyTransaction(initial, withWitnesses(Vector(witness(required), witness(required)))) match
      case Left(R.Failure.Malformed(_)) => ()
      case other                        => fail(s"duplicate VKey was not malformed: $other")
    val extra = withWitnesses(Vector(witness(required), witness(unrelated)))
    val projection = Coverage.decode(extra).toOption.get
    assert(Coverage.check("Conway", 9, initial.utxo, projection).toOption.get.covered)
    assertRejected(R.applyTransaction(initial, extra), "signature")
  }

  test("output count and checkpoint entry bounds fail before typed collection construction") {
    assertResource(
      R.applyTransaction(
        initial,
        setBody(tx("value-conservation", 1), 1, CValue.Arr(Vector.fill(4097)(node(CValue.Null))))
      )
    )
    val entry = entries(raw("initial-utxo.cbor")).head
    val tooMany = encode(CValue.Map(Vector.fill(4097)(entry)))
    assertResource(
      R.initialize(initial.checkpoint.environment, tooMany, initial.checkpoint.attributionDigest)
    )
  }

  test("next-state entry bound is checked before publishing a valid setup") {
    val s = initial
    val entry = entries(raw("initial-utxo.cbor")).head
    val rootId = s.utxo.keys.head.id
    val full = encode(CValue.Map(Vector.tabulate(4096) { index =>
      node(
        CValue.Arr(Vector(node(CValue.ByteString(rootId)), node(CValue.UInt(index))))
      ) -> entry._2
    }))
    val checkpoint =
      good(R.initialize(s.checkpoint.environment, full, s.checkpoint.attributionDigest))
    assertEquals(checkpoint.utxo.size, 4096)
    assertResource(R.applyTransaction(checkpoint, tx("value-conservation", 1)))
    assertEquals(checkpoint.feesSinceCheckpoint, BigInt(0))
    assertEquals(checkpoint.revision.number, BigInt(0))
  }

  test("malformed checkpoint shapes and duplicate semantic input keys cannot create State") {
    val s = initial
    val entry = entries(raw("initial-utxo.cbor")).head
    val alternate =
      node(CValue.Arr(Vector(node(CValue.ByteString(s.utxo.keys.head.id)), node(CValue.UInt(0)))))
    Vector(
      encode(CValue.Arr(Vector.empty)),
      encode(CValue.UInt(0)),
      encode(CValue.Map(Vector(entry, alternate -> entry._2)))
    )
      .foreach { invalid =>
        R.initialize(s.checkpoint.environment, invalid, s.checkpoint.attributionDigest) match
          case Left(R.Failure.Malformed(_)) => ()
          case other                        => fail(s"expected malformed checkpoint, got $other")
      }
  }

  test("assembled state remains closed under the local decoder item cap") {
    val s = initial
    val root = entries(raw("initial-utxo.cbor")).head
    val unrelatedKey = encode(
      CValue.Arr(
        Vector(node(CValue.ByteString(Bytes(Vector.fill(32)(0.toByte)))), node(CValue.UInt(0)))
      )
    )
    val address = encode(CValue.ByteString(s.utxo.values.head.address))
    // 65,534 items initially; replacing the genuine root by A's two outputs adds six items.
    // Empty byte-string chunks preserve the public address without any signing or key changes.
    val unrelatedOutput = Vector(0x82.toByte, 0x5f.toByte) ++ Vector.fill(65520)(0x40.toByte) ++
      address.value ++ Vector(0xff.toByte, 0x00.toByte)
    val nearLimit = Bytes(
      Vector(0xa2.toByte) ++ root._1.original.value ++ root._2.original.value ++
        unrelatedKey.value ++ unrelatedOutput
    )
    val admitted =
      good(R.initialize(s.checkpoint.environment, nearLimit, s.checkpoint.attributionDigest))
    assertEquals(admitted.utxo.size, 2)
    assertResource(R.applyTransaction(admitted, tx("value-conservation", 1)))
    assertEquals(admitted.revision.number, BigInt(0))
    assertEquals(admitted.feesSinceCheckpoint, BigInt(0))
  }

  test("cached sort keys are evaluated exactly once per entry with identical ordering") {
    val values = Vector.tabulate(4096)(index => (index * 73) % 4096)
    var calls = 0
    val actual = R.sortByCachedKey(values) { value =>
      calls += 1
      value
    }
    assertEquals(calls, 4096)
    assertEquals(actual, values.sorted)
    assertEquals(R.sortByCachedKey(Vector.empty[Int])(identity), Vector.empty[Int])
  }

  test("cached reference sorting preserves exact mixed-ID and width-boundary output bytes") {
    val source = initial
    val output = source.utxo.values.head.original
    val unordered = for
      first <- Vector(255, 16, 0)
      index <- Vector(65535, 256, 255, 24, 23, 0)
    yield TxIn
      .create(Bytes(Vector(first.toByte) ++ Vector.fill(31)(0.toByte)), BigInt(index))
      .toOption
      .get
    val ordered = for
      first <- Vector(0, 16, 255)
      index <- Vector(0, 23, 24, 255, 256, 65535)
    yield TxIn
      .create(Bytes(Vector(first.toByte) ++ Vector.fill(31)(0.toByte)), BigInt(index))
      .toOption
      .get
    def mapBytes(refs: Vector[TxIn]): Bytes = Bytes(Vector(0xb2.toByte) ++ refs.flatMap { ref =>
      encode(
        CValue.Arr(Vector(node(CValue.ByteString(ref.id)), node(CValue.UInt(ref.index))))
      ).value ++ output.value
    })
    val admitted = good(
      R.initialize(
        source.checkpoint.environment,
        mapBytes(unordered),
        source.checkpoint.attributionDigest
      )
    )
    assertEquals(admitted.utxo.size, 18)
    assertEquals(admitted.outputMap, mapBytes(ordered))
    assert(admitted.utxo.values.forall(_.original == output))
  }

  test("sort-key caching preserves prepatch profile and state identities") {
    val before = initial
    assertEquals(
      R.ProfileHash.hex,
      "add3a990f26a7a0948485db668dc6fca15fa902137ef3cf12f1580830825b46f"
    )
    assertEquals(
      before.checkpoint.id.hex,
      "4e746db5fee2014eb4a5c267da707070042481ecc020ad98375689202e9c1039"
    )
    assertEquals(
      before.stateId.hex,
      "27a684fc372aa860fabe926a892f18f1a7df763c4a4fc5081fe8eb85d5e547db"
    )
    val applied = good(R.applyTransaction(before, tx("value-conservation", 1)))
    assertEquals(
      applied.state.stateId.hex,
      "d81b5aef6e15d7826896f5f3898d18a16cad9b4fde9a401a552ab5efd8dcd2cf"
    )
  }
