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
  // bundled into the profiler jar too, so code compiled against the API also runs with only the profiler jar
  implementation(project(":memprofiler-api"))

  testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
  systemProperty("java.awt.headless", "true")
  maxHeapSize = "512m"
  testLogging {
    events("failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    showStandardStreams = false
  }
}

tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
}

// The agents' guide and the sources (their javadoc is the API reference the guide points to)
// travel in the jar, so an agent with only the jar has both. Tests check the guide against them.
tasks.processResources {
  from("AGENTS.md")
  into("META-INF/sources") {
    from("src/main/java", "memprofiler-api/src/main/java")
  }
}

tasks.jar {
  archiveBaseName.set("java-memory-profiler")
  manifest {
    attributes(
      "Main-Class" to "com.toro.al.memprofiler.Cli",
      "Implementation-Title" to "java-memory-profiler",
      "Implementation-Version" to project.version,
      "Premain-Class" to "com.toro.al.memprofiler.ProfilerAgent",
      "Agent-Class" to "com.toro.al.memprofiler.ProfilerAgent",
      "Can-Redefine-Classes" to "true",
      "Can-Retransform-Classes" to "true"
    )
  }
  // Bundle CFR and memprofiler-api into the fat jar
  dependsOn(configurations.runtimeClasspath)
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
