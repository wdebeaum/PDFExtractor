package TRIPS.PDFExtractor;

import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.*;
import technology.tabula.HasText;
import technology.tabula.RectangularTextContainer;
import technology.tabula.TextChunk;
import technology.tabula.TextElement;

/** Replacement for Tabula's TextChunk (not Cell) for when we need some extra
 * features it doesn't have (defined in subclasses). Otherwise we use
 * TextChunk/RectangularTextContainer/HasText directly.
 *
 * Many methods of Cell, call them getFoo(), have a corresponding static method
 * getFooOf() that performs the same semantic operation on its argument,
 * whether it is a Cell or something else (usually a TextChunk).  Javadoc is
 * provided only for the non-Of version.
 */
public abstract class Cell extends RectangularTextContainer<TextElement> implements HasHTML {
  public Cell(float top, float left, float width, float height) {
    super(top, left, width, height);
  }

  // ugh, java, just shut up about lossy conversions already; they're all integers anyway!
  public Cell(double top, double left, double width, double height) {
    this((float)top, (float)left, (float)width, (float)height);
  }

  /** Get the rectangle bounding each line of the text content of this cell. */
  public abstract List<Rectangle2D.Float> getLineRects();

  public static List<Rectangle2D.Float> getLineRectsOf(RectangularTextContainer rtc) {
    if (rtc instanceof Cell) {
      return ((Cell)rtc).getLineRects();
    } else {
      List<Rectangle2D.Float> ret = new ArrayList<Rectangle2D.Float>(1);
      ret.add((Rectangle2D.Float)rtc);
      return ret;
    }
  }
  
  // Tabula doesn't do this right, why should we?
  @Override public String getText(boolean useLineReturns) { return null; }

  // NOTE: This takes Object instead of HasText because
  // RectangularTextContainer doesn't implement HasText, even though it has
  // getText() and all of its relevant concrete subclasses implement HasText.
  // Wat?
  public static String getTextOf(Object cell) {
    String text;
    if (cell instanceof Cell) {
      return ((Cell)cell).getText(); // return early to avoid removing \n
    } else if (cell instanceof HasText) {
      text = ((HasText)cell).getText();
    } else if (cell instanceof RectangularTextContainer) {
      text = ((RectangularTextContainer)cell).getText();
    } else if (cell == null) { // this case comes up when we edit a new caption
      text = "";
    } else {
      throw new IllegalArgumentException("expected a Cell, HasText, or RectangularTextContainer, or null, but got a " + cell.getClass().getName());
    }
    // get rid of line breaks etc. within individual unmerged cells
    return text.replaceAll("\\s", " ");
  }

  public HTMLBuilder getHTML() { return getHTML(new HTMLBuilder()); }

  public static HTMLBuilder getHTMLOf(Object o, HTMLBuilder out) {
    if (o instanceof HasHTML) {
      ((HasHTML)o).getHTML(out);
    } else if (o instanceof TextChunk) {
      // detect superscripts and turn them into <sup>
      TextChunk chunk = (TextChunk)o;
      TextElement prev = null;
      StringBuilder supText = null;
      for (TextElement element : chunk.getTextElements()) {
	String text = getTextOf(element);
	if (supText != null) { // in superscript, prev is last before sup
	  if (isSuperscript(prev, element)) {
	    supText.append(text); // continue superscript
	  } else { // end superscript
	    out.sup(supText.toString());
	    supText = null;
	    out.text(text);
	    prev = element;
	  }
	} else if (prev != null && isSuperscript(prev, element)) {
	  // start superscript
	  supText = new StringBuilder(text);
	} else {
	  out.text(text);
	  prev = element;
	}
      }
      if (supText != null) // superscript at end of chunk
	out.sup(supText.toString());
    } else {
      out.text(getTextOf(o)); // TODO? use textLines() instead of text()
    }
    return out;
  }
  public static HTMLBuilder getHTMLOf(Object o) {
    return getHTMLOf(o, new HTMLBuilder());
  }

