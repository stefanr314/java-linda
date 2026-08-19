package rs.ac.bg.etf.kdp.common;

import java.io.Serializable;

/**
 * Static description of a workstation, reported at connect time and used
 * by the server's {@code Scheduler} to pick a free workstation.
 *
 * @param hostName            name of the workstation
 * @param osName              the workstation's operating system name
 * @param javaVersion         the workstation's JVM version
 * @param parallelJobCapacity how many jobs this workstation can run at once
 */
public record WorkstationInfo(String hostName, String osName, String javaVersion,
                              int parallelJobCapacity) implements Serializable {
}