package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.GUIFormBuilder;
import rs.ac.bg.etf.kdp.common.gui.TextAreaLogHandler;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Graphical entry point for the central server. Separate from {@link ServerMain}, which
 * remains the headless entry point: {@code ServerMain} never references this class, so no
 * Swing class is ever loaded on the headless path.
 *
 * <p>
 * All component mutation happens on the Event Dispatch Thread, via {@link
 * SwingUtilities#invokeLater}. Starting the server (binding a socket) and the periodic
 * refresh of the workstation/job tables both run off the EDT, on a background thread /
 * scheduled poller respectively, so the window never freezes.
 * </p>
 */
public final class ServerGui {

	private static final Logger LOGGER = Logger.getLogger(ServerGui.class.getName());

	private static final long POLL_INTERVAL_MILLIS = 1000;

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

	private final JTextField portField = new JTextField("4040", 6);
	private final JTextField heartbeatIntervalField = new JTextField("10000", 6);
	private final JTextField heartbeatTimeoutField = new JTextField("30000", 6);

	private final JLabel boundPortLabel = new JLabel();
	private final WorkstationTableModel workstationTableModel = new WorkstationTableModel();
	private final JobTableModel jobTableModel = new JobTableModel();
	private final JTextArea logArea = new JTextArea();

	private JFrame frame;
	private ServerMain server;
	private ScheduledExecutorService poller;

	public static void main(String[] args) {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
			Font base = new Font("SansSerif", Font.PLAIN, 14);
			for (Object key : UIManager.getLookAndFeelDefaults().keySet()) {
				if (key.toString().endsWith(".font")) UIManager.put(key, base);
			}
		} catch (Exception ignored) {

		}
		SwingUtilities.invokeLater(() -> new ServerGui().show());
	}

	private static JPanel labeled(String title, Component content) {
		JPanel panel = new JPanel(new BorderLayout());
		panel.add(new JLabel(title), BorderLayout.NORTH);
		panel.add(content, BorderLayout.CENTER);
		return panel;
	}

	private void show() {
		java.util.logging.Handler handler = new TextAreaLogHandler(logArea);
		Logger.getLogger("").addHandler(handler);

		frame = new JFrame("java-linda server");
		frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				exitApplication(handler);

			}
		});

		cardPanel.add(buildConfigPanel(), "config");
		cardPanel.add(buildRunningPanel(), "running");
		showCard("config");
//		cards.show(cardPanel, "config");

		frame.getContentPane().add(cardPanel);
		frame.pack();
		frame.setMinimumSize(new Dimension(400, 250));

		frame.setLocationRelativeTo(null);  // center
		frame.setVisible(true);

		portField.requestFocusInWindow();
	}


	private void exitApplication(java.util.logging.Handler handler) {
		Logger.getLogger("").removeHandler(handler);
		stopServer(() -> frame.dispose());
	}

	private JPanel buildConfigPanel() {
		JPanel titlePane = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));

		JLabel title = new JLabel("Welcome to server config menu");
		Font titleFont = title.getFont();
		Font bolded = titleFont.deriveFont(Font.BOLD, 18f);
		title.setFont(bolded);
		title.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));

		titlePane.add(title);

		JPanel form = new GUIFormBuilder()
				.addRow("Bind port:", portField)
				.addRow("Heartbeat interval (ms):", heartbeatIntervalField)
				.addRow("Heartbeat timeout (ms):", heartbeatTimeoutField)
				.build();

		JButton startButton = new JButton("Start");
		startButton.addActionListener(e -> startServer());

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
		buttons.add(startButton);

		JPanel panel = new JPanel(new BorderLayout());
//		panel.setPreferredSize(new Dimension(400, 300));

		panel.add(titlePane, BorderLayout.NORTH);
		panel.add(form, BorderLayout.CENTER);
		panel.add(buttons, BorderLayout.SOUTH);
		return panel;
	}

	private JPanel buildRunningPanel() {
		JPanel top = new JPanel(new BorderLayout());
		top.add(boundPortLabel, BorderLayout.WEST);

		JButton stopButton = new JButton("Stop");
		stopButton.addActionListener(e -> stopServer(() -> showCard("config")));
		top.add(stopButton, BorderLayout.EAST);

		JTable workstationTable = new JTable(workstationTableModel);
		workstationTable.setFillsViewportHeight(true);
		workstationTable.setAutoCreateRowSorter(true);
		workstationTable.setPreferredScrollableViewportSize(new Dimension(800, 150));

		JTable jobTable = new JTable(jobTableModel);
		jobTable.setFillsViewportHeight(true);
		jobTable.setAutoCreateRowSorter(true);
		jobTable.setPreferredScrollableViewportSize(new Dimension(800, 150));

		JSplitPane tables = new JSplitPane(
				JSplitPane.VERTICAL_SPLIT,
				labeled("Workstations", new JScrollPane(workstationTable)),
				labeled("Jobs", new JScrollPane(jobTable))
		);
		tables.setResizeWeight(0.5);

		logArea.setEditable(false);
		logArea.setRows(12);
		logArea.setColumns(80);
		logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		JScrollPane logScroll = new JScrollPane(logArea);

		JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tables, labeled("Log", logScroll));
		center.setResizeWeight(0.7);

		JPanel panel = new JPanel(new BorderLayout());
		panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

