package TRIPS.PDFExtractor;

import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import technology.tabula.HasText;
import technology.tabula.RectangularTextContainer;
import technology.tabula.TextChunk;
import technology.tabula.TextElement;

/** Replacement for the TextChunk cells we get from Tabula and the .merge()
 * method, which properly merges the text contents of the subcells, joining
 * them with spaces and (text or HTML) line breaks. Also translates
 * superscripts to &lt;sup&gt;s.
 */
public class MergedCell extends Cell {
  List<HasText> textElements; // NOTE: not actually TextElement objects
  /** Is the merger happening horizontally? i.e. textElements are arranged left-right; otherwise top-bottom */
  boolean horizontally;
  /** Is the merger just happening for this cell? otherwise for the whole row/column (depending on horizontally). */
  boolean individually;

  /** Make a MergedCell containing the given textElements, merged according to
   * the flags horizontally and individually.
   */
  public MergedCell(List<HasText> textElements, boolean horizontally, boolean individually) {
    // Ugghhhh, java y u no let super come later?
    super(aggregateTop(textElements), aggregateLeft(),
          aggregateWidth(), aggregateHeight());
    this.textElements = textElements;
    this.horizontally = horizontally;
    this.individually = individually;
  }

  /** Make a degenerate MergedCell with no textElements and zero area at the
   * given coordinates.
   */
  public MergedCell(float left, float top) {
    super(top, left, 0, 0);
    this.textElements = new ArrayList<HasText>(0);
    this.horizontally = true; // so we make no newlines
    this.individually = true;
  }

  /** Like the constructor, but takes a List of RTCs (which we secretly know
   * are also HasTexts, because they're either TextChunks or other MergedCells)
   * instead of a List of HasTexts.
   */
  public static MergedCell fromRTCs(List<RectangularTextContainer> textElements, boolean horizontally, boolean individually) {
    List<HasText> copy = new ArrayList<HasText>(textElements.size());
    for (RectangularTextContainer rtc : textElements) {
      copy.add((HasText)rtc);
    }
    return new MergedCell(copy, horizontally, individually);
  }

  //// Cell ////

  @Override
  public List<Rectangle2D.Float> getLineRects() {
    List<Rectangle2D.Float> ret = new ArrayList<Rectangle2D.Float>();
    if (horizontally) {
      // recurse on textElements and merge resulting rectangles on the same line
      for (HasText e : textElements) {
	if (e instanceof RectangularTextContainer) {
	  List<Rectangle2D.Float> subLines =
	    getLineRectsOf((RectangularTextContainer)e);
	  for (int i = 0; i < subLines.size(); i++) {
	    Rectangle2D.Float subLine = subLines.get(i);
	    if (i >= ret.size()) {
	      ret.add(subLine);
	    } else {
	      Rectangle2D.Float oldLine = ret.get(i);
	      Rectangle2D.Float newLine =
	        (Rectangle2D.Float)oldLine.createUnion(subLine);
	      ret.set(i, newLine);
	    }
	  }
	}
      }
    } else { // vertically
      // recurse on textElements and add the results to one big list of lines
      for (HasText e : textElements) {
	if (e instanceof RectangularTextContainer) {
	  ret.addAll(getLineRectsOf((RectangularTextContainer)e));
	}
      }
    }
    return ret;
  }

  @Override public List<TextElement> getTextElements() {
    //return textElements; // this is a List<HasText> :(
    throw new RuntimeException("do I even really need this function?");
  }

  @Override public String getText() {
    return getHTML().toTextString();
  }

  @Override
  public HTMLBuilder getHTML(HTMLBuilder out) {
    List<String> frags = new ArrayList<String>(textElements.size());
    for (HasText element : textElements) {
      frags.add(getHTMLOf(element).toFragmentString());
    }
    if (horizontally) {
      List<List<String>> rows = new ArrayList<List<String>>();
      for (String frag : frags) {
	String[] lines = frag.split("<br/>");
	int i = 0;
	for (String line : lines) {
	  if (rows.size() <= i)
	    rows.add(new ArrayList<String>());
	  rows.get(i).add(line);
	  i++;
	}
      }
      // get the line rectangles for each element
      // NOTE: This is transposed from the normal row,col order to col,row.
      List<List<Rectangle2D.Float>> subLineRects =
        new ArrayList<List<Rectangle2D.Float>>(textElements.size());
      for (HasText e : textElements) {
	if (e instanceof RectangularTextContainer) {
	  subLineRects.add(getLineRectsOf((RectangularTextContainer)e));
	}
      }
      List<String> mergedLines = new ArrayList<String>(rows.size());
      int i = 0;
      for (List<String> row : rows) {
	// join row into a single string, with spaces depending on
	// isSpaceBetween the corresponding rectangles
	StringBuilder line = new StringBuilder();
	int j = 0;
	for (String subLine : row) {
	  if (j > 0) {
	    Rectangle2D.Float left = subLineRects.get(j-1).get(i);
	    Rectangle2D.Float right = subLineRects.get(j).get(i);
	    if (isSpaceBetween(left, right)) {
	      line.append(' ');
	    }
	  }
	  line.append(subLine);
	  j++;
	}
	mergedLines.add(line.toString());
	i++;
      }
      frags = mergedLines;
    } // else vertically
    out.lines(frags);
    return out;
  }

  @Override
  public Dimension getSpan() {
    Dimension d = new Dimension(1,1);
    if (textElements.isEmpty()) {
      d.width = 0;
      d.height = 0;
    } else if (individually) {
      if (horizontally) {
	// take the total width and the minimum height
	d.width = 0;
	d.height = getSpanOf(textElements.get(0)).height;
	for (HasText e : textElements) {
	  Dimension s = getSpanOf(e);
	  d.width += s.width;
	  if (s.height < d.height) { d.height = s.height; }
	}
      } else { // vertically
        // take the minimum width and the total height
	d.width = getSpanOf(textElements.get(0)).width;
	d.height = 0;
	for (HasText e : textElements) {
	  Dimension s = getSpanOf(e);
	  if (s.width < d.width) { d.width = s.width; }
	  d.height += s.height;
	}
      }
    } else { // !individually (whole rows or columns were merged)
      // TODO what? (this doesn't actually come up because we disallow merging whole rows/columns that include individually merged cells)
    }
    return d;
  }

  @Override
  public boolean wasMergedIndividually() { return individually; }

  @Override public CellProperties getProperties() {
    List<CellProperties> propses =
      new ArrayList<CellProperties>(textElements.size());
    for (HasText e : textElements) {
      propses.add(Cell.getPropertiesOf(e));
    }
    return CellProperties.merge(propses);
  }

  @Override
  public void adjustHeadingFor(int first, int last, boolean horizontal, boolean delete) {
    for (HasText e : textElements) {
      Cell.adjustHeadingForOf(e, first, last, horizontal, delete);
    }
  }

  // "Ugghhhh" cont'd
  private static Rectangle2D.Float aggregate;
  private static float aggregateTop(List<HasText> elems) {
    aggregate = new Rectangle2D.Float();
    aggregate.setRect((RectangularTextContainer)elems.get(0));
    for (HasText e : elems.subList(1, elems.size())) {
      aggregate.setRect(aggregate.createUnion((RectangularTextContainer)e));
    }
    return (float)aggregate.getY();
  }
  private static float aggregateLeft() { return (float)aggregate.getX(); }
  private static float aggregateWidth() { return (float)aggregate.getWidth(); }
  private static float aggregateHeight() { return (float)aggregate.getHeight();}
}
