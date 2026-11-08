package dev.sulfurframe.idea;

import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.remote.RemoteConfiguration;
import com.intellij.execution.remote.RemoteConfigurationType;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.function.Consumer;

/**
 * Debugger without a separate run setup: Run client puts a JDWP agent on the game JVM (listening on a free local
 * port, not suspending), and once the game window is up the IDE's own "Remote JVM Debug" configuration
 * ("SulfurFrame: Minecraft") is pointed at that port and started. Breakpoints, stepping and the IDE's own HotSwap
 * work as usual; on JetBrains Runtime the debugger's HotSwap can change methods and fields too.
 */
final class DebugAttach {
    static final String NAME = "SulfurFrame: Minecraft";

    static int freePort() throws IOException {
        try (var s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { return s.getLocalPort(); }
    }

    /** JVM argument for the game. */
    static String jdwpArg(int port) { return "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:" + port; }

    /** Create/update the remote configuration and start debugging (EDT). */
    static void attach(Project project, int port, Consumer<String> log) {
        try {
            var rm = RunManager.getInstance(project);
            var settings = rm.findConfigurationByName(NAME);
            if (settings == null || !(settings.getConfiguration() instanceof RemoteConfiguration)) {
                settings = rm.createConfiguration(NAME, RemoteConfigurationType.getInstance().getConfigurationFactories()[0]);
                settings.setTemporary(true);
                rm.addConfiguration(settings);
            }
            var rc = (RemoteConfiguration) settings.getConfiguration();
            rc.HOST = "127.0.0.1";
            rc.PORT = Integer.toString(port);
            rc.SERVER_MODE = false;
            rc.USE_SOCKET_TRANSPORT = true;
            ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance());
            log.accept("debugger attached to the game (127.0.0.1:" + port + ")");
        } catch (Throwable t) {
            log.accept("debugger attach failed: " + t);
        }
    }

    private DebugAttach() {}
}
