import Project.Initialize
import Util._
import Dependencies._
import Licensed._
import Scope.ThisScope
import LaunchProguard.{ proguard, Proguard }
import Scripted._
import StringUtilities.normalize
import Sxr.sxr

// ThisBuild settings take lower precedence,
// but can be shared across the multi projects.
def buildLevelSettings: Seq[Setting[_]] = Seq(
  organization in ThisBuild := "org.scala-sbt",
  version in ThisBuild := "0.13.8-SNAPSHOT"
)

def commonSettings: Seq[Setting[_]] = Seq(
  scalaVersion := "2.10.4",
  publishArtifact in packageDoc := false,
  publishMavenStyle := false,
  componentID := None,
  crossPaths := false,
  resolvers += Resolver.typesafeIvyRepo("releases"),
  resolvers += Resolver.sonatypeRepo("snapshots"),
  concurrentRestrictions in Global += Util.testExclusiveRestriction,
  testOptions += Tests.Argument(TestFrameworks.ScalaCheck, "-w", "1"),
  javacOptions in compile ++= Seq("-target", "6", "-source", "6", "-Xlint", "-Xlint:-serial"),
  incOptions := incOptions.value.withNameHashing(true),
  crossScalaVersions := Seq(scala210)
)

def minimalSettings: Seq[Setting[_]] =
  commonSettings ++ customCommands ++
  publishPomSettings ++ Release.javaVersionCheckSettings

def baseSettings: Seq[Setting[_]] =
  minimalSettings ++ Seq(projectComponent) ++ baseScalacOptions ++ Licensed.settings ++ Formatting.settings

def testedBaseSettings: Seq[Setting[_]] =
  baseSettings ++ testDependencies

lazy val root: Project = (project in file(".")).
  configs(Sxr.sxrConf).
  aggregate(nonRoots: _*).
  settings(buildLevelSettings: _*).
  settings(minimalSettings ++ rootSettings: _*).
  settings(
    publish := {},
    publishLocal := {}
  )

// This is used to configure an sbt-launcher for this version of sbt.
lazy val bundledLauncherProj =
  (project in file("launch")).
  settings(minimalSettings:_*).
  settings(inConfig(Compile)(Transform.configSettings):_*).
  enablePlugins(SbtLauncherPlugin).
  settings(
    publish := {},
    publishLocal := {}
  )


// This is used only for command aggregation
lazy val allPrecompiled: Project = (project in file("all-precompiled")).
  aggregate(precompiled282, precompiled292, precompiled293).
  settings(buildLevelSettings ++ minimalSettings: _*).
  settings(
    publish := {},
    publishLocal := {}
  )

/* ** subproject declarations ** */

// defines Java structures used across Scala versions, such as the API structures and relationships extracted by
//   the analysis compiler phases and passed back to sbt.  The API structures are defined in a simple
//   format from which Java sources are generated by the datatype generator Projproject
lazy val interfaceProj = (project in file("interface")).
  settings(minimalSettings ++ javaOnlySettings: _*).
  settings(
    name := "Interface",
    projectComponent,
    exportJars := true,
    componentID := Some("xsbti"),
    watchSources <++= apiDefinitions,
    resourceGenerators in Compile <+= (version, resourceManaged, streams, compile in Compile) map generateVersionFile,
    apiDefinitions <<= baseDirectory map { base => (base / "definition") :: (base / "other") :: (base / "type") :: Nil },
    sourceGenerators in Compile <+= (cacheDirectory,
      apiDefinitions,
      fullClasspath in Compile in datatypeProj,
      sourceManaged in Compile,
      mainClass in datatypeProj in Compile,
      runner,
      streams) map generateAPICached
  )

// defines operations on the API of a source, including determining whether it has changed and converting it to a string
//   and discovery of Projclasses and annotations
lazy val apiProj = (project in compilePath / "api").
  dependsOn(interfaceProj).
  settings(testedBaseSettings: _*).
  settings(
    name := "API"
  )

/* **** Utilities **** */

lazy val controlProj = (project in utilPath / "control").
  settings(baseSettings ++ Util.crossBuild: _*).
  settings(
    name := "Control",
    crossScalaVersions := Seq(scala210, scala211)
  )

