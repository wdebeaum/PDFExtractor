package TRIPS.PDFExtractor;

import java.util.ArrayList;
import java.util.List;
import java.awt.geom.Rectangle2D;
import technology.tabula.RectangularTextContainer;
import technology.tabula.TextElement;

/** Cell-like thing derived from a Region origin, but not part of the table
 * proper. A caption or footnote.
 */
public class Note extends Cell {
  public final Region origin;

  public Note(Region origin) {
    super(origin.getY1(), origin.getX1(),
	  origin.getSignedWidth(), origin.getSignedHeight());
    this.origin = origin;
  }

  // HasText
  @Override public String getText() { return origin.getText(); }

  // RTC<TE>
  @SuppressWarnings("unchecked")
  @Override public List<TextElement> getTextElements() {
    // FIXME this cast might not always work
    return ((RectangularTextContainer<TextElement>)(origin.rect)).getTextElements();
  }

  // HasHTML
  @Override public HTMLBuilder getHTML(HTMLBuilder out) {
    return origin.getHTML(out);
  }

  // Cell
  @Override public List<Rectangle2D.Float> getLineRects() {
    // see also SyntheticCell
    List<Rectangle2D.Float> ret = new ArrayList<Rectangle2D.Float>(1);
    ret.add((Rectangle2D.Float)this);
    return ret;
  }
}
