package rs.ac.bg.etf.kdp.client;

import rs.ac.bg.etf.kdp.common.GUIFormBuilder;
import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;
import rs.ac.bg.etf.kdp.common.gui.TextAreaLogHandler;
import rs.ac.bg.etf.kdp.common.protocol.JobStatusResponse;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.logging.Logger;

/**
 * Graphical entry point for the client, exposing everything {@link ClientMain}'s REPL does.
 * {@link ClientMain} never references this class, so no Swing class is ever loaded on the REPL
 * path.
 *
 * <p>
 * All component mutation happens on the Event Dispatch Thread, via {@link
 * SwingUtilities#invokeLater}. Every {@link JobClient} call (connect, submit, status, fetch,
 * abort, reschedule, disconnect) runs on a background thread, marshaling its result back; the
 * action buttons are disabled for the duration and re-enabled once the result (or failure)
 * arrives, which also keeps at most one {@link JobClient} operation in flight at a time — the
 * protocol is lock-step over a single connection and a second concurrent call would read someone
 * else's reply.
 * </p>
 */
public final class ClientGui {

	private static final Logger LOGGER = Logger.getLogger(ClientGui.class.getName());

	private static final DateTimeFormatter TIME_FORMAT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

	private final CardLayout cards = new CardLayout();
	private final JPanel cardPanel = new JPanel(cards) {
		@Override
		public Dimension getPreferredSize() {
			// CardLayout reports the largest card, which would make the small config screen as big
			// as the running screen. Report the visible one instead.
			for (Component child : getComponents()) {
				if (child.isVisible()) return child.getPreferredSize();
			}
			return super.getPreferredSize();
		}
	};

	private final JTextField hostField = new JTextField("localhost", 14);
	private final JTextField portField = new JTextField("4040", 6);
	private final JTextField userField = new JTextField("gui-user", 12);
	private final JTextField historyField = new JTextField("job-history.log", 20);
	private final JButton connectButton = new JButton("Connect");

	private final JLabel connectionLabel = new JLabel();
	private final JobTableModel tableModel = new JobTableModel();
	private final JTable jobTable = new JTable(tableModel);
	private final JButton refreshButton = new JButton("Refresh");
	private final JButton submitButton = new JButton("Submit...");
	private final JButton statusButton = new JButton("Status");
	private final JButton fetchButton = new JButton("Fetch results...");
	private final JButton abortButton = new JButton("Abort");
	private final JButton rescheduleButton = new JButton("Reschedule");
	private final JButton disconnectButton = new JButton("Disconnect");
	private final JTextArea logArea = new JTextArea();

	// EDT-confined: written from a Submit's success callback and a Status success callback, both
	// invokeLater-marshaled; read only from action listeners on the EDT.
	private final Map<String, List<String>> expectedOutputsByJobId = new HashMap<>();
	private final Map<String, String> pendingDecisionByJobId = new HashMap<>();
	private JFrame frame;

	// EDT-confined: assigned only from onConnected()/onDisconnected(), both of which run via
	// invokeLater; read only from action listeners, which already run on the EDT.
	private JobClient client;

	// EDT-confined: same discipline as client above.
	private boolean operationRunning = false;
	private JobId selectedJobId;

	private ClientGui() {
	}

