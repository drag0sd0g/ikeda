plugins {
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "com.ikeda.Main"
}

dependencies {
    implementation("com.worksap.nlp:sudachi:0.7.5")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.0")
    implementation("org.apache.commons:commons-csv:1.14.1")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    workingDir = projectDir
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.named<JavaExec>("run") {
    workingDir = projectDir
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
}
