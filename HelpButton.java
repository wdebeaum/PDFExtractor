package TRIPS.PDFExtractor;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.net.URL;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JEditorPane;
import javax.swing.JScrollPane;
import TRIPS.util.cwc.SwingWindowManager;
import TRIPS.util.cwc.WindowConfig;

public class HelpButton extends JButton implements ActionListener {
  SwingWindowManager windowManager;
  public HelpButton(SwingWindowManager windowManager) {
    super("Help");
    this.windowManager = windowManager;
    addActionListener(this);
  }

  @Override
  public void actionPerformed(ActionEvent evt) {
    WindowConfig wc =
      new WindowConfig("PDFExtractor Help", null, null, 800, 600);
    JFrame window = (JFrame)windowManager.createWindow(wc).getValue();
    try {
      URL helpURL =
        new URL(HelpButton.class.getResource("README.html"), "#gui");
      JEditorPane editor = new JEditorPane(helpURL);
      editor.setEditable(false);
      JScrollPane scroll = new JScrollPane(editor);
      window.add(scroll);
      window.setVisible(true);
    } catch (IOException ex) {
      System.err.println(ex.getMessage());
      ex.printStackTrace();
    }
  }
}
