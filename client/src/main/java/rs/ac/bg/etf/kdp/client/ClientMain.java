package rs.ac.bg.etf.kdp.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Command-line entry point for end users to submit jobs and check on them
 * via {@link JobClient}.
 */
public final class ClientMain {

	private ClientMain() {
	}

	public static void main(String[] args) {
		if (args == null || args[0] == null || args[1] == null) throw new IllegalArgumentException();

		String serverHostName = args[0];
		int serverPort = Integer.parseInt(args[1]);

		try (
				Socket socket = new Socket(serverHostName, serverPort);
				PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
				BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
				BufferedReader stdIn = new BufferedReader(new InputStreamReader(System.in));
		) {
			String userInput;
			while ((userInput = stdIn.readLine()) != null) {
				out.println(userInput);
				System.out.println("echo >>> " + in.readLine());
			}
		} catch (IOException e) {
			e.printStackTrace(System.out);
		}

	}
}