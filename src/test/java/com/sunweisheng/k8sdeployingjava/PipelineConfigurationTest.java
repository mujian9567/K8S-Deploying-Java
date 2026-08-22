package com.sunweisheng.k8sdeployingjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineConfigurationTest {

    private final JsonNode configuration = readConfiguration();

    @Test
    void restrictsImagePublishingAndDeploymentToConfiguredBranch() {
        String deployBranch = configuration.path("variables").path("DEPLOY_BRANCH").asText();
        assertFalse(deployBranch.isBlank());
        assertBranchCondition(stage("image"), deployBranch);
        assertBranchCondition(stage("deploy"), deployBranch);
    }

    @Test
    void allowsSlowRegistryTransfers() {
        assertEquals(60, stage("image").path("timeoutMinutes").asInt());
    }

    @Test
    void deploysOnlyToThePrecreatedNamespace() {
        JsonNode steps = stage("deploy").path("steps");
        JsonNode preparation = steps.get(0);
        assertEquals("command", preparation.path("type").asText());
        assertEquals(
                "${HELM_OVERRIDE_PREPARE_SCRIPT} \"${HELM_OVERRIDE_SOURCE_FILE}\" \"${HELM_OVERRIDE_VALUES_FILE}\"",
                preparation.path("script").asText()
        );

        List<String> actions = new ArrayList<>();
        steps.forEach(step -> {
            if ("helm".equals(step.path("type").asText())) {
                actions.add(step.path("action").asText());
            }
        });
        assertEquals(List.of("lint", "template", "upgrade", "status"), actions);

        assertImageCoordinates(findStep(steps, "lint"));
        assertOptionalValuesFile(findStep(steps, "lint"));
        assertImageCoordinates(findStep(steps, "template"));
        assertOptionalValuesFile(findStep(steps, "template"));
        JsonNode upgrade = findStep(steps, "upgrade");
        assertTrue(upgrade.has("createNamespace"));
        assertFalse(upgrade.path("createNamespace").asBoolean());
        assertTrue(upgrade.path("rollbackOnFailure").asBoolean());
        assertImageCoordinates(upgrade);
        assertOptionalValuesFile(upgrade);

        JsonNode status = findStep(steps, "status");
        assertFalse(status.has("valuesFiles"));
    }

    @Test
    void keepsEnvironmentSpecificNamesInProjectConfiguration() {
        JsonNode variables = configuration.path("variables");
        assertEquals("helm-overrides", variables.path("HELM_OVERRIDE_VOLUME_NAME").asText());
        assertEquals("deploy-overrides", variables.path("HELM_OVERRIDE_CONFIG_MAP").asText());
        assertEquals("/etc/helm/deploy-overrides", variables.path("HELM_OVERRIDE_MOUNT_PATH").asText());
        assertEquals("${HELM_OVERRIDE_MOUNT_PATH}/values.yaml", variables.path("HELM_OVERRIDE_SOURCE_FILE").asText());
        assertEquals(
                ".jenkins-json-build/deploy-overrides-values.yaml",
                variables.path("HELM_OVERRIDE_VALUES_FILE").asText()
        );
        assertEquals("ci/prepare-helm-values.sh", variables.path("HELM_OVERRIDE_PREPARE_SCRIPT").asText());
    }

    private void assertBranchCondition(JsonNode stage, String deployBranch) {
        JsonNode condition = stage.path("condition");
        assertEquals("BRANCH_NAME", condition.path("variable").asText());
        assertEquals("equals", condition.path("operator").asText());
        assertEquals(deployBranch, condition.path("value").asText());
    }

    private JsonNode stage(String id) {
        for (JsonNode stage : configuration.path("stages")) {
            if (id.equals(stage.path("id").asText())) {
                return stage;
            }
        }
        throw new IllegalStateException("Missing pipeline stage: " + id);
    }

    private JsonNode findStep(JsonNode steps, String action) {
        for (JsonNode step : steps) {
            if (action.equals(step.path("action").asText())) {
                return step;
            }
        }
        throw new IllegalStateException("Missing Helm action: " + action);
    }

    private void assertImageCoordinates(JsonNode step) {
        assertEquals("${IMAGE_REPOSITORY}", step.path("setValues").path("image.repository").asText());
        assertEquals("${IMAGE_DIGEST}", step.path("setValues").path("image.digest").asText());
    }

    private void assertOptionalValuesFile(JsonNode step) {
        assertEquals(List.of("${HELM_OVERRIDE_VALUES_FILE}"), toStrings(step.path("valuesFiles")));
        assertFalse(step.path("setValues").has("ingress.host"));
        assertFalse(step.path("setValues").has("ingress.tlsSecret"));
    }

    private List<String> toStrings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private JsonNode readConfiguration() {
        Path path = Path.of("ci", "jenkins-project.json");
        try {
            return new ObjectMapper().readTree(Files.readString(path));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read pipeline configuration: " + path, exception);
        }
    }
}
