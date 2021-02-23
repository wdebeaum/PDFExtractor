package TRIPS.PDFExtractor;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.swing.InputVerifier;
import javax.swing.JFrame;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.JTextComponent;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import technology.tabula.HasText;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Ruling;
//import technology.tabula.Table; conflicts (obvs.)
import technology.tabula.TextChunk;
import technology.tabula.TextElement;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import TRIPS.KQML.*;
import TRIPS.util.cwc.Args;
import TRIPS.util.cwc.CWCException;
import TRIPS.util.cwc.InvalidArgument;
import TRIPS.util.cwc.UnknownAction;


/** Improvement on Tabula's Table class: it has an ID, can be the model for a
 * JTable, and can be edited.
 */
public class Table extends AbstractTableModel implements HasID, TextMatch.Searchable {
  final String id;
  @Override public String getID() { return id; }

  // current state
  int numRows, numCols;
  List<List<RectangularTextContainer>> rows;
  /** Nontrivial Rulings in cell coordinates. */
  List<Ruling> rulings;
  // edit history
  /** The region that was originally selected for the whole table. This is null
   * if the table isn't from Tabula (see originRows).
   */
  Region origin;
  /** The data that was originally used to construct this table. This is null
   * if origin isn't.
   */
  List<List<RectangularTextContainer>> originRows;
  /** The column boundaries actually used by Tabula. This includes both
   * automatic and manual boundaries between columns. Edits other than
   * SplitColumn don't affect this (e.g. MergeColumns doesn't delete entries).
   * Note that this list includes the boundary before the first column, because
   * it makes some things simpler, but Tabula doesn't actually receive that one
   * because then it would make an extra blank column at the beginning.
   */
  List<Float> colBoundXs;
  /** The manually-added column boundaries only. */
  List<SplitColumn> splitColumns;
  /** All edits applied to this table, in order, except SplitColumn edits.
   * splitColumns apply before running Tabula.
   */
  List<Edit> history;
  /** All edits applied to this table, in order, including SplitColumn edits.
   * We need this too in order to implement Undo properly.
   */
  List<Edit> undoHistory;
  /** All edits that have been undone since the last edit was done. */
  List<Edit> redoHistory;

  final static boolean debugCellRegions = false;

  public Table(technology.tabula.Table tabulaTable, Region origin) {
    this.origin = origin;
    originRows = null;
    splitColumns = new LinkedList<SplitColumn>();
    history = new LinkedList<Edit>();
    undoHistory = new LinkedList<Edit>();
    redoHistory = new LinkedList<Edit>();
    this.id = HasID.getNextIDAndPut(this);
    setTabulaTable(tabulaTable);
    if (debugCellRegions) {
      // make a Region for each cell so they get highlighted
      for (List<RectangularTextContainer> row : rows) {
	for (RectangularTextContainer cell : row) {
	  new Region(cell, origin.getPage(), Region.Source.SYSTEM);
	}
      }
    }
  }

  public Table(Region origin) {
    this(extractTabulaTable(origin), origin);
  }

  public Table(List<List<RectangularTextContainer>> originRows) {
    this.originRows = originRows;
    origin = null;
    resetRowsToOrigin();
    splitColumns = new LinkedList<SplitColumn>();
    history = new LinkedList<Edit>();
    undoHistory = new LinkedList<Edit>();
    redoHistory = new LinkedList<Edit>();
    rulings = new ArrayList<Ruling>();
    colBoundXs = null;
    this.id = HasID.getNextIDAndPut(this);
  }

  @Override public int getRowCount() { return numRows; }
  @Override public int getColumnCount() { return numCols; }
  @Override public Object getValueAt(int row, int column) {
    HTMLBuilder out = new HTMLBuilder();
    RectangularTextContainer cell = getCellAt(row, column);
    boolean isHeading = Cell.getPropertiesOf(cell).isHeading();
    if (isHeading) out.beginB();
    Cell.getHTMLOf(cell, out);
    if (isHeading) out.endB();
    return out.toDocumentString();
  }

  @Override public KQMLObject toKQML() {
    KQMLPerformative p = new KQMLPerformative("table");
    p.setParameter(":id", id);
    KQMLList rowsKQML = new KQMLList();
    for (List<RectangularTextContainer> row : rows) {
      KQMLList rowKQML = new KQMLList();
      for (RectangularTextContainer cell : row) {
	rowKQML.add(new KQMLString(Cell.getTextOf(cell)));
      }
      rowsKQML.add(rowKQML);
    }
    p.setParameter(":data", rowsKQML);
    KQMLList rulingsKQML = new KQMLList();
    for (Ruling ruling : rulings) {
      rulingsKQML.add(rulingToKQML(ruling));
    }
    p.setParameter(":rulings", rulingsKQML);
    return p;
  }

  static KQMLObject rulingToKQML(Ruling ruling) {
    KQMLPerformative rulingKQML;
    if (ruling.horizontal()) {
      rulingKQML = new KQMLPerformative("horizontal");
      rulingKQML.setParameter(":row",
	Integer.toString((int)ruling.getPosition()));
      rulingKQML.setParameter(":first-column",
	Integer.toString((int)ruling.getStart()));
      rulingKQML.setParameter(":last-column",
	Integer.toString((int)ruling.getEnd()));
    } else { // vertical
      rulingKQML = new KQMLPerformative("vertical");
      rulingKQML.setParameter(":column",
	Integer.toString((int)ruling.getPosition()));
      rulingKQML.setParameter(":first-row",
	Integer.toString((int)ruling.getStart()));
      rulingKQML.setParameter(":last-row",
	Integer.toString((int)ruling.getEnd()));
    }
    return rulingKQML;
  }

  public static Table fromKQML(KQMLObject listOrID) throws CWCException, KQMLBadPerformativeException {
    if (listOrID instanceof KQMLList) {
      return fromKQML(new KQMLPerformative((KQMLList)listOrID));
    } else if (listOrID instanceof KQMLPerformative) {
      KQMLPerformative perf = (KQMLPerformative)listOrID;
      KQMLToken idKQML =
        Args.getTypedArgument(perf, ":id", KQMLToken.class, new KQMLToken("nil"));
      String id = idKQML.toString().toLowerCase();
      if (id.equals("nil")) { // have no ID, make a new table
	KQMLList dataKQML =
	  Args.getTypedArgument(perf, ":data", KQMLList.class);
	// TODO? rulings
	int numRows = dataKQML.size();
	if (numRows == 0)
	  throw new InvalidArgument(perf, ":data", "nonempty list of rows");
	KQMLObject firstRowObj = dataKQML.get(0);
	if (!(firstRowObj instanceof KQMLList))
	  throw new InvalidArgument(perf, ":data", "list of lists of cells");
	int numCols = ((KQMLList)dataKQML.get(0)).size();
	if (numCols == 0)
	  throw new InvalidArgument(perf, ":data", "nonempty first row");
	List<List<RectangularTextContainer>> rows =
	  new ArrayList<List<RectangularTextContainer>>(numRows);
	for (int i = 0; i < numRows; i++) {
	  KQMLObject rowObj = dataKQML.get(i);
	  if (!(rowObj instanceof KQMLList))
	    throw new InvalidArgument(perf, ":data", "list of lists of cells");
	  KQMLList rowList = (KQMLList)rowObj;
	  if (numCols != rowList.size())
	    throw new InvalidArgument(perf, ":data", "same number of columns in each row");
	  List<RectangularTextContainer> row = new ArrayList<RectangularTextContainer>(numCols);
	  for (int j = 0; j < numCols; j++) {
	    KQMLObject cellObj = rowList.get(j);
	    Cell cell = new SyntheticCell(i, j, cellObj.stringValue());
	    row.add(cell);
	  }
	  rows.add(row);
	}
	return new Table(rows);
      } else { // have ID, just get the existing object
	return HasID.get(id, Table.class);
      }
    } else if (listOrID instanceof KQMLToken) {
      return HasID.get(listOrID.toString(), Table.class);
    } else {
      throw new InvalidArgument("nil", ":table", "list or id", listOrID);
    }
  }

  @Override
  public List<TextMatch> search(Pattern searchPattern) {
    List<TextMatch> matchesHere = new LinkedList<TextMatch>();
    int i = 0;
    for (List<RectangularTextContainer> row : rows) {
      int j = 0;
      for (RectangularTextContainer cell : row) {
	String context = Cell.getTextOf(cell);
	List<TextMatch> matches = TextMatch.search(searchPattern, context);
	for (TextMatch m : matches) {
	  matchesHere.add(m.inCell(this, i, j));
	}
	j++;
      }
      i++;
    }
    return matchesHere;
  }

  /** Write the table data in CSV format using the given writer. */
  public void writeCSV(Writer w) throws IOException {
    for (List<RectangularTextContainer> row : rows) {
      boolean first = true;
      for (RectangularTextContainer cell : row) {
	String text = Cell.getTextOf(cell);
	if (first) {
	  first = false;
	} else {
	  w.write(","); // the titular comma separating the values
	}
	w.write("\""); // quote each cell
	w.write(text.replace("\"", "\"\"")); // escape quotes by doubling them
	w.write("\"");
      }
      w.write("\r\n"); // CRLF line endings, per RFCs 4180 and 2046
    }
  }

  //// writeHTML-related stuff ////
  
  final static boolean debugHTML = false;

  /** Return the value of the HTML id attribute to use for a (heading) cell at
   * the given row and column indices.
   */
  static String cellID(int row, int col) { return "r" + row + "c" + col; }

  /** Update groupSpans with the span from indices first to last. If the new
   * span exactly covers an existing subsequence of spans, they will be merged
   * into one span.
   */
  static void addGroup(int[] groupSpans, int first, int last) {
    if (debugHTML) System.err.println("addGroup(" + groupSpans + ", " + first + ", " + last + ")");
    // if the new span exactly covers and existing subsequence of spans
    if (groupSpans[first] != 0 &&
        (last + 1 == groupSpans.length || groupSpans[last + 1] != 0)) {
      // merge them into one span by zeroing span sizes in the middle
      int i = first + groupSpans[first];
      while (i <= last) {
	int span = groupSpans[i];
//	if (debugHTML) System.err.println("i = " + i + "; span = " + span);
	if (span == 0) throw new RuntimeException("WTF");
	groupSpans[i] = 0;
	i += span;
      }
      // and setting the first span size to the whole span's size
      groupSpans[first] = last + 1 - first;
    }
  }

  /** Get the default value of the HTML scope attribute that will be used for
   * the heading at (i, j) if no explicit scope attribute is used. Returns one
   * of "row", "column", or null.
   */
  String getDefaultHeadingScope(int i, int j, Dimension span) {
    // do we have any data cells to our left or right?
    boolean haveDataLR = false;
    for (int ii = i; ii < i + span.height; ii++) {
      for (int jj = 0; jj < numCols; jj++) {
	RectangularTextContainer c = getCellCovering(ii, jj);
	CellProperties p = Cell.getPropertiesOf(c);
	if (p.type == CellProperties.Type.DATA) {
	  haveDataLR = true;
	  ii = i + span.height;
	  break;
	}
      }
    }
    if (!haveDataLR)
      return "column";
    // do we have any data cells above or below?
    boolean haveDataAB = false;
    for (int ii = 0; ii < numRows; ii++) {
      for (int jj = j; jj < j + span.width; jj++) {
	RectangularTextContainer c = getCellCovering(ii, jj);
	CellProperties p = Cell.getPropertiesOf(c);
	if (p.type == CellProperties.Type.DATA) {
	  haveDataAB = true;
	  ii = numRows;
	  break;
	}
      }
    }
    if (!haveDataLR)
      return "row";
    // no related data cells, scope is indeterminate
    return null;
  }

  /** Get the value of the HTML scope attribute that we want to be in effect
   * for the heading at (i, j) (whether or not we actually have to specify it).
   * Returns one of "row", "rowgroup", "col", "colgroup", or null.
   */
  String getDesiredHeadingScope(int i, int j, Dimension span, CellProperties props, int[] rowgroupSpans, int[] colgroupSpans) {
    if (debugHTML) System.err.println("getEffectiveHeadingScope(" + i + ", " + j + ", (" + span.height + "x" + span.width + "), #, #, #)");
    String scope = null;
    TableSelection ahf = props.headingFor;
    // if the appropriate group span matches ahf, add scope attr
    // FIXME? this ignores ahf's column span for row headings and vice versa
    switch (props.type) {
      case ROW_HEADING:
	if (debugHTML) System.err.println("      ROW_HEADING");
	if (ahf.firstRow <= i && ahf.lastRow >= i + span.height - 1) {
	  // this heading is in the row(group) it is for
	  if (ahf.firstRow == i && ahf.lastRow == i + span.height - 1) {
	    // this heading is for exactly the rows it covers
	    scope = "row";
	  } else if (rowgroupSpans[ahf.firstRow] ==
		     ahf.lastRow + 1 - ahf.firstRow) {
	    // this heading is for one of the row(group)s we chose
	    scope = "rowgroup";
	  }
	}
	break;
      case COLUMN_HEADING:
	if (debugHTML) System.err.println("      COLUMN_HEADING");
	if (ahf.firstCol <= j && ahf.lastCol >= j + span.width - 1) {
	  // this heading is in the col(group) it is for
	  if (ahf.firstCol == j && ahf.lastCol == j + span.width - 1) {
	    // this heading is for exactly the columns it covers
	    scope = "col";
	  } else if (colgroupSpans[ahf.firstCol] ==
		     ahf.lastCol + 1 - ahf.firstCol) {
	    // this heading is for one of the col(group)s we chose
	    scope = "colgroup";
	  }
	}
	break;
    }
    if (debugHTML) System.err.println("returning " + scope + " from getEffectiveHeadingScope()");
    return scope;
  }

  /** Get the value of the HTML scope attribute that will be in effect for the
   * heading at (i, j) if we set the explicit scope attribute (or don't set it)
   * appropriately (i.e. according to getHeadingScope()).
   * Returns one of "row", "rowgroup", "col", "colgroup", or null.
   */
  String getEffectiveHeadingScope(int i, int j, Dimension span, CellProperties props, int[] rowgroupSpans, int[] colgroupSpans) {
    String scope =
      getDesiredHeadingScope(i, j, span, props, rowgroupSpans, colgroupSpans);
    // if we don't desire any particular scope, we don't specify it, and thus
    // get the default (which may also be null)
    if (scope == null)
      scope = getDefaultHeadingScope(i, j, span);
    return scope;
  }

