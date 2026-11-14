// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

/** The version of Plutus Core a program declares, the reference's `Version`: three `Natural` s,
  * ordered lexicographically (`PlutusCore/Version.hs:44-58`, plutus 1.70.0.0 `507cab8c`). The
  * components are unbounded, as the Flat decoder reads them
  * (`PlutusCore/FlatInstances.hs:177-179`).
  */
final case class ProgramVersion private (major: BigInt, minor: BigInt, patch: BigInt):

  /** Whether the Flat decoder admits `constr` and `case` in a program of this version:
    * `version >= plcVersion110` (`UntypedPlutusCore/Core/Instance/Flat.hs:168,182`). The ledger's
    * own check is membership in [[Availability.programVersions]] (`Common/Eval.hs:125`).
    */
  def allowsConstrAndCase: Boolean =
    summon[Ordering[ProgramVersion]].gteq(this, ProgramVersion.V1_1_0)

  def show: String = s"$major.$minor.$patch"

object ProgramVersion:
  /** The version when no component is negative. */
  def of(major: BigInt, minor: BigInt, patch: BigInt): Option[ProgramVersion] =
    Option.when(major.signum >= 0 && minor.signum >= 0 && patch.signum >= 0)(
      new ProgramVersion(major, minor, patch)
    )

  /** `plcVersion100` and `plcVersion110` (`Version.hs:62-67`). */
  val V1_0_0: ProgramVersion = new ProgramVersion(1, 0, 0)
  val V1_1_0: ProgramVersion = new ProgramVersion(1, 1, 0)

  given Ordering[ProgramVersion] =
    Ordering.by[ProgramVersion, (BigInt, BigInt, BigInt)](v => (v.major, v.minor, v.patch))
