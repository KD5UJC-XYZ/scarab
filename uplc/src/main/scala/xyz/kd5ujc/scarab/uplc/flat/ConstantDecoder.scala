// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.flat

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.uplc.{Availability, Constant, DefaultUni, PlutusData, Text, Value}

/** The constants of a Flat program: a type ([[TypeHeader]]) and a value of it, read as
  * `decodeDefaultUniValue` reads it (`PlutusCore/Default/Universe.hs:166-186`, plutus 1.70.0.0
  * `507cab8c`).
  *
  * A value is as deep as its type, which the ledger does not bound before protocol 11, so the value
  * is read by a machine that keeps the lists and pairs still open on the heap (ADR 0011). A list or
  * an array is a continuation bit before each element (`decodeListWith`,
  * `PlutusCore/Flat/Decoder/Strict.hs:60-88`); when its first element took no bits (a type made of
  * `unit` s), the reference repeats that element for each further bit, and so does this.
  */
object ConstantDecoder:

  /** The constant at `at` (`Flat.hs:148-156`): its type, refused unless it has kind `*`, then by
    * `admit` (the ledger's bound on its size), then its value.
    */
  def read(
      input: Input,
      at: Long,
      admit: DefaultUni => Either[Availability.Refusal, Unit]
  ): Either[FlatError, Got[Constant]] =
    TypeHeader.read(input, at).flatMap { case Got(uni, after) =>
      admit(uni) match
        case Left(refusal) => Left(FlatError.Refused(refusal))
        case Right(_)      => value(input, after, uni)
    }

  // A list, array or pair whose value is still being read.
  private enum Open:
    /** The elements read so far of a list (or an array), the latest last. */
    case Items(element: DefaultUni, read: Vector[Constant], array: Boolean, start: Long)
    case First(second: DefaultUni)
    case Second(first: Constant)

  private enum Step:
    case Read(uni: DefaultUni, at: Long)
    case Give(constant: Constant, at: Long)

  /** The value of type `uni` at `at`. */
  def value(input: Input, at: Long, uni: DefaultUni): Either[FlatError, Got[Constant]] =
    @tailrec def go(step: Step, open: List[Open]): Either[FlatError, Got[Constant]] = step match
      case Step.Read(DefaultUni.List(e), from) =>
        input.bit(from) match
          case Left(err)                => Left(err)
          case Right(Got(true, next))   => go(Step.Read(e, next), Open.Items(e, Vector.empty, false, next) :: open)
          case Right(Got(false, next))  => go(Step.Give(Constant.List.empty(e), next), open)
      case Step.Read(DefaultUni.Array(e), from) =>
        input.bit(from) match
          case Left(err)               => Left(err)
          case Right(Got(true, next))  => go(Step.Read(e, next), Open.Items(e, Vector.empty, true, next) :: open)
          case Right(Got(false, next)) => go(Step.Give(Constant.List.empty(e).toArray, next), open)
      case Step.Read(DefaultUni.Pair(a, b), from) => go(Step.Read(a, from), Open.First(b) :: open)
      case Step.Read(leafType, from) =>
        leaf(input, from, leafType) match
          case Left(err)                => Left(err)
          case Right(Got(c, next))      => go(Step.Give(c, next), open)
      case Step.Give(c, from) =>
        open match
          case Nil                          => Right(Got(c, from))
          case Open.First(b) :: below       => go(Step.Read(b, from), Open.Second(c) :: below)
          case Open.Second(first) :: below  => go(Step.Give(Constant.Pair(first, c), from), below)
          case Open.Items(e, read, array, start) :: below =>
            // `goZero` (`Strict.hs:76-86`): an element of no bits is the same for every bit.
            if read.isEmpty && from == start then
              repeated(input, from, c) match
                case Left(err) => Left(err)
                case Right(Got(all, next)) =>
                  items(e, all, array) match
                    case Left(err)   => Left(err)
                    case Right(made) => go(Step.Give(made, next), below)
            else
              input.bit(from) match
                case Left(err) => Left(err)
                case Right(Got(true, next)) =>
                  go(Step.Read(e, next), Open.Items(e, read :+ c, array, start) :: below)
                case Right(Got(false, next)) =>
                  items(e, read :+ c, array) match
                    case Left(err)   => Left(err)
                    case Right(made) => go(Step.Give(made, next), below)
    go(Step.Read(uni, at), Nil)

  // `c` once and again for each set bit up to a clear one.
  private def repeated(input: Input, at: Long, c: Constant): Either[FlatError, Got[Vector[Constant]]] =
    @tailrec def go(from: Long, count: Int): Either[FlatError, Got[Vector[Constant]]] =
      input.bit(from) match
        case Left(err)               => Left(err)
        case Right(Got(true, next))  => go(next, count + 1)
        case Right(Got(false, next)) => Right(Got(Vector.fill(count)(c), next))
    go(at, 1)

  private def items(
      element: DefaultUni,
      read: Vector[Constant],
      array: Boolean
  ): Either[FlatError, Constant] =
    val list = Constant.List.of(element, read).left.map(FlatError.IllTypedElements(_))
    if array then list.map(_.toArray) else list

  /** A value of a type that is not a list, an array or a pair (`compile other`,
    * `Universe.hs:186`): the type's own Flat decoder.
    */
  private def leaf(input: Input, at: Long, uni: DefaultUni): Either[FlatError, Got[Constant]] =
    uni match
      case DefaultUni.Integer    => input.integer(at).map(g => Got(Constant.Integer(g.value), g.at))
      case DefaultUni.ByteString => input.byteString(at).map(g => Got(Constant.ByteString(g.value), g.at))
      case DefaultUni.String =>
        input.byteString(at).flatMap { case Got(raw, next) =>
          Text
            .fromUtf8(raw)
            .left
            .map(_ => FlatError.InvalidUtf8(at))
            .map(text => Got(Constant.String(text), next))
        }
      case DefaultUni.Unit => Right(Got(Constant.Unit, at))
      case DefaultUni.Bool => input.bit(at).map(g => Got(Constant.Bool(g.value), g.at))
      case DefaultUni.Data =>
        input.byteString(at).flatMap { case Got(raw, next) =>
          PlutusData
            .decodePrefix(raw)
            .left
            .map(FlatError.MalformedData(_))
            .map(d => Got(Constant.Data(d), next))
        }
      case DefaultUni.Value => valueOf(input, at).map(g => Got(Constant.Value(g.value), g.at))
      case other            => Left(FlatError.NoFlatValue(other))

  private type Entries = Vector[(Value.Key, Vector[(Value.Key, Value.Quantity)])]

  /** A `value` constant (`Value.hs:223-232`): a list of currencies, each a key and a list of tokens,
    * each a key and an amount. A key is refused as soon as it is read when it is longer than 32
    * bytes (`K`, `Value.hs:103-108`), an amount when it is outside 128 bits (`Quantity`,
    * `Value.hs:137-144`); the order, emptiness and zero checks of `buildValueWith` run on the
    * whole list.
    */
  private def valueOf(input: Input, at: Long): Either[FlatError, Got[Value]] =
    def key(from: Long): Either[FlatError, Got[Value.Key]] =
      input.byteString(from).flatMap { case Got(raw, next) =>
        Value.Key(raw).map(Got(_, next)).toRight(FlatError.InvalidValue(Value.Invalid.KeyTooLong(raw)))
      }
    def quantity(from: Long): Either[FlatError, Got[Value.Quantity]] =
      input.integer(from).flatMap { case Got(amount, next) =>
        Value
          .Quantity(amount)
          .map(Got(_, next))
          .toRight(FlatError.InvalidValue(Value.Invalid.QuantityOutOfBounds(amount)))
      }
    @tailrec def tokens(
        from: Long,
        read: Vector[(Value.Key, Value.Quantity)]
    ): Either[FlatError, Got[Vector[(Value.Key, Value.Quantity)]]] =
      input.bit(from) match
        case Left(err)               => Left(err)
        case Right(Got(false, next)) => Right(Got(read, next))
        case Right(Got(true, next)) =>
          key(next).flatMap(k => quantity(k.at).map(q => Got(k.value -> q.value, q.at))) match
            case Left(err)                => Left(err)
            case Right(Got(token, after)) => tokens(after, read :+ token)
    @tailrec def currencies(from: Long, read: Entries): Either[FlatError, Got[Entries]] =
      input.bit(from) match
        case Left(err)               => Left(err)
        case Right(Got(false, next)) => Right(Got(read, next))
        case Right(Got(true, next)) =>
          key(next).flatMap(k => tokens(k.at, Vector.empty).map(ts => Got(k.value -> ts.value, ts.at))) match
            case Left(err)                   => Left(err)
            case Right(Got(currency, after)) => currencies(after, read :+ currency)
    currencies(at, Vector.empty).flatMap { case Got(entries, next) =>
      Value.build(entries).left.map(FlatError.InvalidValue(_)).map(Got(_, next))
    }
