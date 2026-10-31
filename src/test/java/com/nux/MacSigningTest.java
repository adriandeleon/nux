package com.nux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the macOS Developer ID signing chain (README, "macOS signing"). None of it runs off a Mac or
 * outside a release, and each mistake it guards against ships quietly: a jpackage call that never
 * receives the signing options, or a tag that publishes an unsigned dmg.
 */
class MacSigningTest {

    @Test
    void jpackageIsHandedTheSigningOptions() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(
                pom.contains("<jpackage.signArgs></jpackage.signArgs>"),
                "empty by default, so the plugin skips it on every build but a signing release");
        assertTrue(
                pom.contains("<option>${jpackage.signArgs}</option>"),
                "the jpackage plugin's additionalOptions no longer carry it");
        String entitlements = Files.readString(Path.of("packaging", "macos", "Nux.entitlements"));
        assertTrue(entitlements.contains("com.apple.security.cs.allow-jit"), "the JVM's JIT under the hardened runtime");
    }

    @Test
    void aTagCannotPublishAnUnsignedOrUnnotarizedDmg() throws IOException {
        String release = Files.readString(Path.of(".github", "workflows", "release.yml"))
                .replace("\r\n", "\n");
        int importCert = release.indexOf("- name: Import signing certificate (macOS)");
        int pkg = release.indexOf("- name: Package");
        int notarize = release.indexOf("- name: Notarize the dmg (macOS)");
        int stage = release.indexOf("- name: Stage artifacts");
        assertTrue(importCert >= 0 && notarize >= 0, "the macOS legs no longer sign and notarize");
        assertTrue(
                importCert < pkg && pkg < notarize && notarize < stage,
                "the keychain exists before jpackage signs, and the dmg is notarized before it is staged");
        String importStep = release.substring(importCert, pkg);
        assertTrue(
                importStep.contains("GITHUB_REF_TYPE\" = \"tag\"") && importStep.contains("exit 1"),
                "a tag without the signing secrets must fail");
        assertTrue(importStep.contains("--mac-sign") && importStep.contains("--mac-entitlements"));
        assertTrue(release.substring(pkg, notarize).contains("-Djpackage.signArgs="), "Package passes the options");
        String notarizeStep = release.substring(notarize, stage);
        assertTrue(notarizeStep.contains("stapler staple") && notarizeStep.contains("spctl --assess"));
        assertTrue(
                release.contains("if: always() && steps.macsign.outputs.enabled == 'true'")
                        && release.contains("security delete-keychain"),
                "the keychain holding the private key is removed even when the leg fails");
    }
}
