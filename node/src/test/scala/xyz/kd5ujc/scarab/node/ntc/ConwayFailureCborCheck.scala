// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.data.NonEmptyList
import munit.{Assertions, Location}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.era.rules.ConwayLedgerPredFailure
import xyz.kd5ujc.scarab.ledger.state.Conway

/** The era failures' refusal as [[ConwayFailureCbor]] writes it, for the suites that bind the era
  * rules' failures to the reference's bytes.
  */
object ConwayFailureCborCheck:
  /** `failures`' `ApplyTxErr`, failing the test where there is none or no bytes. */
  def direct(failures: Iterable[ConwayLedgerPredFailure[Conway]])(using Location): Bytes =
    NonEmptyList
      .fromList(failures.toList)
      .fold(Assertions.fail("no failures to encode")) { fs =>
        ConwayFailureCbor
          .applyTxError(fs)
          .fold(e => Assertions.fail(s"no Conway encoding: $e"), identity)
      }

  /** The constructors of `f`, from LEDGER's down to the leaf failure's, joined by `/`: which of the
    * encoder's arms a corpus reaches.
    */
  def constructors(f: ConwayLedgerPredFailure[Conway]): String =
    import xyz.kd5ujc.scarab.ledger.era.rules.*
    val below = f match
      case ConwayLedgerPredFailure.ConwayUtxowFailure(u) =>
        u match
          case ConwayUtxowPredFailure.UtxoFailure(x) =>
            x match
              case ConwayUtxoPredFailure.UtxosFailure(s) =>
                List(x.productPrefix, s.productPrefix) ++ (s match
                  case ConwayUtxosPredFailure.CollectErrors(es) =>
                    es.toList.map(_.productPrefix).distinct
                  case ConwayUtxosPredFailure.ValidationTagMismatch(_, d) => List(d.productPrefix))
              case other => List(other.productPrefix)
          case ConwayUtxowPredFailure.MissingRedeemers(ps) =>
            List(u.productPrefix) ++ ps.toList.map(_._1.productPrefix).distinct
          case other => List(other.productPrefix)
      case ConwayLedgerPredFailure.ConwayCertsFailure(c) =>
        c match
          case ConwayCertsPredFailure.CertFailure(x) =>
            List(c.productPrefix, x.productPrefix) ++ (x match
              case ConwayCertPredFailure.DelegFailure(d)   => List(d.productPrefix)
              case ConwayCertPredFailure.PoolFailure(p)    => List(p.productPrefix)
              case ConwayCertPredFailure.GovCertFailure(g) => List(g.productPrefix))
          case other => List(other.productPrefix)
      case ConwayLedgerPredFailure.ConwayGovFailure(g) => List(g.productPrefix)
      case _                                           => Nil
    (f.productPrefix :: below).mkString("/")
