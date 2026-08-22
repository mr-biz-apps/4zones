import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ─────────────────────────────────────────────────────────────────────────────
// D-2 (H1 / H3) — RELEASE SIGNING INPUTS
//
// No key material EVER lives in this repository. The four signing inputs are
// read from a git-ignored properties file, or from the environment for CI.
//
// The hazard this guards is NOT "the build breaks without a key". It is the
// opposite and likelier failure: `assembleRelease` SUCCEEDING without a key and
// emitting a release-shaped, non-installable APK that is then mistaken for the
// deliverable. Android requires every update to be signed by the SAME key, so a
// wrongly-signed or unsigned first release is unrepairable.
//
// Therefore: the DISTRIBUTABLE release tasks fail loudly on absent, incomplete,
// invalid or implausible signing input (see :app:validateReleaseSigning), and
// the only way to obtain an unsigned artifact is the deliberately, distinctly
// named `unsignedProof` build type, whose output cannot be mistaken for the
// release artifact.
// ─────────────────────────────────────────────────────────────────────────────

// ──────────────────────────────────────────────────────────────────────────────
// D-4 CONDITION 7 — APPLICATION IDENTITY IS ASSERTED, NEVER INHERITED
//
// `applicationId` can never be changed after publication without orphaning every
// install. This repository is periodically RE-SEEDED from an upstream tree — a
// mechanism that overwrites this very file — and that mechanism has already
// reverted the application identity once, silently.
//
// So the identity is not read out of whatever this script happens to say. It is
// FROZEN below, `applicationId` is DERIVED from the frozen value (they cannot
// drift apart), and :app:verify<Variant>ApplicationId re-reads the identity back
// out of the BUILT APK with `aapt2 dump packagename` and FAILS the build if the
// two disagree. Reporting the value is not asserting it; this asserts it.
//
// The check runs on the packaged artifact rather than on source or the merged
// manifest, because the artifact is what ships and inheriting an identity from
// an overwritten build script is precisely the failure mode.
//
// PRINCIPLE: IRREVERSIBLE IDENTITY MUST BE ASSERTED, NEVER INHERITED.
// ──────────────────────────────────────────────────────────────────────────────

/**
 * THE FROZEN APPLICATION IDENTITY. Single source of truth: `applicationId`,
 * `namespace` and every expected value the verification task asserts are all
 * derived from this one constant. Changing it changes what ships; a re-seed
 * that reverts it fails the build loudly instead of publishing quietly.
 */
val frozenApplicationId = "uk.mr_biz.fourzones"

/**
 * The FROZEN, COMPLETE set of build types and the applicationId suffix each one
 * is permitted to add to [frozenApplicationId]. The build types below read their
 * suffix from this map, and the verification task derives its expected value from
 * the same map, so a variant's shipped identity is always
 * `frozenApplicationId + <its frozen suffix>` and never anything else.
 *
 * A build type absent from this map is a HARD CONFIGURATION FAILURE — fail
 * closed. A re-seed that introduces a new build type must declare its identity
 * here before it can be built at all.
 */
val frozenApplicationIdSuffixes: Map<String, String> = mapOf(
    "debug" to "",
    "release" to "",
    "unsignedProof" to ".unsignedproof",
)

/** The complete applicationId a given build type is frozen to produce. */
fun frozenApplicationIdFor(buildTypeName: String): String {
    val suffix = frozenApplicationIdSuffixes[buildTypeName]
        ?: throw GradleException(
            buildString {
                appendLine("UNDECLARED APPLICATION IDENTITY — refusing to configure build type '$buildTypeName'.")
                appendLine("")
                appendLine("D-4 condition 7: every build type must have its applicationId suffix frozen in")
                appendLine("`frozenApplicationIdSuffixes` in app/build.gradle.kts, so the identity it ships")
                appendLine("is derived from `frozenApplicationId` and can be asserted against the built APK.")
                appendLine("")
                appendLine("Frozen build types: ${frozenApplicationIdSuffixes.keys.sorted().joinToString(", ")}")
                append("Undeclared build type: $buildTypeName")
            },
        )
    return frozenApplicationId + suffix
}

val releaseSigningPropertiesFile = rootProject.file("keystore/release-signing.properties")

val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use { load(it) }
    }
}

fun releaseSigningInput(propertyKey: String, environmentKey: String): String? =
    (releaseSigningProperties.getProperty(propertyKey) ?: System.getenv(environmentKey))
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

