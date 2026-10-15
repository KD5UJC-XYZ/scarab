// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.consensus.Anchor
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.BootstrapState
import xyz.kd5ujc.scarab.consensus.ByronState
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.consensus.*
import xyz.kd5ujc.scarab.ledger.GenDelegPair
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.codec.Rational
import org.scalacheck.Gen
import scala.compiletime.{erasedValue, summonInline}
import scala.deriving.Mirror

/** ScalaCheck generators for every persisted type, derived from the same `Mirror` s as the codecs
  * so a field added to a state type is generated (and therefore round-trip tested) automatically.
  */
trait Gens[A]:
  def gen: Gen[A]

object Gens extends LowPriorityGens:
  def apply[A](using g: Gens[A]): Gen[A] = g.gen
  private def of[A](g: => Gen[A]): Gens[A] = new Gens[A]:
    lazy val gen: Gen[A] = g

  private val small: Gen[Int] = Gen.choose(0, 3)

  given Gens[Boolean] = of(Gen.oneOf(true, false))
  given Gens[Int] = of(Gen.oneOf(Gen.choose(-5, 5), Gen.choose(Int.MinValue, Int.MaxValue)))
  given Gens[Long] = of(Gen.oneOf(Gen.choose(0L, 5L), Gen.choose(Long.MinValue, Long.MaxValue)))
  given Gens[String] = of(Gen.alphaNumStr.map(_.take(12)))
  given Gens[BigInt] = of(
    Gen.oneOf(
      Gen.choose(-3L, 300L).map(BigInt(_)),
      Gen.choose(Long.MinValue, Long.MaxValue).map(BigInt(_)),
      Gen
        .choose(0L, Long.MaxValue)
        .flatMap(a => Gen.choose(1L, Long.MaxValue).map(b => BigInt(a) * b))
    )
  )
  given Gens[Bytes] = of(
    Gen.oneOf(
      Gen.const(Bytes.empty),
      Gen
        .choose(1, 40)
        .flatMap(n =>
          Gen.listOfN(n, Gen.choose(-128, 127).map(_.toByte)).map(b => Bytes(b.toVector))
        )
    )
  )
  given Gens[Rational] = of(
    for
      n <- Gens[BigInt]
      d <- Gen.choose(1L, 1000000L)
    yield Rational(n, d)
  )
  given [A](using g: Gens[A]): Gens[Option[A]] = of(Gen.option(g.gen))
  given [A](using g: Gens[A]): Gens[Vector[A]] =
    of(small.flatMap(Gen.listOfN(_, g.gen)).map(_.toVector))
  given [A](using g: Gens[A]): Gens[List[A]] = of(small.flatMap(Gen.listOfN(_, g.gen)))
  given [A](using g: Gens[A]): Gens[Set[A]] = of(small.flatMap(Gen.listOfN(_, g.gen)).map(_.toSet))
  given [K, V](using k: Gens[K], v: Gens[V]): Gens[Map[K, V]] =
    of(small.flatMap(Gen.listOfN(_, Gen.zip(k.gen, v.gen))).map(_.toMap))

  given nativeScript: Gens[NativeScript] = of(nativeScripts(2))
  private def nativeScripts(depth: Int): Gen[NativeScript] =
    val leaf = Gen.oneOf(
      Gens[Bytes].map(NativeScript.Sig(_)),
      Gens[BigInt].map(NativeScript.InvalidBefore(_)),
      Gens[BigInt].map(NativeScript.InvalidHereafter(_))
    )
    if depth == 0 then leaf
    else
      val inner = Gen.lzy(small.flatMap(Gen.listOfN(_, nativeScripts(depth - 1))).map(_.toVector))
      Gen.oneOf(
        leaf,
        inner.map(NativeScript.AllOf(_)),
        inner.map(NativeScript.AnyOf(_)),
        for
          n <- Gens[BigInt]
          ss <- inner
        yield NativeScript.NOfK(n, ss)
      )

  // Shared types are derived once, as in NodeCodecs.
  given credential: Gens[Credential] = auto
  given address: Gens[Address] = auto
  given ptr: Gens[Ptr] = auto
  given rewardAccount: Gens[RewardAccount] = auto
  given value: Gens[Value] = auto
  given datum: Gens[Datum] = auto
  given script: Gens[Script] = auto
  given txIn: Gens[TxIn] = auto
  given txOut: Gens[TxOut] = auto
  given drep: Gens[DRep] = auto
  given paramUpdate: Gens[ParamUpdate] = auto
  given poolParams: Gens[PoolParams] = auto
  given protocolParams: Gens[ProtocolParams] = auto
  given nonce: Gens[Nonce] = auto
  given era: Gens[Era] = auto
  given genDelegPair: Gens[GenDelegPair] = auto
  given govActionId: Gens[GovActionId] = auto
  given voter: Gens[Voter] = auto
  given vote: Gens[Vote] = auto
  given govAction: Gens[GovAction] = auto
  given proposal: Gens[Proposal] = auto
  given govAnchor: Gens[xyz.kd5ujc.scarab.ledger.Anchor] = auto
  given constitution: Gens[Constitution] = auto
  given committee: Gens[Committee] = auto
  given committeeAuth: Gens[CommitteeAuth] = auto
  given prevIds: Gens[PrevIds] = auto
  given govActionState: Gens[GovActionState] = auto
  given proposals: Gens[Proposals] = auto
  given enactState: Gens[EnactState] = auto
  given ratifyState: Gens[RatifyState] = auto
  given pulsingSnapshot: Gens[PulsingSnapshot] = auto
  given govState: Gens[GovState] = auto
  given drepState: Gens[DRepState] = auto
  given account: Gens[Account] = auto
  given stakeSnapshot: Gens[Snapshot] = auto
  given rewardUpdate: Gens[RewardUpdate] = auto
  given ledger: Gens[LedgerState] = auto
  given praos: Gens[PraosState] = auto
  given lite: Gens[LiteLedger] = auto
  given bootstrap: Gens[BootstrapState] = auto
  given byron: Gens[ByronState] = auto
  given blockRef: Gens[BlockRef] = auto
  given nodeState: Gens[NodeState] = auto
  given nodeSnapshot: Gens[StateSnapshot[NodeState]] = auto

trait LowPriorityGens:
  inline given auto[A](using m: Mirror.Of[A]): Gens[A] =
    val gens = LowPriorityGens.summonAll[m.MirroredElemTypes]
    inline m match
      case _: Mirror.SumOf[A]     => LowPriorityGens.sum[A](gens)
      case p: Mirror.ProductOf[A] => LowPriorityGens.product[A](p, gens)

object LowPriorityGens:
  inline def summonAll[T <: Tuple]: List[Gens[?]] = inline erasedValue[T] match
    case _: EmptyTuple => Nil
    case _: (t *: ts)  => summonInline[Gens[t]] :: summonAll[ts]

  def product[A](m: Mirror.ProductOf[A], gens: List[Gens[?]]): Gens[A] = new Gens[A]:
    lazy val gen: Gen[A] =
      gens
        .foldRight(Gen.const(List.empty[Any])) { (g, rest) =>
          for
            head <- g.gen
            tail <- rest
          yield head :: tail
        }
        .map { values =>
          m.fromProduct(new Product:
            def canEqual(that: Any): Boolean = false
            def productArity: Int = values.size
            def productElement(n: Int): Any = values(n))
        }

  def sum[A](gens: List[Gens[?]]): Gens[A] = new Gens[A]:
    lazy val gen: Gen[A] =
      Gen.choose(0, gens.size - 1).flatMap(i => gens(i).gen.asInstanceOf[Gen[A]])