  /** Get the value of the HTML scope attribute to use for the heading at
   * (i, j), or null if no scope attribute should be used.
   */
  String getHeadingScope(int i, int j, Dimension span, CellProperties props, int[] rowgroupSpans, int[] colgroupSpans) {
    // get default and actual values for headingFor and scope
    String defaultScope = getDefaultHeadingScope(i, j, span);
    String scope =
      getEffectiveHeadingScope(i, j, span, props, rowgroupSpans, colgroupSpans);
    TableSelection dhf = props.getDefaultHeadingFor(this, i, j, span);
    TableSelection ahf = props.headingFor;
    // decide whether we need to specify scope explicitly
    if (debugHTML) System.err.println("  dhf = " + dhf.toString() + "; ahf = " + ahf.toString() + "; defaultScope = " + defaultScope + "; scope = " + scope);
    if (!dhf.equals(ahf)) {
      if (debugHTML) System.err.println("  dhf != ahf");
    } else if (defaultScope == null && scope != null ||
               defaultScope != null && scope == null ||
	       defaultScope != null && scope != null &&
	         !defaultScope.equals(scope)) {
      if (debugHTML) System.err.println("  defaultScope != scope");
    } else {
      // default headingFor and scope are good, don't change them
      scope = null;
    }
    if (debugHTML) System.err.println("returning " + scope + " from getHeadingScope()");
    return scope;
  }

  /** The "internal algorithm for scanning and assigning header cells".
   * (ix, iy) = initial coordinate; (dx, dy) = (Δx, Δy); coordinates are all
   * passed in (row, column) order, though.
   * @see htmlDefaultHeadings
   */
  void iafsaahc(int i, int j, SortedSet<int[]> headerList, int iy, int ix, int dy, int dx, int[] rowgroupSpans, int[] colgroupSpans) {
    boolean debug = (debugHTML && (i == 4 && (j == 3 || j == 0)));
    if (debug) System.err.println("iafsaahc(" + i + ", " + j + ", #, " + iy + ", " + ix + ", " + dy + ", " + dx + ", #, #)");
    // 1 & 2
    int x = ix, y = iy;
    // 3
    List<int[]> opaqueHeaders = new LinkedList<int[]>();
    // 4
    boolean inHeaderBlock;
    // "headers from current header block"
    List<int[]> hfchb = new LinkedList<int[]>();
    if (Cell.getPropertiesOf(getCellAt(i, j)).isHeading()) {
      inHeaderBlock = true;
      hfchb.add(new int[] {i, j});
    } else {
      inHeaderBlock = false;
    }
    // 5
    while (true) {
      x += dx; y += dy;
      // 6
      if (x < 0 || y < 0) return; // "abort"
      // 7 (this loop condition is never true for us, all our slots are covered
      // by exactly 1 cell)
      // 8
      RectangularTextContainer currentCell = getCellCovering(y, x);
      Dimension ccSpan = Cell.getSpanOf(currentCell);
      CellProperties ccProps = Cell.getPropertiesOf(currentCell);
      if (debug) System.err.println("  cell covering " + y + "," + x + " is '" + Cell.getTextOf(currentCell) + "' spanning " + ccSpan.height + "x" + ccSpan.width);
      // 9
      if (ccProps.isHeading()) {
	if (debug) System.err.println("    and is a heading");
	// 9.1
	inHeaderBlock = true;
	// 9.2
	hfchb.add(new int[] {y, x});
	// 9.3
	boolean blocked = false;
	// 9.4
	if (dx == 0) {
	  // block if there are opaque headers with same horizontal span
	  for (int[] oh : opaqueHeaders) {
	    RectangularTextContainer ohCell = getCellCovering(oh[0], oh[1]);
	    CellProperties ohProps = Cell.getPropertiesOf(ohCell);
	    Dimension ohSpan = Cell.getSpanOf(ohCell);
	    if (oh[1] == x && ohSpan.width == ccSpan.width) {
	      blocked = true;
	      break;
	    }
	  }
	  // ... or if currentCell is not a column header
	  if (!blocked) {
	    String scope =
	      getEffectiveHeadingScope(y, x, ccSpan, ccProps,
				       rowgroupSpans, colgroupSpans);
	    if (scope == null || !scope.equals("col"))
	      blocked = true;
	  }
	}
	if (dy == 0) {
	  // block if there are opaque headers with the same vertical span
	  for (int[] oh : opaqueHeaders) {
	    RectangularTextContainer ohCell = getCellCovering(oh[0], oh[1]);
	    CellProperties ohProps = Cell.getPropertiesOf(ohCell);
	    Dimension ohSpan = Cell.getSpanOf(ohCell);
	    if (oh[0] == y && ohSpan.height == ccSpan.height) {
	      blocked = true;
	      break;
	    }
	  }
	  // ... or if currentCell is not a row header
	  if (!blocked) {
	    String scope =
	      getHeadingScope(y, x, ccSpan, ccProps,
			      rowgroupSpans, colgroupSpans);
	    if (scope == null || !scope.equals("row"))
	      blocked = true;
	  }
	}
	// 9.5
	if (debug) System.err.println(blocked ? "    blocked" : "    not blocked");
	if (!blocked) headerList.add(new int[] {y, x});
      } else if (inHeaderBlock) {
	if (debug) System.err.println("    and is not a heading, ending header block");
	inHeaderBlock = false;
	opaqueHeaders.addAll(hfchb);
	hfchb.clear();
      }
      // 10
    }
  }

  /** Orders cell coordinate pairs like new int[] {i, j} in row-major order. */
  public static class CellCoordinateComparator implements Comparator<int[]> {
    public int compare(int[] a, int[] b) {
      if (a[0] < b[0]) {
	return -1;
      } else if (a[0] > b[0]) {
	return 1;
      // else a[0] == b[0]
      } else if (a[1] < b[1]) {
	return -1;
      } else if (a[1] > b[1]) {
	return 1;
      } else { // a[1] == b[1]
	return 0;
      }
    }
  }

  /** Return the set of (coordinates of) heading cells that the HTML spec
   * would say apply to the cell at coordinates (i, j) if it had no headers
   * attribute, and col/colgroup/thead/tbody elements existed according to
   * rowgroupSpans and colgroupSpans. The set is sorted in the order that the
   * cells would appear in the document.
   * @see <a href="https://html.spec.whatwg.org/multipage/tables.html#header-and-data-cell-semantics">HTML spec on header and data cell semantics</a>
   * @param i principal_y
   * @param j principal_x
   */
  SortedSet<int[]> htmlDefaultHeadings(int i, int j, int[] rowgroupSpans, int[] colgroupSpans) {
    boolean debug = (debugHTML && (i == 4 && (j == 3 || j == 0)));
    if (debug) System.err.println("htmlDefaultHeadings(" + i + ", " + j + ", #, #)");
    // 1 (really we make a sorted set instead of a list, to accomplish step 5
    // as we go)
    SortedSet<int[]> headerList =
      new TreeSet<int[]>(new CellCoordinateComparator());
    // 2 is the arguments
    // 3.1 & 3.2 (going down the no-"headers" branch)
    Dimension span = Cell.getSpanOf(getCellAt(i,j));
    // span.width = principal_width; span.height = principal_height
    // 3.3
    for (int y = i; y <= i + span.height - 1; y++) {
      iafsaahc(i, j, headerList, y, j, 0, -1, rowgroupSpans, colgroupSpans);
    }
    // 3.4
    for (int x = j; x <= j + span.width - 1; x++) {
      iafsaahc(i, j, headerList, i, x, -1, 0, rowgroupSpans, colgroupSpans);
    }
    // 3.5
    if (rowgroupSpans[i] != 1) { // principal cell is anchored in a row group
      int y = i;
      while (rowgroupSpans[y] == 0) y--;
      // y now indexes the span of the row group the cell is in
      int rgs = rowgroupSpans[y];
      // add all row group headers in this range to headerList
      int maxX = j + span.width - 1;
      int maxY = i + span.height - 1;
      if (debug) System.err.println("y = " + y + "; rgs = " + rgs + "; maxY = " + maxY + "; maxX = " + maxX);
      for (; y <= maxY; y++) {
	for (int x = 0; x <= maxX; x++) {
	  if (debug) System.err.println("y = " + y + "; x = " + x);
	  RectangularTextContainer h = getCellAt(y, x);
	  CellProperties props = Cell.getPropertiesOf(h);
	  if (props.isHeading()) {
	    if (debug) System.err.println("  isHeading");
	    Dimension hs = Cell.getSpanOf(h);
	    String scope =
	      getHeadingScope(y, x, hs, props, rowgroupSpans, colgroupSpans);
	    if (debug) System.err.println("  scope = " + scope);
	    if (scope != null && scope.equals("rowgroup"))
	      headerList.add(new int[] {y, x});
	  }
	}
      }
    }
    // 3.6
    if (colgroupSpans[j] != 1) { // principal cell is anchored in a col group
      int ix = j;
      while (colgroupSpans[ix] == 0) ix--;
      // ix now indexes the span of the col group the cell is in
      int cgs = colgroupSpans[ix];
      // add all col group headers in this range to headerList
      int maxX = j + span.width - 1;
      int maxY = i + span.height - 1;
      for (int y = 0; y <= maxY; y++) {
	for (int x = ix; x <= maxX; x++) {
	  RectangularTextContainer h = getCellAt(y, x);
	  CellProperties props = Cell.getPropertiesOf(h);
	  if (props.isHeading()) {
	    Dimension hs = Cell.getSpanOf(h);
	    String scope =
	      getHeadingScope(y, x, hs, props, rowgroupSpans, colgroupSpans);
	    if (scope != null && scope.equals("colgroup"))
	      headerList.add(new int[] {y, x});
	  }
	}
      }
    }
    // 4
    List<int[]> emptyCells = new LinkedList<int[]>();
    for (int[] header : headerList) {
      if (Cell.isEmpty(getCellAt(header[0], header[1])))
	emptyCells.add(header);
    }
    headerList.removeAll(emptyCells);
    // 5 is already accomplished by virtue of it being a SortedSet
    // 6
    headerList.remove(new int[] {i, j});
    // 7
    return headerList;
  }

  /** Do the lists a and b contain the same elements in the same order, using c
   * to compare elements?
   * This is essentially a.equals(b), except with an explicit Comparator.
   * Because Object#equals() doesn't do the right thing for int[].
   */
  static <T> boolean listsEqual(List<T> a, List<T> b, Comparator<T> c) {
    if (a.size() != b.size())
      return false;
    Iterator<T> bi = b.iterator();
    for (T ae : a) {
      T be = bi.next();
      if (c.compare(ae, be) != 0)
	return false;
    }
    return true;
  }

  /** Remove headings that are headings for other headings later in the same
   * list of headings. Heading-for-ness is transitive in HTML, so it's not
   * necessary to include such headings in the final headers list, and the
   * htmlDefaultHeadings() algorithm sometimes doesn't include them.
   */
  void pruneHeadingsList(List<int[]> headings) {
    for (int ai = 0; ai < headings.size(); ai++) {
      int[] a = headings.get(ai);
      TableSelection ahf =
	Cell.getPropertiesOf(getCellAt(a[0], a[1])).headingFor;
      for (int bi = ai + 1; bi < headings.size(); bi++) {
	int[] b = headings.get(bi);
	if (ahf.completelyContains(b[0], b[1])) {
	  headings.remove(ai);
	  ai--;
	  break;
	}
      }
    }
  }

  /** JavaScript code for substituting variables in annotation templates. */
  final static String annotationTemplateJS = String.join("\n",
    "const ZmA = '0'.codePointAt(0) - 'A'.codePointAt(0);",
    "const K = 'K'.codePointAt(0);",
    "const AmK = 'A'.codePointAt(0) - K;",
    "function base26(str) {",
    "  var letters = Array.from(str);",
    "  var digits = letters.map(function(c) {",
    "    var oldCP = c.codePointAt(0);",
    "    var newCP = oldCP + ((oldCP >= K) ? AmK : ZmA);",
    "    return String.fromCodePoint(newCP);",
    "  });",
    "  return parseInt(digits.join(''), 26);",
    "}",
    "var dataCells = document.getElementsByTagName('td');",
    "for (var i = 0; i < dataCells.length; i++) {",
    "  var cell = dataCells[i];",
    "  cell.title = cell.title.replace(/\\[cell\\]/g, cell.innerText);",
    "}",
    "var headings = document.getElementsByTagName('th');",
    "for (var i = 0; i < headings.length; i++) {",
    "  var hText = headings[i].innerText;",
    "  var hTitle = headings[i].title;",
    "  var m = /((?:row|column) heading) for ([A-Z]+)(\\d+)-([A-Z]+)(\\d+)$/.",
    "	  exec(hTitle);",
    "  if (null === m) continue;",
    "  var vr = new RegExp(\"\\\\[\" + m[1] + \"\\\\]\", 'g');",
    "  var [firstCol, firstRow, lastCol, lastRow] = m.slice(2);",
    "  firstCol = base26(firstCol);",
    "  firstRow = parseInt(firstRow, 10) - 1;",
    "  lastCol = base26(lastCol);",
    "  lastRow = parseInt(lastRow, 10) - 1;",
    "  for (var j = 0; j < dataCells.length; j++) {",
    "    var cell = dataCells[j];",
    "    var col = cell.cellIndex;",
    "    var row = cell.parentNode.rowIndex;",
    "    if (row >= firstRow && col >= firstCol && row <= lastRow && col <= lastCol){",
    "      cell.title = cell.title.replace(vr, hText);",
    "    }",
    "  }",
    "}"
  );