lazy val collectionProj = (project in utilPath / "collection").
  settings(testedBaseSettings ++ Util.keywordsSettings ++ Util.crossBuild: _*).
  settings(
    name := "Collections",
    crossScalaVersions := Seq(scala210, scala211)
  )

lazy val applyMacroProj = (project in utilPath / "appmacro").
  dependsOn(collectionProj).
  settings(testedBaseSettings: _*).
  settings(
    name := "Apply Macro",
    libraryDependencies += scalaCompiler.value
  )

// The API for forking, combining, and doing I/O with system processes
lazy val processProj = (project in utilPath / "process").
  dependsOn(ioProj % "test->test").
  settings(baseSettings: _*).
  settings(
    name := "Process",
    libraryDependencies ++= scalaXml.value
  )

// Path, IO (formerly FileUtilities), NameFilter and other I/O utility classes
lazy val ioProj = (project in utilPath / "io").
  dependsOn(controlProj).
  settings(testedBaseSettings ++ Util.crossBuild: _*).
  settings(
    name := "IO",
    libraryDependencies += scalaCompiler.value % Test,
    crossScalaVersions := Seq(scala210, scala211)
  )

// Utilities related to reflection, managing Scala versions, and custom class loaders
lazy val classpathProj = (project in utilPath / "classpath").
  dependsOn(interfaceProj, ioProj).
  settings(testedBaseSettings: _*).
  settings(
    name := "Classpath",
    libraryDependencies ++= Seq(scalaCompiler.value,Dependencies.launcherInterface)
  )

// Command line-related utilities.
lazy val completeProj = (project in utilPath / "complete").
  dependsOn(collectionProj, controlProj, ioProj).
  settings(testedBaseSettings ++ Util.crossBuild: _*).
  settings(
    name := "Completion",
    libraryDependencies += jline,
    crossScalaVersions := Seq(scala210, scala211)
  )

// logging
lazy val logProj = (project in utilPath / "log").
  dependsOn(interfaceProj, processProj).
  settings(testedBaseSettings: _*).
  settings(
    name := "Logging",
    libraryDependencies += jline
  ) 

// Relation
lazy val relationProj = (project in utilPath / "relation").
  dependsOn(interfaceProj, processProj).
  settings(testedBaseSettings: _*).
  settings(
    name := "Relation"
  )

// class file reader and analyzer
lazy val classfileProj = (project in utilPath / "classfile").
  dependsOn(ioProj, interfaceProj, logProj).
  settings(testedBaseSettings: _*).
  settings(
    name := "Classfile"
  )

// generates immutable or mutable Java data types according to a simple input format
lazy val datatypeProj = (project in utilPath / "datatype").
  dependsOn(ioProj).
  settings(baseSettings: _*).
  settings(
    name := "Datatype Generator"
  )

// cross versioning
lazy val crossProj = (project in utilPath / "cross").
  settings(baseSettings: _*).
  settings(inConfig(Compile)(Transform.crossGenSettings): _*).
  settings(
    name := "Cross"
  )

// A logic with restricted negation as failure for a unique, stable model
lazy val logicProj = (project in utilPath / "logic").
  dependsOn(collectionProj, relationProj).
  settings(testedBaseSettings: _*).
  settings(
    name := "Logic"
  )

/* **** Intermediate-level Modules **** */

// Apache Ivy integration
lazy val ivyProj = (project in file("ivy")).
  dependsOn(interfaceProj, crossProj, logProj % "compile;test->test", ioProj % "compile;test->test", /*launchProj % "test->test",*/ collectionProj).
  settings(baseSettings: _*).
  settings(
    name := "Ivy",
    libraryDependencies ++= Seq(ivy, jsch, sbtSerialization, launcherInterface),
    testExclusive)

// Runner for uniform test interface
lazy val testingProj = (project in file("testing")).
  dependsOn(ioProj, classpathProj, logProj, testAgentProj).
  settings(baseSettings: _*).
  settings(
    name := "Testing",
    libraryDependencies ++= Seq(testInterface,launcherInterface)
  )

