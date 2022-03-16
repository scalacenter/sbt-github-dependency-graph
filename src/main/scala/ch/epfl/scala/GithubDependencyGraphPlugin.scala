package ch.epfl.scala

import java.nio.charset.StandardCharsets
import java.time.Instant

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Properties

import ch.epfl.scala.githubapi.DependencyNode
import ch.epfl.scala.githubapi.DependencyRelationship
import ch.epfl.scala.githubapi.DependencyScope
import ch.epfl.scala.githubapi.DependencySnapshot
import ch.epfl.scala.githubapi.DetectorMetadata
import ch.epfl.scala.githubapi.Job
import ch.epfl.scala.githubapi.JsonProtocol._
import gigahorse.HttpClient
import gigahorse.support.okhttp.Gigahorse
import sbt.Scoped.richTaskSeq
import sbt._
import sbt.plugins.IvyPlugin
import sjsonnew.shaded.scalajson.ast.unsafe.JString
import sjsonnew.shaded.scalajson.ast.unsafe.JValue
import sjsonnew.support.scalajson.unsafe.CompactPrinter
import sjsonnew.support.scalajson.unsafe.Converter
import sjsonnew.support.scalajson.unsafe.Parser

object GithubDependencyGraphPlugin extends AutoPlugin {
  private lazy val http: HttpClient = Gigahorse.http(Gigahorse.config)
  private val runtimeConfigs =
    Set(
      Compile,
      Configurations.CompileInternal,
      Runtime,
      Configurations.RuntimeInternal,
      Provided,
      Optional,
      Configurations.System
    )
      .map(_.toConfigRef)

  object autoImport {
    val githubDependencyManifest: TaskKey[githubapi.Manifest] = taskKey("The dependency manifest of the project")
    val githubJob: TaskKey[Job] = taskKey("The current Github action job")
    val githubDependencySnapshot: TaskKey[DependencySnapshot] = taskKey(
      "The dependency snapshot of the build in a Github action job."
    )
    val submitGithubDependencyGraph: TaskKey[URL] = taskKey(
      "Submit the dependency snapshot of the build in a Github action job"
    )
  }

  import autoImport._

  override def trigger = allRequirements
  override def requires: Plugins = IvyPlugin

  override def globalSettings: Seq[Setting[_]] = Def.settings(
    githubJob := jobTask.value,
    githubDependencySnapshot := snapshotTask.value,
    submitGithubDependencyGraph := submitTask.value
  )

  override def projectSettings: Seq[Setting[_]] = Def.settings(
    githubDependencyManifest := manifestTask.value
  )

  private def manifestTask: Def.Initialize[Task[githubapi.Manifest]] = Def.task {
    // updateFull is needed to have information about callers
    val report = Keys.updateFull.value
    val projectRef = Keys.thisProjectRef.value
    val logger = Keys.streams.value.log
    val projectID =
      prettyPrint(CrossVersion(Keys.scalaVersion.value, Keys.scalaBinaryVersion.value)(Keys.projectID.value))

    val alreadySeen = mutable.Set[String]()
    val moduleReports = mutable.Buffer[(ModuleReport, ConfigRef)]()
    val allDependencies = mutable.Buffer[(String, String)]()

    for {
      configReport <- report.configurations
      moduleReport <- configReport.modules
      module = prettyPrint(moduleReport.module)
      if !moduleReport.evicted && !alreadySeen.contains(module)
    } {
      alreadySeen += module
      moduleReports += (moduleReport -> configReport.configuration)
      for (caller <- moduleReport.callers)
        allDependencies += (prettyPrint(caller.caller) -> module)
    }

    val allDependenciesMap: Map[String, Vector[String]] = allDependencies.view
      .groupBy(_._1)
      .mapValues {
        _.map { case (_, dep) => dep }.toVector
      }
    val projectDependencies = allDependenciesMap.getOrElse(projectID, Vector.empty).toSet

    val resolved =
      for ((moduleReport, configRef) <- moduleReports)
        yield {
          val module = prettyPrint(moduleReport.module)
          val artifacts = moduleReport.artifacts.map { case (a, _) => a }
          val mainArtifact = artifacts
            .find(_.classifier.isEmpty)
            .orElse {
              if (artifacts.nonEmpty)
                logger.warn(s"No main artifact for $module: ${artifacts.flatMap(_.classifier).mkString(", ")}")
              artifacts.headOption
            }
          val url = mainArtifact.flatMap(_.url).map(_.toString)
          val dependencies = allDependenciesMap.getOrElse(module, Vector.empty)
          val relationship =
            if (projectDependencies.contains(module)) DependencyRelationship.direct
            else DependencyRelationship.indirect
          val scope =
            if (isRuntime(configRef)) DependencyScope.runtime
            else DependencyScope.development
          val node = DependencyNode(url, Map.empty[String, JValue], Some(relationship), Some(scope), dependencies)
          (module -> node)
        }

    githubapi.Manifest(projectID, None, Map.empty[String, JValue], resolved.toMap)
  }

