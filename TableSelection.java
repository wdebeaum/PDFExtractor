package TRIPS.PDFExtractor;

import java.awt.Dimension;
import java.util.regex.*;
import TRIPS.KQML.*;
import TRIPS.util.cwc.Args;
import TRIPS.util.cwc.CWCException;
import TRIPS.util.cwc.InvalidArgument;
import TRIPS.util.cwc.UnknownAction;

/** Like ListSelectionEvent for a {@link Table} instead of a list, and it
 * represents the value instead of the event of changing the value. So it can
 * be used in either a "selected" report or a "select" request.
 */
public class TableSelection {
  public final Table table;
  public final int firstRow, firstCol, lastRow, lastCol;

  /** Construct a TableSelection with the given fields. Unless isEmpty,
   * negative row and column indices wrap around.
   */
  public TableSelection(Table table, int firstRow, int firstCol, int lastRow, int lastCol, boolean isEmpty) {
    int numRows = table.getRowCount();
    int numCols = table.getColumnCount();
    if (isEmpty) {
      // make sure TableSelection#isEmpty() agrees
      firstRow = 0; firstCol = 0;
      lastRow = -1; lastCol = -1;
    } else {
      // wrap negative indices
      if (firstRow < 0) firstRow += numRows;
      if (firstCol < 0) firstCol += numCols;
      if (lastRow < 0) lastRow += numRows;
      if (lastCol < 0) lastCol += numCols;
      // TODO? sanity checks
    }
    this.table = table;
    this.firstRow = firstRow; this.firstCol = firstCol;
    this.lastRow = lastRow; this.lastCol = lastCol;
  }

  /** Construct a nonempty TableSelection with the given fields. */
  public TableSelection(Table table, int firstRow, int firstCol, int lastRow, int lastCol) {
    this(table, firstRow, firstCol, lastRow, lastCol, false);
  }

  /** Construct an empty TableSelection. */
  public TableSelection(Table table) {
    this(table, 0, 0, -1, -1, true);
  }

