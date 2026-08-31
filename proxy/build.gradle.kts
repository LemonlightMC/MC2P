import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

dependencies {
    implementation(project(":common"))

    compileOnly("org.slf4j:slf4j-api:2.0.18")
    compileOnly("com.velocitypowered:velocity-api:4.1.1")
    annotationProcessor("com.velocitypowered:velocity-api:4.1.1")
    implementation("dev.jorel:commandapi-velocity-shade:12.0.0")

    implementation("io.modelcontextprotocol.sdk:mcp:2.0.0")
    implementation("org.eclipse.jetty:jetty-server:12.1.12")
    implementation("org.eclipse.jetty.ee10:jetty-ee10-servlet:12.1.12")
    implementation("tools.jackson.core:jackson-databind:3.2.2")
    implementation("org.yaml:snakeyaml:2.6")
}

tasks.withType<ShadowJar>().configureEach {
    archiveBaseName.set("mc2p-proxy")
    archiveClassifier.set("")
    archiveVersion.set("")

    // Merge META-INF/services descriptors (MCP SDK, Jackson, Jetty) instead of dropping
    // duplicates: INCLUDE for service files, EXCLUDE for everything else.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    filesNotMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    mergeServiceFiles()

    relocate("io.modelcontextprotocol", "dev.mc2p.lib.mcp")
    relocate("org.eclipse.jetty", "dev.mc2p.lib.jetty")
    relocate("jakarta.servlet", "dev.mc2p.lib.jakarta.servlet")
    relocate("tools.jackson", "dev.mc2p.lib.jackson")
    relocate("com.networknt", "dev.mc2p.lib.networknt")
    relocate("org.yaml.snakeyaml", "dev.mc2p.lib.snakeyaml")
    relocate("reactor.core", "dev.mc2p.lib.reactor.core")
    relocate("reactor.util", "dev.mc2p.lib.reactor.util")
    relocate("org.reactivestreams", "dev.mc2p.lib.reactivestreams")
    relocate("dev.jorel.commandapi", "dev.mc2p.lib.commandapi")

    exclude("META-INF/versions/**/module-info.class", "module-info.class")
    mergeServiceFiles()
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}

tasks.jar {
    enabled = false
}

extensions.extraProperties["moduleName"] = "proxy"