package rs.ac.bg.etf.kdp.examples.mandelbrot;

import rs.ac.bg.etf.kdp.common.Linda;
import rs.ac.bg.etf.kdp.lindaclient.LindaFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Renders the Mandelbrot set as a bag of tasks: one task per image row.
 *
 * <p>Command: {@code java -jar mandelbrot.jar [width] [height] [maxIterations] [workers]}.
 * Output files: {@code mandelbrot.png} (the image) and {@code rows-per-worker.txt} (how many
 * rows each worker took - shows that load balancing is dynamic, not a fixed split).</p>
 *
 * <p>Tuples used:
 * <pre>
 *   ("params", width, height, maxIterations)   written once, read by every worker (rd)
 *   ("row", y)                                  one task per row, taken by workers (inp)
 *   ("rowResult", y, "i0,i1,...,iW-1")         iteration counts for row y
 *   ("done", workerName, rowsTaken)             worker's farewell - main waits for all of them
 * </pre>
 */
public final class MandelbrotMain {

	public static void main(String[] args) throws IOException {
		int width = args.length > 0 ? Integer.parseInt(args[0]) : 800;
		int height = args.length > 1 ? Integer.parseInt(args[1]) : 600;
		int maxIterations = args.length > 2 ? Integer.parseInt(args[2]) : 500;
		int workers = args.length > 3 ? Integer.parseInt(args[3]) : 4;

		Linda linda = LindaFactory.get();

		// 1) parameters + the whole bag of tasks go in BEFORE any worker starts
		linda.out(new String[]{"params", String.valueOf(width), String.valueOf(height), String.valueOf(maxIterations)});
		for (int y = 0; y < height; y++) {
			linda.out(new String[]{"row", String.valueOf(y)});
		}

		// 2) start the workers - each is a separate process on some workstation
		for (int w = 0; w < workers; w++) {
			linda.eval("mandelbrot-worker-" + w, new RowWorker("worker-" + w));
		}

		// 3) collect every row; the template's null fields are filled in from the matched tuple
		int[][] iterations = new int[height][];
		for (int collected = 0; collected < height; collected++) {
			String[] result = {"rowResult", null, null};
			linda.in(result);
			iterations[Integer.parseInt(result[1])] =
					Arrays.stream(result[2].split(",")).mapToInt(Integer::parseInt).toArray();
		}

		// 4) main must be the LAST one out: when it exits the job becomes DONE and the server
		//    closes the tuple space - a worker still talking to it would fail with exit code 1
		StringBuilder report = new StringBuilder();
		for (int w = 0; w < workers; w++) {
			String[] done = {"done", null, null};
			linda.in(done);
			report.append(done[1]).append(": ").append(done[2]).append(" rows").append(System.lineSeparator());
		}

		ImageIO.write(render(iterations, width, height, maxIterations), "png", Path.of("mandelbrot.png").toFile());
		Files.writeString(Path.of("rows-per-worker.txt"), report.toString());
	}

	private static BufferedImage render(int[][] iterations, int width, int height, int maxIterations) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int it = iterations[y][x];
				if (it >= maxIterations) {
					image.setRGB(x, y, 0);  // inside the set: black
				} else {
					float t = (float) Math.sqrt((double) it / maxIterations);
					image.setRGB(x, y, Color.HSBtoRGB(0.62f - 0.55f * t, 0.85f, 0.2f + 0.8f * t));
				}
			}
		}
		return image;
	}
}
