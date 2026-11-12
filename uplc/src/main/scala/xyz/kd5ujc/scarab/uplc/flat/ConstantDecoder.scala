// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.flat

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.uplc.{Availability, Constant, DefaultUni, PlutusData, Schemes, Text, Value}

/** The constants of a Flat program: a type ([[TypeHeader]]) and a value of it, read as
  * `decodeDefaultUniValue` reads it (`PlutusCore/Default/Universe.hs:166-186`, plutus 1.70.0.0
  * `507cab8c`).
  *
  * As the reference does, the type is first compiled into the reader of its values ([[Plan]]), and
  * a part of the type made only of `unit` s has one value of no bits, built once and shared by
  * every value that holds it. A value is as deep as its type, which the ledger does not bound
  * before protocol 11, so the compilation is a fold on the heap ([[Schemes.hylo]]) and the value is
  * read by a machine that keeps the lists and pairs still open on a list (ADR 0011). A list or an
  * array is a continuation bit before each element (`decodeListWith`,
  * `PlutusCore/Flat/Decoder/Strict.hs:60-88`); when its first element took no bits, the reference
  * repeats that element for each further bit (`goZero`), and so does this.
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

  /** The reader of the values of a type: `decodeDefaultUniValue` 's `compile`
    * (`Universe.hs:166-186`). Each knows the type its values report ([[Plan.uni]]), built from the
    * shared values' own types, so that a list compares its elements' types with it in time that
    * does not grow with the shared parts.
    */
  enum Plan:
    /** A type made of `unit` s (`Pure`): its one value, of no bits. */
    case Shared(value: Constant)

    /** A type with a Flat decoder of its own (`compile other`). */
    case Leaf(leafType: DefaultUni)

    /** A list, or an array, of the plan's values. */
    case Items(element: Plan, array: Boolean)

    case Pair(first: Plan, second: Plan)

    /** The type of the values this plan reads. */
    lazy val uni: DefaultUni = this match
      case Shared(c) => c.typeOf
      case Leaf(u)   => u
      case Items(element, arr) =>
        if arr then DefaultUni.Array(element.uni) else DefaultUni.List(element.uni)
      case Pair(first, second) => DefaultUni.Pair(first.uni, second.uni)

  // One layer of a type, for its fold.
  private enum Layer[+R]:
    case Base(uni: DefaultUni)
    case Items(element: R, array: Boolean)
    case Pair(first: R, second: R)

  private given cats.Traverse[Layer] with
    def traverse[G[_], A, B](fa: Layer[A])(f: A => G[B])(using
        G: cats.Applicative[G]
    ): G[Layer[B]] =
      fa match
        case Layer.Base(u)       => G.pure(Layer.Base(u))
        case Layer.Items(e, arr) => G.map(f(e))(Layer.Items(_, arr))
        case Layer.Pair(a, b)    => G.map2(f(a), f(b))(Layer.Pair(_, _))
    def foldLeft[A, B](fa: Layer[A], b: B)(f: (B, A) => B): B = fa match
      case Layer.Base(_)     => b
      case Layer.Items(e, _) => f(b, e)
      case Layer.Pair(x, y)  => f(f(b, x), y)
    def foldRight[A, B](fa: Layer[A], lb: cats.Eval[B])(
        f: (A, cats.Eval[B]) => cats.Eval[B]
    ): cats.Eval[B] =
      fa match
        case Layer.Base(_)     => lb
        case Layer.Items(e, _) => f(e, lb)
        case Layer.Pair(x, y)  => f(x, cats.Eval.defer(f(y, lb)))

  /** `compile` (`Universe.hs:169-186`): pairs of shared values are shared. */
  def plan(uni: DefaultUni): Plan =
    Schemes.hylo[Layer, DefaultUni, Plan](uni)(
      {
        case DefaultUni.List(e)    => Layer.Items(e, false)
        case DefaultUni.Array(e)   => Layer.Items(e, true)
        case DefaultUni.Pair(a, b) => Layer.Pair(a, b)
        case other                 => Layer.Base(other)
      },
      {
        case Layer.Base(DefaultUni.Unit)                => Plan.Shared(Constant.Unit)
        case Layer.Base(other)                          => Plan.Leaf(other)
        case Layer.Items(element, array)                => Plan.Items(element, array)
        case Layer.Pair(Plan.Shared(a), Plan.Shared(b)) => Plan.Shared(Constant.Pair(a, b))
        case Layer.Pair(first, second)                  => Plan.Pair(first, second)
      }
    )

  // A list, array or pair whose value is still being read.
  private enum Open:
    /** The elements read so far of a list (or an array), the latest last, the first at `start`. */
    case Items(element: Plan, read: Vector[Constant], array: Boolean, start: Long)
    case First(second: Plan)
    case Second(first: Constant)

  private enum Step:
    case Read(plan: Plan, at: Long)
    case Give(constant: Constant, at: Long)

  /** The value of type `uni` at `at`. */
  def value(input: Input, at: Long, uni: DefaultUni): Either[FlatError, Got[Constant]] =
    @tailrec def go(step: Step, open: List[Open]): Either[FlatError, Got[Constant]] = step match
      case Step.Read(Plan.Shared(c), from) => go(Step.Give(c, from), open)
      case Step.Read(Plan.Leaf(leafType), from) =>
        leaf(input, from, leafType) match
          case Left(err)           => Left(err)
          case Right(Got(c, next)) => go(Step.Give(c, next), open)
      case Step.Read(Plan.Items(e, array), from) =>
        input.bit(from) match
          case Left(err) => Left(err)
          case Right(Got(true, next)) =>
            go(Step.Read(e, next), Open.Items(e, Vector.empty, array, next) :: open)
          case Right(Got(false, next)) =>
            items(e, Vector.empty, array) match
              case Left(err)   => Left(err)
              case Right(made) => go(Step.Give(made, next), open)
      case Step.Read(Plan.Pair(a, b), from) => go(Step.Read(a, from), Open.First(b) :: open)
      case Step.Give(c, from) =>
        open match
          case Nil                         => Right(Got(c, from))
          case Open.First(b) :: below      => go(Step.Read(b, from), Open.Second(c) :: below)
          case Open.Second(first) :: below => go(Step.Give(Constant.Pair(first, c), from), below)
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
    go(Step.Read(plan(uni), at), Nil)

  // `c` once and again for each set bit up to a clear one.
  private def repeated(
      input: Input,
      at: Long,
      c: Constant
  ): Either[FlatError, Got[Vector[Constant]]] =
    @tailrec def go(from: Long, count: Int): Either[FlatError, Got[Vector[Constant]]] =
      input.bit(from) match
        case Left(err)               => Left(err)
        case Right(Got(true, next))  => go(next, count + 1)
        case Right(Got(false, next)) => Right(Got(Vector.fill(count)(c), next))
    go(at, 1)

  private def items(
      element: Plan,
      read: Vector[Constant],
      array: Boolean
  ): Either[FlatError, Constant] =
    val list = Constant.List.of(element.uni, read).left.map(FlatError.IllTypedElements(_))
    if array then list.map(_.toArray) else list

  /** A value of a type that is not a list, an array or a pair (`compile other`, `Universe.hs:186`):
    * the type's own Flat decoder.
    */
  private def leaf(input: Input, at: Long, uni: DefaultUni): Either[FlatError, Got[Constant]] =
    uni match
      case DefaultUni.Integer => input.integer(at).map(g => Got(Constant.Integer(g.value), g.at))
      case DefaultUni.ByteString =>
        input.byteString(at).map(g => Got(Constant.ByteString(g.value), g.at))
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

  /** A `value` constant (`Value.hs:223-232`): a list of currencies, each a key and a list of
    * tokens, each a key and an amount. A key is refused as soon as it is read when it is longer
    * than 32 bytes (`K`, `Value.hs:103-108`), an amount when it is outside 128 bits (`Quantity`,
    * `Value.hs:137-144`); the order, emptiness and zero checks of `buildValueWith` run on the whole
    * list.
    */
  private def valueOf(input: Input, at: Long): Either[FlatError, Got[Value]] =
    def key(from: Long): Either[FlatError, Got[Value.Key]] =
      input.byteString(from).flatMap { case Got(raw, next) =>
        Value
          .Key(raw)
          .map(Got(_, next))
          .toRight(FlatError.InvalidValue(Value.Invalid.KeyTooLong(raw)))
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
          key(next).flatMap(k =>
            tokens(k.at, Vector.empty).map(ts => Got(k.value -> ts.value, ts.at))
          ) match
            case Left(err)                   => Left(err)
            case Right(Got(currency, after)) => currencies(after, read :+ currency)
    currencies(at, Vector.empty).flatMap { case Got(entries, next) =>
      Value.build(entries).left.map(FlatError.InvalidValue(_)).map(Got(_, next))
    }
