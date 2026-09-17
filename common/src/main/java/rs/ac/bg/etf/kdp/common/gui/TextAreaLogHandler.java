package rs.ac.bg.etf.kdp.common.gui;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import java.util.Objects;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * A {@link Handler} that appends formatted log records to a {@link JTextArea}.
 *
 * <p>
 * Every mutation of the text area happens via {@link SwingUtilities#invokeLater}, since Swing
 * components are not thread-safe and log records arrive from arbitrary threads (connection
 * handlers, writer threads, heartbeat). The line count is capped so a run that logs heavily
 * (e.g. transferring large files) cannot exhaust the heap; the oldest lines are dropped first.
 * </p>
 */
public final class TextAreaLogHandler extends Handler {

	private static final int MAX_LINES = 4000;

	private final JTextArea textArea;

	public TextAreaLogHandler(JTextArea textArea) {
		this.textArea = Objects.requireNonNull(textArea);
		setFormatter(new SimpleFormatter());
	}

	@Override
	public void publish(LogRecord record) {
		if (!isLoggable(record)) return;

		String formatted = getFormatter().format(record);

		SwingUtilities.invokeLater(() -> append(formatted));
	}

	// EDT-confined: only ever called from within invokeLater.
	private void append(String formatted) {
		textArea.append(formatted);
		trimToLineCap();
	}

	// EDT-confined: only ever called from append().
	private void trimToLineCap() {
		Element root = textArea.getDocument().getDefaultRootElement();
		int excessLines = root.getElementCount() - MAX_LINES;

		if (excessLines <= 0) return;

		int removeUpToOffset = root.getElement(excessLines - 1).getEndOffset();

		try {
			textArea.getDocument().remove(0, removeUpToOffset);
		} catch (BadLocationException impossible) {
			// removeUpToOffset is always a valid offset within the document, computed from its
			// own root element a moment earlier.
		}
	}

	@Override
	public void flush() {
		// nothing buffered outside the text area itself
	}

	@Override
	public void close() {
		// no resource owned by this handler beyond the (caller-owned) text area
	}
}
