dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.112-stable")
    compileOnly("org.slf4j:slf4j-api:2.0.18")

    api("tools.jackson.core:jackson-databind:3.2.2")
    api("org.yaml:snakeyaml:2.6")
    implementation("org.eclipse.jetty:jetty-server:12.1.12")
    implementation("org.eclipse.jetty.ee10:jetty-ee10-servlet:12.1.12")
    implementation("io.modelcontextprotocol.sdk:mcp:2.0.1")
}

extensions.extraProperties["moduleName"] = "common"