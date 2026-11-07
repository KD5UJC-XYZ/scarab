// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import scala.annotation.tailrec
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node}
import xyz.kd5ujc.scarab.protocol.Era

/** The `RecD` records of an input written as an empty indefinite list with their fields after it,
  * and the decode that reads them as the reference does (#687).
  *
  * Below decoder version 12 the reference reads a `RecD` record's list head with
  * `decodeRecordNamed` around a decoder that reads nothing (`decodeCount (RecD cn) n`,
  * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Coders.hs:482`, cardano-ledger
  * f649f975). An indefinite list's break is checked at once (`decodeListLikeT`,
  * `Decoding/Decoder.hs:690-708`), so for `9f ff` the check passes and the record's fields are read
  * from the items after the break. The reference reads a stream of tokens: each enclosing decoder
  * counts the record as one of its reads, and every span it hashes or measures (a body's bytes, a
  * block body's parts, a `Sized` output) runs over the fields. A tree of items, as [[Cbor.decode]]
  * makes, closes the enclosing definite lists and maps early instead, so such an input is refused.
  *
  * Which empty indefinite lists are such records only the structure the reference's decoders read
  * says: [[Shape]] is that structure along every path of a block or a transaction that reaches a
  * `RecD` record, and [[detached]] walks an input as those decoders would and lists the records it
  * finds. [[read]] decodes and reads an input as before; only when that refuses it, it walks the
  * input, and when the walk finds detached records, decodes it again with them ([[Cbor.Detached]])
  * and reads that, whose verdict stands. An input the ordinary read accepts has none: every `RecD`
  * slot of a block is read by [[CborRead.record]] or by a reader that needs its two or more items,
  * and an empty list is neither, while the tree and the stream agree up to the first detached
  * record. A record of any other shape is refused by [[CborRead.array]], so a wrong walk fails
  * safe.
  */