val releaseStoreFilePath: String? =
    releaseSigningInput("storeFile", "DEXZONES_RELEASE_STORE_FILE")
val releaseStorePassword: String? =
    releaseSigningInput("storePassword", "DEXZONES_RELEASE_STORE_PASSWORD")
val releaseKeyAlias: String? =
    releaseSigningInput("keyAlias", "DEXZONES_RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? =
    releaseSigningInput("keyPassword", "DEXZONES_RELEASE_KEY_PASSWORD")

val releaseStoreFile: File? = releaseStoreFilePath?.let { rootProject.file(it) }

/**
 * Every reason this build must NOT produce a distributable release, in a stable
 * order. Empty means the four inputs are present and the store file is at least
 * keystore-SHAPED. It does NOT mean the key is the right key: only signing (an
 * owner-performed step) and `apksigner --print-certs` can establish that.
 *
 * Only the first four bytes of the store file are ever read, purely to reject
 * non-keystore bytes. No key is loaded here and no password is ever logged.
 */
val releaseSigningProblems: List<String> = buildList {
    if (releaseStoreFilePath == null) {
        add("storeFile is absent (property `storeFile` / env DEXZONES_RELEASE_STORE_FILE)")
    }
    if (releaseStorePassword == null) {
        add("storePassword is absent (property `storePassword` / env DEXZONES_RELEASE_STORE_PASSWORD)")
    }
    if (releaseKeyAlias == null) {
        add("keyAlias is absent (property `keyAlias` / env DEXZONES_RELEASE_KEY_ALIAS)")
    }
    if (releaseKeyPassword == null) {
        add("keyPassword is absent (property `keyPassword` / env DEXZONES_RELEASE_KEY_PASSWORD)")
    }
    if (releaseStoreFile != null) {
        if (!releaseStoreFile.isFile) {
            add("storeFile path does not resolve to a regular file: ${releaseStoreFile.absolutePath}")
        } else {
            val header = ByteArray(4)
            val read = releaseStoreFile.inputStream().use { it.read(header) }
            val pkcs12 = read >= 1 && header[0] == 0x30.toByte()
            val jks = read >= 4 &&
                header[0] == 0xFE.toByte() && header[1] == 0xED.toByte() &&
                header[2] == 0xFE.toByte() && header[3] == 0xED.toByte()
            if (!pkcs12 && !jks) {
                add(
                    "storeFile is not a keystore: ${releaseStoreFile.absolutePath} begins with neither " +
                        "a PKCS#12 (0x30) nor a JKS (0xFEEDFEED) header",
                )
            }
        }
    }
}

val releaseSigningIsComplete: Boolean = releaseSigningProblems.isEmpty()

android {
    // D-4 condition 7 — DERIVED, never restated.
    namespace = frozenApplicationId
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // D-4 condition 7 — DERIVED from the frozen constant, never restated,
        // so the two cannot drift apart.
        applicationId = frozenApplicationId
        minSdk = 31
        targetSdk = 37
        // D-2: versionCode is MONOTONIC across every published artifact —
        // Android refuses to install a lower versionCode over a higher one.
        versionCode = 1
        // D-2: pre-1.0 and beta-honest. This is a Shizuku-backed sideload beta,
        // not a 1.0 product.
        versionName = "0.9.0-beta1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Created ONLY when all four inputs are present and the store file is
        // keystore-shaped. When it is absent, `release` has no signingConfig and
        // :app:validateReleaseSigning fails the build before anything is packaged.
        if (releaseSigningIsComplete) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // D-2 H4: minification/R8 is EXPLICITLY DISABLED. R8 has never been
            // proven on this codebase — reflective Shizuku user-service entry
            // points and the AIDL binder stubs are exactly the shapes R8 breaks
            // silently — and proving it is deferred, NOT assumed. Turning this on
            // without that proof risks a release that fails only at runtime, on a
            // user's device.
            optimization {
                enable = false
            }
            // D-2 H9: never ship a debuggable release.
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
        }

        // D-2 H3: the ONLY sanctioned way to obtain an unsigned, release-shaped
        // artifact (for manifest/content inspection and size measurement). Its
        // task name, output path, applicationId and versionName all announce that
        // it is not the deliverable, so it cannot be mistaken for, installed as,
        // or updated over the released app.
        create("unsignedProof") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = null
            isDebuggable = false
            // D-4 condition 7 — the suffix is read from the frozen map, which is the
            // same map the verification task derives its expectation from.
            applicationIdSuffix = frozenApplicationIdSuffixes.getValue("unsignedProof")
            versionNameSuffix = "-UNSIGNED-PROOF-NOT-FOR-DISTRIBUTION"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // AIDL is required only for the Shizuku user-service binder interface.
        aidl = true
        // BuildConfig is used by the Shizuku user-service args (version/debuggable)
        // and by the D-2 H10 release logging gate.
        buildConfig = true
    }
}

