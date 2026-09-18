package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.GUIFormBuilder;
import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.gui.TextAreaLogHandler;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Graphical entry point for a workstation node. {@link WorkstationMain} is the headless entry
 * point and never loads this class (it only calls {@link #main} on the branch taken when
 * {@code --headless} is absent, which is simply never executed on the headless path — class
 * loading is per-reference, so a headless run never touches Swing).
 *
 * <p>
 * All component mutation happens on the Event Dispatch Thread, via {@link
 * SwingUtilities#invokeLater}. Connecting to the server and the periodic refresh of free
 * slots / running jobs both run off the EDT, so the window never freezes.
 * </p>
 */
public final class WorkstationGui {

	private static final Logger LOGGER = Logger.getLogger(WorkstationGui.class.getName());

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

	private final JTextField hostField = new JTextField("localhost", 12);
	private final JTextField portField = new JTextField("4040", 6);
	private final JTextField capacityField = new JTextField("2", 4);

	private final JLabel nameLabel = new JLabel();
	private final JLabel osLabel = new JLabel();
	private final JLabel javaLabel = new JLabel();
	private final JLabel freeSlotsLabel = new JLabel();
	private final DefaultListModel<String> runningJobsModel = new DefaultListModel<>();
	private final JTextArea logArea = new JTextArea();

	private JFrame frame;
	private volatile WorkstationMain workstation;
	private ScheduledExecutorService poller;

	private WorkstationGui() {
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
		WorkstationGui gui = new WorkstationGui();
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			WorkstationMain workstation = gui.workstation;

			if (workstation != null) workstation.jobExecutor().destroyAll();
		}, "workstation-shutdown"));
		SwingUtilities.invokeLater(gui::show);
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

		frame = new JFrame("java-linda workstation");
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

		frame.getContentPane().add(cardPanel);
		frame.pack();
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);
	}

	private void exitApplication(java.util.logging.Handler handler) {
		Logger.getLogger("").removeHandler(handler);
		stopWorkstation(() -> frame.dispose());
	}

	private JPanel buildConfigPanel() {
		JPanel titlePane = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));

		JLabel title = new JLabel("Welcome to workstation config panel");
		Font titleFont = title.getFont();
		Font bolded = titleFont.deriveFont(Font.BOLD, 18f);
		title.setFont(bolded);
		title.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));

		titlePane.add(title);
		JPanel form = new GUIFormBuilder()
				.addRow("Server host:", hostField)
				.addRow("Server port: ", portField)
				.addRow("Station parallelism capacity: ", capacityField)
				.build();

		JPanel buttonPane = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
		JButton startButton = new JButton("Start");
		startButton.addActionListener(e -> startWorkstation());
		buttonPane.add(startButton);

		JPanel panel = new JPanel(new BorderLayout());
		panel.add(titlePane, BorderLayout.NORTH);
		panel.add(form, BorderLayout.CENTER);
		panel.add(buttonPane, BorderLayout.SOUTH);
		return panel;
	}

	private void showCard(String name) {
		cards.show(cardPanel, name);
		frame.pack();                        // recompute from the visible card only
		frame.setLocationRelativeTo(null);   // keep it centred after the resize
	}


	private JPanel buildRunningPanel() {
		JPanel info = new JPanel(new GridLayout(0, 2, 8, 8));
		info.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		info.add(new JLabel("Name:"));
		info.add(nameLabel);
		info.add(new JLabel("OS:"));
		osLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
		info.add(osLabel);
		info.add(new JLabel("Java:"));
		info.add(javaLabel);
		info.add(new JLabel("Free slots:"));
		info.add(freeSlotsLabel);

		JPanel buttonPane = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));

		JButton stopButton = new JButton("Stop");
		stopButton.addActionListener(e -> stopWorkstation(() -> showCard("config")));

		buttonPane.add(stopButton);
		buttonPane.setBorder(new EmptyBorder(20, 0, 0, 0));

		JPanel top = new JPanel(new BorderLayout());
		top.add(info, BorderLayout.CENTER);
		top.add(buttonPane, BorderLayout.EAST);

		JList<String> runningJobsList = new JList<>(runningJobsModel);

		runningJobsList.setVisibleRowCount(10);

		logArea.setEditable(false);
		logArea.setRows(12);
		logArea.setColumns(80);
		logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

		JSplitPane center = new JSplitPane(
				JSplitPane.VERTICAL_SPLIT,
				labeled("Running jobs", new JScrollPane(runningJobsList)),
				labeled("Log", new JScrollPane(logArea))
		);
		center.setResizeWeight(0.3);

		JPanel panel = new JPanel(new BorderLayout());
		panel.setBorder(BorderFactory.createEmptyBorder(12, 10, 12, 10));

