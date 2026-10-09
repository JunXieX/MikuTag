plugins {
    java
}

group = "dev.junxiex"
version = "1.2.0"
description = "MikuTag - Paper/Folia title plugin with dialog menus"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("me.clip:placeholderapi:2.12.3")
    // sqlite-jdbc / mysql-connector-j / mariadb-java-client 不内置：
    // 由 MikuTagLoader 在开服时按 config.yml 的 storage.type 通过 Paper 的 MavenLibraryResolver 解析
}

tasks {
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
}