  /** Write the table data in HTML format using the given writer. */
  public void writeHTML(Writer w) throws IOException {
    if (debugHTML) {
      System.err.println("writeHTML");
      System.err.println("find row groups");
    }
    // find row groups (to be <tbody>s)
    // these need to be such that no cell spans across different groups
    int[] rowgroupSpans = new int[numRows];
    boolean haveRowgroups = false;
    for (int i = 0; i < numRows; ) {
      int maxRowspan = 0;
      for (RectangularTextContainer cell : rows.get(i)) {
	Dimension span = Cell.getSpanOf(cell);
	if (span.height > maxRowspan)
	  maxRowspan = span.height;
      }
      // row spans in different columns may overlap in nontrivial ways; make
      // sure we keep expanding the span of this row group to include spans of
      // non-first rows in the group, until no cell in the group extends
      // outside the group
      for (int i2 = i + 1; i2 < numRows && i2 < i + maxRowspan; i2++) {
	for (RectangularTextContainer cell : rows.get(i2)) {
	  Dimension span = Cell.getSpanOf(cell);
	  int maybeNewRowspan = span.height + i2 - i;
	  if (maybeNewRowspan > maxRowspan)
	    maxRowspan = maybeNewRowspan;
	}
      }
      if (maxRowspan > 1)
	haveRowgroups = true;
      rowgroupSpans[i] = maxRowspan;
      i++;
      for (int k = 1; k < maxRowspan; k++, i++) {
	rowgroupSpans[i] = 0;
      }
    }
    // start with single-column colgroups
    int[] colgroupSpans = new int[numCols];
    for (int j = 0; j < numCols; j++)
      colgroupSpans[j] = 1;
    if (debugHTML) System.err.println("find non-default headings");
    // find headings with non-default headingFor selections, and use them to
    // make bigger row and column groups; also, find the number of top rows
    // that contain column headings (whether default or not) so we can make
    // that group a <thead>
    int numHeadingRows = 0; // number of top rows that contain column headings
    // for each heading cell, with its indices and properties...
    int i = 0;
    for (List<RectangularTextContainer> row : rows) {
      int j = 0;
      for (RectangularTextContainer cell : row) {
	CellProperties props = Cell.getPropertiesOf(cell);
	if (props.isHeading()) {
	  // get default and actual values of headingFor selection
	  Dimension span = Cell.getSpanOf(cell);
	  TableSelection dhf = props.getDefaultHeadingFor(this, i, j, span);
	  TableSelection ahf = props.headingFor;
	  // if they disagree, update row and column groups
	  if (!dhf.equals(ahf)) {
	    if (debugHTML) System.err.println("found non-default heading at " + i + ", " + j);
	    if (props.type == CellProperties.Type.COLUMN_HEADING) {
	      addGroup(colgroupSpans, ahf.firstCol, ahf.lastCol);
	    // else ROW_HEADING
	    } else if (ahf.firstRow != ahf.lastRow) {
	      addGroup(rowgroupSpans, ahf.firstRow, ahf.lastRow);
	      haveRowgroups = true;
	    }
	    if (debugHTML) System.err.println("maybe added row/col group for it?");
	  }
	  // if this is a column heading near the top, update numHeadingRows
	  if (i < 10 && props.type == CellProperties.Type.COLUMN_HEADING) {
	    int after = i + span.height;
	    if (after > numHeadingRows)
	      numHeadingRows = after;
	  }
	}
	j++;
      }
      i++;
    }
    if (debugHTML) System.err.println("reconcile numHeadingRows and rowgroupSpans");
    // make sure numHeadingRows and rowgroupSpans agree
    if (haveRowgroups && numHeadingRows > 0) {
      if (debugHTML) System.err.println("  numHeadingRows was " + numHeadingRows);
      // step through spans until we get past the heading rows, zeroing them
      // out as we go
      i = 0;
      while (i < numHeadingRows) {
	int span = rowgroupSpans[i];
	if (debugHTML) System.err.println("  gobbling rowgroup with span " + span);
	rowgroupSpans[i] = 0;
	i += span;
      }
      // extend the heading span to meet the one after it, and record it as the
      // first span
      numHeadingRows = i;
      rowgroupSpans[0] = numHeadingRows;
      if (debugHTML) System.err.println("  numHeadingRows is now " + numHeadingRows);
    }
    if (debugHTML) System.err.println("consolidate single-row groups");
    // turn long stretches of single rows into their own row groups, to avoid
    // spamming <tbody>
    int firstOne = 0; // first index of a row with span 1 in the current group
    for (i = 0; i < numRows; i++) {
      if (rowgroupSpans[i] != 1) {
	if (firstOne < i - 1) { // found more than one row with span 1
	  // set the span of the first in the group
	  rowgroupSpans[firstOne] = i - firstOne;
	  // zero the spans of the rest of the group
	  for (int k = firstOne + 1; k < i; k++) {
	    rowgroupSpans[k] = 0;
	  }
	}
	firstOne = i + 1;
      }
    }
    // handle stretch of single rows at the end
    if (firstOne < i - 1) {
      rowgroupSpans[firstOne] = i - firstOne;
      for (int k = firstOne + 1; k < i; k++) {
	rowgroupSpans[k] = 0;
      }
    }
    if (debugHTML) {
      System.err.println("rowgroupSpans = ");
      for (i = 0; i < numRows; i++) System.err.println("  " + i + ": " + rowgroupSpans[i]);
      System.err.println("colgroupSpans = ");
      for (i = 0; i < numCols; i++) System.err.println("  " + i + ": " + colgroupSpans[i]);
      System.err.println("build cell2headings");
    }
    // map cell coordinates to the coordinates of each heading for that cell
    List<List<List<int[]>>> cell2headings =
      new ArrayList<List<List<int[]>>>(numRows);
    for (i = 0; i < numRows; i++) {
      List<List<int[]>> row = new ArrayList<List<int[]>>(numCols);
      cell2headings.add(row);
      for (int j = 0; j < numCols; j++)
	row.add(new LinkedList<int[]>());
    }
    i = 0;
    for (List<RectangularTextContainer> row : rows) {
      int j = 0;
      for (RectangularTextContainer cell : row) {
	CellProperties props = Cell.getPropertiesOf(cell);
	if (props.isHeading()) {
	  TableSelection hf = props.headingFor;
	  int[] hCoords = new int[] {i, j};
	  for (int i2 = hf.firstRow; i2 <= hf.lastRow; i2++)
	    for (int j2 = hf.firstCol; j2 <= hf.lastCol; j2++)
	      if ((!(i == i2 && j == j2)) && // not the same cell
		  hf.completelyContains(i2, j2)) // not spanning the edge
		cell2headings.get(i2).get(j2).add(hCoords);
	}
	j++;
      }
      i++;
    }
    if (debugHTML) System.err.println("output table");
    //// begin table output ////
    HTMLBuilder out = new HTMLBuilder();
    out.beginTable();
    if (debugHTML) System.err.println("output col(group)s");
    // output <col> and <colgroup> elements
    for (int j = 0; j < numCols; j++) {
      if (colgroupSpans[j] == 1) {
	out.html("<col>");
      } else {
	out.html("<colgroup").attr("span", ""+colgroupSpans[j]).html(">");
      }
    }
    if (debugHTML) System.err.println("output thead if any");
    if (numHeadingRows > 0)
      out.html("<thead>");
    i = 0;
    for (List<RectangularTextContainer> row : rows) {
      // output <tbody> tags for row headers
      if (rowgroupSpans[i] > 0 && !(numHeadingRows > 0 && i == 0))
	out.html("<tbody>");
      out.beginTR();
      int j = 0;
      for (RectangularTextContainer cell : row) {
//	if (debugHTML) System.err.println("output cell " + i + ", " + j);
	Dimension span = Cell.getSpanOf(cell);
	if (span.width > 0 && span.height > 0) {
	  CellProperties props = Cell.getPropertiesOf(cell);
	  String tooltip = props.getToolTipText();
	  boolean isHeading = props.isHeading();
	  //
	  // use id, headers, scope attributes to make sure the relationship
	  // between a data cell and the headings that apply to it is correct,
	  // while avoiding excessive repetition of heading ids by relying on
	  // HTML/headingFor defaults and the group spans we computed above
	  //
	  String id = null;
	  String scope = null;
	  String headers = null;
//	  if (debugHTML) System.err.println("get html default headings");
	  // get the default headings according to HTML, and the actual
	  // headings according to cell2headings
	  List<int[]> defaultHeadings =
	    new ArrayList<int[]>(
	      htmlDefaultHeadings(i, j, rowgroupSpans, colgroupSpans)
	    );
	  pruneHeadingsList(defaultHeadings);
//	  if (debugHTML) System.err.println("get actual headings");
          List<int[]> actualHeadings = cell2headings.get(i).get(j);
	  pruneHeadingsList(actualHeadings);
//	  if (debugHTML) System.err.println("add headers attribute if necessary");
	  // if they're different, put the actual headings in the headers
	  // attribute
	  if (!listsEqual(defaultHeadings, actualHeadings,
			  new CellCoordinateComparator())) {
	    StringBuilder hsb = new StringBuilder();
	    boolean first = true;
	    for (int[] h : actualHeadings) {
	      if (first) {
		first = false;
	      } else {
		hsb.append(" ");
	      }
	      hsb.append(cellID(h[0], h[1]));
	    }
	    /*if (debugHTML) {
	      hsb.append("[defaults: ");
	      first = true;
	      for (int[] h : defaultHeadings) {
		if (first) {
		  first = false;
		} else {
		  hsb.append(" ");
		}
		hsb.append(cellID(h[0], h[1]));
	      }
	      hsb.append("]");
	    }*/
	    headers = hsb.toString();
	  }
//	  if (debugHTML) System.err.println("add scope and id attributes if necessary");
	  // if this cell is itself a heading
	  if (isHeading) {
	    // add its scope if appropriate
	    scope =
	      getHeadingScope(i, j, span, props, rowgroupSpans, colgroupSpans);
	    // add id attribute so other cells can refer to this heading in
	    // their headers attributes
	    id = cellID(i, j);
	  }
//	  if (debugHTML) System.err.println("finish outputting cell");
	  out.beginCell(span, tooltip, isHeading, id, scope, headers);
	  Cell.getHTMLOf(cell, out);
	  out.endCell(isHeading);
	}
	j++;
      }
      out.endTR();
      i++;
    }
    out.endTable();
    out.script(annotationTemplateJS);
    String title = id;
    HTMLBuilder meta = new HTMLBuilder();
    if (origin != null) {
      Page page = origin.getPage();
      Document doc = page.getDocument();
      PDDocumentInformation info = doc.getPDDocument().getDocumentInformation();
      String pdfTitle = info.getTitle();
      if (pdfTitle != null)
	title = "table from page " + (page.getPageIndex() + 1) +
		" of " + pdfTitle;
      meta.
	meta("generator", "PDFExtractor").
        link("original-document", "application/pdf", "file://" + 
	     doc.getPDFFile().getAbsolutePath()).
	meta("first-page-index", ""+page.getPageIndex()).
	meta("first-region-bbox",
	     "" + origin.getMinX() + "," + origin.getMinY() + "," +
	     origin.getAbsWidth() + "," + origin.getAbsHeight()).
	meta("author", info.getAuthor()).
	meta("description", info.getSubject()).
	meta("keywords", info.getKeywords());
      // creation/modification dates
      // NOTE: converting to Instant before String ensures we use ISO format
      Calendar created = info.getCreationDate();
      if (created != null)
	meta.meta("created", created.toInstant().toString());
      Calendar modified = info.getModificationDate();
      if (modified != null)
	meta.meta("modified", modified.toInstant().toString());
    }
    w.write(out.toProperDocumentString(title, meta.toFragmentString()));
  }

  //// end writeHTML-related stuff ////

  /** Write the table in the given format ("text/csv" or "text/html") using the
   * given Writer.
   */
  public void write(String format, Writer w) throws IOException {
    if (format.equals("text/csv")) {
      writeCSV(w);
    } else if (format.equals("text/html")) {
      writeHTML(w);
    } else {
      throw new IOException("unknown table save format: " + format);
    }
  }

  /** Write the table in the given format (see above) to the given file. */
  public void write(String format, File file) throws IOException {
    FileWriter w = new FileWriter(file);
    write(format, w);
    w.close();
  }

  void computeColBoundXs() {
    List<DeleteColumns> deleteEmptyColumns = new ArrayList<DeleteColumns>();
    // fill colBoundXs with the minimum X coordinate of each column's cell
    // regions (among non-empty cells; empty cells have 0 width)
    colBoundXs = new ArrayList<Float>(numCols);
    // start with the maximum X coordinate for the page
    float minX = origin.getPage().getPDBBox().getUpperRightX();
    // iterate backwards over columns so that the colBoundXs we find are
    // monotonically non-increasing
    for (int j = numCols - 1; j >= 0; j--) {
      boolean empty = true;
      for (List<RectangularTextContainer> row : rows) {
	RectangularTextContainer cell = row.get(j);
	if (!Cell.isEmpty(cell)) {
	  empty = false;
	  float x = (float)cell.getX();
	  if (x < minX) minX = x;
	}
      }
      if (empty) {
	System.err.println("WARNING: Tabula gave us a column (" + j + ") with no non-empty cells; deleting");
	deleteEmptyColumns.add(new DeleteColumns(j, j));
      }
      colBoundXs.add(minX - 1); // subtracting 1 makes it more stable
    }
    // reverse since we found them backwards
    Collections.reverse(colBoundXs);
    // delete any empty columns we found
    for (DeleteColumns dc : deleteEmptyColumns) {
      try {
	edit(dc);
      // exceptions should never happen with these, but we still need to catch
      // them
      } catch (CWCException ex) {
	throw new RuntimeException("WTF", ex);
      } catch (BadEdit ex) {
	throw new RuntimeException("WTF", ex);
      }
    }
  }
  
  /** Get the Rulings on the page this table came from, in terms of pixel
   * coordinates.
   */
  List<Ruling> getPixelRulings() {
    return origin.toTabulaPage().getRulings();
  }

  /** Return a Ruling in terms of cell coordinates (x=col,y=row) corresponding
   * to the given Ruling in pixel coordinates, or null if we should ignore this
   * Ruling. If allowDegenerateRulings is true, rulings spanning the whole
   * table will be returned, and rulings covering even only a single cell will
   * be returned; these latter will have width or height 0.1 in order to
   * indicate a horizontal or vertical ruling, respectively (so that the
   * horizontal(), vertical(), and oblique() tests work properly).
   */
  Ruling pixelToCellRuling(Ruling pr, boolean allowDegenerateRulings) {
    if (pr.oblique()) return null;
    float pos = pr.getPosition();
    float start = pr.getStart();
    float end = pr.getEnd();
    // make sure start and end are in order
    if (start > end) {
      float tmp = start;
      start = end;
      end = tmp;
    }
    Ruling cr;
    if (pr.horizontal()) {
      int j1 = xCoordToColIndex(start);
      if (j1 > 0 && !xIsAfterCol(start, j1-1)) j1--;
      if (j1 < 0) j1 = 0; // clamp
      int j2 = xCoordToColIndex(end);
      if (j2 > 0 && xIsAfterCol(end, j2-1)) j2--;
      if (j2 >= numCols) j2 = numCols-1; // clamp
      if ((!allowDegenerateRulings) &&
	  ((j1 == 0 && j2 == numCols-1) || // covers all columns
	   j1 == j2)) // covers only a single cell
	return null;
      int i = yCoordToRowIndex(pos) - 1;
      if (i < 0) i = 0; // clamp
      cr = new Ruling(i, j1, (j2 == j1 ? 0.1f : (j2 - j1)), 0);
    } else { // vertical
      int i1 = yCoordToRowIndex(start);
      if (i1 > 0 && !yIsAfterRow(start, i1-1)) i1--;
      if (i1 < 0) i1 = 0; // clamp
      int i2 = yCoordToRowIndex(end);
      if (i2 > 0 && yIsAfterRow(end, i2-1)) i2--;
      if (i2 >= numRows) i2 = numRows-1; // clamp
      if ((!allowDegenerateRulings) &&
	  ((i1 == 0 && i2 == numRows-1) || // covers all rows
	   i1 == i2)) // covers only a single cell
	return null;
      int j = xCoordToColIndex(pos) - 1;
      if (j < 0) j = 0; // clamp
      cr = new Ruling(i1, j, 0, (i2 == i1 ? 0.1f : (i2 - i1)));
    }
    return cr;
  }

  final static boolean debugRulings = false;

  /** Get Rulings from Tabula and convert them from pixel coordinates to cell
   * coordinates (x=col,y=row), dropping any oblique Rulings, and any that span
   * all rows or all columns (like those it adds around the perimeter of the
   * table area).
   */
  void computeRulings() {
    if (debugRulings) {
      System.err.println("computeRulings()");
      System.err.println("  numRows=" + numRows + "; numCols=" + numCols);
    }
    List<Ruling> pixelRulings = getPixelRulings();
    List<Ruling> cellRulings = new ArrayList<Ruling>(pixelRulings.size() - 4);
    for (Ruling pr : pixelRulings) {
      Ruling cr = pixelToCellRuling(pr, false);
      if (cr == null) continue;
      if (debugRulings)
	System.err.println("  add ruling " + rulingToKQML(cr).toString());
      cellRulings.add(cr);
    }
    rulings = cellRulings;
  }

