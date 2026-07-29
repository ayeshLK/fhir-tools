/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.health.cmd.core.utils;

import io.ballerina.health.cmd.core.exception.BallerinaHealthException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FhirIgPackageDownloaderTest {

    @TempDir
    Path tempDir;

    @Test
    void extractPackageWritesStructureDefinitionJson() throws Exception {
        byte[] tgz = buildSamplePackageTgz();
        Path target = tempDir.resolve("extracted");
        FhirIgPackageDownloader.extractPackage(tgz, target);

        Path patientSd = target.resolve("StructureDefinition-Patient.json");
        assertTrue(Files.exists(patientSd));
        String content = Files.readString(patientSd);
        assertTrue(content.contains("\"resourceType\": \"StructureDefinition\""));
        assertTrue(SpecificationPathUtils.containsStructureDefinitionResources(target));
    }

    @Test
    void downloadAndExtractSkipsWhenDefinitionsPresent() throws Exception {
        Path target = tempDir.resolve("cached-ig");
        Files.createDirectories(target);
        Files.writeString(target.resolve("StructureDefinition-Patient.json"), """
                {
                  "resourceType": "StructureDefinition",
                  "url": "http://hl7.org/fhir/StructureDefinition/Patient",
                  "name": "Patient",
                  "type": "Patient",
                  "kind": "resource",
                  "fhirVersion": "4.0.1"
                }
                """);

        FhirIgPackageDownloader.DownloadOptions options = new FhirIgPackageDownloader.DownloadOptions(
                "https://packages.fhir.org", tempDir.resolve("cache").toString(), 60, false);
        Path result = FhirIgPackageDownloader.downloadAndExtract(
                target, "hl7.fhir.r4.core", "4.0.1", options);
        assertEquals(target, result);
    }

    @Test
    void parseIgSpecUsesLatestSentinelWhenVersionMissing() {
        FhirIgPackageDownloader.ParsedIgSpec spec =
                FhirIgPackageDownloader.parseIgSpec("hl7.fhir.us.core", null);
        assertEquals("hl7.fhir.us.core", spec.name());
        assertEquals("latest", spec.version());
    }

    @Test
    void parseLatestVersionFromMetadata() throws Exception {
        String metadata = """
                {
                  "name": "hl7.fhir.us.core",
                  "dist-tags": { "latest": "9.0.0" }
                }
                """;
        assertEquals("9.0.0",
                FhirIgPackageDownloader.parseLatestVersionFromMetadata(metadata, "hl7.fhir.us.core"));
    }

    @Test
    void parseLatestVersionFromMetadataMissingDistTags() {
        assertThrows(BallerinaHealthException.class, () ->
                FhirIgPackageDownloader.parseLatestVersionFromMetadata("{\"name\":\"x\"}", "x"));
    }

    @Test
    void resolvePackageVersionKeepsExplicitVersion() throws Exception {
        FhirIgPackageDownloader.DownloadOptions options = new FhirIgPackageDownloader.DownloadOptions(
                "https://packages.fhir.org", tempDir.resolve("cache").toString(), 60, false, true, null);
        assertEquals("6.1.0", FhirIgPackageDownloader.resolvePackageVersion(
                "https://packages.fhir.org", "hl7.fhir.us.core", "6.1.0", options));
    }

    @Test
    void sanitizeIgDirectoryName() {
        assertEquals("hl7_fhir_us_core", FhirIgPackageDownloader.sanitizeIgDirectoryName("hl7.fhir.us.core"));
    }

    private static byte[] buildSamplePackageTgz() throws IOException {
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(byteOut);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzipOut)) {
            addTarEntry(tarOut, "package/package.json", """
                    {"name":"test.package","version":"1.0.0","fhirVersions":["4.0.1"]}
                    """);
            addTarEntry(tarOut, "package/StructureDefinition-Patient.json", """
                    {
                      "resourceType": "StructureDefinition",
                      "url": "http://hl7.org/fhir/StructureDefinition/Patient",
                      "name": "Patient",
                      "type": "Patient",
                      "kind": "resource",
                      "fhirVersion": "4.0.1"
                    }
                    """);
            tarOut.finish();
        }
        return byteOut.toByteArray();
    }

    private static void addTarEntry(TarArchiveOutputStream tarOut, String name, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        tarOut.putArchiveEntry(entry);
        tarOut.write(bytes);
        tarOut.closeArchiveEntry();
    }
}
