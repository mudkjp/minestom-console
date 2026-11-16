import java.time.LocalDate
import java.time.format.DateTimeFormatter

plugins {
    `java-library`
    `maven-publish`
}

description = "JLine integration for Minestom with syntax highlighting and auto-completion"
group = "gg.skylite"

java.toolchain.languageVersion = JavaLanguageVersion.of(25)

java {
    withSourcesJar()
    withJavadocJar()
}

val minestomVersion = "26_3-20261031.160529-11"
val mcVersion = "26.3"
val jlineVersion = "4.3.1"
val date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
version = "$date-$mcVersion"

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name = project.name
                description = project.description
                url = "https://github.com/mudkjp/minestom-console"

                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }

                developers {
                    developer {
                        name = "mudkip"
                        id = "mudkjp"
                        email = "mudkip@mudkip.dev"
                        url = "https://mudkip.dev"
                    }
                }

                scm {
                    url = "https://github.com/mudkjp/minestom-console"
                    connection = "scm:git:git://github.com/mudkjp/minestom-console.git"
                    developerConnection = "scm:git:ssh://git@github.com/mudkjp/minestom-console.git"
                }
            }
        }
    }

    repositories {
        maven {
            name = "skylite"
            url = uri("https://maven.skylite.gg/releases")
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}

repositories {
    mavenCentral()

    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        content {
            includeModule("net.minestom", "minestom")
        }
    }
}

dependencies {
    api("org.jspecify:jspecify:1.0.0")
    api("org.jline:jline-terminal:$jlineVersion")
    api("org.jline:jline-reader:$jlineVersion")

    runtimeOnly("org.jline:jline-terminal-ffm:$jlineVersion")
    runtimeOnly("org.jline:jline-terminal-jni:$jlineVersion")

    compileOnly("net.minestom:minestom:$minestomVersion")
    compileOnly("org.slf4j:slf4j-api:2.0.18")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:all,-serial,-this-escape")
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}
