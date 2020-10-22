package TRIPS.PDFExtractor;

import java.util.HashSet;
import javax.swing.DefaultListSelectionModel;
import javax.swing.ListSelectionModel;

/** Like ListSelectionModel for a {@link Table} instead of a list, without the
 * rough edges.
 */
public class TableSelectionModel {
  HashSet<TableSelectionListener> listeners;
  Table table;
  ListSelectionModel rowModel;
  ListSelectionModel colModel;
  boolean adjustingRows;
  boolean adjustingCols;

  /** Construct a TableSelectionModel for the given Table, empty at first. */
  public TableSelectionModel(Table table) {
    listeners = new HashSet<TableSelectionListener>();
    this.table = table;
    rowModel = new DefaultListSelectionModel();
    rowModel.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
    colModel = new DefaultListSelectionModel();
    colModel.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
    adjustingRows = false;
    adjustingCols = false;
  }

  public void addListener(TableSelectionListener l) {
    listeners.add(l);
  }

  public void fireValueChanged() {
    TableSelection val = new TableSelection(this);
    for (TableSelectionListener l : listeners) {
      l.valueChanged(val);
    }
  }

  public void fireValueStoppedChanging() {
    TableSelection val = new TableSelection(this);
    for (TableSelectionListener l : listeners) {
      l.valueStoppedChanging(val);
    }
  }

  /** Set whether we are currently adjusting each axis of the selection. Fire
   * valueStoppedChanging() if we're now adjusting neither axis.
   */
  public void setAdjusting(boolean rows, boolean cols) {
    adjustingRows = rows;
    adjustingCols = cols;
    if (!(rows || cols)) fireValueStoppedChanging();
  }
  public boolean isAdjustingRows() { return adjustingRows; }
  public boolean isAdjustingColumns() { return adjustingCols; }

  /** Set the position of both stationary and moving corners of the selection
   * to the same cell.
   */
  public void setAnchorAndLead(int row, int col) {
    if (row < 0) row += table.getRowCount();
    if (col < 0) col += table.getColumnCount();
    rowModel.setSelectionInterval(row, row);
    colModel.setSelectionInterval(col, col);
    fireValueChanged();
  }

  /** Set the position of the moving corner of the selection. Values are only
   * set if the corresponding axis is currently being adjusted.
   */
  public void setLead(int row, int col) {
    if (adjustingRows) rowModel.setLeadSelectionIndex(row);
    if (adjustingCols) colModel.setLeadSelectionIndex(col);
    if (adjustingRows || adjustingCols) fireValueChanged();
  }

  public boolean isSelected(int row, int col) {
    return (rowModel.isSelectedIndex(row) && colModel.isSelectedIndex(col));
  }

  public boolean isEmpty() {
    return (rowModel.isSelectionEmpty() || colModel.isSelectionEmpty());
  }

  public int getFirstRow()    { return rowModel.getMinSelectionIndex(); }
  public int getFirstColumn() { return colModel.getMinSelectionIndex(); }
  public int getLastRow()     { return rowModel.getMaxSelectionIndex(); }
  public int getLastColumn()  { return colModel.getMaxSelectionIndex(); }

  /** Select or deselect the given rectangle of cells. Negative indices wrap. */
  public void setSelection(int firstRow, int firstCol,
			   int lastRow, int lastCol,
			   boolean isSelect) {
    int numRows = table.getRowCount();
    int numCols = table.getColumnCount();
    if (firstRow < 0) firstRow += numRows;
    if (firstCol < 0) firstCol += numCols;
    if (lastRow < 0) lastRow += numRows;
    if (lastCol < 0) lastCol += numCols;
    if (isSelect) {
      rowModel.setSelectionInterval(firstRow, lastRow);
      colModel.setSelectionInterval(firstCol, lastCol);
    } else {
      // FIXME?
      rowModel.removeSelectionInterval(firstRow, lastRow);
      colModel.removeSelectionInterval(firstCol, lastCol);
    }
    fireValueChanged();
  }

  /** Select the given rectangle of cells. Negative indices wrap. */
  public void setSelection(int firstRow, int firstCol,
			   int lastRow, int lastCol) {
    setSelection(firstRow, firstCol, lastRow, lastCol, true);
  }

  /** Select or deselect the given selection. Assumes tables match. */
  public void setSelection(TableSelection s, boolean isSelect) {
    setSelection(s.firstRow, s.firstCol, s.lastRow, s.lastCol, isSelect);
  }

  /** Select no cells. */
  public void clearSelection() {
    if (!(rowModel.isSelectionEmpty() || colModel.isSelectionEmpty())) {
      rowModel.clearSelection();
      colModel.clearSelection();
      setAdjusting(false, false);
      fireValueChanged();
    }
  }
}
