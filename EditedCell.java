package TRIPS.PDFExtractor;

import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.util.List;
import technology.tabula.RectangularTextContainer;
import technology.tabula.TextElement;

/** A cell whose properties have been edited. */
public class EditedCell extends Cell {
  /** The original, unedited cell that this EditedCell wraps. */
  RectangularTextContainer<TextElement> original;
  CellProperties properties;

  public EditedCell(RectangularTextContainer orig) {
    super(orig.getTop(), orig.getLeft(), orig.getWidth(), orig.getHeight());
    // we actually *can't* check this generic cast, and we also can't apply
    // SuppressWarnings to any old assignment, so we have to make a local
    // variable and apply it to that (why, java, why?)
    @SuppressWarnings("unchecked")
    RectangularTextContainer<TextElement> origTE = 
      (RectangularTextContainer<TextElement>)orig;
    original = origTE;
    properties = new CellProperties();
  }

  public CellProperties.Editor getEditor() {
    return properties.getEditor(original);
  }

  //// Cell/RTC/HasText ////

  @Override
  public List<Rectangle2D.Float> getLineRects() {
    return Cell.getLineRectsOf(original);
  }

  @Override
  public String getText() {
    if (properties.newText != null)
      return properties.newText;
    return original.getText();
  }

  @Override
  public List<TextElement> getTextElements() {
    return original.getTextElements();
  }

  @Override
  public HTMLBuilder getHTML(HTMLBuilder out) {
    if (properties.newText != null) {
      out.textLines(properties.newText);
    } else {
      Cell.getHTMLOf(original, out);
    }
    return out;
  }

  @Override
  public Dimension getSpan() { return Cell.getSpanOf(original); }

  @Override
  public CellProperties getProperties() {
    return properties;
  }

  @Override
  public EditedCell setProperties(CellProperties props) {
    properties = props;
    return this;
  }

  @Override
  public void adjustHeadingFor(int first, int last, boolean horizontal, boolean delete) {
    if (properties.headingFor == null) return;
    TableSelection newHeadingFor = properties.headingFor.getAdjusted(first, last, horizontal, delete);
    CellProperties.Type newType = properties.type;
    if (newHeadingFor == null) { // lost heading-ness, revert to data cell
      newType = CellProperties.Type.DATA;
    } else if (newHeadingFor.equals(properties.headingFor)) { // no change
      return;
    }
    // clone properties so we don't accidentally change the version in an
    // EditCell table edit
    properties = properties.clone();
    properties.type = newType;
    properties.headingFor = newHeadingFor;
  }

}
