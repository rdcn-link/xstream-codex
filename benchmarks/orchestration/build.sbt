name := "xstream-orchestration-benchmark"
scalaVersion := "2.13.16"
lazy val engine = RootProject(file("../../xstream-engine"))
dependsOn(engine)
lazy val writeClasspath = taskKey[File]("Write the benchmark host's complete JVM classpath")
writeClasspath := {
  val output = target.value / "classpath.txt"
  IO.write(output, (Runtime / fullClasspath).value.map(_.data.getAbsolutePath).mkString(java.io.File.pathSeparator))
  output
}
lazy val files = RootProject(file("../../xstream-data/xstream-data-files"))
lazy val catalog = RootProject(file("../../xstream-data/xstream-data-catalog-static"))
lazy val formats = RootProject(file("../../xstream-data/xstream-data-formats-basic"))
lazy val executors = RootProject(file("../../plugins/xstream-plugin-operator-executors"))
dependsOn(files, catalog, formats, executors)
libraryDependencies += "io.github.rdcn-link" %% "xstream-plugin-execution-file" % "0.1.0-SNAPSHOT"
