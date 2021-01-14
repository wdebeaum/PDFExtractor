package TRIPS.PDFExtractor;

import java.awt.event.ActionEvent;
import java.net.URL;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ImageIcon;
import javax.swing.JButton;

/** Abstract Action that keeps its button. */
public abstract class ActionWithButton extends AbstractAction {
  JButton button;

  /** Make an action whose button is labeled with text only. */
  public ActionWithButton(String buttonLabel) {
    super(buttonLabel);
    button = null;
  }

  /** Make an action whose button has an icon, and put the text in a tool tip.*/
  public ActionWithButton(String toolTipText, String iconName) {
    super(toolTipText, getImageIcon(iconName));
    putValue(Action.SHORT_DESCRIPTION, toolTipText);
    button = null;
  }

  static ImageIcon getImageIcon(String iconName) {
    URL url = ActionWithButton.class.getResource("images/" + iconName + ".png");
    if (url == null) {
      System.err.println("failed to get icon named " + iconName);
      return null; // this makes the action fall back to a text-labeled button
    } else {
      return new ImageIcon(url);
    }
  }

  public void setButton(JButton button) { this.button = button; }

  //// Action ////

  @Override public void setEnabled(boolean enabled) {
    super.setEnabled(enabled);
    button.setVisible(enabled);
  }
}
