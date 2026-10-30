organization := "io.github.rdcn-link"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.13.16"
name := "xstream-operator-repository"
crossPaths := false
libraryDependencies ++= Seq(
  "org.yaml" % "snakeyaml" % "2.3",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.18.3",
  "org.scalatest" %% "scalatest" % "3.2.19" % Test)
Compile / mainClass := Some("xstream.repository.Main")
fork := true
enablePlugins(JavaAppPackaging)

// Package the XStream symbol for the header and favicon.
Compile / resourceGenerators += Def.task {
  Seq("xstream-logo.svg", "xstream-mark.svg").map { name =>
    val output = (Compile / resourceManaged).value / "web" / name
    IO.copyFile(baseDirectory.value.getParentFile / "assets" / name, output)
    output
  }
}.taskValue

lazy val operatorContract = RootProject(file("../xstream-operator-sdk/contract"))
dependsOn(operatorContract)

lazy val sourceAnalysis = RootProject(file("../plugins/xstream-plugin-source-analysis"))
dependsOn(sourceAnalysis)
