/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.gradle.api.artifacts.ResolutionStrategy;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphEdge;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphNode;
import org.gradle.internal.component.local.model.LocalConfigurationMetadata;
import org.gradle.internal.component.local.model.LocalFileDependencyMetadata;
import org.gradle.internal.component.model.ConfigurationMetadata;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.collect.Maps.newLinkedHashMap;

/**
 * Collects all artifacts and their build dependencies.
 */
public class DefaultResolvedArtifactsBuilder implements DependencyArtifactsVisitor {
    private final Map<Long, Set<ArtifactSet>> sortedNodeIds = newLinkedHashMap();
    private final boolean buildProjectDependencies;
    private final Map<Long, ArtifactSet> artifactSetsById = newLinkedHashMap();
    private final Set<Long> buildableArtifactSets = new HashSet<Long>();
    private final ResolutionStrategy.SortOrder sortOrder;

    public DefaultResolvedArtifactsBuilder(boolean buildProjectDependencies, ResolutionStrategy.SortOrder sortOrder) {
        this.buildProjectDependencies = buildProjectDependencies;
        this.sortOrder = sortOrder;
    }

    @Override
    public void startArtifacts(DependencyGraphNode root) {
        if (sortOrder == ResolutionStrategy.SortOrder.DEFAULT) {
            return;
        }
        List<DependencyGraphNode> sortedNodeList = getSortedNodeList(root);
        for (DependencyGraphNode node : sortedNodeList) {
            sortedNodeIds.put(node.getNodeId(), Sets.<ArtifactSet>newLinkedHashSet());
        }
    }

    private List<DependencyGraphNode> getSortedNodeList(DependencyGraphNode root) {
        Set<DependencyGraphNode> tempMarked = Sets.newHashSet();
        List<DependencyGraphNode> marked = Lists.newArrayList();
        topologicalSort(root, tempMarked, marked);
        return sortOrder == ResolutionStrategy.SortOrder.CONSUMER_FIRST ? Lists.reverse(marked) : marked;
    }

    /*
     * Recursively sort the configuration nodes of the resolution graph.
     * Note that this should really be sorting _component nodes_, not configuration nodes.
     */
    private void topologicalSort(DependencyGraphNode node, Set<DependencyGraphNode> tempMarked, List<DependencyGraphNode> marked) {
        if (tempMarked.contains(node)) {
            return;
        }
        if (!marked.contains(node)) {
            tempMarked.add(node);

            List<DependencyGraphEdge> edges = Lists.newArrayList(node.getOutgoingEdges());
            for (DependencyGraphEdge dependencyEdge : Lists.reverse(edges)) {
                for (DependencyGraphNode targetConfiguration : dependencyEdge.getTargets()) {
                    topologicalSort(targetConfiguration, tempMarked, marked);
                }
            }
            marked.add(node);
            tempMarked.remove(node);
        }
    }

    @Override
    public void visitArtifacts(DependencyGraphNode from, LocalFileDependencyMetadata fileDependency, ArtifactSet artifacts) {
        collectArtifactsFor(from, artifacts);
        artifactSetsById.put(artifacts.getId(), artifacts);
        buildableArtifactSets.add(artifacts.getId());
    }

    @Override
    public void visitArtifacts(DependencyGraphNode from, DependencyGraphNode to, ArtifactSet artifacts) {
        collectArtifactsFor(to, artifacts);
        artifactSetsById.put(artifacts.getId(), artifacts);

        // Don't collect build dependencies if not required
        if (!buildProjectDependencies) {
            return;
        }
        if (buildableArtifactSets.contains(artifacts.getId())) {
            return;
        }

        // Collect the build dependencies in 2 steps: collect the artifact sets while traversing and at the end of traversal unpack the build dependencies for each
        // We need to discard the artifact sets to avoid keeping strong references

        ConfigurationMetadata configurationMetadata = to.getMetadata();
        if (!(configurationMetadata instanceof LocalConfigurationMetadata)) {
            return;
        }

        if (from.getOwner().getComponentId() instanceof ProjectComponentIdentifier) {
            // This is here to attempt to leave out build dependencies that would cause a cycle in the task graph for the current build, so that the cross-build cycle detection kicks in. It's not fully correct
            ProjectComponentIdentifier incomingId = (ProjectComponentIdentifier) from.getOwner().getComponentId();
            if (!incomingId.getBuild().isCurrentBuild()) {
                return;
            }
        }

        buildableArtifactSets.add(artifacts.getId());
    }

    private void collectArtifactsFor(DependencyGraphNode node, ArtifactSet artifacts) {
        if (sortOrder != ResolutionStrategy.SortOrder.DEFAULT) {
            sortedNodeIds.get(node.getNodeId()).add(artifacts);
        } else {
            Set<ArtifactSet> artifactSets = sortedNodeIds.get(node.getNodeId());
            if (artifactSets == null) {
                artifactSets = Sets.newLinkedHashSet();
                sortedNodeIds.put(node.getNodeId(), artifactSets);
            }
            artifactSets.add(artifacts);
        }
    }

    @Override
    public void finishArtifacts() {
    }

    public VisitedArtifactsResults complete() {
        Map<Long, ArtifactSet> artifactsById = newLinkedHashMap();
        for (Map.Entry<Long, ArtifactSet> entry : artifactSetsById.entrySet()) {
            artifactsById.put(entry.getKey(), entry.getValue().snapshot());
        }

        return new DefaultVisitedArtifactResults(sortedNodeIds, artifactsById, buildableArtifactSets);
    }
}
