// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import io.circe.{Json, parser}
import munit.FunSuite
import scala.io.Source
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.rules.{RewardPulserFixture, Rewards}
import xyz.kd5ujc.scarab.ledger.state.*

/** Real captured pool stakes/block counts and unchanged native Float bit patterns. Account and
  * pool-registration details are synthetic: the test claims likelihood, not full-state replay.
  */
class RewardLikelihoodSuite extends FunSuite:
  import RewardPulserFixture.*
  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway

  private def load(name: String): Json =
    val stream =
      Option(getClass.getClassLoader.getResourceAsStream(s"reward-likelihood-1.19.0.1/$name.gz"))
        .getOrElse(fail(s"missing likelihood resource $name"))
    val source = Source.fromInputStream(new java.util.zip.GZIPInputStream(stream))
    val text =
      try source.mkString
      finally source.close()
    parser.parse(text).fold(e => fail(e.toString), identity)

  private def field[A: io.circe.Decoder](json: Json, name: String): A =
    json.hcursor.get[A](name).fold(e => fail(e.toString), identity)

  private def id(json: Json): KeyHash[StakePool] =
    val bytes = Bytes.fromHex(field[String](json, "pool")).fold(e => fail(e.toString), identity)
    ok(KeyHash.fromBytes[StakePool](bytes))

  private def array(json: Json): Vector[Json] = json.asArray.getOrElse(fail("expected array"))

  private val input = load("preview-pools.json")
  private val pools = field[Vector[Json]](input, "pools")
  private val totalStake = field[BigInt](input, "totalStake")
  private val base = conway(5, pools.size, pools.size)
  private val rows = pools.zipWithIndex.map { (row, index) =>
    val amount = field[BigInt](row, "stake")
    val template = base.es.snapshots.go.pools(pool(index))
    id(row) -> template.copy(stake = compact(amount))
  }
  private val active = ActiveStake(Sorted.map(pools.zipWithIndex.flatMap { (row, index) =>
    val amount = field[BigInt](row, "stake")
    Option.when(amount > 0)(
      credential(index) -> StakeWithDelegation(ok(NonZero.from(compact(amount))), id(row))
    )
  }))
  private val go = SnapShot(active, Sorted.map(rows))
  private val es = base.es.copy(
    chainAccount = base.es.chainAccount.copy(reserves = coin(Supply - totalStake)),
    snapshots = base.es.snapshots.copy(go = go)
  )
  private val blocks = BlocksMade(Sorted.map(pools.flatMap { row =>
    val count = field[BigInt](row, "blocks")
    Option.when(count > 0)(id(row) -> ok(Word64.fromBigInt(VocabError.Kind.BlockCount, count)))
  }))
  private val realGlobals =
    globals.copy(geometry = globals.geometry.copy(epochLength = 86400, securityParam = 432))

  private def likelihoods(
      json: Json
  ): scala.collection.immutable.SortedMap[KeyHash[StakePool], Likelihood] =
    Sorted.map(
      array(json).map(row =>
        id(row) -> Likelihood(
          field[Vector[Long]](row, "bits").map(bits =>
            LogWeight(java.lang.Float.intBitsToFloat(bits.toInt))
          )
        )
      )
    )

  private def bits(ls: scala.collection.immutable.SortedMap[KeyHash[StakePool], Likelihood]) =
    ls.transform((_, likelihood) =>
      likelihood.weights.map(w => java.lang.Float.floatToRawIntBits(w.float))
    )

  test(
    "all 72,700 generated Float samples equal the original Haskell bits for real Preview pools"
  ) {
    val started = Rewards.start(es, blocks, realGlobals, 5).fold(e => fail(e.toString), identity)
    val want = likelihoods(load("preview-likelihood-bits.json"))
    assertEquals(want.size, 727)
    assertEquals(want.valuesIterator.map(_.weights.size).sum, 72700)
    started match
      case PulsingRewUpdate.Pulsing(snapshot, _) =>
        assertEquals(bits(snapshot.likelihoods), bits(want))
      case _ => fail("start must retain pending state")
    val completed = Rewards.complete(started).fold(e => fail(e.toString), identity)
    assertEquals(
      bits(completed.nonMyopic.likelihoods),
      bits(likelihoods(load("preview-completed-bits.json")))
    )
  }

  test("real pool likelihoods decay and normalize a native-generated preceding history exactly") {
    val previous = NonMyopic(likelihoods(load("preview-completed-bits.json")), coin(0))
    val started = Rewards
      .start(es.copy(nonMyopic = previous), blocks, realGlobals, 5)
      .fold(e => fail(e.toString), identity)
    val completed = Rewards.complete(started).fold(e => fail(e.toString), identity)
    assertEquals(
      bits(completed.nonMyopic.likelihoods),
      bits(likelihoods(load("preview-history-completed-bits.json")))
    )
  }
