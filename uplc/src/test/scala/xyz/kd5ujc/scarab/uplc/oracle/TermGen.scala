// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.oracle

import cats.data.State
import cats.syntax.all.*
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.uplc.*
import xyz.kd5ujc.scarab.uplc.cost.ExBudget
import xyz.kd5ujc.scarab.uplc.oracle.SplitRng.*

/** What the ledger evaluates a script with: its language and the major protocol version. */
final case class Profile(language: PlutusLanguage, protocol: ProtocolMajor):
  lazy val builtins: Vector[DefaultFun] =
    DefaultFun.values.toVector.filter(Availability.builtins(language, protocol).contains)
  lazy val versions: Set[ProgramVersion] = Availability.programVersions(language, protocol)
  def variant: SemanticsVariant = SemanticsVariant.of(language, protocol)
  def name: String = s"V${language.id + 1}-${protocol.value}"

object Profile:
  /** The legal profiles of the ledger, as the costing oracle has them: V1 at protocols 5 to 11, V2
    * at 7 to 11, V3 at 9 to 11.
    */
  val all: Vector[Profile] =
    (5 to 11).map(p => Profile(PlutusLanguage.V1, ProtocolMajor(p))).toVector ++
      (7 to 11).map(p => Profile(PlutusLanguage.V2, ProtocolMajor(p))) ++
      (9 to 11).map(p => Profile(PlutusLanguage.V3, ProtocolMajor(p)))

/** A generated script: the program, the `Data` arguments the ledger applies it to, and the budget
  * it is evaluated within.
  */
final case class Generated(
    profile: Profile,
    seed: Long,
    program: Program,
    arguments: Vector[PlutusData],
    budget: ExBudget
)

/** A generator of closed, well-scoped programs that run the machine and the builtins (lambdas and
  * applications, `force` and `delay`, `constr` and `case`, `case` on constants, every builtin the
  * profile has, applied fully and in part, recursion through a fixed point over a bounded counter),
  * so that most programs run to a value or an operational failure. Four programs in five are about
  * one builtin drawn from the profile's, applied to arguments of its parameter types; three in ten
  * may hold ill-typed or ill-formed terms and `error`, which fail structurally or operationally; a
  * few have a program version, a builtin or a `constr` the ledger refuses.
  *
  * A program is an unfold ([[Schemes.ana]]) of a [[TermGen.Seed]]: a goal (a type to produce, the
  * types of the variables in scope, a size and a generator) unfolds to a template of layers whose
  * leaves are goals again. The program is a function of the profile and a `Long`, so it is
  * reproduced from those and [[TermGen.Version]].
  */
