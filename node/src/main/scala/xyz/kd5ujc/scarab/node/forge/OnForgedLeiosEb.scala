// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.mtl.Raise
import xyz.kd5ujc.scarab.consensus.leios.AnnouncingHeader
import xyz.kd5ujc.scarab.storage.LeiosStoreError

/** What the node does with an endorser block it forged, before the ranking block that announces it
  * goes to chain selection: the forge's `onForgedLeiosEb` argument (ouroboros-consensus 2416493b
  * `ouroboros-consensus-diffusion/src/ouroboros-consensus-diffusion/Ouroboros/Consensus/NodeKernel/Forge.hs:104-107,
  * 283-294`), which the node kernel gives the forge with its Leios state
  * (`NodeKernel.hs:1039-1052`). The node's is its endorser-block fetch's
  * ([[xyz.kd5ujc.scarab.node.LeiosFetchNode.onForged]]). The store's failure is raised, and stops
  * the node.
  */
trait OnForgedLeiosEb[F[_]]:
  def forged(announcing: AnnouncingHeader, eb: ForgedLeiosEb)(using
      Raise[F, LeiosStoreError]
  ): F[Unit]

/** An endorser block forged with a ranking block, and the header that announces it, as the forge
  * hands them on (`mkForgedAnnouncingHeader`, [[AnnouncingHeader.forged]]).
  */
final case class AnnouncedEb(announcing: AnnouncingHeader, forged: ForgedLeiosEb)
