plugins {
    `java-library`
    id("org.springframework.boot") version "3.5.14" apply false
}

val springBootVersion = "3.5.14"

subprojects {
    apply(plugin = "java-library")

    group = "com.zeti"
    version = "0.0.1-SNAPSHOT"

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        api(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        testImplementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

tasks.register("bootRun") {
    group = "application"
    description = "Runs the ZETTY backend bootstrap app."
    dependsOn(":bootstrap-app:bootRun")
}
