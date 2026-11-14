// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import xyz.kd5ujc.scarab.uplc.{Constant, DefaultUni, PlutusData, ProtocolMajor, Term}

/** How `case` treats a constant: the reference's `CaserBuiltin`
  * (`PlutusCore/Builtin/Case.hs:45-67`, plutus 1.70.0.0 `507cab8c`), a parameter of the machine.
  * The ledger chooses it by protocol ([[CaserBuiltin.forProtocol]]); the upstream conformance
  * executable uses [[WithData]] whatever the program
  * (`plutus-conformance/src/PlutusConformance/CekEvaluator.hs:46-61`).
  */
enum CaserBuiltin:
  /** `unavailableCaserBuiltin`: every constant is refused (`Case.hs:63-67`). */
  case Unavailable(protocol: ProtocolMajor)

  /** `caseBuiltinNoData`: casing on every supported type but `Data` (`Universe.hs:1011-1017`). */
  case WithoutData

  /** `caseBuiltin` of `DefaultUni`: `caseBuiltinDefault caseBuiltinData` (`Universe.hs:1036-1038`).
    */
  case WithData

  /** `caseBuiltinDefault` (`PlutusCore/Default/Universe.hs:966-1008`): the branch a constant
    * selects and the constants it is applied to, or why there is none.
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
          // One branch is allowed when the scrutinee is `False` (`Universe.hs:979-985`).
          case Constant.Bool(false) =>
            if len == 1 || len == 2 then Branch(branches(0)) else outOfBounds
          case Constant.Bool(true) => if len == 2 then Branch(branches(1)) else outOfBounds
          case Constant.Integer(x) =>
            if x.signum >= 0 && x < BigInt(len) then Branch(branches(x.toInt)) else outOfBounds
          case Constant.Data(x) => caseData(x, scrutinee, branches)
          case list: Constant.List =>
            if len == 1 || len == 2 then
              list.items.headOption match
                case Some(head)       => Applied(branches(0), head :: list.drop(1) :: Nil)
                case None if len == 1 => Refused(CaseBuiltinError.EmptyList)
                case None             => Branch(branches(1))
            else outOfBounds
          case Constant.Pair(l, r) =>
            if len == 1 then Applied(branches(0), l :: r :: Nil) else outOfBounds
          case other => Refused(CaseBuiltinError.UnsupportedType(other.typeOf))

  /** The handler of `Data`: refusal without it, `caseBuiltinData` with it
    * (`Universe.hs:1019-1034`): a `Constr` selects the branch of its tag, applied to its fields as
    * a list.
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
              Applied(branches(tag.toInt), CaserBuiltin.dataList(fields) :: Nil)
            else Refused(CaseBuiltinError.OutOfBounds(scrutinee, branches.size))
          case _ => Refused(CaseBuiltinError.DataNotConstr)
      case _ => Refused(CaseBuiltinError.DataUnsupported)

object CaserBuiltin:
  /** `defaultCaserBuiltinFor` (`PlutusLedgerApi/Common/Eval.hs:142-146`): unavailable before van
    * Rossem (protocol 11), without `Data` before Dijkstra (12).
    */
  def forProtocol(protocol: ProtocolMajor): CaserBuiltin =
    if protocol.value < ProtocolMajor.VanRossem.value then Unavailable(protocol)
    else if protocol.value < ProtocolMajor.Dijkstra.value then WithoutData
    else WithData

  /** `HeadSpine`'s three cases (`PlutusCore/Builtin/KnownType.hs:339-343`). */
  enum Selection:
    /** `HeadOnly`: the branch, applied to nothing. */
    case Branch(branch: Term)

    /** `HeadSpine`: the branch applied to these constants, in order (never empty). */
    case Applied(branch: Term, arguments: List[Constant])

    /** `HeadError`. */
    case Refused(error: CaseBuiltinError)

  // Every element is a `Data`, so the list is well typed and `of` cannot refuse it.
  private def dataList(fields: Vector[PlutusData]): Constant =
    Constant.List
      .of(DefaultUni.Data, fields.map(Constant.Data(_)))
      .getOrElse(Constant.List.empty(DefaultUni.Data))
