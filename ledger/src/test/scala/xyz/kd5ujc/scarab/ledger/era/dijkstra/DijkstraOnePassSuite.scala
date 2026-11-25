// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream
import scala.annotation.tailrec
import scala.util.Random
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}
import xyz.kd5ujc.scarab.ledger.{CborRead, LeiosBlock}
import xyz.kd5ujc.scarab.ledger.dijkstra.{DijkstraTx as FlatTx, V12}
import xyz.kd5ujc.scarab.ledger.era.{EraTxError, TxLevel}

import DijkstraCorpus.{hex, str}

/** The era decoder that reads a Dijkstra transaction from its item in one pass
  * ([[DijkstraTx.inBlock]], [[DijkstraTx.fromSubmission]], [[DijkstraTxWits.decode]],
  * [[DijkstraTxAuxData.decode]]) against the two passes it replaced, the flat block decoder and the
  * projection of what it read ([[DijkstraTx.inBlockByFlat]] and the others, #85): on every input
  * the same value, the bytes, ids and sizes it keeps included, or the same refusal. The inputs are
  * every Dijkstra transaction the repository holds (the recorded chains, the endorser block's
  * closure, the Leios build's verdicts, the script-context fixtures and the reference's rule and
  * crossing cases), each also in its mempool form, and mutants of them: bytes cut, flipped or
  * spliced, and items of the tree duplicated, dropped, emptied, retagged, widened, given an unknown
  * key or written with an indefinite length. Written cases fix what random mutants reach by chance:
  * of competing refusals across the parts and within one, the one reported, which both ways must
  * name; every form of Plutus data a datum or a redeemer can hold, and those refused; and a fault
  * at each key of a body, a sub-transaction's body, a witness set and auxiliary data.
  */
class DijkstraOnePassSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(4000)

  private def node(raw: Bytes): Option[Node] = CborRead.decode(raw, V12.nestedLimits).toOption

  private def gunzipped(path: String): Vector[Vector[String]] =
    new String(
      new GZIPInputStream(new ByteArrayInputStream(Files.readAllBytes(Path.of(path))))
        .readAllBytes(),
      StandardCharsets.UTF_8
    ).linesIterator.map(_.split('\t').toVector).toVector

  private def blockTxs(raw: Bytes): Vector[Bytes] =
    LeiosBlock.decode(raw).toOption.toVector.flatMap(_.transactions.map(_.raw))

  /** The transactions in their block form, by source. */
  private lazy val sources: Vector[(String, Vector[Bytes])] =
    val corpus = DijkstraCorpus.leiosCorpus
    Vector(
      "testnet blocks" -> DijkstraCorpus.testnetBlocks.flatMap((_, raw) => blockTxs(raw)),
      "devnet blocks" -> DijkstraCorpus.devnetBlocks.flatMap((_, raw) => blockTxs(raw)),
      "endorser block closure" -> gunzipped("fixtures/leios/testnet/eb-1455869.tsv.gz").collect {
        case Vector("tx", _, t) => hex(t)
      },
      "Leios corpus transactions" -> corpus
        .filter(j => str(j, "kind") == "tx")
        .map(j => hex(str(j, "bytes"))),
      "Leios corpus blocks" -> corpus
        .filter(j => str(j, "kind") == "block")
        .flatMap(j => blockTxs(hex(str(j, "bytes")))),
      "script contexts" -> DijkstraCorpus.contextCases.map(c => hex(str(c, "bytes"))),
      "chain script contexts" -> DijkstraCorpus.chainContextCases.map(c => hex(str(c, "bytes"))),
      "context refusals" -> DijkstraCorpus
        .json("fixtures/reference/leios-testnet-context-refusals/cases.json")
        .hcursor
        .get[Vector[io.circe.Json]]("cases")
        .fold(e => fail(e.message), identity)
        .map(c => hex(str(c, "bytes"))),
      "UTXOW cases" -> DijkstraCorpus.utxowCases.map(j => hex(str(j, "tx"))).distinct,
      "ENTITIES cases" -> DijkstraCorpus
        .jsonLines("fixtures/reference/dijkstra-rules/cases.jsonl")
        .filter(j => str(j, "kind") == "entities")
        .map(j => hex(str(j, "tx")))
        .distinct,
      "BBODY cases" -> DijkstraCorpus
        .jsonLines("fixtures/reference/dijkstra-rules/cases.jsonl")
        .filter(j => str(j, "kind") == "bbody")
        .flatMap(j => blockTxs(hex(str(j, "block"))))
    )

  /** The mempool forms, by source: the crossing corpus's and the devnet's submitted transaction. */
  private lazy val submitted: Vector[(String, Vector[Bytes])] = Vector(
    "crossing corpus" -> DijkstraCorpus.crossingForms,
    "devnet submission" -> Vector(DijkstraCorpus.devnetSubmitted)
  )

  /** The mempool form `[body, witnesses, auxiliary data]` of a transaction's block form, its flag
    * dropped, when it is a definite list of four.
    */
  private def mempoolForm(raw: Bytes): Option[Bytes] =
    node(raw).flatMap(_.value match
      case Value.Arr(xs) if xs.sizeIs == 4 && (raw.byteOr(0)(0)(_ & 0xff) == 0x84) =>
        Some(Bytes.wrap(Array(0x83.toByte)) ++ xs(0).original ++ xs(1).original ++ xs(2).original)
      case _ => None)

  /** What the two ways gave, for the tallies. */
  private enum Outcome derives CanEqual:
    case Accepted, Refused, ProjectionRefused, NotCbor

  // the projection refused what the flat decoder accepted: the one case where the order of the
  // two passes' refusals could matter
  private def projectionRefused(n: Node, old: Either[EraTxError, ?]): Boolean =
    FlatTx.inBlock(n).isRight && old.isLeft

  /** The two ways on a transaction's item in its block form, and on its witness set and auxiliary
    * data when it has them; the outcome of the transaction.
    */
  private def sameInBlock(n: Node, what: String): Outcome =
    val one = DijkstraTx.inBlock(n)
    val two = DijkstraTx.inBlockByFlat(n)
    assertEquals(one, two, what)
    one.foreach(t => sameKept(t, two, what))
    n.value match
      case Value.Arr(xs) if xs.sizeIs >= 3 =>
        assertEquals(
          DijkstraTxWits.decode(xs(1)),
          DijkstraTx.witsByFlat(xs(1)),
          s"$what: witnesses"
        )
        if xs(2).value != Value.Null then
          assertEquals(
            DijkstraTxAuxData.decode(xs(2)),
            DijkstraTx.auxDataByFlat(xs(2)),
            s"$what: auxiliary data"
          )
      case _ => ()
    if one.isRight then Outcome.Accepted
    else if projectionRefused(n, two) then Outcome.ProjectionRefused
    else Outcome.Refused

  /** What equality leaves to the parts' own: the bytes of the block form, the size for the fee, the
    * id of the body and of each sub-transaction, and the trees of the native scripts, whose
    * equality is by their bytes' hash.
    */
  private def sameKept(
      t: DijkstraTx[TxLevel.Top],
      other: Either[EraTxError, DijkstraTx[TxLevel.Top]],
      what: String
  ): Unit =
    other.foreach { o =>
      assertEquals(DijkstraTx.forBlockInclusion(t), DijkstraTx.forBlockInclusion(o), what)
      assertEquals(t.sizeTxF, o.sizeTxF, what)
      assertEquals(t.id, o.id, what)
      val subs = DijkstraTxBody.topBody(t.body).subTransactions.toVector
      val otherSubs = DijkstraTxBody.topBody(o.body).subTransactions.toVector
      assertEquals(subs.map(_.id), otherSubs.map(_.id), what)
      def trees(x: DijkstraTx[?]) =
        (x.wits.scripts.values.toVector.flatMap(DijkstraScript.instance.getNativeScript) ++
          x.auxData.toVector.flatMap(_.nativeScripts)).map(s => (s.raw, s.timelock))
      assertEquals(trees(t), trees(o), what)
      assertEquals(subs.map(trees), otherSubs.map(trees), what)
    }

  /** The two ways on the bytes of a transaction in its mempool form. */
  private def sameSubmitted(raw: Bytes, what: String): Outcome =
    val one = DijkstraTx.fromSubmission(raw)
    val two = DijkstraTx.fromSubmissionByFlat(raw)
    assertEquals(one, two, what)
    one.foreach(t => sameKept(t, two, what))
    if one.isRight then Outcome.Accepted
    else if FlatTx.fromSubmission(raw).isRight then Outcome.ProjectionRefused
    else Outcome.Refused

  private def tally(outcomes: Vector[Outcome]): Map[Outcome, Int] =
    outcomes.groupBy(identity).view.mapValues(_.size).toMap

  test("every recorded Dijkstra transaction reads the same in one pass as in two") {
    val counts = sources.map { (name, txs) =>
      val outcomes = txs.zipWithIndex.map((raw, i) =>
        node(raw).fold(Outcome.NotCbor)(n => sameInBlock(n, s"$name $i"))
      )
      val mempool = txs.zipWithIndex.flatMap((raw, i) =>
        mempoolForm(raw).map(sameSubmitted(_, s"$name $i, mempool form"))
      )
      name -> (tally(outcomes), tally(mempool))
    }.toMap
    val forms = submitted
      .map((name, txs) =>
        name -> tally(txs.zipWithIndex.map((raw, i) => sameSubmitted(raw, s"$name $i")))
      )
      .toMap
    import Outcome.*
    // the recorded chains and the closure, every one accepted
    assertEquals(counts("testnet blocks")._1, Map(Accepted -> 40))
    assertEquals(counts("devnet blocks")._1, Map(Accepted -> 1))
    assertEquals(counts("endorser block closure"), (Map(Accepted -> 323), Map(Accepted -> 323)))
    // the Leios build's verdicts: the 273 transactions it accepts and the 263 it refuses, five of
    // them not one item of CBOR
    assertEquals(
      counts("Leios corpus transactions")._1,
      Map(Accepted -> 273, Refused -> 258, NotCbor -> 5)
    )
    assertEquals(counts("Leios corpus blocks")._1, Map(Accepted -> 63))
    assertEquals(counts("script contexts")._1, Map(Accepted -> 11))
    assertEquals(counts("chain script contexts")._1, Map(Accepted -> 200))
    assertEquals(counts("context refusals")._1, Map(Accepted -> 22))
    // the 11 distinct UTXOW transactions, and the collateral return to a pointer address of the
    // cases of 2026-11-24 (#1420); their 13 distinct ENTITIES transactions
    assertEquals(counts("UTXOW cases")._1, Map(Accepted -> (11 + 1)))
    assertEquals(counts("ENTITIES cases")._1, Map(Accepted -> 13))
    assert(counts("BBODY cases")._1.getOrElse(Accepted, 0) > 0, counts("BBODY cases").toString)
    assertEquals(
      forms,
      Map("crossing corpus" -> Map(Accepted -> 149), "devnet submission" -> Map(Accepted -> 1))
    )
    // no refusal of the projection alone among them
    assert(
      !counts.values.exists((a, b) =>
        a.contains(ProjectionRefused) || b.contains(ProjectionRefused)
      ),
      counts.toString
    )
  }

  /** What a transaction read from its item keeps of it: the id is the hash of the body's item, and
    * a block form written as a definite list of four is the transaction's block inclusion, its size
    * for the fee the bytes but the flag's.
    */
  private def kept(t: DijkstraTx[TxLevel.Top], n: Node, what: String): Unit =
    n.value match
      case Value.Arr(xs) if xs.nonEmpty =>
        assertEquals(t.id, xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(xs(0).original), what)
        if n.original.byteOr(0)(0)(_ & 0xff) == 0x84 then
          assertEquals(DijkstraTx.forBlockInclusion(t), n.original, what)
          assertEquals(t.sizeTxF, n.original.size - 1L, what)
      case _ => fail(s"$what: read from no list")

  test("every recorded Dijkstra transaction reads as recorded") {
    import DijkstraOnePassExpected.{corpus, corpusMempool, corpusNoMempoolForm, corpusNotCbor}
    // every transaction but the Leios build's refused ones is accepted in both forms, and keeps
    // its item's bytes; the refused ones are refused as recorded, each form
    sources.foreach { (name, txs) =>
      val recorded = name == "Leios corpus transactions"
      txs.zipWithIndex.foreach { (raw, i) =>
        val what = s"$name $i"
        node(raw) match
          case None => assert(recorded && corpusNotCbor(i), s"$what: not CBOR")
          case Some(n) =>
            assert(!(recorded && corpusNotCbor(i)), what)
            val read = DijkstraTx.inBlock(n)
            assertEquals(read.left.toOption, Option.when(recorded)(corpus.get(i)).flatten, what)
            read.foreach(kept(_, n, what))
        mempoolForm(raw) match
          case None => assert(!recorded || corpusNoMempoolForm(i), s"$what: no mempool form")
          case Some(m) =>
            assert(!(recorded && corpusNoMempoolForm(i)), what)
            assertEquals(
              DijkstraTx.fromSubmission(m).left.toOption,
              Option.when(recorded)(corpusMempool.get(i).orElse(corpus.get(i))).flatten,
              s"$what, mempool form"
            )
      }
    }
    submitted.foreach((name, txs) =>
      txs.zipWithIndex.foreach((raw, i) =>
        assert(DijkstraTx.fromSubmission(raw).isRight, s"$name $i")
      )
    )
    // the 258 refusals of the corpus, its five items that are not CBOR, and its seven without a
    // mempool form: the five, a block form of three items and a list of four that is indefinite
    assertEquals((corpus.size, corpusNotCbor.size, corpusNoMempoolForm.size), (258, 5, 7))
  }

  // ---- mutants

  /** The items of a decoded transaction with where each begins in its bytes and the part it is in:
    * the transaction's item and the key of its body's, witness set's or auxiliary data's map under
    * which it lies. Walked on a list of the items still to visit, so the depth is the heap's.
    */
  private def items(root: Node): Vector[(Node, Int, String)] =
    val base = root.original.unsafeOffset
    def children(n: Node): Vector[Node] = n.value match
      case Value.Arr(xs)    => xs
      case Value.Map(kvs)   => kvs.flatMap((k, v) => Vector(k, v))
      case Value.Tag(_, in) => Vector(in)
      case _                => Vector.empty
    def label(path: List[String]): String = path.reverse.take(2).mkString(":")
    @tailrec def go(
        todo: List[(Node, List[String])],
        out: Vector[(Node, Int, String)]
    ): Vector[(Node, Int, String)] =
      todo match
        case Nil => out
        case (n, path) :: rest =>
          val here = (n, n.original.unsafeOffset - base, label(path))
          val kids = n.value match
            case Value.Map(kvs) if path.sizeIs == 1 =>
              kvs.toList.flatMap((k, v) =>
                val key = k.value match
                  case Value.UInt(u) => u.toString
                  case _             => "?"
                List((k, key :: path), (v, key :: path))
              )
            case Value.Arr(xs) if path.isEmpty =>
              xs.zipWithIndex.toList.map((x, i) => (x, s"part$i" :: path))
            case _ => children(n).toList.map((_, path))
          go(kids ++ rest, out :+ here)
    go(List((root, Nil)), Vector.empty)

  private def head(major: Int, count: Long): Bytes =
    val m = major << 5
    def be(n: Int): Array[Byte] = Array.tabulate(n)(i => (count >>> (8 * (n - 1 - i))).toByte)
    Bytes.wrap(
      if count < 24 then Array((m | count.toInt).toByte)
      else if count < 0x100 then Array((m | 24).toByte) ++ be(1)
      else if count < 0x10000 then Array((m | 25).toByte) ++ be(2)
      else if count < 0x100000000L then Array((m | 26).toByte) ++ be(4)
      else Array((m | 27).toByte) ++ be(8)
    )

  private def concat(xs: Vector[Bytes]): Bytes = xs.foldLeft(Bytes.empty)(_ ++ _)

  /** The item `n` written again by one structural mutation, none when it does not apply. */
  private def rewrite(n: Node, kind: Int, rnd: Random): Option[Bytes] =
    def pick[A](xs: Vector[A]): A = xs(rnd.nextInt(xs.size))
    n.value match
      case Value.Map(kvs) if kvs.nonEmpty =>
        val entries = kvs.map((k, v) => k.original ++ v.original)
        kind % 6 match
          case 0 => // a key given twice, with its own value or another entry's
            val e =
              if rnd.nextBoolean() then pick(entries)
              else kvs(rnd.nextInt(kvs.size))._1.original ++ kvs(rnd.nextInt(kvs.size))._2.original
            Some(head(5, entries.size + 1L) ++ concat(entries) ++ e)
          case 1 => // an entry dropped
            val i = rnd.nextInt(entries.size)
            Some(head(5, entries.size - 1L) ++ concat(entries.patch(i, Nil, 1)))
          case 2 => // an unknown key
            val k = pick(Vector(6L, 10L, 12L, 28L, 99L, 1L << 33))
            Some(head(5, entries.size + 1L) ++ concat(entries) ++ head(0, k) ++ hex("00"))
          case 3 => // written with an indefinite length
            Some(
              Bytes.wrap(Array(0xbf.toByte)) ++ concat(entries) ++ Bytes.wrap(Array(0xff.toByte))
            )
          case 4 => Some(Bytes.wrap(Array(0xa0.toByte))) // emptied
          case _ => // its length written in eight bytes
            Some(
              Bytes.wrap(Array(0xbb.toByte)) ++ Bytes.wrap(Array.fill(7)(0.toByte)) ++
                Bytes.wrap(Array(entries.size.toByte)) ++ concat(entries)
            )
      case Value.Arr(xs) if xs.nonEmpty =>
        val els = xs.map(_.original)
        kind % 6 match
          case 0 => Some(head(4, els.size + 1L) ++ concat(els) ++ pick(els)) // an element twice
          case 1 =>
            val i = rnd.nextInt(els.size)
            Some(head(4, els.size - 1L) ++ concat(els.patch(i, Nil, 1)))
          case 2 => // a set tag over it, or a tag that is not one
            Some(
              Bytes.wrap(Array(0xd9.toByte, 0x01.toByte, pick(Vector(0x02, 0x03)).toByte)) ++
                n.original
            )
          case 3 =>
            Some(Bytes.wrap(Array(0x9f.toByte)) ++ concat(els) ++ Bytes.wrap(Array(0xff.toByte)))
          case 4 => Some(Bytes.wrap(Array(0x80.toByte)))
          case _ =>
            Some(head(4, els.size + 1L) ++ concat(els) ++ concat(els.take(1))) // first twice
      case Value.Tag(t, inner) =>
        kind % 3 match
          case 0 => Some(inner.original) // untagged
          case 1 => Some(head(6, (t + 1).toLong) ++ inner.original)
          case _ => Some(head(6, 258) ++ head(4, 2) ++ inner.original ++ inner.original)
      case Value.UInt(v) =>
        kind % 4 match
          case 0 => Some(head(0, 0))
          case 1 => Some(Bytes.wrap(Array(0x1b.toByte) ++ Array.fill(8)(0xff.toByte)))
          case 2 => // in a head of eight bytes
            Some(
              Bytes.wrap(0x1b.toByte +: Array.tabulate(8)(i => (v.toLong >>> (8 * (7 - i))).toByte))
            )
          case _ => Some(head(1, 0)) // negative
      case Value.Text(t) =>
        val utf8 = Bytes.wrap(t.getBytes(StandardCharsets.UTF_8))
        kind % 3 match
          case 0 => // chunked, in two chunks
            val cut = utf8.size / 2
            Some(
              Bytes.wrap(Array(0x7f.toByte)) ++ head(3, cut.toLong) ++ utf8.take(cut) ++
                head(3, (utf8.size - cut).toLong) ++ utf8.drop(cut) ++ Bytes.wrap(
                  Array(0xff.toByte)
                )
            )
          case 1 => // past 128 bytes
            val long = Bytes.wrap(Array.fill(129)(0x61.toByte))
            Some(head(3, long.size.toLong) ++ long)
          case _ => Some(head(3, 0)) // empty
      case Value.ByteString(b) =>
        kind % 4 match
          case 0 => Some(head(2, b.size + 1L) ++ b ++ Bytes.wrap(Array(0.toByte))) // one byte more
          case 1 => Some(head(2, (b.size - 1).max(0).toLong) ++ b.take(b.size - 1))
          case 2 => Some(Bytes.wrap(Array(0x40.toByte))) // empty
          case _ => // chunked
            Some(
              Bytes.wrap(Array(0x5f.toByte)) ++ head(2, b.size.toLong) ++ b ++ Bytes.wrap(
                Array(0xff.toByte)
              )
            )
      case _ =>
        Some(hex(pick(Vector("f6", "f5", "00", "80", "a0", "40", "60"))))

  /** One mutant of `raw` by `seed`: a structural one of an item of its tree, or a byte-level one.
    */
  private def mutant(raw: Bytes, seed: Long): (Bytes, String) =
    val rnd = new Random(seed)
    def bytes(f: Array[Byte] => Array[Byte]) = Bytes.wrap(f(raw.toArray))
    rnd.nextInt(10) match
      case 0 => (raw.take(rnd.nextInt(raw.size + 1)), "truncated")
      case 1 =>
        (
          bytes { a =>
            val i = rnd.nextInt(a.length); a.updated(i, (a(i) ^ (1 << rnd.nextInt(8))).toByte)
          },
          "bit flipped"
        )
      case 2 =>
        val from = rnd.nextInt(raw.size); val len = 1 + rnd.nextInt(8.min(raw.size - from))
        (raw.take(from) ++ raw.drop(from + len), "range deleted")
      case _ =>
        node(raw).fold((raw, "not cbor")) { root =>
          val all = items(root).drop(1)
          val (n, at, part) = all(rnd.nextInt(all.size))
          rewrite(n, rnd.nextInt(1 << 20), rnd).fold((raw, "unchanged"))(item =>
            (raw.take(at) ++ item ++ raw.drop(at + n.original.size), part)
          )
        }

  private lazy val seeds: Vector[Bytes] =
    (sources.flatMap(_._2) ++ submitted.flatMap(_._2)).distinct

  private val mutants: Gen[(Bytes, String)] =
    for
      i <- Gen.choose(0, Int.MaxValue)
      seed <- Gen.long
    yield mutant(seeds(i % seeds.size), seed)

  property("a mutated transaction reads the same in one pass as in two, or is refused the same") {
    // and the projection never refuses what the flat decoder accepts, on which the order of the
    // one pass's refusals rests: the two passes found every refusal of the flat decoder first
    Prop.forAll(mutants) { (raw, what) =>
      val outcomes = node(raw).map(sameInBlock(_, what)).toVector ++
        Vector(sameSubmitted(raw, s"$what, submitted")) ++
        mempoolForm(raw).map(sameSubmitted(_, s"$what, mempool form")).toVector
      Prop(!outcomes.contains(Outcome.ProjectionRefused)) :| s"$what: the projection refused"
    }
  }

  test("the mutants reach every part of a transaction and both outcomes") {
    // a fixed set of mutants, tallied: which parts and keys they changed, and what they gave
    val read = (0 until 6000).map { i =>
      val (raw, part) = mutant(seeds(i % seeds.size), i.toLong * 7919L)
      (part, node(raw).fold(Outcome.NotCbor)(n => sameInBlock(n, s"mutant $i ($part)")))
    }
    val outcomes = tally(read.map(_._2).toVector)
    val parts = read.map(_._1).toSet
    // as recorded: the outcomes, and the distinct refusals among them
    assertEquals(
      outcomes,
      Map(Outcome.Accepted -> 1589, Outcome.Refused -> 3185, Outcome.NotCbor -> 1226)
    )
    assertEquals(
      read.indices
        .flatMap(i =>
          node(mutant(seeds(i % seeds.size), i.toLong * 7919L)._1)
            .flatMap(DijkstraTx.inBlock(_).left.toOption)
        )
        .distinct
        .size,
      597
    )
    assert(outcomes.getOrElse(Outcome.Accepted, 0) > 100, outcomes.toString)
    assert(outcomes.getOrElse(Outcome.Refused, 0) > 1000, outcomes.toString)
    assert(!outcomes.contains(Outcome.ProjectionRefused), outcomes.toString)
    Vector("part0", "part1", "part2", "part3").foreach(p =>
      assert(parts.exists(_.startsWith(p)), s"no mutant of $p: $parts")
    )
    // body keys, witness keys and auxiliary data keys among them
    Vector("part0:0", "part0:1", "part0:2", "part1:0", "part2:0").foreach(k =>
      assert(parts.contains(k), s"no mutant under $k: $parts")
    )
  }

  test(
    "every text written in chunks and every redeemer purpose given twice reads the same both ways"
  ) {
    // the two kinds of mutant the projection's own readers could refuse otherwise: a text read
    // again by another reader (a pool's metadata URL, `PoolMetadata.read`), and redeemers of one
    // purpose with other data and units, of which the last read is kept
    def chunked(t: String): Bytes =
      val utf8 = Bytes.wrap(t.getBytes(StandardCharsets.UTF_8))
      val cut = utf8.size / 2
      Bytes.wrap(Array(0x7f.toByte)) ++ head(3, cut.toLong) ++ utf8.take(cut) ++
        head(3, (utf8.size - cut).toLong) ++ utf8.drop(cut) ++ Bytes.wrap(Array(0xff.toByte))
    def twice(kvs: Vector[(Node, Node)]): Bytes =
      val (k, _) = kvs.head
      val other = Bytes.wrap(Array(0x82.toByte, 0x00.toByte, 0x82.toByte, 0x00.toByte, 0x00.toByte))
      head(5, kvs.size + 1L) ++ concat(kvs.map((a, b) => a.original ++ b.original)) ++
        k.original ++ other
    val mutants = seeds.flatMap(raw =>
      node(raw).toVector.flatMap(root =>
        items(root).flatMap { (n, at, part) =>
          val item = n.value match
            case Value.Text(t) => Some("text" -> chunked(t))
            case Value.Map(kvs) if part == "part1:5" && kvs.nonEmpty && n.original.size > 2 =>
              Some("redeemers" -> twice(kvs))
            case _ => None
          item.map((kind, b) => kind -> (raw.take(at) ++ b ++ raw.drop(at + n.original.size)))
        }
      )
    )
    val read = mutants.zipWithIndex.map { case ((kind, raw), i) =>
      kind -> node(raw).fold(Outcome.NotCbor)(n => sameInBlock(n, s"$kind mutant $i"))
    }
    val byKind = read.groupBy(_._1).view.mapValues(rs => tally(rs.map(_._2))).toMap
    // as recorded
    assertEquals(
      byKind,
      Map(
        "redeemers" -> Map(Outcome.Accepted -> 233, Outcome.Refused -> 20),
        "text" -> Map(Outcome.Accepted -> 66, Outcome.Refused -> 176)
      )
    )
    assert(byKind.get("text").exists(_.getOrElse(Outcome.Accepted, 0) > 0), byKind.toString)
    assert(byKind.get("redeemers").exists(_.values.sum > 0), byKind.toString)
    assert(!read.exists(_._2 == Outcome.ProjectionRefused), byKind.toString)
  }

  // ---- deterministic cases, written by hand

  private def u(n: Long): Bytes = head(0, n)
  private def arr(xs: Bytes*): Bytes = head(4, xs.size.toLong) ++ concat(xs.toVector)
  private def mp(kvs: (Bytes, Bytes)*): Bytes =
    head(5, kvs.size.toLong) ++ concat(kvs.toVector.flatMap((k, v) => Vector(k, v)))
  private def tag(t: Long, x: Bytes): Bytes = head(6, t) ++ x
  private val nul = hex("f6")
  private val yes = hex("f5")

  /** A body with the keys a top-level body requires, its inputs and outputs empty, and `extra`. */
  private def body(extra: (Int, Bytes)*): Bytes =
    val fields = (Map(0 -> arr(), 1 -> arr(), 2 -> u(0)) ++ extra).toVector.sortBy(_._1)
    mp(fields.map((k, v) => u(k.toLong) -> v)*)

  /** A sub-transaction whose body has the keys a sub-transaction's body requires and `extra`. */
  private def subTx(extra: (Int, Bytes)*): Bytes =
    val fields = (Map(0 -> arr(), 1 -> arr()) ++ extra).toVector.sortBy(_._1)
    arr(mp(fields.map((k, v) => u(k.toLong) -> v)*), mp(), nul)

  /** Both forms of a transaction, the block's and the mempool's, read both ways: the same result
    * each way in each form, the two forms' the same; that result.
    */
  private def bothForms(
      b: Bytes,
      w: Bytes,
      aux: Bytes,
      what: String
  ): Either[EraTxError, DijkstraTx[TxLevel.Top]] =
    val block = arr(b, w, aux, yes)
    val n = node(block).getOrElse(fail(s"$what: not CBOR"))
    sameInBlock(n, what)
    sameSubmitted(arr(b, w, aux), s"$what, mempool form")
    val read = DijkstraTx.inBlock(n)
    assertEquals(DijkstraTx.fromSubmission(arr(b, w, aux)).map(_.id), read.map(_.id), what)
    read

  test(
    "of competing refusals in a transaction, the first is reported, as the two passes report it"
  ) {
    import xyz.kd5ujc.scarab.ledger.{DecodeField, LedgerDecodeError}
    import LedgerDecodeError.{Expected, Shape, UnknownKey}
    val zero = Value.UInt(BigInt(0))
    def refused(e: LedgerDecodeError) = Left(EraTxError.Decode(e))
    val inputs = Expected(DecodeField.Inputs, Shape.Array, zero)
    val vkeys = Expected(DecodeField.VkeyWitnesses, Shape.Array, zero)
    // the first case is `84 a3 00 00 01 80 02 01 a1 00 00 f6 f5`; a key a level does not have is
    // refused before any field, wherever it is written; the keys are read in the decoder's order,
    // not as written
    val cases = Vector(
      ("body key 0 and witness key 0", hex("a3000001800201"), hex("a10000"), nul, inputs),
      ("a body and auxiliary data", body(0 -> u(0)), mp(), u(0), inputs),
      (
        "a witness set and auxiliary data",
        body(),
        mp(u(0) -> u(0)),
        u(0),
        vkeys
      ),
      (
        "a sub-transaction's body (key 23) and the top body's key 24",
        body(23 -> arr(subTx(0 -> u(0))), 24 -> u(0)),
        mp(),
        nul,
        inputs
      ),
      (
        "the top body's key 5 and a sub-transaction's body (key 23)",
        body(5 -> u(0), 23 -> arr(subTx(0 -> u(0)))),
        mp(),
        nul,
        Expected(DecodeField.Withdrawals, Shape.Map, zero)
      ),
      ("a body's keys 0 and 5", body(0 -> u(0), 5 -> u(0)), mp(), nul, inputs),
      (
        "a body's key 0 and an unknown key 6",
        body(0 -> u(0), 6 -> u(0)),
        mp(),
        nul,
        UnknownKey(DecodeField.TransactionBody, BigInt(6))
      ),
      (
        "a sub-transaction's body with a key of the top level only (2)",
        body(23 -> arr(subTx(2 -> u(0), 0 -> u(0)))),
        mp(),
        nul,
        UnknownKey(DecodeField.TransactionBody, BigInt(2))
      ),
      ("a witness set's keys 0 and 4", body(), mp(u(0) -> u(0), u(4) -> u(0)), nul, vkeys),
      (
        "a witness set's keys 5 and 1, written in that order",
        body(),
        mp(u(5) -> u(0), u(1) -> u(0)),
        nul,
        Expected(DecodeField.NativeScripts, Shape.Array, zero)
      ),
      (
        "auxiliary data's keys 1 and 0, written in that order",
        body(),
        mp(),
        tag(259, mp(u(1) -> u(0), u(0) -> u(0))),
        Expected(DecodeField.Metadata, Shape.Map, zero)
      )
    )
    cases.foreach((what, b, w, aux, expected) =>
      assertEquals(bothForms(b, w, aux, what), refused(expected), what)
    )
  }

  test("Plutus data of every form, in datums and redeemers, reads the same both ways") {
    import xyz.kd5ujc.scarab.plutus.PlutusData
    import PlutusData.{B, Constr, I, List as L, Map as M}
    val two64 = BigInt(1) << 64
    val sixtyFour = Bytes.wrap(Array.tabulate(64)(i => i.toByte))
    val accepted: Vector[(String, PlutusData)] = Vector(
      "d86682188080" -> Constr(128, Vector.empty),
      "d866821903e880" -> Constr(1000, Vector.empty),
      "1bffffffffffffffff" -> I(two64 - 1),
      "3bffffffffffffffff" -> I(-two64),
      "c248ffffffffffffffff" -> I(two64 - 1),
      "c249010000000000000000" -> I(two64),
      "c348ffffffffffffffff" -> I(-two64),
      "c349010000000000000000" -> I(-1 - two64),
      s"5840${sixtyFour.hex}" -> B(sixtyFour),
      s"5f5840${sixtyFour.hex}4101ff" -> B(sixtyFour ++ hex("01")),
      "5fff" -> B(Bytes.empty),
      "8201820203" -> L(Vector(I(1), L(Vector(I(2), I(3))))),
      "a1a1010281a0" -> M(Vector(M(Vector(I(1) -> I(2))) -> L(Vector(M(Vector.empty))))),
      "9f01a0ff" -> L(Vector(I(1), M(Vector.empty))),
      "d87982a1010280" -> Constr(0, Vector(M(Vector(I(1) -> I(2))), L(Vector.empty)))
    ) ++ (121 to 127).map(t =>
      tag(t.toLong, arr(u(t.toLong))).hex -> Constr(t - 121, Vector(I(t)))
    ) ++
      Vector(1280, 1281, 1350, 1400).map(t =>
        tag(t.toLong, arr(u(t.toLong))).hex -> Constr(t - 1280 + 7, Vector(I(t)))
      )
    val data = accepted.map((h, d) => hex(h) -> d)
    val wits = mp(
      u(4) -> arr(data.map(_._1)*),
      u(5) -> mp(
        data.zipWithIndex.map((d, i) => arr(u(0), u(i.toLong)) -> arr(d._1, arr(u(1), u(2))))*
      )
    )
    val tx = bothForms(body(), wits, nul, "every form of data").getOrElse(fail("refused"))
    val datums = tx.wits.datums.datums.values.toVector
    val redeemers = tx.wits.redeemers.entries.values.toVector
    assertEquals(datums.size, data.size)
    assertEquals(redeemers.size, data.size)
    data.foreach { (raw, expected) =>
      val held = datums.filter(_.originalBytes == raw)
      assertEquals(held.map(_.getPlutusData), Vector(expected), raw.hex)
      assertEquals(
        redeemers.filter(_._1.originalBytes == raw).map(_._1.getPlutusData),
        Vector(expected),
        raw.hex
      )
    }
    // and the forms the decoder refuses: a chunk of more than 64 bytes, a big number's wide head,
    // a constructor tag out of the two ranges, each as a datum and as a redeemer's data
    import xyz.kd5ujc.scarab.ledger.{DecodeField, LedgerDecodeError}
    val sixtyFive = sixtyFour ++ hex("01")
    val refused = Vector(
      s"5841${sixtyFive.hex}" -> PlutusData.DecodeError.ByteStringChunkLimit,
      s"5f5841${sixtyFive.hex}ff" -> PlutusData.DecodeError.ByteStringChunkLimit,
      "d80248ffffffffffffffff" -> PlutusData.DecodeError.BigNumberWideHead(BigInt(2)),
      "d87880" -> PlutusData.DecodeError.UnexpectedTag(BigInt(120)),
      "d88080" -> PlutusData.DecodeError.UnexpectedTag(BigInt(128)),
      "d904ff80" -> PlutusData.DecodeError.UnexpectedTag(BigInt(1279)),
      "d9057980" -> PlutusData.DecodeError.UnexpectedTag(BigInt(1401))
    )
    refused.foreach { (h, e) =>
      assertEquals(
        bothForms(body(), mp(u(4) -> arr(hex(h))), nul, s"datum $h"),
        Left(EraTxError.Decode(LedgerDecodeError.PlutusData(DecodeField.Datums, e))),
        h
      )
      assertEquals(
        bothForms(
          body(),
          mp(u(5) -> mp(arr(u(0), u(0)) -> arr(hex(h), arr(u(1), u(2))))),
          nul,
          s"redeemer $h"
        ),
        Left(EraTxError.Decode(LedgerDecodeError.PlutusData(DecodeField.Redeemer, e))),
        h
      )
    }
  }

  test(
    "a fault in each key of a body, a sub-transaction's body, a witness set and auxiliary data"
  ) {
    // each key given each of these values in turn, alone: one is no field's form (a text), the
    // others some fields' forms and not others'
    val values =
      Vector("60", "00", "80", "a0", "f6", "1bffffffffffffffff", "d9010280", "4100", "8100", "8140")
        .map(hex)
    // the verdicts recorded of each case
    val expected = Map(
      "body" -> DijkstraOnePassExpected.body,
      "sub-transaction's body" -> DijkstraOnePassExpected.subBody,
      "witness set" -> DijkstraOnePassExpected.wits,
      "auxiliary data" -> DijkstraOnePassExpected.aux
    )
    def table(
        what: String,
        keys: Range,
        make: (Int, Bytes) => (Bytes, Bytes, Bytes)
    ): Vector[(Int, Bytes, Either[EraTxError, DijkstraTx[TxLevel.Top]])] =
      for
        k <- keys.toVector
        v <- values
      yield
        val (b, w, aux) = make(k, v)
        val read = bothForms(b, w, aux, s"$what key $k = ${v.hex}")
        assertEquals(read.left.toOption, expected(what)((k, v.hex)), s"$what key $k = ${v.hex}")
        (k, v, read)
    val tables = Vector(
      "body" -> table("body", 0 to 28, (k, v) => (body(k -> v), mp(), nul)),
      "sub-transaction's body" ->
        table(
          "sub-transaction's body",
          0 to 28,
          (k, v) => (body(23 -> arr(subTx(k -> v))), mp(), nul)
        ),
      "witness set" -> table("witness set", 0 to 9, (k, v) => (body(), mp(u(k.toLong) -> v), nul)),
      "auxiliary data" ->
        table("auxiliary data", 0 to 6, (k, v) => (body(), mp(), tag(259, mp(u(k.toLong) -> v))))
    )
    // a text is no field's form: refused at every key, each key with its field's own refusal but
    // where the decoder reads two keys at one site: a top-level body's balance intervals (26 and
    // 27), the witness set's Plutus scripts (3, 6, 7 and 8) and the auxiliary data's (2 to 5)
    val sites = Map(
      "body" -> 28,
      "sub-transaction's body" -> 29,
      "witness set" -> 7,
      "auxiliary data" -> 4
    )
    tables.foreach { (what, rows) =>
      val texts = rows.filter(_._2 == hex("60"))
      assert(texts.forall(_._3.isLeft), s"$what: a text accepted")
      assertEquals(texts.map(_._3).distinct.size, sites(what), s"$what: the refusals of a text")
    }
    val counts = tables.map((what, rows) =>
      what -> tally(rows.map(r => if r._3.isRight then Outcome.Accepted else Outcome.Refused))
    )
    // both outcomes in every part, and every case recorded
    counts.foreach((what, c) => assert(c.size == 2, s"$what: $c"))
    assertEquals(
      counts.toMap,
      Map(
        "body" -> Map(Outcome.Accepted -> 17, Outcome.Refused -> 273),
        "sub-transaction's body" -> Map(Outcome.Accepted -> 13, Outcome.Refused -> 277),
        "witness set" -> Map(Outcome.Accepted -> 6, Outcome.Refused -> 94),
        "auxiliary data" -> Map(Outcome.Accepted -> 10, Outcome.Refused -> 60)
      )
    )
    assertEquals(expected.values.map(_.size).sum, 750)
  }
