// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import scala.util.Random
import xyz.kd5ujc.scarab.codec.Bytes

/** [[WireSkipFunctional]] against [[ProtocolWire.Reader]]: the item and where it ends, or the same
  * error, for every input (issue 657).
  */
class WireSkipEquivalenceSuite extends munit.FunSuite:
  private def imperative(
      input: Bytes,
      limits: ProtocolWire.Limits
  ): Either[ProtocolError, (Bytes, Int)] =
    ProtocolWire.attempt {
      val reader = new ProtocolWire.Reader(input, limits)
      val item = reader.item()
      (item, reader.position)
    }

  private def functional(
      input: Bytes,
      limits: ProtocolWire.Limits
  ): Either[ProtocolError, (Bytes, Int)] =
    WireSkipFunctional.item(input, limits).map((item, at) => (item, at.pos))

  private def differences(inputs: Vector[Bytes], limits: ProtocolWire.Limits): Vector[String] =
    inputs.zipWithIndex.flatMap { (input, i) =>
      val expected = imperative(input, limits)
      val found = functional(input, limits)
      Option.when(expected != found)(s"input $i (${input.size} B): $expected, functional $found")
    }

  /** Every prefix up to 512 bytes and 512 evenly spaced longer ones. */
  private def prefixes(block: Bytes): Vector[Bytes] =
    val short = (0 to block.size.min(512)).toVector
    val spaced =
      if block.size <= 512 then Vector.empty
      else (1 to 512).map(i => 512 + (block.size - 512) * i / 512)
    (short ++ spaced).distinct.map(block.slice(0, _))

  private def flips(block: Bytes, seed: Long): Vector[Bytes] =
    val random = new Random(seed)
    Vector.fill(16) {
      val bytes = block.toArray
      val at = random.nextInt(bytes.length)
      bytes(at) = (bytes(at) ^ (1 << random.nextInt(8))).toByte
      Bytes.wrap(bytes)
    }

  test("the corpus is the blocks it names") {
    assertEquals(WireCorpus.preview.size, 648)
    assertEquals(WireCorpus.preprod.size, 70)
    assert(WireCorpus.mainnet.nonEmpty)
  }

  test("every block: the same item and end") {
    assertEquals(differences(WireCorpus.all, WireCorpus.Streaming), Vector.empty)
    assert(WireCorpus.all.forall(b => imperative(b, WireCorpus.Streaming).isRight))
  }

  test("every prefix of every block: the same item, or the same error") {
    assertEquals(differences(WireCorpus.all.flatMap(prefixes), WireCorpus.Streaming), Vector.empty)
  }

  test("sixteen single-bit changes of every block: the same item, or the same error") {
    val changed = WireCorpus.all.zipWithIndex.flatMap((b, i) => flips(b, i.toLong))
    assertEquals(differences(changed, WireCorpus.Streaming), Vector.empty)
  }

  test("tight limits reach every bound in the same order") {
    val tight = Vector(
      WireCorpus.Streaming.copy(maxItems = 64),
      WireCorpus.Streaming.copy(maxDepth = 2),
      WireCorpus.Streaming.copy(maxStringBytes = 16),
      WireCorpus.Streaming.copy(maxMessageBytes = 1024, maxStringBytes = 1024),
      WireCorpus.Streaming.copy(maxItems = 0)
    )
    tight.foreach(limits => assertEquals(differences(WireCorpus.all, limits), Vector.empty, limits))
    // each bound is met by some block, so the comparison covers the refusals, not only success
    assert(tight.take(4).forall(l => WireCorpus.all.exists(b => imperative(b, l).isLeft)))
  }

  /** The smallest value of a limit at which the current reader takes `block`, by bisection. */
  private def threshold(block: Bytes, at: Int => ProtocolWire.Limits, high: Int): Int =
    @scala.annotation.tailrec
    def go(low: Int, high: Int): Int =
      if low >= high then high
      else
        val mid = low + (high - low) / 2
        if imperative(block, at(mid)).isRight then go(low, mid) else go(mid + 1, high)
    go(0, high)

  test("at each block's own bounds and one below them, the same item or the same error") {
    val base = WireCorpus.Streaming
    val families: Vector[(String, Int => ProtocolWire.Limits, Int)] = Vector(
      ("depth", d => base.copy(maxDepth = d), base.maxDepth),
      ("items", n => base.copy(maxItems = n.max(1)), base.maxItems),
      ("string bytes", n => base.copy(maxStringBytes = n), base.maxStringBytes),
      (
        "message bytes",
        n =>
          base.copy(maxMessageBytes = n.max(1), maxStringBytes = n.max(1).min(base.maxStringBytes)),
        base.maxMessageBytes
      )
    )
    val found = for
      (name, at, high) <- families
      block <- WireCorpus.all
      bound = threshold(block, at, high)
      limits <- Vector(at(bound), at(bound - 1)).filter(_.valid)
      if imperative(block, limits) != functional(block, limits)
    yield s"$name ${bound}: ${block.size} B"
    assertEquals(found, Vector.empty)
  }

  test(
    "short prefixes under small limits: the same error where a limit and the end of input meet"
  ) {
    val base = WireCorpus.Streaming
    val small =
      Vector(1, 2, 3, 5, 8, 13, 21).map(n => base.copy(maxItems = n)) ++
        Vector(0, 1, 2, 3).map(d => base.copy(maxDepth = d)) ++
        Vector(0, 1, 28, 32).map(n => base.copy(maxStringBytes = n))
    val short = WireCorpus.all.flatMap(b => (0 to b.size.min(512)).map(b.slice(0, _))).distinct
    small.foreach(limits => assertEquals(differences(short, limits), Vector.empty, limits))
  }

  test("hand-made items: indefinite strings, simple values and reserved heads") {
    val cases = Vector(
      "5f4161416262ff", // indefinite byte string of two chunks
      "7f6161ff", // indefinite text string
      "7f61ff", // a one-byte text chunk whose byte is not UTF-8, and no break after it
      "7f62c328ff", // invalid UTF-8 in a chunk
      "5f6161ff", // a text chunk in a byte string
      "9f01029f03ffff", // nested indefinite arrays
      "bf0102ff", // indefinite map
      "bf01ff", // indefinite map with a key and no value
      "f818", // extended simple value below 32
      "f820", // extended simple value 32
      "fc", // reserved simple value
      "ff", // a break where an item belongs
      "1c", // reserved argument
      "c1c2c301", // nested tags
      "9b0000000100000000", // an array longer than the limits
      "fb3ff0000000000000" // a double
    )
    val inputs = cases.map(h => Bytes.fromHex(h).fold(e => fail(e.toString), identity))
    assertEquals(differences(inputs, WireCorpus.Streaming), Vector.empty)
  }
