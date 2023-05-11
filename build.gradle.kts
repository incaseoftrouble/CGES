plugins {
    java
    distribution
    application
    idea
    antlr

    id("com.diffplug.spotless") version "6.18.0"
}

group = "org.game"
version = "0.1"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17

    withSourcesJar()
    withJavadocJar()
}

var defaultEncoding = "UTF-8"
tasks.withType<JavaCompile> { options.encoding = defaultEncoding }
tasks.withType<Javadoc> { options.encoding = defaultEncoding }
tasks.withType<Test> { systemProperty("file.encoding", "UTF-8") }

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}

repositories {
    mavenCentral()
}

spotless {
    java {
        palantirJavaFormat()
    }
    groovyGradle {
        greclipse()
    }
}

dependencies {
    // Owl
    implementation(files("lib/owl-21.0.jar", "lib/jhoafparser-1.1.1-patched.jar"))
    implementation("de.tum.in", "jbdd", "0.5.2")
    implementation("de.tum.in", "naturals-util", "0.17.0")
    implementation("commons-cli", "commons-cli", "1.4")
    implementation("org.antlr", "antlr4-runtime", "4.8-1")

    // Z3
    implementation("io.github.tudo-aqua", "z3-turnkey", "4.8.14")

    // https://mvnrepository.com/artifact/com.google.code.gson/gson
    implementation("com.google.code.gson", "gson", "2.9.0")

    // Guava
    implementation("com.google.guava", "guava", "31.0.1-jre")

    // https://mvnrepository.com/artifact/info.picocli/picocli
    implementation("info.picocli", "picocli", "4.6.3")

    antlr("org.antlr", "antlr4", "4.8-1")
}

configurations {
    api { setExtendsFrom(extendsFrom.filter { it.name != "antlr" }) }
}

application {
    mainClass.set("com.cges.Main")
}

tasks.generateGrammarSource {
    arguments.addAll(listOf("-visitor", "-long-messages", "-lib", "src/main/antlr"))
    outputDirectory = outputDirectory.resolve("com/cges/grammar")
}
