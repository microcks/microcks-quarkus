package io.github.microcks.quarkus.deployment;

import io.quarkus.builder.item.SimpleBuildItem;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public final class ScanResultsBuildItem extends SimpleBuildItem {

    private final Map<File, String> primaryArtifacts;
    private final Map<File, String> secondaryArtifacts;
    private final boolean aPostmanCollectionIsPresent;

    public ScanResultsBuildItem(Map<File, String> primaryArtifacts, Map<File, String> secondaryArtifacts, boolean aPostmanCollectionIsPresent) {
        this.primaryArtifacts = primaryArtifacts != null ? primaryArtifacts : new HashMap<>();
        this.secondaryArtifacts = secondaryArtifacts != null ? secondaryArtifacts : new HashMap<>();
        this.aPostmanCollectionIsPresent = aPostmanCollectionIsPresent;
    }

    public ScanResultsBuildItem() {
        primaryArtifacts = new HashMap<>();
        secondaryArtifacts = new HashMap<>();
        aPostmanCollectionIsPresent = false;
    }

    public boolean aPostmanCollectionIsPresent() {
        return aPostmanCollectionIsPresent;
    }

    public Map<File, String> primary() {
        return primaryArtifacts;
    }

    public Map<File, String> secondary() {
        return secondaryArtifacts;
    }
}
