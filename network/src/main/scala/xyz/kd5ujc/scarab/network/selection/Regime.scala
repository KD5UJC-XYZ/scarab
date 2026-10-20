// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import scala.concurrent.duration.*

/** The targets the churn governor asks for, before and between its rounds (`peerChurnGovernor`,
  * `Cardano/Network/PeerSelection/Churn.hs` 167-214). [[Churn]] computes the changes within each
  * round from these regime-specific baseline targets.
  *
  * The regime follows the fetch mode and whether bootstrap peers are configured (`pickChurnRegime`,
  * 108-115):
  *
  *   - `Default`: the node is close to the tip. Its targets are the configured ones.
  *   - `PraosSync`: far behind, no bootstrap peers. The same targets.
  *   - `BootstrapPraosSync`: far behind, bootstrap peers configured. The active targets are cut to
  *     what a node needs to sync from trusted peers, to conserve resources.
  */
object Regime:
  enum Kind:
    case Default, PraosSync, BootstrapPraosSync

  def kind(fetchMode: FetchMode, bootstrapConfigured: Boolean): Kind =
    (fetchMode, bootstrapConfigured) match
      case (FetchMode.Deadline, _)     => Kind.Default
      case (FetchMode.BulkSync, false) => Kind.PraosSync
      case (FetchMode.BulkSync, true)  => Kind.BootstrapPraosSync

  /** How long the churn governor waits before it lets the root target apply (`threadDelay 3`): a
    * head start for the local roots.
    */
  val HeadStart: FiniteDuration = 3.seconds

  /** The first targets (`targets0'`, `Churn.hs` 181-195): in the default and the sync regime the
    * root target is zero for the head start; in the bootstrap regime the active targets are cut to
    * `min(max(1, localHot) + 1, active)` and `min(1, activeBig)`.
    */
  def initial(kind: Kind, base: Targets, localHot: Int): Targets =
    val adjusted = kind match
      case Kind.Default | Kind.PraosSync => base.withRoot(0)
      case Kind.BootstrapPraosSync =>
        base
          .withActive((localHot.max(1) + 1).min(base.active))
          .flatMap(_.withActiveBig(1.min(base.activeBig)))
    // the adjustments only lower targets, so they stay sane; the base is the fallback if not
    adjusted.getOrElse(base)

  /** The targets after the head start (`Churn.hs` 202-214): the bootstrap regime keeps its cut. */
  def steady(kind: Kind, base: Targets, localHot: Int): Targets = kind match
    case Kind.BootstrapPraosSync => initial(kind, base, localHot)
    case _                       => base