object TermGen:
  /** The version of the generator, recorded with a fixture: a change to the programs a seed gives
    * bumps it.
    */
  val Version: Int = 1

  /** The types the generator works with: a constant's, a function's, a delayed term's, and a value
    * that no goal asks for (a builtin applied in part).
    */
  enum Ty:
    case C(uni: DefaultUni)
    case Fn(argument: Ty, result: Ty)
    case Delayed(ty: Ty)
    case Opaque

  /** A seed: a goal, or a layer already decided whose children are seeds. */
  enum Seed:
    case Goal(ty: Ty, env: Vector[Ty], fuel: Int, loops: Int, rng: SplitRng)
    case Layer(layer: TermF[Seed])

  /** What every goal of one program shares: the profile, whether `constr` and `case` may be written,
    * the builtins that may be named, and whether faults may be.
    */
  final case class Ctx(
      profile: Profile,
      constrCase: Boolean,
      builtins: Vector[DefaultFun],
      faults: Boolean
  ):
    def has(f: DefaultFun): Boolean = builtins.contains(f)
    def bls: Boolean = has(DefaultFun.Bls12_381_G1_uncompress)

  import Ty.*
  import DefaultFun.*

  private val SigType = Signature.Type

  // Templates.
  private def L(layer: TermF[Seed]): Seed = Seed.Layer(layer)
  private def lam(body: Seed): Seed = L(TermF.LamAbs(body))
  private def lams(n: Int, body: Seed): Seed = (1 to n).foldLeft(body)((b, _) => lam(b))
  private def app(f: Seed, a: Seed): Seed = L(TermF.Apply(f, a))
  private def apps(f: Seed, as: Seq[Seed]): Seed = as.foldLeft(f)(app)
  private def force(t: Seed): Seed = L(TermF.Force(t))
  private def delay(t: Seed): Seed = L(TermF.Delay(t))
  private def con(c: Constant): Seed = L(TermF.Constant(c))
  private def builtin(f: DefaultFun): Seed = L(TermF.Builtin(f))
  private def forced(t: Seed, n: Int): Seed = (1 to n).foldLeft(t)((s, _) => force(s))
  private def call(f: DefaultFun, as: Seed*): Seed =
    apps(forced(builtin(f), f.signature.forces), as)
  private def variable(i: Long): Seed = L(TermF.Var(DeBruijn(Word64.fromBits(i))))
  private val error: Seed = L(TermF.Error)
  private def caseOf(s: Seed, bs: Vector[Seed]): Seed = L(TermF.Case(s, bs))
  private def constr(tag: Long, fs: Vector[Seed]): Seed = L(TermF.Constr(Word64.fromBits(tag), fs))
  private def string(s: String): Seed = con(Constant.String(Text.fromString(s)))
  private val unit: Seed = con(Constant.Unit)

  private def goal(ty: Ty, env: Vector[Ty], fuel: Int, loops: Int): Draw[Seed] =
    fork.map(Seed.Goal(ty, env, fuel, loops, _))

  /** The variables of `env` of type `ty`, as indices. */
  private def variablesOf(ty: Ty, env: Vector[Ty]): Vector[Long] =
    env.zipWithIndex.collect { case (t, i) if t == ty => (env.size - i).toLong }

  /** `fuel - 1` cut into `parts` random shares. */
  private def shares(fuel: Int, parts: Int): Draw[Vector[Int]] =
    listOf(parts, between(1, 4)).map { weights =>
      val total = weights.sum
      weights.map(w => (fuel - 1).max(0) * w / total)
    }

  /** A random type: BLS12-381 values only where the profile has `uncompress`, and never in a pair,
    * whose constant cannot be built.
    */
  def uni(ctx: Ctx, depth: Int, inPair: Boolean = false): Draw[DefaultUni] =
    val bls = if ctx.bls && !inPair then 1 else 0
    val leaves = Vector(
      6 -> State.pure[SplitRng, DefaultUni](DefaultUni.Integer),
      4 -> State.pure[SplitRng, DefaultUni](DefaultUni.ByteString),
      2 -> State.pure[SplitRng, DefaultUni](DefaultUni.String),
      1 -> State.pure[SplitRng, DefaultUni](DefaultUni.Unit),
      3 -> State.pure[SplitRng, DefaultUni](DefaultUni.Bool),
      3 -> State.pure[SplitRng, DefaultUni](DefaultUni.Data),
      1 -> State.pure[SplitRng, DefaultUni](DefaultUni.Value),
      bls -> State.pure[SplitRng, DefaultUni](DefaultUni.Bls12_381_G1_Element),
      bls -> State.pure[SplitRng, DefaultUni](DefaultUni.Bls12_381_G2_Element),
      bls -> State.pure[SplitRng, DefaultUni](DefaultUni.Bls12_381_MlResult)
    )
    if depth <= 0 then pick(leaves)
    else
      pick(
        leaves ++ Vector(
          3 -> uni(ctx, depth - 1, inPair).map(DefaultUni.List(_)),
          2 -> (uni(ctx, depth - 1, true), uni(ctx, depth - 1, true)).mapN(DefaultUni.Pair(_, _)),
          1 -> uni(ctx, depth - 1, inPair).map(DefaultUni.Array(_))
        )
      )

  private def ty(ctx: Ctx, depth: Int): Draw[Ty] =
    pick(
      Vector(
        12 -> uni(ctx, 2).map(C(_)),
        1 -> (if depth <= 0 then uni(ctx, 1).map(C(_))
              else (ty(ctx, depth - 1), ty(ctx, depth - 1)).mapN(Fn(_, _))),
        1 -> (if depth <= 0 then uni(ctx, 1).map(C(_)) else ty(ctx, depth - 1).map(Delayed(_)))
      )
    )

  // Unification of a builtin's result with the type a goal asks for.
  private def bind(
      pattern: Signature.Type,
      target: DefaultUni,
      binding: Map[Int, Ty]
  ): Option[Map[Int, Ty]] =
    (pattern, target) match
      case (SigType.Variable(i), u) =>
        binding.get(i) match
          case None                         => Some(binding + (i -> C(u)))
          case Some(C(bound)) if bound == u => Some(binding)
          case _                            => None
      case (SigType.List(p), DefaultUni.List(u))       => bind(p, u, binding)
      case (SigType.Array(p), DefaultUni.Array(u))     => bind(p, u, binding)
      case (SigType.Pair(p, q), DefaultUni.Pair(u, v)) => bind(p, u, binding).flatMap(bind(q, v, _))
      case (p, u)                                      => p.ground.filter(_ == u).map(_ => binding)

  private def constantVariables(s: Signature): Set[Int] =
    s.parameters.collect { case Signature.Parameter.Constant(t) => t.variables }.flatten.toSet

  private def resultBinding(f: DefaultFun, target: Ty): Option[Map[Int, Ty]] =
    val s = f.signature
    target match
      case C(u) => bind(s.result, u, Map.empty)
      case other =>
        s.result match
          case SigType.Variable(i) if !constantVariables(s).contains(i) => Some(Map(i -> other))
          case _                                                        => None

  private def substitute(t: Signature.Type, binding: Map[Int, Ty]): DefaultUni =
    t match
      case SigType.Variable(i) =>
        binding.get(i) match
          case Some(C(u)) => u
          case _          => DefaultUni.Unit
      case SigType.List(e)    => DefaultUni.List(substitute(e, binding))
      case SigType.Array(e)   => DefaultUni.Array(substitute(e, binding))
      case SigType.Pair(a, b) => DefaultUni.Pair(substitute(a, binding), substitute(b, binding))
      case ground             => ground.ground.getOrElse(DefaultUni.Unit)

  /** Whether the generator can build a term of the type: a pair of a BLS12-381 value has no
    * constant and no builtin that makes one.
    */
  private def buildable(u: DefaultUni): Boolean = u match
    case DefaultUni.Pair(a, b) => !GeneratedConstants.hasBls(a) && !GeneratedConstants.hasBls(b)
    case DefaultUni.List(e)    => buildable(e)
    case DefaultUni.Array(e)   => buildable(e)
    case _                     => true

  /** Whether every constant parameter of `f` is buildable once its result is bound. */
  private def feasible(f: DefaultFun, binding: Map[Int, Ty]): Boolean =
    f.signature.parameters.forall {
      case Signature.Parameter.Constant(t) => buildable(substitute(t, binding))
      case Signature.Parameter.Opaque(_)   => true
    }

  /** The parameter types of `f` once its result is `target`; the variables the result leaves free
    * are drawn, never as BLS12-381 values.
    */
  private def parameters(ctx: Ctx, f: DefaultFun, binding: Map[Int, Ty]): Draw[Vector[Ty]] =
    val s = f.signature
    val constants = constantVariables(s)
    s.variables
      .filterNot(binding.contains)
      .foldLeft(State.pure[SplitRng, Map[Int, Ty]](binding)) { (acc, v) =>
        acc.flatMap(b =>
          (if constants.contains(v) then uni(ctx, 1, true).map(C(_)) else ty(ctx, 1))
            .map(t => b + (v -> t))
        )
      }
      .map(full =>
        s.parameters.map {
          case Signature.Parameter.Opaque(v)   => full.getOrElse(v, C(DefaultUni.Unit))
          case Signature.Parameter.Constant(t) => C(substitute(t, full))
        }
      )

  /** An argument of type `t`: a variable of that type when there is one, a third of the time. */
  private def argument(t: Ty, env: Vector[Ty], fuel: Int, loops: Int): Draw[Seed] =
    (chance(35), element(variablesOf(t, env))).tupled.flatMap {
      case (true, Some(i)) => State.pure(variable(i))
      case _               => goal(t, env, fuel, loops)
    }

  private def arguments(
      ts: Vector[Ty],
      env: Vector[Ty],
      fuel: Int,
      loops: Int
  ): Draw[Vector[Seed]] =
    shares(fuel, ts.size.max(1)).flatMap(fs =>
      ts.zip(fs).traverse((t, f) => argument(t, env, f, loops))
    )

  /** `f` applied to arguments of its parameter types: fully, or in part with the partial
    * application bound to a variable and applied to the rest later, or bound before it is forced.
    */
  private def application(
      ctx: Ctx,
      g: Seed.Goal,
      f: DefaultFun,
      binding: Map[Int, Ty]
  ): Draw[Seed] =
    val s = f.signature
    for
      ts <- parameters(ctx, f, binding)
      style <- below(100)
      inner = g.env :+ Opaque
      seed <-
        if style < 15 && ts.size >= 2 then
          for
            k <- between(1, ts.size - 1)
            first <- arguments(ts.take(k), g.env, g.fuel / 2, g.loops)
            rest <- arguments(ts.drop(k), inner, g.fuel / 2, g.loops)
          yield app(lam(apps(variable(1), rest)), apps(forced(builtin(f), s.forces), first))
        else if style < 25 && s.forces > 0 then
          arguments(ts, inner, g.fuel, g.loops)
            .map(as => app(lam(apps(forced(variable(1), s.forces), as)), builtin(f)))
        else arguments(ts, g.env, g.fuel, g.loops).map(as => apps(forced(builtin(f), s.forces), as))
    yield seed

  /** A term of type `t` with no goal of positive size below it. */
  private def leaf(ctx: Ctx, t: Ty, env: Vector[Ty], loops: Int): Draw[Seed] =
    def bytes(encodings: Vector[xyz.kd5ujc.scarab.codec.Bytes]): Draw[Seed] =
      pick(
        Vector(
          5 -> element(encodings).map(b => con(Constant.ByteString(b.getOrElse(encodings.head)))),
          1 -> GeneratedConstants.byteString.map(b => con(Constant.ByteString(b)))
        )
      )
    def hashed(f: DefaultFun): Draw[Seed] =
      (GeneratedConstants.byteString, GeneratedConstants.byteString).mapN((m, d) =>
        call(f, con(Constant.ByteString(m)), con(Constant.ByteString(d.take(300))))
      )
    t match
      case C(DefaultUni.Bls12_381_G1_Element) =>
        pick(
          Vector(
            4 -> bytes(GeneratedConstants.g1Encodings).map(call(Bls12_381_G1_uncompress, _)),
            1 -> hashed(Bls12_381_G1_hashToGroup)
          )
        )
      case C(DefaultUni.Bls12_381_G2_Element) =>
        pick(
          Vector(
            4 -> bytes(GeneratedConstants.g2Encodings).map(call(Bls12_381_G2_uncompress, _)),
            1 -> hashed(Bls12_381_G2_hashToGroup)
          )
        )
      case C(DefaultUni.Bls12_381_MlResult) =>
        (
          leaf(ctx, C(DefaultUni.Bls12_381_G1_Element), env, loops),
          leaf(ctx, C(DefaultUni.Bls12_381_G2_Element), env, loops)
        ).mapN(call(Bls12_381_millerLoop, _, _))
      case C(DefaultUni.List(e)) if GeneratedConstants.hasBls(e) =>
        chance(50).flatMap {
          case true => State.pure(con(Constant.List.empty(e)))
          case false =>
            leaf(ctx, C(e), env, loops).map(call(MkCons, _, con(Constant.List.empty(e))))
        }
      case C(DefaultUni.Array(e)) if GeneratedConstants.hasBls(e) =>
        if ctx.has(ListToArray) then
          leaf(ctx, C(DefaultUni.List(e)), env, loops).map(call(ListToArray, _))
        else State.pure(con(Constant.List.empty(e).toArray))
      case C(u)           => GeneratedConstants.of(u).map(con)
      case Fn(a, r)       => leaf(ctx, r, env :+ a, loops).map(lam)
      case Delayed(inner) => leaf(ctx, inner, env, loops).map(delay)
      case Opaque         => State.pure(unit)

  /** A recursion over a counter through the strict fixed point `Z`: `(Z step) k`, where
    * `step = \rec n -> force (ifThenElse (n <= 0) (delay base) (delay ((\r -> combine) (rec (n - 1)))))`.
    * One in twenty counts up instead, and only the budget ends it.
    */
  private def loop(g: Seed.Goal): Draw[Seed] =
    // Z = \f -> (\x -> f (\v -> x x v)) (\x -> f (\v -> x x v))
    val half = lam(app(variable(2), lam(apps(variable(2), Seq(variable(2), variable(1))))))
    val z = lam(app(half, half))
    val integer = C(DefaultUni.Integer)
    val inLoop = g.env :+ Fn(integer, g.ty) :+ integer
    for
      fs <- shares(g.fuel, 3)
      base <- goal(g.ty, inLoop, fs(0), g.loops + 1)
      combine <- goal(g.ty, inLoop :+ g.ty, fs(1), g.loops + 1)
      runaway <- chance(5)
      step = if runaway then AddInteger else SubtractInteger
      next = call(step, variable(1), con(Constant.Integer(1)))
      body = force(
        call(
          IfThenElse,
          call(LessThanEqualsInteger, variable(1), con(Constant.Integer(0))),
          delay(base),
          delay(app(lam(combine), app(variable(2), next)))
        )
      )
      constant <- chance(89)
      count <-
        if constant then between(0, 12).map(n => con(Constant.Integer(n)))
        else goal(integer, g.env, fs(2), g.loops + 1)
    yield app(app(z, lam(lam(body))), count)

  /** A term that is wrong for its place: a constant of another type, a function where a constant is
    * due, an application or a `force` of a constant, a builtin forced or applied against its
    * signature, a `case` of a function, a variable no binder binds, an extra `delay`.
    */
  private def illFormed(ctx: Ctx, g: Seed.Goal): Draw[Seed] =
    val other = uni(ctx, 1).flatMap(u =>
      if GeneratedConstants.hasBls(u) then State.pure(DefaultUni.Unit) else State.pure(u)
    )
    val small = g.fuel / 2
    pick(
      Vector(
        4 -> other.flatMap(GeneratedConstants.of).map(con),
        2 -> goal(g.ty, g.env :+ C(DefaultUni.Integer), small, g.loops).map(lam),
        2 -> (other.flatMap(GeneratedConstants.of), goal(g.ty, g.env, small, g.loops)).mapN(
          (c, a) => app(con(c), a)
        ),
        2 -> other.flatMap(GeneratedConstants.of).map(c => force(con(c))),
        1 -> goal(g.ty, g.env, small, g.loops).map(t => force(lam(t))),
        1 -> State.pure(force(builtin(AddInteger))),
        1 -> goal(C(DefaultUni.Bool), g.env, small, g.loops).map(b => app(builtin(IfThenElse), b)),
        1 -> State.pure(forced(builtin(Trace), 2)),
        (if ctx.constrCase then 1 else 0) -> goal(g.ty, g.env, small, g.loops).map(b =>
          caseOf(lam(unit), Vector(b))
        ),
        1 -> between(0, 3).map(k => variable(if k == 0 then 0L else (g.env.size + k).toLong)),
        1 -> goal(g.ty, g.env, small, g.loops).map(delay)
      )
    )

  /** `case (constr i fields) branches`: branch `i` takes the fields; the others take as many
    * arguments as they like and are never taken. One time in eight the tag has no branch.
    */
  private def constrCase(ctx: Ctx, g: Seed.Goal): Draw[Seed] =
    for
      n <- between(0, 3)
      fieldTypes <- listOf(n, ty(ctx, 1))
      branches <- between(1, 4)
      tag <- pick(
        Vector(
          14 -> below(branches).map(_.toLong),
          1 -> between(branches, branches + 2).map(_.toLong),
          1 -> oneOf(Long.MaxValue, -1L, Int.MaxValue.toLong + 1)
        )
      )
      fs <- shares(g.fuel, n + 1)
      fields <- fieldTypes.zip(fs).traverse((t, f) => argument(t, g.env, f, g.loops))
      taken <- goal(g.ty, g.env ++ fieldTypes, fs.last, g.loops)
      others <- (0 until branches).toVector.traverse { j =>
        if j.toLong == tag then State.pure(lams(n, taken))
        else
          for
            arity <- between(0, 3)
            types <- listOf(arity, ty(ctx, 0))
            body <- leaf(ctx, g.ty, g.env ++ types, g.loops)
          yield lams(arity, body)
      }
    yield caseOf(constr(tag, fields), others)

  /** `case` of a constant: a boolean, an integer, unit, a list or a pair (from protocol 11; before
    * it the caser refuses every constant).
    */
  private def constantCase(ctx: Ctx, g: Seed.Goal): Draw[Seed] =
    val b = C(DefaultUni.Bool)
    for
      fs <- shares(g.fuel, 3)
      kind <- below(5)
      element <- uni(ctx, 0)
      seed <- kind match
        case 0 =>
          (
            goal(b, g.env, fs(0), g.loops),
            goal(g.ty, g.env, fs(1), g.loops),
            goal(g.ty, g.env, fs(2), g.loops),
            between(1, 3)
          )
            .mapN((s, f, t, n) => caseOf(s, Vector(f, t, t).take(n)))
        case 1 =>
          (
            goal(C(DefaultUni.Integer), g.env, fs(0), g.loops),
            between(1, 4),
            goal(g.ty, g.env, fs(1), g.loops)
          )
            .mapN((s, n, t) => caseOf(s, Vector.fill(n)(t)))
        case 2 =>
          (goal(C(DefaultUni.Unit), g.env, fs(0), g.loops), goal(g.ty, g.env, fs(1), g.loops))
            .mapN((s, t) => caseOf(s, Vector(t)))
        case 3 =>
          val list = C(DefaultUni.List(element))
          (
            goal(list, g.env, fs(0), g.loops),
            goal(g.ty, g.env :+ C(element) :+ list, fs(1), g.loops),
            goal(g.ty, g.env, fs(2), g.loops),
            between(1, 2)
          ).mapN((s, cons, nil, n) => caseOf(s, Vector(lams(2, cons), nil).take(n)))
        case _ =>
          for
            second <- uni(ctx, 0, true)
            first = if GeneratedConstants.hasBls(element) then DefaultUni.Integer else element
            pair = C(DefaultUni.Pair(first, second))
            s <- goal(pair, g.env, fs(0), g.loops)
            t <- goal(g.ty, g.env :+ C(first) :+ C(second), fs(1), g.loops)
          yield caseOf(s, Vector(lams(2, t)))
    yield seed

  /** The template a goal unfolds to. */
  private def plan(ctx: Ctx, g: Seed.Goal): Draw[Seed] =
    val vars = variablesOf(g.ty, g.env)
    if g.fuel <= 0 then
      (chance(40), element(vars)).tupled.flatMap {
        case (true, Some(i)) => State.pure(variable(i))
        case _               => leaf(ctx, g.ty, g.env, g.loops)
      }
    else
      val candidates =
        ctx.builtins.flatMap(f => resultBinding(f, g.ty).filter(feasible(f, _)).map(f -> _))
      val delayed = variablesOf(Delayed(g.ty), g.env)
      val functions = g.env.zipWithIndex.collect {
        case (Fn(a, r), i) if r == g.ty => (a, (g.env.size - i).toLong)
      }
      val small = g.fuel - 1
      pick(
        Vector(
          3 -> leaf(ctx, g.ty, g.env, g.loops),
          (if vars.isEmpty then 0 else 6) -> element(vars).map(i => variable(i.getOrElse(1L))),
          (if candidates.isEmpty then 0 else 24) -> element(candidates).flatMap { c =>
            val (f, binding) = c.getOrElse(candidates.head)
            application(ctx, g, f, binding)
          },
          6 -> (ty(ctx, 1), shares(g.fuel, 2)).tupled.flatMap { (x, fs) =>
            (goal(g.ty, g.env :+ x, fs(0), g.loops), argument(x, g.env, fs(1), g.loops))
              .mapN((body, a) => app(lam(body), a))
          },
          3 -> goal(g.ty, g.env, small, g.loops).map(t => force(delay(t))),
          (if delayed.isEmpty then 0 else 4) -> element(delayed).map(i =>
            force(variable(i.getOrElse(1L)))
          ),
          (if functions.isEmpty then 0 else 4) -> element(functions).flatMap { found =>
            val (a, i) = found.getOrElse(functions.head)
            argument(a, g.env, small, g.loops).map(x => app(variable(i), x))
          },
          6 -> shares(g.fuel, 3).flatMap(fs =>
            (
              goal(C(DefaultUni.Bool), g.env, fs(0), g.loops),
              goal(g.ty, g.env, fs(1), g.loops),
              goal(g.ty, g.env, fs(2), g.loops)
            ).mapN((c, t, e) => force(call(IfThenElse, c, delay(t), delay(e))))
          ),
          (if ctx.constrCase then 6 else 0) -> constrCase(ctx, g),
          (if ctx.constrCase then 3 else 0) -> constantCase(ctx, g),
          (if g.fuel >= 8 && g.loops < 2 then 3 else 0) -> loop(g),
          (if ctx.faults then 3 else 0) -> illFormed(ctx, g),
          (if ctx.faults then 1 else 0) -> State.pure(error)
        )
      )

  /** One layer of the program: a decided layer, or the first layer of a goal's template. */
  @tailrec
  private def layer(ctx: Ctx)(seed: Seed): TermF[Seed] = seed match
    case Seed.Layer(l) => l
    case g: Seed.Goal  => layer(ctx)(plan(ctx, g).runA(g.rng).value)

  /** The term a seed unfolds to. */
  def unfold(ctx: Ctx, seed: Seed): Term = Schemes.ana[TermF, Seed](seed)(layer(ctx))

  /** A full application of `f`, the builtin a program is about, with its type: the variables of its
    * scheme drawn, never as a pair of BLS12-381 values. Choosing the builtin first gives every
    * builtin of the profile the same share of programs, whatever its result type.
    */
  private def focusOn(ctx: Ctx, f: DefaultFun, env: Vector[Ty], fuel: Int): Draw[(Ty, Seed)] =
    val s = f.signature
    val constants = constantVariables(s)
    for
      binding <- s.variables.foldLeft(State.pure[SplitRng, Map[Int, Ty]](Map.empty)) { (acc, v) =>
        acc.flatMap(b =>
          (if constants.contains(v) then uni(ctx, 1, true).map(C(_)) else ty(ctx, 1)).map(t =>
            b + (v -> t)
          )
        )
      }
      result = s.result match
        case SigType.Variable(i) => binding.getOrElse(i, C(DefaultUni.Unit))
        case other               => C(substitute(other, binding))
      rng <- fork
      seed <- application(ctx, Seed.Goal(result, env, fuel, 0, rng), f, binding)
    yield (result, seed)

  private val versions: Vector[ProgramVersion] = Vector(
    ProgramVersion.V1_0_0,
    ProgramVersion.V1_1_0,
    ProgramVersion.of(1, 2, 0).getOrElse(ProgramVersion.V1_1_0),
    ProgramVersion.of(2, 0, 0).getOrElse(ProgramVersion.V1_1_0)
  )

  /** The budgets: the main oracle's (`10^10` CPU and `1.4 * 10^7` memory, `Main.hs`) most of the
    * time, otherwise one small enough to run out.
    */
  private val budget: Draw[ExBudget] = pick(
    Vector(
      4 -> State.pure[SplitRng, ExBudget](ExBudget.of(10000000000L, 14000000L)),
      1 -> (between(4, 9), between(3, 7), between(1, 9), between(1, 9)).mapN((c, m, a, b) =>
        ExBudget.of(a * math.pow(10, c).toLong, b * math.pow(10, m).toLong)
      )
    )
  )

  /** The program of `seed` for `profile`, its arguments and budget. The top of the program takes
    * the arguments, computes a value `v` of a random type, and makes it visible: a trace of whether
    * a condition on `v` holds, then unit (V3, nine times in ten) or `v`.
    */
  def generate(profile: Profile, seed: Long): Generated =
    val draw = for
      version <- weighted(
        Vector(
          (if profile.versions.contains(ProgramVersion.V1_1_0) then 85
           else 0) -> ProgramVersion.V1_1_0,
          15 -> ProgramVersion.V1_0_0
        )
      )
      odd <- chance(3)
      stray <- element(versions)
      chosen = if odd then stray.getOrElse(version) else version
      forcedCase <- chance(2)
      allBuiltins <- chance(1)
      faults <- chance(30)
      ctx = Ctx(
        profile,
        chosen.allowsConstrAndCase || forcedCase,
        if allBuiltins then
          DefaultFun.values.toVector.filterNot(Availability.Batch.Batch7.builtins.contains)
        else profile.builtins,
        faults
      )
      expected = if profile.language == PlutusLanguage.V3 then 1 else 3
      count <- weighted(Vector(4 -> 0, 5 -> expected, 1 -> (expected - 1), 1 -> (expected + 1)))
      lambdas <- weighted(Vector(19 -> count, 1 -> (count + 1)))
      arguments <- listOf(count, between(0, 3).flatMap(GeneratedConstants.data(_, wide = false)))
      fuel <- between(3, 28)
      env = Vector.fill(lambdas)(C(DefaultUni.Data))
      focused <- chance(80)
      focus <- element(ctx.builtins)
      (t, main) <- (focus.filter(_ => focused) match
        case Some(f) => focusOn(ctx, f, env, fuel)
        case None    => ty(ctx, 1).flatMap(t => goal(t, env, fuel, 0).map(t -> _))
      )
      condition <- goal(C(DefaultUni.Bool), env :+ t, fuel / 3, 0)
      unitResult <- chance(if profile.language == PlutusLanguage.V3 then 90 else 30)
      tag <- below(1000)
      result = if unitResult then unit else variable(1)
      observe = force(
        call(
          IfThenElse,
          condition,
          delay(call(Trace, string(s"$tag:1"), result)),
          delay(call(Trace, string(s"$tag:0"), result))
        )
      )
      top = lams(lambdas, app(lam(observe), main))
      limit <- budget
    yield Generated(profile, seed, Program(chosen, unfold(ctx, top)), arguments, limit)
    draw.runA(SplitRng(seed)).value
