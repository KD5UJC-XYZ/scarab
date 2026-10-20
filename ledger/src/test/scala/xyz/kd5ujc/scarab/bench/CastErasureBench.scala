// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.bench

import java.lang.management.ManagementFactory
import java.security.{KeyPairGenerator, SecureRandom}
import java.time.Instant
import scala.collection.immutable.{SortedMap, TreeMap}
import cats.{Eq, Order, Show}
import cats.derived.*
import cats.syntax.show.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational}
import xyz.kd5ujc.scarab.ledger.{
  EpochGeometry,
  LedgerGlobals,
  PlutusStats,
  ProtocolParams,
  TestLedger,
  Tx,
  TxIn,
  Utxo
}
import xyz.kd5ujc.scarab.ledger.TestLedger.*
import xyz.kd5ujc.scarab.ledger.rules.{
  Bbody,
  BbodyEnv,
  BbodySignal,
  BbodyState,
  BodyCommitment,
  Counted,
  Globals as RuleGlobals,
  Sts
}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.ExUnits

/** Development tool for the cast-erasure batch (#202–#207): the same workloads, run against the old
  * and the new code, timed and with the thread's allocation counted.
  *
  * `ledger/Test/runMain xyz.kd5ujc.scarab.bench.CastErasureBench [credentials|decode|diff|rules|all] [n]`
  *
  * Inputs are deterministic and asserted so outside the timed region (distinct credentials, one
  * fixed signing key), and every workload prints a `DIGEST` of its complete output — the sorted
  * credentials, the map entries, both decoded trees, the applied ledger state — hashed with SHA-256
  * over a canonical rendering, computed outside the timed region. Identical digests between two
  * builds therefore mean identical outputs; a digest that covered only a count would not.
  */