// Testing agent for running tests in a separate process.
lazy val testAgentProj = (project in file("testing") / "agent").
  settings(minimalSettings: _*).
  settings(
    name := "Test Agent",
    libraryDependencies += testInterface
  )

// Basic task engine
lazy val taskProj = (project in tasksPath).
  dependsOn(controlProj, collectionProj).
  settings(testedBaseSettings: _*).
  settings(
    name := "Tasks"
  )

// Standard task system.  This provides map, flatMap, join, and more on top of the basic task model.
lazy val stdTaskProj = (project in tasksPath / "standard").
  dependsOn (taskProj % "compile;test->test", collectionProj, logProj, ioProj, processProj).
  settings(testedBaseSettings: _*).
  settings(
    name := "Task System",
    testExclusive
  )

// Persisted caching based on SBinary
lazy val cacheProj = (project in cachePath).
  dependsOn (ioProj, collectionProj).
  settings(baseSettings: _*).
  settings(
    name := "Cache",
    libraryDependencies ++= Seq(sbinary, sbtSerialization) ++ scalaXml.value
  )

// Builds on cache to provide caching for filesystem-related operations
lazy val trackingProj = (project in cachePath / "tracking").
  dependsOn(cacheProj, ioProj).
  settings(baseSettings: _*).
  settings(
    name := "Tracking"
  )

// Embedded Scala code runner
lazy val runProj = (project in file("run")).
  dependsOn (ioProj, logProj % "compile;test->test", classpathProj, processProj % "compile;test->test").
  settings(testedBaseSettings: _*).
  settings(
    name := "Run"
  )

// Compiler-side interface to compiler that is compiled against the compiler being used either in advance or on the fly.
//   Includes API and Analyzer phases that extract source API and relationships.
lazy val compileInterfaceProj = (project in compilePath / "interface").
  dependsOn(interfaceProj % "compile;test->test", ioProj % "test->test", logProj % "test->test", /*launchProj % "test->test",*/ apiProj % "test->test").
  settings(baseSettings ++ precompiledSettings: _*).
  settings(
    name := "Compiler Interface",
    exportJars := true,
    // we need to fork because in unit tests we set usejavacp = true which means
    // we are expecting all of our dependencies to be on classpath so Scala compiler
    // can use them while constructing its own classpath for compilation
    fork in Test := true,
    // needed because we fork tests and tests are ran in parallel so we have multiple Scala
    // compiler instances that are memory hungry
    javaOptions in Test += "-Xmx1G",
    artifact in (Compile, packageSrc) := Artifact(srcID).copy(configurations = Compile :: Nil).extra("e:component" -> srcID)
  )

lazy val precompiled282 = precompiled(scala282)
lazy val precompiled292 = precompiled(scala292)
lazy val precompiled293 = precompiled(scala293)

// Implements the core functionality of detecting and propagating changes incrementally.
//   Defines the data structures for representing file fingerprints and relationships and the overall source analysis
lazy val compileIncrementalProj = (project in compilePath / "inc").
  dependsOn (apiProj, ioProj, logProj, classpathProj, relationProj).
  settings(testedBaseSettings: _*).
  settings(
    name := "Incremental Compiler"
  )

// Persists the incremental data structures using SBinary
lazy val compilePersistProj = (project in compilePath / "persist").
  dependsOn(compileIncrementalProj, apiProj, compileIncrementalProj % "test->test").
  settings(testedBaseSettings: _*).
  settings(
    name := "Persist",
    libraryDependencies += sbinary
  )

// sbt-side interface to compiler.  Calls compiler-side interface reflectively
lazy val compilerProj = (project in compilePath).
  dependsOn(interfaceProj % "compile;test->test", logProj, ioProj, classpathProj, apiProj, classfileProj,
    logProj % "test->test" /*,launchProj % "test->test" */).
  settings(testedBaseSettings: _*).
  settings(
    name := "Compile",
    libraryDependencies ++= Seq(scalaCompiler.value % Test, launcherInterface),
    unmanagedJars in Test <<= (packageSrc in compileInterfaceProj in Compile).map(x => Seq(x).classpath)
  )

lazy val compilerIntegrationProj = (project in (compilePath / "integration")).
  dependsOn(compileIncrementalProj, compilerProj, compilePersistProj, apiProj, classfileProj).
  settings(baseSettings: _*).
  settings(
    name := "Compiler Integration"
  )

