import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
  id("java")
  alias(libs.plugins.changelog)
  alias(libs.plugins.gradleJvmWrapper)
  alias(libs.plugins.grammarkit)
  alias(libs.plugins.intellijPlatform)
  alias(libs.plugins.kotlin)
}

sourceSets {
  main {
    java.srcDir("src/main/gen-parser")
    java.srcDir("src/main/gen-lexer")
    resources {
      exclude("**.bnf")
      exclude("**.flex")
    }
  }
}

val pluginVersion: String by ext.properties
group = "com.intellij.plugin"
version = pluginVersion

repositories {
  intellijPlatform {
    defaultRepositories()
  }

  mavenCentral()
  ivy {
    url = uri("https://github.com/PowerShell/PSScriptAnalyzer/releases/download/")
    patternLayout { artifact("[revision]/[module].[revision].[ext]") }
    content { includeGroup("PSScriptAnalyzer") }
    metadataSources { artifact() }
  }
  ivy {
    url = uri("https://github.com/PowerShell/PowerShellEditorServices/releases/download/")
    patternLayout { artifact("v[revision]/[module].[ext]") }
    content { includeGroup("PowerShellEditorServices") }
    metadataSources { artifact() }
  }
}

val psScriptAnalyzerVersion: String by project
val psScriptAnalyzerSha256Hash: String by project
val psScriptAnalyzer: Configuration by configurations.creating

val psesVersion: String by project
val psesSha256Hash: String by project
val powerShellEditorServices: Configuration by configurations.creating

dependencies {
  intellijPlatform {
    intellijIdeaCommunity(libs.versions.intellij, useInstaller = !libs.versions.intellij.get().contains("SNAPSHOT"))
    bundledPlugins("org.intellij.intelliLang", "org.jetbrains.plugins.terminal")
    testFramework(TestFrameworkType.Bundled)
    testFramework(TestFrameworkType.Platform)
    pluginVerifier()
  }

  implementation(libs.bundles.junixsocket)
  implementation(libs.lsp4j)
  implementation(libs.lsp4jdebug)
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.openTest4J)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
  testRuntimeOnly(libs.junit.vintage.engine)

  psScriptAnalyzer(
    group = "PSScriptAnalyzer",
    name = "PSScriptAnalyzer",
    version = psScriptAnalyzerVersion,
    ext = "nupkg"
  )

  powerShellEditorServices(
    group = "PowerShellEditorServices",
    name = "PowerShellEditorServices",
    version = psesVersion,
    ext = "zip"
  )
}

intellijPlatform {
  pluginConfiguration {
    name = "PowerShell"
  }
  pluginVerification {
    ides {
      recommended()
    }
    freeArgs.addAll(
      "-mute", "ForbiddenPluginIdPrefix",
      "-mute", "TemplateWordInPluginId"
    )
    failureLevel.add(VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES)
  }
}

configurations {
  runtimeClasspath {
    // NOTE: Newer versions of these libraries are provided by IntelliJ, so let's exclude them from the dependency set
    // of org.eclipse.lsp4j:
    exclude("com.google.code.gson", "gson")
    exclude("com.google.guava", "guava")
  }
}

