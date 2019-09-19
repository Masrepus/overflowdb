name := "overflowdb"
ThisBuild/organization := "io.shiftleft"
ThisBuild/scalaVersion := "2.13.1"
publish/skip := true
enablePlugins(GitVersioning)

lazy val tinkerpop3 = project.in(file("tinkerpop3"))
lazy val traversals = project.in(file("traversals")).dependsOn(tinkerpop3) //TODO factor out `core` from tinkerpop3

ThisBuild/scalacOptions ++= Seq("-deprecation", "-feature", "-language:implicitConversions")
ThisBuild/Test/compile/javacOptions ++= Seq("-g")
ThisBuild/Test/fork := true

ThisBuild/resolvers ++= Seq(Resolver.mavenLocal,
                            "Sonatype OSS" at "https://oss.sonatype.org/content/repositories/public")

ThisBuild/useGpg := false
ThisBuild/publishTo := sonatypePublishToBundle.value
ThisBuild/scmInfo := Some(ScmInfo(url("https://github.com/ShiftLeftSecurity/overflowdb"),
                                      "scm:git@github.com:ShiftLeftSecurity/overflowdb.git"))
ThisBuild/homepage := Some(url("https://github.com/ShiftLeftSecurity/overflowdb/"))
ThisBuild/licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild/developers := List(
  Developer("mpollmeier", "Michael Pollmeier", "michael@michaelpollmeier.com", url("http://www.michaelpollmeier.com/")),
  Developer("ml86", "Markus Lottmann", "markus@shiftleft.io", url("https://github.com/ml86")))

// allow to cancel sbt compilation/test/... using C-c
Global/cancelable := true
