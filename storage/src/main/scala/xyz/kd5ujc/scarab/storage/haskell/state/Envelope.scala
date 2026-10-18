// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.Order
import cats.syntax.show.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.consensus.state.{Bound, Current, EraIndexed, Past, Telescope}
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
import xyz.kd5ujc.scarab.protocol.Era

import DecodeError.Kind
import Wire.*

/** The envelopes the consensus layer writes around the ledger state in a `state` file: version
  * numbers, `WithOrigin`, the hard-fork combinator's telescope, and what is kept as it was read.
  * Sources: `ouroboros-consensus` 4.2.1.0 (the pinned tree), each combinator names its file.
  */
object Envelope:
  /** `encodeVersion v` (`Util/Versioned.hs:94-103`): `[v, item]`. A version this decoder does not
    * know, or the older unversioned layout, is an error that names where.
    */
  def versioned[A](version: Int, inner: Codec[A]): Codec[A] = new Codec[A]:
    def decode(n: Node): Decoded[A] =
      Arr.exact(n, 2).flatMap { xs =>
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
  def withOriginMaybe[A](inner: Codec[A]): Codec[WithOrigin[A]] = new Codec[WithOrigin[A]]:
    def decode(n: Node): Decoded[WithOrigin[A]] =
      strictMaybe(inner).decode(n).map(_.fold(WithOrigin.Origin: WithOrigin[A])(WithOrigin.At(_)))
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

  /** `Bound` (`HardFork/History/Summary.hs:526-550`): `[time in picoseconds, slot, epoch]`. The
    * time is an `Integer` (a bignum from 2^64 picoseconds, a few months, on), the slot and the
    * epoch `Word64` s. The four-item form with a Peras round (`PerasEnabled`) is not one of the
    * Cardano eras' and is refused as a wrong length.
    */
  given bound: Codec[Bound] = Record.of[Bound]((integer, bound64, bound64))

  /** `Past` (`State/Instances.hs:141-147`): `[start, end]`. */
  given past: Codec[Past] = Record.of[Past]((bound, bound))

  /** `Current` (`State/Instances.hs:126-132`): `[start, state]`. */
  def current[A](state: Codec[A]): Codec[Current[A]] = new Codec[Current[A]]:
    def decode(n: Node): Decoded[Current[A]] =
      Arr.exact(n, 2).flatMap { xs =>
        for
          start <- bound.decode(xs(0)).at("start")
          s <- state.decode(xs(1)).at("state")
        yield Current(start, s)
      }
    def encode(a: Current[A], out: Writer): Unit =
      out.arrayHead(2)
      bound.encode(a.start, out)
      state.encode(a.state, out)
    override def check(a: Current[A]): Option[ConstraintViolation] =
      state.check(a.state).map(_.at("state"))

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
  def telescope[A](stateOf: Era => Codec[A])(using EraIndexed[A]): Codec[Telescope[A]] =
    new Codec[Telescope[A]]:
      def decode(n: Node): Decoded[Telescope[A]] =
        Arr.between(n, 1, Era.values.length).flatMap { xs =>
          val era = Era.values(xs.size - 1)
          for
            pasts <- Elements.decodeAll(xs.init, past).at("past")
            cur <- current(stateOf(era)).decode(xs.last).at("current")
            t <- Telescope.make(pasts, cur).left.map(refused(n, _))
          yield t
        }
      def encode(a: Telescope[A], out: Writer): Unit =
        out.arrayHead(1 + a.past.size)
        a.past.foreach(past.encode(_, out))
        current(stateOf(a.era)).encode(a.current, out)
      override def check(a: Telescope[A]): Option[ConstraintViolation] =
        current(stateOf(a.era)).check(a.current)

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
