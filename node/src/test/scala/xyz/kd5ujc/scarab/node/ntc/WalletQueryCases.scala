// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.Json
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.{Credential as NodeCredential, PoolRelays, PoolRelay}
import xyz.kd5ujc.scarab.node.read.MirrorEraRead
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellState

/** Explicit current-state wallet fixtures, shared with the native Haskell API observer. */
private[ntc] object WalletQueryCases extends munit.Assertions:
  given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
  private def checked[A](r: Either[VocabError, A]): A = r.fold(e => fail(e.toString), identity)
  private def hash[R <: KeyRole](n: Int): KeyHash[R] =
    checked(KeyHash.fromBytes[R](Bytes(Vector.fill(28)(n.toByte))))
  private def coin(n: BigInt): Coin = Coin(n)
  private def compact(n: BigInt): CompactCoin = checked(CompactCoin.fromBigInt(n))
  private val owners = Vector.tabulate(4)(i => hash[Staking](i + 20))
  val credentials: Vector[Either[BigInt, NodeCredential]] = Vector(
    Left(BigInt(0)),
    Left(BigInt(100000000)),
    Left(BigInt(5000000000L)),
    Right(NodeCredential.Key(owners(0).bytes)),
    Right(NodeCredential.Script(Bytes(Vector.fill(28)(99.toByte)))),
    Right(NodeCredential.Key(Bytes(Vector.fill(28)(98.toByte))))
  )
  private val ids = Vector.tabulate(4)(i => hash[StakePool](i + 10))
  private val vrf = checked(VRFVerKeyHash.fromBytes(Bytes(Vector.fill(32)(1.toByte))))
  private def factsOf(state: xyz.kd5ujc.scarab.consensus.state.MirrorState): ConwayFacts =
    import xyz.kd5ujc.scarab.node.read.ChainRead.given
    QueryView(xyz.kd5ujc.scarab.protocol.ChainSync.Point.Origin, None, state).conway
      .fold(e => fail(e.toString), _.getOrElse(fail("no Conway state")))
  private val baseParams =
    NtcGolden.subjects.lastOption
      .fold(fail("no Conway fixture"))(s => factsOf(s._2))
      .l
      .params
      .copy(nOpt = BigInt(2), a0 = Rational(3, 10))
  private val amounts = Vector[BigInt](5000000000L, 3000000000L, 1500000000L, 500000000L)

  def state(zeroPot: Boolean, withHistory: Boolean, tied: Boolean): NewEpochState[Conway] =
    val params = baseParams.copy(a0 = if tied then Rational.zero else baseParams.a0)
    val empty = NewEpochState.empty[Conway](checked(EpochNo.fromBigInt(10)), params)
    val accounts =
      Sorted.map[Credential[Staking], ConwayAccountState](owners.zipWithIndex.map { (owner, i) =>
        Credential.KeyHashObj[Staking](owner) -> ConwayAccountState(
          compact(1000),
          compact(0),
          Some(ids(i)),
          None
        )
      })
    val pools = Sorted.map(ids.zipWithIndex.map { (id, i) =>
      id -> StakePoolState(
        vrf,
        coin(if i == 3 then amounts(i) + 2000 else 100000000),
        coin(if tied then 0 else 1000000),
        Rational.zero,
        Credential.KeyHashObj[Staking](owners(i)),
        Sorted.set(Vector(owners(i))),
        PoolRelays.encode(Vector(PoolRelay.MultiHostName(s"pool$i.example"))),
        None,
        compact(0),
        Sorted.set(Vector(Credential.KeyHashObj[Staking](owners(i))))
      )
    })
    val cert = empty.es.ls.cert.copy(
      dState = empty.es.ls.cert.dState.copy(accounts = ConwayAccounts(accounts)),
      pState = empty.es.ls.cert.pState.copy(stakePools = pools)
    )
    val instant = ConwayInstantStake(
      Sorted.map[Credential[Staking], CompactCoin](owners.zip(amounts).map { (o, amount) =>
        Credential.KeyHashObj[Staking](o) -> compact(amount)
      })
    )
    val likelihoods =
      if !withHistory then Sorted.empty[KeyHash[StakePool], Likelihood]
      else
        Sorted.map(ids.zipWithIndex.map { (id, i) =>
          id -> Likelihood(Vector.tabulate(100) { j =>
            LogWeight(
              if tied || i == 0 then 0.0f
              else (-math.pow((j.toDouble - (25 + i * 15)) / 12.0, 2)).toFloat
            )
          })
        })
    empty.copy(es =
      empty.es.copy(
        chainAccount =
          ChainAccountState(coin(1000000), coin(NtcGolden.queryEnv.maxSupply - 20000000000L)),
        ls = empty.es.ls.copy(cert = cert, utxo = empty.es.ls.utxo.copy(instantStake = instant)),
        nonMyopic = NonMyopic(likelihoods, coin(if zeroPot then 0 else 1000000000))
      )
    )

  val cases: Vector[(String, NewEpochState[Conway])] = Vector(
    ("current-history", state(false, true, false)),
    ("no-history", state(false, false, false)),
    ("zero-pot", state(true, true, false)),
    ("ranking-tie", state(false, true, true))
  )

  def mirror(n: NewEpochState[Conway]): xyz.kd5ujc.scarab.consensus.state.MirrorState =
    val base = NtcGolden.subjects.lastOption.fold(fail("no Conway fixture"))(_._2)
    import xyz.kd5ujc.scarab.consensus.state.EraLedgerState
    base.ext.ledgerState.state match
      case i: EraLedgerState.InShelleyEra[e] =>
        i.era match
          case ShelleyBasedEra.SConway =>
            base.copy(ext =
              base.ext.copy(ledgerState =
                base.ext.ledgerState.withState(
                  EraLedgerState.InShelleyEra(i.era, i.state.copy(nes = n))
                )
              )
            )
          case _ => fail("fixture is before Conway")
      case _ => fail("fixture is in Byron")

  def facts(n: NewEpochState[Conway]): ConwayFacts = new ConwayFacts(new MirrorEraRead(n))

  def main(args: Array[String]): Unit =
    val path = Path.of(args.headOption.getOrElse("/tmp/wallet-query-inputs.json"))
    val inputs = cases.map { (name, nes) =>
      val bytes = HaskellState.encode(nes).fold(e => fail(e.toString), identity)
      Json.obj(
        "name" -> Json.fromString(name),
        "state" -> Json.fromString(bytes.hex),
        "supply" -> Json.fromBigInt(NtcGolden.queryEnv.maxSupply),
        "credentials" -> Json.arr(credentials.map {
          case Left(n) => Json.obj("coin" -> Json.fromBigInt(n))
          case Right(c) =>
            val tag = c match
              case NodeCredential.Key(_)    => 0
              case NodeCredential.Script(_) => 1
            Json.obj(
              "credential" -> Json.fromString(CborOut.build(_.array(2).uint(tag).bytes(c.hash)).hex)
            )
        }*)
      )
    }
    Files.writeString(path, Json.arr(inputs*).spaces2)
