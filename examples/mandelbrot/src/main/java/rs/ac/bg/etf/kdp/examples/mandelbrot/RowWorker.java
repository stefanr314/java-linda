package rs.ac.bg.etf.kdp.examples.mandelbrot;

import rs.ac.bg.etf.kdp.common.Linda;
import rs.ac.bg.etf.kdp.lindaclient.LindaFactory;

import java.io.Serial;
import java.io.Serializable;

/**
 * An eval worker: takes rows from the bag until it is empty, then says goodbye.
 *
 * <p>Deliberately holds no reference to a {@link Linda} instance - it obtains its own inside
 * {@link #run()}, in the worker process. A proxy captured on the main side would arrive with its
 * transient socket fields set to null.</p>
 */
public final class RowWorker implements Runnable, Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private final String name;

	public RowWorker(String name) {
		this.name = name;
	}

	@Override
	public void run() {
		Linda linda = LindaFactory.get();

		String[] params = {"params", null, null, null};
		linda.rd(params);  // rd, not in: every worker needs the same parameters
		int width = Integer.parseInt(params[1]);
		int height = Integer.parseInt(params[2]);
		int maxIterations = Integer.parseInt(params[3]);

		// complex plane: real part in [-2.5, 1.0], imaginary part centred at 0 with the same scale
		double scale = 3.5 / width;

		int rowsTaken = 0;
		String[] task = {"row", null};
		// inp is non-blocking: the whole bag was written before any worker started,
		// so "no match" really means "no work left" and the worker can stop
		while (linda.inp(task)) {
			int y = Integer.parseInt(task[1]);
			double ci = (y - height / 2.0) * scale;

			StringBuilder row = new StringBuilder(width * 4);
			for (int x = 0; x < width; x++) {
				double cr = -2.5 + x * scale;
				if (x > 0) row.append(',');
				row.append(iterate(cr, ci, maxIterations));
			}

			linda.out(new String[]{"rowResult", String.valueOf(y), row.toString()});
			rowsTaken++;
			task = new String[]{"row", null};  // fresh template: inp filled the old one in
		}

		linda.out(new String[]{"done", name, String.valueOf(rowsTaken)});
	}

	/** Number of iterations of z = z^2 + c before |z| exceeds 2, capped at maxIterations. */
	private static int iterate(double cr, double ci, int maxIterations) {
		double zr = 0, zi = 0;
		int i = 0;
		while (i < maxIterations && zr * zr + zi * zi <= 4.0) {
			double next = zr * zr - zi * zi + cr;
			zi = 2 * zr * zi + ci;
			zr = next;
			i++;
		}
		return i;
	}
}