// D-2 H3 — the loud failure. Wired as a hard dependency of every DISTRIBUTABLE
// release task, so no path through `assembleRelease` / `bundleRelease` can
// produce an artifact without complete, plausible signing input.
val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    group = "verification"
    description =
        "D-2 H3: fails loudly unless complete, keystore-shaped release signing inputs are present."
    val problems = releaseSigningProblems
    val storeFileDescription = releaseStoreFilePath ?: "<absent>"
    outputs.upToDateWhen { false }
    doLast {
        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("RELEASE SIGNING INPUT REJECTED — refusing to build a distributable release.")
                    appendLine("")
                    appendLine("An unsigned or wrongly-signed release cannot be installed or updated, and the")
                    appendLine("first published signature is permanent. Emitting one silently is the hazard")
                    appendLine("this gate exists to prevent (D-2 H1/H3).")
                    appendLine("")
                    appendLine("storeFile input: $storeFileDescription")
                    appendLine("Problems:")
                    problems.forEach { appendLine("  - $it") }
                    appendLine("")
                    appendLine("Supply all four inputs in keystore/release-signing.properties (git-ignored),")
                    appendLine("or as DEXZONES_RELEASE_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD.")
                    append("For a NON-DISTRIBUTABLE artifact to inspect, use :app:assembleUnsignedProof.")
                },
            )
        }
    }
}