tasks {
  val resources = file("src/main/resources")

  generateLexer {
    val genLexerRoot = file("src/main/gen-lexer")
    val genLexerPackageDirectory = genLexerRoot.resolve("com/intellij/plugin/powershell/lang")

    purgeOldFiles = true
    sourceFile = resources.resolve("_PowerShellLexer.flex")
    targetOutputDir = genLexerPackageDirectory
    defaultCharacterEncoding = "UTF-8"
  }

  generateParser {
    val genParserRoot = file("src/main/gen-parser")

    purgeOldFiles = true
    sourceFile = resources.resolve("PowerShell.bnf")
    targetRootOutputDir = genParserRoot
    pathToParser = "com/intellij/plugin/powershell/lang/parser"
    pathToPsiRoot = "com/intellij/plugin/powershell/psi"
    defaultCharacterEncoding = "UTF-8"
  }

  withType<Test> {
    useJUnitPlatform()
  }

  withType<JavaCompile> {
    dependsOn(generateLexer, generateParser)

    options.apply {
      compilerArgs.add("-Werror")
      encoding = "UTF-8"
    }
    sourceCompatibility = "17"
    targetCompatibility = "17"
  }
  withType<KotlinCompile> {
    dependsOn(generateLexer, generateParser)

    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_17)
      allWarningsAsErrors = true
    }
  }

  fun File.verifyHash(expectedHash: String) {
    println("Calculating hash for $name...")
    val data = readBytes()
    val hash = MessageDigest.getInstance("SHA-256").let { sha256 ->
      sha256.update(data)
      sha256.digest().joinToString("") { "%02X".format(it) }
    }
    println("Expected hash for $name = $expectedHash")
    println("Calculated hash for $name = $hash")
    if (!hash.equals(expectedHash, ignoreCase = true)) {
      error("$name hash check failed.\n" +
        "Please try re-downloading the dependency, or update the expected hash in the gradle.properties file.")
    }
  }

  fun PrepareSandboxTask.unpackPsScriptAnalyzer(outDir: Provider<String>) {
    inputs.files(psScriptAnalyzer)
    inputs.property("hash", psScriptAnalyzerSha256Hash)

    doFirst {
      psScriptAnalyzer.singleFile.verifyHash(psScriptAnalyzerSha256Hash)
    }

    from(zipTree(psScriptAnalyzer.singleFile)) {
      into(outDir.map { "$it/PSScriptAnalyzer" })

      // NuGet stuff:
      exclude("_manifest/**", "_rels/**", "package/**", "[Content_Types].xml", "*.nuspec")

      // Compatibility profiles, see https://github.com/PowerShell/PSScriptAnalyzer/issues/1148
      exclude("compatibility_profiles/**")
    }
  }

  fun PrepareSandboxTask.unpackPowerShellEditorServices(outDir: Provider<String>) {
    inputs.files(powerShellEditorServices)
    inputs.property("hash", psesSha256Hash)
    doFirst {
      powerShellEditorServices.singleFile.verifyHash(psesSha256Hash)
    }

    from(zipTree(powerShellEditorServices.singleFile)) {
      into(outDir.map { "$it/" })
      // We only need this module and not anything else from the archive:
      include("PowerShellEditorServices/**")
    }
  }

  withType<PrepareSandboxTask> {
    val outDir = intellijPlatform.pluginConfiguration.name.map { "$it/lib/LanguageHost/modules" }
    unpackPsScriptAnalyzer(outDir)
    unpackPowerShellEditorServices(outDir)
  }

  val maxUnpackedPluginBytes: String by project
  val verifyDistributionSize by registering {
    group = "verification"

    val pluginArtifact = buildPlugin.flatMap { it.archiveFile }

    inputs.file(pluginArtifact)
    inputs.property("maxUnpackedPluginBytes", maxUnpackedPluginBytes)
    outputs.upToDateWhen { true }

    doLast {
      val artifact = pluginArtifact.get().asFile
      val unpackedSize = ZipFile(artifact).use { it.entries().asSequence().sumOf { e -> e.size } }
      val unpackedSizeMiB = "%.3f".format(unpackedSize / 1024.0 / 1024.0)
      if (unpackedSize > maxUnpackedPluginBytes.toLong()) {
        error(
          "The resulting artifact size is too large. Expected no more than $maxUnpackedPluginBytes, but got" +
            " $unpackedSize bytes ($unpackedSizeMiB MiB).\nArtifact path: \"$artifact\"."
        )
      }

      println("Verified unpacked distribution size: $unpackedSizeMiB MiB.")
    }
  }

  check {
    dependsOn(verifyDistributionSize)
    dependsOn(verifyPlugin)
  }

  runIde {
    jvmArgs("-Dide.plugins.snapshot.on.unload.fail=true", "-XX:+UnlockDiagnosticVMOptions")
    autoReload = true
  }

  patchPluginXml {
    untilBuild.set(provider { null })

    changeNotes.set(provider {
      changelog.renderItem(
        changelog
          .getLatest()
          .withHeader(false)
          .withEmptySections(false),
        org.jetbrains.changelog.Changelog.OutputType.HTML
      )
    })
  }

  if (libs.versions.intellij.get() != libs.versions.intellijPreview.get()) {
    val testPreview by intellijPlatformTesting.testIde.registering {
      version = libs.versions.intellijPreview
    }

    check { dependsOn(testPreview.name) }
  }
}
