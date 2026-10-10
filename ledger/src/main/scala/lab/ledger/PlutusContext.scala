// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.Blake2b
import lab.plutus.PlutusExecution
import lab.plutus.PlutusExecution.Failure
import java.security.MessageDigest
import scala.util.control.NonFatal

/** Independent V3 Data construction. No Scalus, reference context, or ledger-valid receipt. */
object PlutusContext:
  private def node(value: Value): Node = Node(value, Bytes.empty)
  private def constr(tag: Int, fields: Value*): Value =
    Value.Tag(BigInt(121 + tag), node(Value.Arr(fields.toVector.map(node))))
  private def list(fields: Value*): Value = Value.Arr(fields.toVector.map(node))
  private def map(fields: (Value, Value)*): Value =
    Value.Map(fields.toVector.map((key, value) => node(key) -> node(value)))
  private def bytes(value: Bytes): Value = Value.ByteString(value)
  private def integer(value: BigInt): Value =
    if value < 0 then Value.NInt(value) else Value.UInt(value)
  private val none = constr(1)
  private val emptyBytes = bytes(Bytes.empty)

  // Pinned Plutus Data serialization: nonempty lists (including constructor fields) are
  // indefinite, empty lists definite; maps are definite and retain pair order.
  // Core Cbor.encode intentionally uses definite lists, so it cannot encode Data wholesale.
  private[ledger] def encodeData(value: Value): Either[String, Bytes] =
    def scalar(v: Value): Either[String, Vector[Byte]] = Cbor.encode(v).map(_.value)
    def sequence(xs: Vector[Node], depth: Int): Either[String, Vector[Byte]] =
      xs.foldLeft[Either[String, Vector[Byte]]](Right(Vector.empty)) { (acc, x) =>
        for before <- acc; next <- go(x.value, depth + 1)
        yield before ++ next
      }
    def go(v: Value, depth: Int): Either[String, Vector[Byte]] =
      if depth > 32 then Left("context depth limit exceeded")
      else
        v match
          case Value.Arr(xs) if xs.isEmpty => Right(Vector(0x80.toByte))
          case Value.Arr(xs) =>
            sequence(xs, depth).map(x => Vector(0x9f.toByte) ++ x :+ 0xff.toByte)
          case Value.Tag(t, n) if t >= 121 && t <= 127 =>
            go(n.value, depth + 1).map(x => Vector(0xd8.toByte, t.toByte) ++ x)
          case Value.Map(xs) if xs.size <= 23 =>
            sequence(xs.flatMap((k, v) => Vector(k, v)), depth)
              .map(x => Vector((0xa0 + xs.size).toByte) ++ x)
          case Value.UInt(_) | Value.NInt(_) | Value.ByteString(_) => scalar(v)
          case _ => Left("unsupported context Data shape")
    for
      encoded <- go(value, 0)
      _ <- Either.cond(encoded.size <= 65536, (), "context byte limit exceeded")
      result = Bytes(encoded)
      _ <- Cbor.decode(result, Cbor.Limits(65536, 32, 4096, 4096))
    yield result

  // Primitive assembly stays internal until the main-owned checked facts DTO is available.
  // POSIX endpoints have already been derived from the authenticated environment schedule.
  private[ledger] def spendData(
      transactionId: Bytes,
      inputId: Bytes,
      inputIndex: BigInt,
      scriptCredential: Bytes,
      inputCoin: BigInt,
      beneficiary: Bytes,
      minimumPayment: BigInt,
      payoutCredential: Bytes,
      payoutCoin: BigInt,
      fee: BigInt,
      lowerMillis: Option[BigInt],
      upperMillis: Option[BigInt]
  ): Either[String, Bytes] =
    val max = (BigInt(1) << 64) - 1
    if Vector(transactionId, inputId).exists(x => x == null || x.size != 32) ||
      Vector(scriptCredential, beneficiary, payoutCredential).exists(x => x == null || x.size != 28)
    then Left("context identity width")
    else if inputIndex < 0 || inputIndex > 65535 ||
      Vector(inputCoin, minimumPayment, payoutCoin, fee).exists(x => x < 0 || x > max)
    then Left("context integer range")
    else if lowerMillis.exists(x => x < 0 || x > max) ||
      upperMillis.exists(x => x < 0 || x > max) ||
      lowerMillis.exists(low => upperMillis.exists(_ < low))
    then Left("context POSIX interval range")
    else
      val own = constr(0, bytes(inputId), integer(inputIndex))
      val datum = constr(0, bytes(beneficiary), integer(minimumPayment))
      def address(credential: Bytes, script: Boolean): Value =
        constr(0, constr(if script then 1 else 0, bytes(credential)), none)
      def value(coin: BigInt): Value = map(emptyBytes -> map(emptyBytes -> integer(coin)))
      val resolved =
        constr(0, address(scriptCredential, true), value(inputCoin), constr(2, datum), none)
      val payout = constr(0, address(payoutCredential, false), value(payoutCoin), constr(0), none)
      def finite(millis: BigInt): Value = constr(1, integer(millis))
      val interval = constr(
        0,
        constr(0, lowerMillis.fold(constr(0))(finite), constr(1)),
        constr(
          0,
          upperMillis.fold(constr(2))(finite),
          constr(if upperMillis.isDefined then 0 else 1)
        )
      )
      val redeemer = integer(7)
      val info = constr(
        0,
        list(constr(0, own, resolved)),
        list(),
        list(payout),
        integer(fee),
        map(),
        list(),
        map(),
        interval,
        list(), // required signers, not the collateral owner's vkey witnesses
        map(constr(1, own) -> redeemer),
        map(), // inline datum does not appear in the witness datum map
        bytes(transactionId),
        map(),
        list(),
        none,
        none
      )
      encodeData(constr(0, info, redeemer, constr(1, own, constr(0, datum))))

  final class Derived private[PlutusContext] (
      val contextCbor: Bytes,
      val originalRedeemers: Bytes,
      val suppliedIntegrity: Bytes,
      val scriptPayload: Bytes,
      val transactionId: Bytes
  )

  private final case class Rejected(failure: Failure) extends RuntimeException
  private def requireShape(ok: Boolean, reason: String): Unit =
    if !ok then throw Rejected(Failure.Unsupported(reason))
  private def take[A](value: Either[String, A]): A =
    value.fold(reason => throw Rejected(Failure.MalformedInput(reason)), identity)
  private def decode(raw: Bytes, maximum: Int): Node =
    take(Cbor.decode(raw, Cbor.Limits(maximum, 32, 4096, 4096)))
  private def array(n: Node, size: Int): Vector[Node] = n.value match
    case Value.Arr(xs) if xs.size == size => xs
    case _ => throw Rejected(Failure.Unsupported("fixed array cardinality"))
  private def unsigned(n: Node, maximum: BigInt = (BigInt(1) << 64) - 1): BigInt = n.value match
    case Value.UInt(x) if x <= maximum => x
    case _ => throw Rejected(Failure.Unsupported("bounded unsigned integer required"))
  private def byteString(n: Node, size: Int): Bytes = n.value match
    case Value.ByteString(x) if x.size == size => x
    case _ => throw Rejected(Failure.Unsupported("byte string width"))
  private def tagged(n: Node, tag: Int): Node = n.value match
    case Value.Tag(t, value) if t == tag => value
    case _ => throw Rejected(Failure.Unsupported("unsupported CBOR tag"))
  private def fields(n: Node, allowed: Set[Int], required: Set[Int]): Map[Int, Node] =
    val pairs = n.value match
      case Value.Map(xs) => xs.map((k, v) => unsigned(k, 255).toInt -> v)
      case _             => throw Rejected(Failure.Unsupported("map required"))
    requireShape(pairs.map(_._1).distinct.size == pairs.size, "duplicate semantic map key")
    val result = pairs.toMap
    requireShape(
      result.keySet.subsetOf(allowed) && required.subsetOf(result.keySet),
      "unsupported or missing map field"
    )
    result
  private case class Output(credential: Bytes, coin: BigInt, datum: Option[(Bytes, BigInt)])
  private def output(n: Node, script: Boolean, networkId: Int): Output =
    val (a, coin, data) = n.value match
      case Value.Arr(_) =>
        val xs = array(n, 2); (xs(0), xs(1), None)
      case Value.Map(_) =>
        val fs = fields(n, Set(0, 1, 2), Set(0, 1)); (fs(0), fs(1), fs.get(2))
      case _ => throw Rejected(Failure.Unsupported("output shape"))
    val address = byteString(a, 29)
    requireShape(
      (address.value.head & 255) == (if script then 0x70 else 0x60) + networkId,
      "enterprise address kind/network"
    )
    val datum = data.map { value =>
      val pair = array(value, 2)
      requireShape(unsigned(pair(0)) == 1, "inline datum required")
      val raw = tagged(pair(1), 24).value match
        case Value.ByteString(x) if x.size <= 256 => x
        case _ => throw Rejected(Failure.Unsupported("inline datum byte limit"))
      val ds = array(tagged(decode(raw, 256), 121), 2)
      (byteString(ds(0), 28), unsigned(ds(1)))
    }
    requireShape(script == datum.nonEmpty, "datum only on script input")
    Output(Bytes(address.value.tail), unsigned(coin), datum)
  private def inputRef(n: Node): (Bytes, BigInt) =
    val pair = array(n, 2); (byteString(pair(0), 32), unsigned(pair(1), 65535))

  /** Derives from original envelope and exact resolved outputs. The caller separately authenticates
    * resolved state, genesis/time source and network against its private checked environment. This
    * rechecks bounded grammar; it neither verifies signatures nor grants phase-one validity.
    */
  def derive(input: PlutusContextInput, networkId: Int): Either[Failure, Derived] =
    try
      requireShape(
        input != null && input.transaction != null && input.time != null,
        "context inputs required"
      )
      requireShape(networkId == 0 || networkId == 1, "network ID outside profile")
      requireShape(
        input.ordinaryInputs.size == 1 && input.collateralInputs.size == 1,
        "one ordinary and one collateral input required"
      )
      val ordinary = input.ordinaryInputs.head; val collateral = input.collateralInputs.head
      for resolved <- Vector(ordinary, collateral) do
        requireShape(
          resolved != null && resolved.transactionId.size == 32 &&
            resolved.index >= 0 && resolved.index <= 65535 && resolved.originalOutput.size <= 4096,
          "resolved input bounds"
        )
      val ordinaryRef = ordinary.transactionId -> ordinary.index
      val collateralRef = collateral.transactionId -> collateral.index
      requireShape(ordinaryRef != collateralRef, "ordinary/collateral overlap")
      val tx = input.transaction
      val wrapper = array(decode(tx.original, 65536), 4)
      requireShape(
        wrapper(2).value == Value.Bool(true) && wrapper(3).value == Value.Null,
        "successful envelope without auxiliary data required"
      )
      val body = fields(wrapper(0), Set(0, 1, 2, 3, 8, 11, 13), Set(0, 1, 2, 11, 13))
      requireShape(
        inputRef(array(tagged(body(0), 258), 1).head) == ordinaryRef,
        "ordinary input resolution mismatch"
      )
      requireShape(
        inputRef(array(tagged(body(13), 258), 1).head) == collateralRef,
        "collateral input resolution mismatch"
      )
      val consumed = output(decode(ordinary.originalOutput, 4096), true, networkId)
      output(decode(collateral.originalOutput, 4096), false, networkId)
      val payoutNode = array(body(1), 1).head
      requireShape(payoutNode.original.size <= 4096, "payout output byte limit")
      val payout = output(payoutNode, false, networkId)
      val witnesses = fields(wrapper(1), Set(0, 5, 7), Set(0, 5, 7))
      val key = array(array(tagged(witnesses(0), 258), 1).head, 2)
      byteString(key(0), 32); byteString(key(1), 64)
      val script = byteString(array(tagged(witnesses(7), 258), 1).head, 369)
      val scriptHash = MessageDigest
        .getInstance("SHA-256")
        .digest(script.toArray)
        .map(b => f"${b & 255}%02x")
        .mkString
      requireShape(
        scriptHash == "57fb50f08ffc1222cbe2b652db3dcfed0f714da98f8170cb104aee2bde4070f6",
        "unregistered V3 script"
      )
      requireShape(
        Blake2b.hash224.hash(Bytes(Vector(3.toByte) ++ script.value)) == consumed.credential,
        "script credential mismatch"
      )
      val pair = witnesses(5).value match
        case Value.Map(xs) if xs.size == 1 => xs.head
        case _ => throw Rejected(Failure.Unsupported("one redeemer map entry required"))
      val pointer = array(pair._1, 2)
      requireShape(
        unsigned(pointer(0)) == 0 && unsigned(pointer(1)) == 0,
        "spending pointer [0,0] required"
      )
      val redeemer = array(pair._2, 2); val units = array(redeemer(1), 2)
      requireShape(
        unsigned(redeemer(0)) == 7 && unsigned(units(0)) == 100000 &&
          unsigned(units(1)) == 30000000,
        "fixed redeemer and execution units required"
      )
      val time = input.time
      val max = (BigInt(1) << 64) - 1
      requireShape(
        time.genesisDigest.size == 32 && time.systemStartMillis >= 0 &&
          time.systemStartMillis <= max && time.slotLengthNumeratorMillis > 0 &&
          time.slotLengthNumeratorMillis <= max && time.slotLengthDenominator > 0 &&
          time.slotLengthDenominator <= max,
        "explicit bounded genesis time geometry required"
      )
      def endpoint(key: Int): Option[BigInt] = body.get(key).map { n =>
        val numerator = unsigned(n) * time.slotLengthNumeratorMillis
        requireShape(
          numerator % time.slotLengthDenominator == 0,
          "fractional POSIX endpoint unsupported"
        )
        val result = time.systemStartMillis + numerator / time.slotLengthDenominator
        requireShape(result <= max, "POSIX endpoint range")
        result
      }
      val datum = consumed.datum.get
      val context = take(
        spendData(
          tx.transactionId,
          ordinary.transactionId,
          ordinary.index,
          consumed.credential,
          consumed.coin,
          datum._1,
          datum._2,
          payout.credential,
          payout.coin,
          unsigned(body(2)),
          endpoint(8),
          endpoint(3)
        )
      )
      Right(
        new Derived(
          context,
          witnesses(5).original,
          byteString(body(11), 32),
          script,
          tx.transactionId
        )
      )
    catch
      case Rejected(error)         => Left(error)
      case _: NullPointerException => Left(Failure.MalformedInput("null context member"))
      case NonFatal(error)         => Left(Failure.InternalFailure(error.getClass.getSimpleName))
