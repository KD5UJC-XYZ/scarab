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
  * key or written with an indefinite length.
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
    assertEquals(counts("UTXOW cases")._1, Map(Accepted -> 11))
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
          case 0 => // a key given twice
            val e = pick(entries)
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
    Prop.forAll(mutants) { (raw, what) =>
      node(raw).foreach(n => sameInBlock(n, what))
      sameSubmitted(raw, s"$what, submitted")
      mempoolForm(raw).foreach(m => sameSubmitted(m, s"$what, mempool form"))
      true
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
    assert(outcomes.getOrElse(Outcome.Accepted, 0) > 100, outcomes.toString)
    assert(outcomes.getOrElse(Outcome.Refused, 0) > 1000, outcomes.toString)
    Vector("part0", "part1", "part2", "part3").foreach(p =>
      assert(parts.exists(_.startsWith(p)), s"no mutant of $p: $parts")
    )
    // body keys, witness keys and auxiliary data keys among them
    Vector("part0:0", "part0:1", "part0:2", "part1:0", "part2:0").foreach(k =>
      assert(parts.contains(k), s"no mutant under $k: $parts")
    )
  }
