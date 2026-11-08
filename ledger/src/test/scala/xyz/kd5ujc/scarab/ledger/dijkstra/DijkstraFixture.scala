// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import io.circe.parser
import java.nio.file.{Files, Path}
import munit.Assertions

/** The Dijkstra genesis of the public Leios testnet (`fixtures/leios/testnet/config`). */
object DijkstraFixture extends Assertions:
  val genesis: DijkstraGenesis =
    val json = parser
      .parse(Files.readString(Path.of("fixtures/leios/testnet/config/dijkstra-genesis.json")))
      .fold(e => fail(e.message), identity)
    DijkstraGenesis.read(json).fold(e => fail(e.message), identity)
