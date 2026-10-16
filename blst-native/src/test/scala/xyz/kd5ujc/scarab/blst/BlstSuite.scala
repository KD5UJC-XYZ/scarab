// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.blst

import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import cats.effect.IO
import cats.mtl.Handle
import munit.CatsEffectSuite
import supranational.blst.blstJNI

/** The portable libblst is the library blst-java loads, it exports what `blstJNI` calls, and the
  * start-up check decides as ADR 0012 says.
  */
class BlstSuite extends CatsEffectSuite:
  private val all = CpuFeature.values.toSet

  private def checked: IO[Either[BlstProblem, BlstReport]] =
    Handle
      .allow[BlstProblem](Blst.verify[IO].map(Right(_)))
      .rescue(problem => IO.pure(Left(problem)))

  test("blst-java loads the portable library of this repository") {
    Blst.build[IO].map { build =>
      if Platform.current == Platform.LinuxAmd64 then assertEquals(build, LibraryBuild.Portable)
      else assert(build != LibraryBuild.Portable)
    }
  }

  test("the start-up check passes here, with the known answers computed") {
    checked.map(r => assert(r.isRight, r.toString))
  }

  test("the resource name is the one blstJNI builds") {
    val props = Map("os.name" -> "Linux", "os.arch" -> "amd64")
    assertEquals(Blst.resourceName(props.get), "Linux/amd64/libblst.so")
    assertEquals(
      Blst.resourceName(Map("os.name" -> "Mac OS X", "os.arch" -> "aarch64").get),
      s"Mac/aarch64/${System.mapLibraryName("blst")}"
    )
    assertEquals(
      Blst.resourceName((props + ("supranational.blst.jniResource" -> "/x/libblst.so")).get),
      "/x/libblst.so"
    )
  }

  test("every native method of blstJNI is exported by the library (SWIG glue and class match)") {
    val exported = scala.io.Source
      .fromResource("blst-exports.txt", getClass.getClassLoader)(using
        scala.io.Codec(StandardCharsets.UTF_8)
      )
      .getLines()
      .toSet
    def mangle(s: String) = s.replace("_", "_1")
    val natives =
      classOf[blstJNI].getDeclaredMethods
        .filter(m => Modifier.isNative(m.getModifiers))
        .map(_.getName)
    val missing =
      natives.filterNot(n => exported.contains(s"Java_supranational_blst_blstJNI_${mangle(n)}"))
    assert(natives.nonEmpty)
    assertEquals(missing.toList, Nil)
  }

  test("/proc/cpuinfo: the features every processor has") {
    val both =
      "processor : 0\nflags\t\t: fpu sse2 bmi2 adx avx2\nprocessor : 1\nflags\t\t: fpu adx bmi2\n"
    assertEquals(CpuSupport.parse(both), CpuSupport.Known(all))
    val athlon = "processor : 0\nflags : fpu sse2 3dnow lm\n"
    assertEquals(CpuSupport.parse(athlon), CpuSupport.Known(Set.empty))
    assertEquals(CpuSupport.parse(athlon).missing, all)
    val hybrid = "flags : adx bmi2\nflags : bmi2\n"
    assertEquals(CpuSupport.parse(hybrid), CpuSupport.Known(Set(CpuFeature.Bmi2)))
    assertEquals(CpuSupport.parse(""), CpuSupport.Unknown)
  }

  private val other = LibraryBuild.Other("00")

  test("admission: a library that is not the portable one needs a processor with every feature") {
    val known = CpuSupport.Known(all)
    val missing = CpuSupport.Known(Set(CpuFeature.Bmi2))
    def report(p: Platform, cpu: CpuSupport, b: LibraryBuild) = BlstReport(p, cpu, b)
    // the portable build runs everywhere
    for cpu <- List(known, missing, CpuSupport.Unknown) do
      assert(Blst.admissible(report(Platform.LinuxAmd64, cpu, LibraryBuild.Portable)).isRight)
    // another build: only on a processor shown to have the features
    assert(Blst.admissible(report(Platform.LinuxAmd64, known, other)).isRight)
    assertEquals(
      Blst.admissible(report(Platform.LinuxAmd64, missing, other)),
      Left(BlstProblem.LibraryNeedsMissingCpuFeatures(Set(CpuFeature.Adx), other))
    )
    assertEquals(
      Blst.admissible(report(Platform.LinuxAmd64, CpuSupport.Unknown, LibraryBuild.OnLibraryPath)),
      Left(BlstProblem.LibraryNeedsMissingCpuFeatures(Set.empty, LibraryBuild.OnLibraryPath))
    )
    // other platforms are not affected
    assert(Blst.admissible(report(Platform.Other, missing, other)).isRight)
  }
end BlstSuite
