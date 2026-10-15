// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import scala.compiletime.{constValueTuple, summonAll}
import scala.deriving.Mirror
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
  */
object Record:
  /** The codec of `A` as a record, from the codec of each field. */
  inline def of[A](using
      m: Mirror.ProductOf[A]
  )(
      codecs: Tuple.Map[m.MirroredElemTypes, Codec]
  ): Codec[A] =
    build[A](
      m,
      constValueTuple[m.MirroredElemLabels].productIterator.map(_.toString).toList,
      codecs.productIterator.toList
    )

  /** The codec of `A` as a record, with the implicit codec of every field. */
  inline def auto[A](using m: Mirror.ProductOf[A]): Codec[A] =
    of[A](using m)(summonAll[Tuple.Map[m.MirroredElemTypes, Codec]])

  private def build[A](m: Mirror.ProductOf[A], names: List[String], codecs: List[Any]): Codec[A] =
    new Codec[A]:
      private val fields: Vector[Codec[Any]] = codecs.toVector.asInstanceOf[Vector[Codec[Any]]]
      private val labels: Vector[String] = names.toVector
      private val arity: Int = fields.size

      def decode(n: Node): Decoded[A] =
        Arr.exact(n, arity).flatMap { items =>
          val values = new Array[AnyRef](arity)
          @scala.annotation.tailrec
          def go(i: Int): Decoded[A] =
            if i == arity then Right(m.fromProduct(Tuple.fromArray(values)))
            else
              fields(i).decode(items(i)) match
                case Right(a) =>
                  values(i) = a.asInstanceOf[AnyRef]
                  go(i + 1)
                case Left(e) => Left(e.at(labels(i)))
          go(0)
        }

      def encode(a: A, out: Writer): Unit =
        val p = a.asInstanceOf[Product]
        out.arrayHead(arity)
        fields.indices.foreach(i => fields(i).encode(p.productElement(i), out))

      override def check(a: A): Option[ConstraintViolation] =
        val p = a.asInstanceOf[Product]
        fields.indices.iterator
          .flatMap(i => fields(i).check(p.productElement(i)).map(_.at(labels(i))))
          .nextOption()
