// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import java.util.Locale
import munit.FunSuite

/** The JVM's default locale, in every category, set for one test and restored after it: code that
  * must not depend on the host's locale is checked under the locales where it would.
  */
object DefaultLocale:
  /** Turkish and Azeri lower `I` to a dotless `ı` (and upper `i` to a dotted `İ`). */
  val Turkish: Locale = Locale.forLanguageTag("tr-TR")
  val Azeri: Locale = Locale.forLanguageTag("az")

  /** The locales a check runs under: those that differ first, then the root locale. */
  val checked: Vector[Locale] = Vector(Turkish, Azeri, Locale.ROOT)

  def label(locale: Locale): String = if locale == Locale.ROOT then "root" else locale.toLanguageTag

  /** The defaults that [[set]] replaced. */
  final case class Saved(default: Locale, display: Locale, format: Locale)

  /** `locale` as the default of every category; the defaults it replaces. */
  def set(locale: Locale): Saved =
    val saved = Saved(
      Locale.getDefault,
      Locale.getDefault(Locale.Category.DISPLAY),
      Locale.getDefault(Locale.Category.FORMAT)
    )
    Locale.setDefault(locale)
    saved

  def restore(saved: Saved): Unit =
    Locale.setDefault(saved.default)
    Locale.setDefault(Locale.Category.DISPLAY, saved.display)
    Locale.setDefault(Locale.Category.FORMAT, saved.format)

/** A suite's fixture that runs a test under a default locale ([[DefaultLocale]]). No other test
  * runs while it is set (`build.sbt`: `Tags.limit(Tags.Test, 1)` and
  * `Test / parallelExecution := false`); sbt's own threads share the JVM and see it for the
  * duration of the test.
  */
trait DefaultLocaleFixture:
  self: FunSuite =>

  def underDefault(locale: Locale): FunFixture[DefaultLocale.Saved] =
    FunFixture[DefaultLocale.Saved](_ => DefaultLocale.set(locale), DefaultLocale.restore)
