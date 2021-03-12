package TRIPS.PDFExtractor;

import java.util.ArrayList;
import java.util.List;
import java.awt.geom.Rectangle2D;
import technology.tabula.TextElement;

/** A Cell that comes not from a region of a PDF page, but rather from a KQML
 * request.
 */
public class SyntheticCell extends Cell {
  String text;

  /** Make a cell at cell position i,j in a fake grid with 10px by 10px cells.
   */
  public SyntheticCell(int i, int j, String text) {
    super(i * 10.0, j * 10.0, 10.0, 10.0);
    this.text = text;
  }

  public SyntheticCell(String text) {
    this(0, 0, text);
  }

  public SyntheticCell() {
    this("");
  }

  @Override public String getText() { return text; }

  @Override
  public List<TextElement> getTextElements() {
    return new ArrayList<TextElement>();
  }

  @Override
  public List<Rectangle2D.Float> getLineRects() {
    List<Rectangle2D.Float> ret = new ArrayList<Rectangle2D.Float>(1);
    ret.add((Rectangle2D.Float)this);
    return ret;
  }

  @Override
  public HTMLBuilder getHTML(HTMLBuilder out) {
    return out.text(text);
  }
}
