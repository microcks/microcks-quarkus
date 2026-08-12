package io.github.microcks.quarkus.deployment;

import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class ArtifactScanner {

    /**
     * List of extensions for detecting artifacts to import as primary ones.
     */
    private static final List<String> PRIMARY_ARTIFACTS_EXTENSIONS = Arrays.asList("-openapi.yml", "-openapi.yaml", "-openapi.json",
            ".proto", ".graphql", "-asyncapi.yml", "-asyncapi.yaml", "-asyncapi.json", "-soapui-project.xml");
    /**
     * List of extensions for detecting artifacts to import as secondary ones.
     */
    private static final List<String> SECONDARY_ARTIFACTS_EXTENSIONS = Arrays.asList("postman-collection.json", "postman_collection.json",
            "-metadata.yml", "-metadata.yaml", "-examples.yml", "-examples.yaml", ".har");
    /**
     * List of extensions corresponding to Postman collection artifacts.
     */
    private static final List<String> POSTMAN_COLLECTION_EXTENSIONS = Arrays.asList("postman-collection.json", "postman_collection.json");


    private Map<File, String> primaryArtifacts;
    private Map<File, String> secondaryArtifacts;
    private boolean aPostmanCollectionIsPresent = false;

    public ArtifactScanner(CurateOutcomeBuildItem outcomeBuildItem) throws IOException {
        primaryArtifacts = scanPrimaryArtifacts(outcomeBuildItem);
        // Continue with secondary artifacts only if we found something.
        if (primaryArtifacts != null && !primaryArtifacts.isEmpty()) {
            secondaryArtifacts = scanSecondaryArtifacts(outcomeBuildItem);
        }
    }

    public ScanResultsBuildItem toBuildItem() {
        return new ScanResultsBuildItem(primaryArtifacts, secondaryArtifacts, aPostmanCollectionIsPresent);
    }

    private Map<File, String> scanPrimaryArtifacts(CurateOutcomeBuildItem outcomeBuildItem) throws IOException {
        return scanArtifacts(outcomeBuildItem, PRIMARY_ARTIFACTS_EXTENSIONS);
    }

    private Map<File, String> scanSecondaryArtifacts(CurateOutcomeBuildItem outcomeBuildItem) throws IOException {
        return scanArtifacts(outcomeBuildItem, SECONDARY_ARTIFACTS_EXTENSIONS);
    }

    private Map<File, String> scanArtifacts(CurateOutcomeBuildItem outcomeBuildItem,
                                            List<String> validSuffixes) throws IOException {
        List<SourceDir> resourceDirs = new ArrayList<>();
        resourceDirs.addAll(outcomeBuildItem.getApplicationModel().getApplicationModule().getMainSources().getResourceDirs());
        resourceDirs.addAll(outcomeBuildItem.getApplicationModel().getApplicationModule().getTestSources().getResourceDirs());

        Map<File, String> filesAndRelativePath = new HashMap<>();
        // Extract all the files and their relative path from resource dir.
        // This path is the one that will be used for hot reloading so we should compute it now.
        for (SourceDir resourceDir : resourceDirs) {
            filesAndRelativePath.putAll(collectFilesAndRelativePaths(resourceDir.getDir(), validSuffixes));
        }

        return filesAndRelativePath;
    }

    private Map<File, String> collectFilesAndRelativePaths(Path dir, List<String> validSuffixes) throws IOException {
        Map<File, String> filesPaths = new HashMap<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.walk(dir, 2)) {
                stream.filter(Files::isRegularFile)
                        .map(Path::toFile)
                        .filter(candidate -> endsWithOneOf(candidate.getName(), validSuffixes))
                        .forEach(file -> filesPaths.put(file, dir.relativize(file.toPath()).toString()));
            }
        }
        return filesPaths;
    }

    private boolean endsWithOneOf(String candidate, List<String> validSuffixes) {
        for (String validSuffix : validSuffixes) {
            if (candidate.endsWith(validSuffix)) {
                if (isAPostmanCollection(candidate)) {
                    aPostmanCollectionIsPresent = true;
                }
                return true;
            }
        }
        return false;
    }

    private static boolean isAPostmanCollection(String candidate) {
        for (String postmanSuffix : POSTMAN_COLLECTION_EXTENSIONS) {
            if (candidate.endsWith(postmanSuffix)) {
                return true;
            }
        }
        return false;
    }
}
