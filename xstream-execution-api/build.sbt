name := "xstream-execution-api"
organization := "io.github.rdcn-link"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.13.16"
libraryDependencies += "io.github.rdcn-link" %% "xstream-data-core" % "0.1.0-SNAPSHOT"

libraryDependencies ++= Seq("com.fasterxml.jackson.core" % "jackson-databind" % "2.18.3", "org.yaml" % "snakeyaml" % "2.3")
