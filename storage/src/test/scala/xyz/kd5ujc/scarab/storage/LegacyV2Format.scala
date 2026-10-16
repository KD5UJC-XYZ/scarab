// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.Applicative
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.legacy

/** The V2 snapshot format of the node state as a [[SnapshotFormat]], for the tests of the
  * migration: it writes the files that the builds before V3 wrote (through the frozen types) and
  * reads them back as the live state.
  */
object LegacyV2Format:
  def format[F[_]: Applicative]: SnapshotFormat[F, NodeState] = new SnapshotFormat[F, NodeState]:
    def encode(snapshot: StateSnapshot[NodeState]): F[Either[SnapshotRefusal, Array[Byte]]] =
      legacy.SnapshotV2
        .encode(legacy.StateSnapshot(snapshot.anchor, legacy.Downgrade.node(snapshot.state)))(using
          legacy.NodeCodecs.nodeSnapshot
        )
        .asRight[SnapshotRefusal]
        .pure[F]

    def decode(content: Array[Byte]) =
      legacy.SnapshotV2
        .decodeNode(content)
        .map(s => StateSnapshot(s.anchor, legacy.Upgrade.node(s.state)))
        .pure[F]
