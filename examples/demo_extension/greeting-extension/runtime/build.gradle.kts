plugins {
    `java-library`
    id("io.quarkus.extension")
    id("maven-publish")
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

quarkusExtension {
    deploymentModule = "deployment"
}

base {
    archivesName.set("greeting-extension")
}

dependencies {
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    implementation("io.quarkus:quarkus-arc")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "com.example"
            artifactId = "greeting-extension"
            version = "1.0.0-SNAPSHOT"
            from(components["java"])
        }
    }
}