	public static void main(String[] args) {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
			Font base = new Font("SansSerif", Font.PLAIN, 14);
			for (Object key : UIManager.getLookAndFeelDefaults().keySet()) {
				if (key.toString().endsWith(".font")) UIManager.put(key, base);
			}
		} catch (Exception ignored) {
		}
		SwingUtilities.invokeLater(() -> new ClientGui().show());
	}

	private static List<String> splitFileList(String field) {
		if (field.isBlank()) return List.of();
		return Arrays.stream(field.split(","))
				.map(String::trim)
				.filter(name -> !name.isEmpty())
				.toList();
	}

	private static JPanel labeled(String title, Component content) {
		JPanel panel = new JPanel(new BorderLayout());
		panel.add(new JLabel(title), BorderLayout.NORTH);
		panel.add(content, BorderLayout.CENTER);
		return panel;
	}

	private void show() {
		Logger.getLogger("").addHandler(new TextAreaLogHandler(logArea));

		frame = new JFrame("java-linda client");
		frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				closeAndDispose();
			}
		});

		cardPanel.add(buildConfigPanel(), "config");
		cardPanel.add(buildRunningPanel(), "running");
		showCard("config");

		frame.getContentPane().add(cardPanel);
		frame.pack();
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);
	}

	private JPanel buildConfigPanel() {

		JPanel titlePane = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));

		JLabel title = new JLabel("Connect to your server");
		Font titleFont = title.getFont();
		Font bolded = titleFont.deriveFont(Font.BOLD, 18f);
		title.setFont(bolded);
		title.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));

		titlePane.add(title);

		JPanel form = new GUIFormBuilder()
				.addRow("Server host:", hostField)
				.addRow("Server port:", portField)
				.addRow("User name:", userField)
				.addRow("Job history file:", historyField)
				.build();

		connectButton.addActionListener(e -> connect());

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
		buttonPanel.setBorder(new EmptyBorder(0, 20, 0, 20));

		buttonPanel.add(connectButton);

		JPanel panel = new JPanel(new BorderLayout());
		panel.add(titlePane, BorderLayout.NORTH);
		panel.add(form, BorderLayout.CENTER);
		panel.add(buttonPanel, BorderLayout.SOUTH);
		return panel;
	}

	private JPanel buildRunningPanel() {
		JPanel top = new JPanel(new BorderLayout());
		top.add(connectionLabel, BorderLayout.WEST);
		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		right.add(disconnectButton);
		top.add(right, BorderLayout.EAST);

		jobTable.getSelectionModel().addListSelectionListener(e -> onSelectionChanged());
		jobTable.setPreferredScrollableViewportSize(new Dimension(700, 200));
		JScrollPane tableScroll = new JScrollPane(jobTable);

		refreshButton.addActionListener(e -> refreshTable());
		submitButton.addActionListener(e -> submit());
		statusButton.addActionListener(e -> status());
		fetchButton.addActionListener(e -> fetch());
		abortButton.addActionListener(e -> abort());
		rescheduleButton.addActionListener(e -> reschedule());
		disconnectButton.addActionListener(e -> disconnect());

		JPanel buttons = new JPanel(new GridLayout(2, 3, 6, 6));
		buttons.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		buttons.add(refreshButton);
		buttons.add(submitButton);
		buttons.add(statusButton);
		buttons.add(fetchButton);
		buttons.add(abortButton);
		buttons.add(rescheduleButton);

		JPanel jobsPanel = new JPanel(new BorderLayout());
		jobsPanel.add(tableScroll, BorderLayout.CENTER);
		jobsPanel.add(buttons, BorderLayout.SOUTH);

		logArea.setEditable(false);
		logArea.setRows(12);
		logArea.setColumns(80);
		logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

		JSplitPane center = new JSplitPane(
				JSplitPane.VERTICAL_SPLIT,
				labeled("Known jobs", jobsPanel),
				labeled("Log", new JScrollPane(logArea))
		);
		center.setResizeWeight(0.6);

		JPanel panel = new JPanel(new BorderLayout());
		panel.setBorder(BorderFactory.createEmptyBorder(12, 10, 12, 10));
//		panel.setPreferredSize(new Dimension(750, 550));
		panel.add(top, BorderLayout.NORTH);
		panel.add(center, BorderLayout.CENTER);
		return panel;
	}

	private void showCard(String name) {
		cards.show(cardPanel, name);
		frame.pack();                        // recompute from the visible card only
		frame.setLocationRelativeTo(null);   // keep it centred after the resize
	}

	// EDT-confined: only called from the selection listener, which already runs on the EDT.
	private void onSelectionChanged() {
		int row = jobTable.getSelectedRow();
		if (row < 0) {
			selectedJobId = null;
		} else {
			String jobId = tableModel.entryAt(row).jobId();
			selectedJobId = jobId.equals("-") ? null : new JobId(jobId);
		}
		updateButtonStates();
	}

	// EDT-confined: only called from the EDT (action listeners, selection changes, and the
	// invokeLater completion callbacks of runOffEdt).
	private void updateButtonStates() {
		boolean idle = !operationRunning;
		boolean hasSelection = selectedJobId != null;
		boolean pending = hasSelection
				&& !pendingDecisionByJobId.getOrDefault(selectedJobId.value(), "").isBlank();

		connectButton.setEnabled(idle);
		refreshButton.setEnabled(idle);
		submitButton.setEnabled(idle);
		disconnectButton.setEnabled(idle);
		statusButton.setEnabled(idle && hasSelection);
		fetchButton.setEnabled(idle && hasSelection);
		abortButton.setEnabled(idle && hasSelection);
		rescheduleButton.setEnabled(idle && pending);
	}

	// EDT-confined (action listener). Only the constructor and connect() run here; connect()
	// itself is dispatched to a background thread below.
	private void connect() {
		String host = hostField.getText().trim();
		String user = userField.getText().trim();
		int port;
		try {
			port = Integer.parseInt(portField.getText().trim());
		} catch (NumberFormatException invalid) {
			JOptionPane.showMessageDialog(frame, "Port must be a number.",
					"Invalid configuration", JOptionPane.ERROR_MESSAGE);
			return;
		}

		JobClient candidate = new JobClient(host, port, user, Path.of(historyField.getText().trim()));
		runOffEdt(
				() -> {
					candidate.connect();
					return candidate;
				},
				connected -> onConnected(connected, host, port, user),
				failure -> showError("Could not connect: " + failure.getMessage())
		);
	}

	// EDT-confined: only reached via the invokeLater callback of runOffEdt in connect().
	private void onConnected(JobClient connected, String host, int port, String user) {
		this.client = connected;

		Font font = connectionLabel.getFont();
		Font bolded = font.deriveFont(Font.BOLD, 16f);
		connectionLabel.setFont(bolded);
		connectionLabel.setText("Connected to " + host + ": " + port + " as " + user);
//		connectionLabel.setBorder(new EmptyBorder(0, 40, 0, 0));

		selectedJobId = null;
		expectedOutputsByJobId.clear();
		pendingDecisionByJobId.clear();
		showCard("running");
		refreshTable();
	}

	// EDT-confined (action listener); the actual disconnect runs off the EDT.
	private void disconnect() {
		JobClient current = client;
		if (current == null) return;

		runOffEdt(
				() -> {
					current.disconnect();
					return null;
				},
				ignored -> onDisconnected(),
				failure -> onDisconnected()
		);
	}

	// EDT-confined: only reached via the invokeLater callbacks of runOffEdt in disconnect().
	private void onDisconnected() {
		client = null;
		selectedJobId = null;
		showCard("config");
	}

	// EDT-confined (window listener). Disconnects off the EDT, then disposes once that completes.
	private void closeAndDispose() {
		JobClient current = client;
		if (current == null) {
			frame.dispose();
			return;
		}

		Thread closer = new Thread(() -> {
			current.disconnect();
			SwingUtilities.invokeLater(frame::dispose);
		}, "client-gui-closer");
		closer.setDaemon(true);
		closer.start();
	}

	// EDT-confined (action listener). Reads the history file directly: it is local, small, and
	// not one of the JobClient calls the Swing rules call out for a background thread (those are
	// the ones that touch the socket).
	private void refreshTable() {
		JobClient current = client;
		if (current == null) return;

		try {
			tableModel.setData(current.history().loadAll());
		} catch (IOException unreadable) {
			LOGGER.warning("Could not read job history: " + unreadable.getMessage());
		}
		onSelectionChanged();
	}

	private void submit() {
		JFileChooser configChooser = new JFileChooser();
		configChooser.setDialogTitle("Choose job config file");
		if (configChooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
		Path configFile = configChooser.getSelectedFile().toPath();

		JFileChooser dirChooser = new JFileChooser();
		dirChooser.setDialogTitle("Choose directory holding the job's files");
		dirChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (dirChooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
		Path sourceDir = dirChooser.getSelectedFile().toPath();

		JobClient current = client;
		runOffEdt(
				() -> submitAll(current, configFile, sourceDir),
				this::onSubmitFinished,
				failure -> {
					showError("Could not read config file: " + failure.getMessage());
					refreshTable();
				}
		);
	}

	// Runs on the background thread started by runOffEdt: parses the config file (same fixed
	// jobFilename|command|inputs|outputs format ClientMain's REPL uses), validates each line
	// locally, and submits the valid ones in order, continuing past any invalid or failed one.
	private SubmitOutcome submitAll(JobClient current, Path configFile, Path sourceDir) throws IOException {
		List<String> submitted = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		Map<String, List<String>> expectedOutputs = new HashMap<>();

		for (String line : Files.readAllLines(configFile)) {
			if (line.isBlank()) continue;

			String[] fields = line.split("\\|", -1);
			String labelForReporting = fields.length > 0 ? fields[0] : line;

			JobSpec spec;
			try {
				if (fields.length != 4) {
					throw new IllegalArgumentException(
							"expected 4 fields separated by '|', got " + fields.length);
				}
				spec = new JobSpec(fields[0], fields[1], splitFileList(fields[2]), splitFileList(fields[3]));
			} catch (IllegalArgumentException badLine) {
				skipped.add(labelForReporting + ": " + badLine.getMessage());
				recordSkipped(current, labelForReporting, badLine.getMessage());
				continue;
			}

			List<String> problems = current.validate(spec, sourceDir);
			if (!problems.isEmpty()) {
				String reason = String.join("; ", problems);
				skipped.add(spec.jobFilename() + ": " + reason);
				recordSkipped(current, spec.jobFilename(), reason);
				continue;
			}

			try {
				JobId jobId = current.submit(spec, sourceDir);
				submitted.add(spec.jobFilename() + " -> " + jobId.value());
				expectedOutputs.put(jobId.value(), spec.outputFiles());
			} catch (IOException | ClassNotFoundException submitFailed) {
				skipped.add(spec.jobFilename() + ": submit failed - " + submitFailed.getMessage());
			}
		}
		return new SubmitOutcome(submitted, skipped, expectedOutputs);
	}

	private void recordSkipped(JobClient current, String label, String reason) {
		try {
			current.history().append("-", label, "NOT_SUBMITTED: " + reason);
		} catch (IOException ignored) {
			// best-effort, matching ClientMain's own tolerance for a failed history write
		}
	}

	// EDT-confined: only reached via the invokeLater callback of runOffEdt in submit().
	private void onSubmitFinished(SubmitOutcome outcome) {
		expectedOutputsByJobId.putAll(outcome.expectedOutputsByJobId());

		StringBuilder message = new StringBuilder();
		message.append("Submitted (").append(outcome.submitted().size()).append("):\n");
		outcome.submitted().forEach(line -> message.append("  ").append(line).append('\n'));
		message.append("\nSkipped (").append(outcome.skipped().size()).append("):\n");
		outcome.skipped().forEach(line -> message.append("  ").append(line).append('\n'));

		JOptionPane.showMessageDialog(frame, message.toString(), "Submit result", JOptionPane.INFORMATION_MESSAGE);
		refreshTable();
	}

	private void status() {
		JobId jobId = selectedJobId;
		JobClient current = client;
		if (jobId == null || current == null) return;

		runOffEdt(
				() -> current.queryStatusResponse(jobId),
				response -> onStatusFinished(jobId, response),
				failure -> {
					showError("Could not query status: " + failure.getMessage());
					refreshTable();
				}
		);
	}

	// EDT-confined: only reached via the invokeLater callback of runOffEdt in status().
	private void onStatusFinished(JobId jobId, JobStatusResponse response) {
		String pendingReason = response.pendingDecisionReason();
		pendingDecisionByJobId.put(jobId.value(), pendingReason == null ? "" : pendingReason);

		StringBuilder message = new StringBuilder("Status: ").append(response.jobStatus());
		if (response.reasonOfFailure() != null && !response.reasonOfFailure().isBlank()) {
			message.append("\nFailure reason: ").append(response.reasonOfFailure());
		}
		if (pendingReason != null && !pendingReason.isBlank()) {
			message.append("\nPending decision: ").append(pendingReason);
		}
		JOptionPane.showMessageDialog(frame, message.toString(), "Job status", JOptionPane.INFORMATION_MESSAGE);
		refreshTable();
	}

	private void fetch() {
		JobId jobId = selectedJobId;
		JobClient current = client;
		if (jobId == null || current == null) return;

		JFileChooser dirChooser = new JFileChooser();
		dirChooser.setDialogTitle("Choose target directory for results");
		dirChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (dirChooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
		Path targetDir = dirChooser.getSelectedFile().toPath();

		runOffEdt(
				() -> current.fetchResults(jobId, targetDir),
				written -> onFetchFinished(jobId, written),
				failure -> {
					showError("Could not fetch results: " + failure.getMessage());
					refreshTable();
				}
		);
	}

	// EDT-confined: only reached via the invokeLater callback of runOffEdt in fetch().
	private void onFetchFinished(JobId jobId, List<Path> written) {
		List<String> landedNames = written.stream().map(p -> p.getFileName().toString()).toList();

		StringBuilder message = new StringBuilder("Landed (").append(written.size()).append("):\n");
		written.forEach(path -> message.append("  ").append(path).append('\n'));

		List<String> expected = expectedOutputsByJobId.get(jobId.value());
		if (expected == null) {
			message.append("\nExpected outputs unknown: this job was not submitted from this session.");
		} else {
			List<String> missing = expected.stream().filter(name -> !landedNames.contains(name)).toList();
			if (missing.isEmpty()) {
				message.append("\nEvery announced output landed.");
			} else {
				message.append("\nAnnounced but never sent: ").append(missing);
			}
		}

		JOptionPane.showMessageDialog(frame, message.toString(), "Fetch result", JOptionPane.INFORMATION_MESSAGE);
		refreshTable();
	}

	private void abort() {
		JobId jobId = selectedJobId;
		JobClient current = client;
		if (jobId == null || current == null) return;

		runOffEdt(
				() -> current.abortJob(jobId),
				this::showInfoAndRefresh,
				failure -> {
					showError("Could not abort job: " + failure.getMessage());
					refreshTable();
				}
		);
	}

	private void reschedule() {
		JobId jobId = selectedJobId;
		JobClient current = client;
		if (jobId == null || current == null) return;

		runOffEdt(
				() -> current.rescheduleJob(jobId),
				message -> {
					pendingDecisionByJobId.remove(jobId.value());
					showInfoAndRefresh(message);
				},
				failure -> {
					showError("Could not reschedule job: " + failure.getMessage());
					refreshTable();
				}
		);
	}

	// EDT-confined: only reached via invokeLater callbacks of runOffEdt (abort()/reschedule()).
	private void showInfoAndRefresh(String message) {
		JOptionPane.showMessageDialog(frame, message, "Result", JOptionPane.INFORMATION_MESSAGE);
		refreshTable();
	}

	// EDT-confined: only ever called from the EDT (action listeners or invokeLater callbacks).
	private void showError(String message) {
		JOptionPane.showMessageDialog(frame, message, "Error", JOptionPane.ERROR_MESSAGE);
	}

	/**
	 * Runs {@code operation} on a background thread and marshals its outcome back to the EDT.
	 * Action buttons are disabled the moment this is called and re-enabled only once the outcome
	 * (success or failure) has been delivered — since every button action goes through this,
	 * at most one {@link JobClient} operation is ever in flight.
	 */
	private <T> void runOffEdt(BlockingOperation<T> operation,
							   java.util.function.Consumer<T> onSuccess,
							   java.util.function.Consumer<Exception> onFailure) {
		operationRunning = true;
		updateButtonStates();

		Thread worker = new Thread(() -> {
			T result;
			try {
				result = operation.run();
			} catch (Exception failure) {
				SwingUtilities.invokeLater(() -> {
					operationRunning = false;
					updateButtonStates();
					onFailure.accept(failure);
				});
				return;
			}
			SwingUtilities.invokeLater(() -> {
				operationRunning = false;
				updateButtonStates();
				onSuccess.accept(result);
			});
		}, "client-gui-worker");
		worker.setDaemon(true);
		worker.start();
	}

	@FunctionalInterface
	private interface BlockingOperation<T> {
		T run() throws Exception;
	}

	private record SubmitOutcome(List<String> submitted, List<String> skipped,
								 Map<String, List<String>> expectedOutputsByJobId) {
	}

	private static final class JobTableModel extends AbstractTableModel {

		private static final String[] COLUMNS = {"#", "Job Id", "Job File", "Submitted At", "Status"};

		private List<JobHistory.Entry> data = List.of();

		// EDT-confined: only ever called from ClientGui.refreshTable(), which runs on the EDT.
		void setData(List<JobHistory.Entry> data) {
			this.data = data;
			fireTableDataChanged();
		}

		// EDT-confined: same discipline as setData above.
		JobHistory.Entry entryAt(int row) {
			return data.get(row);
		}

		@Override
		public int getRowCount() {
			return data.size();
		}

		@Override
		public int getColumnCount() {
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int column) {
			return COLUMNS[column];
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			JobHistory.Entry entry = data.get(rowIndex);
			return switch (columnIndex) {
				case 0 -> rowIndex + 1;
				case 1 -> entry.jobId();
				case 2 -> entry.jobFilename();
				case 3 -> TIME_FORMAT.format(entry.timestamp());
				case 4 -> entry.status();
				default -> throw new IllegalArgumentException("Unknown column " + columnIndex);
			};
		}
	}
}