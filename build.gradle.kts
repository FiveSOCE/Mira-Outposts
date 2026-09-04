import java.net.URI
import java.security.MessageDigest

plugins { java }

group = "gg.mira"
version = "0.1.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val miraCoreVersion = "0.2.0"
val miraCoreSha256 = "66433a266a76088d2a2de90ac1beb1a5a183c26891ee8f394827b47830195b03"
val miraCoreJar = layout.projectDirectory.file("libs/MiraCore-$miraCoreVersion.jar").asFile

val miraFactionsVersion = "0.2.8"
val miraFactionsSha256 = "9e3ad2bc26c25c279cb3f457157f5548c6a7292d9dfef7210b104ac85237b9e7"
val miraFactionsJar = layout.projectDirectory.file("libs/MiraFactions-$miraFactionsVersion.jar").asFile

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(file.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
}

val downloadMiraCore by tasks.registering {
    doLast {
        if (miraCoreJar.exists() && sha256(miraCoreJar) == miraCoreSha256) return@doLast
        miraCoreJar.parentFile.mkdirs()
        URI("https://github.com/FiveSOCE/MIra-core/releases/download/v$miraCoreVersion/MiraCore-$miraCoreVersion.jar")
            .toURL().openStream().use { input ->
                miraCoreJar.outputStream().use { output -> input.copyTo(output) }
            }
        check(sha256(miraCoreJar) == miraCoreSha256) { "Downloaded MiraCore JAR failed SHA-256 verification" }
    }
}

val downloadMiraFactions by tasks.registering {
    doLast {
        if (miraFactionsJar.exists() && sha256(miraFactionsJar) == miraFactionsSha256) return@doLast
        miraFactionsJar.parentFile.mkdirs()
        URI("https://github.com/FiveSOCE/Mira-Factions/releases/download/v$miraFactionsVersion/MiraFactions-$miraFactionsVersion.jar")
            .toURL().openStream().use { input ->
                miraFactionsJar.outputStream().use { output -> input.copyTo(output) }
            }
        check(sha256(miraFactionsJar) == miraFactionsSha256) { "Downloaded MiraFactions JAR failed SHA-256 verification" }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(files(miraCoreJar))
    compileOnly(files(miraFactionsJar))
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

tasks.withType<JavaCompile>().configureEach {
    dependsOn(downloadMiraCore, downloadMiraFactions)
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar { archiveFileName.set("MiraOutposts-${project.version}.jar") }
