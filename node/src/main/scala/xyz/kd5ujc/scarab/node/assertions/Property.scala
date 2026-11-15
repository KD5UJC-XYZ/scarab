// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.assertions

/** What the platform expects of a property: the fallback SDK's assertion functions that the node
  * uses, each with the values its lines carry verbatim (Antithesis docs,
  * `/docs/reference/sdk/fallback/assert/`, the table of declarations).
  */
enum Kind(val mustHit: Boolean, val assertType: String, val displayType: String):
  /** The condition is true every time the property is evaluated, which it is at least once. */
  case Always extends Kind(true, "always", "Always")

  /** The condition is true at least once in a test run. */
  case Sometimes extends Kind(true, "sometimes", "Sometimes")

  /** The property is never reached. */
  case Unreachable extends Kind(false, "reachability", "Unreachable")

/** Where a property is asserted, as the SDK's `location`: the source file, from the repository's
  * root, and the class that asserts it. The function is not given (an empty string), and the line
  * and the column are 0.
  */
final case class Location(file: String, className: String)

object Location:
  private[assertions] val follower = Location(
    "node/src/main/scala/xyz/kd5ujc/scarab/node/Follower.scala",
    "xyz.kd5ujc.scarab.node.Follower"
  )
  private[assertions] val governor = Location(
    "node/src/main/scala/xyz/kd5ujc/scarab/node/PeerGovernor.scala",
    "xyz.kd5ujc.scarab.node.PeerGovernor"
  )
  private[assertions] val mempool = Location(
    "node/src/main/scala/xyz/kd5ujc/scarab/node/mempool/Mempool.scala",
    "xyz.kd5ujc.scarab.node.mempool.Mempool"
  )
  private[assertions] val forger = Location(
    "node/src/main/scala/xyz/kd5ujc/scarab/node/forge/Forger.scala",
    "xyz.kd5ujc.scarab.node.forge.Forger"
  )
  private[assertions] val node = Location(
    "node/src/main/scala/xyz/kd5ujc/scarab/node/Main.scala",
    "xyz.kd5ujc.scarab.node.Main"
  )

/** A property the node asserts. The catalogue is closed: a property is a case of the type of its
  * [[Kind]] below, so that it cannot be declared at one site and misspelt at another, and an
  * assertion of one kind cannot be made with a property of another. Its `message` is the name the
  * platform reports it by, and its `id` as well, as the SDK advises.
  */
sealed trait Property:
  def message: String
  def location: Location
  def kind: Kind

object Property:
  /** Every property, in the order its declaration is written at start. */
  val catalogue: Vector[Property] = Sometimes.values.toVector ++ Unreachable.values.toVector

  /** A state the node reaches at least once in a test run. */
  enum Sometimes(val message: String, val location: Location) extends Property:
    case ForkSwitched extends Sometimes("Scarab switches to a fork", Location.follower)
    case RolledBackMoreThanOne
        extends Sometimes("Scarab rolls back more than 1 block", Location.follower)
    case RolledBackMoreThanHalfK
        extends Sometimes("Scarab rolls back more than k / 2 blocks", Location.follower)
    case PeerDemoted extends Sometimes("Scarab's governor demotes a peer", Location.governor)
    case PeerDemotedAsynchronously
        extends Sometimes("a peer of Scarab's is demoted asynchronously", Location.governor)
    case PeerNoLongerIntersects
        extends Sometimes("a peer's chain no longer intersects Scarab's", Location.governor)
    case MempoolDropsOnRevalidation
        extends Sometimes("Scarab's mempool drops a transaction on revalidation", Location.mempool)
    case Forged extends Sometimes("Scarab forges a block", Location.forger)
    case ForgedWithTransactions
        extends Sometimes("Scarab forges a block with transactions", Location.forger)
    case SnapshotWritten extends Sometimes("Scarab writes a ledger snapshot", Location.node)

    def kind: Kind = Kind.Sometimes

  /** A condition that holds every time it is evaluated, which it is at least once. No property of
    * the catalogue is of this kind yet.
    */
  sealed trait Always extends Property:
    final def kind: Kind = Kind.Always

  /** A branch the node never reaches. */
  enum Unreachable(val message: String, val location: Location) extends Property:
    case ForgedInvalid
        extends Unreachable("Scarab never forges a block its own rules refuse", Location.forger)
    case ForgeDefect
        extends Unreachable(
          "Scarab never builds a block that fails a check only its own bug can fail",
          Location.forger
        )
    case BlockNoVerdict
        extends Unreachable(
          "Scarab's block validation never ends without a verdict",
          Location.follower
        )
    case StoppedOnFault
        extends Unreachable(
          "Scarab never stops on a fault other than a clock moved back too far",
          Location.node
        )
    case ClockWokeEarly
        extends Unreachable(
          "Scarab never traces the clock moved back on a wake 1 µs to 1 ms before its slot, time moving on",
          Location.forger
        )

    def kind: Kind = Kind.Unreachable
