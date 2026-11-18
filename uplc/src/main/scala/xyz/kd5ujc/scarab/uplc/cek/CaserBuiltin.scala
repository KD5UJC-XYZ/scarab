// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import xyz.kd5ujc.scarab.uplc.{Constant, PlutusData, ProgramVersion, ProtocolMajor, Term}

/** How `case` treats a constant: the reference's `CaserBuiltin`
  * (`PlutusCore/Builtin/Case.hs:45-67`, plutus 1.70.0.0 `507cab8c`), a parameter of the machine.
  * The ledger chooses it by protocol and, from plutus 1.71.0.0, by the program's version
  * ([[CaserBuiltin.forProtocol]]). The upstream conformance executable of 1.70.0.0 uses
  * [[WithData]] whatever the program (`plutus-conformance/src/PlutusConformance/CekEvaluator.hs:
  * 46-61`); that of 1.71.0.0 (`261766ad`) uses the ledger's choice at protocol 12 with the
  * program's version (`defaultCaserBuiltinFor newestPV v`, `CekEvaluator.hs:47-54` there).
  */
enum CaserBuiltin:
  /** `unavailableCaserBuiltin`: every constant is refused (`Case.hs:63-67`, plutus 1.70.0.0). */
  case Unavailable(protocol: ProtocolMajor)

  /** `caseBuiltinNoData`: casing on every supported type but `Data` (`Universe.hs:1011-1017`,
    * plutus 1.70.0.0).
    */
  case WithoutData

  /** `caseBuiltin` of `DefaultUni`: `caseBuiltinDefault caseBuiltinData` (`Universe.hs:1036-1038`,
    * plutus 1.70.0.0).
    */
  case WithData

  /** `caseBuiltinDefault` (`PlutusCore/Default/Universe.hs:966-1008`, plutus 1.70.0.0): the branch
    * a constant selects and the constants it is applied to, or why there is none.
    */
  def select(scrutinee: Constant, branches: Vector[Term]): CaserBuiltin.Selection =
    import CaserBuiltin.Selection.*
    val len = branches.size
    def outOfBounds = Refused(CaseBuiltinError.OutOfBounds(scrutinee, len))
    this match
      case Unavailable(protocol) => Refused(CaseBuiltinError.Unavailable(protocol))
      case _ =>
        scrutinee match
          case Constant.Unit => if len == 1 then Branch(branches(0)) else outOfBounds
          // One branch is allowed when the scrutinee is `False` (`Universe.hs:979-985`,
          // plutus 1.70.0.0).
          case Constant.Bool(false) =>
            if len == 1 || len == 2 then Branch(branches(0)) else outOfBounds
          case Constant.Bool(true) => if len == 2 then Branch(branches(1)) else outOfBounds
          case Constant.Integer(x) =>
            if x.signum >= 0 && x < BigInt(len) then Branch(branches(x.toInt)) else outOfBounds
          case Constant.Data(x) => caseData(x, scrutinee, branches)
          case list: Constant.List =>
            if len == 1 || len == 2 then
              list.headOption match
                case Some(head)       => Applied(branches(0), head :: list.drop(1) :: Nil)
                case None if len == 1 => Refused(CaseBuiltinError.EmptyList)
                case None             => Branch(branches(1))
            else outOfBounds
          case Constant.Pair(l, r) =>
            if len == 1 then Applied(branches(0), l :: r :: Nil) else outOfBounds
          case other => Refused(CaseBuiltinError.UnsupportedType(other.typeOf))

  /** The handler of `Data`: refusal without it, `caseBuiltinData` with it (`Universe.hs:1019-1034`,
    * plutus 1.70.0.0): a `Constr` selects the branch of its tag, applied to its fields as a list,
    * the node's own (`someValueOf (DefaultUniList DefaultUniData) fields`, `Universe.hs:1040-1046`
    * of plutus 1.71.0.0 `261766ad`).
    */
  private def caseData(
      data: PlutusData,
      scrutinee: Constant,
      branches: Vector[Term]
  ): CaserBuiltin.Selection =
    import CaserBuiltin.Selection.*
    this match
      case WithData =>
        data match
          case PlutusData.Constr(tag, fields) =>
            if tag.signum >= 0 && tag < BigInt(branches.size) then
              Applied(branches(tag.toInt), Constant.List.OfData(fields) :: Nil)
            else Refused(CaseBuiltinError.OutOfBounds(scrutinee, branches.size))
          case _ => Refused(CaseBuiltinError.DataNotConstr)
      case _ => Refused(CaseBuiltinError.DataUnsupported)

object CaserBuiltin:
  /** `defaultCaserBuiltinFor` (`PlutusLedgerApi/Common/Eval.hs:143-148`, plutus 1.71.0.0
    * `261766ad`): unavailable before van Rossem (protocol 11), without `Data` before Dijkstra (12)
    * or for a program older than 1.2.0, with it otherwise. 1.70.0.0 (`Eval.hs:142-146` at
    * `507cab8c`) does not read the version, so it allows `Data` in a program of 1.0.0 or 1.1.0 from
    * protocol 12; below 12 the two agree.
    */
  def forProtocol(protocol: ProtocolMajor, version: ProgramVersion): CaserBuiltin =
    if protocol.value < ProtocolMajor.VanRossem.value then Unavailable(protocol)
    else if protocol.value < ProtocolMajor.Dijkstra.value ||
      summon[Ordering[ProgramVersion]].lt(version, ProgramVersion.V1_2_0)
    then WithoutData
    else WithData

  /** `HeadSpine`'s three cases (`PlutusCore/Builtin/KnownType.hs:339-343`, plutus 1.70.0.0). */
  enum Selection:
    /** `HeadOnly`: the branch, applied to nothing. */
    case Branch(branch: Term)

    /** `HeadSpine`: the branch applied to these constants, in order (never empty). */
    case Applied(branch: Term, arguments: List[Constant])

    /** `HeadError`. */
    case Refused(error: CaseBuiltinError)
