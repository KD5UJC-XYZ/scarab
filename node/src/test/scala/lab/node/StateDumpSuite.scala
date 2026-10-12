// SPDX-License-Identifier: Apache-2.0
package lab.node

import io.circe.Json
import io.circe.parser.parse

/** The digest definitions are shared with `scripts/oracle/digest.py`; the vector below was produced
  * by that script, so a change on either side that is not mirrored fails here.
  */
class StateDumpSuite extends munit.FunSuite:
  private val entries: List[(String, Json)] = List(
    "keyHash-aa" -> parse("""{"b":1,"a":[1,"z",null,true],"c":{"y":"é\"q","x":0}}""").toOption.get,
    "txid#0" -> Json.fromString("abc"),
    "zz" -> Json.fromBigInt(BigInt("12345678901234567890"))
  )

  test("canonical text sorts object keys and has no whitespace") {
    assertEquals(
      StateDump.canon(entries.head._2),
      """{"a":[1,"z",null,true],"b":1,"c":{"x":0,"y":"é\"q"}}"""
    )
  }

  test("the digest of a map equals the one the Python side computes") {
    val d = new StateDump.Digest
    entries.foreach((k, v) => d.add(k, StateDump.canon(v)))
    val j = d.json
    assertEquals(j.hcursor.get[Long]("count"), Right(3L))
    assertEquals(
      j.hcursor.get[String]("digest"),
      Right("a5b1f3f957573bb07fd0d8397839851cbc5245c80cac5d46ca04e58e0dda1cad")
    )
    val buckets = j.hcursor.get[List[String]]("buckets").toOption.get
    assertEquals(
      buckets.zipWithIndex.filterNot(_._1.startsWith("0:")).map(_._2),
      List(0, 9, 10)
    )
    assertEquals(
      buckets(0),
      "1:3d307e8b04b3a97dc95e798cf0204230ddaf8425115050259ce1801807418247"
    )
  }

  test("the digest does not depend on the order of the entries") {
    def digestOf(es: List[(String, Json)]) =
      val d = new StateDump.Digest
      es.foreach((k, v) => d.add(k, StateDump.canon(v)))
      d.json
    assertEquals(digestOf(entries), digestOf(entries.reverse))
  }

  test("only objects with more than the threshold of fields are replaced by digests") {
    val small = Json.fromFields((1 to StateDump.Threshold).map(i => s"k$i" -> Json.fromInt(i)))
    val big = Json.fromFields((1 to StateDump.Threshold + 1).map(i => s"k$i" -> Json.fromInt(i)))
    val reduced = StateDump.reduce(Json.obj("small" -> small, "big" -> big, "list" -> Json.arr(big)))
    assertEquals(reduced.hcursor.downField("small").focus, Some(small))
    assertEquals(
      reduced.hcursor.downField("big").get[Long]("count"),
      Right((StateDump.Threshold + 1).toLong)
    )
    assertEquals(
      reduced.hcursor.downField("list").downN(0).get[Long]("count"),
      Right((StateDump.Threshold + 1).toLong)
    )
  }

  test("targets: epoch boundaries, every Nth epoch, or a list of slots") {
    assertEquals(StateDump.Targets.parse("epoch-boundaries"), Some(StateDump.Targets.EpochBoundaries(1)))
    assertEquals(StateDump.Targets.parse("epoch-boundaries/5"), Some(StateDump.Targets.EpochBoundaries(5)))
    assertEquals(
      StateDump.Targets.parse("300, 100,200"),
      Some(StateDump.Targets.Slots(List(BigInt(100), BigInt(200), BigInt(300))))
    )
    assertEquals(StateDump.Targets.parse("epoch-boundaries/0"), None)
    assertEquals(StateDump.Targets.parse("soon"), None)
  }
