// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import scala.compiletime.{constValue, constValueTuple, summonAll, summonInline}
import scala.deriving.Mirror
import xyz.kd5ujc.scarab.storage.ProductFields
import xyz.kd5ujc.scarab.models.runtime.{Arr, Codec, ConstraintViolation, Decoded, Node, Writer}

/** The Haskell ledger writes a record as a definite array of its fields in declaration order (`Rec`
  * in `Cardano.Ledger.Binary.Coders`, or `encodeListLen n <> ...` by hand). The mirror's records
  * are declared in that order, so one codec shape serves them all, taken from the compiler's
  * `Mirror` of the case class: the arity, the field names (for the path of a decode error) and the
  * way to build and take apart a value.
  *
  * `Record.of[A]((c1, c2, ...))` takes one codec per field, so that a field that is encoded in a
  * particular way (a unit interval, a nullable, a set) says so at the use site; `Record.auto[A]`
  * summons the codec of every field.
  *
  * The codec tuple and field chain retain the field types. Each generated accessor selects the
  * declared field from A at its original type, so encoding/checking needs neither a transient tuple
  * nor runtime field recovery. Decoding builds the same typed tuple in declaration order.
  */
object Record:
  /** The codec of `A` as a record, from the codec of each field. */
  inline def of[A <: Product](using
      m: Mirror.ProductOf[A]
  )(
      codecs: Tuple.Map[m.MirroredElemTypes, Codec]
  ): Codec[A] =
    val labels = constValueTuple[m.MirroredElemLabels].productIterator.map(_.toString).toVector
    val fields = summonInline[Fields[A, m.MirroredElemTypes, m.MirroredElemLabels]]
    val arity = fields.arity
    make[A](
      arity,
      (a, out) => fields.encode(a, codecs, out),
      n =>
        Arr.exact(n, arity).flatMap { items =>
          fields.decode(codecs, items, 0, labels).map(m.fromProduct)
        },
      a => fields.check(a, codecs, 0, labels)
    )

  /** The codec of `A` as a record, with the implicit codec of every field. */
  inline def auto[A <: Product](using m: Mirror.ProductOf[A]): Codec[A] =
    of[A](using m)(summonAll[Tuple.Map[m.MirroredElemTypes, Codec]])

  /** A record codec from its parts: the instance is made here, not at the inline site, so a
    * derivation does not duplicate an anonymous class.
    */
  private def make[A](
      arity: Int,
      encodeFn: (A, Writer) => Unit,
      decodeFn: Node => Decoded[A],
      checkFn: A => Option[ConstraintViolation]
  ): Codec[A] = new Codec[A]:
    def encode(a: A, out: Writer): Unit =
      out.arrayHead(arity)
      encodeFn(a, out)
    def decode(n: Node): Decoded[A] = decodeFn(n)
    override def check(a: A): Option[ConstraintViolation] = checkFn(a)

  /** The codecs of a record's fields, one per field, in declaration order: the typed walk over the
    * codec tuple and the record's value tuple (both indexed by the field types), so that each codec
    * is applied at the type of the field it belongs to.
    *
    * The instances are resolved when [[Record.of]] is inlined, where the field types are known.
    */
  private[scarab] sealed trait Fields[A, Vs <: Tuple, Ls <: Tuple]:
    def arity: Int

    /** Encode the fields of `value` with their codecs, in declaration order. */
    def encode(value: A, codecs: Tuple.Map[Vs, Codec], out: Writer): Unit

    /** Decode the field at `index` and the fields after it, as a tuple in field order. */
    def decode(
        codecs: Tuple.Map[Vs, Codec],
        nodes: Vector[Node],
        index: Int,
        labels: Vector[String]
    ): Decoded[Vs]

    /** The first field, at `index` or after it, that violates its codec's constraint. */
    def check(
        value: A,
        codecs: Tuple.Map[Vs, Codec],
        index: Int,
        labels: Vector[String]
    ): Option[ConstraintViolation]

  private[scarab] final class NoFields[A] extends Fields[A, EmptyTuple.type, EmptyTuple.type]:
    val arity: Int = 0
    def encode(
        value: A,
        codecs: Tuple.Map[EmptyTuple.type, Codec],
        out: Writer
    ): Unit = ()
    def decode(
        codecs: Tuple.Map[EmptyTuple.type, Codec],
        nodes: Vector[Node],
        index: Int,
        labels: Vector[String]
    ): Decoded[EmptyTuple.type] = Right(EmptyTuple)
    def check(
        value: A,
        codecs: Tuple.Map[EmptyTuple.type, Codec],
        index: Int,
        labels: Vector[String]
    ): Option[ConstraintViolation] = None

  private[scarab] final class OneField[A, T, Ts <: Tuple, L <: String, Ls <: Tuple](
      get: A => T,
      tail: Fields[A, Ts, Ls]
  ) extends Fields[A, T *: Ts, L *: Ls]:
    val arity: Int = 1 + tail.arity

    def encode(value: A, codecs: Tuple.Map[T *: Ts, Codec], out: Writer): Unit =
      codecs.head.encode(get(value), out)
      tail.encode(value, codecs.tail, out)

    def decode(
        codecs: Tuple.Map[T *: Ts, Codec],
        nodes: Vector[Node],
        index: Int,
        labels: Vector[String]
    ): Decoded[T *: Ts] =
      codecs.head.decode(nodes(index)) match
        case Right(value) => tail.decode(codecs.tail, nodes, index + 1, labels).map(value *: _)
        case Left(error)  => Left(error.at(labels(index)))

    def check(
        value: A,
        codecs: Tuple.Map[T *: Ts, Codec],
        index: Int,
        labels: Vector[String]
    ): Option[ConstraintViolation] =
      codecs.head
        .check(get(value))
        .map(_.at(labels(index)))
        .orElse(tail.check(value, codecs.tail, index + 1, labels))
  private[scarab] final class TwoFields[A, T, U, L <: String, M <: String](
      first: A => T,
      second: A => U
  ) extends Fields[A, T *: U *: EmptyTuple, L *: M *: EmptyTuple]:
    val arity: Int = 2
    def encode(value: A, codecs: Tuple.Map[T *: U *: EmptyTuple, Codec], out: Writer): Unit =
      codecs.head.encode(first(value), out)
      codecs.tail.head.encode(second(value), out)
    def decode(
        codecs: Tuple.Map[T *: U *: EmptyTuple, Codec],
        nodes: Vector[Node],
        index: Int,
        labels: Vector[String]
    ): Decoded[(T, U)] =
      codecs.head.decode(nodes(index)).left.map(_.at(labels(index))).flatMap { one =>
        codecs.tail.head
          .decode(nodes(index + 1))
          .left
          .map(_.at(labels(index + 1)))
          .map(two => (one, two))
      }
    def check(
        value: A,
        codecs: Tuple.Map[T *: U *: EmptyTuple, Codec],
        index: Int,
        labels: Vector[String]
    ): Option[ConstraintViolation] =
      codecs.head
        .check(first(value))
        .map(_.at(labels(index)))
        .orElse(codecs.tail.head.check(second(value)).map(_.at(labels(index + 1))))

  private[scarab] given noFields[A]: Fields[A, EmptyTuple.type, EmptyTuple.type] = new NoFields[A]

  private[scarab] inline given oneField[A <: Product, T, L <: String]
      : Fields[A, T *: EmptyTuple, L *: EmptyTuple] =
    new OneField[A, T, EmptyTuple, L, EmptyTuple](
      ProductFields.named[A, T](constValue[L]),
      noFields[A]
    )

  private[scarab] inline given twoFields[A <: Product, T, U, L <: String, M <: String]
      : Fields[A, T *: U *: EmptyTuple, L *: M *: EmptyTuple] =
    new TwoFields[A, T, U, L, M](
      ProductFields.named[A, T](constValue[L]),
      ProductFields.named[A, U](constValue[M])
    )

  private[scarab] inline given manyFields[
      A <: Product,
      T,
      U,
      V,
      Ts <: Tuple,
      L <: String,
      M <: String,
      N <: String,
      Ls <: Tuple
  ](using
      tail: Fields[A, U *: V *: Ts, M *: N *: Ls]
  ): Fields[A, T *: U *: V *: Ts, L *: M *: N *: Ls] =
    new OneField[A, T, U *: V *: Ts, L, M *: N *: Ls](
      ProductFields.named[A, T](constValue[L]),
      tail
    )
