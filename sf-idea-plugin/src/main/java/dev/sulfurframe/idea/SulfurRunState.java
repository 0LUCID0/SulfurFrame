package dev.sulfurframe.idea;

import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.search.GlobalSearchScope;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Starting a SulfurFrame run configuration: the panel launches the client as before (Gradle, agent, embedding);
 * the Run window shows Gradle's (and the game's) output with source links, and Stop saves and quits the game.
 * Debug starts it with a debug port and attaches the debugger once the window is up.
 */
final class SulfurRunState implements RunProfileState {
    /** Game arguments of a launch the panel asked for (restart into a world, test world). */
    static final Key<List<String>> GAME_ARGS = Key.create("sulfurframe.gameArgs");

    private final SulfurRunConfiguration config;
    private final ExecutionEnvironment env;

    SulfurRunState(SulfurRunConfiguration config, ExecutionEnvironment env) { this.config = config; this.env = env; }

    @Override public ExecutionResult execute(Executor executor, ProgramRunner<?> runner) throws ExecutionException {
        var project = config.getProject();
        var panel = PanelHost.obtain(project).panel;
        var t = panel.targetByKey(config.target);
        if (t == null) throw new ExecutionException("The client of '" + config.getName() + "' was not found in the project (rescan, or edit the configuration)");
        List<String> args = env.getUserData(GAME_ARGS);
        if (args == null) {
            args = new ArrayList<>();
            // Rerun reuses the previous run's tab: go back into the world that game was in
            String world = env.getContentToReuse() != null ? panel.lastQuitWorld(t) : "";
            if (world.isEmpty()) world = config.world;
            if (!world.isEmpty() && SulfurPanel.quickPlay(t.profile().minecraftVersion())) args = List.of("--quickPlaySingleplayer", world);
        }
        boolean debug = DefaultDebugExecutor.EXECUTOR_ID.equals(executor.getId());
        var handler = new Session(panel);
        var builder = TextConsoleBuilderFactory.getInstance().createBuilder(project);
        builder.filters(com.intellij.execution.filters.ExceptionFilters.getFilters(GlobalSearchScope.allScope(project)));
        var console = builder.getConsole();
        console.attachToProcess(handler);
        // the game is embedded into the panel's canvas, which needs to be on screen
        var host = PanelHost.of(project);
        var tw = ToolWindowManager.getInstance(project).getToolWindow(SfActions.TOOL_WINDOW);
        if (tw != null && (host == null || !host.inEditor())) tw.show();
        if (!panel.launchSession(t, args, debug, handler)) throw new ExecutionException("A game started by SulfurFrame is already running or starting");
        return new DefaultExecutionResult(console, handler);
    }

    /** The Run window's view of one launch: Gradle's output in, Stop = save and quit. */
    static final class Session extends ProcessHandler {
        private final SulfurPanel panel;
        Session(SulfurPanel panel) { this.panel = panel; }

        void line(String s) { notifyTextAvailable(s + "\n", ProcessOutputTypes.STDOUT); }
        void ended(int code) { if (!isProcessTerminated()) notifyProcessTerminated(code); }

        @Override protected void destroyProcessImpl() { panel.stopSession(this); }
        @Override protected void detachProcessImpl() { notifyProcessDetached(); }
        @Override public boolean detachIsDefault() { return false; }
        @Override public OutputStream getProcessInput() { return null; }
    }
}
