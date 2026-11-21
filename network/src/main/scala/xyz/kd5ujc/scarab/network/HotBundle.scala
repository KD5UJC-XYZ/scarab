// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Resource
import cats.mtl.Raise

/** The hot mini-protocols a build adds to the node-to-node bundle. A release build adds none, so
  * its connections carry the protocols they always did and an SDU of another one ends them. The
  * Leios testnet's build adds LeiosNotify and LeiosFetch, hot only and started on demand, with
  * their own ingress limits (`initiator` and `initiatorAndResponder` 's `withHot`,
  * `Ouroboros/Consensus/Network/NodeToNode.hs` 2000-2020 and 2079-2100 of ouroboros-consensus
  * 2416493b).
  */
enum HotBundle derives CanEqual:
  case Release
  case Leios

  /** The mini-protocols this build adds, each in both directions. */
  def protocols: Set[Int] = this match
    case Release => Set.empty
    case Leios   => Set(Protocols.LeiosNotify, Protocols.LeiosFetch)

/** The LeiosFetch client of one hot activation (`aLeiosFetchClient`, `NodeToNode.hs` 1911-1935 at
  * 2416493b): it runs over its channel until the governor tells the hot protocols to stop, which it
  * learns through the stop it registers with `onStop` ([[ProtocolResults.onHotStop]]), run before
  * the control message says so; it ends with `MsgDone` once its answers are in.
  */
trait LeiosFetchInitiator[F[_]]:
  def run(channel: MuxChannel[F], onStop: F[Unit] => Resource[F, Unit])(using
      Raise[F, NetworkError]
  ): F[Unit]