//		panel.setPreferredSize(new Dimension(700, 500));
		panel.add(top, BorderLayout.NORTH);
		panel.add(center, BorderLayout.CENTER);
		return panel;
	}

	private void showCard(String name) {
		cards.show(cardPanel, name);
		frame.pack();                        // recompute from the visible card only
		frame.setLocationRelativeTo(null);   // keep it centred after the resize
	}

	// Runs on the EDT (button ActionListener); only the blocking part is pushed off it.
	private void startServer() {
		final int port;
		final long intervalMillis;
		final long timeoutMillis;
		try {
			port = Integer.parseInt(portField.getText().trim());
			intervalMillis = Long.parseLong(heartbeatIntervalField.getText().trim());
			timeoutMillis = Long.parseLong(heartbeatTimeoutField.getText().trim());
		} catch (NumberFormatException invalid) {
			JOptionPane.showMessageDialog(frame, "Port and heartbeat fields must be numbers.",
					"Invalid configuration", JOptionPane.ERROR_MESSAGE);
			return;
		}

		Thread starter = new Thread(() -> {
			try {
				ServerMain started = new ServerMain(port, intervalMillis, timeoutMillis);
				SwingUtilities.invokeLater(() -> onServerStarted(started));

				started.serve(); // blocks in the accept loop until the server is closed
			} catch (IOException failedToBind) {
				LOGGER.log(Level.SEVERE, "Failed to start the server: " + failedToBind.getMessage(), failedToBind);
				SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame,
						"Failed to start the server: " + failedToBind.getMessage(),
						"Start failed", JOptionPane.ERROR_MESSAGE));
			}
		}, "server-gui-starter");
		starter.setDaemon(true);
		starter.start();
	}

	// EDT-confined: only called via invokeLater from startServer().
	private void onServerStarted(ServerMain started) {
		this.server = started;
		Font font = boundPortLabel.getFont();
		Font bolded = font.deriveFont(Font.BOLD, 16f);
		boundPortLabel.setFont(bolded);
		boundPortLabel.setText("Listening on port " + started.port());

		poller = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "server-gui-poller");
			t.setDaemon(true);
			return t;
		});
		poller.scheduleWithFixedDelay(this::poll, 0, POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);

		showCard("running");
//		cards.show(cardPanel, "running");
	}

	// Runs on the poller thread, off the EDT: snapshots are cheap (copy-on-read collections), the
	// actual component mutation is marshaled onto the EDT below.
	private void poll() {
		ServerMain current = server;
		if (current == null) return;

		List<WorkstationContext> workstations = List.copyOf(current.workstations().workstations());
		List<JobContext> jobs = current.jobLog().entries();

		SwingUtilities.invokeLater(() -> {
			workstationTableModel.setData(workstations);
			jobTableModel.setData(jobs);
		});
	}

	private void stopServer(Runnable afterwards) {
		if (poller != null) {
			poller.shutdownNow();
			poller = null;
		}

		Thread thread = new Thread(() -> {
			ServerMain current = server;
			server = null;

			if (current != null) {
				try {
					current.close();

				} catch (IOException ignored) {
					// already logged internally by ServerMain
				}
			}
			SwingUtilities.invokeLater(afterwards);
		});

		thread.setDaemon(true);
		thread.start();
	}

	private static final class WorkstationTableModel extends AbstractTableModel {

		private static final String[] COLUMNS = {"Host", "OS", "Java", "Capacity", "Free", "RTT (ms)"};

		private List<WorkstationContext> data = List.of();

		// EDT-confined: only ever called from ServerGui.poll()'s invokeLater.
		void setData(List<WorkstationContext> data) {
			this.data = data;
			fireTableDataChanged();
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
			WorkstationContext ws = data.get(rowIndex);
			return switch (columnIndex) {
				case 0 -> ws.workstationInfo().hostName();
				case 1 -> ws.workstationInfo().osName();
				case 2 -> ws.workstationInfo().javaVersion();
				case 3 -> ws.workstationInfo().parallelJobCapacity();
				case 4 -> ws.availableSlots();
				case 5 -> TimeUnit.NANOSECONDS.toMillis(ws.roundTripTime());
				default -> throw new IllegalArgumentException("Unknown column " + columnIndex);
			};
		}
	}

	private static final class JobTableModel extends AbstractTableModel {

		private static final String[] COLUMNS = {"Arrived", "Job #", "Machine", "Completed", "Status"};

		private static final DateTimeFormatter TIME_FORMAT =
				DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

		private List<JobContext> data = List.of();

		// EDT-confined: only ever called from ServerGui.poll()'s invokeLater.
		void setData(List<JobContext> data) {
			this.data = data;
			fireTableDataChanged();
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
			JobContext job = data.get(rowIndex);
			return switch (columnIndex) {
				case 0 -> TIME_FORMAT.format(job.arrivedAt());
				case 1 -> job.jobNumber();
				case 2 -> String.join(", ", job.assignedWorkstations());
				case 3 -> job.completedAt().map(TIME_FORMAT::format).orElse("");
				case 4 -> job.status();
				default -> throw new IllegalArgumentException("Unknown column " + columnIndex);
			};
		}
	}
}