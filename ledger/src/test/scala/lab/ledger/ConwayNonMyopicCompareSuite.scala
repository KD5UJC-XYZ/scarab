// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.Bytes

/** Synthetic comparator mutations are separate from the hash-pinned native golden test. */
class ConwayNonMyopicCompareSuite extends munit.FunSuite:
  private val N = ConwayNonMyopic
  private def get[A](e: Either[String, A]): A = e.fold(fail(_), identity)
  private def key(s: String) = Bytes(s.grouped(2).map(Integer.parseInt(_, 16).toByte).toVector)
  private def syntheticTransport(): Vector[String] =
    val stream = getClass.getResourceAsStream("/non-myopic-comparison-inputs.tsv")
    val raw =
      try new String(stream.readNBytes(1048577), java.nio.charset.StandardCharsets.US_ASCII)
      finally stream.close()
    val inputs = raw.trim.split("\n").toVector
    val out = Vector.newBuilder[String]
    out += "non-myopic-comparison-v1\tb90a463584e1018a2185023cecb82e1558548fa915b1f57cfe06381820b4d235\t" + "0" * 64
    var i = 0
    while i < inputs.size do
      val c = inputs(i).split("\t"); out += inputs(i); i += 1
      def read(tag: String): Map[Bytes, N.Likelihood] =
        val rows = Map.newBuilder[Bytes, N.Likelihood]
        while i < inputs.size && inputs(i).startsWith(tag + "\t") do
          val row = inputs(i).split("\t"); out += inputs(i); i += 1
          rows += key(row(1)) -> get(
            N.likelihood(row(2).split(",").toVector.map(x => java.lang.Long.parseLong(x, 16).toInt))
          )
        rows.result()
      val history = read("HISTORY"); val fresh = read("FRESH")
      val state = get(N.state(history, BigInt(c(2))))
      val result = N.completeSupplied(state, state.id, BigInt(c(3)), fresh.keySet, fresh)
      val rows =
        if c(4) == "true" then get(result).after.likelihoods
        else
          assert(result.isLeft)
          fresh.map((k, _) => k -> get(N.likelihood(Vector.fill(100)(0))))
      rows.toVector
        .sortBy(_._1.hex)
        .foreach((k, v) => out += s"AFTER\t${k.hex}\t${v.hex.mkString(",")}")
      out += s"POT\t${c(3)}"; out += "END"
    out += "EMPTY\t0\t0\t0"; out += "EMPTY\t37\t37\t37"; out += "GENERATION_DIAGNOSTIC_ONLY\t3"
    out.result()

  test("recorded native raw-bit golden matches the finite supplied model") {
    def resource(name: String): Array[Byte] =
      val in = getClass.getResourceAsStream("/non-myopic/" + name)
      require(in != null, "native comparison resource missing")
      val raw =
        try in.readNBytes(1048577)
        finally in.close()
      require(raw.length <= 1048576, "native comparison resource bound")
      raw
    def sha(raw: Array[Byte]): String = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(raw)
      .map(b => f"${b & 0xff}%02x")
      .mkString
    val native = resource("native-result.json")
    val transport = resource("native-comparison.tsv")
    assertEquals(sha(native), "e336935edca7af26f79c4497074e16ad72d3bcf92075adcb372f92950efce214")
    assertEquals(sha(transport), "a5ffe24af417adea0f8c7c3ea2d0bd8e0b35544c93171086b62ed664f260dedd")
    require(transport.forall(b => b >= 0 && b < 127), "ASCII native transport")
    val text = new String(transport, java.nio.charset.StandardCharsets.US_ASCII)
    require(text.endsWith("\n"), "canonical native transport newline")
    val receipt = ConwayNonMyopicCompare.compare(text.dropRight(1).split("\n", -1).toVector)
    assert(receipt.startsWith("Compared 10 admitted raw-bit cases; 2 Scala overflow rejections;"))
    assert(receipt.endsWith("NativeResultSHA256=" + sha(native)))
  }

  test("synthetic transport exercises ten comparisons, two rejections and separate empty probes") {
    val receipt = ConwayNonMyopicCompare.compare(syntheticTransport())
    assert(receipt.startsWith("Compared 10 admitted raw-bit cases; 2 Scala overflow rejections;"))
    assert(receipt.contains("3 generation probes diagnostic-only"))
  }
  test("one changed native output bit fails even when transport shape remains valid") {
    val lines = syntheticTransport()
    val i = lines.indexWhere(_.startsWith("AFTER\t"))
    val changed = lines.updated(i, lines(i).replaceFirst("00000000", "00000001"))
    intercept[IllegalArgumentException](ConwayNonMyopicCompare.compare(changed))
  }
  test("changed canonical input, wrong identity and truncated or appended transport fail") {
    val lines = syntheticTransport()
    val i = lines.indexWhere(_.startsWith("HISTORY\t"))
    val changed = lines.updated(i, lines(i).replace("40e00000", "40c00000"))
    intercept[IllegalArgumentException](ConwayNonMyopicCompare.compare(changed))
    intercept[IllegalArgumentException](ConwayNonMyopicCompare.compare(lines.updated(0, "wrong")))
    intercept[Exception](ConwayNonMyopicCompare.compare(lines.dropRight(1)))
    intercept[IllegalArgumentException](ConwayNonMyopicCompare.compare(lines :+ "extra"))
  }
