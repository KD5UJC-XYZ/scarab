// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import xyz.kd5ujc.scarab.ledger.state.Word64
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

enum LeiosDbBackend:
  case InMemory, SQLite

/** LeiosAdaptiveEndorsements.hs:90-113 at consensus 2416493b. These are local forging settings, not
  * ledger protocol parameters; the reference uses Double without range checks.
  */
final case class LeiosAdaptiveSettings(
    gainP: Double = 2,
    attack: Double = 0.1,
    release: Double = 0.03,
    minScale: Double = 0.1,
    aliveWindow: Long = 20
)

/** Configuration/Leios.hs:24-131 and POM.hs:390,443-445,828,876 at cardano-node b004fe0c. The
  * optional lead leaves the consensus default in force. `configured` names explicit keys so an
  * unused-setting diagnostic does not report defaults the operator never requested.
  */
final case class LeiosSettings(
    egressBudgetMbps: Double = 250,
    database: LeiosDbBackend = LeiosDbBackend.SQLite,
    minOfferLead: Option[Word64] = None,
    adaptiveEndorsements: Option[LeiosAdaptiveSettings] = None,
    configured: Set[String] = Set.empty
):
  def unused: List[String] = configured.toList.sorted.map(key => s"$key: recognised but unused")

object LeiosSettings:
  private val keys: List[NodeConfig.TopLevelKey] =
    List[NodeConfig.TopLevelKey](
      "EgressBudgetMbps",
      "LeiosDbConfig",
      "LeiosMinOfferLead",
      "LeiosAdaptiveEndorsements"
    )

  /** The keys `build` 's node uses, which are not reported unused: `LeiosDbConfig` under the Leios
    * build when it keeps the store on disk, where the node opens it ([[LeiosStorage]]).
    */
  private def used(build: ReferenceBuild, database: LeiosDbBackend): Set[NodeConfig.TopLevelKey] =
    (build, database) match
      case (ReferenceBuild.LeiosB004fe0c, LeiosDbBackend.SQLite) =>
        Set[NodeConfig.TopLevelKey]("LeiosDbConfig")
      case (ReferenceBuild.LeiosB004fe0c, LeiosDbBackend.InMemory) |
          (ReferenceBuild.Release11_1 | ReferenceBuild.Release11_2, _) =>
        Set.empty

  private[node] def parse(
      k: ConfigFields[NodeConfig.TopLevelKey],
      build: ReferenceBuild
  ): Either[ConfigError, LeiosSettings] =
    for
      egress <- k.realFloat("EgressBudgetMbps")
      database <- k
        .obj("LeiosDbConfig")
        .flatMap(_.traverse { db =>
          db.required[String]("Backend").flatMap {
            case "InMemory" => Right(LeiosDbBackend.InMemory)
            case "SQLite"   => Right(LeiosDbBackend.SQLite)
            case other =>
              Left(
                ConfigError(
                  ConfigProblem.Selection("LeiosDbConfig.Backend", other, Set("InMemory", "SQLite"))
                )
              )
          }
        })
      lead <- k.word64("LeiosMinOfferLead")
      adaptive <- k
        .obj("LeiosAdaptiveEndorsements")
        .flatMap(_.traverse(readAdaptive))
        .map(_.flatten)
    yield
      val backend = database.getOrElse(LeiosDbBackend.SQLite)
      LeiosSettings(
        egress.getOrElse(250),
        backend,
        lead,
        adaptive,
        keys.filter(key => k.value(key).nonEmpty && !used(build, backend)(key)).toSet[String]
      )

  private def readAdaptive(
      k: ConfigFields[String]
  ): Either[ConfigError, Option[LeiosAdaptiveSettings]] =
    for
      enabled <- k.optional[Boolean]("Enabled")
      gain <- k.realFloat("GainP")
      attack <- k.realFloat("Attack")
      release <- k.realFloat("Release")
      scale <- k.realFloat("MinScale")
      alive <- k.int64("AliveWindow")
    yield Option.when(enabled.getOrElse(true))(
      LeiosAdaptiveSettings(
        gain.getOrElse(2),
        attack.getOrElse(0.1),
        release.getOrElse(0.03),
        scale.getOrElse(0.1),
        alive.getOrElse(20)
      )
    )