  public void setTabulaTable(technology.tabula.Table tabulaTable) {
    numRows = tabulaTable.getRowCount();
    numCols = tabulaTable.getColCount();
    rows = tabulaTable.getRows();
    computeRulings();
    if (colBoundXs == null) {
      computeColBoundXs();
    }
  }

  public void resetRowsToOrigin() {
    numRows = originRows.size();
    numCols = originRows.get(0).size();
    // copy originRows into rows
    rows = new ArrayList<List<RectangularTextContainer>>(numRows);
    for (int i = 0; i < numRows; i++)
      rows.add(new ArrayList<RectangularTextContainer>(originRows.get(i)));
  }

  public static technology.tabula.Table extractTabulaTable(Region region, List<Float> colBoundXs) {
    BasicExtractionAlgorithm extractor = new BasicExtractionAlgorithm();
    List<technology.tabula.Table> tables =
      (colBoundXs == null ?
        extractor.extract(region.toTabulaPage()) :
        extractor.extract(region.toTabulaPage(), colBoundXs));
    // NOTE: basic extractor always returns exactly 1 table
    return tables.get(0);
  }

  public static technology.tabula.Table extractTabulaTable(Region region) {
    return extractTabulaTable(region, null);
  }

  /** Get the cell whose top left corner is anchored at the given row and
   * column.
   */
  public RectangularTextContainer getCellAt(int row, int column) {
    return rows.get(row).get(column);
  }

  public void setCellAt(int row, int column, RectangularTextContainer newCell) {
    rows.get(row).set(column, newCell);
  }

  /** Return the row and column indices of the given cell, or null if it isn't
   * in the table. Point.x = column, .y = row.
   */
  public Point findCell(RectangularTextContainer searchCell) {
    Point ret = new Point(0,0);
    for (List<RectangularTextContainer> row : rows) {
      ret.x = 0;
      for (RectangularTextContainer cell : row) {
	if (searchCell == cell)
	  return ret;
	ret.x++;
      }
      ret.y++;
    }
    return null;
  }

  public Dimension getSpanAt(int row, int column) {
    return Cell.getSpanOf(getCellAt(row, column));
  }

  public boolean spanIsEmpty(int row, int column) {
    Dimension span = getSpanAt(row, column);
    return (span.width == 0 || span.height == 0);
  }

  /** Get the cell that covers the slot at the given row and column. This might
   * not be the same as getCellAt(row,column) because cells might span a number
   * of slots in each direction (including 0).
   */
  public RectangularTextContainer getCellCovering(int row, int column) {
    int i = row, j = column;
    // search rows backwards for a nonempty cell
    while (i > 0 && spanIsEmpty(i, j)) i--;
    // if the cell we found doesn't cover row,column
    if (i + getSpanAt(i, j).height - 1 < row) {
      // go back into the empty region
      i++;
      // search columns backwards for a nonempty cell
      while (j > 0 && spanIsEmpty(i, j)) j--;
    }
    // TODO? check that this cell really does cover the requested slot
    return getCellAt(i, j);
  }

  /** Is the given X coordinate completely to the left of all nonempty cells in
   * column j? If column j is completely empty, recurse on j+1.
   */
  public boolean xIsBeforeCol(float x, int j) {
    // NOTE: We can't use colBoundXs here because those are the original column
    // boundaries used by Tabula, before any edits that decrease the number of
    // columns (DeleteColumns/MergeColumns). Another reason is that this is
    // used by computeRulings, which is called before computeColBoundXs because
    // when we auto-delete blank columns, DeleteColumns adjusts the rulings, so
    // they need to exist beforehand. (phew!)
    // NOTE 2: Even though we remove empty columns that Tabula (for some
    // reason?) gives us from the start, there still may be some empty columns
    // created by editing (e.g. by removing the only rows that have nonempty
    // cells in those columns).
    boolean empty = true;
    for (List<RectangularTextContainer> row : rows) {
      RectangularTextContainer cell = row.get(j);
      if (Cell.isEmpty(cell)) continue;
      empty = false;
      if (cell.getLeft() <= x)
	return false;
    }
    if (empty && j < numCols - 1) return xIsBeforeCol(x, j+1);
    return true;
  }

  /** Is the given X coordinate completely to the right of all nonempty cells
   * in column j? If column j is completely empty, recurse on j-1.
   */
  public boolean xIsAfterCol(float x, int j) {
    boolean empty = true;
    for (List<RectangularTextContainer> row : rows) {
      RectangularTextContainer cell = row.get(j);
      if (Cell.isEmpty(cell)) continue;
      empty = false;
      if (cell.getRight() >= x)
	return false;
    }
    if (empty && j > 0) return xIsAfterCol(x, j-1);
    return true;
  }

  /** Is the given Y coordinate completely above all nonempty cells in row i?
   * If row i is completely empty, recurse on i+1.
   */
  public boolean yIsBeforeRow(float y, int i) {
    boolean empty = true;
    for (RectangularTextContainer cell : rows.get(i)) {
      if (Cell.isEmpty(cell)) continue;
      empty = false;
      if (y >= cell.getTop())
	return false;
    }
    if (empty && i < numRows - 1) return yIsBeforeRow(y, i+1);
    return true;
  }

  /** Is the given Y coordinate completely below all nonempty cells in row i?
   * If row i is completely empty, recurse on i-1.
   */
  public boolean yIsAfterRow(float y, int i) {
    boolean empty = true;
    for (RectangularTextContainer cell : rows.get(i)) {
      if (Cell.isEmpty(cell)) continue;
      empty = false;
      if (cell.getBottom() >= y)
	return false;
    }
    if (empty && i > 0) return yIsAfterRow(y, i-1);
    return true;
  }

  /** Find the index of the column after the given X coordinate. If all columns
   * are at least partly before the given X coordinate, return numCols.
   */
  public int xCoordToColIndex(float x) {
    // could do binary search, but linear is simpler to code
    int j = 0;
    for (; j < numCols; j++)
      if (xIsBeforeCol(x, j))
	break;
    return j;
  }

  /** Find the index of the row after the given Y coordinate. If all rows are
   * at least partly before the given Y coordinate, return numRows.
   */
  public int yCoordToRowIndex(float y) {
    int i = 0;
    for (; i < numRows; i++) {
      if (yIsBeforeRow(y, i))
	break;
    }
    return i;
  }

  /** Attempt to automatically detect cells that should be merged, and merge
   * them. Return the list of MergeCells edits that were actually performed.
   */
  public List<Edit> autoMergeCells() {
    List<Edit> ret = new LinkedList<Edit>();
    // convert rulings to MergeCells edits and apply them, ignoring any that
    // fail
    for (Ruling r : rulings) {
      try {
	MergeCells mc = mergeCellsFromRuling(r);
	//System.err.println(mc.toKQML().toString());
	edit(mc);
	ret.add(mc);
      } catch (BadEdit ex) {
	System.err.println("Ignoring " + rulingToKQML(r).toString() + " because it turned into a bad MergeCells edit: " + ex.getMessage());
      } catch (CWCException ex) {
	System.err.println("This should never happen.");
	ex.printStackTrace();
      }
    }
    // merge cells horizontally separated by less than a space width (again
    // making MergeCells edits and ignoring failures)
    // TODO? avoid doing such mergers where there is a vertical ruling (even one cell high) separating them
    int i = 0;
    for (List<RectangularTextContainer> row : rows) {
      TextElement prevChunkLastElement = null;
      int firstCol = 0;
      int j = 0;
      for (RectangularTextContainer cell : row) {
	List<TextElement> elements = new ArrayList<TextElement>();
	if (cell instanceof TextChunk) { // not MergedCell or EditedCell
	  elements = ((TextChunk)cell).getTextElements();
	} else if (cell instanceof EditedCell) {
	  RectangularTextContainer<TextElement> original =
	    ((EditedCell)cell).original;
	  if (original instanceof TextChunk)
	    elements = ((TextChunk)original).getTextElements();
	}
	if (elements.size() > 0) {
	  if (prevChunkLastElement == null) {
	    firstCol = j;
	  } else {
	    TextElement thisChunkFirstElement = elements.get(0);
	    float spaceWidth =
	      Math.max(prevChunkLastElement.getWidthOfSpace(),
		       thisChunkFirstElement.getWidthOfSpace());
	    double hSep =
	      thisChunkFirstElement.getMinX() -
	      prevChunkLastElement.getMaxX();
	    if (hSep > spaceWidth) { // past the end of a merger (potentially)
	      if (firstCol < j - 1) { // more than one cell to merge
		try {
		  //System.err.println("attempting to merge nearby cells on row " + i + " from column " + firstCol + " to column " + (j-1));
		  MergeCells mc = new MergeCells(i, firstCol, i, j - 1);
		  edit(mc);
		  ret.add(mc);
		} catch (BadEdit ex) {
		  System.err.println("Ignoring mergeable chunks on row " + i + " from column " + firstCol + " to column " + (j-1) + " because it turned into a bad MergeCells edit: " + ex.getMessage());
		} catch (CWCException ex) {
		  System.err.println("This should never happen.");
		  ex.printStackTrace();
		}
	      }
	      firstCol = j;
	    }
	  }
	  prevChunkLastElement = elements.get(elements.size() - 1);
	} else { // no elements in this chunk, or it's a MergedCell, or an EditedCell of a MergedCell; can't merge further
	  prevChunkLastElement = null;
	}
	j++;
      }
      i++;
    }
    return ret;
  }

  final static boolean debugASC = false;

  /** A potential automatic SplitColumn edit followed by MergeCells edits. */
  public class AutoSplitColumn implements Comparable<AutoSplitColumn> {
    public final float newColBoundX;
    public int col;
    public BitSet splitRows; // true for i that should stay split, false=>merge
    public boolean splitSucceeded;

    public AutoSplitColumn(float x, int j, BitSet splitRows) {
      newColBoundX = x;
      col = j;
      this.splitRows = splitRows;
      splitSucceeded = false;
    }

    public AutoSplitColumn(float x, int j) {
      this(x, j, new BitSet(numRows)); // initially all false
    }

    /** Ensure that we will split rows in the interval [i1,i2]. */
    public void addRuling(int i1, int i2) {
      for (int i = i1; i <= i2; i++)
	splitRows.set(i);
    }

    /** Order AutoSplitColumns by decreasing X, so we don't have to adjust the
     * column indexes (much) as we go.
     */
    @Override public int compareTo(AutoSplitColumn other) {
      return Float.compare(other.newColBoundX, newColBoundX);
    }

    @Override public boolean equals(Object other) {
      return ((other instanceof AutoSplitColumn) &&
              ((AutoSplitColumn)other).newColBoundX == newColBoundX);
    }

    @Override public int hashCode() {
      return Float.hashCode(newColBoundX);
    }

    /** Do the SplitColumn edit and return it if it succeeded. */
    public Edit splitColumn() {
      // first try to split the column
      try {
	SplitColumn sc = new SplitColumn(newColBoundX);
	edit(sc);
	splitSucceeded = true;
	return sc;
      } catch (BadEdit ex) {
	System.err.println("Ignoring auto-split column " + col + " at x " + newColBoundX + " because it turned into a bad SplitColumn edit: " + ex.getMessage());
      } catch (CWCException ex) {
	System.err.println("This should never happen.");
	ex.printStackTrace();
      }
      return null;
    }

    /** If the SplitColumns edit succeeded, do the MergeCells edits and return
     * those that succeed.
     */
    public List<Edit> mergeCells() {
      if (!splitSucceeded) return new ArrayList<Edit>();
      int numEdits = numRows - splitRows.cardinality();
      List<Edit> edits = new ArrayList<Edit>(numEdits);
      // if that succeeded, merge cells on each row that needs it
      for (int i = 0; i < numRows; i++) {
	if (splitRows.get(i)) continue;
	try {
	  MergeCells mc = new MergeCells(i, col, i, col+1);
	  edit(mc);
	  edits.add(mc);
	} catch (BadEdit ex) {
	  System.err.println("Ignoring unsplit row " + i + " of auto-split column " + col + " at x " + newColBoundX + " because it turned into a bad MergeCells edit: " + ex.getMessage());
	} catch (CWCException ex) {
	  System.err.println("This should never happen.");
	  ex.printStackTrace();
	}
      }
      return edits;
    }
  }

  /** Would it be OK to split the given cell at the given X coordinate, as part
   * of auto-split-columns?
   */
  static boolean isCellSplittableAtX(RectangularTextContainer cell, float splitX) {
    float minDistance = 1000; // minimum distance from right of glyph to splitX
    float maxWOS = 0; // maximum "width of space" measurement for a glyph
    for (Object o : cell.getTextElements()) {
      TextElement e = (TextElement)o;
      String text = e.getText();
      // skip whitespace glyphs
      if (!Pattern.compile("\\S").matcher(text).find())
	continue;
      float wos = (float)e.getWidthOfSpace();
      if ((!Float.isNaN(wos)) && maxWOS < wos) maxWOS = wos;
      float left = e.getLeft();
      float right = e.getRight();
//      if (debugASC) System.err.println("      text=" + text + "; left=" + left + "; wos=" + wos + "; right=" + right);
      float distance = 1000;
      if (left >= splitX) { // glyph is entirely to the right of splitX
	// we split on the left edge of a column detected by TextChunker, so
	// this is expected and doesn't matter
	continue;
      } else if (splitX >= right) { // entirely to the left
        distance = splitX - right;
      } else { // splitX splits the glyph
        distance = 0;
      }
      if (minDistance > distance) minDistance = distance;
//      if (debugASC) System.err.println("        distance=" + distance);
      if (distance == 0) break; // shortcut
    }
//    if (debugASC) System.err.println("      minDistance = " + minDistance + "; maxWOS = " + maxWOS + "; cell splittable = " + (minDistance > maxWOS));
    return (minDistance > maxWOS);
  }

  List<Edit> makeEditsForASCs(Map<Float,AutoSplitColumn> x2asc) {
    if (debugASC) System.err.println("making edits from " + x2asc.size() + " AutoSplitColumns");
    AutoSplitColumn[] ascs = new AutoSplitColumn[x2asc.size()];
    x2asc.values().toArray(ascs);
    Arrays.sort(ascs); // sort by descending X
    List<Edit> edits = new ArrayList<Edit>();
    // first, split all the columns
    int k = 0;
    for (AutoSplitColumn asc : ascs) {
      // skip if it would split less than half of the cells in the column
      if (asc.splitRows.cardinality() >= numRows / 2) {
	Edit sc = asc.splitColumn();
	if (sc != null) { // success
	  edits.add(sc);
	  // adjust col of all previous ascs
	  for (int l = k - 1; l >= 0; l--) {
	    ascs[l].col++;
	  }
	}
      }
      k++;
    }
    // then merge the cells we didn't want split
    // FIXME!!! need to merge mergecells edits from multiple adjacent ascs, or the left ones will be blocked by the right ones
    // . pass ascs array and index to mergeCells and let it decide whether to merge now and how far to the right to merge, based on splitRows agreement with adjacent ascs with the same column index (will it still be the same after the adjustment above?)
    for (AutoSplitColumn asc : ascs) {
      edits.addAll(asc.mergeCells());
    }
    return edits;
  }

