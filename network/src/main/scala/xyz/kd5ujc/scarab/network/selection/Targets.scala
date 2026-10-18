// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import xyz.kd5ujc.scarab.codec.DomainError

/** The seven targets of the governor (`PeerSelectionTargets`, ouroboros-network 1.2.0.0
  * `Ouroboros/Network/PeerSelection/Governor/Types.hs` 189-233): how many root, known, established
  * and active peers it works towards, and the same three for big ledger peers, which are in
  * addition to the others. The root target is one-sided: the governor asks for more public roots
  * when below it and never shrinks the set to meet it.
  *
  * The only constructor outside this object is [[Targets.validated]], which is the reference's
  * `sanePeerSelectionTargets` (`Types.hs` 247-265); a value of this type is therefore always sane.
  */
final case class Targets private (
    root: Int,
    known: Int,
    established: Int,
    active: Int,
    knownBig: Int,
    establishedBig: Int,
    activeBig: Int
):
  /** The same targets with another root target, or the reason that is not sane. */
  def withRoot(value: Int): Either[Targets.Problem, Targets] =
    Targets.validated(value, known, established, active, knownBig, establishedBig, activeBig)

  /** The same targets with another active target, or the reason that is not sane. */
  def withActive(value: Int): Either[Targets.Problem, Targets] =
    Targets.validated(root, known, established, value, knownBig, establishedBig, activeBig)

  /** The same targets with another active big ledger target, or the reason that is not sane. */
  def withActiveBig(value: Int): Either[Targets.Problem, Targets] =
    Targets.validated(root, known, established, active, knownBig, establishedBig, value)

  /** The same targets with the named ones replaced, or the reason the result is not sane. */
  def revised(
      root: Int = root,
      known: Int = known,
      established: Int = established,
      active: Int = active,
      knownBig: Int = knownBig,
      establishedBig: Int = establishedBig,
      activeBig: Int = activeBig
  ): Either[Targets.Problem, Targets] =
    Targets.validated(root, known, established, active, knownBig, establishedBig, activeBig)

object Targets:
  /** Which sets a [[Problem]] is about. */
  enum Kind:
    case Ordinary, BigLedger

  /** Why a set of targets is refused: closed, so the configuration check reports it exactly. */
  enum Problem extends DomainError:
    /** A target is negative. */
    case Negative(kind: Kind)

    /** The nesting `active <= established <= known` (and `root <= known` for ordinary peers) fails.
      */
    case NotNested(kind: Kind)

    /** A target is above the cap the reference puts on it. */
    case AboveCap(kind: Kind, cap: Int)

    def message: String = this match
      case Negative(k)    => s"a $k target is negative"
      case NotNested(k)   => s"the $k targets are not nested (active <= established <= known)"
      case AboveCap(k, c) => s"a $k target is above its cap of $c"

  /** The caps of `sanePeerSelectionTargets`: at most 100 active, 1000 established, 10000 known. */
  val ActiveCap = 100
  val EstablishedCap = 1000
  val KnownCap = 10000

  def validated(
      root: Int,
      known: Int,
      established: Int,
      active: Int,
      knownBig: Int,
      establishedBig: Int,
      activeBig: Int
  ): Either[Problem, Targets] =
    def check(kind: Kind, k: Int, e: Int, a: Int, r: Option[Int]): Either[Problem, Unit] =
      if a < 0 || r.exists(_ < 0) then Left(Problem.Negative(kind))
      else if !(a <= e && e <= k && r.forall(_ <= k)) then Left(Problem.NotNested(kind))
      else if a > ActiveCap then Left(Problem.AboveCap(kind, ActiveCap))
      else if e > EstablishedCap then Left(Problem.AboveCap(kind, EstablishedCap))
      else if k > KnownCap then Left(Problem.AboveCap(kind, KnownCap))
      else Right(())
    for
      _ <- check(Kind.Ordinary, known, established, active, Some(root))
      _ <- check(Kind.BigLedger, knownBig, establishedBig, activeBig, None)
    yield Targets(root, known, established, active, knownBig, establishedBig, activeBig)

  /** `nullPeerSelectionTargets`: the governor works towards nothing (it is sane). */
  val zero: Targets = Targets(0, 0, 0, 0, 0, 0, 0)

  /** The relay defaults of cardano-node 11.1.3 (`Diffusion/Configuration.hs` 61-72: root 60, known
    * 150, established 30, active 20, big ledger 15 / 10 / 5).
    */
  val relay: Targets = Targets(60, 150, 30, 20, 15, 10, 5)

  /** The block producer defaults (same file, the producer split at 66-67: root 100, known 100). */
  val blockProducer: Targets = Targets(100, 100, 30, 20, 15, 10, 5)

  /** The targets while syncing in Genesis mode (`CD/Diffusion/Configuration.hs` 51-60). */
  val sync: Targets = Targets(0, 150, 10, 5, 100, 40, 30)