  // this definition of toString helps JList<Cell> show cell contents with
  // superscripts etc.
  @Override public String toString() { return getHTML().toDocumentString(); }

  /** Get the colspan/rowspan of this (individually?) merged cell. */
  public Dimension getSpan() {
    return new Dimension(1,1);
  }

  /** Like e.getSpan(), but works on all types of cell. */
  public static Dimension getSpanOf(Object e) {
    if (e instanceof Cell) {
      return ((Cell)e).getSpan();
    } else {
      return new Dimension(1,1);
    }
  }

  /** Was the given cell merged individually? This is effectively
   * getIndividually(), but that didn't sound right to me.
   */
  public boolean wasMergedIndividually() { return false; }

  // again, wasMergedIndividuallyOf() doesn't sound right
  public static boolean wasMergedIndividually(HasText e) {
    if (e instanceof Cell) {
      return ((Cell)e).wasMergedIndividually();
    } else {
      return false;
    }
  }

  /** Is there enough horizontal space between the subline rectangles left and
   * right that we should join their texts with a space character?
   */
  public static boolean isSpaceBetween(Rectangle2D.Float left, Rectangle2D.Float right) {
    double avgHeight = (left.getHeight() + right.getHeight()) / 2;
    double hSep = right.getMinX() - left.getMaxX();
    // TODO use TextElement#getWidthOfSpace() instead?
    return (hSep > avgHeight / 6); // more than a "thin space"
  }

  /** Is right plausibly a superscript of left (given they are horizontally
   * adjacent)? Does right seem to use a smaller font, and have a significantly
   * higher bottom edge? (Or is right some kind of dash indicating a negation
   * sign, use a different-size font (space not necessarily narrower), and have
   * a significantly higher bottom edge?)
   */
  public static boolean isSuperscript(TextElement left, TextElement right) {
    // NOTE: getFontSize() doesn't take into account some transformation
    // matrices from the PDF document; we use getWidthOfSpace() as a proxy for
    // font size instead (apparently it does take those matrices into account?)
    float leftSize = left.getWidthOfSpace(); //getFontSize();
    float rightSize = right.getWidthOfSpace(); //getFontSize();
    float leftBottom = left.getBottom();
    float rightBottom = right.getBottom();
    // see also $dash_re in ../TextTagger/Perl/TextTagger/Normalize.pm
    boolean isDash = right.getText().matches("[\\x{2010}-\\x{2015}\\x{2212}-]");
    return ((isDash ? (leftSize != rightSize) : (leftSize > rightSize)) &&
            // right's bottom edge at least width-of-space higher than left's?
	    (leftBottom > rightBottom + leftSize));
  }

  /** Does the cell have no area? */
  public static boolean isEmpty(RectangularTextContainer cell) {
    return (cell.getWidth() <= 0);
  }

  /** Does the cell contain only whitespace characters? */
  public static boolean isBlank(RectangularTextContainer cell) {
    String text = getTextOf(cell);
    System.err.println("cell text = \"" + text + "\" (length = " + text.length() + ")");
    return !Pattern.compile("\\S").matcher(getTextOf(cell)).find();
  }

  /** Get the CellProperties of this cell. Never returns null, even for cells
   * without explicit properties.
   */
  public CellProperties getProperties() {
    return new CellProperties();
  }

  public static CellProperties getPropertiesOf(Object o) {
    if (o instanceof Cell) {
      return ((Cell)o).getProperties();
    } else {
      return new CellProperties();
    }
  }

  /** Set the properties of this cell, wrapping it if necessary. Does not
   * modify the cell's properties object, just replaces it.
  */
  public EditedCell setProperties(CellProperties props) {
    return new EditedCell(this).setProperties(props);
  }

  public static EditedCell setPropertiesOf(RectangularTextContainer rtc, CellProperties props) {
    if (rtc instanceof Cell) {
      return ((Cell)rtc).setProperties(props);
    } else {
      return new EditedCell(rtc).setProperties(props);
    }
  }

