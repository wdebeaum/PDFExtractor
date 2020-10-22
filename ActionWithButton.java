package TRIPS.PDFExtractor;

import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;
import javax.swing.JButton;

/** Abstract Action that keeps its button. */
public abstract class ActionWithButton extends AbstractAction {
  JButton button;
  public ActionWithButton(String buttonLabel) {
    super(buttonLabel);
    button = null;
  }

  public void setButton(JButton button) { this.button = button; }

  //// Action ////

  @Override public void setEnabled(boolean enabled) {
    super.setEnabled(enabled);
    button.setVisible(enabled);
  }
}
