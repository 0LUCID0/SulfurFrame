package dev.sulfurframe.idea;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.runners.RunContentBuilder;
import com.intellij.execution.ui.RunContentDescriptor;

/** Runs SulfurFrame configurations with the Run and the Debug executor (Debug = debug port + debugger attach). */
public final class SulfurProgramRunner extends GenericProgramRunner<RunnerSettings> {
    @Override public String getRunnerId() { return "SulfurFrameRunner"; }

    @Override public boolean canRun(String executorId, RunProfile profile) {
        return profile instanceof SulfurRunConfiguration
                && (DefaultRunExecutor.EXECUTOR_ID.equals(executorId) || DefaultDebugExecutor.EXECUTOR_ID.equals(executorId));
    }

    @Override protected RunContentDescriptor doExecute(RunProfileState state, ExecutionEnvironment env) throws ExecutionException {
        var result = state.execute(env.getExecutor(), this);
        return result == null ? null : new RunContentBuilder(result, env).showRunContent(env.getContentToReuse());
    }
}