  /** Construct a TableSelection representing the current selection state of
   * the given model.
   */
  public TableSelection(TableSelectionModel model) {
    this(model.table,
         model.getFirstRow(), model.getFirstColumn(),
         model.getLastRow(), model.getLastColumn(),
	 model.isEmpty());
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof TableSelection))
      return false;
    TableSelection ots = (TableSelection)o;
    return (table.equals(ots.table) &&
            firstRow == ots.firstRow && firstCol == ots.firstCol &&
	    lastRow == ots.lastRow && lastCol == ots.lastCol);
  }

  @Override
  public int hashCode() {
    return (table.hashCode() ^
            Integer.hashCode(
	      ((((0
	      ) * table.numRows + firstRow
	      ) * table.numCols + firstCol
	      ) * table.numRows + lastRow
	      ) * table.numCols + lastCol
	      ));
  }

  /** Return an Excel-style range of cells corresponding to the selection. */
  public String toString() {
    return Cell.cellIndexToRef(firstRow, firstCol) + "-" +
	   Cell.cellIndexToRef(lastRow, lastCol);
  }

  /** Inverse of toString(). */
  public static TableSelection fromString(Table table, String range) {
    range = range.toUpperCase();
    Matcher m = Pattern.compile("^([A-Z]+[0-9]+)-([A-Z]+[0-9]+)$").matcher(range);
    if (!m.matches())
      throw new NumberFormatException("expected cell range of the form A#-A# where A is letters and # is numbers, but got " + range);
    int[] first = Cell.cellRefToIndex(m.group(1));
    int[] last = Cell.cellRefToIndex(m.group(2));
    return new TableSelection(table, first[0], first[1], last[0], last[1]);
  }

  /** Is verb OK as the verb of a performative to pass to fromKQML()? */
  public static boolean verbOK(String verb) {
    return " row column cell rows columns cells ".contains(" " + verb + " ");
  }

  /** Return the TableSelection represented by the given KQMLObject. */
  public static TableSelection fromKQML(KQMLObject o) throws CWCException, KQMLBadPerformativeException {
    KQMLPerformative p;
    if (o instanceof KQMLPerformative) {
      p = (KQMLPerformative)o;
    } else if (o instanceof KQMLList) {
      p = new KQMLPerformative((KQMLList)o);
    } else {
      throw new InvalidArgument("select", ":what", "performative", o);
    }
    String verb = p.getVerb().toLowerCase();
    KQMLToken tableID = Args.getTypedArgument(p, ":table", KQMLToken.class);
    Table table = HasID.get(tableID.toString(), Table.class);
    if (verb.equals("nothing")) {
      return new TableSelection(table);
    } else if (verb.equals("row")) {
      int index = Args.getTypedArgument(p, ":index", Integer.class);
      return new TableSelection(table, index, 0, index, -1);
    } else if (verb.equals("column")) {
      int index = Args.getTypedArgument(p, ":index", Integer.class);
      return new TableSelection(table, 0, index, -1, index);
    } else if (verb.equals("rows")) {
      int first = Args.getTypedArgument(p, ":first", Integer.class);
      int last = Args.getTypedArgument(p, ":last", Integer.class);
      return new TableSelection(table, first, 0, last, -1);
    } else if (verb.equals("columns")) {
      int first = Args.getTypedArgument(p, ":first", Integer.class);
      int last = Args.getTypedArgument(p, ":last", Integer.class);
      return new TableSelection(table, 0, first, -1, last);
    } else if (verb.equals("cell")) {
      int row = Args.getTypedArgument(p, ":row", Integer.class);
      int col = Args.getTypedArgument(p, ":column", Integer.class);
      return new TableSelection(table, row, col, row, col);
    } else if (verb.equals("cells")) {
      int firstRow = Args.getTypedArgument(p, ":first-row", Integer.class);
      int firstCol = Args.getTypedArgument(p, ":first-column", Integer.class);
      int lastRow = Args.getTypedArgument(p, ":last-row", Integer.class);
      int lastCol = Args.getTypedArgument(p, ":last-column", Integer.class);
      return new TableSelection(table, firstRow, firstCol, lastRow, lastCol);
    } else {
      throw new UnknownAction(verb);
    }
  }

  /** Return a KQMLObject representing this TableSelection. */
  public KQMLObject toKQML() {
    KQMLPerformative what;
    if (isEmpty()) { // nothing selected
      what = new KQMLPerformative("nothing");
    } else if (isRows()) {
      if (isOneRow()) {
	what = new KQMLPerformative("row");
	what.setParameter(":index", ""+firstRow);
      } else { // multiple rows
	what = new KQMLPerformative("rows");
	what.setParameter(":first", ""+firstRow);
	what.setParameter(":last", ""+lastRow);
      }
    } else if (isColumns()) {
      if (isOneColumn()) {
	what = new KQMLPerformative("column");
	what.setParameter(":index", ""+firstCol);
      } else { // multiple cols
	what = new KQMLPerformative("columns");
	what.setParameter(":first", ""+firstCol);
	what.setParameter(":last", ""+lastCol);
      }
    } else { // just some cells
      if (isOneCell()) {
	what = new KQMLPerformative("cell");
	what.setParameter(":row", ""+firstRow);
	what.setParameter(":column", ""+firstCol);
      } else { // multiple cells
	what = new KQMLPerformative("cells");
	what.setParameter(":first-row", ""+firstRow);
	what.setParameter(":first-column", ""+firstCol);
	what.setParameter(":last-row", ""+lastRow);
	what.setParameter(":last-column", ""+lastCol);
      }
    }
    what.setParameter(":table", table.getID());
    return what;
  }

  /** Get a version of this TableSelection that has been adjusted for a
   * (Delete/Merge)(Rows/Columns) edit. Arguments are the same as
   * Table#adjustRulings(). Returns null if the selection has been deleted.
   */
  public TableSelection getAdjusted(int first, int last, boolean horizontal, boolean delete) {
    int lengthReduction = last - first + (delete ? 1 : 0);
    int newFirstRow = firstRow, newFirstCol = firstCol,
        newLastRow = lastRow, newLastCol = lastCol;
    // stand in newFirst and newLast, abstracting away from rows/cols
    int newFirst, newLast;
    if (horizontal) { // columns were deleted/merged
      newFirst = newFirstCol;
      newLast = newLastCol;
    } else { // vertical, rows were deleted/merged
      newFirst = newFirstRow;
      newLast = newLastRow;
    }
    // adjust newFirst/newLast (see also "ruling is parallel to the range" case
    // of Table#adjustRulings())
    if (delete && newFirst >= first && newLast <= last) return null; // deleted
    if (newFirst > last) {
      newFirst -= lengthReduction;
    } else if (newFirst > first) {
      newFirst = first;
    }
    if (newLast > last) {
      newLast -= lengthReduction;
    } else if (newLast > first) {
      newLast = first - (delete ? 1 : 0);
    }
    // move back to concrete row/col vars
    if (horizontal) { // columns were deleted/merged
      newFirstCol = newFirst;
      newLastCol = newLast;
    } else { // vertical, rows were deleted/merged
      newFirstRow = newFirst;
      newLastRow = newLast;
    }
    // make the new selection
    return new TableSelection(table,
			      newFirstRow, newFirstCol,
			      newLastRow, newLastCol);
  }

  /** Is the cell at (row,col) selected? */
  public boolean isSelected(int row, int col) {
    return (row >= firstRow && col >= firstCol &&
            row <= lastRow && col <= lastCol);
  }

  /** Is the cell at (row,col) with the given span completely contained within
   * the selection rectangle?
   */
  public boolean completelyContains(int row, int col, Dimension span) {
    return isSelected(row, col) &&
	   isSelected(row + span.height - 1, col + span.width - 1);
  }

  /** Like completelyContains(row, col, span), but look up the span of the cell
   * at (row,col) in the table.
  */
  public boolean completelyContains(int row, int col) {
    return completelyContains(row, col, table.getSpanAt(row, col));
  }

  /** Are no cells selected? */
  public boolean isEmpty() {
    return (firstRow > lastRow || firstCol > lastCol);
  }

  /** Is the selection a collection of whole rows? */
  public boolean isRows() {
    return (firstCol == 0 && lastCol == table.getColumnCount() - 1);
  }

  /** Is the selection a collection of whole columns? */
  public boolean isColumns() {
    return (firstRow == 0 && lastRow == table.getRowCount() - 1);
  }

  /** Are the selected cells in only one row? */
  public boolean isOneRow() {
    return (firstRow == lastRow);
  }

  /** Are the selected cells in only one column? */
  public boolean isOneColumn() {
    return (firstCol == lastCol);
  }

  /** Is only one cell selected? */
  public boolean isOneCell() {
    return (firstRow == lastRow && firstCol == lastCol);
  }
}
