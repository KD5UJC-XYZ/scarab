// SPDX-License-Identifier: Apache-2.0
package lab.vm

import lab.{Blake2b, TransactionId}
import lab.cbor.{Bytes, Cbor, Node, Value}
import scalus.uplc.builtin.{ByteString, Data}
import scalus.cardano.onchain.plutus.prelude.{List as PList}
import java.security.MessageDigest

/** Test-only independent translation; never validates a transaction. */
object ProfileTranslator:
  private final case class Rejected(reason: String) extends RuntimeException(reason)
  private def check(ok: Boolean, reason: String): Unit = if !ok then throw Rejected(reason)
  private def take[A](e: Either[String, A]): A = e.fold(s => throw Rejected(s), identity)
  private def c(tag: Int, xs: Data*): Data = Data.Constr(tag, PList.from(xs))
  private def list(xs: Seq[Data]): Data = Data.List(PList.from(xs))
  private def map(xs: (Data, Data)*): Data = Data.Map(PList.from(xs))
  private def b(x: Bytes): Data = Data.B(ByteString.fromArray(x.toArray))
  private def i(x: BigInt): Data = Data.I(x)
  private val none = c(1)
  private val empty = b(Bytes.empty)
  private def hash(x: Bytes): String =
    MessageDigest.getInstance("SHA-256").digest(x.toArray).map(v => f"${v & 255}%02x").mkString
  private def decode(x: Bytes, cap: Int): Node = take(
    Cbor.decode(x, Cbor.Limits(cap, 32, 4096, 4096))
  )
  private def arr(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case _             => throw Rejected("expected array")
  private def fixed(n: Node, size: Int): Vector[Node] =
    val xs = arr(n); check(xs.size == size, "wrong array length"); xs
  private def uint(n: Node, max: BigInt = BigInt(Long.MaxValue)): BigInt = n.value match
    case Value.UInt(x) if x <= max => x
    case _                         => throw Rejected("expected bounded unsigned integer")
  private def signed(n: Node): BigInt = n.value match
    case Value.UInt(x) if x <= Long.MaxValue => x
    case Value.NInt(x) if x >= Long.MinValue => x
    case _                                   => throw Rejected("expected signed Int64")
  private def bytes(n: Node, length: Int): Bytes = n.value match
    case Value.ByteString(x) if x.size == length => x
    case _                                       => throw Rejected("wrong byte string length")
  private def fields(n: Node, allowed: Set[Int], required: Set[Int]): Map[Int, Node] =
    val pairs = n.value match
      case Value.Map(xs) => xs.map((k, v) => uint(k, 255).toInt -> v)
      case _             => throw Rejected("expected map")
    check(pairs.map(_._1).distinct.size == pairs.size, "duplicate semantic key")
    val result = pairs.toMap
    check(
      result.keySet.subsetOf(allowed) && required.subsetOf(result.keySet),
      "unsupported or missing map field"
    )
    result
  private def tagged(n: Node, tag: Int): Node = n.value match
    case Value.Tag(t, x) if t == tag => x
    case _                           => throw Rejected("unexpected tag")
  private case class Ref(id: Bytes, index: BigInt):
    def data: Data = c(0, b(id), i(index))
  private def ref(n: Node): Ref =
    val xs = fixed(n, 2); Ref(bytes(xs(0), 32), uint(xs(1), 65535))
  private def refs(n: Node, max: Int): Vector[Ref] =
    val xs = arr(tagged(n, 258)).map(ref)
    check(
      xs.nonEmpty && xs.size <= max && xs.distinct.size == xs.size,
      "input set bounds or duplicate"
    )
    xs.sortBy(x => (x.id.hex, x.index))
  private case class Output(data: Data, script: Boolean, credential: Bytes, datum: Option[Data])
  private def output(n: Node): Output =
    val (addr, amount, datumNode) = n.value match
      case Value.Arr(_) =>
        val xs = fixed(n, 2); (xs(0), xs(1), None)
      case Value.Map(_) =>
        val fs = fields(n, Set(0, 1, 2), Set(0, 1)); (fs(0), fs(1), fs.get(2))
      case _ => throw Rejected("unsupported output wrapper")
    val address = bytes(addr, 29)
    val kind = address.value.head & 255
    check(kind == 0x60 || kind == 0x70, "enterprise testnet address required")
    val credential = Bytes(address.value.tail)
    val datum = datumNode.map { dn =>
      val xs = fixed(dn, 2); check(uint(xs(0)) == 1, "inline datum required")
      val raw = tagged(xs(1), 24).value match
        case Value.ByteString(x) => x
        case _                   => throw Rejected("inline data bytes required")
      val ds = fixed(tagged(decode(raw, 4096), 121), 2)
      c(0, b(bytes(ds(0), 28)), i(uint(ds(1))))
    }
    val value = map(empty -> map(empty -> i(uint(amount))))
    Output(
      c(
        0,
        c(0, c(if kind == 0x70 then 1 else 0, b(credential)), none),
        value,
        datum.fold(c(0))(d => c(2, d)),
        none
      ),
      kind == 0x70,
      credential,
      datum
    )
  private def interval(low: Option[BigInt], high: Option[BigInt]): Data =
    check(low.forall(_ <= 1000000) && high.forall(_ <= 1000000), "slot outside domain")
    check(!(low.isDefined && high.isDefined) || low.get <= high.get, "inverted interval")
    def finite(x: BigInt) = c(1, i(BigInt("1577836800000") + x * 1000))
    c(
      0,
      c(0, low.fold(c(0))(finite), c(1)),
      c(0, high.fold(c(2))(finite), c(if high.isDefined then 0 else 1))
    )

  private[vm] final case class ParsedProfile(
      context: Data,
      redeemerOriginal: Bytes,
      suppliedCommitment: Option[Bytes]
  )

  def translate(
      tx: Bytes,
      parameters: Bytes,
      entries: Vector[(Bytes, Bytes)]
  ): Either[String, Data] = parseProfile(tx, parameters, entries, false).map(_.context)

  // Only the integrity diagnostic may omit body key11. Normal translation still requires it.
  private[vm] def parseProfile(
      tx: Bytes,
      parameters: Bytes,
      entries: Vector[(Bytes, Bytes)],
      allowAbsentCommitment: Boolean
  ): Either[String, ParsedProfile] =
    try
      check(tx.size <= 65536 && parameters.size <= 65536, "packet size bound")
      check(
        entries.size <= 3 && entries.forall((a, b) => a.size <= 128 && b.size <= 4096) && entries
          .map((a, b) => a.size + b.size)
          .sum <= 16384,
        "pre-state bounds"
      )
      check(
        hash(parameters) == "9626e5907d905440a4c5fd119c57b8aa8524f2d0b8e8fe32ef5f33543db2f67b",
        "unregistered parameters"
      )
      val wrapper = fixed(decode(tx, 65536), 4)
      check(
        wrapper(2).value == Value.Bool(true) && wrapper(3).value == Value.Null,
        "unsupported validity or auxiliary wrapper"
      )
      val requiredBody = if allowAbsentCommitment then Set(0, 1, 2, 13) else Set(0, 1, 2, 11, 13)
      val body = fields(wrapper(0), Set(0, 1, 2, 3, 8, 11, 13), requiredBody)
      val suppliedCommitment = body.get(11).map(bytes(_, 32))
      val inputs = refs(body(0), 2); val collateral = refs(body(13), 1)
      check(!inputs.contains(collateral.head), "collateral overlaps ordinary input")
      val pre = entries.map((a, b) => ref(decode(a, 128)) -> output(decode(b, 4096)))
      check(pre.map(_._1).distinct.size == pre.size, "duplicate pre-state input")
      check(pre.map(_._1).toSet == (inputs ++ collateral).toSet, "missing or extra pre-state input")
      val resolved = pre.toMap
      val col = resolved(collateral.head)
      check(!col.script && col.datum.isEmpty, "unsupported collateral output")
      val scripts = inputs.filter(x => resolved(x).script)
      check(scripts.size == 1, "one spending script required")
      check(
        inputs.filterNot(scripts.contains).forall(x => resolved(x).datum.isEmpty),
        "key input datum unsupported"
      )
      val own = scripts.head; val ownOutput = resolved(own)
      check(ownOutput.datum.nonEmpty, "missing inline datum")
      val witnesses = fields(wrapper(1), Set(5, 7), Set(5, 7))
      val scriptNodes = fixed(tagged(witnesses(7), 258), 1)
      val script = bytes(scriptNodes.head, 369)
      check(hash(script) == Pv9ReferenceFixture.scriptCborSha256, "unregistered script")
      val credential = Blake2b.hash224.hash(Bytes(Vector(3.toByte) ++ script.value))
      check(credential == ownOutput.credential, "script credential mismatch")
      val redeemerPairs = witnesses(5).value match
        case Value.Map(xs) if xs.size == 1 => xs
        case _                             => throw Rejected("one redeemer map entry required")
      val pointer = fixed(redeemerPairs.head._1, 2)
      check(
        uint(pointer(0)) == 0 && uint(pointer(1), 1) == inputs.indexOf(own),
        "invalid spending pointer"
      )
      val payload = fixed(redeemerPairs.head._2, 2)
      val redeemer = i(signed(payload(0)))
      val units = fixed(payload(1), 2)
      check(
        uint(units(0)) == 100000 && uint(units(1)) == 30000000,
        "requires fixed declared execution budget"
      )
      val outputs = arr(body(1)).map(output)
      check(
        outputs.nonEmpty && outputs.size <= 2 && outputs.forall(o => !o.script && o.datum.isEmpty),
        "unsupported outputs"
      )
      val range = interval(body.get(8).map(uint(_)), body.get(3).map(uint(_)))
      val info = c(
        0,
        list(inputs.map(x => c(0, x.data, resolved(x).data))),
        list(Seq.empty),
        list(outputs.map(_.data)),
        i(uint(body(2))),
        map(),
        list(Seq.empty),
        map(),
        range,
        list(Seq.empty),
        map(c(1, own.data) -> redeemer),
        map(),
        b(take(TransactionId.fromEnvelope(tx))),
        map(),
        list(Seq.empty),
        none,
        none
      )
      Right(
        ParsedProfile(
          c(0, info, redeemer, c(1, own.data, c(0, ownOutput.datum.get))),
          witnesses(5).original,
          suppliedCommitment
        )
      )
    catch case Rejected(reason) => Left(reason)
