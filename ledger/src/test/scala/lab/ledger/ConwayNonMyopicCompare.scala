// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.Bytes
import java.nio.file.{Files, Path}

/** Effectful test-only transport around the pure model. Input must first pass the pinned Python
  * validator; this receipt compares supplied native observations, not authenticated ledger state.
  */
object ConwayNonMyopicCompare:
  private val N = ConwayNonMyopic
  private val InputHash = "b90a463584e1018a2185023cecb82e1558548fa915b1f57cfe06381820b4d235"
  private val Ids = Vector(
    "empty-clears",
    "empty-pot",
    "new-only",
    "shared-old-only",
    "decay",
    "negative-zero",
    "ties-subnormal",
    "cancellation",
    "addition-overflow",
    "subtract-overflow",
    "zero-min-positive-first",
    "zero-min-negative-first"
  )
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def hex(s: String, width: Int): String =
    require(s.matches(s"[0-9a-f]{$width}"), "hex shape")
    s
  private def key(s: String): Bytes = Bytes(
    hex(s, 56).grouped(2).map(Integer.parseInt(_, 16).toByte).toVector
  )
  private def vector(s: String): Vector[Int] =
    val xs = s.split(",", -1).toVector
    require(xs.size == 100, "weight count")
    xs.map(x => java.lang.Long.parseLong(hex(x, 8), 16).toInt)
  private def coin(s: String): BigInt =
    require(s.matches("0|[1-9][0-9]{0,19}"), "coin shape")
    val n = BigInt(s); require(n < (BigInt(1) << 64), "coin bound"); n

  def compare(lines: Vector[String]): String =
    require(lines.nonEmpty && lines.size < 1000, "line bound")
    val header = lines.head.split("\t", -1).toVector
    require(
      header.size == 3 && header(0) == "non-myopic-comparison-v1" && header(1) == InputHash,
      "comparison provenance"
    )
    hex(header(2), 64)
    val inputLines = Vector.newBuilder[String]
    var index = 1
    var admitted = 0
    var rejected = 0
    for id <- Ids do
      inputLines += lines(index)
      val c = lines(index).split("\t", -1).toVector; index += 1
      require(c.size == 5 && c(0) == "CASE" && c(1) == id, "case order")
      val oldPot = coin(c(2)); val pot = coin(c(3))
      val accepts = !Set("addition-overflow", "subtract-overflow").contains(id)
      require(c(4) == accepts.toString, "admission label")
      def rows(tag: String): Map[Bytes, Vector[Int]] =
        val b = Vector.newBuilder[(Bytes, Vector[Int])]
        while index < lines.size && lines(index).startsWith(tag + "\t") do
          if tag != "AFTER" then inputLines += lines(index)
          val r = lines(index).split("\t", -1).toVector; index += 1
          require(r.size == 3, "row shape"); b += key(r(1)) -> vector(r(2))
        val result = b.result()
        require(
          result.size <= 4096 && result.map(_._1.hex) == result.map(_._1.hex).distinct.sorted,
          "pool order"
        )
        result.toMap
      val history = rows("HISTORY"); val fresh = rows("FRESH"); val after = rows("AFTER")
      val p = lines(index).split("\t", -1).toVector; index += 1
      require(p.size == 2 && p(0) == "POT" && coin(p(1)) == pot, "native pot")
      require(lines(index) == "END", "case terminator"); index += 1
      def supplied(m: Map[Bytes, Vector[Int]]) = m.map((k, v) => k -> get(N.likelihood(v)))
      require(after.keySet == fresh.keySet, "native output pool domain")
      val old = get(N.state(supplied(history), oldPot))
      val result = N.completeSupplied(old, old.id, pot, fresh.keySet, supplied(fresh))
      if accepts then
        val done = get(result)
        require(
          done.after.rewardPot == pot && done.after.likelihoods.map((k, v) =>
            k -> v.rawBits
          ) == after,
          "native raw-bit mismatch: " + id
        )
        admitted += 1
      else
        require(result.isLeft, "unsupported overflow must reject: " + id)
        rejected += 1
    val transportHash = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(
        (inputLines.result().mkString("\n") + "\n")
          .getBytes(java.nio.charset.StandardCharsets.US_ASCII)
      )
      .map(b => f"${b & 0xff}%02x")
      .mkString
    require(
      transportHash == "75304bce22db8caaaf0b433b763fdf317e948adfc7e9161ad6a1578792045ed1",
      "canonical case input transport changed"
    )
    for fee <- Vector(BigInt(0), BigInt(37)) do
      val e = lines(index).split("\t", -1).toVector; index += 1
      require(e.size == 4 && e(0) == "EMPTY" && coin(e(1)) == fee, "empty probe identity")
      val history = get(
        N.state(Map(key("11" * 28) -> get(N.likelihood(Vector.fill(100)(0x40e00000)))), 99)
      )
      val done = get(N.completeSupplied(history, history.id, fee, Set.empty, Map.empty))
      val applied = get(N.applyAtBoundary(history, history.id, Some(done)))
      require(
        done.after.likelihoods.isEmpty && done.after.rewardPot == coin(e(2)),
        "native empty completion"
      )
      require(
        applied.likelihoods.isEmpty && applied.rewardPot == coin(e(3)),
        "native empty application"
      )
    require(
      lines(index) == "GENERATION_DIAGNOSTIC_ONLY\t3" && index + 1 == lines.size,
      "diagnostic terminator"
    )
    s"Compared $admitted admitted raw-bit cases; $rejected Scala overflow rejections; 2 empty-go completion/application probes; 3 generation probes diagnostic-only. InputSHA256=$InputHash NativeResultSHA256=${header(2)}"

  def main(args: Array[String]): Unit =
    require(args.length == 1, "usage: ConwayNonMyopicCompare validated.tsv")
    val path = Path.of(args(0))
    require(Files.size(path) <= 1048576, "comparison file bound")
    val stream = Files.newInputStream(path)
    val bytes =
      try stream.readNBytes(1048577)
      finally stream.close()
    require(
      bytes.length <= 1048576 && bytes.forall(b => b >= 0 && b < 127),
      "ASCII comparison bound"
    )
    val raw = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII)
    require(raw.endsWith("\n"), "canonical newline")
    println(compare(raw.dropRight(1).split("\n", -1).toVector))
