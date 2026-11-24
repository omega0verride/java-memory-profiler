plugins {
  `java-library`
}

group = "com.toro.al"
version = rootProject.version

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
}

tasks.jar {
  archiveBaseName.set("memprofiler-api")
  manifest {
    attributes(
      "Implementation-Title" to "memprofiler-api",
      "Implementation-Version" to project.version
    )
  }
}