lazy val compilerIvyProj = (project in compilePath / "ivy").
  dependsOn (ivyProj, compilerProj).
  settings(baseSettings: _*).
  settings(
    name := "Compiler Ivy Integration"
  )

lazy val scriptedBaseProj = (project in scriptedPath / "base").
  dependsOn (ioProj, processProj).
  settings(testedBaseSettings: _*).
  settings(
    name := "Scripted Framework",
    libraryDependencies ++= scalaParsers.value
  )

lazy val scriptedSbtProj = (project in scriptedPath / "sbt").
  dependsOn (ioProj, logProj, processProj, scriptedBaseProj).
  settings(baseSettings: _*).
  settings(
    name := "Scripted sbt",
    libraryDependencies += launcherInterface % "provided"
  )

lazy val scriptedPluginProj = (project in scriptedPath / "plugin").
  dependsOn (sbtProj, classpathProj).
  settings(baseSettings: _*).
  settings(
    name := "Scripted Plugin"
  )

// Implementation and support code for defining actions.
lazy val actionsProj = (project in mainPath / "actions").
  dependsOn (classpathProj, completeProj, apiProj, compilerIntegrationProj, compilerIvyProj,
    interfaceProj, ioProj, ivyProj, logProj, processProj, runProj, relationProj, stdTaskProj,
    taskProj, trackingProj, testingProj).
  settings(testedBaseSettings: _*).
  settings(
    name := "Actions"
  )

// General command support and core commands not specific to a build system
lazy val commandProj = (project in mainPath / "command").
  dependsOn(interfaceProj, ioProj, logProj, completeProj, classpathProj, crossProj).
  settings(testedBaseSettings: _*).
  settings(
    name := "Command",
    libraryDependencies += launcherInterface
  )

// Fixes scope=Scope for Setting (core defined in collectionProj) to define the settings system used in build definitions
lazy val mainSettingsProj = (project in mainPath / "settings").
  dependsOn (applyMacroProj, interfaceProj, ivyProj, relationProj, logProj, ioProj, commandProj,
    completeProj, classpathProj, stdTaskProj, processProj).
  settings(testedBaseSettings: _*).
  settings(
    name := "Main Settings",
    libraryDependencies += sbinary
  )

// The main integration project for sbt.  It brings all of the Projsystems together, configures them, and provides for overriding conventions.
lazy val mainProj = (project in mainPath).
  dependsOn (actionsProj, mainSettingsProj, interfaceProj, ioProj, ivyProj, logProj, logicProj, processProj, runProj, commandProj).
  settings(testedBaseSettings: _*).
  settings(
    name := "Main",
    libraryDependencies ++= scalaXml.value ++ Seq(launcherInterface)
  )

// Strictly for bringing implicits and aliases from subsystems into the top-level sbt namespace through a single package object
//  technically, we need a dependency on all of mainProj's dependencies, but we don't do that since this is strictly an integration project
//  with the sole purpose of providing certain identifiers without qualification (with a package object)
lazy val sbtProj = (project in sbtPath).
  dependsOn(mainProj, compileInterfaceProj, precompiled282, precompiled292, precompiled293, scriptedSbtProj % "test->test").
  settings(baseSettings: _*).
  settings(
    name := "sbt",
    normalizedName := "sbt"
  )

lazy val mavenResolverPluginProj = (project in file("sbt-maven-resolver")).
  dependsOn(sbtProj, ivyProj % "test->test").
  settings(baseSettings: _*).
  settings(
    name := "sbt-maven-resolver",
    libraryDependencies ++= aetherLibs,
    sbtPlugin := true
  )

def scriptedTask: Initialize[InputTask[Unit]] = Def.inputTask {
  val result = scriptedSource(dir => (s: State) => scriptedParser(dir)).parsed
  publishAll.value
  doScripted((sbtLaunchJar in bundledLauncherProj).value, (fullClasspath in scriptedSbtProj in Test).value,
    (scalaInstance in scriptedSbtProj).value, scriptedSource.value, result, scriptedPrescripted.value)
}

