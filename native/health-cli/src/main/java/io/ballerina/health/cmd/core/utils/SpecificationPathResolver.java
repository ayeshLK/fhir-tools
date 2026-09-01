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

import io.ballerina.health.cmd.core.config.IgRegistryConfig;
import io.ballerina.health.cmd.core.exception.BallerinaHealthException;

import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves FHIR specification directories, optionally downloading IG packages from the FHIR registry first.
 */
public final class SpecificationPathResolver {

    private SpecificationPathResolver() {
    }

    public static ResolvedSpecification resolve(ResolveRequest request) throws BallerinaHealthException {
        IgRegistryConfig registryConfig = request.registryConfig() != null
                ? request.registryConfig() : IgRegistryConfig.defaults();

        String cacheDir = resolveCacheDir(request.cacheDir(), request.executionPath(), registryConfig.getCacheDir());
        boolean cacheDirExplicit = request.cacheDir() != null && !request.cacheDir().trim().isEmpty();
        // resolveCacheDir() always returns an absolute path (it's joined against the absolute execution path),
        // so "relative to the project" has to be judged from the raw --ig-cache-dir override, before resolution.
        boolean cacheDirRelativeToProject = !cacheDirExplicit || !Paths.get(request.cacheDir().trim()).isAbsolute();
        PrintStream out = request.printStream() != null ? request.printStream() : System.out;
        FhirIgPackageDownloader.DownloadOptions downloadOptions = new FhirIgPackageDownloader.DownloadOptions(
                request.registryUrl() != null ? request.registryUrl() : registryConfig.getRegistryUrl(),
                cacheDir,
                registryConfig.getHttpTimeoutSeconds(),
                request.forceDownload(),
                request.nonInteractive(),
                cacheDirExplicit,
                cacheDirRelativeToProject,
                out
        );

        if (request.igName() != null && !request.igName().isEmpty()) {
            Path targetDir = Paths.get(request.executionPath(), "spec",
                    FhirIgPackageDownloader.sanitizeIgDirectoryName(request.igName()));
            downloadIg(request, registryConfig, request.igName(), request.igVersion(), targetDir, downloadOptions);
            String mapped = registryConfig.resolveDependentPackage(request.igName(), request.igVersion());
            return new ResolvedSpecification(targetDir, request.igName(), mapped);
        }

        if (request.specPathArg() != null && !request.specPathArg().trim().isEmpty()) {
            if (CMD_MODE_TEMPLATE.equals(request.mode())) {
                Path path = SpecificationPathUtils.resolveTemplateSpecificationPath(
                        request.specPathArg(), request.executionPath());
                return new ResolvedSpecification(path, null, null);
            }
            Path path = HealthCmdUtils.validateAndSetSpecificationPath(
                    request.specPathArg(), request.executionPath());
            return new ResolvedSpecification(path, null, null);
        }

        IgRegistryConfig.IgPackageRef defaultPkg = registryConfig.getDefaultPackage(
                request.fhirVersion() != null ? request.fhirVersion() : "r4");
        Path targetDir = Paths.get(request.executionPath(), "spec",
                HealthCmdConstants.CMD_DEFAULT_INTERNATIONAL_IG_DIR);
        downloadIg(request, registryConfig, defaultPkg.name(), defaultPkg.version(), targetDir, downloadOptions);
        String mappedDependent = defaultDependentForIgPackage(defaultPkg.name(), registryConfig, request.igName(),
                request.igVersion());
        if (CMD_MODE_TEMPLATE.equals(request.mode())) {
            Path path = SpecificationPathUtils.resolveTemplateSpecificationPath(null, request.executionPath());
            return new ResolvedSpecification(path, defaultPkg.name(), mappedDependent);
        }
        return new ResolvedSpecification(targetDir, defaultPkg.name(), mappedDependent);
    }

    private static String defaultDependentForIgPackage(String packageName, IgRegistryConfig registryConfig,
                                                       String igName, String igVersion) {
        String mapped = igName != null ? registryConfig.resolveDependentPackage(igName, igVersion) : null;
        if (mapped != null) {
            return mapped;
        }
        if (HealthCmdConstants.CMD_DEFAULT_R5_IG_PACKAGE_NAME.equals(packageName)) {
            return HealthCmdConstants.CMD_DEFAULT_R5_DEPENDENT_PACKAGE;
        }
        return HealthCmdConstants.CMD_DEFAULT_R4_DEPENDENT_PACKAGE;
    }

    public static boolean canResolveWithoutLocalSpec(ResolveRequest request) {
        if (request.igName() != null && !request.igName().isEmpty()) {
            return true;
        }
        if (request.specPathArg() != null && !request.specPathArg().trim().isEmpty()) {
            return true;
        }
        return CMD_MODE_TEMPLATE.equals(request.mode()) || CMD_MODE_PACKAGE.equals(request.mode());
    }

    private static void downloadIg(ResolveRequest request, IgRegistryConfig registryConfig,
                                   String packageName, String packageVersion, Path targetDir,
                                   FhirIgPackageDownloader.DownloadOptions downloadOptions)
            throws BallerinaHealthException {
        FhirIgPackageDownloader.ParsedIgSpec spec =
                FhirIgPackageDownloader.resolveIgSpec(packageName, packageVersion, downloadOptions);
        PrintStream out = downloadOptions.printStream() != null ? downloadOptions.printStream() : System.out;
        out.println(HealthCmdConstants.PrintStrings.IG_DOWNLOAD_SUCCESS + spec.source()
                + " into " + targetDir + " ...");
        FhirIgPackageDownloader.downloadAndExtract(targetDir, spec, downloadOptions);
    }

    private static String resolveCacheDir(String override, String executionPath, String configured) {
        if (override != null && !override.isEmpty()) {
            return Paths.get(override).isAbsolute()
                    ? override : Paths.get(executionPath, override).toString();
        }
        return Paths.get(executionPath, configured).toString();
    }

    private static final String CMD_MODE_TEMPLATE = HealthCmdConstants.CMD_MODE_TEMPLATE;
    private static final String CMD_MODE_PACKAGE = HealthCmdConstants.CMD_MODE_PACKAGE;

    public record ResolveRequest(
            String mode,
            String executionPath,
            String specPathArg,
            String igName,
            String igVersion,
            String registryUrl,
            String cacheDir,
            boolean forceDownload,
            boolean nonInteractive,
            String fhirVersion,
            IgRegistryConfig registryConfig,
            PrintStream printStream
    ) {
    }

    public record ResolvedSpecification(Path specificationPath, String igPackageName, String mappedDependentPackage) {
    }
}
