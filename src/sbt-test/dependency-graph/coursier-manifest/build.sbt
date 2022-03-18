import ch.epfl.scala.githubapi.DependencyRelationship
import ch.epfl.scala.githubapi.DependencyScope
import ch.epfl.scala.githubapi.DependencyNode

val checkManifest = taskKey[Unit]("Check the Github manifest of a project")

inThisBuild(
  Seq(
    organization := "ch.epfl.scala",
    version := "1.2.0-SNAPSHOT",
    useCoursier := true,
    scalaVersion := "2.12.15"
  )
)

lazy val p1 = project
  .in(file("p1"))
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-generic" % "0.14.1",
      "org.tpolecat" %% "doobie-core" % "0.13.4",
      "org.scalatest" %% "scalatest" % "3.2.2" % Test
    ),
    checkManifest := {
      val manifest = githubDependencyManifest.value
      val resolved = manifest.resolved

      assert(manifest.name == "ch.epfl.scala:p1_2.12:1.2.0-SNAPSHOT")

      // all dependencies are defined
      assert(resolved.values.forall(n => n.dependencies.forall(resolved.contains)))

      val circe = resolved("io.circe:circe-generic_2.12:0.14.1")
      checkDependencyNode(circe)(
        DependencyRelationship.direct,
        DependencyScope.runtime,
        Seq("com.chuusai:shapeless_2.12:2.3.7")
      )

      val doobie = resolved("org.tpolecat:doobie-core_2.12:0.13.4")
      checkDependencyNode(doobie)(
        DependencyRelationship.direct,
        DependencyScope.runtime,
        Seq("com.chuusai:shapeless_2.12:2.3.7")
      )

      val shapeless = resolved("com.chuusai:shapeless_2.12:2.3.7")
      checkDependencyNode(shapeless)(
        DependencyRelationship.indirect,
        DependencyScope.runtime
      )

      val scalatest = resolved("org.scalatest:scalatest_2.12:3.2.2")
      checkDependencyNode(scalatest)(
        DependencyRelationship.direct,
        DependencyScope.development,
        Seq("org.scalatest:scalatest-core_2.12:3.2.2")
      )

      val scalatestCore = resolved("org.scalatest:scalatest-core_2.12:3.2.2")
      checkDependencyNode(scalatestCore)(
        DependencyRelationship.indirect,
        DependencyScope.development
      )
    }
  )

lazy val p2 = project
  .in(file("p2"))
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http" % "10.2.8"
    ),
    checkManifest := {
      val manifest = githubDependencyManifest.value
      val resolved = manifest.resolved

      assert(manifest.name == "ch.epfl.scala:p2_2.12:1.2.0-SNAPSHOT")

      // all dependencies are defined
      assert(resolved.values.forall(n => n.dependencies.forall(resolved.contains)))

      val akkaHttp = resolved("com.typesafe.akka:akka-http_2.12:10.2.8")
      checkDependencyNode(akkaHttp)(
        DependencyRelationship.direct,
        DependencyScope.runtime
      )

      val p1Node = resolved("ch.epfl.scala:p1_2.12:1.2.0-SNAPSHOT")
      checkDependencyNode(p1Node)(
        DependencyRelationship.direct,
        DependencyScope.runtime
      )

      // transitively depends on circe through p1
      val circe = resolved("io.circe:circe-generic_2.12:0.14.1")
      checkDependencyNode(circe)(
        DependencyRelationship.indirect,
        DependencyScope.runtime,
        Seq("com.chuusai:shapeless_2.12:2.3.7")
      )

      // p2 does not depend on scalatest
      assert(resolved.get("org.scalatest:scalatest_2.12:3.2.2").isEmpty)
    }
  )
  .dependsOn(p1)

def checkDependencyNode(node: DependencyNode)(
    relationship: DependencyRelationship,
    scope: DependencyScope,
    deps: Seq[String] = Seq.empty
): Unit = {
  assert(node.purl.isDefined)
  assert(node.relationship.contains(relationship))
  assert(node.scope.contains(scope))
  deps.foreach(d => assert(node.dependencies.contains(d)))
}