  /** Attempt to automatically detect X coordinates to (partially) split
   * columns at, split them, and re-merge the parts that shouldn't be split
   * (spanning column headers). Return the list of SplitColumn and MergeCells
   * edits that were actually performed.
   */
  public List<Edit> autoSplitColumns() {
    // first, try splitting columns using ruling lines
    List<Ruling> pixelRulings = getPixelRulings();
    Map<Float,AutoSplitColumn> x2asc = new TreeMap<Float,AutoSplitColumn>();
    for (Ruling pr : pixelRulings) {
      Ruling cr = pixelToCellRuling(pr, true);
      // skip unless we have a vertical cell ruling
      if (cr == null || cr.horizontal()) continue;
      float x = pr.getPosition();
      int j = (int)cr.getPosition();
      // skip unless it actually splits the column somewhere
      if (xIsBeforeCol(x, j) || xIsAfterCol(x, j)) continue;
      // get the AutoSplitColumn we already made at this X, or make a new one
      AutoSplitColumn asc = x2asc.get(x);
      if (asc == null) {
	asc = new AutoSplitColumn(x, j);
	x2asc.put(x, asc);
      }
      // add this ruling to it
      int i1 = (int)cr.getStart();
      int i2 = (int)cr.getEnd();
      asc.addRuling(i1, i2);
    }
    List<Edit> edits = makeEditsForASCs(x2asc);
    if (edits.isEmpty()) {
      // splitting columns using ruling lines didn't do anything, give up on
      // those ascs
      x2asc.clear();
      // instead try using TextChunker to detect consistent gaps between glyphs
      // as column splits
      // first get the list of glyphs from the tabula "page" (really page area)
      // for the origin region
      // FIXME? do we need to make a copy of the document like
      // Page#detectParagraphRegions() does, or is this sufficient since we're
      // not calling detect()?
      technology.tabula.Page tabulaPage = origin.toTabulaPage();
      List<TextElement> glyphs = tabulaPage.getText();
      // make a horizontal (default) TextChunker and get columns from it
      TextChunker tc = new TextChunker();
      tc.addAll(glyphs);
      List<TextChunker> tcColumns = tc.split();
      // assign each tc column to a table column, and if it's the same as the
      // previous tc column, split the table column between the two tc columns
      int prevJ = -1;
      TextChunker prevTCCol = null;
      for (TextChunker tcCol : tcColumns) {
	// get the center X coordinate inside tcCol
	float cx = (float)((tcCol.minX + tcCol.maxX) / 2);
	// find the index of the column that cx is inside (-1 because not after)
	int j = xCoordToColIndex(cx) - 1;
	if (debugASC) System.err.println("tcCol: minX=" + tcCol.minX + "; cx=" + cx + "; maxX=" + tcCol.maxX + "; j = " + j);
	if (j >= 0 && j == prevJ) {
	  // This tc column maps to the same table column as the previous one,
	  // so we should split this table column between these two tc columns.
	  // Search for a good X coordinate to split at based on how many rows
	  // are splittable there.
	  // start searching for split on left edge of right tc column
	  float startX = (float)tcCol.minX;
	  // search as far right as the center of right tc column
	  float maxX = (float)((tcCol.minX + tcCol.maxX) / 2);
	  // ... and as far left as the center of left tc column
	  float minX = (float)((prevTCCol.minX + prevTCCol.maxX) / 2);
	  float bestLeftX = startX, bestRightX = startX;
	  int bestLeftCard = -1, bestRightCard = -1;
	  float x;
	  BitSet splitRows = new BitSet(numRows);
	  // search rightward for start of fall in cardinality of splitRows
	  // (i.e. the number of rows that would be split)
	  if (debugASC) System.err.println("  searching rightward from " + startX + " to " + maxX + " for best split x");
	  for (x = startX; x < maxX; x++) {
	    splitRows.clear();
	    // set which rows to split
	    for (int i = 0; i < numRows; i++) {
	      splitRows.set(i, isCellSplittableAtX(getCellAt(i, j), x));
	    }
	    int card = splitRows.cardinality();
	    if (debugASC) System.err.println("    x=" + x + "; card=" + card);
	    if (bestRightCard <= card) { // still not falling
	      bestRightCard = card;
	      bestRightX = x;
	    } else { // falling
	      break;
	    }
	  }
	  // search leftward for end of rise in cardinality of splitRows
	  if (debugASC) System.err.println("  searching leftward from " + (startX-1) + " to " + minX + " for best split x");
	  for (x = startX - 1; x > minX; x--) {
	    splitRows.clear();
	    // set which rows to split
	    for (int i = 0; i < numRows; i++) {
	      splitRows.set(i, isCellSplittableAtX(getCellAt(i, j), x));
	    }
	    int card = splitRows.cardinality();
	    if (debugASC) System.err.println("    x=" + x + "; card=" + card);
	    if (bestLeftCard < card) { // still rising
	      bestLeftCard = card;
	      bestLeftX = x;
	    } else if (bestLeftCard > card) { // falling
	      // NOTE: we break when we see falling, but we only saved the best
	      // X when we last saw rising. This lets us find the end of the
	      // rise even if it has steps in it.
	      break;
	    }
	  }
	  if (bestLeftCard > bestRightCard) {
	    // found strictly better split x to the left
	    if (debugASC) System.err.println("  leftward was better");
	    x = bestLeftX;
	  } else {
	    // found non-strictly better split x to the right
	    if (debugASC) System.err.println("  rightward was good enough");
	    x = bestRightX;
	  }
	  if (debugASC) System.err.println("  x=" + x);
	  // set splitRows one last time using the best x
	  // also make sure both new columns have some contents in split rows
	  splitRows.clear();
	  boolean leftHasContents = false, rightHasContents = false;
	  for (int i = 0; i < numRows; i++) {
	    RectangularTextContainer cell = getCellAt(i, j);
	    boolean splitRow = isCellSplittableAtX(cell, x);
	    splitRows.set(i, splitRow);
	    if (splitRow) {
	      for (Object o : cell.getTextElements()) {
		TextElement e = (TextElement)o;
		String text = e.getText();
		// skip whitespace glyphs
		if (!Pattern.compile("\\S").matcher(text).find())
		  continue;
		float c = (float)e.getCenterX();
		if (debugASC) {
		  if ((!leftHasContents) && c < x)
		    System.err.println("    first left contents are on row " + i + ": " + text);
		  if ((!rightHasContents) && c > x)
		    System.err.println("    first right contents are on row " + i + ": " + text);
		}
		if (c < x) leftHasContents = true;
		if (c > x) rightHasContents = true;
	      }
	    }
	  }
	  if (debugASC) System.err.println("  leftHasContents=" + leftHasContents + "; rightHasContents=" + rightHasContents + "; splitRows cardinality=" + splitRows.cardinality());
	  if (leftHasContents && rightHasContents) {
	    // make and add the ASC with the best x
	    AutoSplitColumn asc = new AutoSplitColumn(x, j, splitRows);
	    x2asc.put(x, asc);
	  }
	}
	prevJ = j;
	prevTCCol = tcCol;
      }
      edits = makeEditsForASCs(x2asc);
    }
    if (debugASC) System.err.println("returning " + edits.size() + " edits from autoSplitColumns");
    return edits;
  }

  //
  // editing steps
  //

  /** Apply the given edit and add it to the history. */
  public synchronized void edit(Edit ed) throws CWCException, BadEdit {
    if (ed instanceof SplitColumn) {
      splitColumns.add((SplitColumn)ed);
    } else {
      history.add(ed);
    }
    undoHistory.add(ed);
    if (!redoHistory.isEmpty()) {
      if (redoHistory.get(0) == ed) { // this is a redo
	redoHistory.remove(0);
      } else { // this is a new edit, clear redos
	redoHistory.clear();
      }
    }
    try {
      ed.apply();
      SwingUtilities.invokeLater(new Runnable() {
	@Override public void run() { fireTableStructureChanged(); }
      });
    } catch (CWCException ex) {
      // if applying the edit failed, remove it from the history and rethrow
      if (ed instanceof SplitColumn) {
	splitColumns.remove((SplitColumn)ed);
      } else {
	history.remove(ed);
      }
      throw ex;
    // sigh, java, y u no have union types?
    } catch (BadEdit ex) {
      if (ed instanceof SplitColumn) {
	splitColumns.remove((SplitColumn)ed);
      } else {
	history.remove(ed);
      }
      throw ex;
    }
  }

  /** Undoable contains two versions of an edit that can be undone: one to
   * remove from the undo history, and one to add to the redo history. They may
   * be different because other edits come after it in the undo history,
   * changing the row/col indices referenced in the edit. If the edit is undone
   * and then redone, it will move to a different position in the undo history,
   * so it might need different indices.
   */
  public class Undoable {
    /** The version of the edit to remove from the undo history. */
    public final Edit undo;
    /** The version of the edit to add to the redo history. */
    public final Edit redo;
    public Undoable(Edit undo, Edit redo) {
      this.undo = undo;
      this.redo = redo;
    }
  }

  /** Undo specific edits from somewhere in the history. Only works properly
   * in specific circumstances. Be careful using this.
   */
  public void undo(List<Undoable> edits) throws CWCException, BadEdit {
    for (Undoable ed : edits)
      undoHistory.remove(ed.undo);
    if (origin == null) { // synthetic table from KQML description
      // we know synthetic tables have no SplitColumn edits, so simply remove
      // the edits, reset to the original state, and redo all the other edits
      for (Undoable ed : edits)
	history.remove(ed.undo);
      resetRowsToOrigin();
      for (Edit e : history)
	e.apply();
    } else { // real table extracted from a page with Tabula
      for (Undoable ed : edits) {
	if (ed.undo instanceof SplitColumn) {
	  SplitColumn sc = (SplitColumn)ed.undo;
	  splitColumns.remove(sc);
	  // undo some of what SplitColumn#apply() does
	  int newColIndex = colBoundXs.indexOf(sc.newColBoundX);
	  colBoundXs.remove(newColIndex);
	  for (Edit e : history) {
	    int newNewColIndex = e.applyToColIndex(newColIndex);
	    e.deleteColumn(newColIndex);
	    newColIndex = newNewColIndex;
	  }
	} else {
	  history.remove(ed.undo);
	}
      }
      // rerun tabula (without first boundary because that would make a blank
      // column at the beginning)
      technology.tabula.Table tabulaTable =
	extractTabulaTable(origin, colBoundXs.subList(1, colBoundXs.size()));
      setTabulaTable(tabulaTable);
      // reapply the (non-SplitColumn) history to the newly extracted table
      for (Edit e : history) {
	// FIXME? theoretically, this should never throw, since we know it
	// worked the first time around; still, maybe we should catch BadEdit
	// here and clean things up before rethrowing, like in edit()?
	e.apply();
      }
    }
    for (Undoable ed : edits)
      redoHistory.add(0, ed.redo);
    SwingUtilities.invokeLater(new Runnable() {
      @Override public void run() { fireTableStructureChanged(); }
    });
  }

  /** Undo a single specific edit from somewhere in the history. Only works
   * properly in specific circumstances. Be careful using this.
   */
  public void undo(Undoable ed) throws CWCException, BadEdit {
    List<Undoable> edits = new ArrayList<Undoable>(1);
    edits.add(ed);
    undo(edits);
  }

  /** Undo the last edit that was applied, and return it. */
  public Edit undo() throws CWCException, BadEdit {
    if (undoHistory.isEmpty())
      throw new UnknownAction("undo");
    Edit ed = undoHistory.get(undoHistory.size()-1);
    undo(new Undoable(ed, ed));
    return ed;
  }
  public boolean canUndo() { return !undoHistory.isEmpty(); }

  /** Redo the last edit that was undone, and return it. */
  public Edit redo() throws CWCException, BadEdit {
    if (redoHistory.isEmpty())
      throw new UnknownAction("redo");
    Edit ed = redoHistory.get(0);
    edit(ed);
    return ed;
  }
  public boolean canRedo() { return !redoHistory.isEmpty(); }

  public static class BadEdit extends Exception {
    public BadEdit(String message) { super(message); }

    public void rethrowAsInvalidArgument(KQMLPerformative editTable) throws InvalidArgument {
      throw new InvalidArgument(editTable, ":edit", getMessage());
    }
  }

  /** Throw a BadEdit if any of the cells in the range were merged individually.
   * Most edits can't be performed sensibly in the presence of individually
   * merged cells.
   */
  public void ensureUnmergedCellsInRange(int firstRow, int firstCol, int lastRow, int lastCol) throws BadEdit {
    for (int row = firstRow; row <= lastRow; row++) {
      List<RectangularTextContainer> cells = rows.get(row);
      for (int col = firstCol; col <= lastCol; col++) {
	RectangularTextContainer cell = cells.get(col);
	if (Cell.wasMergedIndividually((HasText)cell)) {
	  throw new BadEdit("expected cell at row " + row + ", column " + col + " not to have been merged individually already");
	}
      }
    }
  }

  /** Adjust rulings when merging or deleting a range of rows or columns.
   * first - first row/column index in merged/deleted range
   * last - last row/column index in merged/deleted range
   * horizontal - true iff the range is horizontal, i.e. first and last are column indices
   * delete - true iff the range is deleted
   */
  void adjustRulings(int first, int last, boolean horizontal, boolean delete) {
    if (debugRulings)
      System.err.println("adjustRulings(" + first + ", " + last + ", " + horizontal + ", " + delete + ")");
    List<Ruling> newRulings = new ArrayList<Ruling>(rulings.size());
    // how many fewer rows/columns are there as a result of the
    // deletion/merging?
    int lengthReduction = last - first + (delete ? 1 : 0);
    for (Ruling r : rulings) {
      if (debugRulings)
	System.err.println("  ruling was " + rulingToKQML(r).toString());
      float pos = r.getPosition();
      float start = r.getStart();
      float end = r.getEnd();
      if (r.vertical() == horizontal) {
	// ruling is perpendicular to the range of merging/deletion, and
	// parallel to the things being merged/deleted
	if (delete && pos >= first && pos <= last) continue; // within deleted
	if (pos > last) pos -= lengthReduction; // after range
      } else {
	// ruling is parallel to the range of merging/deletion, and
	// perpendicular to the things being merged/deleted
	if (delete && start >= first && end <= last) continue; // within deleted
	if (start > last) {
	  start -= lengthReduction;
	} else if (start > first) {
	  start = first;
	}
	if (end > last) {
	  end -= lengthReduction;
	} else if (end >= first) {
	  end = first - (delete ? 1 : 0);
	}
      }
      // skip rulings that are now such that we wouldn't have included them
      // originally: spanning the whole table or just one cell
      if ((start == 0 && end == (r.vertical() ? numRows : numCols) - 1) ||
	  start == end)
	continue;
      r.setPosition(pos);
      r.setStart(start);
      r.setEnd(end);
      if (debugRulings)
	System.err.println("         now " + rulingToKQML(r).toString());
      newRulings.add(r);
    }
    rulings = newRulings;
  }

