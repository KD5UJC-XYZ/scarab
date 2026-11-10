// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.Order
import scala.collection.immutable.SortedMap
import cats.syntax.traverse.*
import xyz.kd5ujc.scarab.consensus.state.{Bound, Current, EraIndexed, Past, PerasRoundNo, Telescope}
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, Word64, WithOrigin}
import xyz.kd5ujc.scarab.models.runtime.{
  Arr,
  Codec,
  ConstraintViolation,
  DecodeError,
  Decoded,
  Lit,
  Node,
  Writer,
  at
}
import xyz.kd5ujc.scarab.protocol.{DijkstraNotYet, Era, StateLayout}

import DecodeError.Kind
import Wire.*

/** The envelopes the consensus layer writes around the ledger state in a `state` file: version
  * numbers, `WithOrigin`, the hard-fork combinator's telescope, and what is kept as it was read.
  * Sources: `ouroboros-consensus` 4.2.1.0 (the pinned tree), each combinator names its file.
  */
object Envelope:
  /** Consensus array framing in the selected layout; ledger record framing is independent. */
  def arrayNode(layout: StateLayout, n: Node): Decoded[Node] =
    layout match
      case StateLayout.Node11_1 | StateLayout.Leios =>
        if n.isIndefinite then Left(Codec.mismatch("definite array", n))
        else Right(n)

  def arrayIn[A](layout: StateLayout, inner: Codec[A]): Codec[A] = new Codec[A]:
    def decode(n: Node): Decoded[A] = arrayNode(layout, n).flatMap(inner.decode)
    def encode(a: A, out: Writer): Unit = inner.encode(a, out)
    override def check(a: A): Option[ConstraintViolation] = inner.check(a)

  /** `encodeVersion v` (`Util/Versioned.hs:94-103`): `[v, item]`. A version this decoder does not
    * know, or the older unversioned layout, is an error that names where.
    */
  def versioned[A](version: Int, inner: Codec[A]): Codec[A] =
    versionedIn(StateLayout.Node11_1)(version, inner)

  def versionedIn[A](layout: StateLayout)(version: Int, inner: Codec[A]): Codec[A] = new Codec[A]:
    def decode(n: Node): Decoded[A] =
      arrayNode(layout, n).flatMap(Arr.exact(_, 2)).flatMap { xs =>
        for
          _ <- Lit.uint(xs(0), version.toLong).at("version")
          a <- inner.decode(xs(1))
        yield a
      }
    def encode(a: A, out: Writer): Unit =
      out.arrayHead(2)
      out.uint(version.toLong)
      inner.encode(a, out)
    override def check(a: A): Option[ConstraintViolation] = inner.check(a)

  /** `WithOrigin` as `encodeWithOrigin` writes it (`Util/CBOR.hs:344-345`, over `Maybe`): `[]` or
    * `[x]`. The ledger tip and the header state's tip.
    */
  def withOriginMaybe[A](
      inner: Codec[A],
      layout: StateLayout = StateLayout.Node11_1
  ): Codec[WithOrigin[A]] = new Codec[WithOrigin[A]]:
    def decode(n: Node): Decoded[WithOrigin[A]] =
      arrayNode(layout, n)
        .flatMap(strictMaybe(inner).decode)
        .map(_.fold(WithOrigin.Origin: WithOrigin[A])(WithOrigin.At(_)))
    def encode(a: WithOrigin[A], out: Writer): Unit = a match
      case WithOrigin.Origin => out.arrayHead(0)
      case WithOrigin.At(x) =>
        out.arrayHead(1)
        inner.encode(x, out)
    override def check(a: WithOrigin[A]): Option[ConstraintViolation] = a match
      case WithOrigin.Origin => None
      case WithOrigin.At(x)  => inner.check(x)

  /** `WithOrigin` as its `Serialise` instance writes it (`Slot.hs:84-88`, generic): `[0]` or
    * `[1, x]`. The last slot of the protocol states.
    */
  def withOriginTagged[A](inner: Codec[A]): Codec[WithOrigin[A]] = new Codec[WithOrigin[A]]:
    def decode(n: Node): Decoded[WithOrigin[A]] =
      variant(n).flatMap {
        case (t, Vector()) if t == 0  => Right(WithOrigin.Origin)
        case (t, Vector(x)) if t == 1 => inner.decode(x).map(WithOrigin.At(_)).at("slot")
        case (t, xs)                  => unknownVariant(n, "WithOrigin", t, xs.size)
      }
    def encode(a: WithOrigin[A], out: Writer): Unit = a match
      case WithOrigin.Origin =>
        out.arrayHead(1)
        out.uint(0L)
      case WithOrigin.At(x) =>
        out.arrayHead(2)
        out.uint(1L)
        inner.encode(x, out)
    override def check(a: WithOrigin[A]): Option[ConstraintViolation] = a match
      case WithOrigin.Origin => None
      case WithOrigin.At(x)  => inner.check(x)

  /** A map the plain (`cardano-binary`) encoder writes: always a definite length, where the
    * ledger's `encodeMap` is indefinite past 23 entries (`mapOf`). Read in either form.
    */
  def plainMapOf[K, V](key: Codec[K], value: Codec[V])(using
      Order[K]
  ): Codec[SortedMap[K, V]] =
    val base = mapOf(key, value)(using summon, ProtVer(Word64.fromUnsigned(2), Word64.zero))
    new Codec[SortedMap[K, V]]:
      def decode(n: Node): Decoded[SortedMap[K, V]] = base.decode(n)
      def encode(a: SortedMap[K, V], out: Writer): Unit =
        out.mapHead(a.size)
        a.foreach { (k, x) =>
          key.encode(k, out)
          value.encode(x, out)
        }
      override def check(a: SortedMap[K, V]): Option[ConstraintViolation] = base.check(a)

  /** An item kept as it was read, for what the mirror does not model: the bytes of one complete
    * CBOR item, written back as they were.
    */
  val originalItem: Codec[xyz.kd5ujc.scarab.codec.Bytes] =
    new Codec[xyz.kd5ujc.scarab.codec.Bytes]:
      def decode(n: Node): Decoded[xyz.kd5ujc.scarab.codec.Bytes] = Right(n.original)
      def encode(a: xyz.kd5ujc.scarab.codec.Bytes, out: Writer): Unit = out.raw(a)

  // ---- the hard-fork combinator's telescope

  private val word64Max = (BigInt(1) << 64) - 1
  private val bound64: Codec[BigInt] = Codec.uintBig(word64Max)

  /** `PerasRoundNo` (`Block/SupportsPeras.hs:85`, a `Word64`). */
  private val boundPerasRound: Codec[PerasRoundNo] = word64.imap(PerasRoundNo(_))(_.word)

  /** `Bound` in `layout`: `[time in picoseconds, slot, epoch]` and the Peras round. The time is an
    * `Integer` (a bignum from 2^64 picoseconds, a few months, on), the slot, the epoch and the
    * round `Word64` s. Both decoders take three or four items and refuse any other length:
    *   - 11.1 (ouroboros-consensus 82ecba32 `HardFork/History/Summary.hs:526-549`): the round is
    *     `NoPerasEnabled` with three items and `PerasEnabled` with four, and written so;
    *   - Leios (2416493b `Summary.hs:573-601`): three items are round zero; four are always
    *     written.
    */
  def bound(layout: StateLayout): Codec[Bound] = new Codec[Bound]:
    def decode(n: Node): Decoded[Bound] =
      arrayNode(layout, n).flatMap(Arr.between(_, 3, 4)).flatMap { xs =>
        for
          time <- integer.decode(xs(0)).at("boundTime")
          slot <- bound64.decode(xs(1)).at("boundSlot")
          epoch <- bound64.decode(xs(2)).at("boundEpoch")
          round <- xs.lift(3).traverse(boundPerasRound.decode(_).at("boundPerasRound"))
        yield Bound(
          time,
          slot,
          epoch,
          layout match
            case StateLayout.Node11_1 => Bound.Layout.Node11_1(round)
            case StateLayout.Leios =>
              Bound.Layout.Leios(round.getOrElse(PerasRoundNo(Word64.zero)))
        )
      }
    def encode(a: Bound, out: Writer): Unit =
      val round = a.layout match
        case Bound.Layout.Node11_1(r) => r
        case Bound.Layout.Leios(r)    => Some(r)
      out.arrayHead(if round.isDefined then 4 else 3)
      integer.encode(a.timePs, out)
      bound64.encode(a.slot, out)
      bound64.encode(a.epoch, out)
      round.foreach(boundPerasRound.encode(_, out))
    override def check(a: Bound): Option[ConstraintViolation] =
      Option
        .when(a.layout.stateLayout != layout)(
          ConstraintViolation(
            "Bound",
            s"a part of the $layout layout",
            a.layout.stateLayout.toString
          )
        )
        .orElse(integer.check(a.timePs))
        .orElse(bound64.check(a.slot))
        .orElse(bound64.check(a.epoch))

  /** `Past` (`State/Instances.hs:141-147`): `[start, end]`. */
  def past(layout: StateLayout): Codec[Past] =
    arrayIn(layout, Record.of[Past]((bound(layout), bound(layout))))

  /** `Current` (`State/Instances.hs:126-132`): `[start, state]`. */
  def current[A](layout: StateLayout, state: Codec[A]): Codec[Current[A]] = new Codec[Current[A]]:
    private val start = bound(layout)
    def decode(n: Node): Decoded[Current[A]] =
      arrayNode(layout, n).flatMap(Arr.exact(_, 2)).flatMap { xs =>
        for
          s0 <- start.decode(xs(0)).at("start")
          s <- state.decode(xs(1)).at("state")
        yield Current(s0, s)
      }
    def encode(a: Current[A], out: Writer): Unit =
      out.arrayHead(2)
      start.encode(a.start, out)
      state.encode(a.state, out)
    override def check(a: Current[A]): Option[ConstraintViolation] =
      start.check(a.start).map(_.at("start")).orElse(state.check(a.state).map(_.at("state")))

  /** A telescope that [[Telescope.make]] refused, as the violation it is. */
  private def refused(n: Node, e: xyz.kd5ujc.scarab.consensus.state.TelescopeError): DecodeError =
    DecodeError.at(
      n,
      Kind.Domain(e)
    )

  /** `HardForkState` (`Serialisation/Common.hs:371-390`): an array of one `Past` per finished era
    * and then the `Current`, `1 + era index` items; the era of the current state, and with it the
    * codec of its state, is the length. The telescope is built with [[Telescope.make]], so a
    * decoded one is consistent (the eras abut, the state is one of the era it is in).
    */
  def telescope[A](layout: StateLayout, stateOf: Era => Codec[A])(using
      EraIndexed[A]
  ): Codec[Telescope[A]] =
    new Codec[Telescope[A]]:
      private val pastIn = past(layout)
      def decode(n: Node): Decoded[Telescope[A]] =
        arrayNode(layout, n).flatMap(Arr.nonEmpty(_, Era.values.length)).flatMap { xs =>
          val era = Era.values(xs.length - 1)
          for
            // a telescope in Dijkstra holds states this build does not read yet
            _ <- Either.cond(
              era != Era.Dijkstra,
              (),
              DecodeError.at(n, Kind.Domain(DijkstraNotYet.LedgerState))
            )
            pasts <- Elements.decodeAll(xs.init, pastIn).at("past")
            cur <- current(layout, stateOf(era)).decode(xs.last).at("current")
            t <- Telescope.make(pasts, cur).left.map(refused(n, _))
          yield t
        }
      def encode(a: Telescope[A], out: Writer): Unit =
        out.arrayHead(1 + a.past.size)
        a.past.foreach(pastIn.encode(_, out))
        current(layout, stateOf(a.era)).encode(a.current, out)
      override def check(a: Telescope[A]): Option[ConstraintViolation] =
        a.past.iterator
          .flatMap(pastIn.check)
          .nextOption()
          .map(_.at("past"))
          .orElse(current(layout, stateOf(a.era)).check(a.current))

  /** The codec of the state of an era in a telescope: read by what the era says it is (`reader`
    * gets the era), written by what the value is.
    */
  def eraCodec[A](
      reader: Era => Node => Decoded[A],
      writer: (A, Writer) => Unit,
      checker: A => Option[ConstraintViolation]
  ): Era => Codec[A] = era =>
    new Codec[A]:
      private val read = reader(era)
      def decode(n: Node): Decoded[A] = read(n)
      def encode(a: A, out: Writer): Unit = writer(a, out)
      override def check(a: A): Option[ConstraintViolation] = checker(a)