//		panel.setPreferredSize(new Dimension(500, 450));
		panel.add(top, BorderLayout.NORTH);
		panel.add(center, BorderLayout.CENTER);
		return panel;
	}

	// Runs on the EDT (button ActionListener); only the blocking parts are pushed off it.
	private void startWorkstation() {
		final String host = hostField.getText().trim();
		final int port;
		final int capacity;
		try {
			port = Integer.parseInt(portField.getText().trim());
			capacity = Integer.parseInt(capacityField.getText().trim());
		} catch (NumberFormatException invalid) {
			JOptionPane.showMessageDialog(frame, "Port and capacity must be numbers.",
					"Invalid configuration", JOptionPane.ERROR_MESSAGE);
			return;
		}

		Thread starter = new Thread(() -> runWorkstation(host, port, capacity), "workstation-gui-starter");
		starter.setDaemon(true);
		starter.start();
	}

	// Runs entirely off the EDT: connecting is blocking I/O, and run() blocks for the whole
	// lifetime of the connection.
	private void runWorkstation(String host, int port, int capacity) {
		WorkstationMain started;
		try {
			started = new WorkstationMain(host, port, capacity);
		} catch (IOException failedToConnect) {
			LOGGER.log(Level.SEVERE, "Failed to connect to the server: " + failedToConnect.getMessage(),
					failedToConnect);
			SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame,
					"Failed to connect to the server: " + failedToConnect.getMessage(),
					"Start failed", JOptionPane.ERROR_MESSAGE));
			return;
		}

		this.workstation = started;

		SwingUtilities.invokeLater(() -> onWorkstationStarted(started));

		started.run(); // reacting on closing performed outside
	}

	// EDT-confined: only called via invokeLater from runWorkstation().
	private void onWorkstationStarted(WorkstationMain started) {
		nameLabel.setText(started.workstationInfo().hostName());
		osLabel.setText(started.workstationInfo().osName());
		javaLabel.setText(started.workstationInfo().javaVersion());

		poller = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "workstation-gui-poller");
			t.setDaemon(true);
			return t;
		});
		poller.scheduleWithFixedDelay(this::poll, 0, POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);

		showCard("running");
	}

	// Runs on the poller thread, off the EDT: reads are cheap (atomic / copy-on-read), the actual
	// component mutation is marshaled onto the EDT below.
	private void poll() {
		WorkstationMain current = workstation;
		if (current == null) return;

		int freeSlots = current.jobExecutor().freeSlots();
		List<JobId> runningJobs = List.copyOf(current.jobExecutor().runningJobIds());

		SwingUtilities.invokeLater(() -> {
			freeSlotsLabel.setText(String.valueOf(freeSlots));
			runningJobsModel.clear();
			runningJobs.forEach(jobId -> runningJobsModel.addElement(jobId.value()));
		});
	}

	/*
	Method for stopping the statio and performing the afterwards action on EDT
	 */
	private void stopWorkstation(Runnable afterwards) {
		Thread closer = new Thread(() -> {
			WorkstationMain current = workstation;
			if (current == null) return;

			try {
				current.close();
			} catch (IOException ignored) {
				// already logged internally by WorkstationMain
			}

			if (poller != null) {
				poller.shutdownNow();
				poller = null;
			}

			workstation = null;
			SwingUtilities.invokeLater(afterwards);
		});

		closer.setDaemon(true);
		closer.start();
	}
}