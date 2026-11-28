plugins {
    id("org.springframework.boot")
}

tasks.bootJar {
    archiveFileName.set("zetty-backend.jar")
    mainClass.set("com.zeti.bootstrap.ZettyBackendApplication")
}

tasks.bootRun {
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":infrastructure"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