  /** Like adjustRulings, but for the headingFor property of each heading cell.
   */
  void adjustHeadingFors(int first, int last, boolean horizontal, boolean delete) {
    for (List<RectangularTextContainer> row : rows)
      for (RectangularTextContainer cell : row)
	Cell.adjustHeadingForOf(cell, first, last, horizontal, delete);
  }

  void adjustRulingsAndHeadingFors(int first, int last, boolean horizontal, boolean delete) {
    adjustRulings(first, last, horizontal, delete);
    adjustHeadingFors(first, last, horizontal, delete);
  }

  public abstract class Edit {
    /** Perform this editing operation on the parent Table. */
    public abstract void apply() throws CWCException, BadEdit;
    /** Adjust this edit to account for a new column being inserted at index
     * newCol (by SplitColumn).
     */
    public void insertColumn(int newCol) { }
    /** Undo the effect of insertColumn().*/
    public void deleteColumn(int oldCol) { }
    /** Return the new index of the same column after apply() has been called.
     * If the indexed column is now absent, return numCols.
     */
    public int applyToColIndex(int colIndex) { return colIndex; }
    /** Return a KQMLObject representing this edit. */
    public abstract KQMLObject toKQML();
    /** Return the table this edit was created for. */
    public Table getTable() { return Table.this; }
    // Edit subclasses E must also define these static fields:
    // public final static String kqmlVerb;
    // public final static String buttonLabel;
    // ...and these methods in Table:
    // public E eFromKQML(KQMLPerformative) throws CWCException;
    // public E eFromSelection(TableSelection) throws BadEdit;
  }

  /** Return the verb of a KQMLPerformative that represents an instance of
   * the given Edit subclass.
   */
  public static String getKQMLVerb(Class<? extends Edit> c) {
    try {
      return (String)c.getField("kqmlVerb").get(null);
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException("improperly defined Edit subclass", ex);
    }
  }

  /** Return the label to be used for the toolbar button that performs an
   * instance of the given Edit subclass.
   */
  public static String getButtonLabel(Class<? extends Edit> c) {
    try {
      return (String)c.getField("buttonLabel").get(null);
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException("improperly defined Edit subclass", ex);
    }
  }

  private static List<Class<? extends Edit>> editClasses;
  public static List<Class<? extends Edit>> getEditClasses() {
    if (editClasses == null) {
      editClasses = new ArrayList<Class<? extends Edit>>();
      for (Class<?> c : Table.class.getClasses()) {
	if (c.getSuperclass() == Edit.class) {
	  editClasses.add(c.asSubclass(Edit.class));
	}
      }
      // reverse (java, y u no have ArrayList#reverse()?)
      for (int i = 0, j = editClasses.size() - 1; i < j; i++, j--) {
	Class<? extends Edit> tmp = editClasses.get(i);
	editClasses.set(i, editClasses.get(j));
	editClasses.set(j, tmp);
      }
    }
    return editClasses;
  }

  public Edit editFromKQML(KQMLPerformative perf) throws CWCException {
    String verb = perf.getVerb().toLowerCase();
    for (Class<? extends Edit> c : getEditClasses()) {
      if (verb.equals(getKQMLVerb(c))) {
	String className = c.getSimpleName();
	String fromKQMLName =
	  className.substring(0,1).toLowerCase() +
	  className.substring(1) +
	  "FromKQML";
	try {
	  Method fromKQML =
	    Table.class.getMethod(fromKQMLName, KQMLPerformative.class);
	  return (Edit)fromKQML.invoke(this, perf);
	} catch (InvocationTargetException ex) { // unwrap any CWCExceptions
	  Throwable cause = ex.getCause();
	  if (cause instanceof CWCException) {
	    throw (CWCException)cause;
	  } else { // fromKQML threw something it wasn't supposed to
	    throw new RuntimeException("improperly defined Edit subclass", ex);
	  }
	} catch (ReflectiveOperationException ex) { // probably fromKQML undef.
	  throw new RuntimeException("improperly defined Edit subclass", ex);
	}
      }
    }
    throw new UnknownAction(verb);
  }

  /** Concatenate several tables vertically, this being the first. */
  public class MergeTables extends Edit {
    public final static String kqmlVerb = "merge-tables";
    public final static String buttonLabel = "Merge Tables";
    public final List<Table> others;
    public MergeTables(List<Table> others) { this.others = others; }
    @Override public void apply() throws CWCException, BadEdit {
      // make sure all tables have the same number of columns
      for (Table other : others) {
	if (other.numCols != numCols)
	  throw new InvalidArgument("merge-tables", ":other", "table with " + numCols + " columns", new KQMLToken(other.getID()));
      }
      for (Table other : others) {
	// copy the other table's rows
	for (List<RectangularTextContainer> otherRow : other.rows) {
	  List<RectangularTextContainer> newRow =
	    new ArrayList<RectangularTextContainer>(otherRow);
	  rows.add(newRow);
	}
	// copy the other table's rulings, shifting them down by the number of
	// rows we already had before this other table was merged
	for (Ruling otherRuling : other.rulings) {
	  Ruling newRuling = 
	    new Ruling(otherRuling.getTop() + numRows, otherRuling.getLeft(),
		       otherRuling.getWidth(), otherRuling.getHeight());
	  rulings.add(newRuling);
	}
	numRows += other.numRows;
      }
    }
    @Override public KQMLObject toKQML() {
      KQMLPerformative p = new KQMLPerformative(kqmlVerb);
      KQMLList othersIDs = new KQMLList();
      for (Table other : others) {
	othersIDs.add(other.getID());
      }
      p.setParameter(":others", othersIDs);
      return p;
    }
  }
  public MergeTables mergeTablesFromKQML(KQMLPerformative perf) throws CWCException {
    KQMLList tableIDs = Args.getTypedArgument(perf, ":others", KQMLList.class);
    List<Table> tables = new ArrayList<Table>(tableIDs.size());
    for (KQMLObject tableID : tableIDs) {
      if (!(tableID instanceof KQMLToken))
	throw new InvalidArgument("merge-tables", ":other", "table ID", tableID);
      Table table = HasID.get(tableID.toString(), Table.class);
      tables.add(table);
    }
    return new MergeTables(tables);
  }
  public MergeTables mergeTablesFromSelection(TableSelection sel) throws BadEdit {
    throw new BadEdit("can't make a MergeTables edit from a selection");
  }

  /** Delete all rows from firstRow to lastRow. */
  public class DeleteRows extends Edit {
    public final static String kqmlVerb = "delete-rows";
    public final static String buttonLabel = "Delete Rows";
    public final int firstRow, lastRow;
    public DeleteRows(int firstRow, int lastRow) {
      this.firstRow = firstRow;
      this.lastRow = lastRow;
    }
    @Override public void apply() throws CWCException, BadEdit {
      ensureUnmergedCellsInRange(firstRow, 0, lastRow, numCols-1);
      rows.subList(firstRow, lastRow + 1).clear();
      numRows -= lastRow - firstRow + 1;
      adjustRulingsAndHeadingFors(firstRow, lastRow, false, true);
    }
    @Override public KQMLObject toKQML() {
      KQMLPerformative p = new KQMLPerformative(kqmlVerb);
      p.setParameter(":first", Integer.toString(firstRow));
      p.setParameter(":last", Integer.toString(lastRow));
      return p;
    }
  }
  public DeleteRows deleteRowsFromKQML(KQMLPerformative perf) throws CWCException {
    int first = Args.getTypedArgument(perf, ":first", Integer.class);
    int last = Args.getTypedArgument(perf, ":last", Integer.class);
    if (first < 0 || first >= numRows)
      throw new InvalidArgument(perf, ":first", "integer in [0," + numRows + ")");
    if (last < 0 || last >= numRows)
      throw new InvalidArgument(perf, ":last", "integer in [0," + numRows + ")");
    return new DeleteRows(first, last);
  }
  public DeleteRows deleteRowsFromSelection(TableSelection sel) throws BadEdit {
    if (!sel.isRows())
      throw new BadEdit("expected a selection of whole rows");
    ensureUnmergedCellsInRange(sel.firstRow, sel.firstCol, sel.lastRow, sel.lastCol);
    return new DeleteRows(sel.firstRow, sel.lastRow);
  }

  /** Delete all columns from firstCol to lastCol. */
  public class DeleteColumns extends Edit {
    public final static String kqmlVerb = "delete-columns";
    public final static String buttonLabel = "Delete Columns";
    public int firstCol, lastCol; // not final because SplitColumn might happen
    public DeleteColumns(int firstCol, int lastCol) {
      this.firstCol = firstCol;
      this.lastCol = lastCol;
    }
    @Override public void apply() throws CWCException, BadEdit {
      ensureUnmergedCellsInRange(0, firstCol, numRows-1, lastCol);
      for (List<RectangularTextContainer> row : rows) {
	row.subList(firstCol, lastCol + 1).clear();
      }
      numCols -= lastCol - firstCol + 1;
      adjustRulingsAndHeadingFors(firstCol, lastCol, true, true);
    }
    @Override public void insertColumn(int newCol) {
      if (newCol <= firstCol) firstCol++;
      if (newCol <= lastCol) lastCol++;
    }
    @Override public void deleteColumn(int oldCol) {
      // TODO? throw if ==
      if (oldCol < firstCol) firstCol--;
      if (oldCol < lastCol) lastCol--;
    }
    @Override public int applyToColIndex(int colIndex) {
      if (colIndex >= firstCol && colIndex <= lastCol) { // we removed it
	return numCols;
      } else if (colIndex > lastCol) { // it was after what we removed
	return colIndex - (lastCol - firstCol + 1); // subtract number removed
      } else { // it was before what we removed
	return colIndex;
      }
    }
    @Override public KQMLObject toKQML() {
      KQMLPerformative p = new KQMLPerformative(kqmlVerb);
      p.setParameter(":first", Integer.toString(firstCol));
      p.setParameter(":last", Integer.toString(lastCol));
      return p;
    }
  }
  public DeleteColumns deleteColumnsFromKQML(KQMLPerformative perf) throws CWCException {
    int first = Args.getTypedArgument(perf, ":first", Integer.class);
    int last = Args.getTypedArgument(perf, ":last", Integer.class);
    if (first < 0 || first >= numCols)
      throw new InvalidArgument(perf, ":first", "integer in [0," + numCols + ")");
    if (last < 0 || last >= numCols)
      throw new InvalidArgument(perf, ":last", "integer in [0," + numCols + ")");
    return new DeleteColumns(first, last);
  }
  public DeleteColumns deleteColumnsFromSelection(TableSelection sel) throws BadEdit {
    if (!sel.isColumns())
      throw new BadEdit("expected a selection of whole columns");
    ensureUnmergedCellsInRange(sel.firstRow, sel.firstCol, sel.lastRow, sel.lastCol);
    return new DeleteColumns(sel.firstCol, sel.lastCol);
  }

