package rs.ac.bg.etf.kdp.lindaclient;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;

/**
 * Helper bootstrap class which serves as an entry point to the worker serialized thread meant for running the code
 * provided as argument to EVAL command of Java distributed linda. Since communication between two distinct processes
 * is required a temporal file is created by process creator and serves as input source for bootstrap process.
 *
 * @author stefanr
 */
public class EvalBootstrap {
	public static void main(String[] args) throws IOException {
		if (args == null || args[0] == null) {
			throw new IllegalArgumentException();
		}

		try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(args[0]));) {
			((Runnable) in.readObject()).run();
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	}
}