  private def prettyPrint(module: ModuleID): String =
    module
      .withConfigurations(module.configurations.flatMap(c => if (c == "default") None else Some(c)))
      .withExtraAttributes(Map.empty)
      .toString

  private def isRuntime(config: ConfigRef): Boolean = runtimeConfigs.contains(config)

  private def jobTask: Def.Initialize[Task[Job]] = Def.task {
    val name = githubCIEnv("GITHUB_JOB")
    val id = githubCIEnv("GITHUB_RUN_ID")
    val html_url =
      for {
        serverUrl <- Properties.envOrNone("$GITHUB_SERVER_URL")
        repository <- Properties.envOrNone("GITHUB_REPOSITORY")
      } yield s"$serverUrl/$repository/actions/runs/$id"
    Job(name, id, html_url)
  }

  private def snapshotTask: Def.Initialize[Task[DependencySnapshot]] = Def.taskDyn {
    val loadedBuild = Keys.loadedBuild.value
    val job = githubJob.value
    val sha = githubCIEnv("GITHUB_SHA")
    val ref = githubCIEnv("GITHUB_REF")
    val projectRefs = loadedBuild.allProjectRefs.map(_._1)
    val detector = DetectorMetadata("sbt-github-dependency-graph", "", "")
    val scanned = Instant.now
    Def.task {
      val manifests: Map[String, githubapi.Manifest] = projectRefs
        .map(ref => (ref / githubDependencyManifest).?)
        .join
        .value
        .zip(projectRefs)
        .collect { case (Some(manifest), projectRef) => (projectRef.project, manifest) }
        .toMap

      DependencySnapshot(0, job, sha, ref, detector, Map.empty[String, JValue], manifests, scanned.toString)
    }
  }

  private def submitTask: Def.Initialize[Task[URL]] = Def.task {
    val snapshot = githubDependencySnapshot.value
    val logger = Keys.streams.value.log

    val githubApiUrl = githubCIEnv("GITHUB_API_URL")
    val repository = githubCIEnv("GITHUB_REPOSITORY")
    val username = secret("GITHUB_USERNAME")
    val token = secret("GITHUB_TOKEN")
    val url = new URL(s"$githubApiUrl/repos/$repository/dependency-grapĥ/snapshots")

    val snapshotJson = CompactPrinter(Converter.toJsonUnsafe(snapshot))
    val request = Gigahorse
      .url(url.toString)
      .post(snapshotJson, StandardCharsets.UTF_8)
      .withAuth(username, token)

    logger.info(s"Submiting dependency snapshot to $url")
    val response = Await.result(http.run(request), Duration.Inf)
    val id = Parser.parseFromByteBuffer(response.bodyAsByteBuffer).asInstanceOf[JString].value
    val result = new URL(url, id)
    logger.info(s"Submitted successfully as $result")
    result
  }

  private def githubCIEnv(name: String): String =
    Properties.envOrNone(name).getOrElse {
      throw new MessageOnlyException(s"Missing environment variable $name. This task must run in a Github Action.")
    }

  private def secret(name: String): String =
    Properties.envOrNone(name).getOrElse {
      throw new MessageOnlyException(s"Missing secret variable $name.")
    }
}
