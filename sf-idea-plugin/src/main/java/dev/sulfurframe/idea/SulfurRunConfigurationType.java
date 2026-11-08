package dev.sulfurframe.idea;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeBase;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;

/**
 * Run configuration type "Minecraft (SulfurFrame)": one configuration per runnable client of the project (created
 * by the scan), so the IDE's Run / Debug / Stop / Rerun buttons, the run configuration list and Shift+F10 work.
 */
public final class SulfurRunConfigurationType extends ConfigurationTypeBase {
    static final String ID = "SulfurFrameMinecraft";

    public SulfurRunConfigurationType() {
        super(ID, "Minecraft (SulfurFrame)", "A Minecraft mod's dev client, embedded in the IDE", AllIcons.Nodes.Plugin);
        addFactory(new Factory(this));
    }

    static SulfurRunConfigurationType get() { return ConfigurationTypeUtil.findConfigurationType(SulfurRunConfigurationType.class); }

    ConfigurationFactory factory() { return getConfigurationFactories()[0]; }

    static final class Factory extends ConfigurationFactory {
        Factory(SulfurRunConfigurationType type) { super(type); }
        @Override public String getId() { return "SulfurFrame Minecraft"; }
        @Override public RunConfiguration createTemplateConfiguration(Project project) { return new SulfurRunConfiguration(project, this, "Minecraft"); }
    }
}