tasks.configureEach {
    if (name in
        setOf("assembleRelease", "packageRelease", "bundleRelease", "packageReleaseBundle")
    ) {
        dependsOn(validateReleaseSigning)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// D-4 CONDITION 7 — the executable assertion.
//
// `aapt2 dump packagename` is run against every packaged APK of every variant,
// and the value it reports is compared with the value derived from
// `frozenApplicationId`. Mismatch throws, printing BOTH values.
//
// Wired as a dependency of `assemble<Variant>`, so it runs on the ordinary build
// path — including `./gradlew assembleUnsignedProof`, which is the D-4
// condition 1 completeness build and the condition 6 public-CI command. A check
// nobody invokes protects nothing.
// ──────────────────────────────────────────────────────────────────────────────

/**
 * aapt2, resolved from the Android SDK. Resolution is deliberately NOT fatal at
 * configuration time — an absent aapt2 fails the verification task itself, with
 * the search path printed, rather than breaking unrelated tasks.
 */
val applicationIdentityAapt2Candidates: List<File> = run {
    val sdkDirectory = androidComponents.sdkComponents.sdkDirectory.get().asFile
    val buildToolsRoot = File(sdkDirectory, "build-tools")
    val declaredVersion = android.buildToolsVersion
    val preferred = listOf(
        File(buildToolsRoot, "$declaredVersion/aapt2"),
        File(buildToolsRoot, "$declaredVersion/aapt2.exe"),
    )
    val fallbacks = (buildToolsRoot.listFiles().orEmpty())
        .filter { it.isDirectory }
        .sortedByDescending { it.name }
        .flatMap { listOf(File(it, "aapt2"), File(it, "aapt2.exe")) }
    (preferred + fallbacks).distinct()
}

val verifyApplicationIdentity = tasks.register("verifyApplicationIdentity") {
    group = "verification"
    description =
        "D-4 condition 7: asserts every built APK declares the frozen application identity."
}

androidComponents.onVariants { variant ->
    val variantName = variant.name
    val buildTypeName = variant.buildType ?: variantName
    // Derived at CONFIGURATION time from the frozen constant. Never read back
    // from AGP's computed applicationId, which is the value under suspicion.
    val expectedApplicationId = frozenApplicationIdFor(buildTypeName)
    val apkDirectory = variant.artifacts.get(SingleArtifact.APK)
    val aapt2Candidates = applicationIdentityAapt2Candidates
    val frozenBase = frozenApplicationId
    val capitalisedVariant = variantName.replaceFirstChar { it.uppercaseChar() }

    val verifyVariantIdentity =
        tasks.register("verify${capitalisedVariant}ApplicationId") {
            group = "verification"
            description =
                "D-4 condition 7: asserts the built $variantName APK declares $expectedApplicationId."
            inputs.dir(apkDirectory).withPropertyName("apkDirectory")
            inputs.property("expectedApplicationId", expectedApplicationId)
            outputs.upToDateWhen { false }
            doLast {
                val aapt2 = aapt2Candidates.firstOrNull { it.isFile }
                    ?: throw GradleException(
                        buildString {
                            appendLine("APPLICATION IDENTITY NOT VERIFIED — aapt2 was not found.")
                            appendLine("")
                            appendLine("D-4 condition 7 must read the identity back out of the built APK.")
                            appendLine("Failing closed rather than skipping the assertion.")
                            appendLine("")
                            appendLine("Searched:")
                            aapt2Candidates.forEach { appendLine("  - ${it.absolutePath}") }
                            append("Install Android SDK build-tools, or set android.buildToolsVersion.")
                        },
                    )

                val apks = apkDirectory.get().asFile
                    .walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".apk") }
                    .sortedBy { it.absolutePath }
                    .toList()

                if (apks.isEmpty()) {
                    throw GradleException(
                        "APPLICATION IDENTITY NOT VERIFIED — no APK found under " +
                            "${apkDirectory.get().asFile.absolutePath} for variant '$variantName'. " +
                            "D-4 condition 7 fails closed rather than passing vacuously.",
                    )
                }

                apks.forEach { apk ->
                    val process = ProcessBuilder(
                        aapt2.absolutePath,
                        "dump",
                        "packagename",
                        apk.absolutePath,
                    ).start()
                    val standardOutput = process.inputStream.bufferedReader().use { it.readText() }
                    val standardError = process.errorStream.bufferedReader().use { it.readText() }
                    val exitCode = process.waitFor()
                    if (exitCode != 0) {
                        throw GradleException(
                            "APPLICATION IDENTITY NOT VERIFIED — `aapt2 dump packagename` exited " +
                                "$exitCode for ${apk.absolutePath}: ${standardError.trim()}",
                        )
                    }
                    val actualApplicationId = standardOutput.trim()
                    if (actualApplicationId != expectedApplicationId) {
                        throw GradleException(
                            buildString {
                                appendLine("APPLICATION IDENTITY MISMATCH — refusing to build.")
                                appendLine("")
                                appendLine("  expected: $expectedApplicationId")
                                appendLine("  actual:   $actualApplicationId")
                                appendLine("")
                                appendLine("Variant:    $variantName")
                                appendLine("Build type: $buildTypeName")
                                appendLine("Artifact:   ${apk.absolutePath}")
                                appendLine("Read with:  ${aapt2.absolutePath} dump packagename <apk>")
                                appendLine("")
                                appendLine("`expected` is derived from the FROZEN constant `frozenApplicationId`")
                                appendLine("(= $frozenBase) in app/build.gradle.kts plus this build type's frozen")
                                appendLine("suffix; `actual` was read back out of the APK that would ship.")
                                appendLine("")
                                appendLine("applicationId can NEVER be changed after publication without orphaning")
                                appendLine("every install, so this disagreement is not a warning (D-4 condition 7).")
                                appendLine("If the identity legitimately changed, change `frozenApplicationId` —")
                                append("deliberately, once, and before publication.")
                            },
                        )
                    }
                    logger.lifecycle(
                        "D-4 condition 7: ${apk.name} declares $actualApplicationId (frozen, asserted).",
                    )
                }
            }
        }

    // `assemble<Variant>` is created after onVariants runs, so match lazily rather
    // than resolving it eagerly.
    tasks.matching { it.name == "assemble$capitalisedVariant" }
        .configureEach { dependsOn(verifyVariantIdentity) }
    verifyApplicationIdentity.configure { dependsOn(verifyVariantIdentity) }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Privileged (shell-identity) backend. It carries BOTH the read-only
    // desktop-topology reads and the app's one mutating operation (task
    // resize) — it is the only privileged path. Pinned explicitly; api =
    // client API, provider = binder delivery from the Shizuku manager app.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
