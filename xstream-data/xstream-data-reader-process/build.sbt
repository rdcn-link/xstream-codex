name := "xstream-data-reader-process"
organization := "io.github.rdcn-link"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.13.16"
lazy val core = RootProject(file("../xstream-data-core"))
dependsOn(core)