object CastErasureBench:
  private val bean = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
  private def allocated: Long = bean.getCurrentThreadAllocatedBytes

  private def sha256(text: String): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.update(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    md.digest().take(12).map(b => f"$b%02x").mkString

  private def measure(name: String, warmup: Int = 3, reps: Int = 5)(body: => Long): Unit =
    var i = 0
    while i < warmup do
      body
      i += 1
    val samples = (1 to reps).map { _ =>
      val a0 = allocated
      val t0 = System.nanoTime
      val checksum = body
      val ns = System.nanoTime - t0
      val a1 = allocated
      if checksum == Long.MinValue then println("impossible checksum")
      (ns / 1e6, a1 - a0)
    }
    val ms = samples.map(_._1).sorted
    val bytes = samples.map(_._2).sorted
    println(
      f"METRIC $name%-30s median=${ms(ms.size / 2)}%10.3f ms min=${ms.head}%10.3f max=${ms.last}%10.3f alloc=${bytes(bytes.size / 2)}%14d B"
    )

  // ---- #207: the order and the maps of credentials

  /** The 28 bytes of the i-th credential: `i` in the last four bytes, so that every input is
    * distinct (asserted below), and a deterministic filler in the first 24.
    */
  private def credentialBytes(i: Int): Bytes =
    val a = new Array[Byte](28)
    var j = 0
    while j < 24 do
      a(j) = ((j * 31 + (i >> 8)) & 0xff).toByte
      j += 1
    a(24) = ((i >>> 24) & 0xff).toByte
    a(25) = ((i >>> 16) & 0xff).toByte
    a(26) = ((i >>> 8) & 0xff).toByte
    a(27) = (i & 0xff).toByte
    Bytes.fromArray(a)

  private def credential(i: Int): Credential[Staking] =
    if i % 2 == 0 then
      Credential.ScriptHashObj(ScriptHash.fromBytes(credentialBytes(i)).toOption.get)
    else Credential.KeyHashObj(KeyHash.fromBytes[Staking](credentialBytes(i)).toOption.get)

  private def benchCredentials(n: Int): Unit =
    val base = Vector.tabulate(n)(credential)
    require(base.distinct.size == n, s"credentials must be distinct: ${base.distinct.size} of $n")
    val shuffled = scala.util.Random(42).shuffle(base)
    val order = summon[Order[Credential[Staking]]].toOrdering
    val sorted = base.sorted(using order)
    val built = Sorted.map(shuffled.map(c => c -> 1))
    val updated = shuffled.foldLeft(built)((acc, c) => acc.updated(c, acc.getOrElse(c, 0) + 1))
    require(built.size == n, s"the map must hold every credential: ${built.size} of $n")
    println(s"#207 credentials n=$n distinct=${base.distinct.size}")
    // size, not a whole-vector hash: the sink must not outweigh the sort it measures
    measure("credentials.sort")(base.sorted(using order).size.toLong)
    measure("credentials.mapBuild")(Sorted.map(shuffled.map(c => c -> 1)).size.toLong)
    // from a Vector, not from the map itself: `TreeMap.from` on a TreeMap returns the source, and a
    // copy that does no work would time as zero
    measure("credentials.mapCopy")(TreeMap.from(built.toVector)(using built.ordering).size.toLong)
    measure("credentials.mapUpdate")(
      shuffled.foldLeft(built)((acc, c) => acc.updated(c, acc.getOrElse(c, 0) + 1)).size.toLong
    )
    measure("credentials.iterate")(built.foldLeft(0L)((acc, kv) => acc + kv._2))
    // complete outputs, rendered outside the timed region
    println(
      s"DIGEST credentials sort=${sha256(sorted.map(c => c.show).mkString("|"))} map=${sha256(built.toVector.map((c, v) => s"${c.show}=$v").mkString("|"))} updated=${sha256(updated.toVector.map((c, v) => s"${c.show}=$v").mkString("|"))}"
    )

  // ---- #203: the decoder over nested and wide items

  private def nested(depth: Int): Bytes =
    val a = new Array[Byte](depth + 1)
    var i = 0
    while i < depth do
      a(i) = 0x81.toByte
      i += 1
    a(depth) = 0x00
    Bytes.fromArray(a)

  private def wideMap(n: Int): Bytes =
    val a = new Array[Byte](3 + n * 4)
    a(0) = 0xb9.toByte
    a(1) = ((n >> 8) & 0xff).toByte
    a(2) = (n & 0xff).toByte
    var i = 0
    while i < n do
      val o = 3 + i * 4
      a(o) = 0x19.toByte
      a(o + 1) = ((i >> 8) & 0xff).toByte
      a(o + 2) = (i & 0xff).toByte
      a(o + 3) = 0x01.toByte
      i += 1
    Bytes.fromArray(a)

  private def benchDecode(depth: Int, entries: Int): Unit =
    val deep = nested(depth)
    val wide = wideMap(entries)
    val wideLimits = Cbor.Limits(maxItems = 400000)
    val deepOut = Cbor.decode(deep).fold(e => sys.error(e.toString), n => n.value)
    val wideOut = Cbor.decode(wide, wideLimits).fold(e => sys.error(e.toString), n => n.value)
    println(s"#203 decode depth=$depth entries=$entries")
    measure("decode.nested")(Cbor.decode(deep).fold(e => sys.error(e.toString), n => n.value.hashCode.toLong))
    measure("decode.wide")(Cbor.decode(wide, wideLimits).fold(e => sys.error(e.toString), n => n.value.hashCode.toLong))
    // both decoded trees, rendered outside the timed region
    println(s"DIGEST decode nested=${sha256(deepOut.toString)} wide=${sha256(wideOut.toString)}")

  // ---- #206: the difference of records, enums and a map

  private final case class Row(a: Long, b: String, c: Long, d: Long) derives Eq, Show, Diff

  private enum Kind derives Eq, Show, Diff:
    case Alpha(x: Long)
    case Beta(y: String)
    case Gamma(z: Long)

  private def benchDiff(rows: Int): Unit =
    val dRow = Diff[Row]
    val rowA = Row(1, "x", 2, 3)
    val rowEarly = Row(2, "x", 2, 3)
    val rowLate = Row(1, "x", 2, 4)
    val dKind = Diff[Kind]
    val kA = Kind.Beta("y")
    val kB = Kind.Beta("y!")
    val kC = Kind.Gamma(1)
    val map = SortedMap.from((0 until rows).map(i => i -> Row(i, "v", i, i)))
    val changed = map.updated(rows - 1, Row(rows - 1, "v", rows - 1, -1))
    val early = map.updated(0, Row(0, "v", 0, -1))
    val equal = map.foldLeft(SortedMap.empty[Int, Row])((acc, kv) => acc + kv)
    val dMap = Diff[SortedMap[Int, Row]]
    val mapEqualOut = dMap.diff(map, equal)
    val mapEarlyOut = dMap.diff(map, early)
    val mapLateOut = dMap.diff(map, changed)
    val rowOut = List(dRow.diff(rowA, rowEarly), dRow.diff(rowA, rowLate), dRow.diff(rowA, rowA))
    val sumOut = List(dKind.diff(kA, kB), dKind.diff(kA, kC), dKind.diff(kA, kA))
    println(s"#206 diff rows=$rows")
    measure("diff.row.equal")(dRow.diff(rowA, rowA).fold(0L)(_ => 1L))
    measure("diff.row.early")(dRow.diff(rowA, rowEarly).fold(0L)(_.toString.length.toLong))
    measure("diff.row.late")(dRow.diff(rowA, rowLate).fold(0L)(_.toString.length.toLong))
    measure("diff.sum.sameCase")(dKind.diff(kA, kB).fold(0L)(_.toString.length.toLong))
    measure("diff.sum.otherCase")(dKind.diff(kA, kC).fold(0L)(_.toString.length.toLong))
    measure("diff.map.equal")(dMap.diff(map, equal).fold(0L)(_.toString.length.toLong))
    measure("diff.map.early")(dMap.diff(map, early).fold(0L)(_.toString.length.toLong))
    measure("diff.map.late")(dMap.diff(map, changed).fold(0L)(_.toString.length.toLong))
    // every difference, rendered outside the timed region
    val rendered = (rowOut ++ sumOut ++ List(mapEqualOut, mapEarlyOut, mapLateOut))
      .map(_.fold("none")(_.toString))
      .mkString("|")
    println(s"DIGEST diff ${sha256(rendered)}")

  // ---- #202: a block of transactions through the rules

  private val globals = RuleGlobals(
    LedgerGlobals(TestLedger.Supply, 100, Rational(1, 2), network = 0, updateQuorum = 2),
    EpochGeometry(Instant.EPOCH, 1000, 10000, 5, BigDecimal("0.05"))
  )

  private def ok[A](either: Either[VocabError, A]): A =
    either.fold(e => sys.error(s"vocabulary: $e"), identity)

  /** One fixed signing key: the block's inputs must be identical in every build and every run, or
    * the comparison measures the keys as well as the interpreter.
    */
  private def fixedKey(): Key =
    val random = SecureRandom.getInstance("SHA1PRNG")
    random.setSeed(Array[Byte](7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7))
    val generator = KeyPairGenerator.getInstance("Ed25519")
    generator.initialize(new java.security.spec.NamedParameterSpec("Ed25519"), random)
    Key(generator.generateKeyPair())

  private def state(pp: ProtocolParams, utxo: Utxo) =
    val empty = NewEpochState.empty[Conway](ok(EpochNo.fromBigInt(5)), pp)
    val ls = empty.es.ls
    BbodyState(
      Counted(ls.copy[Conway](utxo = ls.utxo.copy(utxo = UtxoTables(utxo))), PlutusStats.zero),
      BlocksMade.empty
    )

  private def run(pp: ProtocolParams, txs: Vector[Tx], from: BbodyState) =
    val issuer = ok(KeyHash.fromBytes[StakePool](Bytes.fromArray(Array.fill(28)(7.toByte))))
    val commitment = BodyCommitment(100, Bytes.fromArray(Array.fill(32)(1.toByte)))
    Sts(Bbody)(
      globals,
      BbodyEnv(TestLedger.env, pp, ok(Coin.fromBigInt(0))),
      from,
      BbodySignal(txs, issuer, commitment, commitment)
    )

  private def benchRules(count: Int): Unit =
    val alice = fixedKey()
    val ada = BigInt(1000000)
    val inputs = Vector.tabulate(count)(i =>
      TxIn(Bytes.fromArray(Array.tabulate[Byte](32)(j => (i >> (8 * (j % 4))).toByte)), i)
    )
    val utxo = Utxo.empty ++ inputs.map(_ -> out(alice.address(), 10 * ada))
    val txs = inputs.zipWithIndex.map { (in, i) =>
      tx(body(s"t$i", Vector(in), Vector(out(alice.address(), 10 * ada - 200)), 200), Vector(alice))
    }
    val pp = params(10).copy(maxBlockExUnits = ExUnits(1, 1))
    val from = state(pp, utxo)
    val once = run(pp, txs, from).fold(f => sys.error(f.reason), identity)
    println(s"#202 rules txs=$count fees=${once.ledger.state.utxo.fees.value}")
    // the UTxO count is the sink; the complete state is the digest, computed outside the region
    measure("rules.bbody")(
      run(pp, txs, from).fold(f => sys.error(f.reason), s => s.ledger.state.utxo.utxo.utxo.count.toLong)
    )
    // the complete applied state, rendered outside the timed region
    println(s"DIGEST rules ${sha256(once.ledger.state.toString)}")

  def main(args: Array[String]): Unit =
    val what = args.headOption.getOrElse("all")
    val n = args.lift(1).map(_.toInt)
    println(
      s"bench=$what java=${System.getProperty("java.version")} cores=${Runtime.getRuntime.availableProcessors}"
    )
    if what == "all" || what == "credentials" then benchCredentials(n.getOrElse(100000))
    if what == "all" || what == "decode" then
      benchDecode(n.getOrElse(2000), args.lift(2).map(_.toInt).getOrElse(50000))
    if what == "all" || what == "diff" then benchDiff(n.getOrElse(20000))
    if what == "all" || what == "rules" then benchRules(n.getOrElse(10000))
