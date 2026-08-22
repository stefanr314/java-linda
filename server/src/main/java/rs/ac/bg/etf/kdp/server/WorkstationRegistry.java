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

	/**
	 * Registers a workstation as available for scheduling.
	 *
	 * @param context required info about the workstation.
	 */
	public void register(WorkstationContext context) {
		workstations.put(context.workstationInfo().hostName(), context);
	}

	/**
	 * Removes a workstation from consideration, e.g. after a missed
	 * heartbeat.
	 * <p>
	 * It is required that remove is performed with the context in mind, since otherwise a slow handler response will
	 * remove fresh connection (e.g. upon workstation losing connection and then returning back).
	 * </p>
	 *
	 * @param context required info about the workstation.
	 */

	public boolean unregister(WorkstationContext context) {
		return workstations.remove(context.workstationInfo().hostName(), context);
	}

	/**
	 * Method for trying to gain free stations (i.e. slot of station). Method does not enforce blocking of any
	 * nature, just returns false if there are not any free stations (in the moment of checking). Otherwise, a
	 * context of free station is returned.
	 * <p>
	 * Iteration on concurrent hashmap if thread safe since no exception will be thrown, and even the concurrent
	 * modifications might be present in the moment of iterating (by specification this is not guaranteed).
	 * However, iterating on this map does not guarantee that no stale values will be present, which in turn
	 * returns the slot for unreachable i.e. stale station. This is expected behaviour. Responsibility is
	 * delegated to the thread that sends the request to station ( handler of client requests per se).
	 * </p>
	 *
	 * @return context of available workstation, or empty value holder.
	 */
	public Optional<WorkstationContext> tryFindFreeStation() {
		for (WorkstationContext workstation : workstations.values()) {
			if (workstation.tryAcquireSlot())
				return Optional.of(workstation);
		}
		return Optional.empty(); //todo
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