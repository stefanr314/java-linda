package rs.ac.bg.etf.kdp.workstation;

import java.util.Arrays;
import java.util.List;

/**
 * Headless entry point for a workstation node: connects to the server,
 * advertises its {@link rs.ac.bg.etf.kdp.common.WorkstationInfo}, and
 * waits for jobs and {@code eval()} work to run.
 *
 * <p>Pass {@code --headless} to run without a UI; see {@link
 * WorkstationGui} for the (placeholder) graphical entry point.
 */
public final class WorkstationMain {

    private WorkstationMain() {
    }

    public static void main(String[] args) {
        List<String> arguments = Arrays.asList(args);
        boolean headless = arguments.contains("--headless");
        if (!headless) {
            WorkstationGui.main(args);
            return;
        }
        throw new UnsupportedOperationException("not yet implemented");
    }
}
