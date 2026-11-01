organization := "io.github.rdcn-link"
version := "0.1.0-SNAPSHOT"
name := "xstream-plugin-dependencies"
scalaVersion := "2.13.16"
lazy val contract = RootProject(file("../../xstream-operator-sdk/contract"))
dependsOn(contract)
libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test
