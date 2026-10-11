// SPDX-License-Identifier: Apache-2.0
package lab.node.mempool

import cats.Applicative
import cats.syntax.all.*
import lab.cbor.{Bytes, Cbor, Value}

/** Transactions submitted by local clients (`cardano-cli transaction submit` over the
  * node-to-client socket). The client sends the hard-fork-combinator wrapped transaction
  * `[era, tx]` where the Conway era is 6 and `tx` is the transaction itself or, as on node-to-node,
  * CBOR-in-CBOR (tag 24).
  */
object LocalSubmission:
  val ConwayEra = 6

  /** The original transaction bytes inside a wrapped one. */
  def unwrap(item: Bytes): Either[Rejection, Bytes] =
    Cbor.decode(item).left.map(Rejection.Malformed(_)).flatMap { root =>
      root.value match
        case Value.Arr(Vector(era, payload)) =>
          era.value match
            case Value.UInt(e) if e == ConwayEra =>
              payload.value match
                case Value.Tag(t, inner) if t == 24 =>
                  inner.value match
                    case Value.ByteString(b) => Right(b)
                    case _ => Left(Rejection.Malformed("tag 24 does not hold a byte string"))
                case _ => Right(payload.original)
            case Value.UInt(e) =>
              Left(Rejection.Malformed(s"era $e is not Conway ($ConwayEra)"))
            case _ => Left(Rejection.Malformed("era is not an integer"))
        case _ => Left(Rejection.Malformed("expected [era, transaction]"))
    }

  /** The rejection as sent to the client: its message as a CBOR text string. */
  def reasonItem(rejection: Rejection): Bytes =
    Cbor.encode(Value.Text(rejection.message)).getOrElse(Bytes.empty)

  /** The handler of the LocalTxSubmission server: `None` when the mempool took the transaction. */
  def handler[F[_]: Applicative](mempool: Mempool[F]): Bytes => F[Option[Bytes]] = item =>
    unwrap(item) match
      case Left(rejection) => Applicative[F].pure(Some(reasonItem(rejection)))
      case Right(tx) =>
        mempool.add(tx).map(_.fold(r => Some(reasonItem(r)), _ => None))
