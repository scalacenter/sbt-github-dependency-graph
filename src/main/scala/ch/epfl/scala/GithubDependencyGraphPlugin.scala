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
    val report = Keys.update.value
    val projectRef = Keys.thisProjectRef.value
    val logger = Keys.streams.value.log

    val modules = mutable.Seq[ModuleReport]()
    val allDependenciesSeq = mutable.Seq[(ModuleID, String)]()

    for {
      configReport <- report.configurations
      moduleReport <- configReport.modules
      if !moduleReport.evicted
    } {
      modules :+ moduleReport
      for (caller <- moduleReport.callers)
        allDependenciesSeq :+ (caller.caller, moduleReport.module.toString)
    }

    val allDependencies = allDependenciesSeq.toVector.groupBy(_._1).mapValues(_.map(_._2))
    val resolved =
      for {
        moduleReport <- modules
        module = moduleReport.module
        filteredArtifacts = moduleReport.artifacts.collect { case (a, _) if a.url.isDefined => a }
        mainArtifact <- filteredArtifacts
          .find(_.classifier.isEmpty)
          .orElse {
            logger.warn(s"No main artifact for $module")
            filteredArtifacts.headOption
          }
      } yield {
        val url = mainArtifact.url.get
        val relationship =
          if (module.isTransitive) DependencyRelationship.indirect else DependencyRelationship.direct
        val scope = module.configurations.map(_.stripSuffix("-internal")) match {
          case Some("compile") | Some("runtime") | Some("provided") | Some("system") => DependencyScope.runtime
          case Some(_)                                                               => DependencyScope.development
          case None =>
            logger.warn(s"No configuration for $module")
            DependencyScope.development
        }
        val dependencies = allDependencies.get(module).getOrElse(Vector.empty)
        val node = DependencyNode(url.toString, Map.empty[String, JValue], relationship, scope, dependencies)
        (module.toString, node)
      }

    githubapi.Manifest(projectRef.project, None, Map.empty[String, JValue], resolved.toMap)
  }

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
