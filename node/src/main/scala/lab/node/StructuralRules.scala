// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.Applicative
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import lab.cbor.Bytes

/** Running totals for a structurally followed chain. */
final case class FollowState(blocks: BigInt, transactions: BigInt)

object FollowState:
  val empty: FollowState = FollowState(0, 0)
  given cats.Eq[FollowState] = cats.Eq.fromUniversalEquals

/** Structural block acceptance: the fetched block is a Conway block whose embedded header is the
  * announced header and whose body matches the header's size and hash commitment. No consensus or
  * ledger rule is checked here.
  */
final class StructuralRules[F[_]: Applicative](using Raise[F, ValidationError])
    extends BlockRules[F, FollowState]:
  def apply(state: FollowState, header: Header, raw: Bytes): F[FollowState] =
    def reject(reason: String) =
      ValidationError(header.point, "structure", reason).raise[F, FollowState]
    Block.decode(raw) match
      case Left(error) => reject(error.message)
      case Right(block) =>
        if block.header.hash != header.hash then
          reject("block header differs from announced header")
        else if block.bodySize != header.bodySize then
          reject(s"body size ${block.bodySize} does not match commitment ${header.bodySize}")
        else if block.bodyHash != header.bodyHash then reject("body hash does not match commitment")
        else FollowState(state.blocks + 1, state.transactions + block.transactions.size).pure[F]
