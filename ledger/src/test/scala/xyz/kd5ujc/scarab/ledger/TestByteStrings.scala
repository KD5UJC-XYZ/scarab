// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

/** The suites of this package read their encodings as a block of an era below decoder version 12
  * does ([[ByteStrings]]); a suite about another policy gives its own.
  */
given testByteStrings: ByteStrings = ByteStrings.Definite
