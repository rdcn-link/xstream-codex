name := "xstream-data-tests"
scalaVersion := "2.13.16"
publish / skip := true
Test / fork := true
Test / javaOptions ++= Seq("--add-opens=java.base/java.nio=ALL-UNNAMED", "-Dio.netty.tryReflectionSetAccessible=true")
libraryDependencies ++= Seq("org.scalatest" %% "scalatest" % "3.2.19" % Test, "org.apache.arrow" % "arrow-memory-netty" % "18.3.0" % Test)
lazy val module0 = RootProject(file("../xstream-data-core"))
dependsOn(module0 % "test->compile")
lazy val module1 = RootProject(file("../xstream-data-files"))
dependsOn(module1 % "test->compile")
lazy val module2 = RootProject(file("../xstream-data-formats-basic"))
dependsOn(module2 % "test->compile")
lazy val module3 = RootProject(file("../xstream-data-parquet"))
dependsOn(module3 % "test->compile")
lazy val module4 = RootProject(file("../xstream-data-excel"))
dependsOn(module4 % "test->compile")
lazy val module5 = RootProject(file("../xstream-data-archives"))
dependsOn(module5 % "test->compile")
lazy val module6 = RootProject(file("../xstream-data-catalog-static"))
dependsOn(module6 % "test->compile")
lazy val module7 = RootProject(file("../xstream-data-mounted"))
dependsOn(module7 % "test->compile")
lazy val module8 = RootProject(file("../xstream-data-scientific-views"))
dependsOn(module8 % "test->compile")
lazy val module9 = RootProject(file("../xstream-data-fasta"))
dependsOn(module9 % "test->compile")
lazy val module10 = RootProject(file("../xstream-data-netcdf"))
dependsOn(module10 % "test->compile")
lazy val module11 = RootProject(file("../xstream-data-fits"))
dependsOn(module11 % "test->compile")

Test / unmanagedSources += baseDirectory.value / ".." / ".." / "examples" / "xstream" / "ViewExamples.scala"
