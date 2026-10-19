// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.NetworkIdentity
import xyz.kd5ujc.scarab.storage.scls.SclsCommitment.Digest
import xyz.kd5ujc.scarab.storage.scls.SclsRecord.Namespace
import xyz.kd5ujc.scarab.storage.scls.SclsValidation.VerifiedContainer

/** Pure admission checks for the complete-base import contract. The service must obtain the base
  * context and its projection from the same owned, verified snapshot/checkpoint. An SCLS file
  * contains a slot but neither a block hash nor complete restart/configuration state.
  */
object SclsAdmission:
  final case class Context(anchor: BlockRef, identity: NetworkIdentity)

  enum Error extends DomainError:
    case PointMismatch(expected: BlockRef, found: BlockRef)
    case NetworkMismatch(expected: NetworkIdentity, found: NetworkIdentity)
    case BaseSlot(expected: BigInt, found: BigInt)
    case IncomingSlot(expected: BigInt, found: BigInt)
    case Entries(expected: BigInt, found: BigInt)
    case NamespaceEntries(namespace: Namespace, expected: BigInt, found: BigInt)
    case NamespaceRoot(namespace: Namespace, expected: Digest, found: Digest)
    case GlobalRoot(expected: Digest, found: Digest)

  /** Bound to the verified incoming artifact and the exact complete-base context. This value does
    * not by itself prove staged storage durability or authorize its publication.
    */
  final class Admitted private[SclsAdmission] (
      val context: Context,
      val incoming: VerifiedContainer
  )

  /** Chunk counts and diagnostic metadata may differ after re-serialization. Canonical entry counts
    * and roots must agree in all twelve namespaces, including the streamed UTxO. No incomplete-base
    * defaults or slot-only chain-point inference are permitted here.
    */
  private[scls] def check(
      expected: Context,
      base: Context,
      baseProjection: VerifiedContainer,
      incoming: VerifiedContainer
  ): Either[Error, Admitted] =
    val before = baseProjection.manifest
    val after = incoming.manifest
    for
      _ <- Either.cond(
        base.anchor == expected.anchor,
        (),
        Error.PointMismatch(expected.anchor, base.anchor)
      )
      _ <- Either.cond(
        base.identity == expected.identity,
        (),
        Error.NetworkMismatch(expected.identity, base.identity)
      )
      _ <- Either.cond(
        before.slot == base.anchor.slot,
        (),
        Error.BaseSlot(base.anchor.slot, before.slot)
      )
      _ <- Either.cond(
        after.slot == expected.anchor.slot,
        (),
        Error.IncomingSlot(expected.anchor.slot, after.slot)
      )
      _ <- Either.cond(
        before.entries == after.entries,
        (),
        Error.Entries(before.entries, after.entries)
      )
      _ <- before.namespaces.zip(after.namespaces).traverse_ { (previous, next) =>
        for
          _ <- Either.cond(
            previous.entries == next.entries,
            (),
            Error.NamespaceEntries(previous.namespace, previous.entries, next.entries)
          )
          _ <- Either.cond(
            previous.root == next.root,
            (),
            Error.NamespaceRoot(previous.namespace, previous.root, next.root)
          )
        yield ()
      }
      _ <- Either.cond(before.root == after.root, (), Error.GlobalRoot(before.root, after.root))
    yield new Admitted(expected, incoming)
