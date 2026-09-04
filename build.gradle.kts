import java.net.URI
import java.security.MessageDigest

plugins { java }

group = "gg.mira"
version = "0.2.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val miraCoreVersion = "0.2.0"
val miraCoreSha256 = "66433a266a76088d2a2de90ac1beb1a5a183c26891ee8f394827b47830195b03"
val miraCoreJar = layout.projectDirectory.file("libs/MiraCore-$miraCoreVersion.jar").asFile

val miraFactionsVersion = "0.2.10"
val miraFactionsSha256 = "a4126efa98f2636d0a106355962f1bba4539ef37a861f6a9584ccb8025daf7f1"
val miraFactionsJar = layout.projectDirectory.file("libs/MiraFactions-$miraFactionsVersion.jar").asFile

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(file.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
}

fun downloadVerified(url: String, target: File, expectedSha256: String) {
    if (target.exists() && sha256(target) == expectedSha256) return
    target.parentFile.mkdirs()
    URI(url).toURL().openStream().use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    }
    check(sha256(target) == expectedSha256) {
        "Downloaded dependency failed SHA-256 verification: ${target.name}"
    }
}

val downloadMiraDependencies by tasks.registering {
    doLast {
        downloadVerified(
            "https://github.com/FiveSOCE/MIra-core/releases/download/v$miraCoreVersion/MiraCore-$miraCoreVersion.jar",
            miraCoreJar,
            miraCoreSha256
        )
        downloadVerified(
            "https://github.com/FiveSOCE/Mira-Factions/releases/download/v$miraFactionsVersion/MiraFactions-$miraFactionsVersion.jar",
            miraFactionsJar,
            miraFactionsSha256
        )
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:2.15.3")
    compileOnly(files(miraCoreJar))
    compileOnly(files(miraFactionsJar))
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

tasks.withType<JavaCompile>().configureEach {
    dependsOn(downloadMiraDependencies)
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar { archiveFileName.set("MiraOutposts-${project.version}.jar") }
