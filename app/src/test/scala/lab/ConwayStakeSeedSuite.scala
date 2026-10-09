// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{ConwayStake as Stake, ClusterTransition as Ledger}

class ConwayStakeSeedSuite extends munit.FunSuite:
  private def get[E, A](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)
  private def raw(s: String) = Bytes.fromArray(s.getBytes("UTF-8"))
  private def sha(b: Bytes) = ClusterHeaderObservation.sha256(b)
  test("seed cannot infer missing JSON fields or accept unpinned bytes") {
    assert(ConwayStakeSeed.decode(raw("{}"), sha(raw("{}")), raw("x"), sha(raw("x")), 500).isLeft)
    assert(ConwayStakeSeed.decode(raw("{}"), Bytes.empty, Bytes.empty, Bytes.empty, 500).isLeft)
  }
  sys.env.get("STAKE_SEQUENCE_EVIDENCE").foreach { location =>
    val dir = Path.of(location)
    def read(n: String) = Bytes.fromArray(Files.readAllBytes(dir.resolve(n)))
    def original = get(SequenceInput.load(dir))
    def utxo = get(Bytes.fromHex(new String(read("pre-utxo-cbor.md").toArray, "UTF-8").trim))
    def prepared = get(
      ConwayStakeSeed.decode(
        read("pre-ledger-state.md"),
        get(Bytes.fromHex("027f2839ae0ec1b8f1b5e15f9342fefd60ff98af3c9163e8430d22649d862b94")),
        utxo,
        sha(utxo),
        500
      )
    )
    test(
      "opt-in complete captured seed and accepted sequence agree with full recomputation and post export"
    ) {
      val in = original; val p = prepared; val owner = Stake.owner()
      var ledger = in.ledger; var state = get(p.attach(owner, ledger)); var changed = false
      val captures =
        get(ClusterHeaderObservation.capturesBounded(dir.resolve("scala-sequence-capture.md"), 16))
      assert(captures.nonEmpty)
      captures.foreach { capture =>
        val block = get(
          SequenceInput.block(BoundedChainFollower.Original(capture.headerEnvelope, capture.block))
        )
        val candidate = get(
          Ledger.prepareBlock(ledger, block.header.hash, block.transactionMemos, block.header.slot)
        )
        val pending = get(Stake.prepare(owner, state, ledger, candidate))
        val next = get(Stake.select(owner, state, pending))
        changed ||= next.utxo != state.utxo
        ledger = get(Ledger.commitBlock(ledger, candidate)).state
        assertEquals(next.instantaneous, get(Stake.recompute(ledger.outputMap)))
        state = next
      }
      assert(changed)
      val post = read("post-ledger-state.md")
      assertEquals(
        sha(post).hex,
        "66e50acab3215a2ec061f45f79816e998d7a19bac41dac03b9bd3bbe0bd429d7"
      )
      val expected = ReferenceJson.field(
        ReferenceJson.parse(post),
        "stateBefore",
        "esLState",
        "utxoState",
        "stake",
        "credentials"
      ) match
        case ReferenceJson.Json.Obj(m) => m.map((k, v) => k -> ReferenceJson.uint(v))
        case _                         => fail("stake map")
      assertEquals(state.instantaneous.map((c, n) => c.key -> n), expected)
      assert(!p.epochTransitionReady && !p.donationsKnown && !p.rewardsDecoded)
    }
    test(
      "opt-in synthetic accepted base spend removes its final credential stake without new signatures"
    ) {
      // Rebind the existing signed transaction to a synthetic supplied UTxO with the SAME payment key.
      // Its staking credential changes; no key generation or signing, and no reference-state claim.
      val in = original; val p = prepared
      val captures =
        get(ClusterHeaderObservation.capturesBounded(dir.resolve("scala-sequence-capture.md"), 16))
      val block = captures
        .map(c =>
          get(SequenceInput.block(BoundedChainFollower.Original(c.headerEnvelope, c.block)))
        )
        .find(_.transactionMemos.nonEmpty)
        .get
      def arr(n: Node): Vector[Node] = n.value.asInstanceOf[V.Arr].value
      val body =
        arr(get(Cbor.decode(block.transactionMemos.head))).head.value.asInstanceOf[V.Map].value
      val inputNode = body.find(_._1.value == V.UInt(0)).get._2
      val refs = inputNode.value match
        case V.Tag(_, inner) => arr(inner)
        case _               => arr(inputNode)
      val target = arr(refs.head)
      val newCredential = Stake.Credential(false, Bytes(Vector.fill(28)(0xab.toByte)))
      val root = get(Cbor.decode(in.ledger.outputMap)).value.asInstanceOf[V.Map].value
      val updated = root.map { (key, out) =>
        val keyParts = arr(key)
        if keyParts.map(_.value) == target.map(_.value) then
          def replace(a: Node): Node =
            val bytes = a.value.asInstanceOf[V.ByteString].value
            val kind = (bytes.value.head & 255) >>> 4
            assert(kind == 0 || kind == 6, "synthetic fixture needs a key payment address")
            Node(
              V.ByteString(
                Bytes(Vector(0.toByte) ++ bytes.value.slice(1, 29) ++ newCredential.hash.value)
              ),
              Bytes.empty
            )
          val value = out.value match
            case V.Arr(xs) => V.Arr(xs.updated(0, replace(xs.head)))
            case V.Map(xs) =>
              V.Map(xs.map((k, v) => (k, if k.value == V.UInt(0) then replace(v) else v)))
            case _ => fail("fixture output")
          key -> Node(value, Bytes.empty)
        else key -> out
      }
      val synthetic = get(Cbor.encode(V.Map(updated)))
      val ledger = get(
        Ledger.checkpoint(in.ledger.environment, synthetic, in.ledger.fees, in.ledger.slot, in.id)
      )
      val (poolId, pool) = p.context.pools.head
      val context = get(
        Stake.context(
          p.sourceId,
          500,
          Map(newCredential -> Stake.Account(0, 0, Some(poolId))),
          Map(poolId -> pool.copy(delegators = Set(newCredential), owners = Set.empty))
        )
      )
      val owner = Stake.owner()
      val before = get(
        Stake.seed(
          owner,
          context,
          ledger,
          p.sourceId,
          get(Stake.recompute(synthetic)),
          Stake.Snapshots(Stake.emptySnapshot, Stake.emptySnapshot, Stake.emptySnapshot, 0)
        )
      )
      assert(before.instantaneous.contains(newCredential))
      val candidate = get(
        Ledger.prepareBlock(ledger, block.header.hash, block.transactionMemos, block.header.slot)
      )
      val after =
        get(Stake.select(owner, before, get(Stake.prepare(owner, before, ledger, candidate))))
      assert(!after.instantaneous.contains(newCredential))
      assert(after.instantaneous != before.instantaneous)
      assertEquals(after.instantaneous, get(Stake.recompute(candidate.outputMap)))
    }
    test("opt-in seed rejects export drift, deprecated reward alias mismatch and partial UTxO") {
      val pre = read("pre-ledger-state.md"); val text = new String(pre.toArray, "UTF-8")
      val hit = """"balance"\s*:\s*([0-9]+)""".r.findFirstMatchIn(text).get
      val changed = raw(
        text.substring(0, hit.start(1)) + (BigInt(hit.group(1)) + 1).toString + text
          .substring(hit.end(1))
      )
      assert(changed != pre, "test must change a balance")
      assert(ConwayStakeSeed.decode(changed, sha(changed), utxo, sha(utxo), 500).isLeft)
      assert(
        ConwayStakeSeed
          .decode(pre, sha(pre), get(Bytes.fromHex("a0")), sha(get(Bytes.fromHex("a0"))), 500)
          .isLeft
      )
      val p = prepared
      val in = original
      val other = get(
        Ledger.checkpoint(
          in.ledger.environment,
          in.ledger.outputMap,
          in.ledger.fees + 1,
          in.ledger.slot,
          in.id
        )
      )
      assert(p.attach(Stake.owner(), other).isLeft)
    }
  }