  /** Turn all rows from firstRow to lastRow into a single row, with each cell
   * of the row being the column of cells joined with line breaks.
   */
  public class MergeRows extends Edit {
    public final static String kqmlVerb = "merge-rows";
    public final static String buttonLabel = "Merge Rows";
    public final int firstRow, lastRow;
    public MergeRows(int firstRow, int lastRow) {
      this.firstRow = firstRow;
      this.lastRow = lastRow;
    }
    @Override public void apply() throws CWCException, BadEdit {
      // FIXME? We might be able deal with individually merged cells in the merged range sensibly in some cases. This rejects them all. (See also MergeColumns.)
      ensureUnmergedCellsInRange(firstRow,0,lastRow,numCols-1);
      // replace first row with row of MergedCells
      List<RectangularTextContainer> newRow =
        new ArrayList<RectangularTextContainer>(numCols);
      for (int colIndex = 0; colIndex < numCols; colIndex++) {
	List<RectangularTextContainer> oldCells =
	  new ArrayList<RectangularTextContainer>(lastRow - firstRow + 1);
	for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
	  oldCells.add(getCellAt(rowIndex, colIndex));
	}
	newRow.add(MergedCell.fromRTCs(oldCells, false, false));
      }
      rows.set(firstRow, newRow);
      // remove all the non-first rows
      rows.subList(firstRow + 1, lastRow + 1).clear();
      numRows -= lastRow - firstRow;
      adjustRulingsAndHeadingFors(firstRow, lastRow, false, false);
    }
    @Override public KQMLObject toKQML() {
      KQMLPerformative p = new KQMLPerformative(kqmlVerb);
      p.setParameter(":first", Integer.toString(firstRow));
      p.setParameter(":last", Integer.toString(lastRow));
      return p;
    }
  }
  public MergeRows mergeRowsFromKQML(KQMLPerformative perf) throws CWCException {
    int first = Args.getTypedArgument(perf, ":first", Integer.class);
    int last = Args.getTypedArgument(perf, ":last", Integer.class);
    if (first < 0 || first >= numRows)
      throw new InvalidArgument(perf, ":first", "integer in [0," + numRows + ")");
    if (last < 0 || last >= numRows)
      throw new InvalidArgument(perf, ":last", "integer in [0," + numRows + ")");
    return new MergeRows(first, last);
  }
  public MergeRows mergeRowsFromSelection(TableSelection sel) throws BadEdit {
    if ((!sel.isRows()) || sel.isOneRow())
      throw new BadEdit("expected a selection of multiple whole rows");
    ensureUnmergedCellsInRange(sel.firstRow, sel.firstCol, sel.lastRow, sel.lastCol);
    return new MergeRows(sel.firstRow, sel.lastRow);
  }

  /** Turn all columns from firstCol to lastCol into a single column, with each
   * cell of the column being the row of cells joined with spaces.
   */
  public class MergeColumns extends Edit {
    public final static String kqmlVerb = "merge-columns";
    public final static String buttonLabel = "Merge Columns";
    public int firstCol, lastCol; // not final because SplitColumn might happen
    public MergeColumns(int firstCol, int lastCol) {
      this.firstCol = firstCol;
      this.lastCol = lastCol;
    }
    @Override public void apply() throws CWCException, BadEdit {
      ensureUnmergedCellsInRange(0,firstCol,numRows-1,lastCol);
      for (List<RectangularTextContainer> row : rows) {
	// replace first cell with a MergedCell
	List<RectangularTextContainer> oldCells =
	  row.subList(firstCol, lastCol + 1);
	row.set(firstCol, MergedCell.fromRTCs(oldCells, true, false));
	// remove all the non-first columns
	row.subList(firstCol + 1, lastCol + 1).clear();
      }
      numCols -= lastCol - firstCol;
      adjustRulingsAndHeadingFors(firstCol, lastCol, true, false);
    }
    @Override public void insertColumn(int newCol) {
      if (newCol <= firstCol) firstCol++;
      if (newCol <= lastCol) lastCol++;
    }
    @Override public void deleteColumn(int oldCol) {
      // TODO? throw if ==
      if (oldCol < firstCol) firstCol--;
      if (oldCol < lastCol) lastCol--;
    }
    @Override public int applyToColIndex(int colIndex) {
      if (colIndex >= firstCol && colIndex <= lastCol) { // we merged it
	return numCols;
      } else if (colIndex > lastCol) { // it was after what we merged
	return colIndex - (lastCol - firstCol); // subtract number merged away
      } else { // it was before what we merged
	return colIndex;
      }
    }
    @Override public KQMLObject toKQML() {
      KQMLPerformative p = new KQMLPerformative(kqmlVerb);
      p.setParameter(":first", Integer.toString(firstCol));
      p.setParameter(":last", Integer.toString(lastCol));
      return p;
    }
  }
  public MergeColumns mergeColumnsFromKQML(KQMLPerformative perf) throws CWCException {
    int first = Args.getTypedArgument(perf, ":first", Integer.class);
    int last = Args.getTypedArgument(perf, ":last", Integer.class);
    if (first < 0 || first >= numCols)
      throw new InvalidArgument(perf, ":first", "integer in [0," + numCols + ")");
    if (last < 0 || last >= numCols)
      throw new InvalidArgument(perf, ":last", "integer in [0," + numCols + ")");
    return new MergeColumns(first, last);
  }
  public MergeColumns mergeColumnsFromSelection(TableSelection sel) throws BadEdit {
    if ((!sel.isColumns()) || sel.isOneColumn())
      throw new BadEdit("expected a selection of multiple whole columns");
    ensureUnmergedCellsInRange(sel.firstRow, sel.firstCol, sel.lastRow, sel.lastCol);
    return new MergeColumns(sel.firstCol, sel.lastCol);
  }

  /** Turn a rectangular region of cells into one merged cell spanning the
   * original region, and fill the rest of the region's cells with degenerate
   * 0-span cells.
   */
  public class MergeCells extends Edit {
    public final static String kqmlVerb = "merge-cells";
    public final static String buttonLabel = "Merge Cells";
    public final int firstRow, lastRow;
    public int firstCol, lastCol; // not final because SplitColumn might happen
    public MergeCells(int firstRow, int firstCol, int lastRow, int lastCol) {
      this.firstRow = firstRow;
      this.firstCol = firstCol;
      this.lastRow = lastRow;
      this.lastCol = lastCol;
    }
    @Override public void apply() throws CWCException, BadEdit {
      ensureUnmergedCellsInRange(firstRow, firstCol, lastRow, lastCol);
      // make the new merged cell to be put in the top left
      MergedCell newCell = null;
      if (firstCol == lastCol) { // just merge rows
	List<HasText> cells = new ArrayList<HasText>(lastRow+1 - firstRow);
	for (int row = firstRow; row <= lastRow; row++) {
	  cells.add((HasText)getCellAt(row, firstCol));
	}
	newCell = new MergedCell(cells, false, true);
      } else if (firstRow == lastRow) { // just merge columns
	List<HasText> cells = new ArrayList<HasText>(lastCol+1 - firstCol);
	for (int col = firstCol; col <= lastCol; col++) {
	  cells.add((HasText)getCellAt(firstRow, col));
	}
	newCell = new MergedCell(cells, true, true);
      } else { // 2D merge (columns, then rows)
        List<HasText> rows = new ArrayList<HasText>(lastRow+1 - firstRow);
        for (int row = firstRow; row <= lastRow; row++) {
	  List<HasText> cells = new ArrayList<HasText>(lastCol+1 - firstCol);
	  for (int col = firstCol; col <= lastCol; col++) {
	    cells.add((HasText)getCellAt(row, col));
	  }
	  rows.add(new MergedCell(cells, true, true));
	}
	newCell = new MergedCell(rows, false, true);
      }
      // replace the cells in the region with either the newCell or degenerate
      // cells
      for (int row = firstRow; row <= lastRow; row++) {
	List<RectangularTextContainer> cells = rows.get(row);
	for (int col = firstCol; col <= lastCol; col++) {
	  RectangularTextContainer oldCell = cells.get(col);
	  cells.set(col,
	    (row == firstRow && col == firstCol) ? newCell :
	      new MergedCell((float)oldCell.getX(), (float)oldCell.getY())
	  );
	}
      }
    }
    @Override public void insertColumn(int newCol) {
      if (newCol <= firstCol) firstCol++;
      if (newCol <= lastCol) lastCol++;
    }
    @Override public void deleteColumn(int oldCol) {
      // TODO? throw if ==
      if (oldCol < firstCol) firstCol--;
      if (oldCol < lastCol) lastCol--;
    }
    @Override public KQMLObject toKQML() {
      KQMLPerformative p = new KQMLPerformative(kqmlVerb);
      p.setParameter(":first-row", Integer.toString(firstRow));
      p.setParameter(":first-column", Integer.toString(firstCol));
      p.setParameter(":last-row", Integer.toString(lastRow));
      p.setParameter(":last-column", Integer.toString(lastCol));
      return p;
    }
  }
  public MergeCells mergeCellsFromKQML(KQMLPerformative perf) throws CWCException {
    int firstRow = Args.getTypedArgument(perf, ":first-row", Integer.class);
    int firstCol = Args.getTypedArgument(perf, ":first-column", Integer.class);
    int lastRow = Args.getTypedArgument(perf, ":last-row", Integer.class);
    int lastCol = Args.getTypedArgument(perf, ":last-column", Integer.class);
    if (firstRow < 0 || firstRow >= numRows)
      throw new InvalidArgument(perf, ":first-row", "integer in [0," + numRows + ")");
    if (firstCol < 0 || firstCol >= numCols)
      throw new InvalidArgument(perf, ":first-column", "integer in [0," + numCols + ")");
    if (lastRow < 0 || lastRow >= numRows)
      throw new InvalidArgument(perf, ":last-row", "integer in [0," + numRows + ")");
    if (lastCol < 0 || lastCol >= numCols)
      throw new InvalidArgument(perf, ":last-column", "integer in [0," + numCols + ")");
    return new MergeCells(firstRow, firstCol, lastRow, lastCol);
  }
  public MergeCells mergeCellsFromSelection(TableSelection sel) throws BadEdit {
    if (sel.isEmpty() || sel.isOneCell())
      throw new BadEdit("expected more than one selected cell");
    ensureUnmergedCellsInRange(sel.firstRow, sel.firstCol, sel.lastRow, sel.lastCol);
    return new MergeCells(sel.firstRow, sel.firstCol, sel.lastRow, sel.lastCol);
  }
  public MergeCells mergeCellsFromRuling(Ruling r) throws BadEdit {
    int pos = (int)r.getPosition();
    int start = (int)r.getStart();
    int end = (int)r.getEnd();
    if (r.horizontal()) {
      if (pos < 0 || pos >= numCols ||
	  start < 0 || start >= numRows ||
	  end < 0 || end >= numRows)
	throw new BadEdit("ruling outside table bounds");
      return new MergeCells(pos, start, pos, end);
    } else { // vertical
      if (pos < 0 || pos >= numRows ||
	  start < 0 || start >= numCols ||
	  end < 0 || end >= numCols)
	throw new BadEdit("ruling outside table bounds");
      return new MergeCells(start, pos, end, pos);
    }
  }

  /** If the cell at row,col was individually merged, and that can be undone,
   * return the Undoable MergeCells edit that merged that cell; otherwise
   * return null. The value returned from this function can be passed to
   * undo().
   */
  public Undoable getUndoableMergeCellsAt(int row, int col) {
    RectangularTextContainer cell = getCellAt(row, col);
    if ((cell instanceof MergedCell) &&
	Cell.wasMergedIndividually((MergedCell)cell)) {
      // search backward through edit history for the relevant MergeCells
      // edit (and keep track of oldRow/oldCol offsets implied by other edits)
      MergeCells edit = null;
      int oldRow = row, oldCol = col;
      for (Iterator<Edit> i =
	     ((LinkedList<Edit>)undoHistory).descendingIterator();
           i.hasNext(); ) {
	Edit e = i.next();
	if (e instanceof MergeCells) {
	  edit = (MergeCells)e;
	  if (edit.firstRow == oldRow && edit.firstCol == oldCol)
	    break; // found it
	  edit = null; // didn't find it yet
	// adjust oldRow/oldCol when we look back past certain kinds of edit
	} else if (e instanceof DeleteRows) {
	  DeleteRows e2 = (DeleteRows)e;
	  if (e2.firstRow <= oldRow)
	    oldRow += e2.lastRow - e2.firstRow + 1;
	} else if (e instanceof MergeRows) {
	  MergeRows e2 = (MergeRows)e;
	  if (e2.firstRow < oldRow)
	    oldRow += e2.lastRow - e2.firstRow;
	} else if (e instanceof DeleteColumns) {
	  DeleteColumns e2 = (DeleteColumns)e;
	  if (e2.firstCol <= oldCol)
	    oldCol += e2.lastCol - e2.firstCol + 1;
	} else if (e instanceof MergeColumns) {
	  MergeColumns e2 = (MergeColumns)e;
	  if (e2.firstCol < oldCol)
	    oldCol += e2.lastCol - e2.firstCol;
	// give up if the cell we're interested in was edited after being
	// merged (this shouldn't really happen since then cell would be
	// instanceof EditedCell instead of MergedCell, but whatever)
	} else if (e instanceof EditCell) {
	  EditCell e2 = (EditCell)e;
	  if (e2.row == oldRow && e2.col == oldCol)
	    break;
	} else if (e instanceof EditCells) {
	  EditCells e2 = (EditCells)e;
	  if (e2.firstRow <= oldRow && oldRow <= e2.lastRow &&
	      e2.firstCol <= oldCol && oldCol <= e2.lastCol)
	    break;
	} else {
	  // other edit types are difficult or impossible to reach back
	  // past, so don't try
	  break;
	}
      }
      if (edit == null)
	return null;
      MergeCells redoEdit =
	new MergeCells(row, col,
		       // NOTE: we can get away with computing lastRow/Col like
		       // this instead of independently tracking them above
		       // because any edits that would change them differently
		       // would have been disallowed for involving an
		       // individually-merged cell
		       edit.lastRow + row - edit.firstRow,
		       edit.lastCol + col - edit.firstCol);
      return new Undoable(edit, redoEdit);
    }
    return null;
  }

  /** Select and reorder a subset of the rows of the table. */
  public class SelectRows extends Edit {
    public final static String kqmlVerb = "select-and-reorder-rows";
    public final static String buttonLabel = "Keep Rows";
    public final List<Integer> selectedRows;
    public SelectRows(List<Integer> selectedRows) {
      this.selectedRows = selectedRows;
    }
    @Override public void apply() throws CWCException, BadEdit {
      // FIXME? This rejects too much; it would be OK if all the rows in a
      // given merger were selected, adjacent and in their original order. But
      // that's complicated to check. (See also SelectColumns.)
      for (int rowIndex : selectedRows) {
	ensureUnmergedCellsInRange(rowIndex,0,rowIndex,numCols-1);
      }
      List<List<RectangularTextContainer>> oldRows = rows;
      rows = new ArrayList<List<RectangularTextContainer>>(selectedRows.size());
      for (int rowIndex : selectedRows) {
	rows.add(oldRows.get(rowIndex));
      }
      numRows = selectedRows.size();
      // keep horizontal rulings in the selected rows, but adjust their position
      List<Ruling> newRulings = new ArrayList<Ruling>();
      for (Ruling r : rulings) {
	if (r.horizontal()) {
	  int pos = (int)r.getPosition();
	  int newRowIndex = 0;
	  for (int oldRowIndex : selectedRows) {
	    if (oldRowIndex == pos) {
	      r.setPosition((float)newRowIndex);
	      newRulings.add(r);
	      break;
	    }
	    newRowIndex++;
	  }
	}
      }
      rulings = newRulings;
    }
    @Override public KQMLObject toKQML() {
      KQMLPerformative p = new KQMLPerformative(kqmlVerb);
      KQMLList rowsKQML = new KQMLList();
      for (int row : selectedRows) {
	rowsKQML.add(Integer.toString(row));
      }
      p.setParameter(":rows", rowsKQML);
      return p;
    }
  }
  public SelectRows selectRowsFromKQML(KQMLPerformative perf) throws CWCException {
    KQMLList rowIndicesKQML =
      Args.getTypedArgument(perf, ":rows", KQMLList.class);
    List<Integer> rowIndices = new ArrayList<Integer>(rowIndicesKQML.size());
    for (KQMLObject rowIndexKQML : rowIndicesKQML) {
      if (!(rowIndexKQML instanceof KQMLToken))
	throw new InvalidArgument("select-and-reorder-rows", ":row", "Integer", rowIndexKQML);
      int rowIndex = Integer.parseInt(rowIndexKQML.toString());
      if (rowIndex < 0 || rowIndex >= numRows)
	throw new InvalidArgument("select-and-reorder-rows", ":row", "integer in [0," + numRows + ")", rowIndexKQML);
      rowIndices.add(rowIndex);
    }
    return new SelectRows(rowIndices);
  }
  public SelectRows selectRowsFromSelection(TableSelection sel) throws BadEdit {
    throw new BadEdit("can't make a SelectRows edit from a selection");
  }

  /** Select and reorder a subset of the columns of the table. */
  public class SelectColumns extends Edit {
    public final static String kqmlVerb = "select-and-reorder-colunms";
    public final static String buttonLabel = "Keep Columns";
    public final List<Integer> selectedCols;
    public SelectColumns(List<Integer> selectedCols) {
      this.selectedCols = selectedCols;
    }
    @Override public void apply() throws CWCException, BadEdit {
      for (int colIndex : selectedCols) {
	ensureUnmergedCellsInRange(0,colIndex,numRows-1,colIndex);
      }
      for (int rowIndex = 0; rowIndex < numRows; rowIndex++) {
	List<RectangularTextContainer> oldRow = rows.get(rowIndex);
	List<RectangularTextContainer> newRow =
	  new ArrayList<RectangularTextContainer>(selectedCols.size());
	for (int colIndex : selectedCols) {
	  newRow.add(oldRow.get(colIndex));
	}
	rows.set(rowIndex, newRow);
      }
      numCols = selectedCols.size();
      // keep vertical rulings in the selected cols, but adjust their position
      List<Ruling> newRulings = new ArrayList<Ruling>();
      for (Ruling r : rulings) {
	if (r.vertical()) {
	  int pos = (int)r.getPosition();
	  int newColIndex = 0;
	  for (int oldColIndex : selectedCols) {
	    if (oldColIndex == pos) {
	      r.setPosition((float)newColIndex);
	      newRulings.add(r);
	      break;
	    }
	    newColIndex++;
	  }
	}
      }
      rulings = newRulings;
    }
    @Override public int applyToColIndex(int colIndex) {
      // TODO? check if colIndex is one of the selected columns? but it won't be in the only situation we call this method, when we've just added a SplitColumn edit.
      return selectedCols.size();
    }
    @Override public KQMLObject toKQML() {
      KQMLPerformative p = new KQMLPerformative(kqmlVerb);
      KQMLList colsKQML = new KQMLList();
      for (int col : selectedCols) {
	colsKQML.add(Integer.toString(col));
      }
      p.setParameter(":columns", colsKQML);
      return p;
    }
  }
  public SelectColumns selectColumnsFromKQML(KQMLPerformative perf) throws CWCException {
    KQMLList colIndicesKQML =
      Args.getTypedArgument(perf, ":columns", KQMLList.class);
    List<Integer> colIndices = new ArrayList<Integer>(colIndicesKQML.size());
    for (KQMLObject colIndexKQML : colIndicesKQML) {
      if (!(colIndexKQML instanceof KQMLToken))
	throw new InvalidArgument("select-and-reorder-columns", ":column", "Integer", colIndexKQML);
      int colIndex = Integer.parseInt(colIndexKQML.toString());
      if (colIndex < 0 || colIndex >= numCols)
	throw new InvalidArgument("select-and-reorder-columns", ":column", "integer in [0," + numCols + ")", colIndexKQML);
      colIndices.add(colIndex);
    }
    return new SelectColumns(colIndices);
  }
  public SelectColumns selectColumnsFromSelection(TableSelection sel) throws BadEdit {
    throw new BadEdit("can't make a SelectColumns edit from a selection");
  }

  /** Split a column by adding a new column boundary at X coordinate
   * newColBoundX before running Tabula.
   */
  public class SplitColumn extends Edit {
    public final static String kqmlVerb = "split-column";
    public final static String buttonLabel = "Split Column";
    public final float newColBoundX;
    public SplitColumn(float newColBoundX) { this.newColBoundX = newColBoundX;}
    @Override public void apply() throws CWCException, BadEdit {
      // before actually changing anything, check that there are no MergeTables
      // edits in the history, since they will surely fail after applying this
      // edit
      for (Edit e : history) {
	if (e instanceof MergeTables) {
	  throw new BadEdit("split-column conflicts with merge-tables already in edit history");
	}
      }
      // the index of the new column (to the right of the new boundary), in the
      // original table returned by tabula (not the current edited table)
      // NOTE: not this (gets the index in the edited table)
      //int newColIndex = xCoordToColIndex(newColBoundX);
      int newColIndex;
      for (newColIndex = 0;
           newColIndex < colBoundXs.size() && colBoundXs.get(newColIndex) <= newColBoundX;
	   newColIndex++)
	;
      if (newColIndex == 0)
	throw new BadEdit("new column boundary is entirely before the first column");
      // add the new boundary
      colBoundXs.add(newColIndex, newColBoundX);
      // rerun tabula (without first boundary because that would make a blank
      // column at the beginning)
      technology.tabula.Table tabulaTable =
	extractTabulaTable(origin, colBoundXs.subList(1, colBoundXs.size()));
      try { // do some sanity checks before setting the table
	// check that it actually added a column
	int expInitNumCols = colBoundXs.size();
	int actInitNumCols = tabulaTable.getColCount();
	if (expInitNumCols != actInitNumCols)
	  throw new BadEdit("wrong initial column count; expected " + expInitNumCols + " but got " + actInitNumCols);
	// check for empty column on either side of new boundary
	boolean prevEmpty = true, nextEmpty = true;
	for (List<RectangularTextContainer> row : tabulaTable.getRows()) {
	  RectangularTextContainer prevCell = row.get(newColIndex-1);
	  if (!Cell.isEmpty(prevCell)) prevEmpty = false;
	  RectangularTextContainer nextCell = row.get(newColIndex);
	  if (!Cell.isEmpty(nextCell)) nextEmpty = false;
	}
	if (prevEmpty)
	  throw new BadEdit("empty column before new column boundary");
	if (nextEmpty)
	  throw new BadEdit("empty column after new column boundary");
      } catch (BadEdit ex) {
	colBoundXs.remove(newColIndex); // undo adding the new boundary
	throw ex;
      }
      setTabulaTable(tabulaTable);
      // reapply the rest of history to the newly extracted table, after
      // adjusting for the new column, and adjusting the new column index after
      // each step
      for (Edit e : history) {
	e.insertColumn(newColIndex);
	e.apply();
	newColIndex = e.applyToColIndex(newColIndex);
      }
    }
    @Override public KQMLObject toKQML() {
      KQMLPerformative p = new KQMLPerformative(kqmlVerb);
      p.setParameter(":at-x", Float.toString(newColBoundX));
      return p;
    }
  }
  public SplitColumn splitColumnFromKQML(KQMLPerformative perf) throws CWCException {
    // NOTE: Double because Args doesn't do Float
    double newColBoundX = Args.getTypedArgument(perf, ":at-x", Double.class);
    return new SplitColumn((float)newColBoundX);
  }
  public SplitColumn splitColumnFromSelection(TableSelection sel) throws BadEdit {
    throw new BadEdit("can't make a SplitColumn edit from a selection");
  }

  /** Edit the CellProperties of a cell (wrapping it in EditedCell if
   * necessary).
   */
  public class EditCell extends Edit {
    public final static String kqmlVerb = "edit-cell";
    public final static String buttonLabel = "Edit Cell";
    public final int row;
    public int col; // not final because SplitColumn might happen
    public CellProperties props;
    public PDFExtractor module;
    public EditCell(int row, int col, CellProperties props) {
      this.row = row;
      this.col = col;
      this.props = props;
      module = null;
    }
    public EditCell(int row, int col) { this(row, col, null); }
    @Override public void apply() throws CWCException, BadEdit {
      if (props == null) { // no properties yet, open a dialog to get them
	if (module == null)
	  throw new RuntimeException("expected module to be set in EditCell before apply() with props==null, but got module==null");
	RectangularTextContainer cell = getCellAt(row, col);	
	CellProperties.Editor editor = Cell.getEditorOf(cell);
	editor.setContext(Table.this, row, col, Cell.getSpanOf(cell));
	props = editor.getProperties();
	JFrame tableWindow = (JFrame)
	  module.tableModel2view.get(Table.this).getTopLevelAncestor(); // ouch.
	JDialog dialog =
	  new JDialog(tableWindow,
		      "edit cell " + Cell.cellIndexToRef(row, col) +
		      " of " + id,
		      true);
	// verify the last focused field and report this edit when the dialog
	// closes
	dialog.addWindowListener(new WindowAdapter() {
	  @Override public void windowClosing(WindowEvent evt) {
	    Component field = dialog.getMostRecentFocusOwner();
	    if (field instanceof JTextComponent) {
	      JTextComponent textField = (JTextComponent)field;
	      InputVerifier v = textField.getInputVerifier();
	      if (v != null)
		v.shouldYieldFocus(textField);
	    }
	    module.reportEdit(EditCell.this, false);
	    fireTableCellUpdated(row, col);
	  }
	});
	dialog.add(editor);
	dialog.pack();
	dialog.setVisible(true);
      }
      setCellAt(row, col, Cell.setPropertiesOf(getCellAt(row, col), props));
    }
    public void insertColumn(int newCol) {
      if (newCol < col) col++;
      if (props.headingFor != null) {
	int newFirstCol = props.headingFor.firstCol;
	int newLastCol = props.headingFor.lastCol;
	if (newCol < newFirstCol) newFirstCol++;
	if (newCol < newLastCol) newLastCol++;
	if (newFirstCol != props.headingFor.firstCol ||
	    newLastCol != props.headingFor.lastCol) {
	  props.headingFor =
	    new TableSelection(Table.this,
			       props.headingFor.firstRow, newFirstCol,
			       props.headingFor.lastRow, newLastCol);
	}
      }
    }
    public void deleteColumn(int oldCol) {
      // TODO? throw if ==
      if (oldCol < col) col--;
      if (props.headingFor != null) {
	TableSelection newHeadingFor =
	  props.headingFor.getAdjusted(oldCol, oldCol, true, true);
	if (newHeadingFor.equals(props.headingFor)) return; // no change
	props.headingFor = newHeadingFor;
	if (props.headingFor == null) // lost heading-ness, revert to data cell
	  props.type = CellProperties.Type.DATA;
      }
    }
    @Override public KQMLObject toKQML() {
      KQMLPerformative p = new KQMLPerformative(kqmlVerb);
      p.setParameter(":row", Integer.toString(row));
      p.setParameter(":column", Integer.toString(col));
      if (props != null)
	props.toKQML(p);
      return p;
    }
  }
  public EditCell editCellFromKQML(KQMLPerformative perf) throws CWCException {
    int row = Args.getTypedArgument(perf, ":row", Integer.class);
    int col = Args.getTypedArgument(perf, ":column", Integer.class);
    if (row < 0 || row >= numRows)
      throw new InvalidArgument(perf, ":row", "integer in [0," + numRows + ")");
    if (col < 0 || col >= numCols)
      throw new InvalidArgument(perf, ":column", "integer in [0," + numCols + ")");
    CellProperties props = CellProperties.fromKQML(this, perf);
    return new EditCell(row, col, props);
  }
  public EditCell editCellFromSelection(TableSelection sel) throws BadEdit {
    if (!sel.isOneCell())
      throw new BadEdit("expected exactly one selected cell");
    return new EditCell(sel.firstRow, sel.firstCol);
  }

  /** Edit the CellProperties of multiple cells (wrapping them in EditedCells
   * if necessary).
   */
  public class EditCells extends Edit {
    public final static String kqmlVerb = "edit-cells";
    public final static String buttonLabel = "Edit Cells";
    public final int firstRow, lastRow;
    public int firstCol, lastCol; // not final because SplitColumn might happen
    public CellProperties props;
    public PDFExtractor module;
    public EditCells(int firstRow, int firstCol, int lastRow, int lastCol, CellProperties props) {
      this.firstRow = firstRow;
      this.firstCol = firstCol;
      this.lastRow = lastRow;
      this.lastCol = lastCol;
      this.props = props;
      module = null;
    }
    public EditCells(int firstRow, int firstCol, int lastRow, int lastCol) {
      this(firstRow, firstCol, lastRow, lastCol, null);
    }
    @Override public void apply() throws CWCException, BadEdit {
      if (props == null) { // no properties yet, open a dialog to get them
	if (module == null)
	  throw new RuntimeException("expected module to be set in EditCells before apply() with props==null, but got module==null");
	CellProperties.Editor editor = CellProperties.getEditor();
	props = editor.getProperties();
	JFrame tableWindow = (JFrame)
	  module.tableModel2view.get(Table.this).getTopLevelAncestor(); // ouch.
	JDialog dialog =
	  new JDialog(tableWindow,
		      "edit cells " + Cell.cellIndexToRef(firstRow, firstCol) +
		      "-" + Cell.cellIndexToRef(lastRow, lastCol) +
		      " of " + id,
		      true);
	// verify the last focused field, clone the properties to each cell,
	// and report this edit, when the dialog closes
	dialog.addWindowListener(new WindowAdapter() {
	  @Override public void windowClosing(WindowEvent evt) {
	    Component field = dialog.getMostRecentFocusOwner();
	    if (field instanceof JTextComponent) {
	      JTextComponent textField = (JTextComponent)field;
	      InputVerifier v = textField.getInputVerifier();
	      if (v != null)
		v.shouldYieldFocus(textField);
	    }
	    // do the else branch now that we have props set
	    try {
	      apply();
	    } catch (CWCException ex) {
	      throw new RuntimeException("this should never happen", ex);
	    } catch (BadEdit ex) {
	      throw new RuntimeException("this should never happen", ex);
	    }
	    // report the change to TRIPS and to any TableModelListeners
	    module.reportEdit(EditCells.this, false);
	    fireTableDataChanged();
	  }
	});
	dialog.add(editor);
	dialog.pack();
	dialog.setVisible(true);
      } else { // have properties already, just apply them
	// set properties of each cell in the range using a clone of props
	// that has headingFor set to the default for that cell, and has
	// newText set so that the content doesn't change
	for (int i = firstRow; i <= lastRow; i++) {
	  for (int j = firstCol; j <= lastCol; j++) {
	    RectangularTextContainer cell = getCellAt(i, j);
	    CellProperties op = Cell.getPropertiesOf(cell);
	    CellProperties p = props.clone();
	    p.newText = op.newText;
	    p.headingFor =
	      p.getDefaultHeadingFor(Table.this, i, j, getSpanAt(i, j));
	    setCellAt(i, j, Cell.setPropertiesOf(cell, p));
	  }
	}
      }
    }
    public void insertColumn(int newCol) {
      if (newCol < firstCol) firstCol++;
      if (newCol < lastCol) lastCol++;
    }
    public void deleteColumn(int oldCol) {
      // TODO? throw if ==
      if (oldCol < firstCol) firstCol--;
      if (oldCol < lastCol) lastCol--;
    }
    @Override public KQMLObject toKQML() {
      KQMLPerformative p = new KQMLPerformative(kqmlVerb);
      p.setParameter(":first-row", Integer.toString(firstRow));
      p.setParameter(":first-column", Integer.toString(firstCol));
      p.setParameter(":last-row", Integer.toString(lastRow));
      p.setParameter(":last-column", Integer.toString(lastCol));
      if (props != null)
	props.toKQML(p);
      return p;
    }
  }
  public EditCells editCellsFromKQML(KQMLPerformative perf) throws CWCException {
    int firstRow = Args.getTypedArgument(perf, ":first-row", Integer.class);
    int firstCol = Args.getTypedArgument(perf, ":first-column", Integer.class);
    int lastRow = Args.getTypedArgument(perf, ":last-row", Integer.class);
    int lastCol = Args.getTypedArgument(perf, ":last-column", Integer.class);
    if (firstRow < 0 || firstRow >= numRows)
      throw new InvalidArgument(perf, ":first-row", "integer in [0," + numRows + ")");
    if (firstCol < 0 || firstCol >= numCols)
      throw new InvalidArgument(perf, ":first-column", "integer in [0," + numCols + ")");
    if (lastRow < 0 || lastRow >= numRows)
      throw new InvalidArgument(perf, ":last-row", "integer in [0," + numRows + ")");
    if (lastCol < 0 || lastCol >= numCols)
      throw new InvalidArgument(perf, ":last-column", "integer in [0," + numCols + ")");
    CellProperties props = CellProperties.fromKQML(this, perf);
    props.newText = null;
    props.headingFor = null;
    return new EditCells(firstRow, firstCol, lastRow, lastCol, props);
  }
  public EditCells editCellsFromSelection(TableSelection sel) throws BadEdit {
    if (sel.isEmpty() || sel.isOneCell())
      throw new BadEdit("expected more than one selected cell");
    return new EditCells(sel.firstRow, sel.firstCol, sel.lastRow, sel.lastCol);
  }

  /* TODO?
   * - add rows/columns from KQML instead of PDF
   * - sort rows with a Comparator
   * - filter rows with a test function (no standard java interface for that?)
   */
}
