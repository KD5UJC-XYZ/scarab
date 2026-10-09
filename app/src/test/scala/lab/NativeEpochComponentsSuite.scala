// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.header.PraosCertificateState.Point
import ReferenceJson.{Json as J, field}

class NativeEpochComponentsSuite extends munit.FunSuite:
  private val E = NativeEpochComponents
  private def get[A](e: Either[?, A]): A = e.fold(x => fail(x.toString), identity)
  private def sha(b: Bytes) = ClusterHeaderObservation.sha256(b)
  private def raw(s: String) = Bytes.fromArray(s.getBytes("UTF-8"))
  private def node(v: V) = Node(v, Bytes.empty)
  private def arr(n: Node) = n.value.asInstanceOf[V.Arr].value
  private def decode(b: Bytes) = get(Cbor.decode(b))
  private def encode(n: Node) = get(Cbor.encode(n.value))
  private def at(n: Node, p: Vector[Int]): Node = p.foldLeft(n)((a, i) => arr(a)(i))
  private def change(n: Node, p: Vector[Int], v: Node): Node =
    if p.isEmpty then v else node(V.Arr(arr(n).updated(p.head, change(arr(n)(p.head), p.tail, v))))
  private def replace(j: J, path: Vector[String], v: J): J =
    if path.isEmpty then v
    else
      val m = j.asInstanceOf[J.Obj].fields
      J.Obj(m.updated(path.head, replace(m(path.head), path.tail, v)))
  private def quoted(s: String): String = "\"" + s.flatMap {
    case '\"' => "\\\""
    case '\\' => "\\\\"
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case c    => c.toString
  } + "\""
  private def render(j: J): String = j match
    case J.Obj(m) =>
      m.toVector.sortBy(_._1).map((k, v) => quoted(k) + ":" + render(v)).mkString("{", ",", "}")
    case J.Arr(xs) => xs.map(render).mkString("[", ",", "]")
    case J.Str(s)  => quoted(s)
    case J.Num(n)  => n
    case J.Lit(v)  => v
  test("no checked-components result without complete bounded originals") {
    assert(E.decode(Map.empty, Map.empty, null).isLeft)
    assert(E.decode(null, null, null).isLeft)
  }
  test("pot projection takes fees from UTxOState index2, never donations at index5") {
    val chain = node(V.Arr(Vector(node(V.UInt(3)), node(V.UInt(5)))))
    val us = node(V.Arr(Vector(0, 0, 7, 0, 0, 11).map(n => node(V.UInt(n)))))
    val pots = get(E.projectedPots(chain, us, 100))
    assertEquals(pots.treasury, BigInt(3))
    assertEquals(pots.reserves, BigInt(5))
    assertEquals(pots.fees, BigInt(7))
    assertNotEquals(pots.fees, BigInt(11))
  }
  sys.env.get("NATIVE_COMPONENTS_BUNDLE").foreach { root =>
    val directory = Path.of(root)
    def read(name: String): Bytes =
      val stream = Files.newInputStream(directory.resolve(name))
      val bytes =
        try stream.readNBytes(524289)
        finally stream.close()
      assert(bytes.length <= 524288); Bytes.fromArray(bytes)
    def originals = E.Names.map(n => n -> read(n)).toMap
    def pins(m: Map[String, Bytes]) = m.map((k, v) => k -> sha(v))
    val anchor = Point(
      get(Bytes.fromHex("20ff3cd11037c1d5393915aacf08fd1a0b3441b6e8e045d03b2595a808b289fe")),
      36,
      1
    )
    val paths = Map(
      "accounts" -> Vector(3, 1, 0, 2, 0),
      "certificateState" -> Vector(3, 1, 0),
      "chainAccountState" -> Vector(3, 0),
      "committee" -> Vector(3, 1, 1, 3, 1),
      "committeeState" -> Vector(3, 1, 0, 0, 1),
      "constitution" -> Vector(3, 1, 1, 3, 2),
      "currentBlocks" -> Vector(2),
      "currentParameters" -> Vector(3, 1, 1, 3, 3),
      "delegationState" -> Vector(3, 1, 0, 2),
      "deposits" -> Vector(3, 1, 1, 1),
      "donations" -> Vector(3, 1, 1, 5),
      "dormantEpochs" -> Vector(3, 1, 0, 0, 2),
      "drepPulsingState" -> Vector(3, 1, 1, 3, 6),
      "dreps" -> Vector(3, 1, 0, 0, 0),
      "fees" -> Vector(3, 1, 1, 2),
      "futureDelegations" -> Vector(3, 1, 0, 2, 1),
      "futureParameters" -> Vector(3, 1, 1, 3, 5),
      "futurePools" -> Vector(3, 1, 0, 1, 2),
      "goPools" -> Vector(3, 2, 2, 1),
      "goSnapshot" -> Vector(3, 2, 2),
      "governance" -> Vector(3, 1, 1, 3),
      "instantaneousRewards" -> Vector(3, 1, 0, 2, 3),
      "instantaneousStake" -> Vector(3, 1, 1, 4),
      "leadership" -> Vector(5),
      "nonMyopic" -> Vector(3, 3),
      "nonMyopicLikelihoods" -> Vector(3, 3, 0),
      "nonMyopicRewardPot" -> Vector(3, 3, 1),
      "pools" -> Vector(3, 1, 0, 1, 1),
      "previousBlocks" -> Vector(1),
      "previousParameters" -> Vector(3, 1, 1, 3, 4),
      "proposals" -> Vector(3, 1, 1, 3, 0),
      "retiringPools" -> Vector(3, 1, 0, 1, 3),
      "rewardState" -> Vector(4),
      "snapshots" -> Vector(3, 2),
      "votingState" -> Vector(3, 1, 0, 0)
    )
    // Rewrite all explicit source bindings coherently, so tests reach semantic checks.
    // Mutated packets remain in memory; originals and native evidence are never overwritten.
    def mutated(path: Vector[Int], value: Node): Map[String, Bytes] =
      val original = originals;
      val full = change(decode(original("derived-full-epoch-seed.cbor")), path, value)
      val debug = change(full, Vector(3, 1, 1, 0), node(V.Map(Vector.empty)))
      val seedBytes = encode(full); val debugBytes = encode(debug)
      var projection = ReferenceJson.parse(original("native-projection.json"))
      projection = replace(projection, Vector("seedSHA256"), J.Str(sha(seedBytes).hex))
      paths.foreach { (name, p) =>
        val bytes = encode(at(full, p))
        projection = replace(projection, Vector("components", name, "cborHex"), J.Str(bytes.hex))
        projection =
          replace(projection, Vector("components", name, "sha256"), J.Str(sha(bytes).hex))
      }
      val capture = replace(
        ReferenceJson.parse(original("capture.json")),
        Vector("epochHex"),
        J.Str(debugBytes.hex)
      )
      original
        .updated("derived-full-epoch-seed.cbor", seedBytes)
        .updated("original-debug-epoch.cbor", debugBytes)
        .updated("native-projection.json", raw(render(projection)))
        .updated("capture.json", raw(render(capture)))
    def rejects(path: Vector[Int], value: Node): Unit =
      val m = mutated(path, value); assert(E.decode(m, pins(m), anchor).isLeft)

    test("audited original bundle derives facts independently and retains exact absent identity") {
      val m = originals
      assertEquals(
        sha(m("native-projection.json")).hex,
        "19bff89f7dfd13f54312ba3cb6dfcad46ebb8b69d347221df0d2965948b49dc7"
      )
      assertEquals(
        sha(m("derived-full-epoch-seed.cbor")).hex,
        "38d8d8fd63e5f92e59ebb8bbdc687029a91435473832a420a1ce79f3cac9e493"
      )
      val p = get(E.decode(m, pins(m), anchor))
      assertEquals(p.utxoCoin, BigInt("90000018000000"));
      assertEquals(p.pots.reserves, BigInt("10000002000000"))
      assertEquals(p.stake.instantaneous.values.sum, BigInt("45000009000000"))
      assertEquals(p.stake.snapshots.mark.total, BigInt("45000009000000"))
      assertEquals(
        p.stake.snapshots.mark.pools.values
          .map(x => (x.ratio.numerator, x.ratio.denominator))
          .toSet,
        Set((BigInt(1), BigInt(3)), (BigInt(2), BigInt(3)))
      )
      assert(p.stake.snapshots.set.pools.isEmpty && p.stake.snapshots.go.pools.isEmpty)
      assert(p.previousBlocks.isEmpty);
      assertEquals(p.currentBlocks.values.toVector, Vector(BigInt(2)))
      assertEquals(p.point.blockNo, BigInt(1)); assert(p.nonMyopic.likelihoods.isEmpty);
      assertEquals(p.nonMyopic.rewardPot, BigInt(0))
      assertEquals(p.reward.original.hex, "80");
      assertEquals(p.reward.seedSHA256, pins(m)("derived-full-epoch-seed.cbor"))
      assertEquals(p.reward.bindingId, p.id); assertEquals(p.reward.point, anchor);
      assertEquals(p.originals, m)
      assert(
        !p.runtimeImport && !p.rewardSeedAdmission && !p.authenticatedSnapshot && !p.fullEpochSemanticsChecked && !p.historicalFreezeAvailable
      )
      assert(!p.parameters.timingProfileCompatible)
    }
    test("opaque invariant report is unused; no block-count inference from point block number") {
      val m = originals;
      val j = replace(
        ReferenceJson.parse(m("native-projection.json")),
        Vector("invariants"),
        J.Lit("null")
      )
      val changed = m.updated("native-projection.json", raw(render(j)));
      assert(E.decode(changed, pins(changed), anchor).isRight)
      val full = decode(m("derived-full-epoch-seed.cbor"));
      val current = at(full, Vector(2)).value.asInstanceOf[V.Map].value
      val changedCount =
        mutated(Vector(2), node(V.Map(current.map((k, _) => k -> node(V.UInt(3))))))
      val p = get(E.decode(changedCount, pins(changedCount), anchor));
      assertEquals(p.currentBlocks.values.toVector, Vector(BigInt(3)))
    }
    test(
      "coherent mutations reject unsupported pending effects and zero-complete masquerading as absent"
    ) {
      val nonempty = node(V.Map(Vector(node(V.UInt(0)) -> node(V.UInt(0)))))
      Vector("futurePools", "retiringPools", "futureDelegations")
        .foreach(n => rejects(paths(n), nonempty))
      rejects(
        paths("instantaneousRewards"),
        node(
          V.Arr(
            Vector(
              node(V.Map(Vector.empty)),
              node(V.Map(Vector.empty)),
              node(V.UInt(1)),
              node(V.UInt(0))
            )
          )
        )
      )
      rejects(paths("rewardState"), node(V.Arr(Vector(node(V.Arr(Vector(node(V.UInt(0)))))))))
      rejects(paths("nonMyopicRewardPot"), node(V.UInt(1)))
    }
    test(
      "coherent mutations reject supply instantaneous mark count-role and delegation inconsistencies"
    ) {
      val full = decode(originals("derived-full-epoch-seed.cbor"))
      rejects(Vector(3, 0, 1), node(V.UInt(BigInt("10000002000001"))))
      rejects(paths("instantaneousStake"), node(V.Map(Vector.empty)))
      rejects(Vector(3, 2, 0), at(full, Vector(3, 2, 2)))
      rejects(paths("previousBlocks"), at(full, Vector(2)))
      rejects(paths("accounts"), node(V.Map(Vector.empty)))
      rejects(paths("dreps"), node(V.Map(Vector.empty)))
      rejects(paths("deposits"), node(V.UInt(1)))
      rejects(paths("fees"), node(V.UInt(1)))
      rejects(paths("donations"), node(V.UInt(1)))
    }
    test("component drift trailing CBOR and wrong point or external pins fail closed") {
      val m = originals;
      val bad = m.updated(
        "original-whole-utxo.cbor",
        Bytes(m("original-whole-utxo.cbor").value :+ 0.toByte)
      )
      assert(E.decode(bad, pins(bad), anchor).isLeft)
      assert(
        E.decode(m, pins(m).updated("capture.json", Bytes(Vector.fill(32)(0.toByte))), anchor)
          .isLeft
      )
      val wrong = Point(anchor.hash, 37, 1); assert(E.decode(m, pins(m), wrong).isLeft)
      val j = replace(
        ReferenceJson.parse(m("native-projection.json")),
        Vector("components", "currentBlocks", "cborHex"),
        J.Str("a0")
      )
      val drift = m.updated("native-projection.json", raw(render(j)));
      assert(E.decode(drift, pins(drift), anchor).isLeft)
    }
  }