  /** Adjust the headingFor property (if present) for a
   * (Delete/Merge)(Rows/Columns) edit. Arguments are the same as
   * Table#adjustRulings().
   */
  public void adjustHeadingFor(int first, int last, boolean horizontal, boolean delete) {}

  public static void adjustHeadingForOf(Object o, int first, int last, boolean horizontal, boolean delete) {
    if (o instanceof Cell)
      ((Cell)o).adjustHeadingFor(first, last, horizontal, delete);
  }

  public CellProperties.Editor getEditor(boolean isNote) {
    return getProperties().getEditor(this, isNote);
  }

  public static CellProperties.Editor getEditorOf(RectangularTextContainer rtc, boolean isNote) {
    if (rtc instanceof Cell) {
      return ((Cell)rtc).getEditor(isNote);
    } else {
      // ugh.
      @SuppressWarnings("unchecked")
      RectangularTextContainer<TextElement> rtcTE =
        (RectangularTextContainer<TextElement>)rtc;
      return new CellProperties().getEditor(rtcTE, isNote);
    }
  }
  
  // some more static stuff for dealing with textual row/col/cell references

  /** Convert a (0-based) row index to a string referring to the row by its
   * (1-based) row number.
   */
  public static String rowIndexToRef(int rowIndex) {
    return Integer.toString(rowIndex + 1);
  }

  /** Inverse of rowIndexToRef. */
  public static int rowRefToIndex(String rowRef) {
    return Integer.parseInt(rowRef) - 1;
  }

  /** Convert a (0-based) column index to a string referring to the column by
   * its alphabetic name. 0 becomes "A", 25 becomes "Z", 26 becomes "AA", etc.
   */
  public static String colIndexToRef(int colIndex) {
    // convert colIndex to base 26, using 0-9a-p
    String base26 = Integer.toString(colIndex, 26);
    // then convert that to use all letters instead, A-Z
    StringBuilder h = new StringBuilder(base26.length());
    for (int i = 0; i < base26.length(); i++) {
      char c = base26.charAt(i);
      if (c >= '0' && c <= '9') { // digit
	c = (char)((int)c - (int)'0' + (int)'A');
      } else { // letter
        c = (char)((int)c - (int)'a' + (int)'A' + 10);
      }
      h.append(c);
    }
    return h.toString();
  }

  /** Inverse of colIndexToRef. */
  public static int colRefToIndex(String colRef) {
    colRef = colRef.toUpperCase();
    // convert ref from A-Z to 0-9a-p
    StringBuilder base26 = new StringBuilder(colRef.length());
    for (int i = 0; i < colRef.length(); i++) {
      char c = colRef.charAt(i);
      if (c >= 'A' && c <= 'J') { // digit
	c = (char)((int)c - (int)'A' + (int)'0');
      } else if (c >= 'K' && c <= 'Z') { // letter
        c = (char)((int)c - (int)'A' - 10 + (int)'a');
      } else {
	throw new NumberFormatException("expected A-Z but got " + c);
      }
      base26.append(c);
    }
    return Integer.parseInt(base26.toString(), 26);
  }

  /** Convert (0-based) row and column indices to a string referring to the
   * cell by its alphanumeric name. (0,0) becomes "A1", (10,3) becomes "C10",
   * etc.
   */
  public static String cellIndexToRef(int rowIndex, int colIndex) {
    return colIndexToRef(colIndex) + rowIndexToRef(rowIndex);
  }

  /** Inverse of cellIndexToRef. Returns a 2-element array of int, in (row,col)
   * order.
   */
  public static int[] cellRefToIndex(String cellRef) {
    cellRef = cellRef.toUpperCase();
    Matcher m = Pattern.compile("^([A-Z]+)([0-9]+)$").matcher(cellRef);
    if (!m.matches())
      throw new NumberFormatException("expected A-Z followed by 0-9, but got " + cellRef);
    int[] r = new int[2];
    r[0] = rowRefToIndex(m.group(2));
    r[1] = colRefToIndex(m.group(1));
    return r;
  }
}
