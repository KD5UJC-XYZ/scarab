// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.Applicative
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.HardForkConfig
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.legacy

/** The V2 snapshot format of the node state as a [[SnapshotFormat]], for the tests of the
  * migration: it writes the files that the builds before V3 wrote (through the frozen types) and
  * reads them back as the live state (the eras are those of `forks`).
  */
object LegacyV2Format:
  def format[F[_]: Applicative](forks: HardForkConfig): SnapshotFormat[F, legacy.FlatNodeState] =
    new SnapshotFormat[F, legacy.FlatNodeState]:
      def encode(
          snapshot: StateSnapshot[legacy.FlatNodeState]
      ): F[Either[SnapshotRefusal, Array[Byte]]] =
        legacy.SnapshotV2
          .encode(legacy.StateSnapshot(snapshot.anchor, legacy.Downgrade.node(snapshot.state)))(
            using legacy.NodeCodecs.nodeSnapshot
          )
          .asRight[SnapshotRefusal]
          .pure[F]

      def decode(
          content: Array[Byte]
      ): F[Either[StorageError, StateSnapshot[legacy.FlatNodeState]]] =
        legacy.SnapshotV2
          .decodeNode(content)
          .flatMap(s =>
            legacy.Upgrade
              .node(s.state, forks, s.anchor)
              .map(StateSnapshot(s.anchor, _))
              .leftMap(e => StorageError.Corrupt("snapshot", e))
          )
          .pure[F]
