plugins {
  `java-library`
  application
}

application {
  mainClass.set("com.toro.al.memprofiler.Demo")
}

group = "com.toro.al"
version = "1.0.${providers.exec {
  commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim()}"

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
  mavenCentral()
}

dependencies {
  implementation("org.benf:cfr:0.152")
}

tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
}

tasks.jar {
  archiveBaseName.set("java-memory-profiler")
  manifest {
    attributes(
      "Main-Class" to "com.toro.al.memprofiler.Demo",
      "Premain-Class" to "com.toro.al.memprofiler.ProfilerAgent",
      "Agent-Class" to "com.toro.al.memprofiler.ProfilerAgent",
      "Can-Redefine-Classes" to "true",
      "Can-Retransform-Classes" to "true"
    )
  }
  // Bundle CFR into the fat jar
  from({
    configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
  })
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<JavaExec>("demo") {
  group = "application"
  description = "Run the profiler demo with a live browser UI."
  mainClass.set("com.toro.al.memprofiler.Demo")
  classpath = sourceSets["main"].runtimeClasspath
  standardInput = System.`in`
}