def scriptedUnpublishedTask: Initialize[InputTask[Unit]] = Def.inputTask {
  val result = scriptedSource(dir => (s: State) => scriptedParser(dir)).parsed
  doScripted((sbtLaunchJar in bundledLauncherProj).value, (fullClasspath in scriptedSbtProj in Test).value,
    (scalaInstance in scriptedSbtProj).value, scriptedSource.value, result, scriptedPrescripted.value)
}

lazy val publishAll = TaskKey[Unit]("publish-all")
lazy val publishLauncher = TaskKey[Unit]("publish-launcher")

lazy val myProvided = config("provided") intransitive

def allProjects = Seq(interfaceProj, apiProj,
  controlProj, collectionProj, applyMacroProj, processProj, ioProj, classpathProj, completeProj,
  logProj, relationProj, classfileProj, datatypeProj, crossProj, logicProj, ivyProj,
  testingProj, testAgentProj, taskProj, stdTaskProj, cacheProj, trackingProj, runProj,
  compileInterfaceProj, compileIncrementalProj, compilePersistProj, compilerProj,
  compilerIntegrationProj, compilerIvyProj,
  scriptedBaseProj, scriptedSbtProj, scriptedPluginProj,
  actionsProj, commandProj, mainSettingsProj, mainProj, sbtProj, mavenResolverPluginProj)

def projectsWithMyProvided = allProjects.map(p => p.copy(configurations = (p.configurations.filter(_ != Provided)) :+ myProvided))
lazy val nonRoots = projectsWithMyProvided.map(p => LocalProject(p.id))

def rootSettings = Release.releaseSettings ++ fullDocSettings ++
  Util.publishPomSettings ++ otherRootSettings ++ Formatting.sbtFilesSettings /*++
  Transform.conscriptSettings(launchProj)*/
def otherRootSettings = Seq(
  Scripted.scriptedPrescripted := { _ => },
  Scripted.scripted <<= scriptedTask,
  Scripted.scriptedUnpublished <<= scriptedUnpublishedTask,
  Scripted.scriptedSource <<= (sourceDirectory in sbtProj) / "sbt-test",
  publishAll := {
    val _ = (publishLocal).all(ScopeFilter(inAnyProject)).value
  }
) ++ inConfig(Scripted.MavenResolverPluginTest)(Seq(
  Scripted.scripted <<= scriptedTask,
  Scripted.scriptedUnpublished <<= scriptedUnpublishedTask,
  Scripted.scriptedPrescripted := { f =>
    val inj = f / "project" / "maven.sbt"
    if (!inj.exists) {
      IO.write(inj, "addMavenResolverPlugin")
      // sLog.value.info(s"""Injected project/maven.sbt to $f""")
    }
  }
))
lazy val docProjects: ScopeFilter = ScopeFilter(
  inAnyProject -- inProjects(root, sbtProj, scriptedBaseProj, scriptedSbtProj, scriptedPluginProj, precompiled282, precompiled292, precompiled293),
  inConfigurations(Compile)
)
def fullDocSettings = Util.baseScalacOptions ++ Docs.settings ++ Sxr.settings ++ Seq(
  scalacOptions += "-Ymacro-no-expand", // for both sxr and doc
  sources in sxr := {
    val allSources = (sources ?? Nil).all(docProjects).value
    allSources.flatten.distinct
  }, //sxr
  sources in (Compile, doc) := (sources in sxr).value, // doc
  Sxr.sourceDirectories := {
    val allSourceDirectories = (sourceDirectories ?? Nil).all(docProjects).value
    allSourceDirectories.flatten
  },
  fullClasspath in sxr := (externalDependencyClasspath in Compile in sbtProj).value,
  dependencyClasspath in (Compile, doc) := (fullClasspath in sxr).value
)

/* Nested Projproject paths */
def sbtPath    = file("sbt")
def cachePath  = file("cache")
def tasksPath  = file("tasks")
def launchPath = file("launch")
def utilPath   = file("util")
def compilePath = file("compile")
def mainPath   = file("main")