object DetachedRecords:

  /** An item's head, as a decoder of the reference peeks at it (`peekTokenType`). An unsigned
    * argument above `2^63` is negative here, and is none of the keys or tags a [[Shape]] tests.
    */
  enum Token:
    case Unsigned(value: Long)
    case Negative, ByteString, Text, List, Map, Bool, Null, Other, Break
    case Tag(number: Long)

  /** How a decoder of the reference reads an item, as far as it decides where a `RecD` record is.
    */
  enum Shape:
    /** Any one item, with no record in it. */
    case Item

    /** A `RecD` record of these fields. */
    case Record(fields: List[Shape])

    /** A list read field by field, definite or indefinite (`decodeRecordNamed` over its fields, an
      * output's `decodeListLenOrIndef`): the items after `fields` are read as `rest`.
      */
    case Fields(fields: List[Shape], rest: Shape)

    /** A list of elements, definite or indefinite, after an optional tag 258. */
    case ListOf(element: Shape)

    /** A map of keys and values, definite or indefinite. */
    case MapOf(key: Shape, value: Shape)

    /** A map read by `SparseKeyed`: each key is a `Word` and says how its value is read. */
    case Keyed(value: Long => Shape)

    /** A sum read by `decodeRecordSum`: its first item is a `Word` tag that says how the next ones
      * are read, the items after those as [[Item]].
      */
    case Sum(fields: Long => List[Shape])

    /** A choice made on the item's head (`peekTokenType`). */
    case ByToken(pick: Token => Shape)

  import Shape.*

  /** [[Cbor.decode]] and `reader`, as without this object; when they refuse `raw`, or when an item
    * of it is an empty indefinite list, the decode and the read again of `raw` with the detached
    * records the walk of it as `shape` finds, if it finds any. The first verdict stands when it
    * finds none.
    *
    * The first detached record of an input is, in the tree of its items, an empty indefinite list
    * at the same place, the tree and the stream agreeing up to it. A reader that reads every `RecD`
    * slot refuses it there; one that keeps parts of the input as bytes (a block's transactions) may
    * not, and a list of parts that is indefinite can then hold more parts than the reference reads.
    * An input with no empty indefinite list has no detached record.
    */
  def read[E, A](raw: Bytes, limits: Cbor.Limits, shape: Shape)(syntax: Cbor.Error => E)(
      reader: Node => Either[E, A]
  ): Either[E, A] =
    val first =
      Cbor.decode(raw, limits).leftMap(syntax).flatMap(root => reader(root).tupleRight(root))
    first match
      case Right((accepted, root)) if !root.decodeHasEmptyIndefiniteList => Right(accepted)
      case _ if !holdsEmptyIndefiniteList(raw)                           => first.map(_._1)
      case _ =>
        detached(raw, shape) match
          case Some(records) if !records.isEmpty =>
            Cbor.decode(raw, limits, records).leftMap(syntax).flatMap(reader)
          case _ => first.map(_._1)

  /** Whether the bytes `9f ff` occur anywhere in `raw`: a detached record's head is those bytes, so
    * an input without them has none and is not walked.
    */
  def mayHold(raw: Bytes): Boolean = holdsEmptyIndefiniteList(raw)

  private def holdsEmptyIndefiniteList(raw: Bytes): Boolean =
    @tailrec def from(i: Int): Boolean =
      if i + 1 >= raw.size then false
      else if raw.byteOr(i)(0)(_ & 0xff) == 0x9f && raw.byteOr(i + 1)(0)(_ & 0xff) == 0xff then true
      else from(i + 1)
    from(0)

  /** The detached records of `raw` read as one item of `shape`, as the reference's decoders read
    * them; none when they would fail to read it (a record of the wrong length, a non-empty
    * indefinite record, a break or the end of the input where an item is due, bytes after the
    * item).
    */
  def detached(raw: Bytes, shape: Shape): Option[Cbor.Detached] =
    new Walk(raw).run(shape) match
      case Walked.Item(end, found) if end == raw.size => Some(records(found))
      case _                                          => None

  /** How the first item of an input reads as a [[Shape]], as the reference's decoders read it from
    * a stream that may hold more after it.
    */
  enum Prefix:
    /** The item ends at `end`, holding the detached `records`. */
    case Item(end: Int, records: Cbor.Detached)

    /** The input ends before the item does: a stream decoder waits for more. */
    case Short

    /** The reference's decoders fail on it. */
    case Refused

  /** The first item of `raw` read as `shape`, as [[detached]] reads a whole input, where the item
    * ends and its detached records; for a protocol whose messages carry such an item unwrapped and
    * must find its end as the reference's decoder does (#700).
    */
  def prefix(raw: Bytes, shape: Shape): Prefix =
    new Walk(raw).run(shape) match
      case Walked.Item(end, found) => Prefix.Item(end, records(found))
      case Walked.Short            => Prefix.Short
      case Walked.Refused          => Prefix.Refused

  private def records(found: List[(Int, Int)]): Cbor.Detached =
    val inOrder = found.reverse.toVector
    Cbor.Detached(inOrder.map(_._1), inOrder.map(_._2))

  /** Where a walk stops: after the item, with the records found (the last first), or where the
    * input ends too soon or the reference's decoders fail.
    */
  private enum Walked:
    case Item(end: Int, found: List[(Int, Int)])
    case Short, Refused

  /** What the items of a list or map being walked are read as. */
  private enum Children:
    case Each(shape: Shape)
    case InOrder(fields: List[Shape], rest: Shape)
    case Pairs(key: Shape, value: Shape, atKey: Boolean)
    case Keys(value: Long => Shape, pending: Option[Shape])
    case Constructor(fields: Long => List[Shape])

    /** The shape of the next item, whose head is `token`, and what the items after it are read as;
      * none where the reference fails on that head (a key or a tag that is not a `Word`).
      */
    def next(token: Token): Option[(Shape, Children)] = this match
      case Each(shape)                  => Some((shape, this))
      case InOrder(field :: more, rest) => Some((field, InOrder(more, rest)))
      case InOrder(Nil, rest)           => Some((rest, this))
      case Pairs(key, value, true)      => Some((key, Pairs(key, value, false)))
      case Pairs(key, value, false)     => Some((value, Pairs(key, value, true)))
      case Keys(value, Some(pending))   => Some((pending, Keys(value, None)))
      case Keys(value, None) =>
        token match
          case Token.Unsigned(k) => Some((Item, Keys(value, Some(value(k)))))
          case _                 => None
      case Constructor(fields) =>
        token match
          case Token.Unsigned(t) => Some((Item, InOrder(fields(t), Item)))
          case _                 => None

  /** A list, map or tag being walked: the items still to come (-1 until a break) and what they are
    * read as.
    */
  private final case class Frame(left: Long, children: Children)

  /** An item's head at a position: its token, the bytes of the head (with a definite string's
    * payload), and its items (-1 until a break).
    */
  private final case class Head(token: Token, length: Int, items: Long)

  /** Where the walk is after entering an item: its next position, the frame of the item's items if
    * it has any, and the detached record it is, if it is one.
    */
  private final case class Entered(at: Int, frame: Option[Frame], record: Option[(Int, Int)])

  /** Why the walk cannot go on: [[Walked.Short]] or [[Walked.Refused]]. */
  private type Stop = Walked.Short.type | Walked.Refused.type

  private final class Walk(raw: Bytes):
    private val end = raw.size

    private def byte(at: Int): Int = raw.byteOr(at)(-1)(_ & 0xff)

    /** The argument of the head at `at` with additional information `ai`, and the head's size;
      * short where the input ends inside the head.
      */
    private def argument(at: Int, ai: Int): Either[Stop, (Long, Int)] =
      def wide(width: Int) =
        raw.bigEndian(at + 1, width).map((_, width + 1)).toRight[Stop](Walked.Short)
      ai match
        case n if n < 24 => Right((n.toLong, 1))
        case 24          => wide(1)
        case 25          => wide(2)
        case 26          => wide(4)
        case 27          => wide(8)
        case _           => Left(Walked.Refused)

    /** A string's `n` bytes fit after `at`; short where they do not, which in a whole input is a
      * refusal.
      */
    private def fits(n: Long, at: Int): Either[Stop, Long] =
      if n < 0 then Left(Walked.Refused) else if n <= end - at then Right(n) else Left(Walked.Short)

    /** The count of a list's or a map's items: one that no input of a `Long` 's length holds is
      * short.
      */
    private def count(n: Long): Either[Stop, Long] =
      if n < 0 then Left(Walked.Refused)
      else if n > Long.MaxValue / 4 then Left(Walked.Short)
      else Right(n)

    private def head(at: Int): Either[Stop, Head] =
      if at >= end then Left(Walked.Short)
      else
        val b = byte(at)
        val major = b >>> 5
        val ai = b & 31
        if ai == 31 then
          major match
            case 2 => Right(Head(Token.ByteString, 1, -1))
            case 3 => Right(Head(Token.Text, 1, -1))
            case 4 => Right(Head(Token.List, 1, -1))
            case 5 => Right(Head(Token.Map, 1, -1))
            case 7 => Right(Head(Token.Break, 1, 0))
            case _ => Left(Walked.Refused)
        else
          argument(at, ai).flatMap { (n, size) =>
            major match
              case 0 => Right(Head(Token.Unsigned(n), size, 0))
              case 1 => Right(Head(Token.Negative, size, 0))
              case 2 | 3 =>
                fits(n, at + size).map(payload =>
                  Head(if major == 2 then Token.ByteString else Token.Text, size + payload.toInt, 0)
                )
              // a count is read before the items, so that a shape refuses a record's wrong count
              // whatever follows, as `matchSize` does; the items, when they are read, run out
              case 4 => count(n).map(Head(Token.List, size, _))
              case 5 => count(n).map(m => Head(Token.Map, size, 2 * m))
              case 6 => Right(Head(Token.Tag(n), size, 1))
              case _ =>
                ai match
                  case 20 | 21 => Right(Head(Token.Bool, 1, 0))
                  case 22      => Right(Head(Token.Null, 1, 0))
                  case _       => Right(Head(Token.Other, size, 0))
          }

    /** The end of the item at `from`, read as one item with no record in it, -2 where the input
      * ends inside it, or -1 where the item is not one the reference reads. Every item a walk reads
      * goes through here but the few on the path of a record, so this is its cost on any input: a
      * loop over a stack of the items still to come at each open level (-1 until a break), which
      * allocates eight bytes a level and nothing an item. Walked with a frame an item, a deeply
      * nested refused input made the decoder fuzz gate's blocks and outputs allocate over their
      * bound of 1000 bytes an input byte (`fuzz 100000 1 4`, 2026-11-02, #687).
      */
    @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While"))
    private def skip(from: Int): Int =
      var counts = new Array[Long](8)
      var depth = 1
      counts(0) = 1L
      var at = from
      var whole = true
      var short = false
      def ends(): Unit =
        whole = false
        short = true
      def open(items: Long): Unit =
        if depth == counts.length then counts = java.util.Arrays.copyOf(counts, depth * 2)
        counts(depth) = items
        depth += 1
      while whole && depth > 0 do
        val top = depth - 1
        if counts(top) == 0 then depth -= 1
        else if at >= end then ends()
        else
          val b = byte(at)
          if b == 0xff then
            if counts(top) < 0 then
              at += 1
              depth -= 1
            else whole = false
          else
            if counts(top) > 0 then counts(top) -= 1
            val major = b >>> 5
            val ai = b & 31
            if ai == 31 then
              if major >= 2 && major <= 5 then
                at += 1
                open(-1L)
              else whole = false
            else if ai > 27 then whole = false
            else
              val size = if ai < 24 then 1 else 1 + (1 << (ai - 24))
              val n = if ai < 24 then ai.toLong else raw.bigEndian(at + 1, size - 1).getOrElse(-1L)
              val room = (end - at - size).toLong
              if size > end - at then ends()
              else if n < 0 && major >= 2 && major <= 5 then whole = false
              else
                at += size
                major match
                  case 2 | 3 =>
                    if n > room then ends() else at += n.toInt
                  // a list's or a map's items are read until the input ends: a break or a
                  // reserved head among the ones that have arrived refuses it, whatever follows
                  case 4 => if n > Long.MaxValue / 4 then ends() else open(n)
                  case 5 => if n > Long.MaxValue / 4 then ends() else open(2 * n)
                  case 6 => open(1L)
                  case _ => ()
      if whole then at else if short then -2 else -1

    @tailrec private def resolve(shape: Shape, token: Token): Shape = shape match
      case ByToken(pick) => resolve(pick(token), token)
      case other         => other

    /** Enter the item at `at`, with head `h`, read as `shape`; the stop where the reference fails
      * or the input ends.
      */
    private def enter(shape: Shape, h: Head, at: Int): Either[Stop, Entered] =
      def open(children: Children): Either[Stop, Entered] =
        Right(Entered(at + h.length, Some(Frame(h.items, children)), None))
      (resolve(shape, h.token), h.token) match
        case (Item, _) =>
          skip(at) match
            case -2    => Left(Walked.Short)
            case -1    => Left(Walked.Refused)
            case after => Right(Entered(after, None, None))
        case (Record(fields), Token.List) if h.items == fields.size.toLong =>
          open(Children.InOrder(fields, Item))
        case (Record(_), Token.List) if h.items < 0 && at + 1 >= end => Left(Walked.Short)
        case (Record(fields), Token.List) if h.items < 0 && byte(at + 1) == 0xff =>
          val record = Some((at, fields.size))
          Right(
            Entered(at + 2, Some(Frame(fields.size.toLong, Children.InOrder(fields, Item))), record)
          )
        case (Fields(fields, rest), Token.List) => open(Children.InOrder(fields, rest))
        case (ListOf(element), Token.Tag(258)) =>
          Right(Entered(at + h.length, Some(Frame(1, Children.Each(ListOf(element)))), None))
        case (ListOf(element), Token.List)  => open(Children.Each(element))
        case (MapOf(key, value), Token.Map) => open(Children.Pairs(key, value, true))
        case (Keyed(value), Token.Map)      => open(Children.Keys(value, None))
        case (Sum(fields), Token.List)      => open(Children.Constructor(fields))
        case _                              => Left(Walked.Refused)

    /** The first item of the input read as `shape`. */
    def run(shape: Shape): Walked =
      walk(0, List(Frame(1, Children.Each(shape))), Nil)

    // One step a head or a finished item: the input bounds the steps, and the stack is a list.
    @tailrec private def walk(at: Int, stack: List[Frame], found: List[(Int, Int)]): Walked =
      stack match
        case Nil                       => Walked.Item(at, found)
        case Frame(0L, _) :: enclosing => walk(at, enclosing, found)
        case Frame(left, children) :: enclosing =>
          head(at) match
            case Left(stop) => stop
            case Right(Head(Token.Break, _, _)) =>
              if left < 0 then walk(at + 1, enclosing, found) else Walked.Refused
            case Right(h) =>
              val step = children
                .next(h.token)
                .toRight(Walked.Refused)
                .flatMap((shape, after) =>
                  enter(shape, h, at).map((_, Frame(if left > 0 then left - 1 else left, after)))
                )
              step match
                case Left(stop) => stop
                case Right((entered, parent)) =>
                  walk(
                    entered.at,
                    entered.frame.toList ++ (parent :: enclosing),
                    entered.record.fold(found)(_ :: found)
                  )

  /** The shapes of the reference's decoders below decoder version 12, era by era (cardano-ledger
    * f649f975), along every path of a block or a transaction that reaches a `RecD` record. Every
    * other item is [[Shape.Item]].
    *
    * From version 12, Dijkstra's, every record a block or a transaction holds is read by
    * `decodeRecordNamed` around its fields, so none is detached: `ifDecoderVersionAtLeast
    * (natVersion
    * @12)`
    *   picks that reader for `ValidityInterval` (`Allegra/Scripts.hs:166`), `MaryValue`
    *   (`Mary/Value.hs:312`), the list form of auxiliary data (`Alonzo/TxAuxData.hs:220`),
    *   `ExUnits` and `Prices` (`Plutus/ExUnits.hs:210, 237`), `Anchor` (`BaseTypes.hs:1005`), and
    *   `GovActionId`, `VotingProcedure`, `ProposalProcedure` and `Constitution`
    *   (`Conway/Governance/Procedures.hs:183, 497, 569, 1046`), all cardano-ledger 9df9f98c, and
    *   Dijkstra's own transaction decoders use no `RecD`. So an era read at version 12 has the
    *   shape [[Shape.Item]] throughout.
    */
  object Shapes:
    private def since(era: Era, first: Era): Boolean = era.ordinal >= first.ordinal

    /** Whether the era's decoder version reads any `RecD` record of a block: below 12. */
    private def recorded(era: Era): Boolean = !DecoderVersion.of(era).atLeast(12)

    /** `decodeNullStrictMaybe`. */
    private def nullable(shape: Shape): Shape = ByToken {
      case Token.Null => Item
      case _          => shape
    }

    /** A `RecD` record of two fields that hold no record: `ExUnits` and `Prices`
      * (`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/ExUnits.hs:195-231`), `Anchor`
      * (`BaseTypes.hs:996-1004`), `GovActionId` (`Conway/Governance/Procedures.hs:182-190`).
      */
    private val pair: Shape = Record(List(Item, Item))

    /** `GovActionId`, a `RecD` record of a transaction id and an index
      * (`Conway/Governance/Procedures.hs:182-190`), as a node-to-client query reads it too (#700).
      */
    val actionId: Shape = pair

    /** `decodeMaryValue` (`eras/mary/impl/src/Cardano/Ledger/Mary/Value.hs:286-306`): a coin, or
      * from Mary a `RecD` record of a coin and a multi-asset.
      */
    private def value(era: Era): Shape =
      if since(era, Era.Mary) then
        ByToken {
          case Token.List => pair
          case _          => Item
        }
      else Item

    /** An output: a list of the address, the value and from Alonzo the datum hash
      * (`decodeListLenOrIndef`, `Alonzo/TxOut.hs:388-411`, `Babbage/TxOut.hs:549-579`), or from
      * Babbage a map whose key 1 is the value (`SparseKeyed "TxOut"`, `Babbage/TxOut.hs:605-640`).
      */
    def output(era: Era): Shape =
      val list = Fields(List(Item, value(era)), Item)
      if since(era, Era.Babbage) then
        ByToken {
          case Token.Map => Keyed(key => if key == 1 then value(era) else Item)
          case _         => list
        }
      else list

    /** An output the stores keep, of any era: the forms of the latest. */
    val storedOutput: Shape = output(Era.Conway)

    /** A `PParamsUpdate` (`SparseKeyed`, `Core/PParams.hs:257-293`): from Alonzo its prices (19)
      * and execution units (20, 21) are `RecD` records.
      */
    private def parameterUpdate(era: Era): Shape =
      if since(era, Era.Alonzo) then Keyed(key => if key >= 19 && key <= 21 then pair else Item)
      else Item

    /** `Update` (`Shelley/PParams.hs:267`): the proposed updates by genesis key, and the epoch. */
    private def update(era: Era): Shape =
      Record(List(MapOf(Item, parameterUpdate(era)), Item))

    private val votingProcedure: Shape = Record(List(Item, nullable(pair)))
    private val constitution: Shape = Record(List(pair, Item))

    /** `GovAction` (`Summands "GovAction"`, `Procedures.hs:876-896`): the actions that name a
      * previous action, a parameter change's update, a new constitution.
      */
    private val governanceAction: Shape = Sum {
      case 0         => List(nullable(pair), parameterUpdate(Era.Conway))
      case 1 | 3 | 4 => List(nullable(pair))
      case 5         => List(nullable(pair), constitution)
      case _         => Nil
    }

    private val proposal: Shape = Record(List(Item, Item, governanceAction, pair))

    /** Conway's certificates that carry an anchor (`Conway/TxCert.hs:702-718`). */
    private val certificate: Shape = Sum {
      case 15 | 18 => List(Item, nullable(pair))
      case 16      => List(Item, Item, nullable(pair))
      case _       => Nil
    }

    /** A body (`SparseKeyed` over the era's `bodyFields`). */
    def body(era: Era): Shape =
      val conway = since(era, Era.Conway)
      if !recorded(era) then Item
      else
        Keyed {
          case 1                             => ListOf(output(era))
          case 4 if conway                   => ListOf(certificate)
          case 6 if !conway                  => update(era)
          case 16 if since(era, Era.Babbage) => output(era)
          case 19 if conway                  => MapOf(Item, MapOf(pair, votingProcedure))
          case 20 if conway                  => ListOf(proposal)
          case _                             => Item
        }

    /** A redeemer of the list form (`decodeRecordNamed "Redeemer"` over its fields,
      * `Alonzo/TxWits.hs:588-597`).
      */
    private val redeemer: Shape = Fields(List(Item, Item, Item, pair), Item)

    /** A witness set: from Alonzo its redeemers (5), a list, and from Conway (decoder version 9) a
      * map of purpose to data and units (`Alonzo/TxWits.hs:549-597`).
      */
    def witnesses(era: Era): Shape =
      if !recorded(era) then Item
      else witnessesBelow12(era)

    private def witnessesBelow12(era: Era): Shape =
      val redeemers =
        if since(era, Era.Conway) then
          ByToken {
            case Token.Map => MapOf(Item, Fields(List(Item, pair), Item))
            case _         => ListOf(redeemer)
          }
        else ListOf(redeemer)
      if since(era, Era.Alonzo) then Keyed(key => if key == 5 then redeemers else Item) else Item

    /** Auxiliary data: from Allegra a list of the metadata and the scripts is a `RecD` record
      * (`Allegra/TxAuxData.hs:185-210`, `Alonzo/TxAuxData.hs:215-262`).
      */
    def auxiliaryData(era: Era): Shape = auxiliaryDataAt(DecoderVersion.of(era).value)

    /** [[auxiliaryData]] at a decoder version: Allegra's is 3. */
    def auxiliaryDataAt(version: BigInt): Shape =
      if version >= 3 && version < 12 then
        ByToken {
          case Token.List => pair
          case _          => Item
        }
      else Item

    /** A whole transaction: from Alonzo `decodeRecordNamed "AlonzoTx"` over the body, the witness
      * set, an optional validity flag and the auxiliary data or null (`Alonzo/Tx.hs:532-553`);
      * before, the `RecD` record `ShelleyTx` (`Shelley/Tx.hs:184-192`).
      */
    def transaction(era: Era): Shape =
      if !recorded(era) then Item
      else if since(era, Era.Alonzo) then
        Fields(
          List(body(era), witnesses(era)),
          ByToken {
            case Token.Bool | Token.Null => Item
            case _                       => auxiliaryData(era)
          }
        )
      else Record(List(body(era), witnesses(era), nullable(auxiliaryData(era))))

    /** A header of a Shelley-based era: from Babbage the Praos `HeaderRaw` and its `HeaderBody` are
      * `RecD` records (`libs/cardano-protocol/src/Cardano/Protocol/Praos/BlockHeader.hs:203-229`),
      * the body's operational certificate and protocol version lists read by `decodeRecordNamed`
      * and so not records; a TPraos header to Alonzo holds none (`TPraos/BlockHeader.hs:193-241`).
      */
    def header(era: Era): Shape =
      if !recorded(era) then Item
      else if since(era, Era.Babbage) then Record(List(Record(List.fill(10)(Item)), Item))
      else Item

    /** A block as BlockFetch carries it, `[era tag, block]`: a block of a Shelley-based era is its
      * [[header]], then the bodies, the witness sets, the auxiliary data by index and the invalid
      * transactions (`Block.hs:104-115`, `Alonzo/BlockBody/Internal.hs:215-240`).
      */
    val block: Shape = Sum(tag =>
      // A Dijkstra block, `[header, [txs, leios_cert / nil, peras_cert / nil]]`, is read at decoder
      // version 12, where it holds no `RecD` record (above); a Byron block holds none either
      Era.fromBlockTag(BigInt(tag)).filter(e => e != Era.Byron && recorded(e)) match
        case Some(era) =>
          List(
            Fields(
              List(
                header(era),
                ListOf(body(era)),
                ListOf(witnesses(era)),
                MapOf(Item, auxiliaryData(era))
              ),
              Item
            )
          )
        case None => Nil
    )
