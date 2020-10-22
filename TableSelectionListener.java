package TRIPS.PDFExtractor;

/** Like ListSelectionListener for a table instead of a list. */
public interface TableSelectionListener {
  /** Called when the selection has changed, but more changes may be coming as
   * part of the same mouse drag.
  */
  public void valueChanged(TableSelection newVal);
  /** Called when the selection has finished changing for the current mouse
   * drag.
   */
  public void valueStoppedChanging(TableSelection val);
}
