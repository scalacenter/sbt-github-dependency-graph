def isRelease() =
  System.getenv("GITHUB_REPOSITORY") == "scalacenter/sbt-github-dependency-graph" &&
    System.getenv("GITHUB_WORKFLOW") == "Release"

def isCI = System.getenv("CI") != null

inThisBuild(
  Seq(
    organization := "ch.epfl.scala",
    homepage := Some(url("https://github.com/scalacenter/sbt-github-dependency-graph")),
    onLoadMessage := s"Welcome to sbt-github-dependency-graph ${version.value}",
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := Developers.all,
    version ~= { dynVer =>
      if (isRelease) dynVer
      else "0.1.0-SNAPSHOT" // only for local publishing
    }
  )
)

val `sbt-github-dependency-graph` = project
  .in(file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-debug-adapter",
    sbtVersion := "1.4.9",
    scalaVersion := "2.12.15",
    scriptedLaunchOpts += s"-Dplugin.version=${version.value}",
    scriptedBufferLog := false,
    scriptedDependencies := {
      publishLocal.value
    }
  )
