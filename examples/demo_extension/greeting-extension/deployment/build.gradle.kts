plugins {
    `java-library`
    id("maven-publish")
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    implementation("io.quarkus:quarkus-arc-deployment")
    implementation("io.quarkus:quarkus-core-deployment")
    implementation("io.quarkus:quarkus-devui-deployment-spi")

    implementation(project(":greeting-extension:runtime"))
}

base {
    archivesName.set("greeting-extension-deployment")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "com.example"
            artifactId = "greeting-extension-deployment"
            version = "1.0.0-SNAPSHOT"
            from(components["java"])
        }
    }
}
