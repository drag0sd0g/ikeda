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
}

tasks.named<JavaExec>("run") {
    workingDir = projectDir
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
}