def precompiledSettings = Seq(
  artifact in packageBin <<= (appConfiguration, scalaVersion) { (app, sv) =>
    val launcher = app.provider.scalaProvider.launcher
    val bincID = binID + "_" + ScalaInstance(sv, launcher).actualVersion
    Artifact(binID) extra ("e:component" -> bincID)
  },
  target <<= (target, scalaVersion) { (base, sv) => base / ("precompiled_" + sv) },
  scalacOptions := Nil,
  ivyScala ~= { _.map(_.copy(checkExplicit = false, overrideScalaVersion = false)) },
  exportedProducts in Compile := Nil,
  libraryDependencies += scalaCompiler.value % "provided"
)

def precompiled(scalav: String): Project = Project(id = normalize("Precompiled " + scalav.replace('.', '_')), base = compilePath / "interface").
  dependsOn(interfaceProj).
  settings(baseSettings ++ precompiledSettings: _*).
  settings(
    name := "Precompiled " + scalav.replace('.', '_'),
    scalaHome := None,
    scalaVersion <<= (scalaVersion in ThisBuild) { sbtScalaV =>
      assert(sbtScalaV != scalav, "Precompiled compiler interface cannot have the same Scala version (" + scalav + ") as sbt.")
      scalav
    },
    crossScalaVersions := Seq(scalav),
    // we disable compiling and running tests in precompiled Projprojects of compiler interface
    // so we do not need to worry about cross-versioning testing dependencies
    sources in Test := Nil
  )

lazy val safeUnitTests = taskKey[Unit]("Known working tests (for both 2.10 and 2.11)")
lazy val safeProjects: ScopeFilter = ScopeFilter(
  inProjects(mainSettingsProj, mainProj, ivyProj, completeProj,
    actionsProj, classpathProj, collectionProj, compileIncrementalProj,
    logProj, runProj, stdTaskProj),
  inConfigurations(Test)
)

def customCommands: Seq[Setting[_]] = Seq(
  commands += Command.command("setupBuildScala211") { state =>
    s"""set scalaVersion in ThisBuild := "$scala211" """ ::
      state
  },
  // This is invoked by Travis
  commands += Command.command("checkBuildScala211") { state =>
    s"++ $scala211" ::
      // First compile everything before attempting to test
      "all compile test:compile" ::
      // Now run known working tests.
      safeUnitTests.key.label ::
      state
  },
  safeUnitTests := {
    test.all(safeProjects).value
  },
  commands += Command.command("release-sbt-local") { state =>
    "clean" ::
    "allPrecompiled/clean" ::
    "allPrecompiled/compile" ::
    "allPrecompiled/publishLocal" ::
    "so compile" ::
    "so publishLocal" ::
    "reload" ::
    state
  },
  /** There are several complications with sbt's build.
   * First is the fact that interface project is a Java-only project
   * that uses source generator from datatype subproject in Scala 2.10.4,
   * which is depended on by Scala 2.8.2, Scala 2.9.2, and Scala 2.9.3 precompiled project. 
   *
   * Second is the fact that sbt project (currently using Scala 2.10.4) depends on
   * the precompiled projects (that uses Scala 2.8.2 etc.)
   * 
   * Finally, there's the fact that all subprojects are released with crossPaths
   * turned off for the sbt's Scala version 2.10.4, but some of them are also
   * cross published against 2.11.1 with crossPaths turned on.
   *
   * Because of the way ++ (and its improved version wow) is implemented
   * precompiled compiler briges are handled outside of doge aggregation on root.
   * `so compile` handles 2.10.x/2.11.x cross building. 
   */
  commands += Command.command("release-sbt") { state =>
    // TODO - Any sort of validation
    "checkCredentials" ::
    "clean" ::
    "allPrecompiled/clean" ::
      "allPrecompiled/compile" ::
      "allPrecompiled/publishSigned" ::
      "conscript-configs" ::
      "so compile" ::
      "so publishSigned" ::
      "publishLauncher" ::
      state
  },
  // stamp-version doesn't work with ++ or "so".
  commands += Command.command("release-nightly") { state =>
    "stamp-version" ::
      "clean" ::
      "allPrecompiled/clean" ::
      "allPrecompiled/compile" ::
      "allPrecompiled/publish" ::
      "compile" ::
      "publish" ::
      state
  }
)
