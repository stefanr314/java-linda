package rs.ac.bg.etf.kdp.server;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry holder class for active workstations. Approached by multiple threads so special care is required when
 * trying to accomplish the compound atomic action on map; otherwise check-than-act issues might arise.
 */
public final class WorkstationRegistry {

	private final Map<String, WorkstationContext> workstations = new ConcurrentHashMap<>();

	public void register(WorkstationContext context) {
		workstations.put(context.workstationInfo().hostName(), context);
	}

	public boolean unregister(WorkstationContext context) {
		return workstations.remove(context.workstationInfo().hostName(), context);
	}

	public Collection<WorkstationContext> workstations() {
		return List.copyOf(workstations.values());
	}

	public Optional<WorkstationContext> find(String hostname) {
		return Optional.ofNullable(workstations.get(hostname));
	}

	// NOTE: this method is more of an estimation rather than the exact size value; check concurrent map docs. Do not
	// rely on it.
	public int size() {
		return workstations.size();
	}
}