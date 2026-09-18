package rs.ac.bg.etf.kdp.common;

import javax.swing.*;
import java.awt.*;

public final class GUIFormBuilder {

	private final JPanel panel = new JPanel(new GridBagLayout());
	private int row = 0;

	public GUIFormBuilder addRow(String label, JComponent field) {
		GridBagConstraints labelAt = new GridBagConstraints();
		labelAt.gridx = 0;
		labelAt.gridy = row;
		labelAt.anchor = GridBagConstraints.LINE_END;   // right-align, so colons line up
		labelAt.insets = new Insets(4, 4, 4, 8);
		panel.add(new JLabel(label), labelAt);

		GridBagConstraints fieldAt = new GridBagConstraints();
		fieldAt.gridx = 1;
		fieldAt.gridy = row++;
		fieldAt.weightx = 1.0;                          // this column absorbs extra width
		fieldAt.fill = GridBagConstraints.HORIZONTAL;
		fieldAt.insets = new Insets(4, 0, 4, 4);
		panel.add(field, fieldAt);

		return this;
	}

	public JPanel build() {
		panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		return panel;
	}
}