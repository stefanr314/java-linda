package rs.ac.bg.etf.kdp.examples.hello;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class HelloMain {

	public static void main(String[] args) throws IOException {
		Path outFile = Path.of("out.txt");
		System.setOut(new PrintStream(Files.newOutputStream(outFile, StandardOpenOption.CREATE,
				StandardOpenOption.APPEND)));


		System.out.println("Hello world from client");
	}
}